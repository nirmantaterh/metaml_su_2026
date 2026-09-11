package com.metaml.workbench.capability.gap;

import static org.assertj.core.api.Assertions.assertThat;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

// MetaML Scope 6, Phase 5, test category B: static BPMN gap detection. An independently invented
// domain (invoice validation) with its own activity/variable names - never RedCollar's (section 21).
class StaticCapabilityGapDetectorTest {

    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn2:process id="invoiceValidationProcess" isExecutable="true">
                <bpmn2:startEvent id="Start" />
                <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="ValidateInvoice" />
                <bpmn2:serviceTask id="ValidateInvoice" name="Validate Invoice"
                    camunda:type="external" camunda:topic="ValidateInvoice" />
                <bpmn2:sequenceFlow id="f2" sourceRef="ValidateInvoice" targetRef="GW1" />
                <bpmn2:exclusiveGateway id="GW1">
                  <bpmn2:incoming>f2</bpmn2:incoming>
                  <bpmn2:outgoing>f3</bpmn2:outgoing>
                  <bpmn2:outgoing>f4</bpmn2:outgoing>
                </bpmn2:exclusiveGateway>
                <bpmn2:sequenceFlow id="f3" sourceRef="GW1" targetRef="End1">
                  <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${riskScore}</bpmn2:conditionExpression>
                </bpmn2:sequenceFlow>
                <bpmn2:sequenceFlow id="f4" sourceRef="GW1" targetRef="End2">
                  <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!riskScore}</bpmn2:conditionExpression>
                </bpmn2:sequenceFlow>
                <bpmn2:endEvent id="End1" />
                <bpmn2:endEvent id="End2" />
              </bpmn2:process>
            </bpmn2:definitions>
            """;

    private static BpmnModelInstance model() {
        return Bpmn.readModelFromStream(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void staticGapDetectedWhenNoProducerAndNoProviderSatisfyTheGatewayVariable() {
        List<CapabilityGap> gaps = StaticCapabilityGapDetector.detect(model(), "invoiceValidationProcess",
                List.of(), Instant.now());

        assertThat(gaps).hasSize(1);
        CapabilityGap gap = gaps.get(0);
        assertThat(gap.activityId()).isEqualTo("ValidateInvoice");
        assertThat(gap.origin()).isEqualTo(GapOrigin.STATIC_MODEL);
        assertThat(gap.status()).isEqualTo(GapStatus.OPEN);
        assertThat(gap.requiredContract().producedOutputs())
                .extracting(IoDeclaration::name)
                .containsExactly("riskScore");
        // No business value anywhere on the gap (section 7).
        assertThat(gap.availableInputs()).doesNotContainValue(null);
    }

    @Test
    void noGapWhenADataOutputAssociationAlreadyDeclaresTheOutput() {
        String xmlWithDeclaredOutput = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:itemDefinition id="_riskScoreItem" structureRef="String" />
                  <bpmn2:process id="invoiceValidationProcess" isExecutable="true">
                    <bpmn2:property id="riskScore" itemSubjectRef="_riskScoreItem" name="riskScore" />
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="ValidateInvoice" />
                    <bpmn2:serviceTask id="ValidateInvoice" name="Validate Invoice"
                        camunda:type="external" camunda:topic="ValidateInvoice">
                      <bpmn2:dataOutputAssociation id="doa1">
                        <bpmn2:targetRef>riskScore</bpmn2:targetRef>
                      </bpmn2:dataOutputAssociation>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="f2" sourceRef="ValidateInvoice" targetRef="GW1" />
                    <bpmn2:exclusiveGateway id="GW1">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="GW1" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${riskScore}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="GW1" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!riskScore}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
        BpmnModelInstance declaredModel =
                Bpmn.readModelFromStream(new ByteArrayInputStream(xmlWithDeclaredOutput.getBytes(StandardCharsets.UTF_8)));

        List<CapabilityGap> gaps = StaticCapabilityGapDetector.detect(declaredModel, "invoiceValidationProcess",
                List.of(), Instant.now());

        assertThat(gaps).isEmpty();
    }

    @Test
    void noGapWhenASatisfyingProviderAlreadyExistsInTheCatalog() {
        CapabilityProvider provider = new CapabilityProvider("risk-scorer-01", "risk-scorer", "1.0.0",
                new CapabilityContract(null, Set.of(), Set.of(new IoDeclaration("riskScore", IoType.UNKNOWN, true)),
                        ExecutionMode.SYNCHRONOUS, Map.of(), Set.of()),
                "synthetic risk scorer", true, null);

        List<CapabilityGap> gaps = StaticCapabilityGapDetector.detect(model(), "invoiceValidationProcess",
                List.of(provider), Instant.now());

        assertThat(gaps).isEmpty();
    }

    @Test
    void unavailableProviderStillOpensAGap() {
        CapabilityProvider unavailable = new CapabilityProvider("risk-scorer-01", "risk-scorer", "1.0.0",
                new CapabilityContract(null, Set.of(), Set.of(new IoDeclaration("riskScore", IoType.UNKNOWN, true)),
                        ExecutionMode.SYNCHRONOUS, Map.of(), Set.of()),
                "synthetic risk scorer", false, "offline for maintenance");

        List<CapabilityGap> gaps = StaticCapabilityGapDetector.detect(model(), "invoiceValidationProcess",
                List.of(unavailable), Instant.now());

        assertThat(gaps).hasSize(1);
    }

    @Test
    void repeatedDetectionOverTheSameModelIsDeterministic() {
        List<CapabilityGap> first = StaticCapabilityGapDetector.detect(model(), "invoiceValidationProcess",
                List.of(), Instant.now());
        List<CapabilityGap> second = StaticCapabilityGapDetector.detect(model(), "invoiceValidationProcess",
                List.of(), Instant.now());

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first.get(0).gapId()).isEqualTo(second.get(0).gapId());
    }
}
