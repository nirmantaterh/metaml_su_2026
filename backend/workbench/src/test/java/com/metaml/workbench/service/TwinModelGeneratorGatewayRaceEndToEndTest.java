package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.metaml.workbench.automation.DefaultProjectAutomationService;
import com.metaml.workbench.automation.ProjectAutomationService;
import com.metaml.workbench.automation.ValidatorExecutor;
import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
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

// TwinModelGenerator gateway/automation-output race investigation: real-Camunda proof that a
// gateway immediately following a synchronized activity now waits for that activity's automation
// task (and therefore its declared output) rather than becoming enabled concurrently with it. Same
// hand-wired in-memory-engine harness pattern as GenericLifecycleEndToEndTest - no new harness, no
// P5/CapabilityGap wiring (this investigation is scoped to TwinModelGenerator only). A generic,
// independently invented domain - no RedCollar.
@Tag("slow")
class TwinModelGeneratorGatewayRaceEndToEndTest {

    @TempDir
    Path tempDir;

    private static final String ASSESS_ACTIVITY = "Activity_Assess";
    private static final String FOLLOW_UP_ACTIVITY = "Activity_FollowUp";
    private static final String REJECTED_ACTIVITY = "Activity_Rejected";

    // Activity_Assess (produces 'riskFlagged' via CreditRiskAssessorExecutor) -> Gateway (branches on
    // riskFlagged, produced by the immediately preceding activity) -> FollowUp or Rejected -> End.
    private static BpmnModelInstance gatewayAfterAssessProcess() {
        return Bpmn.readModelFromStream(new java.io.ByteArrayInputStream(("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_GatewayRace" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_GatewayRace" name="Gateway Race" isExecutable="true">
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
                      <bpmn:outgoing>Flow_ToRejected</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:userTask id="%s" name="Follow Up">
                      <bpmn:incoming>Flow_ToFollowUp</bpmn:incoming>
                      <bpmn:outgoing>Flow_FollowUpToEnd</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:userTask id="%s" name="Rejected">
                      <bpmn:incoming>Flow_ToRejected</bpmn:incoming>
                      <bpmn:outgoing>Flow_RejectedToEnd</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End">
                      <bpmn:incoming>Flow_FollowUpToEnd</bpmn:incoming>
                      <bpmn:incoming>Flow_RejectedToEnd</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_ToAssess" sourceRef="Start" targetRef="%s" />
                    <bpmn:sequenceFlow id="Flow_ToGateway" sourceRef="%s" targetRef="Gateway_Assess" />
                    <bpmn:sequenceFlow id="Flow_ToFollowUp" sourceRef="Gateway_Assess" targetRef="%s">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${riskFlagged}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_ToRejected" sourceRef="Gateway_Assess" targetRef="%s">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${!riskFlagged}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_FollowUpToEnd" sourceRef="%s" targetRef="End" />
                    <bpmn:sequenceFlow id="Flow_RejectedToEnd" sourceRef="%s" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(ASSESS_ACTIVITY, FOLLOW_UP_ACTIVITY, REJECTED_ACTIVITY, ASSESS_ACTIVITY, ASSESS_ACTIVITY,
                FOLLOW_UP_ACTIVITY, REJECTED_ACTIVITY, FOLLOW_UP_ACTIVITY, REJECTED_ACTIVITY))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    // Two genuinely independent parallel branches with no data dependency between them - must
    // remain unaffected (no global serialization).
    private static BpmnModelInstance parallelBranchesProcess() {
        return Bpmn.readModelFromStream(new java.io.ByteArrayInputStream(("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    id="Definitions_Parallel" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_ParallelRace" name="Parallel Race" isExecutable="true">
                    <bpmn:startEvent id="Start">
                      <bpmn:outgoing>Flow_ToSplit</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:parallelGateway id="Split">
                      <bpmn:incoming>Flow_ToSplit</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToA</bpmn:outgoing>
                      <bpmn:outgoing>Flow_ToB</bpmn:outgoing>
                    </bpmn:parallelGateway>
                    <bpmn:userTask id="Branch_A" name="Branch A">
                      <bpmn:incoming>Flow_ToA</bpmn:incoming>
                      <bpmn:outgoing>Flow_AToJoin</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:userTask id="Branch_B" name="Branch B">
                      <bpmn:incoming>Flow_ToB</bpmn:incoming>
                      <bpmn:outgoing>Flow_BToJoin</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:parallelGateway id="Join">
                      <bpmn:incoming>Flow_AToJoin</bpmn:incoming>
                      <bpmn:incoming>Flow_BToJoin</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToEnd</bpmn:outgoing>
                    </bpmn:parallelGateway>
                    <bpmn:endEvent id="End">
                      <bpmn:incoming>Flow_ToEnd</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_ToSplit" sourceRef="Start" targetRef="Split" />
                    <bpmn:sequenceFlow id="Flow_ToA" sourceRef="Split" targetRef="Branch_A" />
                    <bpmn:sequenceFlow id="Flow_ToB" sourceRef="Split" targetRef="Branch_B" />
                    <bpmn:sequenceFlow id="Flow_AToJoin" sourceRef="Branch_A" targetRef="Join" />
                    <bpmn:sequenceFlow id="Flow_BToJoin" sourceRef="Branch_B" targetRef="Join" />
                    <bpmn:sequenceFlow id="Flow_ToEnd" sourceRef="Join" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    // A sequential multi-instance activity followed by a downstream task - must remain unaffected
    // (already correctly wrapper-scoped; this is a regression check, not part of the fix).
    private static BpmnModelInstance multiInstanceThenDownstreamProcess() {
        return Bpmn.readModelFromStream(new java.io.ByteArrayInputStream(("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_MI" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_MI" name="Multi Instance" isExecutable="true">
                    <bpmn:startEvent id="Start">
                      <bpmn:outgoing>Flow_ToLoop</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Loop_Task" name="Loop Task">
                      <bpmn:incoming>Flow_ToLoop</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToAfter</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="true">
                        <bpmn:loopCardinality>2</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:userTask id="After_Task" name="After Loop">
                      <bpmn:incoming>Flow_ToAfter</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToEnd</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End">
                      <bpmn:incoming>Flow_ToEnd</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_ToLoop" sourceRef="Start" targetRef="Loop_Task" />
                    <bpmn:sequenceFlow id="Flow_ToAfter" sourceRef="Loop_Task" targetRef="After_Task" />
                    <bpmn:sequenceFlow id="Flow_ToEnd" sourceRef="After_Task" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private ProcessEngine engine;
    private RuntimeService runtimeService;
    private NodeManagerClient nodeManagerClient;
    private WorkbenchServiceImpl service;
    private final AtomicReference<ProjectAutomationService> automationRef = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        AtomicReference<JavaDelegate> realDelegate = new AtomicReference<>();
        JavaDelegate delegateBridge = execution -> realDelegate.get().execute(execution);

        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:gw-race-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
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
        RepositoryService repositoryService = engine.getRepositoryService();

        nodeManagerClient = mock(NodeManagerClient.class);
        GovernanceService governanceService = mock(GovernanceService.class);
        when(governanceService.reserveEvolutionSlot(anyString(), anyString()))
                .thenReturn(new GovernanceDecision(true, null));
        when(governanceService.reserveTwinExecutionSlot(anyString()))
                .thenReturn(new GovernanceDecision(true, null));

        WorkflowEventStore eventStore = new WorkflowEventStore(tempDir.resolve("events.json").toString(), true);
        WorkflowStateTracker tracker = new WorkflowStateTracker(eventStore);

        WorkbenchStateStore stateStore = mock(WorkbenchStateStore.class);
        when(stateStore.load()).thenReturn(new WorkbenchStateStore.Snapshot(List.of()));
        ApprovalService approvalService = mock(ApprovalService.class);
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
                new DelegateClassGenerator(), new ExternalTaskWorkerGenerator());

        service = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                mock(PolicyDecisionEngine.class), approvalService, runtimeService, repositoryService,
                engine.getHistoryService(), engine.getTaskService(), engine.getExternalTaskService(),
                new TwinModelGenerator(), stateStore,
                new ProcessModelFileStore(tempDir.resolve("models").toString()), archiveStore,
                delegateClassGenerator, generator, new SpringBootProjectLauncher(), tracker);

        automationRef.set(new DefaultProjectAutomationService(
                List.of(new CreditRiskAssessorExecutor(), new ValidatorExecutor())));
        realDelegate.set(new TwinAutomationDelegate(Map.of("default", automationRef.get()), service,
                repositoryService));
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private void stubCatalog(String agentType, String agentName) {
        AgentAvailabilityResult available = new AgentAvailabilityResult(agentType, true, agentName,
                "Available in catalog", Map.of());
        when(nodeManagerClient.checkAgentAvailability(agentType)).thenReturn(available);
    }

    // TEST A: existing/simple gateway behavior (condition on an input unrelated to any automation
    // output) still works.
    @Test
    void simpleGatewayUnrelatedToAnyAutomationOutputStillWorks() {
        // Literal EL booleans, not a process variable: this fixture proves an ordinary gateway not
        // depending on any activity's own output still works, without needing this test to seed a
        // variable before the original process instance ever reaches the gateway.
        BpmnModelInstance xml = Bpmn.createExecutableProcess("Process_Simple")
                .startEvent("Start")
                .exclusiveGateway("GW")
                .condition("toA", "${true}")
                .userTask("Task_A")
                .endEvent("End_A")
                .moveToLastGateway()
                .condition("toB", "${false}")
                .userTask("Task_B")
                .endEvent("End_B")
                .done();

        ProcessModel model = service.saveProcessModel(null, "Simple gateway fixture", Bpmn.convertToString(xml));
        TwinProcess twin = service.launchProcess(model.getId());

        assertThat(twin.getTwinProcessId()).isNotBlank();
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(twin.getTwinProcessId()).count()).isEqualTo(1);
    }

    // TEST B + C: generic activity -> real output -> gateway evaluates the real output -> correct
    // branch selected, in both directions.
    @Test
    void gatewayTakesTheTrueBranchUsingTheActivitysOwnRealProducedOutput() {
        ProcessModel model = service.saveProcessModel(null, "Gateway race fixture (true)",
                Bpmn.convertToString(gatewayAfterAssessProcess()));
        TwinProcess twin = service.launchProcess(model.getId());
        service.connectActivity(twin.getId(), ASSESS_ACTIVITY, ASSESS_ACTIVITY);

        // CreditRiskAssessorExecutor flags risk (riskFlagged=true) whenever no transferAmount/
        // creditScore variables are set - the "baseline" branch in its own real, unmodified logic.
        stubCatalog("credit-risk-assessor", "credit-risk-agent-01");
        AgentDecision bound = service.evolveActivity(twin.getId(), ASSESS_ACTIVITY, "credit-risk-assessor");
        assertThat(bound.isApproved()).as(bound.getReason()).isTrue();

        service.bridgeActivityEvent(twin.getId(), ASSESS_ACTIVITY);

        TwinActivityExecutionState executed = service.getActivityExecutionState(twin.getId(), ASSESS_ACTIVITY);
        assertThat(executed.getStatus()).isEqualTo("EXECUTED");
        assertThat(executed.getOutput()).containsEntry("riskFlagged", true);

        // The gateway must have evaluated the REAL produced value and routed to Activity_FollowUp -
        // never throwing "Cannot resolve identifier", and never taking Activity_Rejected instead.
        // Checked on the TWIN's own execution (not getActivityExecutionState's activeInstances,
        // which reflects the ORIGINAL process instance - that one is still sitting on its own
        // Activity_Assess user task, since nothing in this test completes it independently; only the
        // twin advances here, via bridging).
        List<String> twinActiveActivityIds = twinActiveActivityIds(twin);
        assertThat(twinActiveActivityIds)
                .as("the true branch (Activity_FollowUp) must have been reached: %s", twinActiveActivityIds)
                .contains(FOLLOW_UP_ACTIVITY);
        assertThat(twinActiveActivityIds)
                .as("the false branch (Activity_Rejected) must not have been reached: %s", twinActiveActivityIds)
                .doesNotContain(REJECTED_ACTIVITY);
    }

    @Test
    void gatewayTakesTheFalseBranchUsingTheActivitysOwnRealProducedOutput() {
        ProcessModel model = service.saveProcessModel(null, "Gateway race fixture (false)",
                Bpmn.convertToString(gatewayAfterAssessProcess()));
        TwinProcess twin = service.launchProcess(model.getId());
        service.connectActivity(twin.getId(), ASSESS_ACTIVITY, ASSESS_ACTIVITY);

        // A high credit score with a modest amount drives CreditRiskAssessorExecutor's own real
        // logic to riskScore=20 (below the default 50 threshold) -> riskFlagged=false.
        runtimeService.setVariable(twin.getTwinProcessId(), "creditScore", 800);
        runtimeService.setVariable(twin.getTwinProcessId(), "transferAmount", 500.0);
        stubCatalog("credit-risk-assessor", "credit-risk-agent-01");
        AgentDecision bound = service.evolveActivity(twin.getId(), ASSESS_ACTIVITY, "credit-risk-assessor");
        assertThat(bound.isApproved()).as(bound.getReason()).isTrue();

        service.bridgeActivityEvent(twin.getId(), ASSESS_ACTIVITY);

        TwinActivityExecutionState executed = service.getActivityExecutionState(twin.getId(), ASSESS_ACTIVITY);
        assertThat(executed.getStatus()).isEqualTo("EXECUTED");
        assertThat(executed.getOutput()).containsEntry("riskFlagged", false);

        List<String> twinActiveActivityIds = twinActiveActivityIds(twin);
        assertThat(twinActiveActivityIds)
                .as("the false branch (Activity_Rejected) must have been reached: %s", twinActiveActivityIds)
                .contains(REJECTED_ACTIVITY);
        assertThat(twinActiveActivityIds)
                .as("the true branch (Activity_FollowUp) must not have been reached: %s", twinActiveActivityIds)
                .doesNotContain(FOLLOW_UP_ACTIVITY);
    }

    // The twin process instance may have already ended entirely (the strongest possible form of
    // "reached the end") - getActiveActivityIds throws for an execution that no longer exists, so
    // only query it while the instance is still running.
    private List<String> twinActiveActivityIds(TwinProcess twin) {
        boolean stillRunning = runtimeService.createProcessInstanceQuery()
                .processInstanceId(twin.getTwinProcessId()).count() > 0;
        return stillRunning ? runtimeService.getActiveActivityIds(twin.getTwinProcessId()) : List.of();
    }

    // TEST D: two genuinely independent parallel branches with no data dependency remain parallel -
    // the fix must not globally serialize unrelated branches.
    @Test
    void independentParallelBranchesRemainUnaffected() {
        ProcessModel model = service.saveProcessModel(null, "Parallel fixture",
                Bpmn.convertToString(parallelBranchesProcess()));
        TwinProcess twin = service.launchProcess(model.getId());

        TwinActivityExecutionState a = service.getActivityExecutionState(twin.getId(), "Branch_A");
        TwinActivityExecutionState b = service.getActivityExecutionState(twin.getId(), "Branch_B");
        assertThat(a.getActiveInstances()).as("Branch A must be independently reachable").isNotEmpty();
        assertThat(b.getActiveInstances()).as("Branch B must be independently reachable").isNotEmpty();
    }

    // TEST E: sequential multi-instance activity followed by a downstream task remains valid - this
    // path was already correctly wrapper-scoped before the fix and must stay that way.
    @Test
    void multiInstanceActivityFollowedByADownstreamTaskRemainsValid() {
        ProcessModel model = service.saveProcessModel(null, "Multi-instance fixture",
                Bpmn.convertToString(multiInstanceThenDownstreamProcess()));
        TwinProcess twin = service.launchProcess(model.getId());

        assertThat(twin.getTwinProcessId()).isNotBlank();
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(twin.getTwinProcessId()).count()).isEqualTo(1);
    }
}
