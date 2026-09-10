package com.metaml.wbapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

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
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.IoDeclarationDescriptor;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.LaunchedProject;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.GovernanceService;
import com.metaml.workbench.service.WorkbenchService;

// P7 Final Acceptance Closure - the EXISTING RedCollar use case, completed legitimately rather than
// declared PASS around the fail-closed incident P7 Step 6 correctly surfaced.
//
// VerifyOrder's gateway reads ${orderApproved}; Checking's gateway reads ${qualityPassed}. Neither
// name has ever had a legitimate producer in this codebase - git history shows both were previously
// fabricated with Math.random() > 0.5, removed by an earlier architectural correction with nothing
// legitimate put in its place. This test establishes REAL CapabilityBinding for both, through the
// SAME real evolve/governance/binding/HTTP-retrieval/CapabilityDispatcher path
// CapabilityRuntimeLiveProofEndToEndTest already proved for a synthetic activity, and then runs the
// UNMODIFIED, real RedCollar Manuf-camunda.bpmn (auto-derived twin, the generic mirror mechanism) to
// genuine completion in a freshly generated, separately launched, standalone Target Platform.
//
// Nothing here weakens fail-closed behavior: OrderApprovalExecutor and QualityCheckExecutor compute
// their outputs from real process variables (see their own class comments) - Math.random() is gone
// and stays gone. The Workbench's own internal fixture model below exists ONLY to drive a real
// evolve/governance decision under the same (processDefinitionKey, activityId) pair the real
// generated RedCollar Twin will look up at startup - exactly the seam
// CapabilityRuntimeLiveProofEndToEndTest's own comment documents ("connected only by the
// (processDefinitionKey, activityId) binding key, exactly as the architecture intends"). The
// STANDALONE TARGET PLATFORM ITSELF bundles the real, unmodified Manuf-camunda.bpmn.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Tag("slow")
class RedCollarCapabilityBindingLiveProofEndToEndTest {

    private static final Path REAL_TEMPLATE = Path.of("../RedCollarTP");
    private static final String VERIFY_ORDER_ACTIVITY_ID = "_BF94795D-C812-42FC-987C-536FFF9BFDB1";
    private static final String CHECKING_ACTIVITY_ID = "_8F5A4559-5D75-4D79-AEEC-E6EE61672A5D";
    private static final String MANUF_PROCESS_ID = "RedCollar.Manuf";

    @TempDir
    static Path isolatedStoreDir;

    @TempDir
    Path tempDir;

    @Value("${local.server.port}")
    private int wbPort;

    @MockitoBean
    private NodeManagerClient nodeManagerClient;

    @Autowired
    private WorkbenchService workbenchService;
    @Autowired
    private GovernanceService governanceService;

    @DynamicPropertySource
    static void isolateWorkbenchState(DynamicPropertyRegistry registry) {
        registry.add("workbench.state.persist", () -> "false");
        registry.add("workbench.workflow.persist", () -> "false");
        registry.add("workbench.governance.approval.persist", () -> "false");
        registry.add("workbench.governance.tenant-policy.persist", () -> "false");
        registry.add("workbench.models.directory", () -> isolatedStoreDir.resolve("models").toString());
        registry.add("workbench.generation.output-directory",
                () -> isolatedStoreDir.resolve("generated-projects").toString());
        registry.add("workbench.generation.template-directory", () -> "../RedCollarTP");
        registry.add("workbench.capability.binding.file",
                () -> isolatedStoreDir.resolve("capability-bindings.json").toString());
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:metaml-redcollar-capability-live-proof-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1");
    }

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
    void existingRedCollarProcessCompletesLegitimatelyWithRealCapabilityBindingsForBothGateways() throws Exception {
        Path repoRoot = redCollarBpmnDir();
        Assumptions.assumeTrue(Files.isRegularFile(repoRoot.resolve("Manuf-camunda.bpmn")),
                "RedCollar Manuf-camunda.bpmn not found at " + repoRoot.toAbsolutePath());
        Assumptions.assumeTrue(Files.isDirectory(REAL_TEMPLATE), "RedCollarTP template must exist at "
                + REAL_TEMPLATE.toAbsolutePath());
        HttpClient http = HttpClient.newHttpClient();
        Assumptions.assumeTrue(rabbitMqReachable(http), "no RabbitMQ broker reachable at localhost:15672");

        stubReferenceCatalog();
        governanceService.updatePolicy(Set.of(), 50, 200);

        // ---- 1. Establish REAL CapabilityBindings for both gateway-gated activities, through the
        //         real evolve/governance path - never a CapabilityBinding instantiated by this test.
        //         Two independent single-activity fixtures (evolveActivity requires the ORIGINAL
        //         process instance to have actually reached the activity being evolved, so one
        //         fixture cannot carry both activities unless something completes the first).
        ProcessModel orderModel = workbenchService.saveProcessModel(null,
                "RedCollar order-approval binding fixture", workbenchFixtureBpmn(VERIFY_ORDER_ACTIVITY_ID));
        TwinProcess orderTwin = workbenchService.launchProcess(orderModel.getId());
        workbenchService.connectActivity(orderTwin.getId(), VERIFY_ORDER_ACTIVITY_ID, VERIFY_ORDER_ACTIVITY_ID);
        AgentDecision orderDecision = workbenchService.evolveActivity(orderTwin.getId(), VERIFY_ORDER_ACTIVITY_ID,
                "order-approval");
        assertThat(orderDecision.isApproved()).as(orderDecision.getReason()).isTrue();
        assertThat(orderDecision.getAgentName()).isEqualTo("order-approval-agent-01");

        ProcessModel qualityModel = workbenchService.saveProcessModel(null,
                "RedCollar quality-check binding fixture", workbenchFixtureBpmn(CHECKING_ACTIVITY_ID));
        TwinProcess qualityTwin = workbenchService.launchProcess(qualityModel.getId());
        workbenchService.connectActivity(qualityTwin.getId(), CHECKING_ACTIVITY_ID, CHECKING_ACTIVITY_ID);
        AgentDecision qualityDecision = workbenchService.evolveActivity(qualityTwin.getId(), CHECKING_ACTIVITY_ID,
                "quality-check");
        assertThat(qualityDecision.isApproved()).as(qualityDecision.getReason()).isTrue();
        assertThat(qualityDecision.getAgentName()).isEqualTo("quality-check-agent-01");

        // processDefinitionKey the Workbench's own twin deployed under - TwinModelGenerator's
        // TWIN_PROCESS_ID_SUFFIX ("_twin") matches TargetPlatformTwinMirrorGenerator's own suffix,
        // so this equals the string the real generated RedCollar Target Platform's auto-derived twin
        // will independently arrive at from the real Manuf-camunda.bpmn's own process id.
        String processDefinitionKey = MANUF_PROCESS_ID + "_twin";
        assertThat(workbenchService.listCapabilityBindings(processDefinitionKey,
                List.of(VERIFY_ORDER_ACTIVITY_ID, CHECKING_ACTIVITY_ID))).hasSize(2);

        // ---- 2. REAL HTTP boundary: the same endpoint a generated Target Platform's own
        //         CapabilityBindingBootstrap calls at startup.
        String bindingsUrl = "http://localhost:" + wbPort + "/api/v1/wb/transmute/bindings?processKey="
                + java.net.URLEncoder.encode(processDefinitionKey, StandardCharsets.UTF_8) + "&activityIds="
                + java.net.URLEncoder.encode(VERIFY_ORDER_ACTIVITY_ID, StandardCharsets.UTF_8) + "&activityIds="
                + java.net.URLEncoder.encode(CHECKING_ACTIVITY_ID, StandardCharsets.UTF_8);
        HttpResponse<String> bindingsResponse = http.send(
                HttpRequest.newBuilder(URI.create(bindingsUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(bindingsResponse.statusCode()).as(bindingsResponse.body()).isEqualTo(200);
        assertThat(bindingsResponse.body()).contains("order-approval-agent-01").contains("quality-check-agent-01");

        // ---- 3. Fresh generation of the REAL, unmodified RedCollar Manuf-camunda.bpmn, auto-derived
        //         twin (the generic TargetPlatformTwinMirrorGenerator mechanism, not a hand-authored one).
        String manufBpmnXml = Files.readString(repoRoot.resolve("Manuf-camunda.bpmn"));
        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generate(manufBpmnXml, List.of());

        // ---- 3b. The PROXY side runs its OWN copy of the same orderApproved/qualityPassed gateway
        //          on its OWN process instance (RabbitMQ only synchronizes timing between Proxy and
        //          Twin, never process variables) - so the Proxy independently needs a real,
        //          non-fabricated producer too. Registered exactly the way a real business
        //          GatewayOutputProvider implementation would be, reusing the identical decision
        //          logic the Twin runs through CapabilityDispatcher, so neither side ever invents its
        //          own separate answer.
        writeRedCollarGatewayOutputProvider(project);

        // ---- 4. Real Maven build + real standalone launch (RedCollarTP has no mvnw - launch() does
        //         'mvn clean install -DskipTests' then 'mvn spring-boot:run' itself), wired to this
        //         test's real embedded Workbench for binding retrieval.
        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched;
            try {
                launched = launcher.launch(project,
                        Map.of("METAML_CAPABILITY_WORKBENCH_URL", "http://localhost:" + wbPort));
            } catch (Exception launchEx) {
                Path launchLog = project.directory().resolve("launch.log");
                Path buildLog = project.directory().resolve("build.log");
                String logContent = Files.exists(launchLog) ? Files.readString(launchLog) : "(no launch.log)";
                String buildContent = Files.exists(buildLog) ? Files.readString(buildLog) : "(no build.log)";
                throw new AssertionError("Launch failed. build.log:\n" + buildContent + "\nlaunch.log:\n"
                        + logContent, launchEx);
            }

            String proxyBase = "http://localhost:" + launched.port() + "/api/proxy";
            String twinBase = "http://localhost:" + launched.port() + "/api/twin";
            String statusBase = "http://localhost:" + launched.port() + "/api/v1/process";
            String businessKey = "closure-proof-" + UUID.randomUUID();

            // ---- 5. Both bindings must have been resolved from the real Workbench at startup.
            String bootstrapLog = awaitLogContaining(project.directory(),
                    "CAPABILITY BINDING BOOTSTRAP: cache warmed with 2 Workbench-authoritative binding(s)",
                    Duration.ofSeconds(30));
            // "2 of <N>": N is however many camunda:type="external" activities the whole bundled
            // Twin BPMN carries (RestCapabilityBindingClient offers every one to the bulk fetch, not
            // only the two gateway-gated ones - see its own class comment) - what matters here is
            // that exactly the two real bindings this test created were the ones actually resolved.
            assertThat(bootstrapLog).containsPattern("resolved 2 of \\d+ activity binding\\(s\\) for process '"
                    + java.util.regex.Pattern.quote(processDefinitionKey) + "'");

            // ---- 6. Start the real Main/Proxy and Twin process instances.
            HttpResponse<String> proxyStart = http.send(
                    HttpRequest.newBuilder(URI.create(proxyBase + "/start?businessKey=" + businessKey))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(proxyStart.statusCode()).as(proxyStart.body()).isEqualTo(200);
            String proxyInstanceId = extractField(proxyStart.body(), "processInstanceId");

            http.send(HttpRequest.newBuilder(URI.create(twinBase + "/start?businessKey=" + businessKey))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

            // ---- 7. Both real capability dispatches must occur, with their real, non-fabricated,
            //         legitimately-produced outputs - not merely "some" dispatch.
            String orderDispatchLog = awaitLogContaining(project.directory(),
                    "CAPABILITY DISPATCH: activity=" + VERIFY_ORDER_ACTIVITY_ID, Duration.ofSeconds(60));
            assertThat(orderDispatchLog).contains("providerIdentity=order-approval-agent-01");
            assertThat(awaitLogContaining(project.directory(),
                    "CAPABILITY COMPLETE: activity=" + VERIFY_ORDER_ACTIVITY_ID, Duration.ofSeconds(30)))
                    .contains("orderApproved=true");

            String qualityDispatchLog = awaitLogContaining(project.directory(),
                    "CAPABILITY DISPATCH: activity=" + CHECKING_ACTIVITY_ID, Duration.ofSeconds(180));
            assertThat(qualityDispatchLog).contains("providerIdentity=quality-check-agent-01");
            assertThat(awaitLogContaining(project.directory(),
                    "CAPABILITY COMPLETE: activity=" + CHECKING_ACTIVITY_ID, Duration.ofSeconds(30)))
                    .contains("qualityPassed=true");

            // ---- 8. Both directions of real RabbitMQ traffic occurred.
            assertThat(awaitLogContaining(project.directory(), "TASK: published signal", Duration.ofSeconds(30)))
                    .contains("to RabbitMQ exchange");
            assertThat(awaitLogContaining(project.directory(), "RESPONSE: published signal", Duration.ofSeconds(30)))
                    .contains("to RabbitMQ exchange");

            // ---- 9. The Main/Proxy instance must reach genuine completion, with zero incidents -
            //         no fabricated continuation ever needed, because a real provider legitimately
            //         supplied both required outputs.
            boolean completed = awaitInstanceInactive(http, statusBase, proxyInstanceId, Duration.ofSeconds(300));
            if (!completed) {
                String tail = Files.readString(project.directory().resolve("launch.log"));
                String status = getStatus(http, statusBase, proxyInstanceId);
                throw new AssertionError("proxy instance " + proxyInstanceId
                        + " did not reach completion within 300s. Current status: " + status
                        + "\n--- tail of launch.log ---\n"
                        + tail.substring(Math.max(0, tail.length() - 6000)));
            }
            assertThat(incidentCount(http, statusBase, proxyInstanceId))
                    .as("zero incidents: nothing was fabricated to get here").isZero();

            // ---- 10. The generated Target Platform's OWN runtime log is the primary evidence.
            String fullLog = Files.readString(project.directory().resolve("launch.log"));
            System.out.println("=== P7 Final Acceptance Closure: relevant generated Target Platform log lines ===");
            fullLog.lines()
                    .filter(l -> l.contains("CAPABILITY") || l.contains("TASK: published")
                            || l.contains("RESPONSE: published") || l.contains("delivered signal")
                            || l.contains("Started Redcollar"))
                    .forEach(System.out::println);
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // ------------------------------------------------------------------------------------ fixtures

    private void stubReferenceCatalog() {
        AgentAvailabilityResult orderApproval = new AgentAvailabilityResult("order-approval", true,
                "order-approval-agent-01", "available in synthetic test catalog", Map.of(),
                "generic order-approval reference provider", List.of(),
                List.of(new IoDeclarationDescriptor("quantity", "NUMBER", false),
                        new IoDeclarationDescriptor("orderStatus", "STRING", false),
                        new IoDeclarationDescriptor("forceOrderRejection", "BOOLEAN", false)),
                List.of(new IoDeclarationDescriptor("orderApproved", "BOOLEAN", true),
                        new IoDeclarationDescriptor("approvalReason", "STRING", true)));
        AgentAvailabilityResult qualityCheck = new AgentAvailabilityResult("quality-check", true,
                "quality-check-agent-01", "available in synthetic test catalog", Map.of(),
                "generic quality-check reference provider", List.of(),
                List.of(new IoDeclarationDescriptor("orderStatus", "STRING", false),
                        new IoDeclarationDescriptor("forceQualityFailure", "BOOLEAN", false)),
                List.of(new IoDeclarationDescriptor("qualityPassed", "BOOLEAN", true),
                        new IoDeclarationDescriptor("qualityMessage", "STRING", true)));
        given(nodeManagerClient.checkAgentAvailability("order-approval")).willReturn(orderApproval);
        given(nodeManagerClient.checkAgentAvailability("quality-check")).willReturn(qualityCheck);
        given(nodeManagerClient.listAgents()).willReturn(List.of(orderApproval, qualityCheck));
    }

    // The PROXY-side legitimate producer for its own copy of the orderApproved/qualityPassed gateway
    // variables - written into the generated project's own source tree exactly the way
    // GatewayOutputProviderEndToEndTest's TestGatewayOutputProvider is, and exactly the way a real
    // business implementation would be dropped in. Reuses OrderApprovalExecutor/QualityCheckExecutor
    // directly (via the same generic ExternalTaskExecutionContext adapter the Twin's own
    // CapabilityDispatcher path uses) so both sides of this Target Platform reach the identical real
    // decision from the identical real process data - never two independently-fabricated answers.
    private static void writeRedCollarGatewayOutputProvider(GeneratedProject project) throws java.io.IOException {
        String workerPackage = "com.tp.TargetPlatform.worker.proxy";
        Path packageDir = project.directory().resolve("src/main/java").resolve(workerPackage.replace('.', '/'));
        Files.createDirectories(packageDir);
        String source = """
                package %1$s;

                import java.util.Map;

                import org.camunda.bpm.engine.RuntimeService;
                import org.camunda.bpm.engine.externaltask.LockedExternalTask;
                import org.springframework.stereotype.Component;

                import com.metaml.workbench.automation.OrderApprovalExecutor;
                import com.metaml.workbench.automation.QualityCheckExecutor;
                import com.metaml.workbench.capability.runtime.ExternalTaskExecutionContext;

                @Component
                public class RedCollarGatewayOutputProvider implements GatewayOutputProvider {

                    private final OrderApprovalExecutor orderApprovalExecutor = new OrderApprovalExecutor();
                    private final QualityCheckExecutor qualityCheckExecutor = new QualityCheckExecutor();
                    private final RuntimeService runtimeService;

                    public RedCollarGatewayOutputProvider(RuntimeService runtimeService) {
                        this.runtimeService = runtimeService;
                    }

                    @Override
                    public Map<String, Object> provide(String topic, LockedExternalTask task) {
                        ExternalTaskExecutionContext context = new ExternalTaskExecutionContext(task, runtimeService);
                        if ("VerifyOrder".equals(topic)) {
                            return orderApprovalExecutor.execute(context, task.getActivityId(),
                                    OrderApprovalExecutor.AGENT_NAME).outputs();
                        }
                        if ("Checking".equals(topic)) {
                            return qualityCheckExecutor.execute(context, task.getActivityId(),
                                    QualityCheckExecutor.AGENT_NAME).outputs();
                        }
                        return Map.of();
                    }
                }
                """.formatted(workerPackage);
        Files.writeString(packageDir.resolve("RedCollarGatewayOutputProvider.java"), source);
    }

    // The Workbench's OWN internal source model driving evolveActivity/connectActivity - deliberately
    // independent of the standalone Target Platform's own bundled BPMN (the real Manuf-camunda.bpmn),
    // exactly as CapabilityRuntimeLiveProofEndToEndTest's own comment establishes: the two are
    // connected only by the (processDefinitionKey, activityId) binding key. Sharing MANUF_PROCESS_ID
    // as this model's own process id is what makes TwinModelGenerator's "_twin"-suffixed key equal
    // the real generated Target Platform's own auto-derived twin key. One activity per fixture:
    // evolveActivity requires the ORIGINAL instance to have actually reached the activity being
    // evolved, so a single, always-open userTask is what keeps each fixture's activity "reached".
    private static String workbenchFixtureBpmn(String activityId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_%1$s_%2$s" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="%1$s" name="RedCollar Manuf binding fixture" isExecutable="true">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="%2$s" name="Fixture Activity">
                      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="%2$s" />
                    <bpmn:sequenceFlow id="F2" sourceRef="%2$s" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(MANUF_PROCESS_ID, activityId);
    }

    // ------------------------------------------------------------------------------------- helpers

    private static String extractField(String json, String fieldName) {
        String marker = "\"" + fieldName + "\"";
        int key = json.indexOf(marker);
        int firstQuote = json.indexOf('"', key + marker.length() + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static String getStatus(HttpClient http, String statusBase, String processInstanceId)
            throws java.io.IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status")).GET()
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static boolean awaitInstanceInactive(HttpClient http, String statusBase, String processInstanceId,
            Duration timeout) throws java.io.IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (getStatus(http, statusBase, processInstanceId).contains("\"active\":false")) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static long incidentCount(HttpClient http, String statusBase, String processInstanceId)
            throws java.io.IOException, InterruptedException {
        String body = http.send(HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId
                        + "/incidents/count")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"incidentCount\"\\s*:\\s*(-?\\d+)")
                .matcher(body);
        if (!m.find()) {
            throw new IllegalArgumentException("incidentCount not found in: " + body);
        }
        return Long.parseLong(m.group(1));
    }

    private static String awaitLogContaining(Path projectDir, String fragment, Duration timeout)
            throws java.io.IOException {
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
