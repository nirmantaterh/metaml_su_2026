package com.metaml.workbench.bpmn;

import static org.assertj.core.api.Assertions.assertThat;

import com.metaml.workbench.bpmn.BpmnCapabilityContractReader.ActivityCapabilityDerivation;
import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

// Focused unit tests for BpmnCapabilityContractReader (MetaML Scope 6, Phase 1).
//
// Every fixture below uses an independently invented domain (widget inspection) with its own
// activity and variable names - never RedCollar's names ("orderApproved" included), proving the
// reader is generic BPMN interpretation rather than a RedCollar-specific rule engine. A separate
// section at the bottom runs the same reader against the real RedCollar fixtures purely as a
// regression/sanity check; it asserts no RedCollar-specific behavior.
class BpmnCapabilityContractReaderTest {

    private static BpmnModelInstance parse(String xml) {
        return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Activity activity(BpmnModelInstance model, String id) {
        return (Activity) model.getModelElementById(id);
    }

    // ---- Gateway requirements (design lock section 15, items 1-4) ----

    @Test
    void gatewayConditionOnUndeclaredOutputIsRequiredAndUnsatisfied() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:drools="http://www.jboss.org/drools"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="widgetInspectionProcess" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="InspectWidget" />
                    <bpmn2:serviceTask id="InspectWidget" name="Inspect Widget"
                        camunda:type="external" camunda:topic="InspectWidget">
                      <bpmn2:ioSpecification id="io1">
                        <bpmn2:dataInput id="di1" name="batchId" drools:dtype="String" />
                        <bpmn2:inputSet>
                          <bpmn2:dataInputRefs>di1</bpmn2:dataInputRefs>
                        </bpmn2:inputSet>
                        <bpmn2:outputSet />
                      </bpmn2:ioSpecification>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="f2" sourceRef="InspectWidget" targetRef="GW1" />
                    <bpmn2:exclusiveGateway id="GW1">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="GW1" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${widgetGrade}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="GW1" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!widgetGrade}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
        BpmnModelInstance model = parse(xml);

        ActivityCapabilityDerivation derivation =
                BpmnCapabilityContractReader.derive(model, activity(model, "InspectWidget"));

        assertThat(derivation.requiredOutputs()).containsExactly("widgetGrade");
        assertThat(derivation.declaredOutputs()).isEmpty();
        assertThat(derivation.unsatisfiedOutputs()).containsExactly("widgetGrade");

        // Unrelated required input (item 4: "the activity's unrelated inputs/outputs remain intact")
        assertThat(derivation.contract().requiredInputs())
                .containsExactly(new IoDeclaration("batchId", IoType.STRING, true));
        assertThat(derivation.contract().producedOutputs()).isEmpty();
    }

    // ---- Declared output via dataOutputAssociation (design lock section 15, items 5-7) ----

    @Test
    void dataOutputAssociationSatisfiesTheMatchingGatewayRequirement() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:itemDefinition id="_widgetGradeItem" structureRef="String" />
                  <bpmn2:process id="widgetClassificationProcess" isExecutable="true">
                    <bpmn2:property id="widgetGrade" itemSubjectRef="_widgetGradeItem" name="widgetGrade" />
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="ClassifyWidget" />
                    <bpmn2:serviceTask id="ClassifyWidget" name="Classify Widget"
                        camunda:type="external" camunda:topic="ClassifyWidget">
                      <bpmn2:dataOutputAssociation id="doa1">
                        <bpmn2:targetRef>widgetGrade</bpmn2:targetRef>
                      </bpmn2:dataOutputAssociation>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="f2" sourceRef="ClassifyWidget" targetRef="GW1" />
                    <bpmn2:exclusiveGateway id="GW1">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="GW1" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${widgetGrade}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="GW1" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!widgetGrade}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
        BpmnModelInstance model = parse(xml);

        ActivityCapabilityDerivation derivation =
                BpmnCapabilityContractReader.derive(model, activity(model, "ClassifyWidget"));

        assertThat(derivation.declaredOutputs()).containsExactly("widgetGrade");
        assertThat(derivation.requiredOutputs()).containsExactly("widgetGrade");
        assertThat(derivation.unsatisfiedOutputs()).isEmpty();
        assertThat(derivation.contract().producedOutputs())
                .containsExactly(new IoDeclaration("widgetGrade", IoType.STRING, true));
    }

    // ---- metaml:agentOutputs (design lock section 15, items 8-9) ----

    @Test
    void metamlAgentOutputSatisfiesTheMatchingGatewayRequirement() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:metaml="http://metaml.com/schema/bpmn/metaml"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="widgetReviewProcess" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="ReviewWidget" />
                    <bpmn2:userTask id="ReviewWidget" name="Review Widget">
                      <bpmn2:extensionElements>
                        <metaml:agentOutputs>
                          <metaml:agentOutput name="flaggedForReview" variable="flaggedForReview" />
                        </metaml:agentOutputs>
                      </bpmn2:extensionElements>
                    </bpmn2:userTask>
                    <bpmn2:sequenceFlow id="f2" sourceRef="ReviewWidget" targetRef="GW1" />
                    <bpmn2:exclusiveGateway id="GW1">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="GW1" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${flaggedForReview}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="GW1" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!flaggedForReview}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
        BpmnModelInstance model = parse(xml);

        ActivityCapabilityDerivation derivation =
                BpmnCapabilityContractReader.derive(model, activity(model, "ReviewWidget"));

        assertThat(derivation.declaredOutputs()).containsExactly("flaggedForReview");
        assertThat(derivation.requiredOutputs()).containsExactly("flaggedForReview");
        assertThat(derivation.unsatisfiedOutputs()).isEmpty();
        assertThat(derivation.contract().producedOutputs())
                .containsExactly(new IoDeclaration("flaggedForReview", IoType.UNKNOWN, true));
    }

    // ---- Inputs (design lock section 15, items 10-12) ----

    @Test
    void ioSpecificationInputsAreDerivedAndDroolsBoilerplateIsExcluded() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:drools="http://www.jboss.org/drools"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="widgetBatchProcess" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="RecordBatch" />
                    <bpmn2:userTask id="RecordBatch" name="Record Batch">
                      <bpmn2:ioSpecification id="io1">
                        <bpmn2:dataInput id="di1" name="batchId" drools:dtype="String" />
                        <bpmn2:dataInput id="di2" name="TaskName" drools:dtype="Object" />
                        <bpmn2:dataInput id="di3" name="Skippable" drools:dtype="Object" />
                        <bpmn2:dataInput id="di4" name="GroupId" drools:dtype="Object" />
                        <bpmn2:dataInput id="di5" name="Priority" drools:dtype="Object" />
                        <bpmn2:dataInput id="di6" name="Comment" drools:dtype="Object" />
                        <bpmn2:dataInput id="di7" name="Content" drools:dtype="Object" />
                        <bpmn2:dataInput id="di8" name="Locale" drools:dtype="Object" />
                        <bpmn2:dataInput id="di9" name="CreatedBy" drools:dtype="Object" />
                        <bpmn2:dataInput id="di10" name="NotStartedNotify" drools:dtype="Object" />
                        <bpmn2:dataInput id="di11" name="NotCompletedNotify" drools:dtype="Object" />
                        <bpmn2:dataInput id="di12" name="NotStartedReassign" drools:dtype="Object" />
                        <bpmn2:dataInput id="di13" name="NotCompletedReassign" drools:dtype="Object" />
                        <bpmn2:inputSet>
                          <bpmn2:dataInputRefs>di1</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di2</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di3</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di4</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di5</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di6</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di7</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di8</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di9</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di10</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di11</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di12</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di13</bpmn2:dataInputRefs>
                        </bpmn2:inputSet>
                        <bpmn2:outputSet />
                      </bpmn2:ioSpecification>
                    </bpmn2:userTask>
                    <bpmn2:sequenceFlow id="f2" sourceRef="RecordBatch" targetRef="End" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
        BpmnModelInstance model = parse(xml);

        ActivityCapabilityDerivation derivation =
                BpmnCapabilityContractReader.derive(model, activity(model, "RecordBatch"));

        assertThat(derivation.contract().requiredInputs())
                .containsExactly(new IoDeclaration("batchId", IoType.STRING, true));
    }

    @Test
    void camundaInputOutputParametersAreDerivedAsRequiredInputs() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="widgetAssignmentProcess" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="AssignOperator" />
                    <bpmn2:serviceTask id="AssignOperator" name="Assign Operator"
                        camunda:type="external" camunda:topic="AssignOperator">
                      <bpmn2:extensionElements>
                        <camunda:inputOutput>
                          <camunda:inputParameter name="operatorId">${operatorId}</camunda:inputParameter>
                        </camunda:inputOutput>
                      </bpmn2:extensionElements>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="f2" sourceRef="AssignOperator" targetRef="End" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
        BpmnModelInstance model = parse(xml);

        ActivityCapabilityDerivation derivation =
                BpmnCapabilityContractReader.derive(model, activity(model, "AssignOperator"));

        assertThat(derivation.contract().requiredInputs())
                .containsExactly(new IoDeclaration("operatorId", IoType.UNKNOWN, true));
    }

    // ---- Type mapping (design lock section 15, items 13-17) ----

    @Test
    void droolsDtypeIsMappedPerTheLockedTypeTable() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:drools="http://www.jboss.org/drools"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="widgetTypeCheckProcess" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="TypeCheckTask" />
                    <bpmn2:userTask id="TypeCheckTask" name="Type Check">
                      <bpmn2:ioSpecification id="io1">
                        <bpmn2:dataInput id="di1" name="isDefective" drools:dtype="Boolean" />
                        <bpmn2:dataInput id="di2" name="unitCount" drools:dtype="Integer" />
                        <bpmn2:dataInput id="di3" name="batchSize" drools:dtype="Long" />
                        <bpmn2:dataInput id="di4" name="tolerance" drools:dtype="Float" />
                        <bpmn2:dataInput id="di5" name="weight" drools:dtype="Double" />
                        <bpmn2:dataInput id="di6" name="rank" drools:dtype="Short" />
                        <bpmn2:dataInput id="di7" name="unitPrice" drools:dtype="BigDecimal" />
                        <bpmn2:dataInput id="di8" name="labelText" drools:dtype="String" />
                        <bpmn2:dataInput id="di9" name="payload" drools:dtype="Object" />
                        <bpmn2:dataInput id="di10" name="unspecifiedType" />
                        <bpmn2:dataInput id="di11" name="mysteryType" drools:dtype="SomethingElse" />
                        <bpmn2:inputSet>
                          <bpmn2:dataInputRefs>di1</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di2</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di3</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di4</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di5</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di6</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di7</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di8</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di9</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di10</bpmn2:dataInputRefs>
                          <bpmn2:dataInputRefs>di11</bpmn2:dataInputRefs>
                        </bpmn2:inputSet>
                        <bpmn2:outputSet />
                      </bpmn2:ioSpecification>
                    </bpmn2:userTask>
                    <bpmn2:sequenceFlow id="f2" sourceRef="TypeCheckTask" targetRef="End" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
        BpmnModelInstance model = parse(xml);

        ActivityCapabilityDerivation derivation =
                BpmnCapabilityContractReader.derive(model, activity(model, "TypeCheckTask"));

        assertThat(derivation.contract().requiredInputs()).containsExactlyInAnyOrder(
                new IoDeclaration("isDefective", IoType.BOOLEAN, true),
                new IoDeclaration("unitCount", IoType.NUMBER, true),
                new IoDeclaration("batchSize", IoType.NUMBER, true),
                new IoDeclaration("tolerance", IoType.NUMBER, true),
                new IoDeclaration("weight", IoType.NUMBER, true),
                new IoDeclaration("rank", IoType.NUMBER, true),
                new IoDeclaration("unitPrice", IoType.NUMBER, true),
                new IoDeclaration("labelText", IoType.STRING, true),
                new IoDeclaration("payload", IoType.STRUCT, true),
                new IoDeclaration("unspecifiedType", IoType.UNKNOWN, true),
                new IoDeclaration("mysteryType", IoType.UNKNOWN, true));
    }

    // ---- Execution mode (design lock section 11) ----

    @Test
    void externalTaskTypeIsAsynchronousEverythingElseIsSynchronous() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="widgetModeProcess" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="ExternalStep" />
                    <bpmn2:serviceTask id="ExternalStep" name="External Step"
                        camunda:type="external" camunda:topic="ExternalStep" />
                    <bpmn2:sequenceFlow id="f2" sourceRef="ExternalStep" targetRef="InlineStep" />
                    <bpmn2:userTask id="InlineStep" name="Inline Step" />
                    <bpmn2:sequenceFlow id="f3" sourceRef="InlineStep" targetRef="End" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
        BpmnModelInstance model = parse(xml);

        assertThat(BpmnCapabilityContractReader.derive(model, activity(model, "ExternalStep"))
                .contract().executionMode()).isEqualTo(ExecutionMode.ASYNCHRONOUS);
        assertThat(BpmnCapabilityContractReader.derive(model, activity(model, "InlineStep"))
                .contract().executionMode()).isEqualTo(ExecutionMode.SYNCHRONOUS);
    }

    // ---- Genericity and determinism (design lock section 15, items 18-22) ----

    @Test
    void deriveAllCoversEveryActivityAndNeverMentionsRedCollarNames() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="widgetMultiStepProcess" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="StepOne" />
                    <bpmn2:serviceTask id="StepOne" name="Step One"
                        camunda:type="external" camunda:topic="StepOne" />
                    <bpmn2:sequenceFlow id="f2" sourceRef="StepOne" targetRef="StepTwo" />
                    <bpmn2:userTask id="StepTwo" name="Step Two" />
                    <bpmn2:sequenceFlow id="f3" sourceRef="StepTwo" targetRef="End" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
        BpmnModelInstance model = parse(xml);

        Map<String, ActivityCapabilityDerivation> all = BpmnCapabilityContractReader.deriveAll(model);

        assertThat(all).containsOnlyKeys("StepOne", "StepTwo");
        // No hardcoded business names, no orderApproved special case: an activity with no
        // BPMN-declared inputs/outputs and no successor gateway derives to an empty contract.
        for (ActivityCapabilityDerivation derivation : all.values()) {
            assertThat(derivation.contract().requiredInputs()).isEmpty();
            assertThat(derivation.contract().producedOutputs()).isEmpty();
            assertThat(derivation.requiredOutputs()).isEmpty();
            assertThat(derivation.unsatisfiedOutputs()).isEmpty();
            assertThat(derivation.contract().capabilityId()).isNull();
            assertThat(derivation.contract().constraints()).isEmpty();
            assertThat(derivation.contract().governanceLabels()).isEmpty();
        }
    }

    @Test
    void repeatedDerivationOfTheSameModelIsDeterministic() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:itemDefinition id="_widgetGradeItem" structureRef="String" />
                  <bpmn2:process id="widgetClassificationProcess" isExecutable="true">
                    <bpmn2:property id="widgetGrade" itemSubjectRef="_widgetGradeItem" name="widgetGrade" />
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="ClassifyWidget" />
                    <bpmn2:serviceTask id="ClassifyWidget" name="Classify Widget"
                        camunda:type="external" camunda:topic="ClassifyWidget">
                      <bpmn2:dataOutputAssociation id="doa1">
                        <bpmn2:targetRef>widgetGrade</bpmn2:targetRef>
                      </bpmn2:dataOutputAssociation>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="f2" sourceRef="ClassifyWidget" targetRef="GW1" />
                    <bpmn2:exclusiveGateway id="GW1">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="GW1" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${widgetGrade}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="GW1" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!widgetGrade}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
        BpmnModelInstance model = parse(xml);

        Map<String, ActivityCapabilityDerivation> first = BpmnCapabilityContractReader.deriveAll(model);
        Map<String, ActivityCapabilityDerivation> second = BpmnCapabilityContractReader.deriveAll(model);

        assertThat(second).isEqualTo(first);
        CapabilityContract contract = first.get("ClassifyWidget").contract();
        assertThat(BpmnCapabilityContractReader.derive(model, activity(model, "ClassifyWidget")).contract())
                .isEqualTo(contract);
    }

    // ---- Real RedCollar fixtures: regression only, no RedCollar-specific behavior asserted ----
    //
    // These two gaps are real: RedCollar's own Manuf-camunda.bpmn references ${orderApproved} and
    // ${qualityPassed} in gateway conditions but never declares either one via a
    // dataOutputAssociation or metaml:agentOutputs anywhere in the file. The reader surfaces that
    // structural fact generically - it does not special-case these names to produce it.

    private static Path redCollarBpmnDir() {
        String configured = System.getProperty("redcollar.bpmn.dir", System.getenv("REDCOLLAR_BPMN_DIR"));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("../..");
    }

    private static void assumeFixturesPresent() {
        Assumptions.assumeTrue(Files.isRegularFile(redCollarBpmnDir().resolve("Manuf-camunda.bpmn")),
                "RedCollar BPMN not found - configure redcollar.bpmn.dir or REDCOLLAR_BPMN_DIR");
    }

    @Test
    void realManufBpmnSurfacesItsOwnUndeclaredGatewayVariablesAsUnsatisfied() throws Exception {
        assumeFixturesPresent();
        String manufBpmn = Files.readString(redCollarBpmnDir().resolve("Manuf-camunda.bpmn"));
        BpmnModelInstance model = parse(manufBpmn);

        Map<String, ActivityCapabilityDerivation> all = BpmnCapabilityContractReader.deriveAll(model);

        Activity verifyOrder = findByTopic(model, "VerifyOrder");
        Activity checking = findByTopic(model, "Checking");

        assertThat(all.get(verifyOrder.getId()).unsatisfiedOutputs()).contains("orderApproved");
        assertThat(all.get(checking.getId()).unsatisfiedOutputs()).contains("qualityPassed");
    }

    private static Activity findByTopic(BpmnModelInstance model, String topic) {
        return model.getModelElementsByType(Activity.class).stream()
                .filter(a -> topic.equals(a.getAttributeValueNs(
                        "http://camunda.org/schema/1.0/bpmn", "topic")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No activity with topic " + topic));
    }
}
