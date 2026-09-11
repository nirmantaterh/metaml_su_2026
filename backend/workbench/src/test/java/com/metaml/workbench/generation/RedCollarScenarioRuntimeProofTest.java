package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;

/**
 * Real-runtime proof for portal response sequences. The test's RedCollar IDs are evidence-only: the
 * production sequence mechanism knows only a resolved provider identity and a map of outputs.
 */
@Tag("slow")
class RedCollarScenarioRuntimeProofTest {

    private static final Path REPO_ROOT = Path.of("../..");
    private static final Path TEMPLATE = Path.of("../RedCollarTP");
    private static final String EDIT_ORDER = "_3962D306-3E6E-468A-9767-CCD73D08A592";
    private static final String CHECKING = "_8F5A4559-5D75-4D79-AEEC-E6EE61672A5D";

    @TempDir
    Path tempDir;

    @Test
    void freshPlatformExecutesHappyEditAndReworkScenariosThroughRealWorkersAndRabbitMq() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REPO_ROOT.resolve("Manuf-camunda.bpmn")));
        Assumptions.assumeTrue(Files.isDirectory(TEMPLATE));
        HttpClient http = HttpClient.newHttpClient();
        Assumptions.assumeTrue(rabbitMqReachable(http), "RabbitMQ management API is unavailable");

        String bpmn = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(TEMPLATE.toString(),
                tempDir.resolve("generated-target-platforms").toString(), new TwinModelGenerator(),
                new DelegateClassGenerator(), new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generate(bpmn, List.of(), "redcollar-scenario-proof");

        // Template copy, followed by the launcher's normal package build, is the fresh-artifact
        // compile check. No generated source is edited by this test.
        assertThat(project.directory().resolve("src/main/java/com/tp/TargetPlatform/portal/PortalController.java"))
                .exists();
        assertThat(project.directory().resolve("src/main/resources/static/app.js")).exists();

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project, Map.of(
                    "METAML_MESSAGING_ENABLED", "true", "SPRING_RABBITMQ_HOST", "localhost",
                    "SPRING_RABBITMQ_PORT", "5672"));
            String base = "http://localhost:" + launched.port();

            Run happy = startConfiguredRun(http, base, "happy", "{\"order-approval\":[{\"orderApproved\":true}],"
                    + "\"quality-check\":[{\"qualityPassed\":true}]}");
            awaitComplete(http, base, happy, Duration.ofSeconds(150));
            assertRunHealthy(http, base, happy);
            assertProviderOutput(project.directory(), happy.originalId(), "order-approval", "orderApproved=true", 1);
            assertProviderOutput(project.directory(), happy.originalId(), "quality-check", "qualityPassed=true", 1);
            assertRabbitMqRoundTrip(http, base, happy.businessKey());

            Run edit = startConfiguredRun(http, base, "edit", "{\"order-approval\":[{\"orderApproved\":false},"
                    + "{\"orderApproved\":true}],\"quality-check\":[{\"qualityPassed\":true}]}");
            awaitComplete(http, base, edit, Duration.ofSeconds(150));
            assertRunHealthy(http, base, edit);
            assertProviderOutput(project.directory(), edit.originalId(), "order-approval", "orderApproved=false", 1);
            assertProviderOutput(project.directory(), edit.originalId(), "order-approval", "orderApproved=true", 1);
            assertThat(lockstep(http, base, edit.businessKey()))
                    .contains("\"activityId\":\"" + EDIT_ORDER + "\"")
                    .contains("\"originalState\":\"COMPLETED\"");
            assertRabbitMqRoundTrip(http, base, edit.businessKey());

            Run rework = startConfiguredRun(http, base, "rework", "{\"order-approval\":[{\"orderApproved\":true}],"
                    + "\"quality-check\":[{\"qualityPassed\":false},{\"qualityPassed\":false},"
                    + "{\"qualityPassed\":true}]}");
            awaitComplete(http, base, rework, Duration.ofSeconds(180));
            assertRunHealthy(http, base, rework);
            assertProviderOutput(project.directory(), rework.originalId(), "quality-check", "qualityPassed=false", 2);
            assertProviderOutput(project.directory(), rework.originalId(), "quality-check", "qualityPassed=true", 1);
            assertThat(lockstep(http, base, rework.businessKey()))
                    .contains("\"activityId\":\"" + CHECKING + "\"")
                    .contains("\"originalState\":\"COMPLETED\"");
            assertRabbitMqRoundTrip(http, base, rework.businessKey());
        } finally {
            launcher.stop(project.projectId());
        }
    }

    private static Run startConfiguredRun(HttpClient http, String base, String label, String responses)
            throws Exception {
        String businessKey = "scenario-" + label + "-" + UUID.randomUUID();
        String original = field(post(http, base + "/api/proxy/start?businessKey=" + businessKey
                + "&executionMode=STEP", null), "processInstanceId");
        String twin = field(post(http, base + "/api/twin/start?businessKey=" + businessKey, null), "processInstanceId");
        String configured = post(http, base + "/api/portal/runs/" + businessKey + "/capability-responses", responses);
        assertThat(configured).contains("order-approval").contains("quality-check");
        String execution = get(http, base + "/api/portal/runs/" + businessKey + "/execution");
        assertThat(execution).contains("\"mode\":\"STEP_WAITING\"")
                .contains("capabilityResponseConfiguration");
        // This is the normal portal control: it releases workers; it does not complete a BPMN
        // activity or choose a gateway route itself.
        post(http, base + "/api/portal/runs/" + businessKey + "/complete", null);
        return new Run(businessKey, original, twin);
    }

    private static void awaitComplete(HttpClient http, String base, Run run, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            String original = get(http, base + "/api/v1/process/" + run.originalId() + "/status");
            String twin = get(http, base + "/api/v1/process/" + run.twinId() + "/status");
            if (original.contains("\"active\":false") && twin.contains("\"active\":false")) return;
            Thread.sleep(1000);
        }
        throw new AssertionError("pair did not complete: " + run.businessKey());
    }

    private static void assertRunHealthy(HttpClient http, String base, Run run) throws Exception {
        assertThat(get(http, base + "/api/v1/process/" + run.originalId() + "/incidents/count"))
                .contains("\"incidentCount\":0");
        assertThat(get(http, base + "/api/v1/process/" + run.twinId() + "/incidents/count"))
                .contains("\"incidentCount\":0");
    }

    private static void assertProviderOutput(Path projectDirectory, String processId, String provider,
            String output, int atLeast) throws Exception {
        String log = Files.readString(projectDirectory.resolve("launch.log"));
        int matches = Pattern.compile("CAPABILITY COMPLETE:.*?processInstanceId=" + Pattern.quote(processId)
                + ".*?providerIdentity=" + Pattern.quote(provider) + ".*?" + Pattern.quote(output))
                .matcher(log).results().toList().size();
        assertThat(matches).as("provider %s output %s on process %s", provider, output, processId)
                .isGreaterThanOrEqualTo(atLeast);
    }

    private static void assertRabbitMqRoundTrip(HttpClient http, String base, String businessKey) throws Exception {
        String messages = get(http, base + "/api/portal/messages?limit=1000");
        assertThat(messages).contains("\"businessKey\":\"" + businessKey + "\"")
                .contains("\"direction\":\"TASK\"")
                .contains("\"direction\":\"RESPONSE\"");
    }

    private static String lockstep(HttpClient http, String base, String businessKey) throws Exception {
        return get(http, base + "/api/portal/lockstep?businessKey=" + businessKey);
    }

    private static String post(HttpClient http, String url, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
        if (body == null) request.POST(HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s -> %s", url, response.body()).isEqualTo(200);
        return response.body();
    }

    private static String get(HttpClient http, String url) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s -> %s", url, response.body()).isEqualTo(200);
        return response.body();
    }

    private static String field(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        assertThat(start).as("field %s in %s", name, json).isGreaterThanOrEqualTo(0);
        int valueStart = start + marker.length();
        return json.substring(valueStart, json.indexOf('"', valueStart));
    }

    private static boolean rabbitMqReachable(HttpClient http) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:15672/api/overview"))
                    .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
                            "guest:guest".getBytes(StandardCharsets.UTF_8)))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    private record Run(String businessKey, String originalId, String twinId) { }
}
