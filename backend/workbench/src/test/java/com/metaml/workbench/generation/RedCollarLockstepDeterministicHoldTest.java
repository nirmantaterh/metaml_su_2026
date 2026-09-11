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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;

// Deterministic proof that the Twin cannot advance past a logical activity the Original has not
// reached, using the SAME real, generic mechanisms already proven elsewhere - real generation, real
// build, real standalone launch, real RabbitMQ, real Camunda, real CapabilityDispatcher - with no
// RedCollar-specific production code and no sleeps in production code.
//
// The "hold" is not an artificial delay: it is the SAME fail-closed capability-binding requirement
// already proven in RedCollarTargetPlatformSyncEndToEndTest, deliberately left unsatisfied for ONE
// downstream activity (Checking) while an earlier one (Verify Order Details) IS bound. This creates a
// real, indefinite (not time-boxed) hold at a known point: the Twin genuinely cannot complete
// "Checking" without a capability binding, so it stays there until this test supplies one - exactly
// mirroring how a human task would hold a process, for a process that (per this exact generated BPMN)
// has none. Every wait below is a bounded poll on real engine/portal state, not a fixed sleep assumed
// to be "long enough".
@Tag("slow")
class RedCollarLockstepDeterministicHoldTest {

    @TempDir
    Path tempDir;

    private static final Path REPO_ROOT = redCollarBpmnDir();
    private static final Path REAL_TEMPLATE = Path.of("../RedCollarTP");

    // The exact activity ids and provider identities already proven in this session's acceptance
    // work - test-fixture knowledge, not generic-infrastructure knowledge.
    private static final String VERIFY_ORDER_ACTIVITY_ID = "_BF94795D-C812-42FC-987C-536FFF9BFDB1";
    private static final String CHECKING_ACTIVITY_ID = "_8F5A4559-5D75-4D79-AEEC-E6EE61672A5D";
    private static final String ORDER_APPROVAL_PROVIDER = "order-approval";
    private static final String QUALITY_CHECK_PROVIDER = "quality-check";

    private static Path redCollarBpmnDir() {
        String configured = System.getProperty("redcollar.bpmn.dir", System.getenv("REDCOLLAR_BPMN_DIR"));
        return (configured != null && !configured.isBlank()) ? Path.of(configured) : Path.of("../..");
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
    void twinCannotAdvancePastAnUnboundDownstreamActivityWhileOriginalGenuinelyHolds() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REPO_ROOT.resolve("Manuf-camunda.bpmn")),
                "Manuf-camunda.bpmn not found at " + REPO_ROOT.toAbsolutePath());
        Assumptions.assumeTrue(Files.isDirectory(REAL_TEMPLATE),
                "RedCollarTP template must exist at " + REAL_TEMPLATE.toAbsolutePath());
        HttpClient http = HttpClient.newHttpClient();
        Assumptions.assumeTrue(rabbitMqReachable(http), "no RabbitMQ broker reachable at localhost:15672");

        String manufBpmnXml = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        // The single-BPMN entry point - the exact path the real Workbench REST chain
        // (saveModel -> generate-project, no authored twin) uses in production.
        GeneratedProject project = generator.generate(manufBpmnXml, List.of());

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched;
            try {
                launched = launcher.launch(project);
            } catch (Exception launchEx) {
                String buildLog = Files.exists(project.directory().resolve("build.log"))
                        ? Files.readString(project.directory().resolve("build.log")) : "(none)";
                throw new AssertionError("Launch failed. build.log:\n" + buildLog, launchEx);
            }

            String base = "http://localhost:" + launched.port();
            String businessKey = "lockstep-hold-test-" + UUID.randomUUID();

            String proxyBody = post(http, base + "/api/proxy/start?businessKey=" + businessKey, null);
            String proxyId = extractField(proxyBody, "processInstanceId");
            String twinBody = post(http, base + "/api/twin/start?businessKey=" + businessKey, null);
            String twinId = extractField(twinBody, "processInstanceId");

            // Bind ONLY Verify Order Details, on both instances, so the flow can pass it - but
            // deliberately leave Checking unbound. This is the real, generic capability-binding
            // mechanism, not a fabricated shortcut.
            bindCapability(http, base, twinId, VERIFY_ORDER_ACTIVITY_ID, ORDER_APPROVAL_PROVIDER);
            bindCapability(http, base, proxyId, VERIFY_ORDER_ACTIVITY_ID, ORDER_APPROVAL_PROVIDER);

            // Deterministic wait: not a fixed sleep, a bounded poll on the real Camunda incident this
            // missing binding must produce (same fail-closed mechanism proven in the sync test class).
            awaitLogContaining(project.directory(),
                    "no retries remaining, task now has an incident: Activity '" + CHECKING_ACTIVITY_ID + "'",
                    Duration.ofSeconds(90));

            // ---- THE HOLD: repeated sampling over a real time window, not a single snapshot ----
            // Five samples, two seconds apart, real time elapsing between each - if the Twin were
            // ever going to race ahead of the Original, or if the Original were ever going to
            // falsely advance while the Twin's incident stands, this window is long enough to show
            // it (the fully-bound run of this same flow round-trips ~10 downstream signals in under
            // 20 seconds total, so 10 seconds of a genuine hold is a real, non-trivial fraction of
            // that budget, not an instant that could be missed by bad luck).
            for (int sample = 0; sample < 5; sample++) {
                java.util.Map<String, String[]> steps = fetchLockstepSteps(http, base, businessKey);

                assertThat(steps.get(CHECKING_ACTIVITY_ID)[1])
                        .as("sample %d: Twin must still be incidented at Checking, not silently advanced",
                                sample)
                        .isEqualTo("INCIDENT");
                assertThat(steps.get(CHECKING_ACTIVITY_ID)[0])
                        .as("sample %d: Original must NOT have advanced onto/past Checking while the "
                                + "Twin cannot legitimately complete it", sample)
                        .isNotEqualTo("COMPLETED");

                // Nothing downstream of Checking may show any progress on either side.
                for (String downstreamId : List.of("_0FC5B3EE-E55C-405A-A755-65504ED71E08" /* Pressing */,
                        "_9BDC6C05-C6E9-4CE8-B6DE-886693BF4DE7" /* Packaging */,
                        "_3E1A9952-83B6-4C80-AB67-3BAEDA810FFE" /* Shipping */)) {
                    String[] state = steps.get(downstreamId);
                    assertThat(state).as("sample %d: downstream activity %s must exist in the step list",
                            sample, downstreamId).isNotNull();
                    assertThat(state[0]).as("sample %d: Original must not have reached %s", sample, downstreamId)
                            .isEqualTo("PENDING");
                    assertThat(state[1]).as("sample %d: Twin must not have reached %s", sample, downstreamId)
                            .isEqualTo("PENDING");
                }

                // The general invariant, checked across EVERY activity this sample: the Twin is never
                // COMPLETED on an activity the Original has not also completed.
                for (java.util.Map.Entry<String, String[]> entry : steps.entrySet()) {
                    String[] state = entry.getValue();
                    if ("COMPLETED".equals(state[1])) {
                        assertThat(state[0])
                                .as("sample %d: activity %s - Twin is COMPLETED but Original is '%s': "
                                        + "the Twin has raced ahead of the Original", sample, entry.getKey(),
                                        state[0])
                                .isEqualTo("COMPLETED");
                    }
                }

                Thread.sleep(2000);
            }

            // ---- RELEASE: supply the withheld binding through the same real mechanism ----
            bindCapability(http, base, twinId, CHECKING_ACTIVITY_ID, QUALITY_CHECK_PROVIDER);
            bindCapability(http, base, proxyId, CHECKING_ACTIVITY_ID, QUALITY_CHECK_PROVIDER);

            // Deterministic wait for genuine completion of both instances.
            Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
            boolean bothEnded = false;
            while (Instant.now().isBefore(deadline)) {
                String proxyStatus = fetchStatus(http, base + "/api/v1/process", proxyId);
                String twinStatus = fetchStatus(http, base + "/api/v1/process", twinId);
                if (proxyStatus.contains("\"active\":false") && twinStatus.contains("\"active\":false")) {
                    bothEnded = true;
                    break;
                }
                Thread.sleep(1500);
            }
            assertThat(bothEnded).as("both Original and Twin must reach genuine completion after the "
                    + "withheld binding was supplied").isTrue();

            // Final state: every activity except the untaken rejection branch (Edit Order Details)
            // must be COMPLETED on both sides, and zero incidents must remain.
            java.util.Map<String, String[]> finalSteps = fetchLockstepSteps(http, base, businessKey);
            for (java.util.Map.Entry<String, String[]> entry : finalSteps.entrySet()) {
                if (entry.getKey().equals("_3962D306-3E6E-468A-9767-CCD73D08A592") /* Edit Order Details */) {
                    continue;
                }
                assertThat(entry.getValue()[0]).as("final: Original activity %s", entry.getKey())
                        .isEqualTo("COMPLETED");
                assertThat(entry.getValue()[1]).as("final: Twin activity %s", entry.getKey())
                        .isEqualTo("COMPLETED");
            }
            String proxyIncidents = get(http, base + "/api/v1/process/" + proxyId + "/incidents/count");
            String twinIncidents = get(http, base + "/api/v1/process/" + twinId + "/incidents/count");
            assertThat(proxyIncidents).contains("\"incidentCount\":0");
            assertThat(twinIncidents).contains("\"incidentCount\":0");
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // ---- helpers ----

    private static String post(HttpClient http, String url, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        if (body == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s -> %s", url, response.body()).isEqualTo(200);
        return response.body();
    }

    private static String get(HttpClient http, String url) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String fetchStatus(HttpClient http, String statusBase, String processInstanceId)
            throws IOException, InterruptedException {
        return get(http, statusBase + "/" + processInstanceId + "/status");
    }

    // Real, generic capability-binding mechanism this session's portal layer already exposes -
    // sets the same evolvedAgentType_<activityId> process variable CapabilityDispatcher reads, and
    // resets retries on any of that instance's zero-retry external tasks.
    private static void bindCapability(HttpClient http, String base, String processInstanceId,
            String activityId, String providerType) throws IOException, InterruptedException {
        String body = "{\"activityId\":\"" + activityId + "\",\"providerType\":\"" + providerType + "\"}";
        post(http, base + "/api/portal/instances/" + processInstanceId + "/capability-binding", body);
    }

    // activityId -> [originalState, twinState]
    private static java.util.Map<String, String[]> fetchLockstepSteps(HttpClient http, String base,
            String businessKey) throws IOException, InterruptedException {
        String json = get(http, base + "/api/portal/lockstep?businessKey=" + businessKey);
        java.util.Map<String, String[]> result = new java.util.LinkedHashMap<>();
        Pattern stepPattern = Pattern.compile(
                "\"activityId\":\"([^\"]+)\".*?\"originalState\":\"([^\"]+)\".*?\"twinState\":\"([^\"]+)\"");
        Matcher m = stepPattern.matcher(json);
        while (m.find()) {
            result.put(m.group(1), new String[] { m.group(2), m.group(3) });
        }
        return result;
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
