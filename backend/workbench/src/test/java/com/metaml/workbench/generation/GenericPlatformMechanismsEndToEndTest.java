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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;

// Proves several GENERIC platform mechanisms with small synthetic fixtures deliberately named
// nothing like RedCollar, so a pass here cannot be explained by RedCollar's own BPMN shape
// happening to carry signals (masking the scheduling-coupling bug) or by only ever running one
// instance at a time (masking any cross-instance signal question). Slow: builds and launches real
// Target Harness JVMs.
@Tag("slow")
class GenericPlatformMechanismsEndToEndTest {

    @TempDir
    Path tempDir;

    private static final Path REAL_TEMPLATE = Path.of("../../templates/camundademo");

    // Regression for the accidental-scheduling-coupling defect: a BPMN pair with external tasks but
    // NO signals at all must still get a working ExternalTaskPoller. Before the fix, @EnableScheduling
    // only ever came from SignalBroadcaster, which this fixture never generates (zero signals), so
    // the poller would silently never run - no error, no exception, just tasks that never execute.
    @Test
    void externalTaskWorkersExecuteWithNoSignalComponentGenerated() throws Exception {
        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());

        GeneratedProject project = generator.generateWithAuthoredTwin(noSignalManufBpmn(), noSignalTwinBpmn());

        // The fixture declares no bpmn:signal at all, so SignalBroadcaster must not exist - proving
        // that whatever makes the poller run below is NOT coming from that class.
        String slug = "genericnosignalmanuf";
        String basePackagePath = "src/main/java/com/metaml/targetplatform/" + slug;
        assertThat(project.directory().resolve(basePackagePath + "/signal/SignalBroadcaster.java")).doesNotExist();
        assertThat(project.directory().resolve(basePackagePath + "/worker/SchedulingConfig.java")).exists();
        assertThat(Files.readString(project.directory().resolve(basePackagePath + "/worker/SchedulingConfig.java")))
                .contains("@EnableScheduling");

        buildProject(project);
        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project);
            HttpClient http = HttpClient.newHttpClient();
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";
            String manufInstance = extractProcessInstanceId(
                    post(http, "http://localhost:" + launched.port() + "/api/v1/manufacturing/start").body());
            String twinInstance = extractProcessInstanceId(
                    post(http, "http://localhost:" + launched.port() + "/api/v1/twin/start").body());

            // Real engine state (via the generic status endpoint), not log text - each fixture is a
            // straight-line start -> external task -> end, so the instance completing (no longer
            // active) IS the proof the poller fetched, executed, and completed the external task with
            // no SignalBroadcaster anywhere in the deployed app. Log text is intentionally not the
            // primary evidence here: child-process stdout is not line-flushed on a fixed schedule, so
            // a low-log-volume fixture like this one can leave real, already-completed work
            // temporarily invisible in launch.log even though the engine has genuinely finished it.
            boolean manufCompleted = awaitInstanceCompleted(http, statusBase, manufInstance, Duration.ofSeconds(30));
            boolean twinCompleted = awaitInstanceCompleted(http, statusBase, twinInstance, Duration.ofSeconds(30));
            assertThat(manufCompleted).as("manufacturing instance should have run its external task to completion")
                    .isTrue();
            assertThat(twinCompleted).as("twin instance should have run its external task to completion").isTrue();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    private static boolean awaitInstanceCompleted(HttpClient http, String statusBase, String processInstanceId,
            Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status")).GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.body().contains("\"active\":false")) {
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    // Signal-concurrency investigation (audit item: does one broadcast advance an unrelated
    // process instance?). Starts the SAME process definition twice so both instances are genuinely
    // parked on the same signal catch event, waits for the generated SignalBroadcaster's own natural
    // cycle, then reads ACTUAL engine state (via the generic status endpoint, not logs) for both
    // instances. camunda:signalEventReceived(name) is a global broadcast by BPMN specification - not
    // scoped to one execution - so this is expected to show both instances advancing together; the
    // point of running it for real is to confirm that is what this generated platform actually does,
    // not merely what the spec implies.
    @Test
    void signalBroadcastEffectOnConcurrentInstancesOfTheSameProcess() throws Exception {
        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());

        GeneratedProject project = generator.generateWithAuthoredTwin(signalManufBpmn(), signalTwinBpmn());
        buildProject(project);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project, java.util.Map.of("METAML_BROADCASTER_FIXED_DELAY", "5000"));
            HttpClient http = HttpClient.newHttpClient();
            String manufBase = "http://localhost:" + launched.port() + "/api/v1/manufacturing";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";

            // Two independent instances of the SAME process, both starting fresh - both will reach
            // the signal catch event and park there.
            String instanceA = extractProcessInstanceId(post(http, manufBase + "/start").body());
            String instanceB = extractProcessInstanceId(post(http, manufBase + "/start").body());

            // Confirm both are genuinely parked on the catch event before any broadcast could have
            // fired for either of them - otherwise "both advanced" would be unfalsifiable.
            awaitActiveActivity(http, statusBase, instanceA, "SignalCatch", Duration.ofSeconds(10));
            awaitActiveActivity(http, statusBase, instanceB, "SignalCatch", Duration.ofSeconds(10));

            // "Advanced" means the instance is no longer parked AT the catch event - either it is now
            // at ManufFinish, or (since this is a straight-line process) it already ran ManufFinish to
            // completion and no longer exists in runtime at all. Either observation proves the signal
            // reached it; checking for "ManufFinish" text specifically would be racy against how fast
            // the external task completes and the instance vanishes from RuntimeService.
            boolean advancedA = awaitLeftActivity(http, statusBase, instanceA, "SignalCatch", Duration.ofSeconds(15));
            boolean advancedB = awaitLeftActivity(http, statusBase, instanceB, "SignalCatch", Duration.ofSeconds(15));

            assertThat(advancedA).as("instance A should have advanced past the signal catch").isTrue();
            assertThat(advancedB).as("instance B should have advanced past the signal catch too - same broadcast, "
                    + "unrelated instance, matching BPMN signal semantics (global, not execution-scoped)").isTrue();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Proves business-key-scoped Main/Twin pairing: two CONCURRENT pairs, started with distinct
    // caller-supplied business keys, stay correctly labeled and correctly isolated in real Camunda
    // state - each instance's own businessKey is exactly what its own /start call supplied, never the
    // other pair's, and each pair's own runtime data (the Twin's per-topic agentInvocationId values)
    // is independently generated per pair, never shared. This fixture's Twin runs its own task before
    // ever reaching the shared signal (see pairedTwinBpmn), so it does not exercise SignalBroadcaster's
    // two-step initiator/responder handoff in a meaningful way - that direction-of-causality proof is
    // mainRequestGatesTwinDelegateAndTwinCompletionGatesMainContinuation, below, whose fixtures gate
    // each side's own task BEHIND the shared signal specifically so the handoff has something to gate.
    // What this test proves is narrower and orthogonal: that concurrent pairs do not corrupt each
    // other's identity or data - what a business-key-unaware implementation genuinely could get wrong.
    @Test
    void concurrentMainTwinPairsStayCorrectlyLabeledAndIsolatedByBusinessKey() throws Exception {
        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());

        GeneratedProject project = generator.generateWithAuthoredTwin(signalManufBpmn(), pairedTwinBpmn());
        buildProject(project);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project, java.util.Map.of("METAML_BROADCASTER_FIXED_DELAY", "5000"));
            HttpClient http = HttpClient.newHttpClient();
            String manufBase = "http://localhost:" + launched.port() + "/api/v1/manufacturing";
            String twinBase = "http://localhost:" + launched.port() + "/api/v1/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";

            String keyA = "pair-A-" + java.util.UUID.randomUUID();
            String keyB = "pair-B-" + java.util.UUID.randomUUID();

            String mainA = extractProcessInstanceId(post(http, manufBase + "/start?businessKey=" + keyA).body());
            String twinA = extractProcessInstanceId(post(http, twinBase + "/start?businessKey=" + keyA).body());
            String mainB = extractProcessInstanceId(post(http, manufBase + "/start?businessKey=" + keyB).body());
            String twinB = extractProcessInstanceId(post(http, twinBase + "/start?businessKey=" + keyB).body());

            // Real Camunda state (RuntimeService.getBusinessKey() via the status endpoint), not a
            // value this test invented - each instance's businessKey is exactly the one its own
            // /start call supplied, and never the other pair's. Checked immediately after starting,
            // before either Twin (a single-external-task, no-signal-wait fixture) has a chance to
            // complete and drop out of RuntimeService.
            assertThat(getStatus(http, statusBase, mainA)).contains("\"businessKey\":\"" + keyA + "\"");
            assertThat(getStatus(http, statusBase, mainB)).contains("\"businessKey\":\"" + keyB + "\"");

            // Confirm both Main instances are genuinely parked on their own signal catch before either
            // has a chance to advance - both this catch and the Twin's own trailing hold share the
            // same signal name, so whichever check runs later risks racing against the broadcaster's
            // very first tick.
            awaitActiveActivity(http, statusBase, mainA, "SignalCatch", Duration.ofSeconds(10));
            awaitActiveActivity(http, statusBase, mainB, "SignalCatch", Duration.ofSeconds(10));

            // The Twin side's own simulated-invocation output (UUID per topic, see
            // ExternalTaskWorkerGenerator) must never be shared between the two pairs - each pair
            // generates its own, independently, proving no cross-pair data leakage. Captured right
            // after starting, since this fixture's Twin has no signal wait and can complete (and drop
            // its variables from RuntimeService) within one poll cycle.
            String twinAVars = awaitVariableContaining(http, statusBase, twinA, "agentInvocationId",
                    Duration.ofSeconds(10));
            String twinBVars = awaitVariableContaining(http, statusBase, twinB, "agentInvocationId",
                    Duration.ofSeconds(10));
            assertThat(twinAVars).as("pair A's twin must have real agent output, tagged with its own business key")
                    .contains("agentInvocationId")
                    .contains("\"businessKey\":\"" + keyA + "\"");
            assertThat(twinBVars).as("pair B's twin must have real agent output, tagged with its own business key")
                    .contains("agentInvocationId")
                    .contains("\"businessKey\":\"" + keyB + "\"");
            String invocationIdA = extractField(twinAVars, "agentInvocationId");
            String invocationIdB = extractField(twinBVars, "agentInvocationId");
            assertThat(invocationIdA).as("each pair's simulated invocation is independently generated")
                    .isNotEqualTo(invocationIdB);

            // Both pairs independently advance past their own signal catch on the Main side,
            // concurrently, without interfering with each other's ability to run to completion.
            assertThat(awaitLeftActivity(http, statusBase, mainA, "SignalCatch", Duration.ofSeconds(15))).isTrue();
            assertThat(awaitLeftActivity(http, statusBase, mainB, "SignalCatch", Duration.ofSeconds(15))).isTrue();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Proves the actual Main -> Twin -> Main causal chain SignalBroadcaster now establishes, using
    // fixtures where each side's own task is deliberately GATED BEHIND the shared signal (unlike
    // pairedTwinBpmn above), so releasing an execution genuinely gates whatever runs next in its BPMN
    // - exactly the shape both real RedCollar signal barriers have (task -> catch -> task -> catch...).
    //
    // Twin's fixture has exactly ONE signal wait total, so RESPONDER_HAS_ADVANCED_PAST (see
    // SignalBroadcaster) can only ever become true by Twin's WHOLE process instance completing - there
    // is no second signal for it to be "subscribed to instead". That makes the causal guarantee this
    // test checks airtight by construction, not merely likely: Main's own release is only ever
    // attempted on a SignalBroadcaster tick that first re-queries Twin's runtime state and finds it
    // fully completed, so this test's own read of "Twin completed" and the engine's own precondition
    // for releasing Main are answering the identical question against the identical runtime state.
    @Test
    void mainRequestGatesTwinDelegateAndTwinCompletionGatesMainContinuation() throws Exception {
        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());

        GeneratedProject project = generator.generateWithAuthoredTwin(causalManufBpmn(), causalTwinBpmn());
        buildProject(project);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project, java.util.Map.of("METAML_BROADCASTER_FIXED_DELAY", "5000"));
            HttpClient http = HttpClient.newHttpClient();
            String manufBase = "http://localhost:" + launched.port() + "/api/v1/manufacturing";
            String twinBase = "http://localhost:" + launched.port() + "/api/v1/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";
            String key = "causal-" + java.util.UUID.randomUUID();

            // Main registers first under this key, so it is the initiator - the caller-facing sense of
            // "Main" - and Twin, registering second, is the responder ("Twin"). Roles come purely from
            // arrival order under a shared business key (see PairRegistry), not from which controller
            // was called - this fixture pair could just as easily be any two processes.
            HttpResponse<String> mainStart = post(http, manufBase + "/start?businessKey=" + key);
            assertThat(mainStart.body()).contains("\"role\":\"initiator\"");
            String mainId = extractProcessInstanceId(mainStart.body());

            HttpResponse<String> twinStart = post(http, twinBase + "/start?businessKey=" + key);
            assertThat(twinStart.body()).contains("\"role\":\"responder\"");
            String twinId = extractProcessInstanceId(twinStart.body());

            // Both sides reach their own request barrier immediately on start (no preamble task in
            // either fixture) - confirms Main genuinely "sent the request" by parking here, not that it
            // raced ahead before this check ran.
            awaitActiveActivity(http, statusBase, mainId, "CausalManufCatch", Duration.ofSeconds(10));

            // Twin's delegate (the simulated agent invocation) only runs once released, and its only
            // remaining step after that is its own end event - so Twin fully completing IS the proof
            // its gated delegate ran, not merely that the request signal arrived.
            boolean twinCompleted = awaitInstanceCompleted(http, statusBase, twinId, Duration.ofSeconds(20));
            assertThat(twinCompleted).as("Twin's delegate must run and let Twin reach its own end event").isTrue();

            // The instant this test first observes Twin complete, Main must still be genuinely parked
            // at its own request barrier. Main's own release is only ever attempted on a
            // SignalBroadcaster tick that re-queries Twin AFTER this test's own observation could have
            // happened - never within the same instant - so Main having already left here would mean
            // its continuation was not actually gated by Twin's completion at all.
            String mainStatusRightAfterTwinCompleted = getStatus(http, statusBase, mainId);
            assertThat(mainStatusRightAfterTwinCompleted)
                    .as("Main must still be waiting at the exact moment Twin's completion is first observed - "
                            + "otherwise Main's continuation could not be caused by that completion")
                    .contains("CausalManufCatch");

            // Only now should Main be released, and only because Twin's completion above triggered it.
            boolean mainLeftBarrier = awaitLeftActivity(http, statusBase, mainId, "CausalManufCatch",
                    Duration.ofSeconds(10));
            assertThat(mainLeftBarrier)
                    .as("Main must continue after, and only after, Twin's completion was observed above")
                    .isTrue();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Stress-tests the same two-step handoff at higher concurrency and with hostile start
    // ordering, to raise confidence beyond a single pair: 5 concurrent Main/Twin pairs, started in
    // a deliberately interleaved order (not grouped pair-by-pair - do not assume creation order
    // means execution order), including one pair (B) started Twin-first, reversing which
    // controller claims "initiator" - proving roles come from arrival order alone (PairRegistry),
    // never from which endpoint was called. Every pair must independently complete with its own,
    // never another pair's, pairing key, and one pair is re-checked mid-flight (authoritative
    // runtime state, not a sleep) to confirm its Main is still gated the instant its own Twin is
    // first observed complete - the same proof as the single-pair causal test, re-run here under
    // pressure from four other simultaneously-active pairs.
    @Test
    void fiveConcurrentPairsWithInterleavedOrderingNeverCrossTalkOrReleaseEarly() throws Exception {
        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());

        GeneratedProject project = generator.generateWithAuthoredTwin(causalManufBpmn(), causalTwinBpmn());
        buildProject(project);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project, java.util.Map.of("METAML_BROADCASTER_FIXED_DELAY", "5000"));
            HttpClient http = HttpClient.newHttpClient();
            String manufBase = "http://localhost:" + launched.port() + "/api/v1/manufacturing";
            String twinBase = "http://localhost:" + launched.port() + "/api/v1/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";

            String keyA = "stress-A-" + java.util.UUID.randomUUID();
            String keyB = "stress-B-" + java.util.UUID.randomUUID();
            String keyC = "stress-C-" + java.util.UUID.randomUUID();
            String keyD = "stress-D-" + java.util.UUID.randomUUID();
            String keyE = "stress-E-" + java.util.UUID.randomUUID();

            // Deliberately interleaved across pairs, and pair B is started Twin-first.
            HttpResponse<String> mainAStart = post(http, manufBase + "/start?businessKey=" + keyA);
            HttpResponse<String> mainCStart = post(http, manufBase + "/start?businessKey=" + keyC);
            HttpResponse<String> twinBStart = post(http, twinBase + "/start?businessKey=" + keyB);
            HttpResponse<String> twinAStart = post(http, twinBase + "/start?businessKey=" + keyA);
            HttpResponse<String> mainDStart = post(http, manufBase + "/start?businessKey=" + keyD);
            HttpResponse<String> twinCStart = post(http, twinBase + "/start?businessKey=" + keyC);
            HttpResponse<String> mainBStart = post(http, manufBase + "/start?businessKey=" + keyB);
            HttpResponse<String> twinEStart = post(http, twinBase + "/start?businessKey=" + keyE);
            HttpResponse<String> twinDStart = post(http, twinBase + "/start?businessKey=" + keyD);
            HttpResponse<String> mainEStart = post(http, manufBase + "/start?businessKey=" + keyE);

            // Each instance's OWN businessKey and role, read straight from its own /start response
            // (authoritative - Camunda's own startProcessInstanceByKey(key, businessKey) result and
            // PairRegistry's own classification, not an echo of what this test sent) - checked now,
            // before any instance has a chance to complete and stop carrying this in later status
            // calls. Roles reflect arrival order, not which controller was called: B reverses it.
            assertThat(mainAStart.body()).contains("\"businessKey\":\"" + keyA + "\"", "\"role\":\"initiator\"");
            assertThat(twinAStart.body()).contains("\"businessKey\":\"" + keyA + "\"", "\"role\":\"responder\"");
            assertThat(twinBStart.body()).contains("\"businessKey\":\"" + keyB + "\"", "\"role\":\"initiator\"");
            assertThat(mainBStart.body()).contains("\"businessKey\":\"" + keyB + "\"", "\"role\":\"responder\"");
            assertThat(mainCStart.body()).contains("\"businessKey\":\"" + keyC + "\"", "\"role\":\"initiator\"");
            assertThat(twinCStart.body()).contains("\"businessKey\":\"" + keyC + "\"", "\"role\":\"responder\"");
            assertThat(mainDStart.body()).contains("\"businessKey\":\"" + keyD + "\"", "\"role\":\"initiator\"");
            assertThat(twinDStart.body()).contains("\"businessKey\":\"" + keyD + "\"", "\"role\":\"responder\"");
            assertThat(twinEStart.body()).contains("\"businessKey\":\"" + keyE + "\"", "\"role\":\"initiator\"");
            assertThat(mainEStart.body()).contains("\"businessKey\":\"" + keyE + "\"", "\"role\":\"responder\"");

            String mainA = extractProcessInstanceId(mainAStart.body());
            String twinA = extractProcessInstanceId(twinAStart.body());
            String mainB = extractProcessInstanceId(mainBStart.body());
            String mainC = extractProcessInstanceId(mainCStart.body());
            String mainD = extractProcessInstanceId(mainDStart.body());
            String twinB = extractProcessInstanceId(twinBStart.body());
            String twinC = extractProcessInstanceId(twinCStart.body());
            String twinD = extractProcessInstanceId(twinDStart.body());
            String mainE = extractProcessInstanceId(mainEStart.body());
            String twinE = extractProcessInstanceId(twinEStart.body());

            // Mid-flight, authoritative-state re-check on pair A only, under concurrency pressure
            // from the other four pairs racing alongside it.
            awaitActiveActivity(http, statusBase, mainA, "CausalManufCatch", Duration.ofSeconds(10));
            assertThat(awaitInstanceCompleted(http, statusBase, twinA, Duration.ofSeconds(30)))
                    .as("pair A's twin must complete").isTrue();
            assertThat(getStatus(http, statusBase, mainA))
                    .as("pair A's Main must still be waiting the instant pair A's Twin is first observed complete, "
                            + "even with four other pairs concurrently active")
                    .contains("CausalManufCatch");

            // Every Main eventually leaves its own barrier - none stuck, none released by another
            // pair's traffic.
            assertThat(awaitLeftActivity(http, statusBase, mainA, "CausalManufCatch", Duration.ofSeconds(15)))
                    .as("pair A's Main").isTrue();
            assertThat(awaitLeftActivity(http, statusBase, mainB, "CausalManufCatch", Duration.ofSeconds(15)))
                    .as("pair B's Main (Twin-first pairing)").isTrue();
            assertThat(awaitLeftActivity(http, statusBase, mainC, "CausalManufCatch", Duration.ofSeconds(15)))
                    .as("pair C's Main").isTrue();
            assertThat(awaitLeftActivity(http, statusBase, mainD, "CausalManufCatch", Duration.ofSeconds(15)))
                    .as("pair D's Main").isTrue();
            assertThat(awaitLeftActivity(http, statusBase, mainE, "CausalManufCatch", Duration.ofSeconds(15)))
                    .as("pair E's Main (Twin-first pairing)").isTrue();

            // Every twin completed too - every pair's delegate genuinely ran, not just pair A's.
            assertThat(awaitInstanceCompleted(http, statusBase, twinB, Duration.ofSeconds(15)))
                    .as("pair B's twin").isTrue();
            assertThat(awaitInstanceCompleted(http, statusBase, twinC, Duration.ofSeconds(15)))
                    .as("pair C's twin").isTrue();
            assertThat(awaitInstanceCompleted(http, statusBase, twinD, Duration.ofSeconds(15)))
                    .as("pair D's twin").isTrue();
            assertThat(awaitInstanceCompleted(http, statusBase, twinE, Duration.ofSeconds(15)))
                    .as("pair E's twin").isTrue();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    private static String getStatus(HttpClient http, String statusBase, String processInstanceId)
            throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status")).GET()
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static String awaitVariableContaining(HttpClient http, String statusBase, String processInstanceId,
            String variableNameFragment, Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String lastBody = "";
        while (Instant.now().isBefore(deadline)) {
            lastBody = getStatus(http, statusBase, processInstanceId);
            if (lastBody.contains(variableNameFragment)) {
                return lastBody;
            }
            Thread.sleep(300);
        }
        return lastBody;
    }

    private static String extractField(String json, String fieldName) {
        int key = json.indexOf("\"" + fieldName + "\"");
        int firstQuote = json.indexOf('"', key + fieldName.length() + 3);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static void buildProject(GeneratedProject project) throws IOException, InterruptedException {
        Process build = new ProcessBuilder(mvnw(project.directory()), "-q", "package", "-DskipTests")
                .directory(project.directory().toFile())
                .redirectErrorStream(true)
                .start();
        String buildOutput = new String(build.getInputStream().readAllBytes());
        boolean buildFinished = build.waitFor(5, TimeUnit.MINUTES);
        assertThat(buildFinished).as("mvn build did not finish in time").isTrue();
        assertThat(build.exitValue()).as("generated project failed to build:%n%s", buildOutput).isZero();
    }

    private static HttpResponse<String> post(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s failed: %s", url, response.body()).isEqualTo(200);
        return response;
    }

    // Polls the generic status endpoint until the given activity id appears among the instance's
    // active activities, returning the body that proved it - real engine state, not log text.
    private static String awaitActiveActivity(HttpClient http, String statusBase, String processInstanceId,
            String activityId, Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String lastBody = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status")).GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            lastBody = response.body();
            if (lastBody.contains(activityId)) {
                return lastBody;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("Timed out waiting for activity '" + activityId + "' on instance "
                + processInstanceId + ". Last status: " + lastBody);
    }

    // Polls until the instance's active activities no longer include activityId - true whether it
    // moved on to another activity or the whole (completed) instance disappeared from RuntimeService
    // entirely, both of which mean it left activityId. Returns false on timeout rather than throwing,
    // so the caller can assert on it directly and get a clear failure message.
    private static boolean awaitLeftActivity(HttpClient http, String statusBase, String processInstanceId,
            String activityId, Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status")).GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (!response.body().contains(activityId)) {
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    private static String mvnw(Path projectDir) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = projectDir.resolve(windows ? "mvnw.cmd" : "mvnw");
        return wrapper.toAbsolutePath().toString();
    }

    private static String extractProcessInstanceId(String json) {
        int key = json.indexOf("\"processInstanceId\"");
        int firstQuote = json.indexOf('"', key + "\"processInstanceId\"".length() + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        return json.substring(firstQuote + 1, secondQuote);
    }

    // start -> external task -> end, in both BPMNs. No signals anywhere in either fixture.
    private static String noSignalManufBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_NoSignalManuf" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="GenericNoSignalManuf" name="Generic No Signal Manuf" isExecutable="true">
                    <bpmn2:startEvent id="NoSigManufStart" />
                    <bpmn2:serviceTask id="NoSigManufStep" name="Step" camunda:type="external"
                        camunda:topic="GenericNoSignalStep" />
                    <bpmn2:endEvent id="NoSigManufEnd" />
                    <bpmn2:sequenceFlow id="NoSigManufFlow1" sourceRef="NoSigManufStart" targetRef="NoSigManufStep" />
                    <bpmn2:sequenceFlow id="NoSigManufFlow2" sourceRef="NoSigManufStep" targetRef="NoSigManufEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String noSignalTwinBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_NoSignalTwin" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="GenericNoSignalTwin" name="Generic No Signal Twin" isExecutable="true">
                    <bpmn2:startEvent id="NoSigTwinStart" />
                    <bpmn2:serviceTask id="NoSigTwinStep" name="Twin Step" camunda:type="external"
                        camunda:topic="GenericNoSignalTwinStep" />
                    <bpmn2:endEvent id="NoSigTwinEnd" />
                    <bpmn2:sequenceFlow id="NoSigTwinFlow1" sourceRef="NoSigTwinStart" targetRef="NoSigTwinStep" />
                    <bpmn2:sequenceFlow id="NoSigTwinFlow2" sourceRef="NoSigTwinStep" targetRef="NoSigTwinEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    // start -> signal catch("SharedSignal") -> external task("ManufFinish") -> end. Twin has no
    // signal of its own; the shared signal only needs to exist somewhere for SignalBroadcaster to
    // be generated, and Manufacturing is where the concurrency question actually gets asked.
    private static String signalManufBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_SignalManuf" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:signal id="Signal_Shared" name="SharedSignal" />
                  <bpmn2:process id="GenericSignalManuf" name="Generic Signal Manuf" isExecutable="true">
                    <bpmn2:startEvent id="SigManufStart" />
                    <bpmn2:intermediateCatchEvent id="SignalCatch" name="Signal Catch">
                      <bpmn2:signalEventDefinition signalRef="Signal_Shared" />
                    </bpmn2:intermediateCatchEvent>
                    <bpmn2:serviceTask id="ManufFinish" name="Manuf Finish" camunda:type="external"
                        camunda:topic="GenericSignalManufFinish" />
                    <bpmn2:endEvent id="SigManufEnd" />
                    <bpmn2:sequenceFlow id="SigManufFlow1" sourceRef="SigManufStart" targetRef="SignalCatch" />
                    <bpmn2:sequenceFlow id="SigManufFlow2" sourceRef="SignalCatch" targetRef="ManufFinish" />
                    <bpmn2:sequenceFlow id="SigManufFlow3" sourceRef="ManufFinish" targetRef="SigManufEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String signalTwinBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_SignalTwin" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="GenericSignalTwin" name="Generic Signal Twin" isExecutable="true">
                    <bpmn2:startEvent id="SigTwinStart" />
                    <bpmn2:serviceTask id="SigTwinStep" name="Twin Step" camunda:type="external"
                        camunda:topic="GenericSignalTwinStep" />
                    <bpmn2:endEvent id="SigTwinEnd" />
                    <bpmn2:sequenceFlow id="SigTwinFlow1" sourceRef="SigTwinStart" targetRef="SigTwinStep" />
                    <bpmn2:sequenceFlow id="SigTwinFlow2" sourceRef="SigTwinStep" targetRef="SigTwinEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    // Same shape signalTwinBpmn() has (external task first, so its worker fires immediately with no
    // gate before it) plus a trailing catch on the SAME shared signal signalManufBpmn() uses, so the
    // instance stays alive - queryable via the status endpoint - after its external task completes,
    // instead of falling straight through to an end event and disappearing from RuntimeService before
    // any HTTP poll could observe its variables. Used only by the business-key isolation test, which
    // needs a stable window to read the Twin's simulated-invocation output.
    private static String pairedTwinBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_PairedTwin" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:signal id="Signal_PairedShared" name="SharedSignal" />
                  <bpmn2:process id="GenericPairedTwin" name="Generic Paired Twin" isExecutable="true">
                    <bpmn2:startEvent id="PairedTwinStart" />
                    <bpmn2:serviceTask id="PairedTwinStep" name="Twin Step" camunda:type="external"
                        camunda:topic="GenericPairedTwinStep" />
                    <bpmn2:intermediateCatchEvent id="PairedTwinHold" name="Hold">
                      <bpmn2:signalEventDefinition signalRef="Signal_PairedShared" />
                    </bpmn2:intermediateCatchEvent>
                    <bpmn2:endEvent id="PairedTwinEnd" />
                    <bpmn2:sequenceFlow id="PairedTwinFlow1" sourceRef="PairedTwinStart" targetRef="PairedTwinStep" />
                    <bpmn2:sequenceFlow id="PairedTwinFlow2" sourceRef="PairedTwinStep" targetRef="PairedTwinHold" />
                    <bpmn2:sequenceFlow id="PairedTwinFlow3" sourceRef="PairedTwinHold" targetRef="PairedTwinEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    // start -> catch(CausalSignal) -> external task -> end. Unlike pairedTwinBpmn above, the task on
    // BOTH sides is deliberately GATED BEHIND the shared signal, matching the real RedCollar BPMNs'
    // own task -> catch -> task -> catch shape - so releasing an execution here genuinely gates
    // whatever runs next, which is what mainRequestGatesTwinDelegateAndTwinCompletionGatesMainContinuation
    // needs to prove real Main -> Twin -> Main ordering instead of just data isolation.
    private static String causalManufBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_CausalManuf" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:signal id="Signal_Causal" name="CausalSignal" />
                  <bpmn2:process id="GenericCausalManuf" name="Generic Causal Manuf" isExecutable="true">
                    <bpmn2:startEvent id="CausalManufStart" />
                    <bpmn2:intermediateCatchEvent id="CausalManufCatch" name="Request Barrier">
                      <bpmn2:signalEventDefinition signalRef="Signal_Causal" />
                    </bpmn2:intermediateCatchEvent>
                    <bpmn2:serviceTask id="CausalManufStep" name="Manuf Continue" camunda:type="external"
                        camunda:topic="GenericCausalManufStep" />
                    <bpmn2:endEvent id="CausalManufEnd" />
                    <bpmn2:sequenceFlow id="CausalManufFlow1" sourceRef="CausalManufStart" targetRef="CausalManufCatch" />
                    <bpmn2:sequenceFlow id="CausalManufFlow2" sourceRef="CausalManufCatch" targetRef="CausalManufStep" />
                    <bpmn2:sequenceFlow id="CausalManufFlow3" sourceRef="CausalManufStep" targetRef="CausalManufEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String causalTwinBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_CausalTwin" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:signal id="Signal_CausalTwin" name="CausalSignal" />
                  <bpmn2:process id="GenericCausalTwin" name="Generic Causal Twin" isExecutable="true">
                    <bpmn2:startEvent id="CausalTwinStart" />
                    <bpmn2:intermediateCatchEvent id="CausalTwinCatch" name="Request Barrier">
                      <bpmn2:signalEventDefinition signalRef="Signal_CausalTwin" />
                    </bpmn2:intermediateCatchEvent>
                    <bpmn2:serviceTask id="CausalTwinStep" name="Twin Delegate" camunda:type="external"
                        camunda:topic="GenericCausalTwinStep" />
                    <bpmn2:endEvent id="CausalTwinEnd" />
                    <bpmn2:sequenceFlow id="CausalTwinFlow1" sourceRef="CausalTwinStart" targetRef="CausalTwinCatch" />
                    <bpmn2:sequenceFlow id="CausalTwinFlow2" sourceRef="CausalTwinCatch" targetRef="CausalTwinStep" />
                    <bpmn2:sequenceFlow id="CausalTwinFlow3" sourceRef="CausalTwinStep" targetRef="CausalTwinEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }
}
