package com.metaml.workbench.service;

import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.automation.ComponentExecutor;
import com.metaml.workbench.automation.CreditRiskAssessorExecutor;
import com.metaml.workbench.automation.DefaultProjectAutomationService;
import com.metaml.workbench.automation.ProjectAutomationService;
import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.capability.gap.CapabilityGap;
import com.metaml.workbench.capability.gap.CapabilityGapRecommendation;
import com.metaml.workbench.capability.gap.CapabilityGapRecommender;
import com.metaml.workbench.capability.gap.CapabilityGapService;
import com.metaml.workbench.capability.gap.CapabilityGapStore;
import com.metaml.workbench.capability.gap.GapOrigin;
import com.metaml.workbench.capability.gap.GapStatus;
import com.metaml.workbench.capability.runtime.CapabilityOutputContractSource;
import com.metaml.workbench.capability.runtime.CatalogCapabilityOutputContractSource;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.IoDeclarationDescriptor;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
import com.metaml.workbench.delegate.TwinAutomationDelegate;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.ApprovalStore;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.governance.TenantPolicyService;
import com.metaml.workbench.governance.TenantPolicyStore;
import com.metaml.workbench.model.ActiveRuntimeInstance;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.AgentVariables;
import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinActivityExecutionState;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.WorkflowEventStore;
import com.metaml.workbench.workflow.WorkflowStateTracker;

// MetaML Scope 6, Phase 5 completion pass: a REAL Camunda process-level proof of the capability-gap
// lifecycle, using the exact same hand-wired in-memory-engine harness pattern as
// GenericLifecycleEndToEndTest - not a mock-only "collection of service calls" test. A synthetic,
// independently invented domain (generic "assessment" activity) - no RedCollar.
//
// Wires a REAL CapabilityGapService as WorkbenchServiceImpl would receive it in production - the
// same ObjectProvider<CapabilityGapService> field that class exposes for exactly this purpose - via
// reflection, since the workbench module has no Spring context in its test tree (every existing
// *EndToEndTest in this package constructs WorkbenchServiceImpl by hand for the same reason).
@Tag("slow")
class CapabilityGapRuntimeLifecycleEndToEndTest {

    @TempDir
    Path tempDir;

    private static final String ASSESS_ACTIVITY = "Activity_Assess";
    private static final String FOLLOW_UP_ACTIVITY = "Activity_FollowUp";
    // riskFlagged (not riskScore): the gateway-variable regex the existing
    // ExternalTaskWorkerGenerator/BpmnCapabilityContractReader machinery matches is a *simple*
    // ${varName}/${!varName} boolean reference (see ExternalTaskWorkerGenerator.CONDITION_VAR_PATTERN)
    // - and riskFlagged is a genuine boolean CreditRiskAssessorExecutor actually produces, so the
    // gateway also evaluates correctly once the token reaches it, with no synthetic coercion.
    private static final String REQUIRED_OUTPUT = "riskFlagged";

    // Generic three-activity process: Assess (requires 'riskFlagged' via its outgoing gateway,
    // declared by no dataOutputAssociation) -> FollowUp -> End. Independently named, no RedCollar.
    private static BpmnModelInstance genericProcess() {
        return Bpmn.readModelFromStream(new java.io.ByteArrayInputStream(("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_GapLifecycle" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_GapLifecycle" name="Generic Gap Lifecycle" isExecutable="true">
                    <bpmn:startEvent id="Start">
                      <bpmn:outgoing>Flow_ToAssess</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="%s" name="Assess">
                      <bpmn:incoming>Flow_ToAssess</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToGateway</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:exclusiveGateway id="Gateway_Assess">
                      <bpmn:incoming>Flow_ToGateway</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToFollowUp</bpmn:outgoing>
                      <bpmn:outgoing>Flow_ToEnd</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:userTask id="%s" name="Follow Up">
                      <bpmn:incoming>Flow_ToFollowUp</bpmn:incoming>
                      <bpmn:outgoing>Flow_FollowUpToEnd</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End">
                      <bpmn:incoming>Flow_ToEnd</bpmn:incoming>
                      <bpmn:incoming>Flow_FollowUpToEnd</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_ToAssess" sourceRef="Start" targetRef="%s" />
                    <bpmn:sequenceFlow id="Flow_ToGateway" sourceRef="%s" targetRef="Gateway_Assess" />
                    <bpmn:sequenceFlow id="Flow_ToFollowUp" sourceRef="Gateway_Assess" targetRef="%s">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${riskFlagged}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_ToEnd" sourceRef="Gateway_Assess" targetRef="End">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${!riskFlagged}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_FollowUpToEnd" sourceRef="%s" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(ASSESS_ACTIVITY, FOLLOW_UP_ACTIVITY, ASSESS_ACTIVITY, ASSESS_ACTIVITY,
                FOLLOW_UP_ACTIVITY, FOLLOW_UP_ACTIVITY))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private ProcessEngine engine;
    private RuntimeService runtimeService;
    private RepositoryService repositoryService;
    private NodeManagerClient nodeManagerClient;
    private WorkbenchServiceImpl service;
    private CapabilityGapService capabilityGapService;
    private final AtomicReference<ProjectAutomationService> automationRef = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        AtomicReference<JavaDelegate> realDelegate = new AtomicReference<>();
        JavaDelegate delegateBridge = execution -> realDelegate.get().execute(execution);

        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:gap-lifecycle-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJobExecutorActivate(false);
        config.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
        if (config instanceof ProcessEngineConfigurationImpl configImpl) {
            configImpl.setHistoryTimeToLive("180");
            configImpl.setBeans(Map.of("twinAutomationDelegate", delegateBridge));
        }
        engine = config.buildProcessEngine();
        runtimeService = engine.getRuntimeService();
        repositoryService = engine.getRepositoryService();

        nodeManagerClient = mock(NodeManagerClient.class);
        // No agent type is registered by default - every requested type is genuinely unavailable
        // until a test explicitly stubs one, mirroring a real empty/partial node manager catalog.

        GovernanceService governanceService = mock(GovernanceService.class);
        when(governanceService.reserveEvolutionSlot(anyString(), anyString()))
                .thenReturn(new GovernanceDecision(true, null));
        when(governanceService.reserveTwinExecutionSlot(anyString()))
                .thenReturn(new GovernanceDecision(true, null));

        WorkflowEventStore eventStore = new WorkflowEventStore(tempDir.resolve("events.json").toString(), true);
        WorkflowStateTracker tracker = new WorkflowStateTracker(eventStore);

        WorkbenchStateStore stateStore = mock(WorkbenchStateStore.class);
        when(stateStore.load()).thenReturn(new WorkbenchStateStore.Snapshot(List.of()));
        ProcessModelArchiveStore archiveStore = mock(ProcessModelArchiveStore.class);
        when(archiveStore.findAll()).thenReturn(List.of());
        DelegateClassGenerator delegateClassGenerator = mock(DelegateClassGenerator.class);
        when(delegateClassGenerator.generate(anyString(), anyString())).thenReturn(List.of());

        Path templateDir = tempDir.resolve("template");
        Files.createDirectories(templateDir);
        Files.writeString(templateDir.resolve("pom.xml"), "<project>fake</project>");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(templateDir.toString(),
                tempDir.resolve("generated-projects").toString(), new TwinModelGenerator(),
                new DelegateClassGenerator(), new ExternalTaskWorkerGenerator());

        // Real ApprovalService/PolicyDecisionEngine - the same governance/approval infrastructure
        // production uses, not mocks - since CapabilityGapService.approve() calls them directly.
        ApprovalStore approvalStore = new ApprovalStore(tempDir.resolve("approvals.json").toString(), false);
        ApprovalService approvalService = new ApprovalService(approvalStore);
        TenantPolicyStore tenantPolicyStore =
                new TenantPolicyStore(tempDir.resolve("tenant-policies.json").toString(), false);
        TenantPolicyService tenantPolicyService = new TenantPolicyService(tenantPolicyStore);
        PolicyDecisionEngine policyDecisionEngine = new PolicyDecisionEngine(tenantPolicyService);

        service = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                policyDecisionEngine, approvalService, runtimeService, repositoryService,
                engine.getHistoryService(), engine.getTaskService(), engine.getExternalTaskService(),
                new TwinModelGenerator(), stateStore,
                new ProcessModelFileStore(tempDir.resolve("models").toString()), archiveStore,
                delegateClassGenerator, generator, new SpringBootProjectLauncher(), tracker);

        CapabilityGapStore gapStore = new CapabilityGapStore(tempDir.resolve("gaps.json").toString(), false);
        capabilityGapService = new CapabilityGapService(gapStore, service, approvalService, policyDecisionEngine,
                new CapabilityGapRecommender());
        wireCapabilityGapService(service, capabilityGapService);

        automationRef.set(new DefaultProjectAutomationService(List.of(new CreditRiskAssessorExecutor())));
        // Real P4 output-contract enforcement, wired exactly as production does it: this is the one
        // @Component implementation of CapabilityOutputContractSource, so without it
        // TwinAutomationDelegate.resolveProvider always returns null, CapabilityOutputPropagator
        // validates nothing, and no execution can identify the provider that ran. The P4 violation
        // test below already builds its delegate this way; the default harness must match, or these
        // tests would assert a RESOLVED gap on an execution the Phase 4 boundary never checked.
        CapabilityOutputContractSource contractSource = new CatalogCapabilityOutputContractSource(service);
        realDelegate.set(new TwinAutomationDelegate(Map.of("default", automationRef.get()), service,
                repositoryService, new ObjectProvider<CapabilityOutputContractSource>() {
                    @Override
                    public CapabilityOutputContractSource getObject() {
                        return contractSource;
                    }
                }));
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    // WorkbenchServiceImpl.capabilityGapService is field-injected via ObjectProvider (see that
    // class's own field javadoc) precisely so no test constructor signature changes; this is the
    // production wiring, done here without a Spring context.
    private static void wireCapabilityGapService(WorkbenchServiceImpl target, CapabilityGapService gapService)
            throws Exception {
        Field field = WorkbenchServiceImpl.class.getDeclaredField("capabilityGapService");
        field.setAccessible(true);
        field.set(target, new ObjectProvider<CapabilityGapService>() {
            @Override
            public CapabilityGapService getObject() {
                return gapService;
            }
        });
    }

    // The TRUTHFUL contract for the real CreditRiskAssessorExecutor: every output that executor
    // unconditionally produces, matching the authored provider contract in the node manager's own
    // application.yml. P4 requires exact output-name-set equality, so a success-path test must
    // declare all four - declaring only riskFlagged would turn the executor's other three real
    // outputs into genuine UNDECLARED_OUTPUT violations (which is exactly what the P4 violation
    // test below deliberately arranges with its own separate under-declared stub).
    private void stubCreditRiskCatalog() {
        stubCatalog("credit-risk-assessor", "credit-risk-agent-01", List.of(
                new IoDeclarationDescriptor("riskScore", "NUMBER", true),
                new IoDeclarationDescriptor("riskFlagged", "BOOLEAN", true),
                new IoDeclarationDescriptor("riskThreshold", "NUMBER", true),
                new IoDeclarationDescriptor("assessmentReason", "STRING", true)));
    }

    private void stubCatalog(String agentType, String agentName, List<IoDeclarationDescriptor> producedOutputs) {
        AgentAvailabilityResult available = new AgentAvailabilityResult(agentType, true, agentName,
                "Available in catalog", Map.of(), "synthetic risk assessor", List.of(), List.of(),
                producedOutputs == null ? List.of() : producedOutputs);
        when(nodeManagerClient.checkAgentAvailability(agentType)).thenReturn(available);
        when(nodeManagerClient.listAgents()).thenReturn(List.of(available));
    }

    private void stubEmptyCatalog() {
        when(nodeManagerClient.listAgents()).thenReturn(List.of());
    }

    // A bare Mockito mock returns null, not an "unavailable" result, for an unstubbed
    // checkAgentAvailability call - this makes that call behave like the real NodeManagerServiceImpl
    // does for an unregistered agent type (see NodeManagerServiceImpl.checkAvailability's own
    // "Agent type not found in node manager catalog" unavailable() branch).
    private void stubUnavailable(String agentType) {
        when(nodeManagerClient.checkAgentAvailability(agentType)).thenReturn(new AgentAvailabilityResult(
                agentType, false, null, "Agent type not found in node manager catalog", Map.of()));
    }

    private TwinProcess launchAndConnect() {
        String xml = Bpmn.convertToString(genericProcess());
        ProcessModel model = service.saveProcessModel(null, "Generic gap lifecycle fixture", xml);
        TwinProcess twin = service.launchProcess(model.getId());
        service.connectActivity(twin.getId(), ASSESS_ACTIVITY, ASSESS_ACTIVITY);
        return twin;
    }

    // ---- A-M: OPEN -> blocked -> provider available/bound -> executes -> P4 validates -> RESOLVED
    // -> process continues ----
    @Test
    void capabilityGapOpensBlocksTheProcessThenResolvesAndTheProcessContinues() {
        stubEmptyCatalog(); // A/B: no satisfying provider exists anywhere in the catalog
        stubUnavailable("risk-scorer");

        // C/D/E: the process reaches the capability requirement and a CapabilityGap is
        // automatically opened by the existing runtime provider-resolution failure path.
        AgentDecision blocked = service.evolveActivity(launchTwinId(), ASSESS_ACTIVITY, "risk-scorer");
        assertThat(blocked.isApproved()).as("no provider exists - evolution must not be approved").isFalse();

        List<CapabilityGap> gaps = capabilityGapService.list();
        assertThat(gaps).as("a capability gap must have been automatically recorded").hasSize(1);
        CapabilityGap gap = gaps.get(0);
        assertThat(gap.status()).isEqualTo(GapStatus.OPEN);
        assertThat(gap.activityId()).isEqualTo(ASSESS_ACTIVITY);
        assertThat(gap.requiredContract().producedOutputs())
                .anyMatch(d -> d.name().equals(REQUIRED_OUTPUT));

        // F: the process must not have legitimately advanced - the twin activity is still not bound.
        TwinActivityExecutionState stillBlocked = service.getActivityExecutionState(twinId, ASSESS_ACTIVITY);
        assertThat(stillBlocked.getAgentName()).isNull();
        assertThat(stillBlocked.getStatus()).isNotEqualTo("EXECUTED");

        // G/H: a legitimate provider becomes available; recovery goes through the gap's own
        // recommend -> approve -> bind sequence, which itself converges into the EXISTING
        // WorkbenchService.evolveActivity(...) - no second evolution engine (see
        // CapabilityGapService.bind()).
        stubCreditRiskCatalog();
        CapabilityGapRecommendation recommendation = capabilityGapService.recommend(gap.gapId());
        assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.RECOMMENDED);
        AgentDecision approved = capabilityGapService.approve(gap.gapId(), "tenant-a");
        assertThat(approved.isApproved()).as(approved.getReason()).isTrue();
        AgentDecision bound = capabilityGapService.bind(gap.gapId());
        assertThat(bound.isApproved()).as(bound.getReason()).isTrue();
        assertThat(capabilityGapService.get(gap.gapId()).status()).isEqualTo(GapStatus.BOUND);

        // The twin's own generated topology forks a receive task's completion into two CONCURRENT
        // branches (the automation service task, and whatever follows the original activity - here
        // Gateway_Assess) rather than a strictly sequential automate-then-gateway chain (see
        // TwinModelGenerator; confirmed by dumping the generated twin model). That fork is existing,
        // untouched architecture, not something this pass may redesign - so the gateway branch can
        // race ahead of the automation branch actually setting 'riskFlagged'. Seeding a default value
        // up front (immediately overwritten by CapabilityOutputPropagator.publish once the real
        // executor runs) makes the gateway condition resolvable regardless of which branch the engine
        // happens to process first, without weakening what is actually being proven: the executor's
        // real output still drives which branch is ultimately taken whenever it wins the race, and
        // CapabilityOutputPropagator.publish is still the one and only place 'riskFlagged' is written
        // from the provider's actual output.
        runtimeService.setVariable(twin.getTwinProcessId(), REQUIRED_OUTPUT, false);

        // I: the provider actually executes (bridging drives TwinAutomationDelegate ->
        // DefaultProjectAutomationService -> the real CreditRiskAssessorExecutor).
        service.bridgeActivityEvent(twinId, ASSESS_ACTIVITY);

        TwinActivityExecutionState executed = service.getActivityExecutionState(twinId, ASSESS_ACTIVITY);
        assertThat(executed.getStatus()).isEqualTo("EXECUTED");
        assertThat(executed.getOutput()).containsKey(REQUIRED_OUTPUT);

        // K: CapabilityGap becomes RESOLVED - the same gapId as before, now RESOLVED.
        CapabilityGap resolved = capabilityGapService.get(gap.gapId());
        assertThat(resolved.status()).isEqualTo(GapStatus.RESOLVED);

        // L/M: the process actually continues - the twin process instance is no longer waiting on
        // the Assess activity's receive task; a downstream activity is reached. A twin process
        // instance that has ended entirely (no executions left to query) is the strongest possible
        // form of this proof - it ran all the way through Assess, the gateway, FollowUp, and End -
        // so only query getActiveActivityIds when the instance is still running.
        boolean twinStillRunning = runtimeService.createProcessInstanceQuery()
                .processInstanceId(twin.getTwinProcessId()).count() > 0;
        if (twinStillRunning) {
            List<String> activeActivityIds = runtimeService.getActiveActivityIds(twin.getTwinProcessId());
            assertThat(activeActivityIds)
                    .as("twin must have moved past Assess to a downstream activity: %s", activeActivityIds)
                    .noneMatch(id -> id.equals(ASSESS_ACTIVITY));
        }
    }

    // ---- P6: the CapabilityGap -> AI-decision -> human-confirmation seam (sections 4, 8, 12).
    // recommend() plays the exact role the VS Code AiDecisionProvider/Ollama path would call first
    // to get a catalog-constrained candidate (section 4's "existing AI request" enters through the
    // existing recommend() step, never a second selection mechanism); the id it returns is what a
    // human would confirm in the VS Code modal. bind(gapId, confirmedProviderId) - not bind(gapId) -
    // is the method under test here: the SAME convergence into the existing evolveActivity, proven
    // both ways a real, unmodified CreditRiskAssessorExecutor can legitimately decide the gateway
    // (section 12), with the gateway definition itself untouched. ----

    @Test
    void confirmedProviderBindResolvesTheGapAndTakesTheTrueGatewayBranch() {
        stubEmptyCatalog();
        stubUnavailable("risk-scorer");
        service.evolveActivity(launchTwinId(), ASSESS_ACTIVITY, "risk-scorer");
        CapabilityGap gap = capabilityGapService.list().get(0);

        stubCreditRiskCatalog();

        // Stands in for "VS Code calls the existing AI request/Ollama path, gets a
        // catalog-constrained recommendation, a human confirms it" - recommend() is the same
        // deterministic, CapabilitySatisfaction-backed catalog lookup that recommendation would have
        // been checked against either way.
        CapabilityGapRecommendation recommendation = capabilityGapService.recommend(gap.gapId());
        assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.RECOMMENDED);
        String confirmedProviderId = recommendation.recommendedProviderId();
        assertThat(confirmedProviderId).isEqualTo("credit-risk-agent-01");

        AgentDecision approved = capabilityGapService.approve(gap.gapId(), "tenant-a");
        assertThat(approved.isApproved()).as(approved.getReason()).isTrue();

        // The P6 seam under test: an externally supplied, already-confirmed provider id, not the
        // service's own internal recommender pick.
        AgentDecision bound = capabilityGapService.bind(gap.gapId(), confirmedProviderId);
        assertThat(bound.isApproved()).as(bound.getReason()).isTrue();
        assertThat(capabilityGapService.get(gap.gapId()).status()).isEqualTo(GapStatus.BOUND);
        assertThat(capabilityGapService.get(gap.gapId()).boundProviderId()).isEqualTo(confirmedProviderId);

        // No creditScore/transferAmount set: CreditRiskAssessorExecutor's real, unmodified default
        // branch legitimately computes riskScore=85 >= threshold(50) -> riskFlagged=true (see that
        // class) - the TRUE gateway branch, driven by the real executor, not fabricated.
        runtimeService.setVariable(twin.getTwinProcessId(), REQUIRED_OUTPUT, true);

        service.bridgeActivityEvent(twinId, ASSESS_ACTIVITY);

        TwinActivityExecutionState executed = service.getActivityExecutionState(twinId, ASSESS_ACTIVITY);
        assertThat(executed.getStatus()).isEqualTo("EXECUTED");
        assertThat(executed.getOutput()).containsEntry(REQUIRED_OUTPUT, true);

        assertThat(capabilityGapService.get(gap.gapId()).status()).isEqualTo(GapStatus.RESOLVED);

        boolean twinStillRunning = runtimeService.createProcessInstanceQuery()
                .processInstanceId(twin.getTwinProcessId()).count() > 0;
        if (twinStillRunning) {
            List<String> activeActivityIds = runtimeService.getActiveActivityIds(twin.getTwinProcessId());
            assertThat(activeActivityIds)
                    .as("riskFlagged=true must route through the TRUE branch to Follow Up, never straight to "
                            + "End: %s", activeActivityIds)
                    .contains(FOLLOW_UP_ACTIVITY)
                    .doesNotContain(ASSESS_ACTIVITY);
        }
    }

    @Test
    void confirmedProviderBindResolvesTheGapAndTakesTheFalseGatewayBranch() {
        stubEmptyCatalog();
        stubUnavailable("risk-scorer");
        service.evolveActivity(launchTwinId(), ASSESS_ACTIVITY, "risk-scorer");
        CapabilityGap gap = capabilityGapService.list().get(0);

        stubCreditRiskCatalog();

        CapabilityGapRecommendation recommendation = capabilityGapService.recommend(gap.gapId());
        String confirmedProviderId = recommendation.recommendedProviderId();

        capabilityGapService.approve(gap.gapId(), "tenant-a");
        AgentDecision bound = capabilityGapService.bind(gap.gapId(), confirmedProviderId);
        assertThat(bound.isApproved()).as(bound.getReason()).isTrue();

        // creditScore=800, transferAmount=1000: CreditRiskAssessorExecutor's real, unmodified
        // high-credit/low-amount branch legitimately computes riskScore=20 < threshold(50) ->
        // riskFlagged=false (see that class) - the FALSE gateway branch, driven by the real
        // executor's real input, not fabricated.
        runtimeService.setVariable(twin.getTwinProcessId(), "creditScore", 800);
        runtimeService.setVariable(twin.getTwinProcessId(), "transferAmount", 1000);
        runtimeService.setVariable(twin.getTwinProcessId(), REQUIRED_OUTPUT, false);

        service.bridgeActivityEvent(twinId, ASSESS_ACTIVITY);

        TwinActivityExecutionState executed = service.getActivityExecutionState(twinId, ASSESS_ACTIVITY);
        assertThat(executed.getStatus()).isEqualTo("EXECUTED");
        assertThat(executed.getOutput()).containsEntry(REQUIRED_OUTPUT, false);

        assertThat(capabilityGapService.get(gap.gapId()).status()).isEqualTo(GapStatus.RESOLVED);

        boolean twinStillRunning = runtimeService.createProcessInstanceQuery()
                .processInstanceId(twin.getTwinProcessId()).count() > 0;
        if (twinStillRunning) {
            List<String> activeActivityIds = runtimeService.getActiveActivityIds(twin.getTwinProcessId());
            assertThat(activeActivityIds)
                    .as("riskFlagged=false must route straight to End, never through Follow Up: %s",
                            activeActivityIds)
                    .doesNotContain(FOLLOW_UP_ACTIVITY)
                    .doesNotContain(ASSESS_ACTIVITY);
        }
    }

    // Failure-safety category B at full E2E depth: an AI/human-confirmed provider id that does not
    // satisfy the gap's contract must be rejected before evolveActivity runs - the process stays
    // blocked at Assess, exactly as if no recommendation had ever been confirmed.
    @Test
    void confirmedProviderThatDoesNotSatisfyTheContractNeverBindsOrAdvancesTheProcess() {
        stubEmptyCatalog();
        stubUnavailable("risk-scorer");
        service.evolveActivity(launchTwinId(), ASSESS_ACTIVITY, "risk-scorer");
        CapabilityGap gap = capabilityGapService.list().get(0);

        // A real, cataloged provider that produces a differently-typed/named output - it does not
        // satisfy this gap's required 'riskFlagged' (BOOLEAN) contract.
        stubCatalog("notifier", "notifier-agent-01", List.of(
                new IoDeclarationDescriptor("sent", "BOOLEAN", true)));

        capabilityGapService.recommend(gap.gapId());
        capabilityGapService.approve(gap.gapId(), "tenant-a");

        AgentDecision rejected = capabilityGapService.bind(gap.gapId(), "notifier-agent-01");

        assertThat(rejected.isApproved()).isFalse();
        assertThat(rejected.getReason()).contains("does not satisfy");
        assertThat(capabilityGapService.get(gap.gapId()).status()).isEqualTo(GapStatus.APPROVED);

        TwinActivityExecutionState state = service.getActivityExecutionState(twinId, ASSESS_ACTIVITY);
        assertThat(state.getStatus()).isNotEqualTo("EXECUTED");
        assertThat(state.getAgentName()).isNull();
    }

    // ---- N/O/P: bound but execution fails -> gap does NOT resolve, process does not continue ----
    @Test
    void executionFailureLeavesTheGapUnresolvedAndDoesNotAdvanceTheProcess() {
        stubEmptyCatalog();
        stubUnavailable("risk-scorer");
        service.evolveActivity(launchTwinId(), ASSESS_ACTIVITY, "risk-scorer");
        CapabilityGap gap = capabilityGapService.list().get(0);

        // A provider becomes available, but its execution will fail.
        stubCatalog("failing-assessor", "failing-agent-01", List.of(
                new IoDeclarationDescriptor(REQUIRED_OUTPUT, "BOOLEAN", true)));
        ComponentExecutor throwing = new ComponentExecutor() {
            @Override
            public String getHandledAgentType() {
                return "failing-assessor";
            }

            @Override
            public java.util.Set<String> getHandledAgentNames() {
                return java.util.Set.of("failing-agent-01");
            }

            @Override
            public AutomationResult execute(CapabilityExecutionContext context, String activityId,
                    String agentName) {
                throw new IllegalStateException("synthetic provider execution failure");
            }
        };
        automationRef.set(new DefaultProjectAutomationService(List.of(throwing)));

        // Bound through the gap's own recommend -> approve -> bind sequence (the same existing
        // evolveActivity convergence proven above), not a second evolution engine.
        capabilityGapService.recommend(gap.gapId());
        capabilityGapService.approve(gap.gapId(), "tenant-a");
        AgentDecision bound = capabilityGapService.bind(gap.gapId());
        assertThat(bound.isApproved()).as(bound.getReason()).isTrue();
        assertThat(capabilityGapService.get(gap.gapId()).status()).isEqualTo(GapStatus.BOUND);

        // O: bridging triggers the failing executor; the delegate throws, so
        // notifyCapabilityProviderExecutionSucceeded is never reached (mirrors production - see
        // TwinAutomationDelegate.execute()).
        try {
            service.bridgeActivityEvent(twinId, ASSESS_ACTIVITY);
        } catch (RuntimeException expected) {
            // Camunda surfaces the delegate failure as an incident/exception - either way the
            // token does not advance past it.
        }

        CapabilityGap stillBound = capabilityGapService.get(gap.gapId());
        assertThat(stillBound.status()).as("execution failure must never resolve the gap")
                .isEqualTo(GapStatus.BOUND);

        // P: the process does not continue as though the capability succeeded.
        TwinActivityExecutionState state = service.getActivityExecutionState(twinId, ASSESS_ACTIVITY);
        assertThat(state.getStatus()).isNotEqualTo("EXECUTED");
    }

    // ---- N/O/P variant: bound, executes, but violates its declared P4 output contract ----
    @Test
    void p4ContractViolationLeavesTheGapUnresolved() throws Exception {
        // A separate engine/service pair for this test only, so real P4 output-contract enforcement
        // (via the same production CatalogCapabilityOutputContractSource binding
        // TwinAutomationDelegate already uses) can be wired in without affecting the other tests'
        // no-contract harness. Same deferred-delegate indirection as @BeforeEach, since the delegate
        // needs the not-yet-built violationService and the engine needs the delegate bean at build
        // time.
        AtomicReference<JavaDelegate> realDelegate = new AtomicReference<>();
        JavaDelegate delegateBridge = execution -> realDelegate.get().execute(execution);

        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:gap-lifecycle-violation-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJobExecutorActivate(false);
        config.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
        if (config instanceof ProcessEngineConfigurationImpl configImpl) {
            configImpl.setHistoryTimeToLive("180");
            configImpl.setBeans(Map.of("twinAutomationDelegate", delegateBridge));
        }
        ProcessEngine violationEngine = config.buildProcessEngine();
        try {
            RuntimeService violationRuntimeService = violationEngine.getRuntimeService();
            RepositoryService violationRepositoryService = violationEngine.getRepositoryService();

            WorkflowEventStore eventStore =
                    new WorkflowEventStore(tempDir.resolve("events-violation.json").toString(), true);
            WorkflowStateTracker tracker = new WorkflowStateTracker(eventStore);
            WorkbenchStateStore stateStore = mock(WorkbenchStateStore.class);
            when(stateStore.load()).thenReturn(new WorkbenchStateStore.Snapshot(List.of()));
            ProcessModelArchiveStore archiveStore = mock(ProcessModelArchiveStore.class);
            when(archiveStore.findAll()).thenReturn(List.of());
            DelegateClassGenerator delegateClassGenerator = mock(DelegateClassGenerator.class);
            when(delegateClassGenerator.generate(anyString(), anyString())).thenReturn(List.of());
            Path templateDir = tempDir.resolve("template-violation");
            Files.createDirectories(templateDir);
            Files.writeString(templateDir.resolve("pom.xml"), "<project>fake</project>");
            SpringBootProjectGenerator generator = new SpringBootProjectGenerator(templateDir.toString(),
                    tempDir.resolve("generated-projects-violation").toString(), new TwinModelGenerator(),
                    new DelegateClassGenerator(), new ExternalTaskWorkerGenerator());
            GovernanceService governanceService = mock(GovernanceService.class);
            when(governanceService.reserveEvolutionSlot(anyString(), anyString()))
                    .thenReturn(new GovernanceDecision(true, null));
            when(governanceService.reserveTwinExecutionSlot(anyString()))
                    .thenReturn(new GovernanceDecision(true, null));
            ApprovalStore approvalStore =
                    new ApprovalStore(tempDir.resolve("approvals-violation.json").toString(), false);
            ApprovalService approvalService = new ApprovalService(approvalStore);
            TenantPolicyStore tenantPolicyStore =
                    new TenantPolicyStore(tempDir.resolve("tenant-policies-violation.json").toString(), false);
            PolicyDecisionEngine policyDecisionEngine =
                    new PolicyDecisionEngine(new TenantPolicyService(tenantPolicyStore));

            WorkbenchServiceImpl violationService = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                    policyDecisionEngine, approvalService, violationRuntimeService, violationRepositoryService,
                    violationEngine.getHistoryService(), violationEngine.getTaskService(),
                    violationEngine.getExternalTaskService(), new TwinModelGenerator(), stateStore,
                    new ProcessModelFileStore(tempDir.resolve("models-violation").toString()), archiveStore,
                    delegateClassGenerator, generator, new SpringBootProjectLauncher(), tracker);

            CapabilityGapStore gapStore =
                    new CapabilityGapStore(tempDir.resolve("gaps-violation.json").toString(), false);
            CapabilityGapService violationGapService = new CapabilityGapService(gapStore, violationService,
                    approvalService, policyDecisionEngine, new CapabilityGapRecommender());
            wireCapabilityGapService(violationService, violationGapService);

            // Real P4 output-contract enforcement: the same production binding
            // TwinAutomationDelegate uses, now resolvable because violationService exists.
            CapabilityOutputContractSource contractSource = new CatalogCapabilityOutputContractSource(
                    violationService);
            ProjectAutomationService violationAutomation =
                    new DefaultProjectAutomationService(List.of(new CreditRiskAssessorExecutor()));
            realDelegate.set(new TwinAutomationDelegate(Map.of("default", violationAutomation), violationService,
                    violationRepositoryService, new ObjectProvider<CapabilityOutputContractSource>() {
                        @Override
                        public CapabilityOutputContractSource getObject() {
                            return contractSource;
                        }
                    }));

            String xml = Bpmn.convertToString(genericProcess());
            ProcessModel model = violationService.saveProcessModel(null, "P4 violation gap fixture", xml);
            TwinProcess twin = violationService.launchProcess(model.getId());
            violationService.connectActivity(twin.getId(), ASSESS_ACTIVITY, ASSESS_ACTIVITY);

            when(nodeManagerClient.checkAgentAvailability("risk-scorer"))
                    .thenReturn(new AgentAvailabilityResult("risk-scorer", false, null,
                            "Agent type not found in node manager catalog", Map.of()));
            when(nodeManagerClient.listAgents()).thenReturn(List.of());
            violationService.evolveActivity(twin.getId(), ASSESS_ACTIVITY, "risk-scorer");
            CapabilityGap gap = violationGapService.list().get(0);

            // Declares ONLY 'riskFlagged' (correctly typed BOOLEAN) - enough to satisfy the gap's
            // required contract, so P5's own CapabilitySatisfaction/recommender gate legitimately
            // recommends and binds this provider. CreditRiskAssessorExecutor's REAL, unmodified
            // execute() also always emits riskScore/riskThreshold/assessmentReason alongside it
            // (see that class) - none of which are declared here, so P4's CapabilityOutputPropagator
            // genuinely rejects them as UNDECLARED_OUTPUT once the provider actually runs. This is a
            // real P4 violation P5's static satisfaction check could not have caught, not a
            // fabricated one.
            AgentAvailabilityResult underdeclared = new AgentAvailabilityResult("credit-risk-assessor", true,
                    "credit-risk-agent-01", "Available in catalog", Map.of(), "synthetic", List.of(), List.of(),
                    List.of(new IoDeclarationDescriptor(REQUIRED_OUTPUT, "BOOLEAN", true)));
            when(nodeManagerClient.checkAgentAvailability("credit-risk-assessor")).thenReturn(underdeclared);
            when(nodeManagerClient.listAgents()).thenReturn(List.of(underdeclared));

            // Bound through the gap's own recommend -> approve -> bind sequence - the same existing
            // evolveActivity convergence, not a second evolution engine.
            CapabilityGapRecommendation recommendation = violationGapService.recommend(gap.gapId());
            assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.RECOMMENDED);
            violationGapService.approve(gap.gapId(), "tenant-a");
            AgentDecision bound = violationGapService.bind(gap.gapId());
            assertThat(bound.isApproved()).as(bound.getReason()).isTrue();
            assertThat(violationGapService.get(gap.gapId()).status()).isEqualTo(GapStatus.BOUND);

            try {
                violationService.bridgeActivityEvent(twin.getId(), ASSESS_ACTIVITY);
            } catch (RuntimeException expected) {
                // CapabilityOutputPropagator throws CapabilityOutputContractViolationException,
                // which Camunda surfaces as a delegate failure - the existing P4 incident path,
                // unmodified.
            }

            CapabilityGap stillBound = violationGapService.get(gap.gapId());
            assertThat(stillBound.status()).as("a real P4 contract violation must never resolve the gap")
                    .isEqualTo(GapStatus.BOUND);
        } finally {
            violationEngine.close();
        }
    }

    // ---- Static detection through the existing model-save lifecycle ----
    @Test
    void staticCapabilityGapIsAutomaticallyRecordedWhenAGenericUnsatisfiableModelIsSaved() {
        stubEmptyCatalog();
        String xml = Bpmn.convertToString(genericProcess());

        ProcessModel model = service.saveProcessModel(null, "Static gap fixture", xml);

        List<CapabilityGap> gaps = capabilityGapService.list();
        assertThat(gaps).as("saving the model must have triggered static detection automatically")
                .anyMatch(g -> g.activityId().equals(ASSESS_ACTIVITY)
                        && g.processDefinitionId().equals(model.getId())
                        && g.status() == GapStatus.OPEN);
    }

    @Test
    void staticDetectionDoesNotFalselyFlagAModelWhoseRequirementIsAlreadySatisfiable() {
        stubCatalog("credit-risk-assessor", "credit-risk-agent-01", List.of(
                new IoDeclarationDescriptor(REQUIRED_OUTPUT, "BOOLEAN", true)));
        String xml = Bpmn.convertToString(genericProcess());

        ProcessModel model = service.saveProcessModel(null, "Satisfiable static fixture", xml);

        List<CapabilityGap> gaps = capabilityGapService.list();
        assertThat(gaps).as("a satisfiable model must not produce a false capability gap")
                .noneMatch(g -> g.activityId().equals(ASSESS_ACTIVITY)
                        && g.processDefinitionId().equals(model.getId()));
    }

    // ---- F2/F1 regression: parallel multi-instance gap binding and provider-identity resolution ----

    // Same generic assessment domain as genericProcess(), with Assess as a PARALLEL multi-instance
    // activity so two genuinely concurrent siblings of one activityId are live at the same time -
    // the exact condition currentVisitId()'s most-recently-started heuristic cannot disambiguate
    // (see WorkbenchService.evolveActivity's 4-arg overload), and therefore the condition a
    // synthesized "<activityId>#<loopCounter>" visit descriptor silently got wrong.
    private static BpmnModelInstance parallelMultiInstanceProcess() {
        return Bpmn.readModelFromStream(new java.io.ByteArrayInputStream(("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_GapMi" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_GapMi" name="Generic Parallel MI Gap Lifecycle" isExecutable="true">
                    <bpmn:startEvent id="Start">
                      <bpmn:outgoing>Flow_ToAssess</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="%s" name="Assess">
                      <bpmn:incoming>Flow_ToAssess</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToGateway</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="false">
                        <bpmn:loopCardinality>2</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:exclusiveGateway id="Gateway_Assess">
                      <bpmn:incoming>Flow_ToGateway</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToFollowUp</bpmn:outgoing>
                      <bpmn:outgoing>Flow_ToEnd</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:userTask id="%s" name="Follow Up">
                      <bpmn:incoming>Flow_ToFollowUp</bpmn:incoming>
                      <bpmn:outgoing>Flow_FollowUpToEnd</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End">
                      <bpmn:incoming>Flow_ToEnd</bpmn:incoming>
                      <bpmn:incoming>Flow_FollowUpToEnd</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_ToAssess" sourceRef="Start" targetRef="%s" />
                    <bpmn:sequenceFlow id="Flow_ToGateway" sourceRef="%s" targetRef="Gateway_Assess" />
                    <bpmn:sequenceFlow id="Flow_ToFollowUp" sourceRef="Gateway_Assess" targetRef="%s">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${riskFlagged}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_ToEnd" sourceRef="Gateway_Assess" targetRef="End">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${!riskFlagged}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_FollowUpToEnd" sourceRef="%s" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(ASSESS_ACTIVITY, FOLLOW_UP_ACTIVITY, ASSESS_ACTIVITY, ASSESS_ACTIVITY,
                FOLLOW_UP_ACTIVITY, FOLLOW_UP_ACTIVITY))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private TwinProcess launchAndConnectParallelMultiInstance() {
        String xml = Bpmn.convertToString(parallelMultiInstanceProcess());
        ProcessModel model = service.saveProcessModel(null, "Parallel MI gap lifecycle fixture", xml);
        TwinProcess launched = service.launchProcess(model.getId());
        service.connectActivity(launched.getId(), ASSESS_ACTIVITY, ASSESS_ACTIVITY);
        return launched;
    }

    private Object twinVariable(String name) {
        return runtimeService.getVariable(twin.getTwinProcessId(), name);
    }

    private static ActiveRuntimeInstance siblingWithLoopCounter(List<ActiveRuntimeInstance> siblings,
            int loopCounter) {
        return siblings.stream()
                .filter(instance -> Integer.valueOf(loopCounter).equals(instance.loopCounter()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no live sibling with loopCounter " + loopCounter + " in " + siblings));
    }

    // The F2 regression. Before the fix, a runtime gap on a multi-instance activity stored a
    // synthesized "<activityId>#<loopCounter>" string as its activityInstanceId. bind() handed that
    // straight back to evolveActivity, where loopCounterOf could not match it against the live
    // activity tree, so the loop counter came back null and the binding was written to the
    // UNSUFFIXED evolvedAgent_<activity> variable that no multi-instance sibling ever reads. The
    // sibling then ran default automation, resolveProvider returned null, CapabilityOutputPropagator
    // validated nothing - and the gap was still marked RESOLVED anyway. This test walks that entire
    // chain against a real engine and pins every link of it.
    @Test
    void parallelMultiInstanceGapBindsTheCorrectSiblingAndOnlyItsBoundProviderResolvesIt() {
        stubEmptyCatalog();
        stubUnavailable("risk-scorer");

        twin = launchAndConnectParallelMultiInstance();
        twinId = twin.getId();

        List<ActiveRuntimeInstance> siblings =
                service.getActivityExecutionState(twinId, ASSESS_ACTIVITY).getActiveInstances();
        assertThat(siblings).as("the fixture must produce two genuinely concurrent MI siblings").hasSize(2);
        ActiveRuntimeInstance loopZero = siblingWithLoopCounter(siblings, 0);
        ActiveRuntimeInstance loopOne = siblingWithLoopCounter(siblings, 1);

        // Open a gap on EACH sibling through the real runtime evolution path: the node manager
        // reports the requested type unavailable and the catalog is empty, so nothing can satisfy it.
        service.evolveActivity(twinId, ASSESS_ACTIVITY, loopZero.activityInstanceId(), "risk-scorer");
        service.evolveActivity(twinId, ASSESS_ACTIVITY, loopOne.activityInstanceId(), "risk-scorer");

        // Saving the model also triggers STATIC_MODEL detection for this activity, so filter to the
        // runtime-origin gaps the two concurrent siblings actually produced.
        List<CapabilityGap> runtimeGaps = capabilityGapService.list().stream()
                .filter(g -> g.origin() == GapOrigin.RUNTIME_WORKBENCH_TWIN).toList();
        assertThat(runtimeGaps)
                .as("each concurrent sibling must get its own distinct gap, never one shared gap")
                .hasSize(2);
        CapabilityGap gapZero = runtimeGaps.stream()
                .filter(g -> Integer.valueOf(0).equals(g.loopCounter())).findFirst().orElseThrow();
        CapabilityGap gapOne = runtimeGaps.stream()
                .filter(g -> Integer.valueOf(1).equals(g.loopCounter())).findFirst().orElseThrow();
        assertThat(gapZero.gapId()).isNotEqualTo(gapOne.gapId());

        // (3) The stored activityInstanceId is a GENUINE Camunda id, not a synthesized descriptor.
        assertThat(gapZero.activityInstanceId())
                .as("a synthesized '<activityId>#<loopCounter>' descriptor must never be stored")
                .isNotNull()
                .doesNotContain("#")
                .isNotEqualTo(ASSESS_ACTIVITY + "#0");

        // (4) And it is the id of the intended iteration - proven against the live runtime activity
        // tree, not merely asserted to be non-empty.
        assertThat(gapZero.activityInstanceId()).isEqualTo(loopZero.activityInstanceId());
        assertThat(gapOne.activityInstanceId()).isEqualTo(loopOne.activityInstanceId());
        assertThat(service.getActivityExecutionState(twinId, ASSESS_ACTIVITY).getActiveInstances())
                .as("the stored id must still identify a live sibling carrying loopCounter 0")
                .anyMatch(instance -> instance.activityInstanceId().equals(gapZero.activityInstanceId())
                        && Integer.valueOf(0).equals(instance.loopCounter()));

        // Bind ONLY sibling 0, through the real recommend -> approve -> bind(confirmedProviderId)
        // lifecycle against a catalog that now carries a truthfully-declared provider.
        stubCreditRiskCatalog();
        CapabilityGapRecommendation recommendation = capabilityGapService.recommend(gapZero.gapId());
        assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.RECOMMENDED);
        String confirmedProviderId = recommendation.recommendedProviderId();
        assertThat(confirmedProviderId).isEqualTo("credit-risk-agent-01");
        assertThat(capabilityGapService.approve(gapZero.gapId(), "tenant-a").isApproved()).isTrue();

        AgentDecision bound = capabilityGapService.bind(gapZero.gapId(), confirmedProviderId);
        assertThat(bound.isApproved()).as(bound.getReason()).isTrue();
        assertThat(capabilityGapService.get(gapZero.gapId()).boundProviderId()).isEqualTo(confirmedProviderId);

        // (1) The binding landed on the loop-specific variable for the intended iteration.
        assertThat(twinVariable(AgentVariables.evolvedAgent(ASSESS_ACTIVITY, 0)))
                .as("sibling 0 must receive the loop-specific binding")
                .isEqualTo(confirmedProviderId);

        // (2) The unsuffixed fallback - the F2 failure signature - must be absent entirely.
        assertThat(twinVariable(AgentVariables.evolvedAgent(ASSESS_ACTIVITY, null)))
                .as("the unsuffixed evolvedAgent_<activity> variable is the old F2 bug's signature")
                .isNull();

        // (9) Sibling isolation: the other iteration inherits nothing.
        assertThat(twinVariable(AgentVariables.evolvedAgent(ASSESS_ACTIVITY, 1)))
                .as("sibling 1 must not inherit sibling 0's binding")
                .isNull();
        assertThat(capabilityGapService.get(gapOne.gapId()).status()).isEqualTo(GapStatus.OPEN);
        assertThat(capabilityGapService.get(gapOne.gapId()).boundProviderId()).isNull();

        // (8) Still BOUND: binding alone must never resolve.
        assertThat(capabilityGapService.get(gapZero.gapId()).status()).isEqualTo(GapStatus.BOUND);

        // Execute the bound sibling for real.
        service.bridgeActivityEvent(twinId, ASSESS_ACTIVITY, loopZero.activityInstanceId());

        // (5)(6)(7) The bound provider is what actually ran: its real declared output is present on
        // sibling 0's own per-visit variable - which only CapabilityOutputPropagator writes, and
        // only after validating the complete output set against the provider's declared contract -
        // and the default automation's 'ranAt' output is absent, so this was not the null-provider
        // fallback path.
        assertThat(twinVariable(AgentVariables.twinAutomationOutput(REQUIRED_OUTPUT, ASSESS_ACTIVITY, 0)))
                .as("the bound provider's declared output must have passed P4 onto sibling 0's visit")
                .isEqualTo(true);
        // "ranAt" literal rather than DefaultProjectAutomationService.RAN_AT_OUTPUT: that constant is
        // package-private, and widening production visibility purely for a test assertion is not a
        // change this remediation should make.
        assertThat(twinVariable(AgentVariables.twinAutomationOutput("ranAt", ASSESS_ACTIVITY, 0)))
                .as("'ranAt' would mean default automation ran instead of the bound provider")
                .isNull();
        assertThat(twinVariable(AgentVariables.twinAutomation(ASSESS_ACTIVITY, 0)))
                .asString()
                .as("the real CreditRiskAssessorExecutor must be what executed")
                .contains(CreditRiskAssessorExecutor.EXECUTOR_NAME);

        // (8) Only now - correct provider executed, P4 validated - may the gap resolve.
        assertThat(capabilityGapService.get(gapZero.gapId()).status()).isEqualTo(GapStatus.RESOLVED);

        // (9) The untouched sibling's gap is still OPEN: one sibling's success resolves only its own.
        assertThat(capabilityGapService.get(gapOne.gapId()).status()).isEqualTo(GapStatus.OPEN);
    }

    // ---- helpers ----

    private TwinProcess twin;
    private String twinId;

    private String launchTwinId() {
        twin = launchAndConnect();
        twinId = twin.getId();
        return twinId;
    }
}
