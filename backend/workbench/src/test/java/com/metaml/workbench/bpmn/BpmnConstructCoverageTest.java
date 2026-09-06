package com.metaml.workbench.bpmn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask;
import org.camunda.bpm.model.bpmn.instance.CallActivity;
import org.camunda.bpm.model.bpmn.instance.EndEvent;
import org.camunda.bpm.model.bpmn.instance.ErrorEventDefinition;
import org.camunda.bpm.model.bpmn.instance.IntermediateThrowEvent;
import org.camunda.bpm.model.bpmn.instance.ManualTask;
import org.camunda.bpm.model.bpmn.instance.ScriptTask;
import org.camunda.bpm.model.bpmn.instance.SendTask;
import org.camunda.bpm.model.bpmn.instance.SignalEventDefinition;
import org.camunda.bpm.model.bpmn.instance.SubProcess;
import org.junit.jupiter.api.Test;

// Verifies that TwinModelGenerator correctly processes, preserves,
// and outputs valid BPMN for ScriptTask, BusinessRuleTask, CallActivity, SubProcess, SendTask,
// ManualTask, IntermediateThrowEvent, and non-plain EndEvents.
class BpmnConstructCoverageTest {

    private final TwinModelGenerator generator = new TwinModelGenerator();

    @Test
    void scriptTaskIsPreservedWithScriptFormatAndScriptBody() {
        BpmnModelInstance model = readModel("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Def_Script" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_Script" isExecutable="true">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:scriptTask id="Task_Script" name="Compute Value" scriptFormat="juel">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:script>${execution.setVariable("computed", true)}</bpmn:script>
                    </bpmn:scriptTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_Script"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_Script" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """);

        BpmnModelInstance twin = generator.generate(model);

        ScriptTask scriptTask = twin.getModelElementById("Task_Script");
        assertThat(scriptTask).isNotNull();
        assertThat(scriptTask.getScriptFormat()).isEqualTo("juel");
        assertThat(scriptTask.getScript().getTextContent()).isEqualTo("${execution.setVariable(\"computed\", true)}");
        Bpmn.validateModel(twin);
    }

    @Test
    void businessRuleTaskIsPreservedWithDecisionRef() {
        BpmnModelInstance model = readModel("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Def_Rule" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_Rule" isExecutable="true">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:businessRuleTask id="Task_Rule" name="Evaluate Risk" camunda:decisionRef="riskDecision">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:businessRuleTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_Rule"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_Rule" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """);

        BpmnModelInstance twin = generator.generate(model);

        BusinessRuleTask ruleTask = twin.getModelElementById("Task_Rule");
        assertThat(ruleTask).isNotNull();
        assertThat(ruleTask.getCamundaDecisionRef()).isEqualTo("riskDecision");
        Bpmn.validateModel(twin);
    }

    @Test
    void callActivityIsPreservedWithCalledElement() {
        BpmnModelInstance model = readModel("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Def_Call" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_Call" isExecutable="true">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:callActivity id="Call_Child" name="Invoke Child Process" calledElement="childProcessId">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:callActivity>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Call_Child"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Call_Child" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """);

        BpmnModelInstance twin = generator.generate(model);

        CallActivity callActivity = twin.getModelElementById("Call_Child");
        assertThat(callActivity).isNotNull();
        assertThat(callActivity.getCalledElement()).isEqualTo("childProcessId");
        Bpmn.validateModel(twin);
    }

    @Test
    void sendTaskAndManualTaskArePreserved() {
        BpmnModelInstance model = readModel("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Def_Tasks" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_Tasks" isExecutable="true">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:sendTask id="Task_Send" name="Send Dispatch">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:sendTask>
                    <bpmn:manualTask id="Task_Manual" name="Manual Verification">
                      <bpmn:incoming>F2</bpmn:incoming>
                      <bpmn:outgoing>F3</bpmn:outgoing>
                    </bpmn:manualTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F3</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Task_Send"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Task_Send" targetRef="Task_Manual"/>
                    <bpmn:sequenceFlow id="F3" sourceRef="Task_Manual" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """);

        BpmnModelInstance twin = generator.generate(model);

        SendTask sendTask = twin.getModelElementById("Task_Send");
        assertThat(sendTask).isNotNull();
        ManualTask manualTask = twin.getModelElementById("Task_Manual");
        assertThat(manualTask).isNotNull();
        Bpmn.validateModel(twin);
    }

    @Test
    void intermediateThrowEventAndNonPlainEndEventArePreserved() {
        BpmnModelInstance model = readModel("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Def_Events" targetNamespace="http://metaml.com/test">
                  <bpmn:signal id="Signal_1" name="AlertSignal" />
                  <bpmn:error id="Error_1" name="ValidationError" errorCode="ERR_01" />
                  <bpmn:process id="Process_Events" isExecutable="true">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:intermediateThrowEvent id="Throw_Signal" name="Broadcast Alert">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:signalEventDefinition signalRef="Signal_1"/>
                    </bpmn:intermediateThrowEvent>
                    <bpmn:endEvent id="End_Error" name="Fatal Error">
                      <bpmn:incoming>F2</bpmn:incoming>
                      <bpmn:errorEventDefinition errorRef="Error_1"/>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Throw_Signal"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Throw_Signal" targetRef="End_Error"/>
                  </bpmn:process>
                </bpmn:definitions>
                """);

        BpmnModelInstance twin = generator.generate(model);

        IntermediateThrowEvent throwEvent = twin.getModelElementById("Throw_Signal");
        assertThat(throwEvent).isNotNull();
        assertThat(throwEvent.getEventDefinitions()).hasSize(1);
        assertThat(throwEvent.getEventDefinitions().iterator().next()).isInstanceOf(SignalEventDefinition.class);

        EndEvent errorEnd = twin.getModelElementById("End_Error");
        assertThat(errorEnd).isNotNull();
        assertThat(errorEnd.getEventDefinitions()).hasSize(1);
        assertThat(errorEnd.getEventDefinitions().iterator().next()).isInstanceOf(ErrorEventDefinition.class);

        Bpmn.validateModel(twin);
    }

    @Test
    void subProcessIsPreservedInTwinModel() {
        BpmnModelInstance model = readModel("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Def_Sub" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_Sub" isExecutable="true">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:subProcess id="Sub_Group" name="SubProcess Group">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                      <bpmn:startEvent id="SubStart"><bpmn:outgoing>SF1</bpmn:outgoing></bpmn:startEvent>
                      <bpmn:userTask id="SubTask" name="Nested Task"><bpmn:incoming>SF1</bpmn:incoming><bpmn:outgoing>SF2</bpmn:outgoing></bpmn:userTask>
                      <bpmn:endEvent id="SubEnd"><bpmn:incoming>SF2</bpmn:incoming></bpmn:endEvent>
                      <bpmn:sequenceFlow id="SF1" sourceRef="SubStart" targetRef="SubTask"/>
                      <bpmn:sequenceFlow id="SF2" sourceRef="SubTask" targetRef="SubEnd"/>
                    </bpmn:subProcess>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="Sub_Group"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="Sub_Group" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """);

        BpmnModelInstance twin = generator.generate(model);

        SubProcess subProcess = twin.getModelElementById("Sub_Group");
        assertThat(subProcess).isNotNull();
        Bpmn.validateModel(twin);
    }

    private static BpmnModelInstance readModel(String xml) {
        return Bpmn.readModelFromStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
