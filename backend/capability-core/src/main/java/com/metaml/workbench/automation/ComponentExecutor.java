package com.metaml.workbench.automation;

import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;

import java.util.Set;

/** Pluggable executable component contract for evolved Twin Process activities. */
public interface ComponentExecutor {

    /**
     * The primary authoritative agent type this executor handles (e.g. "credit-risk-assessor", "validator").
     */
    String getHandledAgentType();

    /**
     * The exact set of agent names resolved by Node Manager that this executor handles (e.g. Set.of("credit-risk-agent-01")).
     */
    default Set<String> getHandledAgentNames() {
        return Set.of();
    }

    /** Evaluates case-insensitive exact equality against handled types/names; fuzzy matching is prohibited. */
    default boolean handles(String agentTypeOrName) {
        if (agentTypeOrName == null || agentTypeOrName.isBlank()) {
            return false;
        }
        String trimmed = agentTypeOrName.trim();
        if (trimmed.equalsIgnoreCase(getHandledAgentType())) {
            return true;
        }
        for (String name : getHandledAgentNames()) {
            if (trimmed.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Executes the component logic against the activity's runtime state and returns the computed
     * result.
     *
     * <p>Takes a {@link CapabilityExecutionContext} rather than a {@code DelegateExecution} so one
     * provider implementation is executable from both Camunda shapes a generated Target Platform
     * can produce - a delegateExpression service task and an external-task worker - which share no
     * common execution type. Which shape an activity became is decided by the authored model, and a
     * provider must not care.
     */
    AutomationResult execute(CapabilityExecutionContext context, String activityId, String agentName);
}
