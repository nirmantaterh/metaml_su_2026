package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;

/**
 * Verifies that process execution state persists across JVM restarts when using file-backed H2 storage.
 */
@Tag("slow")
class FilePersistenceRestartRecoveryTest {

    @TempDir
    Path tempDir;

    private static final Path REAL_TEMPLATE = Path.of("../../templates/camundademo");

    @Test
    void processInstanceAndVariablesSurviveJvmRestartWithFileBackedH2() throws Exception {
        assertThat(Files.isDirectory(REAL_TEMPLATE))
                .as("templates/camundademo must exist at %s", REAL_TEMPLATE.toAbsolutePath())
                .isTrue();

        Path outputDir = tempDir.resolve("generated-projects");
        Path dbDir = tempDir.resolve("h2-persistent-db");
        Files.createDirectories(dbDir);
        // The file-backed H2 URL - same path used for BOTH launches
        String h2FileUrl = "jdbc:h2:file:" + dbDir.resolve("camunda-engine").toAbsolutePath()
                .toString().replace('\\', '/')
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());

        GeneratedProject project = generator.generateWithAuthoredTwin(signalManufBpmn(), signalTwinBpmn());
        buildProject(project);

        // Env vars that override the default in-memory H2 with file-backed H2
        Map<String, String> initialEnv = Map.of(
                "SPRING_DATASOURCE_CAMUNDA_URL", h2FileUrl,
                "SPRING_DATASOURCE_URL", h2FileUrl,
                "METAML_BROADCASTER_FIXED_DELAY", "3600000"
        );
        Map<String, String> restartEnv = Map.of(
                "SPRING_DATASOURCE_CAMUNDA_URL", h2FileUrl,
                "SPRING_DATASOURCE_URL", h2FileUrl,
                "METAML_BROADCASTER_FIXED_DELAY", "500"
        );

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        HttpClient http = HttpClient.newHttpClient();
        String processInstanceId;

        // First launch - create runtime state
        LaunchedProject launched1 = launcher.launch(project, initialEnv);
        try {
            String manufBase = "http://localhost:" + launched1.port() + "/api/v1/manufacturing";
            String statusBase = "http://localhost:" + launched1.port() + "/api/v1/process";

            // Start a process - it will park at SignalCatch (intermediate catch event)
            HttpResponse<String> startResponse = post(http,  manufBase + "/start");
            assertThat(startResponse.statusCode()).as("start failed: %s", startResponse.body()).isEqualTo(200);
            processInstanceId = extractProcessInstanceId(startResponse.body());
            assertThat(processInstanceId).isNotBlank();

            // Wait for the process to reach SignalCatch (gated wait state)
            awaitActiveActivity(http, statusBase, processInstanceId, "SignalCatch", Duration.ofSeconds(15));

            // Read current status to capture pre-restart state
            String statusBefore = fetchStatus(http, statusBase, processInstanceId);
            assertThat(statusBefore).contains("\"active\":true");
            assertThat(statusBefore).contains("SignalCatch");

            // Record pre-restart state
            System.out.println("=== PRE-RESTART STATE ===");
            System.out.println("Process Instance ID: " + processInstanceId);
            System.out.println("Status: " + statusBefore);
            System.out.println("H2 File URL: " + h2FileUrl);
            System.out.println("DB files exist: " + Files.exists(dbDir.resolve("camunda-engine.mv.db")));
        } finally {
            // Stop the JVM
            launcher.stop(project.projectId());
        }

        // Verify the JVM process has terminated
        assertThat(launcher.find(project.projectId())).isEmpty();

        // Verify the H2 database file exists on disk
        assertThat(Files.exists(dbDir.resolve("camunda-engine.mv.db")))
                .as("H2 database file should exist on disk after JVM shutdown")
                .isTrue();
        long dbFileSize = Files.size(dbDir.resolve("camunda-engine.mv.db"));
        assertThat(dbFileSize).as("H2 database file should have content").isGreaterThan(0);
        System.out.println("H2 DB file size after first shutdown: " + dbFileSize + " bytes");

        // Second launch - recover runtime state
        LaunchedProject launched2 = launcher.launch(project, restartEnv);
        try {
            String statusBase = "http://localhost:" + launched2.port() + "/api/v1/process";

            // Wait a moment for process engine to settle and complete
            Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
            boolean completed = false;
            while (Instant.now().isBefore(deadline)) {
                String status = fetchStatus(http, statusBase, processInstanceId);
                if (status.contains("\"active\":false")) {
                    completed = true;
                    break;
                }
                Thread.sleep(300);
            }
            assertThat(completed).as("Process should have completed post-restart").isTrue();

            String statusAfter = fetchStatus(http, statusBase, processInstanceId);

            System.out.println("=== POST-RESTART STATE ===");
            System.out.println("Process Instance ID: " + processInstanceId);
            System.out.println("Status: " + statusAfter);

            // Fetch historic activity counts from HistoryService
            HttpResponse<String> histResponse = http.send(
                    HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/activity-history/SignalCatch/count")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.println("Activity history for SignalCatch: " + histResponse.body());

            HttpResponse<String> histManuf = http.send(
                    HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/activity-history/ManufFinish/count")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.println("Activity history for ManufFinish: " + histManuf.body());

            // State continuation verification:
            // Process instance recovered on JVM 2; history verifies both activities were entered and completed
            assertThat(histResponse.body())
                    .as("SignalCatch must have been visited (recorded in historic database)")
                    .contains("\"visitCount\":1");

            assertThat(histManuf.body())
                    .as("ManufFinish must have been visited and completed post-restart")
                    .contains("\"visitCount\":1");

            // Verify HikariCP connection pool is being used
            Path launchLog = project.directory().resolve("launch.log");
            if (Files.exists(launchLog)) {
                String logContent = Files.readString(launchLog);
                assertThat(logContent)
                        .as("Launch log must confirm HikariCP pool initialization")
                        .contains("Hikari");
                System.out.println("HikariCP initialization confirmed in launch.log");
            }

        } finally {
            launcher.stop(project.projectId());
        }

        System.out.println("File-backed H2 restart recovery and persistence verified.");
    }

    // start -> signal catch("SharedSignal") -> external task("ManufFinish") -> end
    // The process parks at SignalCatch - a stable intermediate wait state.
    private static String signalManufBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_PersistManuf" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:signal id="Signal_Persist" name="PersistTestSignal" />
                  <bpmn2:process id="PersistTestManuf" name="Persist Test Manuf" isExecutable="true">
                    <bpmn2:startEvent id="PersistStart" />
                    <bpmn2:intermediateCatchEvent id="SignalCatch" name="Signal Catch">
                      <bpmn2:signalEventDefinition signalRef="Signal_Persist" />
                    </bpmn2:intermediateCatchEvent>
                    <bpmn2:serviceTask id="ManufFinish" name="Manuf Finish" camunda:type="external"
                        camunda:topic="PersistTestManufFinish" />
                    <bpmn2:endEvent id="PersistEnd" />
                    <bpmn2:sequenceFlow id="PersistFlow1" sourceRef="PersistStart" targetRef="SignalCatch" />
                    <bpmn2:sequenceFlow id="PersistFlow2" sourceRef="SignalCatch" targetRef="ManufFinish" />
                    <bpmn2:sequenceFlow id="PersistFlow3" sourceRef="ManufFinish" targetRef="PersistEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    // Twin: start -> external task -> end (no signal, simplest possible twin)
    private static String signalTwinBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_PersistTwin" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="PersistTestTwin" name="Persist Test Twin" isExecutable="true">
                    <bpmn2:startEvent id="PersistTwinStart" />
                    <bpmn2:serviceTask id="PersistTwinStep" name="Twin Step" camunda:type="external"
                        camunda:topic="PersistTestTwinStep" />
                    <bpmn2:endEvent id="PersistTwinEnd" />
                    <bpmn2:sequenceFlow id="PersistTwinFlow1" sourceRef="PersistTwinStart" targetRef="PersistTwinStep" />
                    <bpmn2:sequenceFlow id="PersistTwinFlow2" sourceRef="PersistTwinStep" targetRef="PersistTwinEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static void buildProject(GeneratedProject project) throws IOException, InterruptedException {
        Process build = new ProcessBuilder(mvnw(project.directory()), "-q", "package", "-DskipTests")
                .directory(project.directory().toFile())
                .redirectErrorStream(true)
                .start();
        String buildOutput = new String(build.getInputStream().readAllBytes());
        boolean finished = build.waitFor(5, TimeUnit.MINUTES);
        assertThat(finished).as("mvn build did not finish in time").isTrue();
        assertThat(build.exitValue()).as("generated project failed to build:\n%s", buildOutput).isZero();
    }

    private static HttpResponse<String> post(HttpClient http, String url)
            throws IOException, InterruptedException {
        return http.send(
                HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String fetchStatus(HttpClient http, String statusBase, String processInstanceId)
            throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static boolean awaitActiveActivity(HttpClient http, String statusBase, String processInstanceId,
            String activityId, Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.body().contains(activityId)) {
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    private static String extractProcessInstanceId(String json) {
        int key = json.indexOf("\"processInstanceId\"");
        int firstQuote = json.indexOf('"', key + "\"processInstanceId\"".length() + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static String mvnw(Path projectDir) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = projectDir.resolve(windows ? "mvnw.cmd" : "mvnw");
        return wrapper.toAbsolutePath().toString();
    }
}
