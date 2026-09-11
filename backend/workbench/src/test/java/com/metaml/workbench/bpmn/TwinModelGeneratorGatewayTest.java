package com.metaml.workbench.bpmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.ConditionExpression;
import org.camunda.bpm.model.bpmn.instance.EventBasedGateway;
import org.camunda.bpm.model.bpmn.instance.EventDefinition;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.camunda.bpm.model.bpmn.instance.MessageEventDefinition;
import org.camunda.bpm.model.bpmn.instance.ParallelGateway;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.TimerEventDefinition;
import org.junit.jupiter.api.Test;

/**
 * Tests gateway handling and validation in TwinModelGenerator.
 */
class TwinModelGeneratorGatewayTest {

    private final TwinModelGenerator generator = new TwinModelGenerator();

    // Split+join with conditions and a default flow.
    private static BpmnModelInstance inclusiveGatewayProcess() {
        return readModel("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_IG" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_IG" name="Inclusive Gateway Test" isExecutable="true">
                    <bpmn:startEvent id="Start">
                      <bpmn:outgoing>Flow_ToSplit</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:inclusiveGateway id="Gateway_Split" name="Split" default="Flow_A">
                      <bpmn:incoming>Flow_ToSplit</bpmn:incoming>
                      <bpmn:outgoing>Flow_A</bpmn:outgoing>
                      <bpmn:outgoing>Flow_B</bpmn:outgoing>
                    </bpmn:inclusiveGateway>
                    <bpmn:userTask id="Task_A" name="Branch A">
                      <bpmn:incoming>Flow_A</bpmn:incoming>
                      <bpmn:outgoing>Flow_A_Join</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:userTask id="Task_B" name="Branch B">
                      <bpmn:incoming>Flow_B</bpmn:incoming>
                      <bpmn:outgoing>Flow_B_Join</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:inclusiveGateway id="Gateway_Join" name="Join">
                      <bpmn:incoming>Flow_A_Join</bpmn:incoming>
                      <bpmn:incoming>Flow_B_Join</bpmn:incoming>
                      <bpmn:outgoing>Flow_End</bpmn:outgoing>
                    </bpmn:inclusiveGateway>
                    <bpmn:endEvent id="End">
                      <bpmn:incoming>Flow_End</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_ToSplit" sourceRef="Start" targetRef="Gateway_Split" />
                    <bpmn:sequenceFlow id="Flow_A" sourceRef="Gateway_Split" targetRef="Task_A" />
                    <bpmn:sequenceFlow id="Flow_B" sourceRef="Gateway_Split" targetRef="Task_B">
                      <bpmn:conditionExpression>${amount > 1000}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_A_Join" sourceRef="Task_A" targetRef="Gateway_Join" />
                    <bpmn:sequenceFlow id="Flow_B_Join" sourceRef="Task_B" targetRef="Gateway_Join" />
                    <bpmn:sequenceFlow id="Flow_End" sourceRef="Gateway_Join" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """);
    }

    @Test
    void inclusiveGatewayTwinContainsBothSplitAndJoinGateways() {
        BpmnModelInstance twin = generator.generate(inclusiveGatewayProcess());

        InclusiveGateway split = twin.getModelElementById("Gateway_Split");
        assertThat(split).as("split gateway must exist in twin").isNotNull();
        assertThat(split.getName()).isEqualTo("Split");

        InclusiveGateway join = twin.getModelElementById("Gateway_Join");
        assertThat(join).as("join gateway must exist in twin").isNotNull();
        assertThat(join.getName()).isEqualTo("Join");
    }

    @Test
    void inclusiveGatewayTwinPreservesIncomingAndOutgoingFlows() {
        BpmnModelInstance twin = generator.generate(inclusiveGatewayProcess());

        InclusiveGateway split = twin.getModelElementById("Gateway_Split");
        assertThat(split.getIncoming()).hasSize(1);
        assertThat(split.getOutgoing()).hasSize(2);

        InclusiveGateway join = twin.getModelElementById("Gateway_Join");
        assertThat(join.getIncoming()).hasSize(2);
        assertThat(join.getOutgoing()).hasSize(1);
    }

    @Test
    void inclusiveGatewayTwinPreservesConditions() {
        BpmnModelInstance twin = generator.generate(inclusiveGatewayProcess());

        SequenceFlow flowB = twin.getModelElementById("Flow_B");
        assertThat(flowB).isNotNull();
        ConditionExpression condition = flowB.getConditionExpression();
        assertThat(condition).as("condition on Flow_B must be copied").isNotNull();
        assertThat(condition.getTextContent()).isEqualTo("${amount > 1000}");
    }

    @Test
    void inclusiveGatewayTwinPreservesDefaultFlow() {
        BpmnModelInstance twin = generator.generate(inclusiveGatewayProcess());

        InclusiveGateway split = twin.getModelElementById("Gateway_Split");
        assertThat(split.getDefault()).as("default flow must be preserved").isNotNull();
        assertThat(split.getDefault().getId()).isEqualTo("Flow_A");
    }

    @Test
    void inclusiveGatewayTwinIsValidBpmn() {
        BpmnModelInstance twin = generator.generate(inclusiveGatewayProcess());
        // Bpmn.validateModel throws if the model is structurally invalid
        Bpmn.validateModel(twin);
    }

    // Event-based gateway followed by message and timer catch events.
    private static BpmnModelInstance eventBasedGatewayProcess() {
        return readModel("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_EBG" targetNamespace="http://metaml.com/test">
                  <bpmn:message id="Message_Approval" name="ApprovalMessage" />
                  <bpmn:process id="Process_EBG" name="Event-Based GW Test" isExecutable="true">
                    <bpmn:startEvent id="Start">
                      <bpmn:outgoing>Flow_ToGW</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Before" name="Prepare">
                      <bpmn:incoming>Flow_ToGW</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToEBG</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:eventBasedGateway id="Gateway_EBG" name="Wait for Event">
                      <bpmn:incoming>Flow_ToEBG</bpmn:incoming>
                      <bpmn:outgoing>Flow_ToMsg</bpmn:outgoing>
                      <bpmn:outgoing>Flow_ToTimer</bpmn:outgoing>
                    </bpmn:eventBasedGateway>
                    <bpmn:intermediateCatchEvent id="Catch_Message" name="Message Received">
                      <bpmn:incoming>Flow_ToMsg</bpmn:incoming>
                      <bpmn:outgoing>Flow_MsgEnd</bpmn:outgoing>
                      <bpmn:messageEventDefinition messageRef="Message_Approval" />
                    </bpmn:intermediateCatchEvent>
                    <bpmn:intermediateCatchEvent id="Catch_Timer" name="Timeout">
                      <bpmn:incoming>Flow_ToTimer</bpmn:incoming>
                      <bpmn:outgoing>Flow_TimerEnd</bpmn:outgoing>
                      <bpmn:timerEventDefinition>
                        <bpmn:timeDuration>PT30M</bpmn:timeDuration>
                      </bpmn:timerEventDefinition>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:endEvent id="End_Msg">
                      <bpmn:incoming>Flow_MsgEnd</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:endEvent id="End_Timer">
                      <bpmn:incoming>Flow_TimerEnd</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_ToGW" sourceRef="Start" targetRef="Task_Before" />
                    <bpmn:sequenceFlow id="Flow_ToEBG" sourceRef="Task_Before" targetRef="Gateway_EBG" />
                    <bpmn:sequenceFlow id="Flow_ToMsg" sourceRef="Gateway_EBG" targetRef="Catch_Message" />
                    <bpmn:sequenceFlow id="Flow_ToTimer" sourceRef="Gateway_EBG" targetRef="Catch_Timer" />
                    <bpmn:sequenceFlow id="Flow_MsgEnd" sourceRef="Catch_Message" targetRef="End_Msg" />
                    <bpmn:sequenceFlow id="Flow_TimerEnd" sourceRef="Catch_Timer" targetRef="End_Timer" />
                  </bpmn:process>
                </bpmn:definitions>
                """);
    }

    @Test
    void eventBasedGatewayTwinContainsTheGatewayWithCorrectId() {
        BpmnModelInstance twin = generator.generate(eventBasedGatewayProcess());

        EventBasedGateway gateway = twin.getModelElementById("Gateway_EBG");
        assertThat(gateway).as("event-based gateway must exist in twin").isNotNull();
        assertThat(gateway.getName()).isEqualTo("Wait for Event");
    }

    @Test
    void eventBasedGatewayTwinPreservesOutgoingFlowsToCatchEvents() {
        BpmnModelInstance twin = generator.generate(eventBasedGatewayProcess());

        EventBasedGateway gateway = twin.getModelElementById("Gateway_EBG");
        assertThat(gateway.getOutgoing()).hasSize(2);

        IntermediateCatchEvent msgCatch = twin.getModelElementById("Catch_Message");
        assertThat(msgCatch).isNotNull();
        assertThat(msgCatch.getName()).isEqualTo("Message Received");

        IntermediateCatchEvent timerCatch = twin.getModelElementById("Catch_Timer");
        assertThat(timerCatch).isNotNull();
        assertThat(timerCatch.getName()).isEqualTo("Timeout");
    }

    @Test
    void eventBasedGatewayTwinPreservesEventDefinitions() {
        BpmnModelInstance twin = generator.generate(eventBasedGatewayProcess());

        IntermediateCatchEvent msgCatch = twin.getModelElementById("Catch_Message");
        Collection<EventDefinition> msgDefs = msgCatch.getEventDefinitions();
        assertThat(msgDefs).as("message catch event must have an event definition").hasSize(1);
        assertThat(msgDefs.iterator().next()).isInstanceOf(MessageEventDefinition.class);

        IntermediateCatchEvent timerCatch = twin.getModelElementById("Catch_Timer");
        Collection<EventDefinition> timerDefs = timerCatch.getEventDefinitions();
        assertThat(timerDefs).as("timer catch event must have an event definition").hasSize(1);
        assertThat(timerDefs.iterator().next()).isInstanceOf(TimerEventDefinition.class);
    }

    @Test
    void eventBasedGatewayTwinIsValidBpmn() {
        BpmnModelInstance twin = generator.generate(eventBasedGatewayProcess());
        Bpmn.validateModel(twin);
    }

    // Complex gateways are unsupported in Camunda execution and must be rejected.
    private static BpmnModelInstance complexGatewayProcess() {
        return readModel("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_CG" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_CG" isExecutable="true">
                    <bpmn:startEvent id="Start">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:complexGateway id="Gateway_Complex">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:complexGateway>
                    <bpmn:endEvent id="End">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="Gateway_Complex" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Gateway_Complex" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """);
    }

    @Test
    void complexGatewayIsRejectedBecauseCamundaCannotExecuteIt() {
        assertThatThrownBy(() -> generator.generate(complexGatewayProcess()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Gateway_Complex")
                .hasMessageContaining("does not support");
    }

    @Test
    void exclusiveGatewayStillWorksAfterGatewayExpansion() {
        BpmnModelInstance original = Bpmn.createExecutableProcess("P")
                .startEvent("S")
                .exclusiveGateway("XGW")
                .userTask("T1").endEvent("E1")
                .moveToLastGateway()
                .userTask("T2").endEvent("E2")
                .done();

        BpmnModelInstance twin = generator.generate(original);

        assertThat((ExclusiveGateway) twin.getModelElementById("XGW")).isNotNull();
        Bpmn.validateModel(twin);
    }

    @Test
    void parallelGatewayStillWorksAfterGatewayExpansion() {
        BpmnModelInstance original = Bpmn.createExecutableProcess("P")
                .startEvent("S")
                .parallelGateway("PGW")
                .userTask("T1").endEvent("E1")
                .moveToLastGateway()
                .userTask("T2").endEvent("E2")
                .done();

        BpmnModelInstance twin = generator.generate(original);

        assertThat((ParallelGateway) twin.getModelElementById("PGW")).isNotNull();
        Bpmn.validateModel(twin);
    }

    // ---- Gateway/automation-output race (TwinModelGenerator investigation) ----
    //
    // A gateway immediately following a synchronized activity (UserTask/ReceiveTask/ServiceTask/bare
    // BusinessRuleTask) must be wired to leave from that activity's automation task, not its receive
    // task - otherwise the gateway becomes reachable concurrently with, rather than after, the
    // automation task that actually produces the activity's declared output.

    @Test
    void gatewayImmediatelyAfterAUserTaskExitsFromTheAutomationTaskNotTheReceiveTask() {
        BpmnModelInstance original = Bpmn.createExecutableProcess("P")
                .startEvent("S")
                .userTask("Task_A")
                .exclusiveGateway("GW")
                .condition("toEnd1", "${flagged}")
                .endEvent("E1")
                .moveToLastGateway()
                .condition("toEnd2", "${!flagged}")
                .endEvent("E2")
                .done();

        BpmnModelInstance twin = generator.generate(original);

        ExclusiveGateway gateway = twin.getModelElementById("GW");
        assertThat(gateway.getIncoming()).hasSize(1);
        SequenceFlow toGateway = gateway.getIncoming().iterator().next();
        assertThat(toGateway.getSource().getId())
                .as("the gateway must be reachable only once the automation task has actually run, "
                        + "not as soon as the receive task completes")
                .isEqualTo("Task_A_automate");
        Bpmn.validateModel(twin);
    }

    @Test
    void gatewayImmediatelyAfterAServiceTaskExitsFromTheAutomationTaskNotTheReceiveTask() {
        BpmnModelInstance original = Bpmn.createExecutableProcess("P")
                .startEvent("S")
                .serviceTask("Task_A")
                .exclusiveGateway("GW")
                .condition("toEnd1", "${flagged}")
                .endEvent("E1")
                .moveToLastGateway()
                .condition("toEnd2", "${!flagged}")
                .endEvent("E2")
                .done();

        BpmnModelInstance twin = generator.generate(original);

        ExclusiveGateway gateway = twin.getModelElementById("GW");
        SequenceFlow toGateway = gateway.getIncoming().iterator().next();
        assertThat(toGateway.getSource().getId()).isEqualTo("Task_A_automate");
        Bpmn.validateModel(twin);
    }

    // A gateway following an activity that is NOT converted into a receive/automation pair (here, a
    // plain exclusive gateway split with no intervening task at all) is unaffected by this fix - the
    // exit id is still the node's own id.
    @Test
    void gatewayNotFollowingASynchronizedActivityIsUnaffected() {
        BpmnModelInstance original = Bpmn.createExecutableProcess("P")
                .startEvent("S")
                .exclusiveGateway("GW")
                .condition("toEnd1", "${flagged}")
                .endEvent("E1")
                .moveToLastGateway()
                .condition("toEnd2", "${!flagged}")
                .endEvent("E2")
                .done();

        BpmnModelInstance twin = generator.generate(original);

        ExclusiveGateway gateway = twin.getModelElementById("GW");
        SequenceFlow toGateway = gateway.getIncoming().iterator().next();
        assertThat(toGateway.getSource().getId()).isEqualTo("S");
    }

    private static BpmnModelInstance readModel(String xml) {
        return Bpmn.readModelFromStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
