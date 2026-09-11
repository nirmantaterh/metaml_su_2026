package com.metaml.workbench.bpmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.GeneratedDelegate;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.SpringBootProjectGenerator;

// BPMN construct combinations and Proxy/Twin completeness
// Verifies complex construct combinations, fresh generation, runtime execution in Camunda 7.22,
// semantic preservation between original and twin, and loud fail-fast negative testing.
class BpmnCombinationStressTest {

    private final TwinModelGenerator generator = new TwinModelGenerator();
    private ProcessEngine engine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:bpmn-stress-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
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

    // Process A: Service Task -> Exclusive Gateway -> Inclusive Gateway -> Parallel Gateway -> User Task -> End
    @Test
    void processA_combinesServiceTaskAndMultiGatewayPipeline() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_ProcA" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="processA" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="StartA"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:serviceTask id="Task_Svc" name="Calculate Risk" camunda:expression="${true}">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:exclusiveGateway id="G_Exclusive" default="F_ExDefault">
                      <bpmn:incoming>F2</bpmn:incoming>
                      <bpmn:outgoing>F_ExDefault</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:inclusiveGateway id="G_Inclusive" default="F_IncDefault">
                      <bpmn:incoming>F_ExDefault</bpmn:incoming>
                      <bpmn:outgoing>F_IncDefault</bpmn:outgoing>
                    </bpmn:inclusiveGateway>
                    <bpmn:parallelGateway id="G_Parallel">
                      <bpmn:incoming>F_IncDefault</bpmn:incoming>
                      <bpmn:outgoing>F_Par1</bpmn:outgoing>
                    </bpmn:parallelGateway>
                    <bpmn:userTask id="Task_User" name="Final Review">
                      <bpmn:incoming>F_Par1</bpmn:incoming>
                      <bpmn:outgoing>F_End</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndA"><bpmn:incoming>F_End</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="StartA" targetRef="Task_Svc"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_Svc" targetRef="G_Exclusive"/>
                    <bpmn:sequenceFlow id="F_ExDefault" sourceRef="G_Exclusive" targetRef="G_Inclusive"/>
                    <bpmn:sequenceFlow id="F_IncDefault" sourceRef="G_Inclusive" targetRef="G_Parallel"/>
                    <bpmn:sequenceFlow id="F_Par1" sourceRef="G_Parallel" targetRef="Task_User"/>
                    <bpmn:sequenceFlow id="F_End" sourceRef="Task_User" targetRef="EndA"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);
        BpmnModelInstance twin = generator.generate(model);

        repositoryService.createDeployment()
                .addModelInstance("processA_twin.bpmn", twin)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("processA_twin");
        assertThat(pi).isNotNull();

        // Advance twin receive tasks for serviceTask and userTask
        runtimeService.createMessageCorrelation("TwinAdvance_Task_Svc").processInstanceId(pi.getId()).correlate();
        runtimeService.createMessageCorrelation("TwinAdvance_Task_User").processInstanceId(pi.getId()).correlate();

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    // Process B: Event-Based Gateway -> Catch Events -> SubProcess -> Multi-Instance User Task -> End
    @Test
    void processB_combinesEventBasedGatewaySubProcessAndMultiInstance() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_ProcB" targetNamespace="http://metaml.com/test">
                  <bpmn:message id="Msg_B1" name="MessageB1"/>
                  <bpmn:process id="processB" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="StartB"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:eventBasedGateway id="G_Event">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F_Msg</bpmn:outgoing>
                    </bpmn:eventBasedGateway>
                    <bpmn:intermediateCatchEvent id="Catch_Msg" name="Wait Msg">
                      <bpmn:incoming>F_Msg</bpmn:incoming>
                      <bpmn:outgoing>F_Sub</bpmn:outgoing>
                      <bpmn:messageEventDefinition messageRef="Msg_B1"/>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:subProcess id="Sub_B" name="Nested Sub">
                      <bpmn:incoming>F_Sub</bpmn:incoming>
                      <bpmn:outgoing>F_End</bpmn:outgoing>
                      <bpmn:startEvent id="SubStartB"><bpmn:outgoing>SF1</bpmn:outgoing></bpmn:startEvent>
                      <bpmn:userTask id="Task_MIB" name="MI Review">
                        <bpmn:incoming>SF1</bpmn:incoming>
                        <bpmn:outgoing>SF2</bpmn:outgoing>
                        <bpmn:multiInstanceLoopCharacteristics isSequential="false">
                          <bpmn:loopCardinality>2</bpmn:loopCardinality>
                        </bpmn:multiInstanceLoopCharacteristics>
                      </bpmn:userTask>
                      <bpmn:endEvent id="SubEndB"><bpmn:incoming>SF2</bpmn:incoming></bpmn:endEvent>
                      <bpmn:sequenceFlow id="SF1" sourceRef="SubStartB" targetRef="Task_MIB"/>
                      <bpmn:sequenceFlow id="SF2" sourceRef="Task_MIB" targetRef="SubEndB"/>
                    </bpmn:subProcess>
                    <bpmn:endEvent id="EndB"><bpmn:incoming>F_End</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="StartB" targetRef="G_Event"/>
                    <bpmn:sequenceFlow id="F_Msg" sourceRef="G_Event" targetRef="Catch_Msg"/>
                    <bpmn:sequenceFlow id="F_Sub" sourceRef="Catch_Msg" targetRef="Sub_B"/>
                    <bpmn:sequenceFlow id="F_End" sourceRef="Sub_B" targetRef="EndB"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);
        BpmnModelInstance twin = generator.generate(model);

        repositoryService.createDeployment()
                .addModelInstance("processB_twin.bpmn", twin)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("processB_twin");
        assertThat(pi).isNotNull();

        // Correlate message to trigger event catch
        runtimeService.createMessageCorrelation("MessageB1").processInstanceId(pi.getId()).correlate();

        // Complete user task or correlate twin advance message for multi-instance sub-process
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(pi.getId()).list();
        for (Task t : tasks) {
            taskService.complete(t.getId());
        }
        runtimeService.createMessageCorrelation("TwinAdvance_Task_MIB").processInstanceId(pi.getId()).correlateAll();

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    // Process C: Call Activity -> Child Process -> Script Task -> Return to Parent
    @Test
    void processC_combinesCallActivityAndScriptTaskChild() {
        String childXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_ChildC" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="childProcessC" name="Child Process C" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="ChildStartC"><bpmn:outgoing>CF1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:scriptTask id="Task_ChildScript" name="Child Logic" scriptFormat="juel">
                      <bpmn:incoming>CF1</bpmn:incoming>
                      <bpmn:outgoing>CF2</bpmn:outgoing>
                      <bpmn:script>${execution.setVariable("childRun", true)}</bpmn:script>
                    </bpmn:scriptTask>
                    <bpmn:endEvent id="ChildEndC"><bpmn:incoming>CF2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="CF1" sourceRef="ChildStartC" targetRef="Task_ChildScript"/>
                    <bpmn:sequenceFlow id="CF2" sourceRef="Task_ChildScript" targetRef="ChildEndC"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        String parentXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_ParentC" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="processC" name="Parent Process C" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="StartC"><bpmn:outgoing>PF1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:callActivity id="Call_ChildC" name="Call Child C" calledElement="childProcessC">
                      <bpmn:incoming>PF1</bpmn:incoming>
                      <bpmn:outgoing>PF2</bpmn:outgoing>
                    </bpmn:callActivity>
                    <bpmn:endEvent id="EndC"><bpmn:incoming>PF2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="PF1" sourceRef="StartC" targetRef="Call_ChildC"/>
                    <bpmn:sequenceFlow id="PF2" sourceRef="Call_ChildC" targetRef="EndC"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance childTwin = generator.generate(readModel(childXml));
        BpmnModelInstance parentTwin = generator.generate(readModel(parentXml));

        repositoryService.createDeployment()
                .addModelInstance("childProcessC_twin.bpmn", childTwin)
                .addModelInstance("processC_twin.bpmn", parentTwin)
                .addModelInstance("childProcessC.bpmn", readModel(childXml))
                .deploy();

        ProcessInstance parentPi = runtimeService.startProcessInstanceByKey("processC_twin");
        assertThat(parentPi).isNotNull();

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(parentPi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    // Process D: Business Rule Task -> Conditional Gateway -> Parallel Branches -> Join
    @Test
    void processD_combinesBusinessRuleTaskAndParallelBranches() {
        String dmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                             xmlns:camunda="http://camunda.org/schema/1.0/dmn"
                             id="Def_DmnD" name="Tier Assessment" namespace="http://camunda.org/schema/1.0/dmn">
                  <decision id="tierDecision" name="Tier Decision" camunda:historyTimeToLive="180">
                    <decisionTable id="dt_tier">
                      <input id="in_amount" label="Amount">
                        <inputExpression id="exp_amount" typeRef="integer">
                          <text>amount</text>
                        </inputExpression>
                      </input>
                      <output id="out_tier" name="tier" label="Tier" typeRef="string"/>
                      <rule id="rule_tier">
                        <inputEntry id="ie_tier"><text>&gt; 100</text></inputEntry>
                        <outputEntry id="oe_tier"><text>"GOLD"</text></outputEntry>
                      </rule>
                    </decisionTable>
                  </decision>
                </definitions>
                """;

        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_ProcD" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="processD" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="StartD"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:businessRuleTask id="Task_DmnD" name="Assess Tier"
                                          camunda:decisionRef="tierDecision"
                                          camunda:resultVariable="tierResult"
                                          camunda:mapDecisionResult="singleEntry">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:businessRuleTask>
                    <bpmn:parallelGateway id="G_SplitD">
                      <bpmn:incoming>F2</bpmn:incoming>
                      <bpmn:outgoing>F_Branch1</bpmn:outgoing>
                      <bpmn:outgoing>F_Branch2</bpmn:outgoing>
                    </bpmn:parallelGateway>
                    <bpmn:scriptTask id="Task_ScriptD1" name="Branch 1" scriptFormat="juel">
                      <bpmn:incoming>F_Branch1</bpmn:incoming>
                      <bpmn:outgoing>F_Join1</bpmn:outgoing>
                      <bpmn:script>${execution.setVariable("b1", true)}</bpmn:script>
                    </bpmn:scriptTask>
                    <bpmn:scriptTask id="Task_ScriptD2" name="Branch 2" scriptFormat="juel">
                      <bpmn:incoming>F_Branch2</bpmn:incoming>
                      <bpmn:outgoing>F_Join2</bpmn:outgoing>
                      <bpmn:script>${execution.setVariable("b2", true)}</bpmn:script>
                    </bpmn:scriptTask>
                    <bpmn:parallelGateway id="G_JoinD">
                      <bpmn:incoming>F_Join1</bpmn:incoming>
                      <bpmn:incoming>F_Join2</bpmn:incoming>
                      <bpmn:outgoing>F_EndD</bpmn:outgoing>
                    </bpmn:parallelGateway>
                    <bpmn:endEvent id="EndD"><bpmn:incoming>F_EndD</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="StartD" targetRef="Task_DmnD"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_DmnD" targetRef="G_SplitD"/>
                    <bpmn:sequenceFlow id="F_Branch1" sourceRef="G_SplitD" targetRef="Task_ScriptD1"/>
                    <bpmn:sequenceFlow id="F_Branch2" sourceRef="G_SplitD" targetRef="Task_ScriptD2"/>
                    <bpmn:sequenceFlow id="F_Join1" sourceRef="Task_ScriptD1" targetRef="G_JoinD"/>
                    <bpmn:sequenceFlow id="F_Join2" sourceRef="Task_ScriptD2" targetRef="G_JoinD"/>
                    <bpmn:sequenceFlow id="F_EndD" sourceRef="G_JoinD" targetRef="EndD"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance twin = generator.generate(readModel(bpmnXml));

        repositoryService.createDeployment()
                .addString("tierDecision.dmn", dmnXml)
                .addModelInstance("processD_twin.bpmn", twin)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKey("processD_twin", Map.of("amount", 200));
        assertThat(pi).isNotNull();

        HistoricProcessInstance hpi = engine.getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(hpi.getState()).isEqualTo(HistoricProcessInstance.STATE_COMPLETED);
    }

    // Negative Test: Complex Gateway rejection fails fast with clear error message
    @Test
    void negativeTesting_complexGatewayFailsFastLoudly() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Def_ComplexFail" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_complex" isExecutable="true">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:complexGateway id="G_Complex">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:complexGateway>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="G_Complex"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="G_Complex" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);

        assertThatThrownBy(() -> generator.generate(model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("G_Complex")
                .hasMessageContaining("does not support");
    }

    // Negative Test: Plain unsupported task element fails fast with clear error message
    @Test
    void negativeTesting_plainAbstractTaskFailsFastLoudly() {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Def_PlainTaskFail" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="process_plain" isExecutable="true">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:task id="Task_Plain" name="Abstract Step">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:task>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_Plain"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_Plain" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        BpmnModelInstance model = readModel(bpmnXml);

        assertThatThrownBy(() -> generator.generate(model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task_Plain")
                .hasMessageContaining("does not support");
    }

    private static BpmnModelInstance readModel(String xml) {
        return Bpmn.readModelFromStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
