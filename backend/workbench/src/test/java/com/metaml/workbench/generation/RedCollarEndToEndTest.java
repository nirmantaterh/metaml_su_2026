package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;

// End-to-end integration test verifying RedCollar target harness generation, launch,
// inter-process signal synchronization, and ML agent external-task execution.
@Tag("slow")
class RedCollarEndToEndTest {

    @TempDir
    Path tempDir;

    private static final Path REPO_ROOT = redCollarBpmnDir();
    private static final Path REAL_TEMPLATE = Path.of("../../templates/camundademo");

    private static Path redCollarBpmnDir() {
        String configured = System.getProperty("redcollar.bpmn.dir", System.getenv("REDCOLLAR_BPMN_DIR"));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("../..");
    }

    private static void assumeFixturesPresent() {
        Assumptions.assumeTrue(Files.isRegularFile(REPO_ROOT.resolve("Manuf-camunda.bpmn"))
                        && Files.isRegularFile(REPO_ROOT.resolve("Twin-camunda.bpmn")),
                "RedCollar BPMNs not found at " + REPO_ROOT.toAbsolutePath()
                        + " - point redcollar.bpmn.dir or REDCOLLAR_BPMN_DIR at the BPMN model directory");
    }

    @Test
    void redCollarProcessesCompileLaunchAndExecuteWithExternalTaskWorkersAndSignalSynchronization() throws Exception {
        assumeFixturesPresent();
        assertThat(Files.isDirectory(REAL_TEMPLATE))
                .as("templates/camundademo must exist at %s", REAL_TEMPLATE.toAbsolutePath())
                .isTrue();

        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        String twinBpmnXml = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));

        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());

        // 1. Generate from authored BPMN definitions.
        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml);

        // Verify generated structure
        String slug = "redcollarmanuf";
        String basePackagePath = "src/main/java/com/metaml/targetplatform/" + slug;
        assertThat(project.directory().resolve(basePackagePath
                + "/controller/manufacturing/GeneratedManufacturingController.java")).exists();
        assertThat(project.directory().resolve(basePackagePath
                + "/controller/twin/GeneratedTwinController.java")).exists();
        assertThat(project.directory().resolve("src/main/resources/processes/RedCollar.Manuf.bpmn")).exists();
        assertThat(project.directory().resolve("src/main/resources/processes/RedCollar.Twin.bpmn")).exists();

        // Verify workers exist (sample check)
        assertThat(project.directory().resolve(basePackagePath
                + "/worker/manufacturing/VerifyOrderWorker.java")).exists();
        assertThat(project.directory().resolve(basePackagePath
                + "/worker/twin/SamplingTwinWorker.java")).exists();

        // Verify signal broadcaster
        assertThat(project.directory().resolve(basePackagePath
                + "/signal/SignalBroadcaster.java")).exists();

        // Verify worker infrastructure (interface + poller)
        assertThat(project.directory().resolve(basePackagePath
                + "/worker/GeneratedExternalTaskWorker.java")).exists();
        assertThat(project.directory().resolve(basePackagePath
                + "/worker/ExternalTaskPoller.java")).exists();

        // 2. Build generated project.
        Process build = new ProcessBuilder(mvnw(project.directory()), "-q", "package", "-DskipTests")
                .directory(project.directory().toFile())
                .redirectErrorStream(true)
                .start();
        String buildOutput = new String(build.getInputStream().readAllBytes());
        boolean buildFinished = build.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
        assertThat(buildFinished).as("mvn build did not finish in time").isTrue();
        assertThat(build.exitValue()).as("generated project failed to build:%n%s", buildOutput).isZero();

        // 3. Launch generated application.
        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched;
            try {
                launched = launcher.launch(project);
            } catch (Exception launchEx) {
                // Capture launch.log for diagnosis before the temp dir is cleaned
                Path launchLog = project.directory().resolve("launch.log");
                String logContent = Files.exists(launchLog) ? Files.readString(launchLog) : "(no launch.log found)";
                throw new AssertionError("Launch failed. launch.log:\n" + logContent, launchEx);
            }
            HttpClient http = HttpClient.newHttpClient();
            String manufBase = "http://localhost:" + launched.port() + "/api/v1/manufacturing";
            String twinBase = "http://localhost:" + launched.port() + "/api/v1/twin";

            // 4. Start paired Main and Twin process instances.
            String pairKey = "redcollar-pair-" + java.util.UUID.randomUUID();
            HttpResponse<String> manufStart = http.send(
                    HttpRequest.newBuilder(URI.create(manufBase + "/start?businessKey=" + pairKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(manufStart.statusCode()).as("manufacturing start failed: %s", manufStart.body())
                    .isEqualTo(200);
            String manufInstanceId = extractProcessInstanceId(manufStart.body());
            // PairRegistry classifies the first registered process as the initiator,
            // gating shared signals for lockstep coordination.
            assertThat(manufStart.body()).as("Main must be classified as the initiator for this pairing key")
                    .contains("\"role\":\"initiator\"");

            HttpResponse<String> twinStart = http.send(
                    HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + pairKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(twinStart.statusCode()).as("twin start failed: %s", twinStart.body())
                    .isEqualTo(200);
            String twinInstanceId = extractProcessInstanceId(twinStart.body());
            assertThat(twinStart.body()).as("Twin must be classified as the responder for this pairing key")
                    .contains("\"role\":\"responder\"");

            // Real Camunda state: both instances actually carry the shared pairing key, not merely
            // what the /start request bodies echoed back.
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";
            assertThat(fetchStatus(http, statusBase, manufInstanceId))
                    .as("Main instance must carry the real business key it was paired with")
                    .contains("\"businessKey\":\"" + pairKey + "\"");
            assertThat(fetchStatus(http, statusBase, twinInstanceId))
                    .as("Twin instance must carry the same real business key as its paired Main")
                    .contains("\"businessKey\":\"" + pairKey + "\"");

            // 5. Wait for workers to execute and verify via logs.
            String log = awaitLogContaining(project.directory(),
                    "Executing generated external-task worker", Duration.ofSeconds(60));
            assertThat(log).as("Manufacturing workers should have executed")
                    .contains("Executing generated external-task worker");

            // Twin ML-agent simulation should produce non-deterministic output
            String twinLog = awaitLogContaining(project.directory(),
                    "[Twin] Invoking simulated ML agent", Duration.ofSeconds(60));
            assertThat(twinLog).as("Twin agent invocation should appear in logs")
                    .contains("[Twin] Invoking simulated ML agent");
            assertThat(twinLog).as("Twin agent should produce runtime result data")
                    .contains("[Twin] Agent invocation result:");

            // Verify execution variables persisted on the active process instance.
            String twinVariables = awaitVariableContaining(http, statusBase, twinInstanceId, "agentInvocationId",
                    Duration.ofSeconds(20));
            assertThat(twinVariables)
                    .as("agentInvocationId must be set as a process variable on the twin instance")
                    .contains("agentInvocationId");

            // Gateway variables should have been set non-deterministically
            String gatewayLog = awaitLogContaining(project.directory(),
                    "Worker completion variables:", Duration.ofSeconds(30));
            assertThat(gatewayLog).as("Gateway-preceding workers should log their variables")
                    .contains("Worker completion variables:");

            // Same causal check for the manufacturing side: orderApproved (or qualityPassed, whichever
            // gateway-preceding worker fired first) must be a real variable on the real instance, read
            // back immediately after the log confirms a gateway-preceding worker just ran.
            String manufVariables = awaitVariableContaining(http, statusBase, manufInstanceId, "Approved",
                    Duration.ofSeconds(20));
            if (!manufVariables.contains("Approved")) {
                // Checking (qualityPassed) may have completed before orderApproved was observed depending on signal timing;
                // either gateway variable confirms runtime state propagation.
                manufVariables = awaitVariableContaining(http, statusBase, manufInstanceId, "qualityPassed",
                        Duration.ofSeconds(10));
            }
            assertThat(manufVariables)
                    .as("a gateway variable (orderApproved or qualityPassed) must be a real process variable on "
                            + "the manufacturing instance itself, not merely a value the worker logged")
                    .containsAnyOf("orderApproved", "qualityPassed");

            // Signal broadcaster should have fired
            // (no explicit log for signal broadcast by design — verification is that processes
            // advance past signal catch events, which is proven by workers executing after them)

            // Verify no hard-coded PASS/FAIL outcomes in the log
            assertThat(twinLog)
                    .doesNotContain("[Twin] ML agent result: PASS")
                    .doesNotContain("[Twin] ML agent result: FAIL");

        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Verifies RedCollar Main-to-Twin signal handoff over an external RabbitMQ broker.
    // Skips execution if no broker is reachable at localhost:15672.
    @Test
    void realRedCollarMainAndTwinCommunicateOverARealRabbitMqBroker() throws Exception {
        assumeFixturesPresent();
        HttpClient rabbitAdmin = HttpClient.newHttpClient();
        if (!rabbitMqReachable(rabbitAdmin)) {
            System.out.println("SKIPPED realRedCollarMainAndTwinCommunicateOverARealRabbitMqBroker: "
                    + "no RabbitMQ broker reachable at localhost:15672 (management API). Start one with "
                    + "'docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management' to run this test.");
            return;
        }

        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        String twinBpmnXml = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));

        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml);

        // Launches generated target platform in background JVM and awaits health check readiness.
        String basePackagePath = "src/main/java/com/metaml/targetplatform/redcollarmanuf/messaging";
        assertThat(project.directory().resolve(basePackagePath + "/RabbitMqConfig.java")).exists();
        assertThat(project.directory().resolve(basePackagePath + "/TaskQueuePublisher.java")).exists();
        assertThat(project.directory().resolve(basePackagePath + "/TaskQueueListener.java")).exists();
        assertThat(project.directory().resolve(basePackagePath + "/ResponseQueuePublisher.java")).exists();
        assertThat(project.directory().resolve(basePackagePath + "/ResponseQueueListener.java")).exists();

        Process build = new ProcessBuilder(mvnw(project.directory()), "-q", "package", "-DskipTests")
                .directory(project.directory().toFile())
                .redirectErrorStream(true)
                .start();
        String buildOutput = new String(build.getInputStream().readAllBytes());
        assertThat(build.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)).isTrue();
        assertThat(build.exitValue()).as("generated project failed to build:%n%s", buildOutput).isZero();

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project, Map.of(
                    "METAML_MESSAGING_ENABLED", "true",
                    "SPRING_RABBITMQ_HOST", "localhost",
                    "SPRING_RABBITMQ_PORT", "5672"));
            HttpClient http = HttpClient.newHttpClient();
            String manufBase = "http://localhost:" + launched.port() + "/api/v1/manufacturing";
            String twinBase = "http://localhost:" + launched.port() + "/api/v1/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";

            String pairKey = "rabbitmq-pair-" + java.util.UUID.randomUUID();
            HttpResponse<String> manufStart = http.send(
                    HttpRequest.newBuilder(URI.create(manufBase + "/start?businessKey=" + pairKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(manufStart.statusCode()).as("manufacturing start failed: %s", manufStart.body())
                    .isEqualTo(200);
            String manufInstanceId = extractProcessInstanceId(manufStart.body());

            HttpResponse<String> twinStart = http.send(
                    HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + pairKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(twinStart.statusCode()).as("twin start failed: %s", twinStart.body()).isEqualTo(200);
            String twinInstanceId = extractProcessInstanceId(twinStart.body());

            // Verify publishing to task and response queues over RabbitMQ exchange.
            String publishLog = awaitLogContaining(project.directory(),
                    "TASK: published activity", Duration.ofSeconds(90));
            assertThat(publishLog)
                    .as("Main->Twin TASK must be published to the real per-activity task queue")
                    .contains("TASK: published activity")
                    .contains("to RabbitMQ exchange");

            String responseLog = awaitLogContaining(project.directory(),
                    "RESPONSE: published activity", Duration.ofSeconds(90));
            assertThat(responseLog)
                    .as("Twin->Main RESPONSE must also be published to the real per-activity response queue")
                    .contains("RESPONSE: published activity")
                    .contains("to RabbitMQ exchange");

            // Verify message consumption and signal delivery.
            String consumeLog = awaitLogContaining(project.directory(),
                    "delivered signal", Duration.ofSeconds(30));
            assertThat(consumeLog)
                    .as("SignalDeliveryListener must have consumed at least one message and delivered the "
                            + "real Camunda signal because of it")
                    .contains("via RabbitMQ");

            // Verify twin delegate executed simulated invocation.
            String twinLog = awaitLogContaining(project.directory(),
                    "[Twin] Invoking simulated ML agent", Duration.ofSeconds(30));
            assertThat(twinLog).contains("[Twin] Invoking simulated ML agent");
            String twinVariables = awaitVariableContaining(http, statusBase, twinInstanceId, "agentInvocationId",
                    Duration.ofSeconds(20));
            assertThat(twinVariables).as("Twin's simulated invocation must be a real process variable")
                    .contains("agentInvocationId");

            // Verify Main process advanced past its RabbitMQ-mediated wait state.
            boolean manufAdvanced = awaitInstanceInactive(http, statusBase, manufInstanceId, Duration.ofSeconds(120))
                    || activityVisitCount(http, statusBase, manufInstanceId, "_FB42C5F3-6B4A-49A0-BCFD-BE2666946C16")
                            >= 1;
            assertThat(manufAdvanced).as("Main must have advanced past its request barrier").isTrue();

            // Query RabbitMQ management API to verify queue creation and message throughput.
            String allQueuesJson = listRabbitQueues(rabbitAdmin);
            java.util.List<String> ownQueues = queueSegmentsMatchingPrefix(allQueuesJson, project.projectId());
            assertThat(ownQueues).as("broker must report this project's own task/response queues").isNotEmpty();
            long publishCount = ownQueues.stream().mapToLong(q -> extractLongField(q, "publish")).sum();
            long deliverCount = ownQueues.stream().mapToLong(q -> extractLongField(q, "deliver_get")).sum();
            assertThat(publishCount).as("aggregate broker-reported publish count across this project's queues")
                    .isGreaterThan(0);
            assertThat(deliverCount).as("aggregate broker-reported deliver count across this project's queues")
                    .isGreaterThan(0);
        } finally {
            launcher.stop(project.projectId());
        }
    }

    private static boolean rabbitMqReachable(HttpClient http) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:15672/api/overview"))
                            .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                    .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8)))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // Lists every queue on the default vhost - queue names are generated per project id (see
    // SpringBootProjectGenerator's queue-naming), so there is no fixed name to look up directly.
    private static String listRabbitQueues(HttpClient http) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:15672/api/queues/%2f"))
                        .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8)))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("RabbitMQ management API queue listing").isEqualTo(200);
        return response.body();
    }

    // Picks out the raw JSON object segment for each queue whose "name" contains nameFragment
    // (here, this project's own id), so per-queue fields (like publish/deliver_get counts) can be
    // read back out with extractLongField without a full JSON parser.
    private static java.util.List<String> queueSegmentsMatchingPrefix(String queueListJson, String nameFragment) {
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.List<Integer> starts = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"name\":\"([^\"]*)\"")
                .matcher(queueListJson);
        while (matcher.find()) {
            names.add(matcher.group(1));
            starts.add(matcher.start());
        }
        java.util.List<String> segments = new java.util.ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).contains(nameFragment)) {
                int start = starts.get(i);
                int end = (i + 1 < starts.size()) ? starts.get(i + 1) : queueListJson.length();
                segments.add(queueListJson.substring(start, end));
            }
        }
        return segments;
    }

        // Verifies twin external task workers execute and record simulation outputs in process variables.
    private static long extractLongField(String json, String fieldName) {
        String marker = "\"" + fieldName + "\":";
        int key = json.indexOf(marker);
        if (key < 0) {
            return 0;
        }
        int start = key + marker.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        return Long.parseLong(json.substring(start, end));
    }

    // Validates that quality-rework loops fallback correctly when paired Twin is no longer co-waiting,
    // ensuring all process instances reach completion without deadlocks.
    @Test
    void reworkLoopOnQualityFailureFallsBackCorrectlyAndEveryPairStillCompletes() throws Exception {
        assumeFixturesPresent();
        assertThat(Files.isDirectory(REAL_TEMPLATE)).isTrue();
        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        String twinBpmnXml = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));
        String stitchingCatchActivityId = "_3B1579A6-631E-49C1-A21A-C40D5DE37836";

        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml);

        Process build = new ProcessBuilder(mvnw(project.directory()), "-q", "package", "-DskipTests")
                .directory(project.directory().toFile())
                .redirectErrorStream(true)
                .start();
        String buildOutput = new String(build.getInputStream().readAllBytes());
        assertThat(build.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)).isTrue();
        assertThat(build.exitValue()).as("generated project failed to build:%n%s", buildOutput).isZero();

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project);
            HttpClient http = HttpClient.newHttpClient();
            String manufBase = "http://localhost:" + launched.port() + "/api/v1/manufacturing";
            String twinBase = "http://localhost:" + launched.port() + "/api/v1/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";

            int pairCount = 6;
            String[] keys = new String[pairCount];
            String[] mainIds = new String[pairCount];
            String[] twinIds = new String[pairCount];
            for (int i = 0; i < pairCount; i++) {
                keys[i] = "rework-pair-" + i + "-" + java.util.UUID.randomUUID();
                HttpResponse<String> mainStart = http.send(
                        HttpRequest.newBuilder(URI.create(manufBase + "/start?businessKey=" + keys[i]))
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(mainStart.statusCode()).isEqualTo(200);
                mainIds[i] = extractProcessInstanceId(mainStart.body());
                HttpResponse<String> twinStart = http.send(
                        HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + keys[i]))
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(twinStart.statusCode()).isEqualTo(200);
                twinIds[i] = extractProcessInstanceId(twinStart.body());
            }

            // Every pair must reach a terminal state - none deadlocked, whether or not it happened
            // to take the rework path.
            for (int i = 0; i < pairCount; i++) {
                assertThat(awaitInstanceInactive(http, statusBase, mainIds[i], Duration.ofSeconds(150)))
                        .as("pair %d's Main must reach completion (no deadlock)", i).isTrue();
                assertThat(awaitInstanceInactive(http, statusBase, twinIds[i], Duration.ofSeconds(30)))
                        .as("pair %d's Twin must reach completion (no deadlock)", i).isTrue();
            }

            // Verify via HistoryService that rework path execution occurred across runs:
            boolean anyPairReworked = false;
            for (int i = 0; i < pairCount; i++) {
                long visits = activityVisitCount(http, statusBase, mainIds[i], stitchingCatchActivityId);
                assertThat(visits).as("pair %d must have visited the stitching barrier at least once", i)
                        .isGreaterThanOrEqualTo(1);
                if (visits >= 2) {
                    anyPairReworked = true;
                }
            }
            assertThat(anyPairReworked)
                    .as("at least one of %d pairs should have taken the real quality-rework path "
                            + "(P(none do) = 0.5^%d ≈ %.3f%%)", pairCount, pairCount, Math.pow(0.5, pairCount) * 100)
                    .isTrue();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Validates manual REST completion through /{id}/{activity}/complete reaching NotificationBridge.
    @Test
    void manualRestCompletionReachesNotificationBridgeAndTheLiveDisabledMessagingDefault() throws Exception {
        assumeFixturesPresent();
        assertThat(Files.isDirectory(REAL_TEMPLATE)).isTrue();
        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        String twinBpmnXml = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));
        String orderMgmtActivityId = "_E12DB58F-C11B-42BF-BA46-88B171B228EC";

        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml);

        Path controllerFile = project.directory().resolve(
                "src/main/java/com/metaml/targetplatform/redcollarmanuf"
                        + "/controller/manufacturing/GeneratedManufacturingController.java");
        String orderMgmtSlug = endpointSlugForActivity(controllerFile, orderMgmtActivityId);

        Process build = new ProcessBuilder(mvnw(project.directory()), "-q", "package", "-DskipTests")
                .directory(project.directory().toFile())
                .redirectErrorStream(true)
                .start();
        String buildOutput = new String(build.getInputStream().readAllBytes());
        assertThat(build.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)).isTrue();
        assertThat(build.exitValue()).as("generated project failed to build:%n%s", buildOutput).isZero();

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project);
            HttpClient http = HttpClient.newHttpClient();
            String manufBase = "http://localhost:" + launched.port() + "/api/v1/manufacturing";

            boolean won = false;
            for (int attempt = 0; attempt < 20 && !won; attempt++) {
                HttpResponse<String> start = http.send(
                        HttpRequest.newBuilder(URI.create(manufBase + "/start"))
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                String instanceId = extractProcessInstanceId(start.body());
                HttpResponse<String> complete = http.send(
                        HttpRequest.newBuilder(
                                        URI.create(manufBase + "/" + instanceId + "/" + orderMgmtSlug + "/complete"))
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (complete.statusCode() == 200 && complete.body().contains("\"completed\":[\"")) {
                    won = true;
                }
            }
            assertThat(won).as("this test's own REST /complete call must win the race against "
                    + "ExternalTaskPoller at least once in 20 attempts").isTrue();

            String log = awaitLogContaining(project.directory(),
                    "[manufacturing -> twin] activity '" + orderMgmtActivityId + "'", Duration.ofSeconds(15));
            assertThat(log)
                    .as("NotificationBridge must have been reached by the manual completion path, live")
                    .contains("[manufacturing -> twin] activity '" + orderMgmtActivityId
                            + "' complete - notifying twin");
            assertThat(log)
                    .as("HarnessMessagePublisher defaults to messaging disabled")
                    .contains("[messaging disabled] would publish")
                    .contains("exchange 'twin.exchange'")
                    .contains("key 'twin.stage.update'");
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Verifies that automatic ExternalTaskPoller completion is architecturally independent
    // and does not invoke NotificationBridge or RabbitMQ.
    @Test
    void automaticPollerCompletionNeverInvokesNotificationBridgeOrRabbitMq() throws Exception {
        assumeFixturesPresent();
        assertThat(Files.isDirectory(REAL_TEMPLATE)).isTrue();
        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        String twinBpmnXml = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));
        String orderMgmtActivityId = "_E12DB58F-C11B-42BF-BA46-88B171B228EC";

        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml);

        Process build = new ProcessBuilder(mvnw(project.directory()), "-q", "package", "-DskipTests")
                .directory(project.directory().toFile())
                .redirectErrorStream(true)
                .start();
        String buildOutput = new String(build.getInputStream().readAllBytes());
        assertThat(build.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)).isTrue();
        assertThat(build.exitValue()).as("generated project failed to build:%n%s", buildOutput).isZero();

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project);
            HttpClient http = HttpClient.newHttpClient();
            String manufBase = "http://localhost:" + launched.port() + "/api/v1/manufacturing";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";

            HttpResponse<String> start = http.send(
                    HttpRequest.newBuilder(URI.create(manufBase + "/start"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            String instanceId = extractProcessInstanceId(start.body());

            // No REST /complete call is made; the poller advances this instance.
            String log = awaitLogContaining(project.directory(),
                    "Executing generated external-task worker for activity \"Order Mgmt", Duration.ofSeconds(30));
            assertThat(log).contains("Executing generated external-task worker");

            // Authoritative confirmation the activity actually completed (not merely logged as
            // attempted): its historic visit count is at least 1.
            long visits = activityVisitCount(http, statusBase, instanceId, orderMgmtActivityId);
            assertThat(visits).as("OrderMgmtInitialization must have been completed by the poller")
                    .isGreaterThanOrEqualTo(1);

            // The defining negative assertion: NotificationBridge (and therefore RabbitMQ) is never
            // reached by this instance's own automatic completion, across the whole log.
            String fullLog = Files.readString(project.directory().resolve("launch.log"));
            assertThat(fullLog)
                    .as("automatic poller completion must never invoke NotificationBridge")
                    .doesNotContain("notifying twin")
                    .doesNotContain("notifying manufacturing")
                    .doesNotContain("[messaging disabled] would publish");
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Parses the generated controller source for the REST endpoint slug matching a given BPMN
    // activity id, using the exact comment format renderActivityEndpoint emits - ground truth from
    // the actual generated file, not a reimplementation of the generator's own slugging logic.
    private static String endpointSlugForActivity(Path controllerFile, String activityId) throws IOException {
        String content = Files.readString(controllerFile);
        String marker = "(id: " + activityId + ")";
        int idx = content.indexOf(marker);
        assertThat(idx).as("activity %s not found in generated controller %s", activityId, controllerFile)
                .isGreaterThanOrEqualTo(0);
        String mappingPrefix = "@PostMapping(\"/{processInstanceId}/";
        int mappingStart = content.indexOf(mappingPrefix, idx);
        int slugStart = mappingStart + mappingPrefix.length();
        int slugEnd = content.indexOf("/complete", slugStart);
        return content.substring(slugStart, slugEnd);
    }

    private static boolean awaitInstanceInactive(HttpClient http, String statusBase, String processInstanceId,
            Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (fetchStatus(http, statusBase, processInstanceId).contains("\"active\":false")) {
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    // Reads the total historic visit count for one BPMN activity on one process instance via the
    // generic activity-history endpoint (HistoryService-backed) - authoritative even after the
    // instance itself has completed and dropped out of RuntimeService.
    private static long activityVisitCount(HttpClient http, String statusBase, String processInstanceId,
            String activityId) throws IOException, InterruptedException {
        String body = http.send(
                        HttpRequest.newBuilder(
                                        URI.create(statusBase + "/" + processInstanceId + "/activity-history/"
                                                + activityId + "/count"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
        int key = body.indexOf("\"visitCount\"");
        assertThat(key).as("visitCount missing from response: %s", body).isGreaterThanOrEqualTo(0);
        int colon = body.indexOf(':', key);
        int end = colon + 1;
        while (end < body.length() && (Character.isDigit(body.charAt(end)))) {
            end++;
        }
        return Long.parseLong(body.substring(colon + 1, end).trim());
    }

    private static String fetchStatus(HttpClient http, String statusBase, String processInstanceId)
            throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status")).GET()
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }

        // Verifies signal broadcasting advances twin catch events in synchronization with proxy process.
    private static String awaitVariableContaining(HttpClient http, String statusBase, String processInstanceId,
            String variableNameFragment, Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String lastBody = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status")).GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            lastBody = response.body();
            if (lastBody.contains(variableNameFragment)) {
                return lastBody;
            }
            Thread.sleep(300);
        }
        return lastBody;
    }

    private static String extractProcessInstanceId(String json) {
        int key = json.indexOf("\"processInstanceId\"");
        int firstQuote = json.indexOf('"', key + "\"processInstanceId\"".length() + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static String awaitLogContaining(Path projectDir, String fragment, Duration timeout) throws IOException {
        Path logFile = projectDir.resolve("launch.log");
        Instant deadline = Instant.now().plus(timeout);
        String log = "";
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(logFile)) {
                log = Files.readString(logFile);
                if (log.contains(fragment)) {
                    return log;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for '" + fragment + "' in launch.log:\n" + log);
    }

    private static String mvnw(Path projectDir) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = projectDir.resolve(windows ? "mvnw.cmd" : "mvnw");
        return wrapper.toAbsolutePath().toString();
    }
}
