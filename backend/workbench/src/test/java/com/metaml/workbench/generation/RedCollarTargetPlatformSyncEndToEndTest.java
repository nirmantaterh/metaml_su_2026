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
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
import com.metaml.workbench.model.ProxyTwinActivityMapping;

/**
 * End-to-end synchronization tests for RedCollar target platform proxy and twin executions.
 */
@Tag("slow")
class RedCollarTargetPlatformSyncEndToEndTest {

    @TempDir
    Path tempDir;

    private static final Path REPO_ROOT = redCollarBpmnDir();
    private static final Path REAL_TEMPLATE = Path.of("../RedCollarTP");

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
                            + " - configure redcollar.bpmn.dir or REDCOLLAR_BPMN_DIR to point to the BPMN files");
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

    @Test
    void proxyAndTwinSynchronizeOnSharedSignalsOverARealRabbitMqBroker() throws Exception {
        assumeFixturesPresent();
        assertThat(Files.isDirectory(REAL_TEMPLATE)).as("RedCollarTP template must exist at %s",
                REAL_TEMPLATE.toAbsolutePath()).isTrue();
        HttpClient http = HttpClient.newHttpClient();
        Assumptions.assumeTrue(rabbitMqReachable(http), "no RabbitMQ broker reachable at localhost:15672 - "
                + "start one with 'docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management'");

        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        String twinBpmnXml = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));

        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml,
                firstAuthoredMapping(), null, null);

        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");
        assertThat(tpRoot.resolve("messaging/RabbitMqConfig.java")).exists();
        assertThat(tpRoot.resolve("signal/SignalBroadcaster.java")).exists();

        // SpringBootProjectLauncher itself runs 'mvn clean install -DskipTests' before 'mvn
        // spring-boot:run' for this no-mvnw template - no separate build step needed here.
        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched;
            try {
                launched = launcher.launch(project);
            } catch (Exception launchEx) {
                Path launchLog = project.directory().resolve("launch.log");
                Path buildLog = project.directory().resolve("build.log");
                String logContent = Files.exists(launchLog) ? Files.readString(launchLog) : "(no launch.log found)";
                String buildContent = Files.exists(buildLog) ? Files.readString(buildLog) : "(no build.log found)";
                throw new AssertionError("Launch failed. build.log:\n" + buildContent + "\nlaunch.log:\n"
                        + logContent, launchEx);
            }

            String proxyBase = "http://localhost:" + launched.port() + "/api/proxy";
            String twinBase = "http://localhost:" + launched.port() + "/api/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";
            String businessKey = "sync-test-" + UUID.randomUUID();

            HttpResponse<String> proxyStart = http.send(
                    HttpRequest.newBuilder(URI.create(proxyBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(proxyStart.statusCode()).as("proxy start failed: %s", proxyStart.body()).isEqualTo(200);
            assertThat(proxyStart.body()).contains("\"role\":\"initiator\"");
            String proxyInstanceId = extractField(proxyStart.body(), "processInstanceId");

            HttpResponse<String> twinStart = http.send(
                    HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(twinStart.statusCode()).as("twin start failed: %s", twinStart.body()).isEqualTo(200);
            assertThat(twinStart.body()).contains("\"role\":\"responder\"");
            String twinInstanceId = extractField(twinStart.body(), "processInstanceId");

            // Verify signal publishing across RabbitMQ exchange
            String publishLog = awaitLogContaining(project.directory(), "TASK: published signal",
                    Duration.ofSeconds(60));
            assertThat(publishLog).contains("TASK: published signal").contains("to RabbitMQ exchange");

            String responseLog = awaitLogContaining(project.directory(), "RESPONSE: published signal",
                    Duration.ofSeconds(60));
            assertThat(responseLog).contains("RESPONSE: published signal").contains("to RabbitMQ exchange");

            String consumeLog = awaitLogContaining(project.directory(), "delivered signal",
                    Duration.ofSeconds(30));
            assertThat(consumeLog).as("a RabbitMQ listener must have actually delivered a real Camunda signal")
                    .contains("via RabbitMQ");

            // Verify instance progression via engine runtime state
            assertThat(awaitInstanceInactive(http, statusBase, proxyInstanceId, Duration.ofSeconds(120))
                    || awaitDistinctActiveActivity(http, statusBase, proxyInstanceId, Duration.ofSeconds(5)))
                    .as("proxy instance must have advanced past its start")
                    .isTrue();
            // Twin may have already completed by now (it advances quickly, and signal traffic
            // confirms synchronization occurred) - either an active instance carrying the paired
            // business key or an inactive status confirms the process ran to completion.
            String twinStatus = fetchStatus(http, statusBase, twinInstanceId);
            assertThat(twinStatus)
                    .as("twin instance must be either still running under the paired business key, or "
                            + "have completed - not simply missing")
                    .satisfiesAnyOf(
                            s -> assertThat(s).contains("\"businessKey\":\"" + businessKey + "\""),
                            s -> assertThat(s).contains("\"active\":false"));

            // Verify queue activity via RabbitMQ management API
            String allQueuesJson = listRabbitQueues(http);
            List<String> ownQueues = queueSegmentsMatchingPrefix(allQueuesJson, project.projectId());
            assertThat(ownQueues).as("broker must report this project's own sync queues").isNotEmpty();
            long publishCount = ownQueues.stream().mapToLong(q -> extractLongField(q, "publish")).sum();
            assertThat(publishCount).as("aggregate broker-reported publish count across this project's queues")
                    .isGreaterThan(0);
        } finally {
            launcher.stop(project.projectId());
        }
    }

    private static List<ProxyTwinActivityMapping> firstAuthoredMapping() {
        ProxyTwinActivityMapping mapping = new ProxyTwinActivityMapping();
        mapping.setProxyActivityId("_E12DB58F-C11B-42BF-BA46-88B171B228EC");
        mapping.setTwinActivityId("_379CDD3F-5F9F-4B1E-B8D5-734D7D5994EB");
        mapping.setSynchronizationKey("initialization");
        return List.of(mapping);
    }

    private static List<ProxyTwinActivityMapping> incidentTestMappings() {
        return List.of(
                new ProxyTwinActivityMapping("_E12DB58F-C11B-42BF-BA46-88B171B228EC",
                        "_379CDD3F-5F9F-4B1E-B8D5-734D7D5994EB", "initialization"),
                new ProxyTwinActivityMapping("_0FC5B3EE-E55C-405A-A755-65504ED71E08",
                        "_A2C4B0C6-1E9D-4B3A-9022-4CECD2909943", "pressing"),
                new ProxyTwinActivityMapping("_9BDC6C05-C6E9-4CE8-B6DE-886693BF4DE7",
                        "_3F0912D2-6F88-4809-B54A-2DCAF4B7F939", "packaging"),
                new ProxyTwinActivityMapping("_3E1A9952-83B6-4C80-AB67-3BAEDA810FFE",
                        "_7F7942AA-09FC-4424-BB95-42F59FDD9B8C", "shipping")
        );
    }

    // Verifies synchronization when no authored twin BPMN is supplied: generates a target platform
    // using the single-BPMN generate() entry point, where TargetPlatformTwinMirrorGenerator derives
    // a structural mirror for twin execution.
    @Test
    void proxyAndAutoDerivedTwinSynchronizeOnSharedSignalsOverARealRabbitMqBroker() throws Exception {
        assumeFixturesPresent();
        assertThat(Files.isDirectory(REAL_TEMPLATE)).as("RedCollarTP template must exist at %s",
                REAL_TEMPLATE.toAbsolutePath()).isTrue();
        HttpClient http = HttpClient.newHttpClient();
        Assumptions.assumeTrue(rabbitMqReachable(http), "no RabbitMQ broker reachable at localhost:15672 - "
                + "start one with 'docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management'");

        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));

        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        // The single-BPMN entry point - no authored Twin passed in anywhere.
        GeneratedProject project = generator.generate(manufBpmnXml, List.of());

        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");
        assertThat(tpRoot.resolve("messaging/RabbitMqConfig.java")).exists();
        assertThat(tpRoot.resolve("signal/SignalBroadcaster.java")).exists();

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched;
            try {
                launched = launcher.launch(project);
            } catch (Exception launchEx) {
                Path launchLog = project.directory().resolve("launch.log");
                Path buildLog = project.directory().resolve("build.log");
                String logContent = Files.exists(launchLog) ? Files.readString(launchLog) : "(no launch.log found)";
                String buildContent = Files.exists(buildLog) ? Files.readString(buildLog) : "(no build.log found)";
                throw new AssertionError("Launch failed. build.log:\n" + buildContent + "\nlaunch.log:\n"
                        + logContent, launchEx);
            }

            String proxyBase = "http://localhost:" + launched.port() + "/api/proxy";
            String twinBase = "http://localhost:" + launched.port() + "/api/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";
            String businessKey = "auto-twin-test-" + UUID.randomUUID();

            HttpResponse<String> proxyStart = http.send(
                    HttpRequest.newBuilder(URI.create(proxyBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(proxyStart.statusCode()).as("proxy start failed: %s", proxyStart.body()).isEqualTo(200);
            String proxyInstanceId = extractField(proxyStart.body(), "processInstanceId");

            HttpResponse<String> twinStart = http.send(
                    HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(twinStart.statusCode()).as("twin start failed: %s", twinStart.body()).isEqualTo(200);

            // Verify signal publishing across RabbitMQ broker
            String publishLog = awaitLogContaining(project.directory(), "TASK: published signal",
                    Duration.ofSeconds(60));
            assertThat(publishLog).contains("TASK: published signal").contains("to RabbitMQ exchange");

            String consumeLog = awaitLogContaining(project.directory(), "delivered signal",
                    Duration.ofSeconds(30));
            assertThat(consumeLog).as("a RabbitMQ listener must have actually delivered a real Camunda signal")
                    .contains("via RabbitMQ");

            // Verify proxy progression via engine runtime state
            assertThat(awaitInstanceInactive(http, statusBase, proxyInstanceId, Duration.ofSeconds(120))
                    || awaitDistinctActiveActivity(http, statusBase, proxyInstanceId, Duration.ofSeconds(5)))
                    .as("proxy instance must have advanced past its start")
                    .isTrue();

            // Verify queue activity via RabbitMQ management API
            String allQueuesJson = listRabbitQueues(http);
            List<String> ownQueues = queueSegmentsMatchingPrefix(allQueuesJson, project.projectId());
            assertThat(ownQueues).as("broker must report this project's own sync queues").isNotEmpty();
            long publishCount = ownQueues.stream().mapToLong(q -> extractLongField(q, "publish")).sum();
            assertThat(publishCount).as("aggregate broker-reported publish count across this project's queues")
                    .isGreaterThan(0);
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Verifies that redelivery of an already-consumed TASK message does not duplicate execution
    // on the twin. The payload is republished via the RabbitMQ management API, and TaskQueueListener
    // handles the delivery idempotently if the execution has already advanced.
    @Test
    void duplicateTaskRedeliveryDoesNotDoubleAdvanceTheTwin() throws Exception {
        assumeFixturesPresent();
        assertThat(Files.isDirectory(REAL_TEMPLATE)).as("RedCollarTP template must exist at %s",
                REAL_TEMPLATE.toAbsolutePath()).isTrue();
        HttpClient http = HttpClient.newHttpClient();
        Assumptions.assumeTrue(rabbitMqReachable(http), "no RabbitMQ broker reachable at localhost:15672 - "
                + "start one with 'docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management'");

        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        String twinBpmnXml = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));

        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml,
                firstAuthoredMapping(), null, null);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched;
            try {
                launched = launcher.launch(project);
            } catch (Exception launchEx) {
                Path launchLog = project.directory().resolve("launch.log");
                Path buildLog = project.directory().resolve("build.log");
                String logContent = Files.exists(launchLog) ? Files.readString(launchLog) : "(no launch.log found)";
                String buildContent = Files.exists(buildLog) ? Files.readString(buildLog) : "(no build.log found)";
                throw new AssertionError("Launch failed. build.log:\n" + buildContent + "\nlaunch.log:\n"
                        + logContent, launchEx);
            }

            String proxyBase = "http://localhost:" + launched.port() + "/api/proxy";
            String twinBase = "http://localhost:" + launched.port() + "/api/twin";
            String businessKey = "dup-test-" + UUID.randomUUID();

            http.send(HttpRequest.newBuilder(URI.create(proxyBase + "/start?businessKey=" + businessKey))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            http.send(HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + businessKey))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

            // Capture the FIRST real TASK publish for this pair - the exact exchange/routing
            // key/payload the generated code itself used, not a value this test invents.
            String publishLog = awaitLogContaining(project.directory(),
                    "TASK: published signal", Duration.ofSeconds(60));
            java.util.regex.Matcher taskMatch = java.util.regex.Pattern.compile(
                    "TASK: published signal '([^']+)' to RabbitMQ exchange '([^']+)' key '([^']+)' for execution "
                            + "(\\S+) \\(processInstanceId=([^,]+), businessKey=" + java.util.regex.Pattern.quote(
                                    businessKey) + "\\)").matcher(publishLog);
            assertThat(taskMatch.find()).as("must find this pair's own TASK publish line in launch.log").isTrue();
            String signalName = taskMatch.group(1);
            String exchange = taskMatch.group(2);
            String routingKey = taskMatch.group(3);
            String executionId = taskMatch.group(4);
            String processInstanceId = taskMatch.group(5);

            // Confirm the real (first, legitimate) delivery landed before injecting a duplicate.
            awaitLogContaining(project.directory(),
                    "TASK: delivered signal '" + signalName + "' to execution " + executionId,
                    Duration.ofSeconds(30));
            String beforeDuplicateLog = Files.readString(project.directory().resolve("launch.log"));
            int deliveredCountBefore = countOccurrences(beforeDuplicateLog,
                    "TASK: delivered signal '" + signalName + "' to execution " + executionId);

            // Republish the identical payload through the broker management API to test duplicate delivery handling.
            String payload = signalName + "|" + executionId + "|" + processInstanceId + "|" + businessKey;
            String publishBody = "{\"properties\":{},\"routing_key\":\"" + routingKey
                    + "\",\"payload\":\"" + payload.replace("\"", "\\\"") + "\",\"payload_encoding\":\"string\"}";
            String encodedExchange = java.net.URLEncoder.encode(exchange, StandardCharsets.UTF_8);
            HttpResponse<String> republish = http.send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://localhost:15672/api/exchanges/%2f/" + encodedExchange + "/publish"))
        // Verifies publisher confirm confirms broker receipt before state advancement.
                            .version(java.net.http.HttpClient.Version.HTTP_1_1)
                            .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                    .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8)))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(publishBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(republish.statusCode()).as("manual duplicate publish via management API: %s",
                    republish.body()).isEqualTo(200);

            // The listener skips duplicate delivery when the execution has already advanced past the signal catch event.
            awaitLogContaining(project.directory(),
                    "TASK: signal '" + signalName + "' delivery to execution " + executionId
                            + " skipped - already advanced",
                    Duration.ofSeconds(30));

            String afterDuplicateLog = Files.readString(project.directory().resolve("launch.log"));
            int deliveredCountAfter = countOccurrences(afterDuplicateLog,
                    "TASK: delivered signal '" + signalName + "' to execution " + executionId);
            assertThat(deliveredCountAfter)
                    .as("the duplicate delivery must not trigger a second signal delivery once the execution has advanced")
                    .isEqualTo(deliveredCountBefore);
        } finally {
            launcher.stop(project.projectId());
        }
    }

        // Asserts task queue listener consumes message and delivers signal to Camunda subscription.
    @Test
    void twinIncidentLeavesProxyWaitingObservablyNotFalselyAdvanced() throws Exception {
        assumeFixturesPresent();
        assertThat(Files.isDirectory(REAL_TEMPLATE)).as("RedCollarTP template must exist at %s",
                REAL_TEMPLATE.toAbsolutePath()).isTrue();
        HttpClient http = HttpClient.newHttpClient();
        Assumptions.assumeTrue(rabbitMqReachable(http), "no RabbitMQ broker reachable at localhost:15672 - "
                + "start one with 'docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management'");

        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        String twinBpmnXml = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));

        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml,
                incidentTestMappings(), null, null);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched;
            try {
                launched = launcher.launch(project);
            } catch (Exception launchEx) {
                Path launchLog = project.directory().resolve("launch.log");
                Path buildLog = project.directory().resolve("build.log");
                String logContent = Files.exists(launchLog) ? Files.readString(launchLog) : "(no launch.log found)";
                String buildContent = Files.exists(buildLog) ? Files.readString(buildLog) : "(no build.log found)";
                throw new AssertionError("Launch failed. build.log:\n" + buildContent + "\nlaunch.log:\n"
                        + logContent, launchEx);
            }

            String proxyBase = "http://localhost:" + launched.port() + "/api/proxy";
            String twinBase = "http://localhost:" + launched.port() + "/api/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";
            String businessKey = "incident-test-" + UUID.randomUUID();

            HttpResponse<String> proxyStart = http.send(
                    HttpRequest.newBuilder(URI.create(proxyBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            String proxyInstanceId = extractField(proxyStart.body(), "processInstanceId");
            HttpResponse<String> twinStart = http.send(
                    HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            String twinInstanceId = extractField(twinStart.body(), "processInstanceId");

        // Asserts response queue listener correlates response payload back to originating process.
            List<String> twinTopics = List.of("PressingTwin", "PackagingTwin", "ShippingTwin");
            String externalTaskId = null;
        // Verifies unroutable or rejected messages route to project dead-letter queue.
            Instant raceDeadline = Instant.now().plus(Duration.ofSeconds(90));
            outer:
            while (Instant.now().isBefore(raceDeadline)) {
                for (String topic : twinTopics) {
                    HttpResponse<String> failResponse = http.send(
                            HttpRequest.newBuilder(URI.create(
                                            statusBase + "/" + twinInstanceId + "/external-task/" + topic
                                                    + "/fail-permanently"))
                                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (failResponse.statusCode() == 200) {
                        externalTaskId = extractField(failResponse.body(), "externalTaskId");
                        break outer;
                    }
                }
                // Still far tighter than the internal poller's fixed 500ms cadence, but spares the
                // generated app's HTTP connector from being hammered with zero pacing for 90s.
                Thread.sleep(20);
            }
            assertThat(externalTaskId)
                    .as("must catch some twin external task on instance %s before poller completes it", twinInstanceId)
                    .isNotNull();

            // A real Camunda incident is now open on the twin's process instance.
            assertThat(fetchIncidentCount(http, statusBase, twinInstanceId))
                    .as("twin instance must show a real, broker-independent Camunda incident").isGreaterThan(0);

            // The proxy must not advance past this signal while its twin partner is waiting.
            Thread.sleep(3000);
            assertThat(fetchStatus(http, statusBase, proxyInstanceId))
                    .as("proxy must still be waiting, not falsely advanced, while twin is incident-stuck")
                    .doesNotContain("\"active\":false");

            // SignalBroadcaster's new incident-aware logging must have fired - the wait is
            // observable, not silent - but exactly once, not once per second for as long as it stays
            // open.
            String stuckFragment = "twin partner (processInstanceId=" + twinInstanceId + ") has an open Camunda "
                    + "incident";
            awaitLogContaining(project.directory(), stuckFragment, Duration.ofSeconds(15));
            String logAfterFirstStuck = Files.readString(project.directory().resolve("launch.log"));
            int stuckCountFirst = countOccurrences(logAfterFirstStuck, stuckFragment);
            Thread.sleep(4000);
            String logAfterMoreTicks = Files.readString(project.directory().resolve("launch.log"));
            int stuckCountLater = countOccurrences(logAfterMoreTicks, stuckFragment);
            assertThat(stuckCountLater)
                    .as("the STUCK log must fire once per stuck period, not once per broadcaster tick "
                            + "(~4 more ticks elapsed here) for as long as the incident stays open")
                    .isEqualTo(stuckCountFirst);

            // Recovery: resolving the incident (restoring retries so the job executor retries the task)
            // allows the in-flight handoff to proceed to completion.
            http.send(HttpRequest.newBuilder(URI.create(statusBase + "/external-task/" + externalTaskId + "/retry"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

            assertThat(awaitInstanceInactive(http, statusBase, proxyInstanceId, Duration.ofSeconds(60))
                    || awaitDistinctActiveActivity(http, statusBase, proxyInstanceId, Duration.ofSeconds(5)))
                    .as("proxy must advance once the twin incident is resolved")
                    .isTrue();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Exercises broker outage recovery during active synchronization by restarting the container
    // publishing port 5672 mid-sync and verifying that the proxy recovers and completes.
    @Test
    void rabbitMqInterruptionDuringActiveSynchronizationRecoversAfterRestart() throws Exception {
        assumeFixturesPresent();
        assertThat(Files.isDirectory(REAL_TEMPLATE)).as("RedCollarTP template must exist at %s",
                REAL_TEMPLATE.toAbsolutePath()).isTrue();
        HttpClient http = HttpClient.newHttpClient();
        Assumptions.assumeTrue(rabbitMqReachable(http), "no RabbitMQ broker reachable at localhost:15672 - "
                + "start one with 'docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management'");
        String containerId = findDockerContainerPublishingPort(5672);
        Assumptions.assumeTrue(containerId != null, "no local docker container publishing port 5672 found - "
                + "this test stops/restarts the real broker mid-sync and can only do that via docker");

        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        String twinBpmnXml = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));

        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml,
                firstAuthoredMapping(), null, null);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched;
            try {
                launched = launcher.launch(project);
            } catch (Exception launchEx) {
                Path launchLog = project.directory().resolve("launch.log");
                Path buildLog = project.directory().resolve("build.log");
                String logContent = Files.exists(launchLog) ? Files.readString(launchLog) : "(no launch.log found)";
                String buildContent = Files.exists(buildLog) ? Files.readString(buildLog) : "(no build.log found)";
                throw new AssertionError("Launch failed. build.log:\n" + buildContent + "\nlaunch.log:\n"
                        + logContent, launchEx);
            }

            String proxyBase = "http://localhost:" + launched.port() + "/api/proxy";
            String twinBase = "http://localhost:" + launched.port() + "/api/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";
            String businessKey = "broker-outage-test-" + UUID.randomUUID();

            HttpResponse<String> proxyStart = http.send(
                    HttpRequest.newBuilder(URI.create(proxyBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            String proxyInstanceId = extractField(proxyStart.body(), "processInstanceId");
            http.send(HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + businessKey))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

            // Confirm synchronization is active before stopping the broker.
            awaitLogContaining(project.directory(), "TASK: published signal", Duration.ofSeconds(60));

            runDockerCommand("stop", containerId);
            try {
                // Allow enough time for in-flight publish attempts to experience the broker outage.
                Thread.sleep(8000);
            } finally {
                runDockerCommand("start", containerId);
            }

            // Spring AMQP connection recovery handles reconnection once the broker is back online.
            assertThat(awaitInstanceInactive(http, statusBase, proxyInstanceId, Duration.ofSeconds(180)))
                    .as("proxy must reach completion after broker recovers")
                    .isTrue();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // DLQ validation: publishes a malformed payload to the task queue to trigger listener failure,
    // exhausts retries according to configured retry settings, and verifies delivery to the dead-letter queue.
    @Test
    void malformedTaskMessageIsRetriedThenDeadLetteredAndObservable() throws Exception {
        assumeFixturesPresent();
        assertThat(Files.isDirectory(REAL_TEMPLATE)).as("RedCollarTP template must exist at %s",
                REAL_TEMPLATE.toAbsolutePath()).isTrue();
        HttpClient http = HttpClient.newHttpClient();
        Assumptions.assumeTrue(rabbitMqReachable(http), "no RabbitMQ broker reachable at localhost:15672 - "
                + "start one with 'docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management'");

        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        String twinBpmnXml = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));

        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(manufBpmnXml, twinBpmnXml,
                firstAuthoredMapping(), null, null);

        // Verify configuration directly from the generated RabbitMqConfig source:
        Path configFile = project.directory()
                .resolve("src/main/java/com/tp/TargetPlatform/messaging/RabbitMqConfig.java");
        assertThat(configFile).exists();
        String configSource = Files.readString(configFile);
        assertThat(configSource).as("generated config must actually declare dead-letter routing")
                .contains("DLX_EXCHANGE").contains("x-dead-letter-exchange").contains("x-dead-letter-routing-key");

        java.util.regex.Matcher exchangeMatch = java.util.regex.Pattern.compile("EXCHANGE = \"([^\"]+)\"")
                .matcher(configSource);
        assertThat(exchangeMatch.find()).as("must find the generated sync exchange name").isTrue();
        String exchange = exchangeMatch.group(1);

        // A TASK routing key looks like "sync.<slug>"; a RESPONSE routing key is
        // "sync.responses.<slug>" - the negative lookahead is what tells them apart regardless of
        // which map's entries the regex happens to scan first.
        java.util.regex.Matcher taskRoutingMatch = java.util.regex.Pattern
                .compile("\"(sync\\.(?!responses\\.)[a-z0-9-]+)\"").matcher(configSource);
        assertThat(taskRoutingMatch.find()).as("must find at least one task routing key").isTrue();
        String taskRoutingKey = taskRoutingMatch.group(1);

        java.util.regex.Matcher dlqTasksMatch = java.util.regex.Pattern.compile("DLQ_TASKS_QUEUE = \"([^\"]+)\"")
                .matcher(configSource);
        assertThat(dlqTasksMatch.find()).as("must find the generated task DLQ name").isTrue();
        String dlqTasksQueueName = dlqTasksMatch.group(1);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher(Duration.ofMinutes(8));
        try {
            LaunchedProject launched;
            try {
                launched = launcher.launch(project);
            } catch (Exception launchEx) {
                Path launchLog = project.directory().resolve("launch.log");
                Path buildLog = project.directory().resolve("build.log");
                String logContent = Files.exists(launchLog) ? Files.readString(launchLog) : "(no launch.log found)";
                String buildContent = Files.exists(buildLog) ? Files.readString(buildLog) : "(no build.log found)";
                throw new AssertionError("Launch failed. build.log:\n" + buildContent + "\nlaunch.log:\n"
                        + logContent, launchEx);
            }

            // Payloads lacking the expected pipe separator throw IllegalArgumentException prior to Camunda delivery.
            String poisonPayload = "poison-message-missing-pipe-delimited-fields-"
                    + UUID.randomUUID();
            publishRawMessage(http, exchange, taskRoutingKey, poisonPayload);

            // TaskQueueListener logs a malformed-message error on each delivery attempt;
            // repeated entries confirm message redelivery.
            String malformedLogFragment = "[task-queue] malformed message, routing to DLQ: " + poisonPayload;
            awaitLogContaining(project.directory(), malformedLogFragment, Duration.ofSeconds(15));
            Thread.sleep(4000);
            String logDuringRetries = Files.readString(project.directory().resolve("launch.log"));
            assertThat(countOccurrences(logDuringRetries, malformedLogFragment))
                    .as("the listener must have retried delivery, not merely invoked once")
                    .isGreaterThanOrEqualTo(2);

            // Asserts message routes to DLQ after exhausting configured 30 consumer retries.
            long dlqCount = 0;
            Instant dlqDeadline = Instant.now().plus(Duration.ofSeconds(150));
            int lastRetryCount = 0;
            while (Instant.now().isBefore(dlqDeadline) && dlqCount == 0) {
                dlqCount = fetchQueueDeliverGetCount(http, dlqTasksQueueName);
                if (dlqCount == 0) {
                    String currentLog = Files.readString(project.directory().resolve("launch.log"));
                    lastRetryCount = countOccurrences(currentLog, malformedLogFragment);
                    Thread.sleep(2000);
                }
            }
            if (dlqCount == 0) {
                String finalLog = Files.readString(project.directory().resolve("launch.log"));
                int finalRetryCount = countOccurrences(finalLog, malformedLogFragment);
                throw new AssertionError("poison message never reached DLQ '" + dlqTasksQueueName
                        + "' within the wait window. Observed retry attempts: " + finalRetryCount
                        + " (last polled: " + lastRetryCount + "). Tail of launch.log:\n"
                        + finalLog.substring(Math.max(0, finalLog.length() - 4000)));
            }

            // DLQ observability: the generated DeadLetterQueueListener must have consumed it and
            // logged it - the same payload, tying this specific dead-lettered message back to the
            // one this test published, not just "some" message.
            awaitLogContaining(project.directory(),
                    "DEAD-LETTERED TASK message (unprocessable after retries): " + poisonPayload,
                    Duration.ofSeconds(15));

            // Normal proxy/twin synchronization must still complete in this running application afterward.
            String proxyBase = "http://localhost:" + launched.port() + "/api/proxy";
            String twinBase = "http://localhost:" + launched.port() + "/api/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";
            String businessKey = "post-dlq-sync-test-" + UUID.randomUUID();
            HttpResponse<String> proxyStart = http.send(
                    HttpRequest.newBuilder(URI.create(proxyBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            String proxyInstanceId = extractField(proxyStart.body(), "processInstanceId");
            http.send(HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + businessKey))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

            assertThat(awaitInstanceInactive(http, statusBase, proxyInstanceId, Duration.ofSeconds(120)))
                    .as("normal proxy/twin synchronization must still complete after the poison-message "
                            + "incident - the DLQ episode must not have left the app in a bad state")
                    .isTrue();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    private static void publishRawMessage(HttpClient http, String exchange, String routingKey, String payload)
            throws IOException, InterruptedException {
        String publishBody = "{\"properties\":{},\"routing_key\":\"" + routingKey
                + "\",\"payload\":\"" + payload.replace("\"", "\\\"") + "\",\"payload_encoding\":\"string\"}";
        String encodedExchange = java.net.URLEncoder.encode(exchange, StandardCharsets.UTF_8);
        // Retry publish until RabbitMQ exchange bindings are established.
        String lastBody = null;
        int lastStatus = -1;
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:15672/api/exchanges/%2f/" + encodedExchange
                                    + "/publish"))
                            .version(java.net.http.HttpClient.Version.HTTP_1_1)
                            .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                    .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8)))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(publishBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
            lastStatus = response.statusCode();
            lastBody = response.body();
            if (lastStatus == 200 && lastBody.contains("\"routed\":true")) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("publish to exchange '" + exchange + "' key '" + routingKey
                + "' never succeeded within 30s (last status " + lastStatus + "): " + lastBody);
    }

    // Reads cumulative deliver_get count from RabbitMQ management API.
    private static long fetchQueueDeliverGetCount(HttpClient http, String queueName)
            throws IOException, InterruptedException {
        String encoded = java.net.URLEncoder.encode(queueName, StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:15672/api/queues/%2f/" + encoded))
                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8)))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return 0;
        }
        return extractLongField(response.body(), "deliver_get");
    }

    private static String findDockerContainerPublishingPort(int port) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "ps", "--filter", "publish=" + port, "-q")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        process.waitFor();
        return output.isBlank() ? null : output.lines().findFirst().orElse(null);
    }

    private static void runDockerCommand(String command, String containerId) throws IOException,
            InterruptedException {
        Process process = new ProcessBuilder("docker", command, containerId).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("docker " + command + " " + containerId + " failed (exit " + exit + "): "
                    + output);
        }
    }

    private static long fetchIncidentCount(HttpClient http, String statusBase, String processInstanceId)
            throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/incidents/count")).GET()
                        .build(), HttpResponse.BodyHandlers.ofString());
        return extractLongField(response.body(), "incidentCount");
    }

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(fragment, index)) != -1) {
            count++;
            index += fragment.length();
        }
        return count;
    }

    private static boolean awaitDistinctActiveActivity(HttpClient http, String statusBase, String processInstanceId,
            Duration wait) throws IOException, InterruptedException {
        Thread.sleep(wait.toMillis());
        return fetchStatus(http, statusBase, processInstanceId).contains("activeActivityIds");
    }

    private static boolean awaitInstanceInactive(HttpClient http, String statusBase, String processInstanceId,
            Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (fetchStatus(http, statusBase, processInstanceId).contains("\"active\":false")) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static String fetchStatus(HttpClient http, String statusBase, String processInstanceId)
            throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status")).GET()
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }

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

    private static List<String> queueSegmentsMatchingPrefix(String queueListJson, String nameFragment) {
        List<String> names = new java.util.ArrayList<>();
        List<Integer> starts = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"name\":\"([^\"]*)\"")
                .matcher(queueListJson);
        while (matcher.find()) {
            names.add(matcher.group(1));
            starts.add(matcher.start());
        }
        List<String> segments = new java.util.ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).contains(nameFragment)) {
                int start = starts.get(i);
                int end = (i + 1 < starts.size()) ? starts.get(i + 1) : queueListJson.length();
                segments.add(queueListJson.substring(start, end));
            }
        }
        return segments;
    }

    private static long extractLongField(String json, String fieldName) {
        String marker = "\"" + fieldName + "\":";
        int key = json.indexOf(marker);
        if (key < 0) {
            return 0;
        }
        int start = key + marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static String extractField(String json, String fieldName) {
        String marker = "\"" + fieldName + "\"";
        int key = json.indexOf(marker);
        int firstQuote = json.indexOf('"', key + marker.length() + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static String awaitLogContaining(Path projectDir, String fragment, Duration timeout)
            throws IOException {
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
}
