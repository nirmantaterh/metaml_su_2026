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
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Verifies BPMN variant execution across non-interrupting event subprocesses,
// link events, multiple catch events, and non-interrupting boundary events.
class BpmnVariantCoverageTest {

    private final TwinModelGenerator generator = new TwinModelGenerator();
    private ProcessEngine engine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:bpmn-variant-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJobExecutorActivate(false);
        if (config instanceof ProcessEngineConfigurationImpl configImpl) {
            configImpl.setBeans(Map.of("twinAutomationDelegate", (JavaDelegate) execution -> {}));
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
    void nonInterruptingEventSubProcessExecutesConcurrentlyWithoutCancelingMainProcess() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_NonIntEvtSub" targetNamespace="http://metaml.com/test">
                  <bpmn:signal id="Sig_NonInt" name="NonInterruptingSignal"/>
                  <bpmn:process id="process_non_int_sub" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="Task_Main" name="Main Task">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_Main"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_Main" targetRef="End"/>

                    <bpmn:subProcess id="Sub_NonInt" triggeredByEvent="true">
                      <bpmn:startEvent id="EvtSubStart" isInterrupting="false">
                        <bpmn:outgoing>EF1</bpmn:outgoing>
                        <bpmn:signalEventDefinition signalRef="Sig_NonInt"/>
                      </bpmn:startEvent>
                      <bpmn:userTask id="Task_Sub" name="Concurrent Sub Task">
                        <bpmn:incoming>EF1</bpmn:incoming>
                        <bpmn:outgoing>EF2</bpmn:outgoing>
                      </bpmn:userTask>
                      <bpmn:endEvent id="EvtSubEnd"><bpmn:incoming>EF2</bpmn:incoming></bpmn:endEvent>
                      <bpmn:sequenceFlow id="EF1" sourceRef="EvtSubStart" targetRef="Task_Sub"/>
                      <bpmn:sequenceFlow id="EF2" sourceRef="Task_Sub" targetRef="EvtSubEnd"/>
                    </bpmn:subProcess>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);

        repositoryService.createDeployment()
                .addModelInstance("process_non_int_sub.bpmn", model)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("process_non_int_sub");
        assertThat(pi).isNotNull();

        // Broadcast non-interrupting signal
        runtimeService.createSignalEvent("NonInterruptingSignal").send();

        // Both Task_Main and Task_Sub should be active concurrently
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
    void linkEventsJumpNativelyInCamundaEngine() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_LinkEvent" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_link_event" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:intermediateThrowEvent id="Throw_Link" name="Jump Target A">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:linkEventDefinition name="LinkA"/>
                    </bpmn:intermediateThrowEvent>
                    <bpmn:intermediateCatchEvent id="Catch_Link" name="Catch Target A">
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:linkEventDefinition name="LinkA"/>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:userTask id="Task_AfterLink" name="Post Link Task">
                      <bpmn:incoming>F2</bpmn:incoming>
                      <bpmn:outgoing>F3</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F3</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Throw_Link"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Catch_Link" targetRef="Task_AfterLink"/>
                    <bpmn:sequenceFlow id="F3" sourceRef="Task_AfterLink" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);

        repositoryService.createDeployment()
                .addModelInstance("process_link_event.bpmn", model)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("process_link_event");
        assertThat(pi).isNotNull();

        Task afterLinkTask = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(afterLinkTask).isNotNull();
        taskService.complete(afterLinkTask.getId());

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    @Test
    void multipleCatchEventsTriggersOnSignalOrMessage() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_MultiCatch" targetNamespace="http://metaml.com/test">
                  <bpmn:signal id="Sig_M" name="MultiSignal"/>
                  <bpmn:message id="Msg_M" name="MultiMessage"/>
                  <bpmn:process id="process_multi_catch" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:intermediateCatchEvent id="Catch_Multi" name="Multi Catch">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:signalEventDefinition signalRef="Sig_M"/>
                      <bpmn:messageEventDefinition messageRef="Msg_M"/>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:userTask id="Task_AfterMulti" name="After Multi Task">
                      <bpmn:incoming>F2</bpmn:incoming>
                      <bpmn:outgoing>F3</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F3</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Catch_Multi"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Catch_Multi" targetRef="Task_AfterMulti"/>
                    <bpmn:sequenceFlow id="F3" sourceRef="Task_AfterMulti" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);

        repositoryService.createDeployment()
                .addModelInstance("process_multi_catch.bpmn", model)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("process_multi_catch");
        assertThat(pi).isNotNull();

        // Broadcast signal to satisfy multiple catch event
        runtimeService.createSignalEvent("MultiSignal").send();

        Task afterTask = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(afterTask).isNotNull();
        taskService.complete(afterTask.getId());

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    @Test
    void nonInterruptingBoundaryEventSpawnsConcurrentBranchWithoutCancelingTask() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_NonIntBoundary" targetNamespace="http://metaml.com/test">
                  <bpmn:signal id="Sig_Boundary" name="BoundaryAlertSignal"/>
                  <bpmn:process id="process_non_int_boundary" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="Task_Main" name="Primary Work">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:boundaryEvent id="Boundary_Alert" attachedToRef="Task_Main" cancelActivity="false">
                      <bpmn:outgoing>F_Alert</bpmn:outgoing>
                      <bpmn:signalEventDefinition signalRef="Sig_Boundary"/>
                    </bpmn:boundaryEvent>
                    <bpmn:userTask id="Task_AlertHandler" name="Alert Handler">
                      <bpmn:incoming>F_Alert</bpmn:incoming>
                      <bpmn:outgoing>F_EndAlert</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:endEvent id="End_Alert"><bpmn:incoming>F_EndAlert</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_Main"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_Main" targetRef="End"/>
                    <bpmn:sequenceFlow id="F_Alert" sourceRef="Boundary_Alert" targetRef="Task_AlertHandler"/>
                    <bpmn:sequenceFlow id="F_EndAlert" sourceRef="Task_AlertHandler" targetRef="End_Alert"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);

        repositoryService.createDeployment()
                .addModelInstance("process_non_int_boundary.bpmn", model)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("process_non_int_boundary");
        assertThat(pi).isNotNull();

        // Broadcast signal to trigger non-interrupting boundary event
        runtimeService.createSignalEvent("BoundaryAlertSignal").send();

        // Complete both primary task and alert handler task concurrently
        for (Task t : taskService.createTaskQuery().processInstanceId(pi.getId()).list()) {
            taskService.complete(t.getId());
        }

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
