package com.metaml.workbench.bpmn;

import java.util.ArrayList;
import java.util.List;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.builder.AbstractFlowNodeBuilder;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.EventDefinition;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.SignalEventDefinition;

// Derives the operational twin definition for runtime RabbitMQ communication from signal-gated external tasks.
public final class OperationalTwinGenerator {

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";

    private OperationalTwinGenerator() {
    }

    private record Gate(String signalName, String sourceTopic, String sourceActivityName) {
    }

    // Returns null when mainModel has no gates - not an error.
    public static String deriveTwinXml(BpmnModelInstance mainModel, String mainProcessKey) {
        List<Gate> gates = findGates(mainModel);
        if (gates.isEmpty()) {
            return null;
        }

        String twinProcessKey = mainProcessKey + "_twin";
        AbstractFlowNodeBuilder<?, ?> builder = Bpmn.createExecutableProcess(twinProcessKey)
                .name("Twin")
                .startEvent("start");

        int index = 0;
        for (Gate gate : gates) {
            String slug = sanitize(gate.sourceTopic()) + "_" + index;
            builder = builder
                    .intermediateCatchEvent("catch_" + slug)
                    .signal(gate.signalName())
                    .serviceTask("task_" + slug)
                    .name(gate.sourceActivityName() + " Twin")
                    .camundaType("external")
                    .camundaTopic(deriveTwinTopic(gate.sourceTopic()));
            index++;
        }

        BpmnModelInstance twinModel = builder.endEvent("end").done();
        return Bpmn.convertToString(twinModel);
    }

    // e.g. "Sampling" -> "SamplingTwin".
    private static String deriveTwinTopic(String sourceTopic) {
        return sourceTopic + "Twin";
    }

    private static String sanitize(String raw) {
        String slug = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9]+", "_");
        return slug.isBlank() ? "activity" : slug;
    }

    // Every intermediateCatchEvent+signalEventDefinition whose outgoing flow leads directly into a camunda:type="external" activity, in document order.
    private static List<Gate> findGates(BpmnModelInstance mainModel) {
        List<Gate> gates = new ArrayList<>();
        for (IntermediateCatchEvent catchEvent : mainModel.getModelElementsByType(IntermediateCatchEvent.class)) {
            String signalName = null;
            for (EventDefinition definition : catchEvent.getEventDefinitions()) {
                if (definition instanceof SignalEventDefinition signalDefinition
                        && signalDefinition.getSignal() != null) {
                    signalName = signalDefinition.getSignal().getName();
                }
            }
            if (signalName == null || signalName.isBlank()) {
                continue;
            }
            for (SequenceFlow flow : mainModel.getModelElementsByType(SequenceFlow.class)) {
                if (!catchEvent.equals(flow.getSource())) {
                    continue;
                }
                FlowNode target = flow.getTarget();
                if (target instanceof Activity activity
                        && "external".equals(activity.getAttributeValueNs(CAMUNDA_NS, "type"))) {
                    String topic = activity.getAttributeValueNs(CAMUNDA_NS, "topic");
                    String activityName = activity.getName() != null ? activity.getName() : topic;
                    if (topic != null && !topic.isBlank()) {
                        gates.add(new Gate(signalName, topic, activityName));
                    }
                }
            }
        }
        return gates;
    }
}
