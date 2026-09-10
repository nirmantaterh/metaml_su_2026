package com.metaml.workbench.bpmn;

import static org.assertj.core.api.Assertions.assertThat;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnEdge;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnPlane;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnShape;
import org.junit.jupiter.api.Test;

// Verifies that generated Twin diagram interchange elements have deterministic IDs
// derived from depicted model elements, preventing spurious redeployments in Camunda.
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

    // Diagram element IDs must be deterministic across regenerations to support duplicate filtering.
    @Test
    void regeneratingFromTheSameInputProducesByteIdenticalXml() {
        // Re-parses original model from XML to hold sequence-flow IDs stable across calls.
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
