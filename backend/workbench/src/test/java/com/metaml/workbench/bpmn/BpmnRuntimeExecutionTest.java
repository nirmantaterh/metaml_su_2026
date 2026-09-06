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

// Runtime completion and end-to-end proof:
// Exercises real, standalone Camunda process and DMN engines to prove runtime execution for:
// 1. Script Task (JUEL expression execution & process variable setting)
// 2. Embedded SubProcess (Twin generation, deployment, task completion, process completion)
// 3. Call Activity (Parent process invocation of child process & child twin)
// 4. Business Rule Task (DMN decision table deployment, execution, result variable setting)
// 5. Intermediate Signal Catch Event (Signal event subscription & continuation)
// 6. Multi-Instance User Task (Literal cardinality execution in twin)
class BpmnRuntimeExecutionTest {

    private final TwinModelGenerator generator = new TwinModelGenerator();
    private ProcessEngine engine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:phase9-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
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
    void scriptTaskExecutesNativelyInCamundaAndSetsProcessVariable() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_ScriptRun" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_script_run" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:scriptTask id="Task_Script" name="Compute" scriptFormat="juel">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:script>${execution.setVariable("resultStatus", "SCRIPT_PASSED")}</bpmn:script>
                    </bpmn:scriptTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_Script"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_Script" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);
        BpmnModelInstance twin = generator.generate(model);

        repositoryService.createDeployment()
                .addModelInstance("process_script_run_twin.bpmn", twin)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("process_script_run_twin");
        assertThat(pi).isNotNull();

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
        Object varValue = engine.getHistoryService()
                .createHistoricVariableInstanceQuery()
                .processInstanceId(pi.getId())
                .variableName("resultStatus")
                .singleResult()
                .getValue();
        assertThat(varValue).isEqualTo("SCRIPT_PASSED");
    }

    @Test
    void embeddedSubProcessExecutesAndCompletesInCamundaEngine() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_SubRun" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_sub_run" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:subProcess id="Sub_Nested" name="Nested Workflow">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:startEvent id="SubStart"><bpmn:outgoing>SF1</bpmn:outgoing></bpmn:startEvent>
                      <bpmn:userTask id="SubTask" name="Nested User Step">
                        <bpmn:incoming>SF1</bpmn:incoming>
                        <bpmn:outgoing>SF2</bpmn:outgoing>
                      </bpmn:userTask>
                      <bpmn:endEvent id="SubEnd"><bpmn:incoming>SF2</bpmn:incoming></bpmn:endEvent>
                      <bpmn:sequenceFlow id="SF1" sourceRef="SubStart" targetRef="SubTask"/>
                      <bpmn:sequenceFlow id="SF2" sourceRef="SubTask" targetRef="SubEnd"/>
                    </bpmn:subProcess>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Sub_Nested"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Sub_Nested" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);
        BpmnModelInstance twin = generator.generate(model);

        repositoryService.createDeployment()
                .addModelInstance("process_sub_run_twin.bpmn", twin)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("process_sub_run_twin");
        assertThat(pi).isNotNull();

        // Complete user task inside sub-process
        Task userTask = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        if (userTask != null) {
            taskService.complete(userTask.getId());
        } else {
            runtimeService.createMessageCorrelation("TwinAdvance_SubTask")
                    .processInstanceId(pi.getId())
                    .correlateAll();
        }

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    @Test
    void callActivityInvokesChildProcessInCamundaEngine() {
        String childXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_Child" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="childProcess" name="Child Process" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="ChildStart"><bpmn:outgoing>CF1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:scriptTask id="ChildScript" name="Child Script" scriptFormat="juel">
                      <bpmn:incoming>CF1</bpmn:incoming>
                      <bpmn:outgoing>CF2</bpmn:outgoing>
                      <bpmn:script>${execution.setVariable("childExecuted", true)}</bpmn:script>
                    </bpmn:scriptTask>
                    <bpmn:endEvent id="ChildEnd"><bpmn:incoming>CF2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="CF1" sourceRef="ChildStart" targetRef="ChildScript"/>
                    <bpmn:sequenceFlow id="CF2" sourceRef="ChildScript" targetRef="ChildEnd"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        String parentXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_Parent" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="parentProcess" name="Parent Process" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="ParentStart"><bpmn:outgoing>PF1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:callActivity id="Call_Child" name="Invoke Child" calledElement="childProcess">
                      <bpmn:incoming>PF1</bpmn:incoming>
                      <bpmn:outgoing>PF2</bpmn:outgoing>
                    </bpmn:callActivity>
                    <bpmn:endEvent id="ParentEnd"><bpmn:incoming>PF2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="PF1" sourceRef="ParentStart" targetRef="Call_Child"/>
                    <bpmn:sequenceFlow id="PF2" sourceRef="Call_Child" targetRef="ParentEnd"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance childTwin = generator.generate(readModel(childXml));
        BpmnModelInstance parentTwin = generator.generate(readModel(parentXml));

        // Deploy both child process and parent process definitions
        repositoryService.createDeployment()
                .addModelInstance("childProcess_twin.bpmn", childTwin)
                .addModelInstance("parentProcess_twin.bpmn", parentTwin)
                .addModelInstance("childProcess.bpmn", readModel(childXml))
                .deploy();

        ProcessInstance parentPi = runtimeService.startProcessInstanceByKey("parentProcess_twin");
        assertThat(parentPi).isNotNull();

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(parentPi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    @Test
    void businessRuleTaskExecutesDmnDecisionTableAndSetsResultVariable() {
        String dmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                             xmlns:camunda="http://camunda.org/schema/1.0/dmn"
                             id="Def_RiskDmn" name="Risk Assessment" namespace="http://camunda.org/schema/1.0/dmn">
                  <decision id="riskDecision" name="Risk Assessment Decision" camunda:historyTimeToLive="180">
                    <decisionTable id="dt_risk">
                      <input id="in_score" label="Score">
                        <inputExpression id="exp_score" typeRef="integer">
                          <text>score</text>
                        </inputExpression>
                      </input>
                      <output id="out_risk" name="riskTier" label="Risk Tier" typeRef="string"/>
                      <rule id="rule_low">
                        <inputEntry id="ie_low"><text>&gt; 75</text></inputEntry>
                        <outputEntry id="oe_low"><text>"LOW"</text></outputEntry>
                      </rule>
                      <rule id="rule_high">
                        <inputEntry id="ie_high"><text>&lt;= 75</text></inputEntry>
                        <outputEntry id="oe_high"><text>"HIGH"</text></outputEntry>
                      </rule>
                    </decisionTable>
                  </decision>
                </definitions>
                """;

        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_DmnBpmn" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_dmn_run" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:businessRuleTask id="Task_Rule" name="Evaluate"
                                          camunda:decisionRef="riskDecision"
                                          camunda:resultVariable="riskTier"
                                          camunda:mapDecisionResult="singleEntry">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:businessRuleTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_Rule"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_Rule" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance twin = generator.generate(readModel(bpmnXml));

        repositoryService.createDeployment()
                .addString("riskDecision.dmn", dmnXml)
                .addModelInstance("process_dmn_run_twin.bpmn", twin)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "process_dmn_run_twin", Map.of("score", 85));

        assertThat(pi).isNotNull();

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
        Object riskResult = engine.getHistoryService()
                .createHistoricVariableInstanceQuery()
                .processInstanceId(pi.getId())
                .variableName("riskTier")
                .singleResult()
                .getValue();
        assertThat(riskResult).isEqualTo(java.util.List.of(Map.of("riskTier", "LOW")));
    }

    @Test
    void intermediateSignalCatchEventSubscribesAndResumesOnSignal() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_SignalRun" targetNamespace="http://metaml.com/test">
                  <bpmn:signal id="Sig_1" name="Phase9AlertSignal" />
                  <bpmn:process id="process_signal_run" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:intermediateCatchEvent id="Catch_Signal" name="Wait for Alert">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:signalEventDefinition signalRef="Sig_1"/>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Catch_Signal"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Catch_Signal" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance twin = generator.generate(readModel(bpmnXml));

        repositoryService.createDeployment()
                .addModelInstance("process_signal_run_twin.bpmn", twin)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("process_signal_run_twin");
        assertThat(pi).isNotNull();

        // Broadcast signal to resume execution
        runtimeService.createSignalEvent("Phase9AlertSignal").send();

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    @Test
    void multiInstanceUserTaskExecutesLiteralCardinalityInTwin() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_MIRun" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_mi_run" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="Task_MI" name="Parallel Review">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="false">
                        <bpmn:loopCardinality>2</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_MI"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_MI" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance twin = generator.generate(readModel(bpmnXml));

        repositoryService.createDeployment()
                .addModelInstance("process_mi_run_twin.bpmn", twin)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("process_mi_run_twin");
        assertThat(pi).isNotNull();

        // Correlate message for both parallel multi-instance sub-process instances
        runtimeService.createMessageCorrelation("TwinAdvance_Task_MI")
                .processInstanceId(pi.getId())
                .correlateAll();

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
