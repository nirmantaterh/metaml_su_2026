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
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Integration tests verifying Proxy and Twin runtime execution behavior across
// event synchronization, expression-based multi-instance activities, and complex constructs.
class ProxyTwinRuntimeIntegrationTest {

    private final TwinModelGenerator generator = new TwinModelGenerator();
    private ProcessEngine engine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:proxy-twin-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
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
    void intermediateSignalCatchEventSyncsToTwinReceiveTaskInLockstep() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_SigSync" targetNamespace="http://metaml.com/test">
                  <bpmn:signal id="Sig_Alert" name="SystemAlertSignal"/>
                  <bpmn:process id="process_sig_sync" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:intermediateCatchEvent id="Catch_Signal" name="Wait Alert">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:signalEventDefinition signalRef="Sig_Alert"/>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Catch_Signal"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Catch_Signal" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);
        BpmnModelInstance twin = generator.generate(model);

        repositoryService.createDeployment()
                .addModelInstance("process_sig_sync_twin.bpmn", twin)
                .deploy();

        ProcessInstance twinPi = runtimeService.startProcessInstanceByKey("process_sig_sync_twin");
        assertThat(twinPi).isNotNull();

        // Broadcast signal to trigger intermediate catch event in twin
        runtimeService.createSignalEvent("SystemAlertSignal").send();

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(twinPi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    @Test
    void intermediateMessageCatchEventSyncsToTwinReceiveTaskInLockstep() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_MsgSync" targetNamespace="http://metaml.com/test">
                  <bpmn:message id="Msg_In" name="IncomingOrderMsg"/>
                  <bpmn:process id="process_msg_sync" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:intermediateCatchEvent id="Catch_Message" name="Wait Order">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:messageEventDefinition messageRef="Msg_In"/>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Catch_Message"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Catch_Message" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);
        BpmnModelInstance twin = generator.generate(model);

        repositoryService.createDeployment()
                .addModelInstance("process_msg_sync_twin.bpmn", twin)
                .deploy();

        ProcessInstance twinPi = runtimeService.startProcessInstanceByKey("process_msg_sync_twin");
        assertThat(twinPi).isNotNull();

        // Correlate message to trigger intermediate message catch event in twin
        runtimeService.createMessageCorrelation("IncomingOrderMsg")
                .processInstanceId(twinPi.getId())
                .correlate();

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(twinPi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    @Test
    void expressionBasedMultiInstancePreservedAndExecutedInTwin() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_ExprMI" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_expr_mi" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="Task_ExprMI" name="Dynamic Loop">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="false">
                        <bpmn:loopCardinality>${loopCount}</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_ExprMI"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_ExprMI" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);
        BpmnModelInstance twin = generator.generate(model);

        repositoryService.createDeployment()
                .addModelInstance("process_expr_mi_twin.bpmn", twin)
                .deploy();

        // Start twin with runtime variable loopCount = 2
        ProcessInstance twinPi = runtimeService.startProcessInstanceByKey("process_expr_mi_twin", Map.of("loopCount", 2));
        assertThat(twinPi).isNotNull();

        // Advance both multi-instance iterations in twin
        runtimeService.createMessageCorrelation("TwinAdvance_Task_ExprMI")
                .processInstanceId(twinPi.getId())
                .correlateAll();

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(twinPi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    @Test
    void combinedWorkflow_catchEventsSubProcessAndExpressionMI() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_AdvCombined" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_adv_combined" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:intermediateCatchEvent id="Catch_Step1" name="Wait Step">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:timerEventDefinition>
                        <bpmn:timeDuration>PT10S</bpmn:timeDuration>
                      </bpmn:timerEventDefinition>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:subProcess id="Sub_Pipeline" name="Pipeline Sub">
                      <bpmn:incoming>F2</bpmn:incoming>
                      <bpmn:outgoing>F3</bpmn:outgoing>
                      <bpmn:startEvent id="SubStart"><bpmn:outgoing>SF1</bpmn:outgoing></bpmn:startEvent>
                      <bpmn:userTask id="Task_AdvMI" name="Adv Review">
                        <bpmn:incoming>SF1</bpmn:incoming>
                        <bpmn:outgoing>SF2</bpmn:outgoing>
                        <bpmn:multiInstanceLoopCharacteristics isSequential="false">
                          <bpmn:loopCardinality>${itemsCount}</bpmn:loopCardinality>
                        </bpmn:multiInstanceLoopCharacteristics>
                      </bpmn:userTask>
                      <bpmn:endEvent id="SubEnd"><bpmn:incoming>SF2</bpmn:incoming></bpmn:endEvent>
                      <bpmn:sequenceFlow id="SF1" sourceRef="SubStart" targetRef="Task_AdvMI"/>
                      <bpmn:sequenceFlow id="SF2" sourceRef="Task_AdvMI" targetRef="SubEnd"/>
                    </bpmn:subProcess>
                    <bpmn:endEvent id="End"><bpmn:incoming>F3</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Catch_Step1"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Catch_Step1" targetRef="Sub_Pipeline"/>
                    <bpmn:sequenceFlow id="F3" sourceRef="Sub_Pipeline" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);
        BpmnModelInstance twin = generator.generate(model);

        repositoryService.createDeployment()
                .addModelInstance("process_adv_combined_twin.bpmn", twin)
                .deploy();

        ProcessInstance twinPi = runtimeService.startProcessInstanceByKey("process_adv_combined_twin", Map.of("itemsCount", 2));
        assertThat(twinPi).isNotNull();

        // Execute timer job to advance timer catch event in twin
        org.camunda.bpm.engine.runtime.Job timerJob = engine.getManagementService().createJobQuery()
                .processInstanceId(twinPi.getId()).singleResult();
        if (timerJob != null) {
            engine.getManagementService().executeJob(timerJob.getId());
        }

        // Complete any active user tasks and correlate multi-instance receive tasks
        for (org.camunda.bpm.engine.task.Task t : taskService.createTaskQuery().processInstanceId(twinPi.getId()).list()) {
            taskService.complete(t.getId());
        }
        runtimeService.createMessageCorrelation("TwinAdvance_Task_AdvMI")
                .processInstanceId(twinPi.getId())
                .correlateAll();

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(twinPi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    private static BpmnModelInstance readModel(String xml) {
        return Bpmn.readModelFromStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
