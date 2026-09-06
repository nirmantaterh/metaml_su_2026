package com.metaml.workbench.bpmn;

import static org.assertj.core.api.Assertions.assertThat;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnEdge;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnPlane;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnShape;
import org.junit.jupiter.api.Test;

// The generated Twin carries its own diagram interchange
// because the builder hands every DI element a fresh random id on every call, which broke
// enableDuplicateFiltering - relaunching the same model kept deploying a spurious new Twin
// definition version. stabilizeDiagramInterchange fixes that the same way the message/loop ids
// nearby already were fixed: derive each DI element's id from what it depicts instead of dropping
// it, so the Twin stays deterministic AND stays renderable in Cockpit.
class TwinModelGeneratorDiagramTest {

    private final TwinModelGenerator generator = new TwinModelGenerator();

    private static BpmnModelInstance twoActivityProcess() {
        return Bpmn.createExecutableProcess("proxyProcess")
                .startEvent("Start")
                .userTask("TaskA")
                .userTask("TaskB")
                .endEvent("End")
                .done();
    }

    @Test
    void theGeneratedTwinKeepsARenderableDiagramInsteadOfNone() {
        BpmnModelInstance twin = generator.generate(twoActivityProcess());

        assertThat(twin.getModelElementsByType(BpmnDiagram.class))
                .as("a Twin definition Cockpit can render needs at least one BPMNDiagram")
                .isNotEmpty();
        assertThat(twin.getModelElementsByType(BpmnPlane.class)).isNotEmpty();
        assertThat(twin.getModelElementsByType(BpmnShape.class))
                .as("every copied flow node should have gotten a shape")
                .hasSizeGreaterThanOrEqualTo(1);
        assertThat(twin.getModelElementsByType(BpmnEdge.class))
                .as("every copied sequence flow should have gotten an edge")
                .isNotEmpty();
    }

    @Test
    void everyShapeAndEdgeIdIsDerivedFromTheTwinElementItDepicts() {
        BpmnModelInstance twin = generator.generate(twoActivityProcess());

        for (BpmnShape shape : twin.getModelElementsByType(BpmnShape.class)) {
            assertThat(shape.getBpmnElement()).as("every shape must depict a real element").isNotNull();
            assertThat(shape.getId()).isEqualTo("BPMNShape_" + shape.getBpmnElement().getId());
        }
        for (BpmnEdge edge : twin.getModelElementsByType(BpmnEdge.class)) {
            assertThat(edge.getBpmnElement()).as("every edge must depict a real element").isNotNull();
            assertThat(edge.getId()).isEqualTo("BPMNEdge_" + edge.getBpmnElement().getId());
        }
    }

    // The actual defect this whole method exists to fix: repeated generate() calls on the SAME
    // input used to produce byte-different XML purely because of the diagram's own random ids,
    // which defeated Camunda's enableDuplicateFiltering and deployed a spurious new Twin version
    // on every relaunch of an unchanged model.
    @Test
    void regeneratingFromTheSameInputProducesByteIdenticalXml() {
        // The SAME already-built original, read from the SAME persisted XML twice - matching how a
        // real regenerate actually happens (from one saved model's bpmnXml, not two independent
        // fresh builds), so the original's own sequence-flow ids (also builder-assigned, and just
        // as random per build as the diagram this test is really about) are fixed across both calls.
        String originalXml = Bpmn.convertToString(twoActivityProcess());

        String first = Bpmn.convertToString(generator.generate(readModel(originalXml)));
        String second = Bpmn.convertToString(generator.generate(readModel(originalXml)));

        assertThat(second).isEqualTo(first);
    }

    private static BpmnModelInstance readModel(String xml) {
        return Bpmn.readModelFromStream(
                new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
