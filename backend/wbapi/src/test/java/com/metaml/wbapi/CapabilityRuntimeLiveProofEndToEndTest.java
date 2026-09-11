package com.metaml.wbapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
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
import com.metaml.workbench.capability.runtime.CapabilityBinding;
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

// P7 Step 5 - the one link the rest of the capability-binding architecture had not yet proven live:
//
//     FRESH GENERATED TARGET PLATFORM -> REAL HTTP GET /transmute/bindings -> REAL CACHE
//     -> REAL Twin external-task worker -> REAL CapabilityDispatcher -> REAL ComponentExecutor
//     -> REAL reference-providers provider -> REAL CapabilityOutputPropagator -> REAL completion.
//
// Everything upstream of "GET /transmute/bindings" already has its own coverage
// (CapabilityBindingRegistryTest, CapabilityBindingStoreTest, CapabilityBindingCompensationEndToEndTest);
// everything downstream of the generated worker already has its own coverage in isolation
// (CapabilityDispatcherTest, CapabilityDispatcherEngineTest, CapabilityOutputPropagatorTest). This test
// is deliberately the one place that wires a REAL embedded Workbench HTTP server to a REAL, separately
// built and launched standalone JVM and proves the boundary between them actually works - no mocks, no
// direct WorkbenchService/cache injection, no RedCollar/manufacturing domain semantics.
//
// A single real embedded Workbench (this @SpringBootTest's own Spring context, RANDOM_PORT, backing
// its own isolated capability-bindings.json/models/generated-projects under a per-run temp directory)
// serves every scenario below. NodeManagerClient is stubbed only because it is this Workbench's own
// external agent-catalog dependency, not part of the capability runtime chain being proven here -
// exactly the same seam CapabilityOutputContractIntegrationTest already stubs it at.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Tag("slow")
class CapabilityRuntimeLiveProofEndToEndTest {

    private static final Path REAL_TEMPLATE = Path.of("../../templates/camundademo");

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
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private RepositoryService repositoryService;

    // Routes every durable Workbench store to this test run's own temp directory and gives Camunda an
    // isolated in-memory schema - this Workbench must never touch a developer's real
    // ./data/capability-bindings.json or collide with another concurrently running test/session on the
    // same checkout.
    @DynamicPropertySource
    static void isolateWorkbenchState(DynamicPropertyRegistry registry) {
        registry.add("workbench.state.persist", () -> "false");
        registry.add("workbench.workflow.persist", () -> "false");
        registry.add("workbench.governance.approval.persist", () -> "false");
        registry.add("workbench.governance.tenant-policy.persist", () -> "false");
        registry.add("workbench.models.directory", () -> isolatedStoreDir.resolve("models").toString());
        registry.add("workbench.generation.output-directory",
                () -> isolatedStoreDir.resolve("generated-projects").toString());
        registry.add("workbench.generation.template-directory", () -> "../../templates/camundademo");
        registry.add("workbench.capability.binding.file",
                () -> isolatedStoreDir.resolve("capability-bindings.json").toString());
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:metaml-capability-live-proof-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    // ============================================================== SCENARIO 1: THE PRIMARY PROOF

    @Test
    void freshlyGeneratedTargetPlatformRetrievesAndDispatchesARealWorkbenchBinding() throws Exception {
        stubValidatorCatalog();
        governanceService.updatePolicy(Set.of(), 50, 200);

        // ---- 1. Establish the synthetic process and a LEGITIMATE binding through the real
        //         evolution/governance path - never a CapabilityBinding instantiated by this test.
        String activityId = "Activity_Assess";
        ProcessModel model = workbenchService.saveProcessModel(null, "P7 Step 5 synthetic capability process",
                genericWorkbenchProcess("SyntheticCapabilityProcessSource", activityId));
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), activityId, activityId);
        AgentDecision decision = workbenchService.evolveActivity(twin.getId(), activityId, "validator");
        assertThat(decision.isApproved()).as(decision.getReason()).isTrue();
        assertThat(decision.getAgentName()).isEqualTo("validator-agent-01");

        // The binding's actual key - resolved from the live engine, not assumed, since a standalone
        // Target Platform must derive the identical string from its own bundled BPMN.
        String processDefinitionKey = processDefinitionKeyOf(twin.getTwinProcessId());

        // ---- 2. Durable + in-memory, in-process - sanity before crossing to HTTP.
        List<CapabilityBinding> boundInProcess =
                workbenchService.listCapabilityBindings(processDefinitionKey, List.of(activityId));
        assertThat(boundInProcess).hasSize(1);
        assertThat(boundInProcess.get(0).providerId()).isEqualTo("validator-agent-01");

        // ---- 3. REAL HTTP boundary: GET /transmute/bindings against this test's real embedded
        //         Workbench server (a genuine embedded Tomcat on a real TCP port, not a mock).
        HttpClient http = HttpClient.newHttpClient();
        String bindingsUrl = "http://localhost:" + wbPort + "/api/v1/wb/transmute/bindings?processKey="
                + URLEncoder.encode(processDefinitionKey, StandardCharsets.UTF_8) + "&activityIds="
                + URLEncoder.encode(activityId, StandardCharsets.UTF_8);
        HttpResponse<String> bindingsResponse = http.send(
                HttpRequest.newBuilder(URI.create(bindingsUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(bindingsResponse.statusCode()).as(bindingsResponse.body()).isEqualTo(200);
        assertThat(bindingsResponse.body()).contains("validator-agent-01").contains(activityId);

        // ---- 4. Fresh generation, from the CURRENT template, via the CURRENT generation mechanism.
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                tempDir.resolve("generated").toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(
                simpleManufBpmn("SyntheticCapabilityManuf"), assessTwinBpmn(processDefinitionKey, activityId));

        // ---- 5. Real Maven build, real standalone launch, with the real HTTP retrieval seam enabled.
        buildProject(project);
        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project, Map.of(
                    "METAML_CAPABILITY_WORKBENCH_URL", "http://localhost:" + wbPort,
                    "METAML_BROADCASTER_FIXED_DELAY", "5000"));
            String base = "http://localhost:" + launched.port();
            String statusBase = base + "/api/v1/process";

            String instanceId = extractProcessInstanceId(post(http, base + "/api/v1/twin/start").body());

            // ---- 6. Real dispatch + real provider + real output propagation, observed while the
            //         instance is genuinely still active (a completed instance's runtime variables are
            //         gone from RuntimeService by design - this hold-then-release is the only correct
            //         place to observe them without fabricating a check).
            String parkedStatus = awaitActiveActivity(http, statusBase, instanceId, "AssessHold",
                    Duration.ofSeconds(30));
            assertThat(parkedStatus)
                    .as("the real ValidatorExecutor's declared output must have reached the runtime through "
                            + "CapabilityOutputPropagator before the process is allowed past the hold")
                    .contains("twinAutomationOutput_validationPassed_" + activityId)
                    .contains("true");

            // ---- 7. Real completion: release the hold via the real SignalBroadcaster and observe the
            //         process actually end - not merely the activity, the whole instance.
            assertThat(awaitInstanceCompleted(http, statusBase, instanceId, Duration.ofSeconds(30)))
                    .as("the process must reach a genuine end event, not just activity completion").isTrue();
            assertThat(incidentCount(http, statusBase, instanceId)).isZero();

            // ---- 8. The generated Target Platform's OWN runtime log is the primary evidence.
            String log = Files.readString(project.directory().resolve("launch.log"));
            assertThat(log).contains("CAPABILITY RUNTIME READY");
            assertThat(log).contains("CAPABILITY DISPATCH: activity=" + activityId);
            assertThat(log).contains("providerIdentity=validator-agent-01");
            System.out.println("=== P7 Step 5 live proof: relevant generated Target Platform log lines ===");
            log.lines()
                    .filter(l -> l.contains("CAPABILITY") || l.contains("RestCapabilityBindingClient")
                            || l.contains("CapabilityBindingBootstrap"))
                    .forEach(System.out::println);
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // ================================================== SCENARIO 2: REQUIRED CAPABILITY, NO BINDING

    // Section 16 regression: a required capability with no binding must fail explicitly (a genuine
    // Camunda incident) and never fabricate a continuation. Deliberately never establishes any
    // Workbench binding for Activity_Required - the point is to prove the ABSENCE is handled correctly
    // through the same real HTTP + real dispatcher chain, not a shortcut around it.
    @Test
    void requiredCapabilityWithNoBindingSurfacesAsAGenuineIncidentNeverFabricatedContinuation() throws Exception {
        String activityId = "Activity_Required";
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                tempDir.resolve("generated").toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(
                simpleManufBpmn("SyntheticRequiredGapManuf"),
                requiredGapTwinBpmn("SyntheticRequiredGapProcess", activityId));
        buildProject(project);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project,
                    Map.of("METAML_CAPABILITY_WORKBENCH_URL", "http://localhost:" + wbPort));
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + launched.port();
            String statusBase = base + "/api/v1/process";

            String instanceId = extractProcessInstanceId(post(http, base + "/api/v1/twin/start").body());

            assertThat(awaitIncidentCount(http, statusBase, instanceId, 1, Duration.ofSeconds(30)))
                    .as("a required capability with no Workbench binding must surface as a real Camunda "
                            + "incident, never a silent stall or a fabricated continuation").isTrue();
            String status = getStatus(http, statusBase, instanceId);
            assertThat(status).as("the instance must still be active - blocked, not fabricated-continued")
                    .contains("\"active\":true").contains(activityId);
            assertThat(visitCount(http, statusBase, instanceId, "BranchApproved"))
                    .as("neither gateway branch may have executed").isZero();
            assertThat(visitCount(http, statusBase, instanceId, "BranchDefault"))
                    .as("neither gateway branch may have executed").isZero();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // ================================================== SCENARIO 3: ORDINARY UNBOUND, OLD FALLBACK

    // Section 17 regression: a genuinely ordinary (non-capability-required) Twin activity that is also
    // unbound must retain its pre-existing simulated-agent fallback behavior, not fail. No
    // TwinDecisionAgent bean exists in a freshly generated project, so this exercises the built-in
    // simulated fallback specifically.
    @Test
    void ordinaryUnboundTwinActivityRetainsTheExistingFallbackBehavior() throws Exception {
        String activityId = "Activity_Ordinary";
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(REAL_TEMPLATE.toString(),
                tempDir.resolve("generated").toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        GeneratedProject project = generator.generateWithAuthoredTwin(
                simpleManufBpmn("SyntheticOrdinaryManuf"), ordinaryTwinBpmn("SyntheticOrdinaryProcess", activityId));
        buildProject(project);

        SpringBootProjectLauncher launcher = new SpringBootProjectLauncher();
        try {
            LaunchedProject launched = launcher.launch(project, Map.of(
                    "METAML_CAPABILITY_WORKBENCH_URL", "http://localhost:" + wbPort,
                    "METAML_BROADCASTER_FIXED_DELAY", "5000"));
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + launched.port();
            String statusBase = base + "/api/v1/process";

            String instanceId = extractProcessInstanceId(post(http, base + "/api/v1/twin/start").body());

            String parkedStatus = awaitActiveActivity(http, statusBase, instanceId, "OrdinaryHold",
                    Duration.ofSeconds(30));
            assertThat(parkedStatus)
                    .as("an ordinary, non-capability-required, genuinely unbound Twin activity must still "
                            + "fall through to the pre-existing simulated-agent fallback, exactly as before "
                            + "P7 Step 5")
                    .contains("agentTopic").contains("agentInvocationId").contains("agentTimestamp");

            assertThat(awaitInstanceCompleted(http, statusBase, instanceId, Duration.ofSeconds(30)))
                    .as("the ordinary activity's fallback must still let the process complete normally")
                    .isTrue();
            assertThat(incidentCount(http, statusBase, instanceId)).isZero();
        } finally {
            launcher.stop(project.projectId());
        }
    }

    // ------------------------------------------------------------------------------------ fixtures

    private void stubValidatorCatalog() {
        List<IoDeclarationDescriptor> outputs = List.of(
                new IoDeclarationDescriptor("validationPassed", "BOOLEAN", true),
                new IoDeclarationDescriptor("schemaVersion", "STRING", true),
                new IoDeclarationDescriptor("validationStatus", "STRING", true),
                new IoDeclarationDescriptor("validationMessage", "STRING", true));
        AgentAvailabilityResult validator = new AgentAvailabilityResult("validator", true, "validator-agent-01",
                "available in synthetic test catalog", Map.of(),
                "synthetic validator provider used for the P7 Step 5 capability runtime live proof",
                List.of(), List.of(), outputs);
        given(nodeManagerClient.checkAgentAvailability("validator")).willReturn(validator);
        given(nodeManagerClient.listAgents()).willReturn(List.of(validator));
    }

    private String processDefinitionKeyOf(String twinProcessInstanceId) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(twinProcessInstanceId).singleResult();
        assertThat(instance).as("workbench twin process instance must still exist").isNotNull();
        ProcessDefinition definition = repositoryService.getProcessDefinition(instance.getProcessDefinitionId());
        return definition.getKey();
    }

    // The Workbench's own internal model driving evolveActivity/connectActivity - a plain userTask
    // flow, matching the shape CapabilityBindingCompensationEndToEndTest already established as
    // sufficient for this path. Deliberately independent of the standalone Target Platform's own
    // bundled Twin BPMN (assessTwinBpmn below); the two are connected only by the
    // (processDefinitionKey, activityId) binding key, exactly as the architecture intends.
    private static String genericWorkbenchProcess(String processId, String activityId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_%1$s" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="%1$s" name="Synthetic Capability Process Source" isExecutable="true">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="%2$s" name="Assess">
                      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="%2$s" />
                    <bpmn:sequenceFlow id="F2" sourceRef="%2$s" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(processId, activityId);
    }

    // Trivial, unused Main/proxy half - generateWithAuthoredTwin requires one, but this test's subject
    // is entirely the Twin side, so this side is never started.
    private static String simpleManufBpmn(String processId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_%1$s" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="%1$s" name="%1$s" isExecutable="true">
                    <bpmn2:startEvent id="ManufStart" />
                    <bpmn2:serviceTask id="ManufStep" name="Step" camunda:type="external"
                        camunda:topic="%1$sStep" />
                    <bpmn2:endEvent id="ManufEnd" />
                    <bpmn2:sequenceFlow id="ManufFlow1" sourceRef="ManufStart" targetRef="ManufStep" />
                    <bpmn2:sequenceFlow id="ManufFlow2" sourceRef="ManufStep" targetRef="ManufEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(processId);
    }

    // Start -> Twin capability-bound Service Task -> Hold (real SignalBroadcaster releases it) -> End.
    // The hold exists only so this test can observe the propagated output while the instance is
    // genuinely still active - a completed instance's runtime variables are gone from RuntimeService.
    private static String assessTwinBpmn(String processDefinitionKey, String activityId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_SyntheticCapabilityTwin" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:signal id="Signal_AssessHold" name="AssessHoldSignal" />
                  <bpmn2:process id="%1$s" name="Synthetic Capability Twin" isExecutable="true">
                    <bpmn2:startEvent id="AssessStart" />
                    <bpmn2:serviceTask id="%2$s" name="Assess" camunda:type="external" camunda:topic="%2$s" />
                    <bpmn2:intermediateCatchEvent id="AssessHold" name="Hold">
                      <bpmn2:signalEventDefinition signalRef="Signal_AssessHold" />
                    </bpmn2:intermediateCatchEvent>
                    <bpmn2:endEvent id="AssessEnd" />
                    <bpmn2:sequenceFlow id="AssessFlow1" sourceRef="AssessStart" targetRef="%2$s" />
                    <bpmn2:sequenceFlow id="AssessFlow2" sourceRef="%2$s" targetRef="AssessHold" />
                    <bpmn2:sequenceFlow id="AssessFlow3" sourceRef="AssessHold" targetRef="AssessEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(processDefinitionKey, activityId);
    }

    // Start -> Required Service Task -> exclusive gateway on ${approved} -> two branches. The gateway
    // immediately downstream is what makes REQUIRES_CAPABILITY_OUTPUT true for the required activity
    // (ExternalTaskWorkerGenerator), mirroring GatewayOutputProviderEndToEndTest's proven shape.
    private static String requiredGapTwinBpmn(String processId, String activityId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_%1$s" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="%1$s" name="Synthetic Required Gap Twin" isExecutable="true">
                    <bpmn2:startEvent id="ReqStart" />
                    <bpmn2:serviceTask id="%2$s" name="Required" camunda:type="external" camunda:topic="%2$s" />
                    <bpmn2:exclusiveGateway id="Gateway_Required" name="Approved?">
                      <bpmn2:incoming>ReqFlow2</bpmn2:incoming>
                      <bpmn2:outgoing>ReqFlowTrue</bpmn2:outgoing>
                      <bpmn2:outgoing>ReqFlowFalse</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:serviceTask id="BranchApproved" name="Approved" camunda:type="external"
                        camunda:topic="BranchApprovedTopic" />
                    <bpmn2:serviceTask id="BranchDefault" name="Default" camunda:type="external"
                        camunda:topic="BranchDefaultTopic" />
                    <bpmn2:endEvent id="ReqEndTrue" />
                    <bpmn2:endEvent id="ReqEndFalse" />
                    <bpmn2:sequenceFlow id="ReqFlow1" sourceRef="ReqStart" targetRef="%2$s" />
                    <bpmn2:sequenceFlow id="ReqFlow2" sourceRef="%2$s" targetRef="Gateway_Required" />
                    <bpmn2:sequenceFlow id="ReqFlowTrue" sourceRef="Gateway_Required" targetRef="BranchApproved">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${approved}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="ReqFlowFalse" sourceRef="Gateway_Required" targetRef="BranchDefault">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!approved}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="ReqFlow3" sourceRef="BranchApproved" targetRef="ReqEndTrue" />
                    <bpmn2:sequenceFlow id="ReqFlow4" sourceRef="BranchDefault" targetRef="ReqEndFalse" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(processId, activityId);
    }

    // Start -> ordinary Twin Service Task (no downstream gateway) -> Hold -> End.
    private static String ordinaryTwinBpmn(String processId, String activityId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_%1$s" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:signal id="Signal_OrdinaryHold" name="OrdinaryHoldSignal" />
                  <bpmn2:process id="%1$s" name="Synthetic Ordinary Twin" isExecutable="true">
                    <bpmn2:startEvent id="OrdinaryStart" />
                    <bpmn2:serviceTask id="%2$s" name="Ordinary" camunda:type="external" camunda:topic="%2$s" />
                    <bpmn2:intermediateCatchEvent id="OrdinaryHold" name="Hold">
                      <bpmn2:signalEventDefinition signalRef="Signal_OrdinaryHold" />
                    </bpmn2:intermediateCatchEvent>
                    <bpmn2:endEvent id="OrdinaryEnd" />
                    <bpmn2:sequenceFlow id="OrdinaryFlow1" sourceRef="OrdinaryStart" targetRef="%2$s" />
                    <bpmn2:sequenceFlow id="OrdinaryFlow2" sourceRef="%2$s" targetRef="OrdinaryHold" />
                    <bpmn2:sequenceFlow id="OrdinaryFlow3" sourceRef="OrdinaryHold" targetRef="OrdinaryEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(processId, activityId);
    }

    // ------------------------------------------------------------------------------------- helpers

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

    private static String getStatus(HttpClient http, String statusBase, String processInstanceId)
            throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId + "/status")).GET()
                        .build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static boolean awaitInstanceCompleted(HttpClient http, String statusBase, String processInstanceId,
            Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (getStatus(http, statusBase, processInstanceId).contains("\"active\":false")) {
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    // Polls until the given activity id appears among the instance's active activities, returning the
    // body that proved it - real engine state, not log text. Throws on timeout.
    private static String awaitActiveActivity(HttpClient http, String statusBase, String processInstanceId,
            String activityId, Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String lastBody = "";
        while (Instant.now().isBefore(deadline)) {
            lastBody = getStatus(http, statusBase, processInstanceId);
            if (lastBody.contains(activityId)) {
                return lastBody;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("Timed out waiting for activity '" + activityId + "' on instance "
                + processInstanceId + ". Last status: " + lastBody);
    }

    private static long visitCount(HttpClient http, String statusBase, String processInstanceId, String activityId)
            throws IOException, InterruptedException {
        String body = http.send(HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId
                        + "/activity-history/" + activityId + "/count")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return extractLongField(body, "visitCount");
    }

    private static long incidentCount(HttpClient http, String statusBase, String processInstanceId)
            throws IOException, InterruptedException {
        String body = http.send(HttpRequest.newBuilder(URI.create(statusBase + "/" + processInstanceId
                        + "/incidents/count")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        return extractLongField(body, "incidentCount");
    }

    private static boolean awaitIncidentCount(HttpClient http, String statusBase, String processInstanceId,
            long expected, Duration timeout) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (incidentCount(http, statusBase, processInstanceId) >= expected) {
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
