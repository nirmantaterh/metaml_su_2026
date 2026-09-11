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

// Verifies generated TaskListener beans are invoked when Camunda reaches the user task.
@Tag("slow")
class TaskListenerTargetPlatformEndToEndTest {

    @TempDir
    Path tempDir;

    private static final Path REAL_TEMPLATE = Path.of("../RedCollarTP");

    private static final String APPROVAL_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                id="Definitions_tasklistener" targetNamespace="http://metaml.com/bpmn">
              <bpmn2:process id="TaskListenerDemo" name="Task Listener Demo" isExecutable="true">
                <bpmn2:startEvent id="start">
                  <bpmn2:outgoing>Flow_1</bpmn2:outgoing>
                </bpmn2:startEvent>
                <bpmn2:sequenceFlow id="Flow_1" sourceRef="start" targetRef="ApproveOrder"/>
                <bpmn2:userTask id="ApproveOrder" name="Approve Order">
                  <bpmn2:extensionElements>
                    <camunda:taskListener event="create" delegateExpression="${orderApprovalListener}" />
                  </bpmn2:extensionElements>
                  <bpmn2:incoming>Flow_1</bpmn2:incoming>
                  <bpmn2:outgoing>Flow_2</bpmn2:outgoing>
                </bpmn2:userTask>
                <bpmn2:sequenceFlow id="Flow_2" sourceRef="ApproveOrder" targetRef="end"/>
                <bpmn2:endEvent id="end">
                  <bpmn2:incoming>Flow_2</bpmn2:incoming>
                </bpmn2:endEvent>
              </bpmn2:process>
            </bpmn2:definitions>
            """;

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
    void taskListenerGeneratesCorrectlyAndFiresOnTheRealEngineForBothProxyAndAutoDerivedTwin() throws Exception {
        assertThat(Files.isDirectory(REAL_TEMPLATE)).as("RedCollarTP template must exist at %s",
                REAL_TEMPLATE.toAbsolutePath()).isTrue();
        HttpClient http = HttpClient.newHttpClient();
        Assumptions.assumeTrue(rabbitMqReachable(http), "no RabbitMQ broker reachable at localhost:15672 - "
                + "start one with 'docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management'");

        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        // Single-BPMN entry point - the auto-derived (mirror) twin path, same one
        // RedCollarTargetPlatformSyncEndToEndTest's own auto-derive test exercises, so the Twin
        // side gets its own Twin-suffixed taskListener bean via the same mirror mechanism.
        GeneratedProject project = generator.generate(APPROVAL_BPMN, List.of());

        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");
        Path proxyListener = tpRoot.resolve("proxy/listeners/OrderApprovalListener.java");
        Path twinListener = tpRoot.resolve("twin/listeners/OrderApprovalListenerTwin.java");
        assertThat(proxyListener).exists();
        assertThat(twinListener).exists();

        // The Original keeps its human task, so its listener is still a TaskListener.
        String proxySource = Files.readString(proxyListener);
        assertThat(proxySource).contains("implements TaskListener").contains("@Component(\"orderApprovalListener\")");

        // The Twin does not: a mirrored userTask is a wait state with no engine-side actor, so the
        // mirror automates it into a serviceTask. A camunda:taskListener cannot fire on one, so the
        // listener the model declared is carried over as an ExecutionListener rather than dropped -
        // same bean name, same generated file, the only interface a serviceTask can actually invoke.
        String twinSource = Files.readString(twinListener);
        assertThat(twinSource).contains("implements ExecutionListener")
                .contains("@Component(\"orderApprovalListenerTwin\")");

        Path processes = project.directory().resolve("src/main/resources/processes");
        String proxyBpmn = Files.readString(processes.resolve("TaskListenerDemo.bpmn"));
        String twinBpmn = Files.readString(processes.resolve("TaskListenerDemo_twin.bpmn"));
        assertThat(proxyBpmn).as("the Original process is never rewritten").contains("userTask");
        assertThat(twinBpmn).as("no Twin activity may be a human task").doesNotContain("userTask");
        // and the automated Twin activity got a delegate of its own to execute and observe
        assertThat(tpRoot.resolve("twin/delegates/ApproveOrder.java")).exists();

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
            String businessKey = "task-listener-test-" + UUID.randomUUID();

            HttpResponse<String> proxyStart = http.send(
                    HttpRequest.newBuilder(URI.create(proxyBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(proxyStart.statusCode()).as("proxy start failed: %s", proxyStart.body()).isEqualTo(200);

            HttpResponse<String> twinStart = http.send(
                    HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(twinStart.statusCode()).as("twin start failed: %s", twinStart.body()).isEqualTo(200);

            // The "create" event fires the instant Camunda reaches the user task - no task
            // completion call needed - so both beans' log markers confirm that Camunda
            // invoked the generated TaskListener (not just that it compiled).
            String proxyLog = awaitLogContaining(project.directory(),
                    "PROXY (TASK LISTENER) INVOKED", Duration.ofSeconds(30));
            assertThat(proxyLog)
                    .contains("bean=orderApprovalListener")
                    // the generic runtime context the Target Platform's own log has to carry
                    .contains("processInstanceId=")
                    .contains("activityId=ApproveOrder");

            // start, not create: the Twin activity is a serviceTask now, so the listener fires on the
            // execution lifecycle rather than on a task that no longer exists.
            String twinLog = awaitLogContaining(project.directory(),
                    "TWIN (LISTENER) INVOKED", Duration.ofSeconds(30));
            assertThat(twinLog)
                    .contains("bean=orderApprovalListenerTwin")
                    .contains("processInstanceId=");
            // the automated Twin activity really executed its own generated delegate
            assertThat(awaitLogContaining(project.directory(), "TWIN DELEGATE INVOKED", Duration.ofSeconds(30)))
                    .contains("activityId=ApproveOrder");

            // and the capability runtime came up inside the generated application itself, with the
            // provider implementations actually discovered - not merely present on the classpath
            assertThat(awaitLogContaining(project.directory(), "CAPABILITY RUNTIME READY",
                    Duration.ofSeconds(30)))
                    .contains("credit-risk-assessor")
                    .contains("validator");
        } finally {
            launcher.stop(project.projectId());
        }
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
