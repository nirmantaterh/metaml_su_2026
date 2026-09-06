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

// Acceptance test for camunda:taskListener support in the RedCollarTP Target Platform
// pipeline (TargetPlatformSourceGenerator.scanTaskListeners). The professor-supplied RedCollar
// BPMNs contain no User Task, so this deliberately builds its own minimal fixture (Start -> User
// Task with a "create" Task Listener -> End) rather than modifying those files - the "create" event
// fires the instant the process instance reaches the user task, with no human task-completion step
// needed, which keeps this deterministic without a REST call into Camunda's task API.
//
// Generates a REAL Target Platform from the REAL RedCollarTP template (not a fake), builds and
// launches it for real, and proves - from the generated app's own log output, not a mock - that
// Camunda actually invoked the generated TaskListener bean, not merely that a .java file exists.
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
        String proxySource = Files.readString(proxyListener);
        assertThat(proxySource).contains("implements TaskListener").contains("@Component(\"orderApprovalListener\")");
        String twinSource = Files.readString(twinListener);
        assertThat(twinSource).contains("implements TaskListener")
                .contains("@Component(\"orderApprovalListenerTwin\")");

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
            // completion call needed - so both beans' log markers are the direct, deterministic
            // proof that Camunda invoked the generated TaskListener (not just that it compiled).
            String proxyLog = awaitLogContaining(project.directory(),
                    "PROXY (TASK LISTENER) - orderApprovalListener ---- Spring Bean invoked", Duration.ofSeconds(30));
            assertThat(proxyLog).contains("orderApprovalListener");

            String twinLog = awaitLogContaining(project.directory(),
                    "TWIN (TASK LISTENER) - orderApprovalListenerTwin ---- Spring Bean invoked",
                    Duration.ofSeconds(30));
            assertThat(twinLog).contains("orderApprovalListenerTwin");
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
