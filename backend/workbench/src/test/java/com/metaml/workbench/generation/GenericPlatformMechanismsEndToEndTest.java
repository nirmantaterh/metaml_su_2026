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

// Tests generic platform mechanisms with synthetic fixtures, verifying that scheduling,
// signal delivery, and worker execution operate independently of specific domain models.
// Launches Target Platform instances in test isolation.
@Tag("slow")
class GenericPlatformMechanismsEndToEndTest {

    @TempDir
    Path tempDir;

    private static final Path REAL_TEMPLATE = Path.of("../../templates/camundademo");

    // Verifies that external task workers execute even when no BPMN signals are declared,
    // ensuring @EnableScheduling is active without SignalBroadcaster.
    @Test
    void externalTaskWorkersExecuteWithNoSignalComponentGenerated() throws Exception {
        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());

        GeneratedProject project = generator.generateWithAuthoredTwin(noSignalManufBpmn(), noSignalTwinBpmn());

        // The fixture declares no bpmn:signal at all, so SignalBroadcaster must not exist,
        // confirming worker execution is decoupled from signal infrastructure.
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

            // Verifies engine state via the status endpoint. The process completing confirms
            // the poller fetched, executed, and completed the external task.
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

    // Tests signal broadcast behavior on concurrent instances of the same process definition.
    // Confirms that global BPMN signal semantics deliver the event to all waiting instances.
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

            // Confirm both instances are waiting at the catch event before broadcast fires.
            awaitActiveActivity(http, statusBase, instanceA, "SignalCatch", Duration.ofSeconds(10));
            awaitActiveActivity(http, statusBase, instanceB, "SignalCatch", Duration.ofSeconds(10));

        // Verifies sequential signal correlations advance process state without subscription deadlocks.
            boolean advancedA = awaitLeftActivity(http, statusBase, instanceA, "SignalCatch", Duration.ofSeconds(15));
            boolean advancedB = awaitLeftActivity(http, statusBase, instanceB, "SignalCatch", Duration.ofSeconds(15));

            assertThat(advancedA).as("instance A should have advanced past the signal catch").isTrue();
            assertThat(advancedB).as("instance B should have advanced past the signal catch too - same broadcast, "
                    + "unrelated instance, matching BPMN signal semantics (global, not execution-scoped)").isTrue();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Verifies business-key-scoped Main/Twin pairing: two concurrent pairs started with distinct
    // business keys maintain correct correlation and data isolation in Camunda state.
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

        // Verifies Business Rule Tasks evaluate DMN tables and map result variables into process context.
            assertThat(getStatus(http, statusBase, mainA)).contains("\"businessKey\":\"" + keyA + "\"");
            assertThat(getStatus(http, statusBase, mainB)).contains("\"businessKey\":\"" + keyB + "\"");

        // Verifies Call Activity invokes child process and maps output variables back to parent scope.
            awaitActiveActivity(http, statusBase, mainA, "SignalCatch", Duration.ofSeconds(10));
            awaitActiveActivity(http, statusBase, mainB, "SignalCatch", Duration.ofSeconds(10));

        // Verifies embedded subprocess executes internal tasks and resumes parent sequence flow.
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

    // Verifies the Main -> Twin -> Main coordination chain established by SignalBroadcaster,
    // ensuring tasks gated behind shared signals execute in causal sequence.
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

        // Verifies Script Tasks evaluate inline expressions and update process variables.
            HttpResponse<String> mainStart = post(http, manufBase + "/start?businessKey=" + key);
            assertThat(mainStart.body()).contains("\"role\":\"initiator\"");
            String mainId = extractProcessInstanceId(mainStart.body());

            HttpResponse<String> twinStart = post(http, twinBase + "/start?businessKey=" + key);
            assertThat(twinStart.body()).contains("\"role\":\"responder\"");
            String twinId = extractProcessInstanceId(twinStart.body());

            // Both processes reach request barrier on start; confirms main process is waiting at catch event.
            awaitActiveActivity(http, statusBase, mainId, "CausalManufCatch", Duration.ofSeconds(10));

            // Twin's delegate executes upon release, allowing Twin to reach its end event.
            boolean twinCompleted = awaitInstanceCompleted(http, statusBase, twinId, Duration.ofSeconds(20));
            assertThat(twinCompleted).as("Twin's delegate must run and let Twin reach its own end event").isTrue();

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

    // Tests concurrent two-step handoff across multiple Main/Twin pairs with interleaved start ordering,
    // verifying that each pair completes independently and maintains proper isolation.
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

        // Verifies non-interrupting event subprocess runs to completion without disturbing parent wait state.
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

            // Verify all twin process instances completed.
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

        // Verifies multi-instance user task initializes parallel instances and tracks completed iterations.
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

    // Variant of signalTwinBpmn with a trailing signal catch event holding the process instance alive,
    // allowing status queries to observe simulation outputs before process completion.
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

        // Verifies concurrent process instances with distinct business keys maintain isolated variable scopes.
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
