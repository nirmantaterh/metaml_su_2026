package com.metaml.workbench.codegen;

// Represents a generated external task worker subscribed to a Camunda fetch-and-lock topic.
//
// activityId is the BPMN element id of the (first, per ExternalTaskWorkerGenerator's existing
// topic-dedup convention) activity that established this worker - the identity P7 Step 5's capability
// dispatch is keyed by, distinct from topic (an external-task routing concept, not a BPMN identity).
// Blank for the synthetic GatewayOutputProvider interface entry, which names no BPMN activity at all.
public record GeneratedWorker(String className, String topic, String activityId, String sourceCode) {
}
