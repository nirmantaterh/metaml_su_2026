package com.metaml.workbench.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

// Starts a project SpringBootProjectGenerator assembled on disk as its own child JVM. Kept separate
// from the generator because file assembly is trivially unit-testable and starting a real JVM is not.
// Several generated apps can run at once, so ports are assigned automatically rather than fixed.
@Component
public class SpringBootProjectLauncher {

    private static final Logger logger = LoggerFactory.getLogger(SpringBootProjectLauncher.class);

    // Camunda bootstrap plus a cold Maven resolution can take minutes on a machine that has not built
    // this project before, and a large model has more to deploy - hence minutes, not seconds.
    private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofMinutes(5);

    private final Map<String, Running> running = new ConcurrentHashMap<>();

    // One lock per projectId rather than ConcurrentHashMap.compute() on `running`: compute() holds the
    // map's bin lock for the whole mapping function, which here spawns a child JVM and waits out the
    // readiness timeout, so an unrelated projectId hashing into the same bin would block behind it.
    private final Map<String, ReentrantLock> launchLocks = new ConcurrentHashMap<>();
    private final Duration readyTimeout;

    public SpringBootProjectLauncher() {
        this(DEFAULT_READY_TIMEOUT);
    }

    // package-private, not @Value-configurable - this exists so a test can prove the real timeout path without waiting out the real 5 minutes, not so an operator tunes it
    SpringBootProjectLauncher(Duration readyTimeout) {
        this.readyTimeout = readyTimeout;
    }

    private record Running(LaunchedProject info, Process process) {
    }

    // stop -> findFreePort -> startProcess -> put has to be atomic per projectId. As separate steps two
    // concurrent launches of the same id could both spawn a JVM, and the second put() would orphan the
    // first - still running, unreachable through find(), holding its port until shutdown.
    public LaunchedProject launch(GeneratedProject project) {
        return launch(project, Map.of());
    }

    // extraEnv is additive on top of the SERVER_PORT/SERVER_ADDRESS this always sets, for a caller that
    // needs a property the generated project leaves at its default (e.g. METAML_MESSAGING_ENABLED=true).
    public LaunchedProject launch(GeneratedProject project, Map<String, String> extraEnv) {
        ReentrantLock lock = lockFor(project.projectId());
        lock.lock();
        try {
            // re-launching a project id that's already running would otherwise leak the old process and its port, orphaned with nothing left pointing at it
            stop(project.projectId());

            int port = findFreePort();
            Process process;
            try {
                process = startProcess(project.directory(), port, extraEnv);
            } catch (RuntimeException e) {
                throw attachPort(e, port);
            }
            try {
                awaitReady(process, project.directory(), port, readyTimeout);
            } catch (RuntimeException e) {
                destroyTree(process);
                throw attachPort(e, port);
            }

            // modelId filled in by WorkbenchServiceImpl, not here - this class only ever sees a GeneratedProject, which has no notion of "model"
            LaunchedProject info = new LaunchedProject(project.projectId(), project.processKey(), port,
                    Instant.now(), null, project.displayName());
            running.put(project.projectId(), new Running(info, process));
            logger.info("Launched generated project {} (process key '{}') on port {}",
                    project.projectId(), project.processKey(), port);
            return info;
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(String projectId) {
        return launchLocks.computeIfAbsent(projectId, id -> new ReentrantLock());
    }

    // Runs `action` only while this projectId is provably idle - neither running nor mid-launch - under
    // the same lock launch() takes, so the answer cannot go stale between the check and the action.
    // The in-flight half is why this exists: launch() only publishes into `running` after awaitReady
    // returns, so for minutes find() honestly reports "not running" while a JVM is still booting.
    public boolean runIfIdle(String projectId, Runnable action) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        return runIfAllIdle(List.of(projectId), action);
    }

    // All-or-nothing form for a caller acting on a set of projects at once, such as model deletion,
    // which must not delete some of a model's projects and then find another one still running.
    // Locks are taken in sorted order as discipline, so a future caller with overlapping sets cannot
    // introduce a deadlock.
    public boolean runIfAllIdle(Collection<String> projectIds, Runnable action) {
        List<String> ordered = projectIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .sorted()
                .toList();
        List<ReentrantLock> held = new ArrayList<>(ordered.size());
        try {
            for (String projectId : ordered) {
                ReentrantLock lock = lockFor(projectId);
                if (!lock.tryLock()) {
                    // something is mid-launch; give up rather than block the caller behind it
                    return false;
                }
                held.add(lock);
            }
            for (String projectId : ordered) {
                // same liveness question find() already answers, asked while nothing can change it - and it self-heals a dead entry on the way past, exactly as it does for any other caller (see find()'s own comment); a project whose JVM died externally reads as idle here for the same reason it reads as not-running everywhere else
                if (find(projectId).isPresent()) {
                    return false;
                }
            }
            // an empty set is vacuously idle - a model that was never generated has nothing to guard, and its deletion should not be refused for lack of anything to check
            action.run();
            return true;
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
        }
    }

    // awaitReady already throws GeneratedProjectLaunchException with the port (and exit code, when known) attached directly - this only wraps failures from elsewhere in the launch path (right now, just startProcess itself failing to spawn anything at all) so every way launch() can fail carries the port it was attempting, not just the two most common ones
    private static RuntimeException attachPort(RuntimeException e, int port) {
        return e instanceof GeneratedProjectLaunchException ? e
                : new GeneratedProjectLaunchException(e.getMessage(), port, null, e);
    }

    // Liveness is checked on read rather than by a background poller: an app that dies on its own leaves
    // its Process not alive, and this asks before any entry is handed to a caller acting on it.
    public Optional<LaunchedProject> find(String projectId) {
        Running r = running.get(projectId);
        if (r == null) {
            return Optional.empty();
        }
        if (!r.process().isAlive()) {
            forgetIfStillDead(projectId, r);
            return Optional.empty();
        }
        return Optional.of(r.info());
    }

    // the Evolve workflow step's own future read of "what's currently deployed to connect to" - same liveness re-check as find(), for the same reason: a dead entry here would otherwise read as a real, connectable application
    public List<LaunchedProject> listRunning() {
        List<LaunchedProject> alive = new ArrayList<>();
        for (Map.Entry<String, Running> entry : running.entrySet()) {
            Running r = entry.getValue();
            if (r.process().isAlive()) {
                alive.add(r.info());
            } else {
                forgetIfStillDead(entry.getKey(), r);
            }
        }
        return alive;
    }

    // Self-heal as soon as a dead entry is seen. Conditional remove(key, value) so it can never drop a
    // different, freshly-launched Running that raced in under the same projectId.
    private void forgetIfStillDead(String projectId, Running observed) {
        if (running.remove(projectId, observed)) {
            logger.warn("Generated project {} was reported running but its process has died; "
                    + "removing the stale entry", projectId);
        }
    }

    public boolean stop(String projectId) {
        Running r = running.remove(projectId);
        if (r == null) {
            return false;
        }
        destroyTree(r.process());
        logger.info("Stopped generated project {}", projectId);
        return true;
    }

    // Nothing else kills these: a child JVM started with ProcessBuilder does not die with its parent, so
    // before this every workbench restart left the previous run's apps alive holding their ports.
    // Spring calls this on normal shutdown; a hard kill of the workbench still leaks them.
    @PreDestroy
    void stopEverythingStillRunning() {
        for (String projectId : List.copyOf(running.keySet())) {
            try {
                stop(projectId);
            } catch (RuntimeException e) {
                // one project refusing to die shouldn't strand the rest of them
                logger.warn("Could not stop generated project {} during shutdown: {}", projectId, e.toString());
            }
        }
    }

    // Process.destroy() alone only signals the immediate process - on Windows that's cmd.exe, not the actual java process the wrapper script spawns underneath it, which would otherwise keep the port bound after stop() returns. Process.descendants() (JDK 9+) is the portable fix: walk the whole tree, not just the one handle we started.
    private static void destroyTree(Process process) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            try {
                long pid = process.pid();
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid)).start().waitFor();
            } catch (Exception e) {
                // Fall back to standard JDK process destruction below if taskkill fails
            }
        }
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    // Restart-only probe for the one case the registry cannot answer: a hard kill leaves generated JVMs
    // running and skips @PreDestroy, so the next workbench starts with an empty map while those apps
    // still hold their ports - and cleanup would then delete a live project's directory.
    // Deliberately not consulted during normal operation; while the workbench is up, `running` is
    // authoritative.
    public boolean somethingIsListeningOn(int port) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException nothingThere) {
            return false;
        }
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not find a free port to launch the generated project on", e);
        }
    }

    // SERVER_PORT rather than a CLI argument: Spring's relaxed env binding picks it up as server.port,
    // and it keeps the command simple enough for a test to substitute a fake for spring-boot:run.
    // The wrapper is referenced by absolute path - a bare "mvnw.cmd" does not resolve from the current
    // directory in every cmd.exe configuration.
    private Process startProcess(Path projectDir, int port, Map<String, String> extraEnv) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String wrapper = projectDir.resolve(windows ? "mvnw.cmd" : "mvnw").toAbsolutePath().toString();
        boolean hasWrapper = Files.isRegularFile(Path.of(wrapper));
        // RedCollarTP ships Maven wrapper metadata but not the wrapper scripts, so fall back to the installed
        // Maven executable. Only this path needs the explicit build step below; wrapper-based templates
        // compile as part of spring-boot:run's own lifecycle.
        if (!hasWrapper) {
            runMavenPackage(projectDir);
        }
        List<String> command = hasWrapper
                ? (windows ? List.of("cmd.exe", "/c", wrapper, "spring-boot:run")
                        : List.of(wrapper, "spring-boot:run"))
                : List.of(mavenExecutable(), "spring-boot:run");
        try {
            Path logFile = projectDir.resolve("launch.log");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                    .redirectErrorStream(true);
            builder.environment().put("SERVER_PORT", String.valueOf(port));
            builder.environment().putAll(extraEnv);
            // Same relaxed binding as SERVER_PORT, set for security rather than function: Spring binds every
            // interface by default, so a generated project with the template's permissive dev config and a
            // Camunda engine behind it was reachable from anything on the same network.
            builder.environment().put("SERVER_ADDRESS", "127.0.0.1");
            return builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start generated project at " + projectDir.toAbsolutePath(), e);
        }
    }

    // Build before run, so a compile or dependency failure is reported here with its own log rather than
    // surfacing later as an opaque "never started listening on port N".
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(5);

    private void runMavenPackage(Path projectDir) {
        Path buildLog = projectDir.resolve("build.log");
        // The generated application is launched directly below; no other build needs this temporary
        // artifact in the local Maven repository. Packaging still compiles and repackages the exact
        // runnable application while avoiding an unnecessary shared-repository write.
        List<String> command = List.of(mavenExecutable(), "clean", "package", "-DskipTests");
        Process build;
        try {
            build = new ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.to(buildLog.toFile()))
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run 'mvn clean package -DskipTests' for "
                    + projectDir.toAbsolutePath(), e);
        }
        boolean finished;
        try {
            finished = build.waitFor(BUILD_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            build.destroyForcibly();
            throw new IllegalStateException("Interrupted while running 'mvn clean package -DskipTests' for "
                    + projectDir.toAbsolutePath());
        }
        if (!finished) {
            build.destroyForcibly();
            throw new IllegalStateException("'mvn clean package -DskipTests' did not finish within "
                    + BUILD_TIMEOUT.toSeconds() + "s for " + projectDir.toAbsolutePath() + " - check "
                    + buildLog.toAbsolutePath());
        }
        if (build.exitValue() != 0) {
            throw new IllegalStateException("'mvn clean package -DskipTests' failed (exit " + build.exitValue()
                    + ") for " + projectDir.toAbsolutePath() + " - check " + buildLog.toAbsolutePath());
        }
        logger.info("'mvn clean package -DskipTests' finished for {}", projectDir.toAbsolutePath());
    }

    // An app launched from an IDE/service frequently inherits a much shorter PATH than an interactive terminal.  Resolve the standard Maven locations before falling back to PATH so a RedCollar-derived template without mvnw still launches on macOS/Homebrew and Linux.
    private static String mavenExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return resolveMavenExecutable(System.getenv("MAVEN_HOME"), windows);
    }

    // Split out from mavenExecutable() so both branches are testable without faking the environment.
    // Windows has no extensionless "mvn" launcher, only "mvn.cmd" - but the official distribution ships
    // an inert POSIX "mvn" beside it on every platform, so the name alone is not enough to pick one.
    static String resolveMavenExecutable(String mavenHome, boolean windows) {
        String mvnName = windows ? "mvn.cmd" : "mvn";
        if (mavenHome != null && !mavenHome.isBlank()) {
            Path candidate = Path.of(mavenHome, "bin", mvnName);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        // Homebrew/Linux install locations only - meaningless on Windows, where Maven is never installed at a Unix absolute path.
        if (!windows) {
            for (String candidate : List.of("/opt/homebrew/bin/mvn", "/usr/local/bin/mvn", "/usr/bin/mvn")) {
                if (Files.isExecutable(Path.of(candidate))) return candidate;
            }
        }
        return mvnName;
    }

    // Polls rather than sleeping a fixed time: a cold dependency download takes nothing like a warm one.
    // Takes the Process because "not listening yet" and "already dead" are different - a project that
    // fails to compile exits in seconds, and the caller should not wait out the full timeout for it.
    private static void awaitReady(Process process, Path projectDir, int port, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
                return;
            } catch (IOException notReadyYet) {
                // inside the failed-connect branch rather than at the top of the loop: listening is the success condition, so a process that got the port up and then exited between two polls should still count as started rather than be failed on liveness
                if (!process.isAlive()) {
                    throw new GeneratedProjectLaunchException("Generated project at " + projectDir.toAbsolutePath()
                            + " exited with code " + process.exitValue()
                            + " before it started listening on port " + port
                            + " - check " + projectDir.resolve("launch.log").toAbsolutePath(),
                            port, process.exitValue(), null);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GeneratedProjectLaunchException("Interrupted while waiting for the generated "
                            + "project to start", port, null, e);
                }
            }
        }
        throw new GeneratedProjectLaunchException("Generated project did not start listening on port " + port
                + " within " + timeout.getSeconds() + "s - check its launch.log", port, null, null);
    }
}
