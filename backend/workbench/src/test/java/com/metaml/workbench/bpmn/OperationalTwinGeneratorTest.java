package com.metaml.workbench.bpmn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.camunda.bpm.model.bpmn.instance.Signal;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class OperationalTwinGeneratorTest {

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";

    @Test
    void aPlainSingleProcessBpmnWithNoSignalGatesYieldsNoOperationalTwin() {
        BpmnModelInstance model = readModel(loanApprovalBpmn());
        String twinXml = OperationalTwinGenerator.deriveTwinXml(model, "loanApproval");
        assertThat(twinXml).isNull();
    }

    @Test
    void aSignalGatedMainBpmnProducesADeployableTwinWithMatchingSignalNamesAndTwinTopics() {
        BpmnModelInstance model = readModel(signalGatedBpmn());
        String twinXml = OperationalTwinGenerator.deriveTwinXml(model, "GenericMain");

        assertThat(twinXml).isNotNull();

        // Must actually be valid, executable BPMN - not just a string that happens to contain XML.
        BpmnModelInstance twinModel = readModel(twinXml);
        assertThat(twinModel.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Process.class))
                .as("generated Twin must declare exactly one process")
                .hasSize(1);
        assertThat(twinModel.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Process.class)
                .iterator().next().isExecutable())
                .as("generated Twin process must be executable, or Camunda cannot deploy it")
                .isTrue();

        Set<String> twinSignalNames = twinModel.getModelElementsByType(Signal.class).stream()
                .map(Signal::getName)
                .collect(Collectors.toSet());
        assertThat(twinSignalNames)
                .as("Twin's own signal names must exactly match Main's gated signals, by name - "
                        + "SignalBroadcaster correlates only on name")
                .containsExactlyInAnyOrder("cutSignal", "sewSignal");

        List<Activity> externalTasks = twinModel.getModelElementsByType(Activity.class).stream()
                .filter(a -> "external".equals(a.getAttributeValueNs(CAMUNDA_NS, "type")))
                .toList();
        Set<String> twinTopics = externalTasks.stream()
                .map(a -> a.getAttributeValueNs(CAMUNDA_NS, "topic"))
                .collect(Collectors.toSet());
        assertThat(twinTopics)
                .as("generic 'append Twin' convention, not hard-coded to any one activity name")
                .containsExactlyInAnyOrder("CutTwin", "SewTwin");

        assertThat(twinModel.getModelElementsByType(IntermediateCatchEvent.class))
                .as("one catch event per gated signal")
                .hasSize(2);
    }

    @Test
    void aSecondUnrelatedSignalGatedBpmnAlsoGetsItsOwnOperationalTwinWithNoCodeChanges() {
        // Different domain, different activity/signal names entirely - verifies the derivation is
        // generic rather than shaped around one BPMN's vocabulary.
        String otherDomainBpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_other" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="WarehousePicking" isExecutable="true">
                    <bpmn2:startEvent id="Start">
                      <bpmn2:outgoing>Flow_toCatch</bpmn2:outgoing>
                    </bpmn2:startEvent>
                    <bpmn2:sequenceFlow id="Flow_toCatch" sourceRef="Start" targetRef="Catch_pack" />
                    <bpmn2:intermediateCatchEvent id="Catch_pack">
                      <bpmn2:incoming>Flow_toCatch</bpmn2:incoming>
                      <bpmn2:outgoing>Flow_toPack</bpmn2:outgoing>
                      <bpmn2:signalEventDefinition signalRef="Signal_pack" />
                    </bpmn2:intermediateCatchEvent>
                    <bpmn2:sequenceFlow id="Flow_toPack" sourceRef="Catch_pack" targetRef="Task_pack" />
                    <bpmn2:serviceTask id="Task_pack" name="Pack Order" camunda:type="external" camunda:topic="Pack">
                      <bpmn2:incoming>Flow_toPack</bpmn2:incoming>
                      <bpmn2:outgoing>Flow_toEnd</bpmn2:outgoing>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="Flow_toEnd" sourceRef="Task_pack" targetRef="End" />
                    <bpmn2:endEvent id="End">
                      <bpmn2:incoming>Flow_toEnd</bpmn2:incoming>
                    </bpmn2:endEvent>
                  </bpmn2:process>
                  <bpmn2:signal id="Signal_pack" name="packSignal" />
                </bpmn2:definitions>
                """;
        BpmnModelInstance model = readModel(otherDomainBpmn);
        String twinXml = OperationalTwinGenerator.deriveTwinXml(model, "WarehousePicking");

        assertThat(twinXml).isNotNull();
        BpmnModelInstance twinModel = readModel(twinXml);
        Set<String> twinTopics = twinModel.getModelElementsByType(Activity.class).stream()
                .filter(a -> "external".equals(a.getAttributeValueNs(CAMUNDA_NS, "type")))
                .map(a -> a.getAttributeValueNs(CAMUNDA_NS, "topic"))
                .collect(Collectors.toSet());
        assertThat(twinTopics).containsExactly("PackTwin");
    }

    // Skips gracefully when the fixture isn't present, same as the other RedCollar-dependent
    // tests. Main has ten gated activities, not nine - "Verify Order Details" has no counterpart
    // in the authored Twin, but that distinction isn't visible from Main's structure alone.
    @Test
    void theRealRedCollarManufBpmnProducesATwinTopicForEveryGatedActivity() throws Exception {
        Path dir = redCollarBpmnDir();
        Path manuf = dir.resolve("Manuf-camunda.bpmn");
        Assumptions.assumeTrue(Files.exists(manuf),
                "RedCollar Manuf-camunda.bpmn not found at " + manuf.toAbsolutePath() + " - skipping");

        String bpmnXml = Files.readString(manuf);
        BpmnModelInstance model = readModel(bpmnXml);
        String twinXml = OperationalTwinGenerator.deriveTwinXml(model, "RedCollar.Manuf");

        assertThat(twinXml).as("Main-camunda.bpmn has real signal-gated activities - must not be null").isNotNull();
        BpmnModelInstance twinModel = readModel(twinXml);
        Set<String> twinTopics = twinModel.getModelElementsByType(Activity.class).stream()
                .filter(a -> "external".equals(a.getAttributeValueNs(CAMUNDA_NS, "type")))
                .map(a -> a.getAttributeValueNs(CAMUNDA_NS, "topic"))
                .collect(Collectors.toSet());
        assertThat(twinTopics).containsExactlyInAnyOrder("SamplingTwin", "LayingTwin", "MarkingTwin", "CuttingTwin",
                "StitchingTwin", "CheckingTwin", "PressingTwin", "PackagingTwin", "ShippingTwin", "VerifyOrderTwin");
    }

    private static Path redCollarBpmnDir() {
        String configured = System.getProperty("redcollar.bpmn.dir", System.getenv("REDCOLLAR_BPMN_DIR"));
        return configured != null ? Path.of(configured) : Path.of("../..");
    }

    private static BpmnModelInstance readModel(String xml) {
        return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String loanApprovalBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="loanApproval" name="Loan Approval" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="ServiceTask_1" name="Calculate Interest"
                        camunda:delegateExpression="${calculateInterestService}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    // Two signal-gated external-task activities (Cut, Sew), shaped exactly like the pattern
    // RedCollar's own Main uses: a catch event immediately before each gated task.
    private static String signalGatedBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_gated" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="GenericMain" isExecutable="true">
                    <bpmn2:startEvent id="Start">
                      <bpmn2:outgoing>Flow_toCatch1</bpmn2:outgoing>
                    </bpmn2:startEvent>
                    <bpmn2:sequenceFlow id="Flow_toCatch1" sourceRef="Start" targetRef="Catch_cut" />
                    <bpmn2:intermediateCatchEvent id="Catch_cut">
                      <bpmn2:incoming>Flow_toCatch1</bpmn2:incoming>
                      <bpmn2:outgoing>Flow_toCut</bpmn2:outgoing>
                      <bpmn2:signalEventDefinition signalRef="Signal_cut" />
                    </bpmn2:intermediateCatchEvent>
                    <bpmn2:sequenceFlow id="Flow_toCut" sourceRef="Catch_cut" targetRef="Task_cut" />
                    <bpmn2:serviceTask id="Task_cut" name="Cut" camunda:type="external" camunda:topic="Cut">
                      <bpmn2:incoming>Flow_toCut</bpmn2:incoming>
                      <bpmn2:outgoing>Flow_toCatch2</bpmn2:outgoing>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="Flow_toCatch2" sourceRef="Task_cut" targetRef="Catch_sew" />
                    <bpmn2:intermediateCatchEvent id="Catch_sew">
                      <bpmn2:incoming>Flow_toCatch2</bpmn2:incoming>
                      <bpmn2:outgoing>Flow_toSew</bpmn2:outgoing>
                      <bpmn2:signalEventDefinition signalRef="Signal_sew" />
                    </bpmn2:intermediateCatchEvent>
                    <bpmn2:sequenceFlow id="Flow_toSew" sourceRef="Catch_sew" targetRef="Task_sew" />
                    <bpmn2:serviceTask id="Task_sew" name="Sew" camunda:type="external" camunda:topic="Sew">
                      <bpmn2:incoming>Flow_toSew</bpmn2:incoming>
                      <bpmn2:outgoing>Flow_toEnd</bpmn2:outgoing>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="Flow_toEnd" sourceRef="Task_sew" targetRef="End" />
                    <bpmn2:endEvent id="End">
                      <bpmn2:incoming>Flow_toEnd</bpmn2:incoming>
                    </bpmn2:endEvent>
                  </bpmn2:process>
                  <bpmn2:signal id="Signal_cut" name="cutSignal" />
                  <bpmn2:signal id="Signal_sew" name="sewSignal" />
                </bpmn2:definitions>
                """;
    }
}
