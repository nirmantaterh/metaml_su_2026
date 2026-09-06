package com.metaml.workbench.bpmn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// BPMN runtime coverage verification
// Verifies Event SubProcess, Timer Boundary Event, and Error Boundary Event execution in Camunda 7.22.
class BpmnCoverageVerificationTest {

    private final TwinModelGenerator generator = new TwinModelGenerator();
    private ProcessEngine engine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:phase13-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJobExecutorActivate(false);
        if (config instanceof ProcessEngineConfigurationImpl configImpl) {
            configImpl.setBeans(Map.of(
                    "twinAutomationDelegate", (JavaDelegate) execution -> {},
                    "failingDelegate", (JavaDelegate) execution -> {
                        throw new BpmnError("ERR_01", "Business failure");
                    }));
        }
        engine = config.buildProcessEngine();

        repositoryService = engine.getRepositoryService();
        runtimeService = engine.getRuntimeService();
        taskService = engine.getTaskService();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void eventSubProcessTriggersAndExecutesInTwinEngine() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_EvtSub" targetNamespace="http://metaml.com/test">
                  <bpmn:signal id="Sig_Evt" name="TriggerEvtSubSignal"/>
                  <bpmn:process id="process_evt_sub" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="Task_Main" name="Main Task">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_Main"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_Main" targetRef="End"/>

                    <bpmn:subProcess id="Sub_Evt" triggeredByEvent="true">
                      <bpmn:startEvent id="EvtSubStart" isInterrupting="true">
                        <bpmn:outgoing>EF1</bpmn:outgoing>
                        <bpmn:signalEventDefinition signalRef="Sig_Evt"/>
                      </bpmn:startEvent>
                      <bpmn:userTask id="Task_EvtSub" name="Evt Sub Task">
                        <bpmn:incoming>EF1</bpmn:incoming>
                        <bpmn:outgoing>EF2</bpmn:outgoing>
                      </bpmn:userTask>
                      <bpmn:endEvent id="EvtSubEnd"><bpmn:incoming>EF2</bpmn:incoming></bpmn:endEvent>
                      <bpmn:sequenceFlow id="EF1" sourceRef="EvtSubStart" targetRef="Task_EvtSub"/>
                      <bpmn:sequenceFlow id="EF2" sourceRef="Task_EvtSub" targetRef="EvtSubEnd"/>
                    </bpmn:subProcess>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);

        repositoryService.createDeployment()
                .addModelInstance("process_evt_sub.bpmn", model)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("process_evt_sub");
        assertThat(pi).isNotNull();

        // Broadcast signal to trigger event sub-process
        runtimeService.createSignalEvent("TriggerEvtSubSignal").send();

        // Complete active tasks
        for (Task t : taskService.createTaskQuery().processInstanceId(pi.getId()).list()) {
            taskService.complete(t.getId());
        }

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    @Test
    void timerBoundaryEventTriggersAlternatePathInTwin() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_TimerBoundary" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_timer_boundary" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="Task_Wait" name="Wait User">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:boundaryEvent id="Boundary_Timer" attachedToRef="Task_Wait" cancelActivity="true">
                      <bpmn:outgoing>F_Timeout</bpmn:outgoing>
                      <bpmn:timerEventDefinition>
                        <bpmn:timeDuration>PT10S</bpmn:timeDuration>
                      </bpmn:timerEventDefinition>
                    </bpmn:boundaryEvent>
                    <bpmn:userTask id="Task_Timeout" name="Timeout Handler">
                      <bpmn:incoming>F_Timeout</bpmn:incoming>
                      <bpmn:outgoing>F_EndTimeout</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:endEvent id="End_Timeout"><bpmn:incoming>F_EndTimeout</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_Wait"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_Wait" targetRef="End"/>
                    <bpmn:sequenceFlow id="F_Timeout" sourceRef="Boundary_Timer" targetRef="Task_Timeout"/>
                    <bpmn:sequenceFlow id="F_EndTimeout" sourceRef="Task_Timeout" targetRef="End_Timeout"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);

        repositoryService.createDeployment()
                .addModelInstance("process_timer_boundary.bpmn", model)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("process_timer_boundary");
        assertThat(pi).isNotNull();

        // Execute timer job attached to boundary event
        Job timerJob = engine.getManagementService().createJobQuery()
                .processInstanceId(pi.getId()).singleResult();
        assertThat(timerJob).isNotNull();
        engine.getManagementService().executeJob(timerJob.getId());

        // Complete timeout task
        Task timeoutTask = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(timeoutTask).isNotNull();
        taskService.complete(timeoutTask.getId());

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    @Test
    void errorBoundaryEventCatchesExceptionAndExecutesRecoveryFlow() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_ErrorBoundary" targetNamespace="http://metaml.com/test">
                  <bpmn:error id="Error_1" name="ERR_01" errorCode="ERR_01"/>
                  <bpmn:process id="process_error_boundary" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:serviceTask id="Task_Fail" name="Failing Service" camunda:delegateExpression="${failingDelegate}">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:boundaryEvent id="Boundary_Error" attachedToRef="Task_Fail">
                      <bpmn:outgoing>F_Err</bpmn:outgoing>
                      <bpmn:errorEventDefinition errorRef="Error_1"/>
                    </bpmn:boundaryEvent>
                    <bpmn:userTask id="Task_Recovery" name="Recovery Step">
                      <bpmn:incoming>F_Err</bpmn:incoming>
                      <bpmn:outgoing>F_EndErr</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:endEvent id="End_Err"><bpmn:incoming>F_EndErr</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_Fail"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_Fail" targetRef="End"/>
                    <bpmn:sequenceFlow id="F_Err" sourceRef="Boundary_Error" targetRef="Task_Recovery"/>
                    <bpmn:sequenceFlow id="F_EndErr" sourceRef="Task_Recovery" targetRef="End_Err"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);
        // Note: For Proxy process, Task_Fail executes failingDelegate throwing BpmnError("ERR_01"),
        // which triggers Boundary_Error and flows to Task_Recovery.
        repositoryService.createDeployment()
                .addModelInstance("process_error_boundary.bpmn", model)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("process_error_boundary");
        assertThat(pi).isNotNull();

        // Complete recovery user task
        Task recTask = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(recTask).isNotNull();
        taskService.complete(recTask.getId());

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    private static BpmnModelInstance readModel(String xml) {
        return Bpmn.readModelFromStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
