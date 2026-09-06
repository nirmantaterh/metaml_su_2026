package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
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

import com.metaml.workbench.automation.CreditRiskAssessorExecutor;
import com.metaml.workbench.automation.DataEnricherExecutor;
import com.metaml.workbench.automation.DefaultProjectAutomationService;
import com.metaml.workbench.automation.NotifierExecutor;
import com.metaml.workbench.automation.ProjectAutomationService;
import com.metaml.workbench.automation.RecommenderExecutor;
import com.metaml.workbench.automation.ValidatorExecutor;
import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.delegate.TwinAutomationDelegate;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinActivityExecutionState;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.WorkflowEventStore;
import com.metaml.workbench.workflow.WorkflowStateTracker;

// Scope 6 P1 (post-completion hardening audit, gap 6 - "canonical E2E independent of RedCollar"):
// WorkbenchLifecycleAgainstRealRedCollarBpmnTest proves the real Workbench lifecycle (evolve ->
// bridge -> TwinAutomationDelegate -> DefaultProjectAutomationService -> real ComponentExecutor)
// against a genuine customer BPMN, but that BPMN is professor-supplied and not committed to this
// repo - that test SKIPS (never fails) whenever the file is absent, which is the common case in a
// fresh checkout or CI. There was consequently no test proving this exact chain that actually RUNS
// unconditionally and is provably free of RedCollar-specific assumptions.
//
// This test exercises the identical production sequence - saveProcessModel -> launchProcess ->
// connectActivity -> evolveActivity -> bridgeActivityEvent -> real ComponentExecutor -> the same
// getActivityExecutionState API both VS Code and the Workbench UI render - against a small BPMN
// authored inline for this test alone. Nothing here is RedCollar, WireTransfer, or Citibank; the
// activity/process names below exist only in this file. A pass is evidence the Scope 6 runtime
// contract holds for ANY compliant BPMN, not merely the one demo vehicle.
@Tag("slow")
class ScopeSixGenericLifecycleEndToEndTest {

    @TempDir
    Path tempDir;

    private static final String ACTIVITY_ID = "Activity_ReviewSubmission";

    // Two plain userTasks in sequence, no gateway - the minimal shape TwinModelGenerator pairs
    // each with a receive+service task on the twin side (see its own class comment). A second
    // downstream activity keeps both the original and the twin instance genuinely RUNNING after
    // the first activity's automation executes, matching every real MetaML process (which always
    // has more than one activity) rather than the degenerate single-activity-then-end case.
    // Deliberately generic: this exact structure is what any newly-authored MetaML process looks
    // like before a human integrates a component onto one of its activities.
    private static BpmnModelInstance genericProcess() {
        return Bpmn.readModelFromStream(new java.io.ByteArrayInputStream(("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_ScopeSixGeneric" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_ScopeSixGeneric" name="Scope 6 Generic Lifecycle" isExecutable="true">
                    <bpmn:startEvent id="Start">
                      <bpmn:outgoing>Flow_ToReview</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="%s" name="Review Submission">
                      <bpmn:incoming>Flow_ToReview</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToFollowUp</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:userTask id="Activity_FollowUp" name="Follow Up">
                      <bpmn:incoming>Flow_ToFollowUp</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToEnd</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End">
                      <bpmn:incoming>Flow_ToEnd</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_ToReview" sourceRef="Start" targetRef="%s" />
                    <bpmn:sequenceFlow id="Flow_ToFollowUp" sourceRef="%s" targetRef="Activity_FollowUp" />
                    <bpmn:sequenceFlow id="Flow_ToEnd" sourceRef="Activity_FollowUp" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(ACTIVITY_ID, ACTIVITY_ID, ACTIVITY_ID)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private ProcessEngine engine;
    private RuntimeService runtimeService;
    private NodeManagerClient nodeManagerClient;
    private WorkbenchServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        // The engine needs a twinAutomationDelegate bean, and the real delegate needs the
        // WorkbenchService that is itself built from the engine. Break the cycle with a holder
        // the engine can call through, populated once both sides exist (same technique as
        // WorkbenchLifecycleAgainstRealRedCollarBpmnTest).
        AtomicReference<JavaDelegate> realDelegate = new AtomicReference<>();
        JavaDelegate delegateBridge = execution -> realDelegate.get().execute(execution);

        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:scope6-generic-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJobExecutorActivate(false);
        // Mirrors production's Camunda Spring Boot starter default (HistoryLevelFull) - see
        // WorkbenchLifecycleAgainstRealRedCollarBpmnTest's own comment for why AUDIT (the
        // standalone engine default) would silently break alreadyEvolved()'s re-evolution guard.
        config.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
        if (config instanceof ProcessEngineConfigurationImpl configImpl) {
            configImpl.setHistoryTimeToLive("180");
            configImpl.setBeans(Map.of("twinAutomationDelegate", delegateBridge));
        }
        engine = config.buildProcessEngine();
        runtimeService = engine.getRuntimeService();
        RepositoryService repositoryService = engine.getRepositoryService();
        HistoryService historyService = engine.getHistoryService();
        TaskService taskService = engine.getTaskService();
        ExternalTaskService externalTaskService = engine.getExternalTaskService();

        nodeManagerClient = mock(NodeManagerClient.class);
        GovernanceService governanceService = mock(GovernanceService.class);
        when(governanceService.reserveEvolutionSlot(anyString(), anyString()))
                .thenReturn(new GovernanceDecision(true, null));
        when(governanceService.reserveTwinExecutionSlot(anyString()))
                .thenReturn(new GovernanceDecision(true, null));

        WorkflowEventStore eventStore = new WorkflowEventStore(tempDir.resolve("events.json").toString(), true);
        WorkflowStateTracker tracker = new WorkflowStateTracker(eventStore);

        WorkbenchStateStore stateStore = mock(WorkbenchStateStore.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        when(stateStore.load()).thenReturn(new WorkbenchStateStore.Snapshot(List.of()));
        when(approvalService.listAllApproved()).thenReturn(List.of());
        ProcessModelArchiveStore archiveStore = mock(ProcessModelArchiveStore.class);
        when(archiveStore.findAll()).thenReturn(List.of());
        DelegateClassGenerator delegateClassGenerator = mock(DelegateClassGenerator.class);
        when(delegateClassGenerator.generate(anyString(), anyString())).thenReturn(List.of());

        Path templateDir = tempDir.resolve("template");
        Files.createDirectories(templateDir);
        Files.writeString(templateDir.resolve("pom.xml"), "<project>fake</project>");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(templateDir.toString(),
                tempDir.resolve("generated-projects").toString(), new TwinModelGenerator(),
                new DelegateClassGenerator(), new com.metaml.workbench.codegen.ExternalTaskWorkerGenerator());

        service = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                mock(PolicyDecisionEngine.class), approvalService, runtimeService, repositoryService,
                historyService, taskService, externalTaskService,
                new TwinModelGenerator(), stateStore,
                new ProcessModelFileStore(tempDir.resolve("models").toString()), archiveStore,
                delegateClassGenerator, generator, new SpringBootProjectLauncher(), tracker);

        // REAL executors and REAL dispatcher - the production automation path, not a stub.
        ProjectAutomationService automation = new DefaultProjectAutomationService(List.of(
                new CreditRiskAssessorExecutor(), new ValidatorExecutor(), new DataEnricherExecutor(),
                new NotifierExecutor(), new RecommenderExecutor()));
        realDelegate.set(new TwinAutomationDelegate(Map.of("default", automation), service, repositoryService));
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    // Mirrors the Node Manager catalog contract: an agent TYPE resolves to that type's agent NAME.
    private void stubCatalog(String agentType, String agentName) {
        AgentAvailabilityResult available = new AgentAvailabilityResult();
        available.setAvailable(true);
        available.setAgentName(agentName);
        available.setReason("Available in catalog");
        when(nodeManagerClient.checkAgentAvailability(agentType)).thenReturn(available);
    }

    @Test
    void genericBpmnCarriesTheFullScopeSixChainToARealComponentExecutor() throws Exception {
        String xml = Bpmn.convertToString(genericProcess());

        // 1. Author + launch a Twin from a BPMN that is neither RedCollar nor any other named demo.
        ProcessModel model = service.saveProcessModel(null, "Scope 6 generic lifecycle fixture", xml);
        assertThat(model.getProcessDefinitionId()).isNotBlank();

        TwinProcess twin = service.launchProcess(model.getId());
        assertThat(twin.getOriginalProcessId()).isNotBlank();
        assertThat(twin.getTwinProcessId()).isNotBlank();

        // 2. Connect the activity a human (or the VS Code extension) selected.
        service.connectActivity(twin.getId(), ACTIVITY_ID, ACTIVITY_ID);

        // 3. Runtime identity discovery must see the activity live before anything is bound.
        TwinActivityExecutionState discovered = service.getActivityExecutionState(twin.getId(), ACTIVITY_ID);
        assertThat(discovered.getActiveInstances())
                .as("the connected activity must be an active runtime instance right after launch")
                .hasSize(1);
        assertThat(discovered.getStatus()).isEqualTo("NOT_STARTED");

        // 4. Bind the catalog-validated, governance-approved component (the step the VS Code
        //    extension's confirmed AI recommendation ultimately calls).
        stubCatalog("validator", "validator-agent-01");
        AgentDecision decision = service.evolveActivity(twin.getId(), ACTIVITY_ID, "validator");
        assertThat(decision.isApproved()).as("evolution must be approved: %s", decision.getReason()).isTrue();
        assertThat(decision.getAgentName()).isEqualTo("validator-agent-01");

        TwinActivityExecutionState bound = service.getActivityExecutionState(twin.getId(), ACTIVITY_ID);
        assertThat(bound.getAgentName()).isEqualTo("validator-agent-01");
        assertThat(bound.getStatus()).isEqualTo("BOUND");

        // 5. Bridge: TwinAutomationDelegate -> DefaultProjectAutomationService -> ValidatorExecutor.
        service.bridgeActivityEvent(twin.getId(), ACTIVITY_ID);

        // 6. Execution-state API (the same one both VS Code and the Workbench render) must reflect
        //    a real, fresh executor run - not an inferred/derived status.
        TwinActivityExecutionState executed = service.getActivityExecutionState(twin.getId(), ACTIVITY_ID);
        assertThat(executed.getStatus())
                .as("bound component must actually have executed; summary=%s", executed.getSummary())
                .isEqualTo("EXECUTED");
        assertThat(executed.getSummary()).contains("ValidatorExecutor");
        assertThat(executed.getOutput()).containsEntry("executor", "ValidatorExecutor");
        assertThat(executed.getSummary()).doesNotContain("CreditRiskAssessorExecutor");
    }

    // Same generic BPMN, a different catalog component: proves dispatch follows the bound
    // catalog identity generically rather than any fixed/default executor.
    @Test
    void aDifferentCatalogComponentDispatchesToItsOwnExecutorOnTheSameGenericActivity() throws Exception {
        String xml = Bpmn.convertToString(genericProcess());
        ProcessModel model = service.saveProcessModel(null, "Scope 6 generic lifecycle fixture (alt agent)", xml);
        TwinProcess twin = service.launchProcess(model.getId());
        service.connectActivity(twin.getId(), ACTIVITY_ID, ACTIVITY_ID);

        stubCatalog("credit-risk-assessor", "credit-risk-agent-01");
        AgentDecision decision = service.evolveActivity(twin.getId(), ACTIVITY_ID, "credit-risk-assessor");
        assertThat(decision.isApproved()).as(decision.getReason()).isTrue();
        assertThat(decision.getAgentName()).isEqualTo("credit-risk-agent-01");

        service.bridgeActivityEvent(twin.getId(), ACTIVITY_ID);

        TwinActivityExecutionState executed = service.getActivityExecutionState(twin.getId(), ACTIVITY_ID);
        assertThat(executed.getStatus()).isEqualTo("EXECUTED");
        assertThat(executed.getSummary()).contains("CreditRiskAssessorExecutor");
        assertThat(executed.getOutput()).containsEntry("executor", "CreditRiskAssessorExecutor");
        assertThat(executed.getSummary()).doesNotContain("ValidatorExecutor");
    }
}
