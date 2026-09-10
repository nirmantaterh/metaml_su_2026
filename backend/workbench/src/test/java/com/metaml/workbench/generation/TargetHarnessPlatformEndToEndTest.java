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
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.GeneratedDelegate;

// End-to-end test for target harness platform delegate generation, build, and launch.
@Tag("slow")
class TargetHarnessPlatformEndToEndTest {

    @TempDir
    Path tempDir;

    private static final Path REAL_TEMPLATE = Path.of("../../templates/camundademo");

    @Test
    void generatedProjectCompilesLaunchesAndActuallyRunsAGeneratedDelegateWhenInvoked() throws Exception {
        assertThat(Files.isDirectory(REAL_TEMPLATE))
                .as("templates/camundademo must exist at %s for this test to mean anything",
                        REAL_TEMPLATE.toAbsolutePath())
                .isTrue();

        Path outputDir = tempDir.resolve("generated-projects");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new com.metaml.workbench.codegen.ExternalTaskWorkerGenerator());

        String bpmnXml = manufacturingFixtureBpmn();
        List<GeneratedDelegate> delegates = new DelegateClassGenerator()
                .generate(bpmnXml, SpringBootProjectGenerator.DELEGATE_PACKAGE);

        GeneratedProject project = generator.generate(bpmnXml, delegates);

        // 1. Verify generated code structure.
        String slug = "fixtureprocess";
        String basePackagePath = "src/main/java/com/metaml/targetplatform/" + slug;
        assertThat(project.directory().resolve(basePackagePath
                + "/controller/manufacturing/GeneratedManufacturingController.java")).exists();
        assertThat(project.directory().resolve(basePackagePath
                + "/controller/twin/GeneratedTwinController.java")).exists();
        assertThat(project.directory().resolve(basePackagePath
                + "/delegate/manufacturing/StitchService.java")).exists();
        assertThat(project.directory().resolve(basePackagePath + "/bridge/NotificationBridge.java")).exists();
        assertThat(project.directory().resolve("src/main/resources/processes/fixtureProcess.bpmn")).exists();
        assertThat(project.directory().resolve("src/main/resources/processes/fixtureProcess_twin.bpmn")).exists();

        // Messaging ships in the template and must be repackaged for this project.
        Path messagingDir = project.directory().resolve(basePackagePath + "/messaging");
        assertThat(messagingDir.resolve("MessagingTopology.java")).exists();
        assertThat(messagingDir.resolve("HarnessMessage.java")).exists();
        assertThat(messagingDir.resolve("MessagingConfig.java")).exists();
        assertThat(messagingDir.resolve("HarnessMessagePublisher.java")).exists();
        assertThat(messagingDir.resolve("TwinMessageListener.java")).exists();
        assertThat(messagingDir.resolve("ManufacturingMessageListener.java")).exists();
        assertThat(messagingDir.resolve("ExternalComponentStubListener.java")).exists();
        assertThat(Files.readString(project.directory().resolve(basePackagePath + "/bridge/NotificationBridge.java")))
                .contains("package com.metaml.targetplatform." + slug + ".bridge;")
                .doesNotContain("com.example.camundademo");
        assertThat(project.directory().resolve(
                "src/test/java/com/metaml/targetplatform/" + slug + "/messaging/MessagingFlowIT.java")).exists();

        // Enable messaging if a broker is reachable; without one the generated platform must still build and run.
        boolean brokerAvailable = isBrokerReachable();
        if (brokerAvailable) {
            Path properties = project.directory().resolve("src/main/resources/application.properties");
            Files.writeString(properties, Files.readString(properties)
                    .replace("metaml.messaging.enabled=false", "metaml.messaging.enabled=true"));
            
            // Delete static queues to ensure a clean test run
            HttpClient adminClient = HttpClient.newHttpClient();
            List<String> staticQueues = List.of(
                    "twin.stage.updates",
                    "twin.stage.responses.manuf",
                    "gateway.qc.requests",
                    "gateway.qc.responses.twin",
                    "machines.requests",
                    "machines.completions.manuf"
            );
            for (String queue : staticQueues) {
                try {
                    adminClient.send(
                            HttpRequest.newBuilder(URI.create("http://localhost:15672/api/queues/%2f/" + queue))
                                     .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                             .encodeToString("guest:guest".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                     .DELETE().build(),
                             HttpResponse.BodyHandlers.ofString());
                 } catch (Exception e) {
                     // Ignore failures during cleanup
                 }
             }
         }
 
        // 2. Build generated application.
        Process build = new ProcessBuilder(mvnw(project.directory()), "-q", "package", "-DskipTests")
                .directory(project.directory().toFile())
                .redirectErrorStream(true)
                .start();
        String buildOutput = new String(build.getInputStream().readAllBytes());
        boolean buildFinished = build.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
        assertThat(buildFinished).as("mvn build did not finish in time").isTrue();
        assertThat(build.exitValue()).as("generated project failed to build:%n%s", buildOutput).isZero();

        // 3. Launch generated application as a standalone process.
        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        LaunchedProject launched;
        try {
            launched = launcher.launch(project);

            // 4. Invoke the manufacturing endpoint over HTTP against the launched process port.
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + launched.port() + "/api/v1/manufacturing";

            HttpResponse<String> startResponse = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/start")).POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(startResponse.statusCode()).as("start failed: %s", startResponse.body()).isEqualTo(200);
            String processInstanceId = extractProcessInstanceId(startResponse.body());

            // Completing UserTask_Stitch fires the taskListener delegate and NotificationBridge.notifyTwin.
            HttpResponse<String> completeResponse = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/" + processInstanceId + "/stitch/complete"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(completeResponse.statusCode())
                    .as("completing the manufacturing activity failed: %s", completeResponse.body())
                    .isEqualTo(200);

            // 5. Verify execution logs from the launched application.
            String log = Files.readString(project.directory().resolve("launch.log"));
            assertThat(log).as("generated delegate never logged its execution:%n%s", log)
                    .contains("Executing generated task listener for activity \"Stitch\"");
            assertThat(log).as("manufacturing controller never notified the twin via NotificationBridge:%n%s", log)
                    .contains("[manufacturing -> twin] activity 'UserTask_Stitch' complete");

            // 6. Verify end-to-end messaging chain with RabbitMQ broker.
            if (brokerAvailable) {
                // Process is parked at ReceiveTask_AwaitTwin; only the twin's answer triggers message correlation.
                String messagingLog = awaitLogContaining(project.directory(),
                        "resumed Camunda process instance " + processInstanceId);
                assertThat(messagingLog).contains("exchange 'twin.exchange' key 'twin.stage.update'");
                assertThat(messagingLog).contains("[twin] received stage update for activity 'UserTask_Stitch'");
                assertThat(messagingLog).contains("[gateway stub] executing QC for activity 'UserTask_Stitch'");
                assertThat(messagingLog).contains("[twin] received QC response 'PASS'");
                assertThat(messagingLog).contains("[manufacturing] twin reported stage result 'PASS'");

                // 7. Probe wait state via generated endpoint; 409 confirms token has advanced.
                HttpResponse<String> waitStateProbe = http.send(
                        HttpRequest.newBuilder(URI.create(base + "/" + processInstanceId
                                + "/await-twin-result/complete"))
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(waitStateProbe.statusCode())
                        .as("expected 409 (nothing parked at ReceiveTask_AwaitTwin any more), body=%s",
                                waitStateProbe.body())
                        .isEqualTo(409);

                // 8. Verify finalize delegate log confirms progression past receive task.
                String afterResume = awaitLogContaining(project.directory(),
                        "Executing generated delegate for activity \"Finalize\"");
                assertThat(afterResume).contains("resumed Camunda process instance " + processInstanceId);
            }
        } finally {
            launcher.stop(project.projectId());
            if (brokerAvailable) {
                HttpClient adminClient = HttpClient.newHttpClient();
                List<String> staticQueues = List.of(
                        "twin.stage.updates",
                        "twin.stage.responses.manuf",
                        "gateway.qc.requests",
                        "gateway.qc.responses.twin",
                        "machines.requests",
                        "machines.completions.manuf"
                );
                for (String queue : staticQueues) {
                    try {
                        adminClient.send(
                                HttpRequest.newBuilder(URI.create("http://localhost:15672/api/queues/%2f/" + queue))
                                        .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                                .encodeToString("guest:guest".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                        .DELETE().build(),
                                HttpResponse.BodyHandlers.ofString());
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            }
        }
    }

    // The messaging chain is async; HTTP returning does not mean the chain finished.
    private static String awaitLogContaining(Path projectDir, String fragment) throws IOException {
        Path logFile = projectDir.resolve("launch.log");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        String log = "";
        while (Instant.now().isBefore(deadline)) {
            log = Files.readString(logFile);
            if (log.contains(fragment)) {
                return log;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for '" + fragment + "' in launch.log:\n" + log);
    }

    private static boolean isBrokerReachable() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", 5672), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // UserTask with taskListener transformed into receive-service pair and exposed via completion endpoint.
    private static String manufacturingFixtureBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_Fixture" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:message id="Message_TwinStageResponse" name="TwinStageResponse" />
                  <bpmn2:process id="fixtureProcess" name="Fixture Process" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:userTask id="UserTask_Stitch" name="Stitch">
                      <bpmn2:extensionElements>
                        <camunda:taskListener event="complete" delegateExpression="${stitchService}" />
                      </bpmn2:extensionElements>
                    </bpmn2:userTask>
                    <bpmn2:receiveTask id="ReceiveTask_AwaitTwin" name="Await Twin Result"
                        messageRef="Message_TwinStageResponse" />
                    <bpmn2:serviceTask id="ServiceTask_Finalize" name="Finalize"
                        camunda:delegateExpression="${finalizeService}" />
                    <bpmn2:endEvent id="End" />
                    <bpmn2:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="UserTask_Stitch" />
                    <bpmn2:sequenceFlow id="Flow_2" sourceRef="UserTask_Stitch" targetRef="ReceiveTask_AwaitTwin" />
                    <bpmn2:sequenceFlow id="Flow_3" sourceRef="ReceiveTask_AwaitTwin" targetRef="ServiceTask_Finalize" />
                    <bpmn2:sequenceFlow id="Flow_4" sourceRef="ServiceTask_Finalize" targetRef="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String mvnw(Path projectDir) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = projectDir.resolve(windows ? "mvnw.cmd" : "mvnw");
        return wrapper.toAbsolutePath().toString();
    }

    private static String extractProcessInstanceId(String json) {
        // {"processInstanceId":"..."} - no Jackson dependency needed for one field
        int key = json.indexOf("\"processInstanceId\"");
        int firstQuote = json.indexOf('"', key + "\"processInstanceId\"".length() + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        return json.substring(firstQuote + 1, secondQuote);
    }
}
