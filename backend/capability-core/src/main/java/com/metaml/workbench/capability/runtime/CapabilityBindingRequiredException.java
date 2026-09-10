package com.metaml.workbench.capability.runtime;

// Thrown by generated activity code when a BPMN model's OWN gateway/data-association demand
// (BpmnCapabilityContractReader's derived required-output set - never a process-specific flag)
// requires this activity to produce capability output, but no provider is bound to it: neither the
// per-visit evolvedAgent_*/evolvedAgentType_* process variables CapabilityDispatcher reads first, nor
// (when supplied) a CapabilityBindingCache fallback resolved anything for this activity.
//
// This is the explicit-failure half of the same policy CapabilityDispatcher.dispatch() already
// documents for a missing binding: "nothing is bound" is harmless for an activity that requires
// nothing, and must never be silently treated as harmless for one whose downstream control flow
// depends on it. Deciding which of those two an activity is stays a BPMN question, answered by the
// caller (the generated worker, from the deployed model) - this exception is what the caller raises
// once it has already decided the answer is the second one. It must never be swallowed into a
// fabricated result or a fallback that was never meant to govern this activity.
public class CapabilityBindingRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CapabilityBindingRequiredException(String activityId) {
        super("Activity '" + activityId + "' requires a capability binding (its declared output is read "
                + "by downstream BPMN control flow) but none could be resolved - neither a bound process "
                + "variable nor a cached Workbench-authoritative binding. Refusing to execute a fallback "
                + "that was never approved to govern this activity.");
    }
}
