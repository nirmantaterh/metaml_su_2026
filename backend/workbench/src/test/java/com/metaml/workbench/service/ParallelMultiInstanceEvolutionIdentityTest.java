package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.model.ActiveRuntimeInstance;
import com.metaml.workbench.model.ActivityLink;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.AgentVariables;
import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.TwinActivityExecutionState;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.WorkflowEventStore;
import com.metaml.workbench.workflow.WorkflowStateTracker;

// Verifies that evolveActivity(twinId, activityId, activityInstanceId, agentType) binds
// the explicitly specified runtime sibling of a parallel multi-instance activity.
// Uses a real standalone Camunda engine with concurrent runtime ActivityInstances.
class ParallelMultiInstanceEvolutionIdentityTest {

    @TempDir
    Path tempDir;

    private static final String ACTIVITY_ID = "Task_MI";
    private static final String TWIN_ACTIVITY_ID = "Task_MI_twin";
    // Ordinary (non-multi-instance) sibling branch, deployed alongside Task_MI in the same
    // original process instance - verifies runtime discovery/evolve behavior is unchanged for the
    // common single-instance case.
    private static final String SINGLE_ACTIVITY_ID = "Task_Single";
    private static final String SINGLE_TWIN_ACTIVITY_ID = "Task_Single_twin";
    private static final String TWIN_ID = "twin-mi-identity-01";

    private ProcessEngine engine;
    private RuntimeService runtimeService;
    private HistoryService historyService;
    private RepositoryService repositoryService;
    private GovernanceService governanceService;
    private NodeManagerClient nodeManagerClient;
    private WorkbenchServiceImpl service;
    private TwinProcess twin;

    private String originalProcessInstanceId;
    private String twinProcessInstanceId;

    @BeforeEach
    void setUp() throws Exception {
        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:mi-identity-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJobExecutorActivate(false);
        if (config instanceof ProcessEngineConfigurationImpl configImpl) {
            configImpl.setBeans(Map.of("twinAutomationDelegate", (JavaDelegate) execution -> {}));
        }
        engine = config.buildProcessEngine();

        runtimeService = engine.getRuntimeService();
        historyService = engine.getHistoryService();
        repositoryService = engine.getRepositoryService();

        // Deploy and start the original process containing both multi-instance and single-instance tasks.
        String originalBpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_MIOriginal" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_mi_original" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:parallelGateway id="Split"><bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing><bpmn:outgoing>F3</bpmn:outgoing></bpmn:parallelGateway>
                    <bpmn:userTask id="%s" name="Parallel Review">
                      <bpmn:incoming>F2</bpmn:incoming>
                      <bpmn:outgoing>F4</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="false">
                        <bpmn:loopCardinality>2</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:userTask id="%s" name="Single Review">
                      <bpmn:incoming>F3</bpmn:incoming>
                      <bpmn:outgoing>F5</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End1"><bpmn:incoming>F4</bpmn:incoming></bpmn:endEvent>
                    <bpmn:endEvent id="End2"><bpmn:incoming>F5</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Split"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Split" targetRef="%s"/>
                    <bpmn:sequenceFlow id="F3" sourceRef="Split" targetRef="%s"/>
                    <bpmn:sequenceFlow id="F4" sourceRef="%s" targetRef="End1"/>
                    <bpmn:sequenceFlow id="F5" sourceRef="%s" targetRef="End2"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(ACTIVITY_ID, SINGLE_ACTIVITY_ID, ACTIVITY_ID, SINGLE_ACTIVITY_ID, ACTIVITY_ID,
                SINGLE_ACTIVITY_ID);
        repositoryService.createDeployment()
                .addModelInstance("process_mi_original.bpmn", readModel(originalBpmn))
                .deploy();
        ProcessInstance originalPi = runtimeService.startProcessInstanceByKey("process_mi_original");
        originalProcessInstanceId = originalPi.getId();

        // Deploy and start twin sink process to hold runtime variable state.
        String twinBpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_MITwinSink" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_mi_twin_sink" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="TStart"><bpmn:outgoing>TF1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="Sink"><bpmn:incoming>TF1</bpmn:incoming><bpmn:outgoing>TF2</bpmn:outgoing></bpmn:userTask>
                    <bpmn:endEvent id="TEnd"><bpmn:incoming>TF2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="TF1" sourceRef="TStart" targetRef="Sink"/>
                    <bpmn:sequenceFlow id="TF2" sourceRef="Sink" targetRef="TEnd"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        repositoryService.createDeployment()
                .addModelInstance("process_mi_twin_sink.bpmn", readModel(twinBpmn))
                .deploy();
        ProcessInstance twinPi = runtimeService.startProcessInstanceByKey("process_mi_twin_sink");
        twinProcessInstanceId = twinPi.getId();

        // Configure WorkbenchServiceImpl against real Camunda engine services.
        governanceService = mock(GovernanceService.class);
        nodeManagerClient = mock(NodeManagerClient.class);

        Path eventFile = tempDir.resolve("events.json");
        WorkflowEventStore eventStore = new WorkflowEventStore(eventFile.toString(), true);
        WorkflowStateTracker tracker = new WorkflowStateTracker(eventStore);
        invokeDeclared(tracker, WorkflowStateTracker.class, "restore");

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
                historyService, mock(TaskService.class), mock(ExternalTaskService.class),
                mock(TwinModelGenerator.class), stateStore,
                new ProcessModelFileStore(tempDir.resolve("models").toString()), archiveStore,
                delegateClassGenerator, generator, new SpringBootProjectLauncher(), tracker);

        // Register TwinProcess backed by the active process instances.
        twin = new TwinProcess();
        twin.setId(TWIN_ID);
        twin.setOriginalProcessId(originalProcessInstanceId);
        twin.setTwinProcessId(twinProcessInstanceId);
        twin.setStatus("RUNNING");
        twin.setEventLog(new CopyOnWriteArrayList<>());
        twin.setActivityLinks(new CopyOnWriteArrayList<>(List.of(
                new ActivityLink(ACTIVITY_ID, TWIN_ACTIVITY_ID),
                new ActivityLink(SINGLE_ACTIVITY_ID, SINGLE_TWIN_ACTIVITY_ID))));

        @SuppressWarnings("unchecked")
        Map<String, TwinProcess> twinProcesses = (Map<String, TwinProcess>) getField(service, "twinProcesses");
        twinProcesses.put(TWIN_ID, twin);

        when(governanceService.reserveEvolutionSlot(anyString(), anyString()))
                .thenReturn(new GovernanceDecision(true, null));
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void explicitActivityInstanceIdBindsOnlyTheRequestedSiblingRegardlessOfHeuristic() throws Exception {
        // Concurrent runtime siblings of the same activityId.
        ActivityInstance tree = runtimeService.getActivityInstance(originalProcessInstanceId);
        ActivityInstance[] visits = tree.getActivityInstances(ACTIVITY_ID);
        assertThat(visits).hasSize(2);

        String instanceIdA = visits[0].getId();
        String instanceIdB = visits[1].getId();
        Object loopCounterA = runtimeService.getVariableLocal(visits[0].getExecutionIds()[0], "loopCounter");
        Object loopCounterB = runtimeService.getVariableLocal(visits[1].getExecutionIds()[0], "loopCounter");
        assertThat(loopCounterA).isNotEqualTo(loopCounterB);

        // Empirically determine which sibling currentVisitId()'s "most-recently-started, still
        // active" heuristic would pick, using the same private method evolveOnce() itself calls
        // for the legacy 3-arg overload — not an assumption about engine timestamp ordering.
        String heuristicPick = invokeCurrentVisitId(twin, ACTIVITY_ID);
        assertThat(heuristicPick).isIn(instanceIdA, instanceIdB);

        // Deliberately target the OTHER sibling — the one the heuristic would NOT have picked —
        // so a passing assertion below is only possible if the explicit id, not the heuristic,
        // drove the binding.
        String targetInstanceId = heuristicPick.equals(instanceIdA) ? instanceIdB : instanceIdA;
        Object targetLoopCounter = targetInstanceId.equals(instanceIdA) ? loopCounterA : loopCounterB;
        Object otherLoopCounter = targetInstanceId.equals(instanceIdA) ? loopCounterB : loopCounterA;

        AgentAvailabilityResult available = new AgentAvailabilityResult();
        available.setAvailable(true);
        available.setAgentName("validator-agent-01");
        available.setReason("Available");
        when(nodeManagerClient.checkAgentAvailability("validator")).thenReturn(available);

        AgentDecision decision = service.evolveActivity(TWIN_ID, ACTIVITY_ID, targetInstanceId, "validator");

        assertThat(decision.isApproved()).isTrue();
        assertThat(decision.getAgentName()).isEqualTo("validator-agent-01");

        String targetVariable = AgentVariables.evolvedAgent(TWIN_ACTIVITY_ID, targetLoopCounter);
        String otherVariable = AgentVariables.evolvedAgent(TWIN_ACTIVITY_ID, otherLoopCounter);

        // Requirement 6: the selected sibling receives the evolution/binding.
        assertThat(runtimeService.getVariable(twinProcessInstanceId, targetVariable))
                .isEqualTo("validator-agent-01");
        // Requirement 7: the other sibling does NOT receive the evolution/binding.
        assertThat(runtimeService.getVariable(twinProcessInstanceId, otherVariable)).isNull();
    }

    @Test
    void nullActivityInstanceIdPreservesLegacyHeuristicBehavior() {
        ActivityInstance tree = runtimeService.getActivityInstance(originalProcessInstanceId);
        ActivityInstance[] visits = tree.getActivityInstances(ACTIVITY_ID);
        assertThat(visits).hasSize(2);

        AgentAvailabilityResult available = new AgentAvailabilityResult();
        available.setAvailable(true);
        available.setAgentName("validator-agent-01");
        available.setReason("Available");
        when(nodeManagerClient.checkAgentAvailability("validator")).thenReturn(available);

        // The legacy 3-arg overload must still work exactly as before: it resolves the visit via
        // currentVisitId()'s heuristic rather than requiring an explicit instance id.
        AgentDecision decision = service.evolveActivity(TWIN_ID, ACTIVITY_ID, "validator");

        assertThat(decision.isApproved()).isTrue();
        assertThat(decision.getAgentName()).isEqualTo("validator-agent-01");
    }

    @Test
    void discoveryExposesBothConcurrentSiblingsFromLiveRuntimeNotHistoricOrdering() {
        // Ground truth read directly off the live runtime tree - the same mechanism
        // loopCounterOf()/originalExecutionIdForVisit() already use.
        ActivityInstance tree = runtimeService.getActivityInstance(originalProcessInstanceId);
        ActivityInstance[] visits = tree.getActivityInstances(ACTIVITY_ID);
        assertThat(visits).hasSize(2);
        List<String> expectedIds = List.of(visits[0].getId(), visits[1].getId());

        TwinActivityExecutionState state = service.getActivityExecutionState(TWIN_ID, ACTIVITY_ID);

        assertThat(state.getActiveInstances()).hasSize(2);
        assertThat(state.getActiveInstances().stream().map(ActiveRuntimeInstance::activityInstanceId).toList())
                .containsExactlyInAnyOrderElementsOf(expectedIds);
        // Both distinct loopCounters are represented - confirms discovery reads each sibling's
        // own live execution-local variable, not a single shared/aggregated value.
        assertThat(state.getActiveInstances().stream().map(ActiveRuntimeInstance::loopCounter).toList())
                .containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void singleInstanceActivityExposesExactlyOneActiveInstanceAndEvolvesNormally() {
        // Single-instance compatibility: a non-multi-instance sibling branch of the running
        // process instance reports exactly one active instance, and 3-argument evolution functions normally.
        TwinActivityExecutionState state = service.getActivityExecutionState(TWIN_ID, SINGLE_ACTIVITY_ID);
        assertThat(state.getActiveInstances()).hasSize(1);
        assertThat(state.getActiveInstances().get(0).loopCounter()).isNull();

        AgentAvailabilityResult available = new AgentAvailabilityResult();
        available.setAvailable(true);
        available.setAgentName("validator-agent-01");
        available.setReason("Available");
        when(nodeManagerClient.checkAgentAvailability("validator")).thenReturn(available);

        AgentDecision decision = service.evolveActivity(TWIN_ID, SINGLE_ACTIVITY_ID, "validator");

        assertThat(decision.isApproved()).isTrue();
        assertThat(decision.getAgentName()).isEqualTo("validator-agent-01");
        assertThat(runtimeService.getVariable(twinProcessInstanceId,
                AgentVariables.evolvedAgent(SINGLE_TWIN_ACTIVITY_ID, null)))
                .isEqualTo("validator-agent-01");
    }

    // Integration hold scoping: a claim staked on one runtime sibling holds only that sibling.
    // The other concurrent sibling of the same activity definition remains eligible for autonomous bridging.
    // Exercised through bridgeActivityEvent's instance-qualified entry point.
    @Test
    void anIntegrationClaimOnOneSiblingDoesNotHoldItsConcurrentSibling() {
        ActivityInstance tree = runtimeService.getActivityInstance(originalProcessInstanceId);
        ActivityInstance[] visits = tree.getActivityInstances(ACTIVITY_ID);
        assertThat(visits).hasSize(2);
        String claimedInstanceId = visits[0].getId();
        String untouchedInstanceId = visits[1].getId();

        service.requestComponentIntegration(TWIN_ID, ACTIVITY_ID, claimedInstanceId);

        AgentAvailabilityResult available = new AgentAvailabilityResult();
        available.setAvailable(true);
        available.setAgentName("validator-agent-01");
        available.setReason("Available");
        when(nodeManagerClient.checkAgentAvailability("validator")).thenReturn(available);

        AgentDecision claimed = service.bridgeActivityEvent(TWIN_ID, ACTIVITY_ID, claimedInstanceId);
        AgentDecision untouched = service.bridgeActivityEvent(TWIN_ID, ACTIVITY_ID, untouchedInstanceId);

        assertThat(claimed.getReason())
                .as("the claimed sibling must be held for its integration decision")
                .isEqualTo("Activity is awaiting a component integration decision");
        assertThat(untouched.getReason())
                .as("the unrelated concurrent sibling must not be held by the other's claim")
                .isNotEqualTo("Activity is awaiting a component integration decision");
    }

    private String invokeCurrentVisitId(TwinProcess twin, String activityId) throws Exception {
        Method method = WorkbenchServiceImpl.class.getDeclaredMethod("currentVisitId", TwinProcess.class,
                String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, twin, activityId);
    }

    private static BpmnModelInstance readModel(String xml) {
        return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void invokeDeclared(Object target, Class<?> type, String methodName) {
        try {
            Method method = type.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
