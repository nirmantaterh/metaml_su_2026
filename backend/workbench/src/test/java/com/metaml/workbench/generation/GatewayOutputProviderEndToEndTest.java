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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;

// Live proof for the ExternalTaskWorkerGenerator gateway-guard fix (MetaML P6 generic
// gateway/output defect): a generated proxy worker whose topic precedes an exclusive gateway must
// (a) route a legitimate producer's output to the gateway and let Camunda pick the correct branch,
// and (b) still fail as a genuine Camunda incident - never fabricate a value and never silently
// continue - when no legitimate producer is registered. Uses a generic synthetic process (no
// RedCollar/manufacturing-domain names) per the generic-platform-mechanisms pattern already
// established by GenericPlatformMechanismsEndToEndTest.
@Tag("slow")
class GatewayOutputProviderEndToEndTest {

    @TempDir
    Path tempDir;

    private static final Path REAL_TEMPLATE = Path.of("../../templates/camundademo");
    private static final String BASE_PACKAGE = "com.metaml.targetplatform.genericgatewaymanuf";
    private static final String WORKER_PACKAGE = BASE_PACKAGE + ".worker.manufacturing";

    // Test 1 (TRUE gateway): provider produces true -> output propagated -> gateway evaluates true
    // -> true branch executes.
    @Test
    void trueBranchExecutesWhenGatewayOutputProviderSuppliesTrue() throws Exception {
        GeneratedProject project = generateProject("gw-provider-true-test");
        writeTestGatewayOutputProvider(project, true);
        buildProject(project);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project);
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + launched.port();
            String instanceId = extractProcessInstanceId(
                    post(http, base + "/api/v1/manufacturing/start").body());

            assertThat(awaitInstanceCompleted(http, base, instanceId, Duration.ofSeconds(30)))
                    .as("instance should complete once the gateway variable is legitimately supplied").isTrue();
            assertThat(awaitVisitCount(http, base, instanceId, "BranchTrueStep", 1, Duration.ofSeconds(10)))
                    .as("the TRUE branch activity must have executed exactly once").isTrue();
            assertThat(visitCount(http, base, instanceId, "BranchFalseStep"))
                    .as("the FALSE branch must never have executed").isZero();
            assertThat(incidentCount(http, base, instanceId))
                    .as("a legitimately-supplied gateway variable must not produce an incident").isZero();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Test 2 (FALSE gateway): provider produces false -> output propagated -> gateway evaluates
    // false -> false branch executes.
    @Test
    void falseBranchExecutesWhenGatewayOutputProviderSuppliesFalse() throws Exception {
        GeneratedProject project = generateProject("gw-provider-false-test");
        writeTestGatewayOutputProvider(project, false);
        buildProject(project);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project);
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + launched.port();
            String instanceId = extractProcessInstanceId(
                    post(http, base + "/api/v1/manufacturing/start").body());

            assertThat(awaitInstanceCompleted(http, base, instanceId, Duration.ofSeconds(30)))
                    .as("instance should complete once the gateway variable is legitimately supplied").isTrue();
            assertThat(awaitVisitCount(http, base, instanceId, "BranchFalseStep", 1, Duration.ofSeconds(10)))
                    .as("the FALSE branch activity must have executed exactly once").isTrue();
            assertThat(visitCount(http, base, instanceId, "BranchTrueStep"))
                    .as("the TRUE branch must never have executed").isZero();
            assertThat(incidentCount(http, base, instanceId))
                    .as("a legitimately-supplied gateway variable must not produce an incident").isZero();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // Test 3 (missing legitimate producer): gateway requires output -> no legitimate producer ->
    // blocked behavior (a genuine Camunda incident) -> no fabricated value -> no false continuation.
    @Test
    void missingGatewayOutputProviderProducesIncidentNotFabricatedContinuation() throws Exception {
        // No TestGatewayOutputProvider written into this project - the out-of-the-box scaffold.
        GeneratedProject project = generateProject("gw-provider-missing-test");
        buildProject(project);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project);
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + launched.port();
            String instanceId = extractProcessInstanceId(
                    post(http, base + "/api/v1/manufacturing/start").body());

            assertThat(awaitIncidentCount(http, base, instanceId, 1, Duration.ofSeconds(30)))
                    .as("a required gateway variable with no legitimate producer must surface as a real "
                            + "Camunda incident, not a silent stall").isTrue();

            String status = getStatus(http, base, instanceId);
            assertThat(status).as("the process must still be active - blocked, not fabricated-continued")
                    .contains("\"active\":true");
            assertThat(visitCount(http, base, instanceId, "BranchTrueStep"))
                    .as("neither gateway branch may have executed").isZero();
            assertThat(visitCount(http, base, instanceId, "BranchFalseStep"))
                    .as("neither gateway branch may have executed").isZero();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    private GeneratedProject generateProject(String label) {
        Path outputDir = tempDir.resolve(label);
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        return generator.generateWithAuthoredTwin(gatewayManufBpmn(), gatewayTwinBpmn(), label);
    }

    private static void writeTestGatewayOutputProvider(GeneratedProject project, boolean approved)
            throws IOException {
        Path packageDir = project.directory().resolve("src/main/java")
                .resolve(WORKER_PACKAGE.replace('.', '/'));
        Files.createDirectories(packageDir);
        String source = """
                package %1$s;

                import java.util.Map;

                import org.camunda.bpm.engine.externaltask.LockedExternalTask;
                import org.springframework.stereotype.Component;

                // Test-only legitimate producer for the "GatewayDecide" topic's "approved" gateway
                // variable, registered exactly the way a real business implementation would be.
                @Component
                public class TestGatewayOutputProvider implements GatewayOutputProvider {
                    @Override
                    public Map<String, Object> provide(String topic, LockedExternalTask task) {
                        return Map.of("approved", %2$s);
                    }
                }
                """.formatted(WORKER_PACKAGE, approved);
        Files.writeString(packageDir.resolve("TestGatewayOutputProvider.java"), source);
    }

    // Generic synthetic process (rule 14): Start -> DecideActivity -> exclusive gateway on
    // ${approved} -> BranchTrueStep / BranchFalseStep. DecideActivity has a legitimate provider
    // (registered per-test); the gateway itself is never touched between true/false runs.
    private static String gatewayManufBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_GatewayManuf" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="GenericGatewayManuf" name="Generic Gateway Manuf" isExecutable="true">
                    <bpmn2:startEvent id="GwManufStart" />
                    <bpmn2:serviceTask id="DecideActivity" name="Decide" camunda:type="external"
                        camunda:topic="GatewayDecide" />
                    <bpmn2:exclusiveGateway id="Gateway_Approved" name="Approved?">
                      <bpmn2:incoming>GwManufFlow2</bpmn2:incoming>
                      <bpmn2:outgoing>GwManufFlowTrue</bpmn2:outgoing>
                      <bpmn2:outgoing>GwManufFlowFalse</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:serviceTask id="BranchTrueStep" name="Branch True" camunda:type="external"
                        camunda:topic="GatewayBranchTrue" />
                    <bpmn2:serviceTask id="BranchFalseStep" name="Branch False" camunda:type="external"
                        camunda:topic="GatewayBranchFalse" />
                    <bpmn2:endEvent id="GwManufEndTrue" />
                    <bpmn2:endEvent id="GwManufEndFalse" />
                    <bpmn2:sequenceFlow id="GwManufFlow1" sourceRef="GwManufStart" targetRef="DecideActivity" />
                    <bpmn2:sequenceFlow id="GwManufFlow2" sourceRef="DecideActivity" targetRef="Gateway_Approved" />
                    <bpmn2:sequenceFlow id="GwManufFlowTrue" sourceRef="Gateway_Approved" targetRef="BranchTrueStep">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${approved}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="GwManufFlowFalse" sourceRef="Gateway_Approved" targetRef="BranchFalseStep">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!approved}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="GwManufFlow3" sourceRef="BranchTrueStep" targetRef="GwManufEndTrue" />
                    <bpmn2:sequenceFlow id="GwManufFlow4" sourceRef="BranchFalseStep" targetRef="GwManufEndFalse" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    // Twin side is not the subject of this test; kept minimal and signal-free.
    private static String gatewayTwinBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_GatewayTwin" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="GenericGatewayTwin" name="Generic Gateway Twin" isExecutable="true">
                    <bpmn2:startEvent id="GwTwinStart" />
                    <bpmn2:serviceTask id="GwTwinStep" name="Twin Step" camunda:type="external"
                        camunda:topic="GatewayTwinStep" />
                    <bpmn2:endEvent id="GwTwinEnd" />
                    <bpmn2:sequenceFlow id="GwTwinFlow1" sourceRef="GwTwinStart" targetRef="GwTwinStep" />
                    <bpmn2:sequenceFlow id="GwTwinFlow2" sourceRef="GwTwinStep" targetRef="GwTwinEnd" />
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
        boolean buildFinished = build.waitFor(5, TimeUnit.MINUTES);
        assertThat(buildFinished).as("mvn build did not finish in time").isTrue();
        assertThat(build.exitValue()).as("generated project failed to build:%n%s", buildOutput).isZero();
    }

    private static String mvnw(Path projectDir) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = projectDir.resolve(windows ? "mvnw.cmd" : "mvnw");
        return wrapper.toAbsolutePath().toString();
    }

    private static HttpResponse<String> post(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s failed: %s", url, response.body()).isEqualTo(200);
        return response;
    }

    private static String extractProcessInstanceId(String json) {
        int key = json.indexOf("\"processInstanceId\"");
        int firstQuote = json.indexOf('"', key + "\"processInstanceId\"".length() + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static String getStatus(HttpClient http, String base, String processInstanceId)
            throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/process/" + processInstanceId
                        + "/status")).GET().build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static boolean awaitInstanceCompleted(HttpClient http, String base, String processInstanceId,
            Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (getStatus(http, base, processInstanceId).contains("\"active\":false")) {
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    private static long visitCount(HttpClient http, String base, String processInstanceId, String activityId)
            throws IOException, InterruptedException {
        String body = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/process/" + processInstanceId
                        + "/activity-history/" + activityId + "/count")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return extractLongField(body, "visitCount");
    }

    private static boolean awaitVisitCount(HttpClient http, String base, String processInstanceId,
            String activityId, long expected, Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (visitCount(http, base, processInstanceId, activityId) >= expected) {
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    private static long incidentCount(HttpClient http, String base, String processInstanceId)
            throws IOException, InterruptedException {
        String body = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/process/" + processInstanceId
                        + "/incidents/count")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        return extractLongField(body, "incidentCount");
    }

    private static boolean awaitIncidentCount(HttpClient http, String base, String processInstanceId,
            long expected, Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (incidentCount(http, base, processInstanceId) >= expected) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static long extractLongField(String json, String fieldName) {
        Matcher m = Pattern.compile(Pattern.quote("\"" + fieldName + "\"") + "\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) {
            throw new IllegalArgumentException("Field '" + fieldName + "' not found in: " + json);
        }
        return Long.parseLong(m.group(1));
    }
}
