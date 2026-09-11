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

// Verifies that rebinding an activity ensures execution state reflects the active binding without stale outputs.
class StaleExecutionStateOnRebindTest {

    @TempDir
    Path tempDir;

    private static final String ACTIVITY_ID = "Task_Generic";
    private static final String TWIN_ACTIVITY_ID = "Task_Generic_twin";
    private static final String TWIN_ID = "twin-stale-rebind-01";

    private ProcessEngine engine;
    private RuntimeService runtimeService;
    private GovernanceService governanceService;
    private NodeManagerClient nodeManagerClient;
    private WorkbenchServiceImpl service;

    private String originalProcessInstanceId;
    private String twinProcessInstanceId;

    @BeforeEach
    void setUp() throws Exception {
        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:stale-rebind-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
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
        RepositoryService repositoryService = engine.getRepositoryService();
        HistoryService historyService = engine.getHistoryService();

        // Advance past first task so its visit is completed while process remains running.
        String originalBpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_StaleOriginal" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_stale_original" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="%s"><bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing></bpmn:userTask>
                    <bpmn:userTask id="Task_Later"><bpmn:incoming>F2</bpmn:incoming><bpmn:outgoing>F3</bpmn:outgoing></bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F3</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="%s"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="%s" targetRef="Task_Later"/>
                    <bpmn:sequenceFlow id="F3" sourceRef="Task_Later" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(ACTIVITY_ID, ACTIVITY_ID, ACTIVITY_ID);
        repositoryService.createDeployment()
                .addModelInstance("process_stale_original.bpmn", readModel(originalBpmn)).deploy();
        ProcessInstance originalPi = runtimeService.startProcessInstanceByKey("process_stale_original");
        originalProcessInstanceId = originalPi.getId();
        engine.getTaskService().complete(engine.getTaskService().createTaskQuery()
                .processInstanceId(originalProcessInstanceId).singleResult().getId());

        String twinBpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_StaleTwin" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_stale_twin" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="TStart"><bpmn:outgoing>TF1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="Sink"><bpmn:incoming>TF1</bpmn:incoming><bpmn:outgoing>TF2</bpmn:outgoing></bpmn:userTask>
                    <bpmn:endEvent id="TEnd"><bpmn:incoming>TF2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="TF1" sourceRef="TStart" targetRef="Sink"/>
                    <bpmn:sequenceFlow id="TF2" sourceRef="Sink" targetRef="TEnd"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        repositoryService.createDeployment()
                .addModelInstance("process_stale_twin.bpmn", readModel(twinBpmn)).deploy();
        twinProcessInstanceId = runtimeService.startProcessInstanceByKey("process_stale_twin").getId();

        governanceService = mock(GovernanceService.class);
        nodeManagerClient = mock(NodeManagerClient.class);

        WorkflowEventStore eventStore = new WorkflowEventStore(tempDir.resolve("events.json").toString(), true);
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

        TwinProcess twin = new TwinProcess();
        twin.setId(TWIN_ID);
        twin.setOriginalProcessId(originalProcessInstanceId);
        twin.setTwinProcessId(twinProcessInstanceId);
        twin.setStatus("RUNNING");
        twin.setEventLog(new CopyOnWriteArrayList<>());
        twin.setActivityLinks(new CopyOnWriteArrayList<>(List.of(
                new ActivityLink(ACTIVITY_ID, TWIN_ACTIVITY_ID))));
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

    // Sets execution variables matching TwinAutomationDelegate completion state.
    private void simulatePriorExecutionBy(String executorName, Map<String, Object> outputs) {
        runtimeService.setVariable(twinProcessInstanceId,
                AgentVariables.twinAutomation(TWIN_ACTIVITY_ID, null),
                executorName + " executed for " + ACTIVITY_ID + ": riskScore=85, riskFlagged=true");
        outputs.forEach((k, v) -> runtimeService.setVariable(twinProcessInstanceId,
                AgentVariables.twinAutomationOutput(k, TWIN_ACTIVITY_ID, null), v));
    }

    private void stubCatalog(String agentType, String agentName) {
        AgentAvailabilityResult available = new AgentAvailabilityResult();
        available.setAvailable(true);
        available.setAgentName(agentName);
        available.setReason("Available");
        when(nodeManagerClient.checkAgentAvailability(agentType)).thenReturn(available);
    }

    @Test
    void rebindingAnAlreadyExecutedActivityOnARunningProcessMustNotReportThePriorExecutorsOutput() {
        simulatePriorExecutionBy("CreditRiskAssessorExecutor",
                Map.of("executor", "CreditRiskAssessorExecutor", "riskScore", 85, "riskFlagged", true));

        TwinActivityExecutionState before = service.getActivityExecutionState(TWIN_ID, ACTIVITY_ID);
        assertThat(before.getStatus()).isEqualTo("EXECUTED");
        assertThat(before.getSummary()).contains("CreditRiskAssessorExecutor");

        stubCatalog("validator", "validator-agent-01");
        AgentDecision decision = service.evolveActivity(TWIN_ID, ACTIVITY_ID, "validator");
        assertThat(decision.isApproved()).isTrue();
        assertThat(decision.getAgentName()).isEqualTo("validator-agent-01");

        TwinActivityExecutionState after = service.getActivityExecutionState(TWIN_ID, ACTIVITY_ID);

        assertThat(after.getAgentName()).isEqualTo("validator-agent-01");
        assertThat(after.getStatus())
                .as("re-bound activity has not been executed by the new component yet")
                .isEqualTo("BOUND");
        assertThat(after.getSummary())
                .as("must not attribute the prior executor's summary to the newly bound agent")
                .isNull();
        assertThat(after.getOutput())
                .as("must not attribute the prior executor's output to the newly bound agent")
                .isEmpty();
    }

    @Test
    void rebindingToTheSameAgentAlsoClearsThePriorExecutionRecord() {
        simulatePriorExecutionBy("ValidatorExecutor",
                Map.of("executor", "ValidatorExecutor", "validationPassed", true));

        stubCatalog("validator", "validator-agent-01");
        assertThat(service.evolveActivity(TWIN_ID, ACTIVITY_ID, "validator").isApproved()).isTrue();

        TwinActivityExecutionState after = service.getActivityExecutionState(TWIN_ID, ACTIVITY_ID);
        assertThat(after.getStatus()).isEqualTo("BOUND");
        assertThat(after.getSummary()).isNull();
        assertThat(after.getOutput()).isEmpty();
    }

    @Test
    void evolutionDoesNotDisturbADifferentActivitysExecutionRecord() {
        String otherTwinActivityId = "Task_Other_twin";
        runtimeService.setVariable(twinProcessInstanceId,
                AgentVariables.twinAutomation(otherTwinActivityId, null), "ValidatorExecutor executed");
        runtimeService.setVariable(twinProcessInstanceId,
                AgentVariables.twinAutomationOutput("executor", otherTwinActivityId, null), "ValidatorExecutor");

        stubCatalog("validator", "validator-agent-01");
        assertThat(service.evolveActivity(TWIN_ID, ACTIVITY_ID, "validator").isApproved()).isTrue();

        assertThat(runtimeService.getVariable(twinProcessInstanceId,
                AgentVariables.twinAutomation(otherTwinActivityId, null)))
                .as("another activity's execution record must survive untouched")
                .isEqualTo("ValidatorExecutor executed");
        assertThat(runtimeService.getVariable(twinProcessInstanceId,
                AgentVariables.twinAutomationOutput("executor", otherTwinActivityId, null)))
                .isEqualTo("ValidatorExecutor");
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
