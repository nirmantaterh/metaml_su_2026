package com.metaml.workbench.model;

import java.util.Collection;
import java.util.List;

// Naming conventions for agent execution variables on twin and original processes.
public final class AgentVariables {

    // predates the generic mechanism; bare name kept because Gateway_ChecksPassed in the citi model reads it
    public static final String RISK_FLAGGED_OUTPUT = "riskFlagged";

    private static final String OUTPUT_NAME_SEPARATOR = ",";

    private AgentVariables() {
    }

    public static String evolvedAgent(String twinActivityId, Object loopCounter) {
        return "evolvedAgent_" + perVisit(twinActivityId, loopCounter);
    }

    // The agent type (e.g. "validator") that was resolved by the node manager during evolution.
    // Stored alongside the agent name so the automation dispatch can fall back to type-level
    // executor matching when no executor handles the specific agent name — the only way
    // multi-instance activities work, since each parallel sibling gets a distinct agent name
    // from the catalog but they all share one type that maps to one ComponentExecutor.
    public static String evolvedAgentType(String twinActivityId, Object loopCounter) {
        return "evolvedAgentType_" + perVisit(twinActivityId, loopCounter);
    }

    public static String evolvedAgentOutput(String outputName, String twinActivityId, Object loopCounter) {
        return "evolvedAgentOutput_" + outputName + "_" + perVisit(twinActivityId, loopCounter);
    }

    // tracks which outputs the last evolution wrote; re-evolution needs this to remove stale ones
    public static String evolvedAgentOutputIndex(String twinActivityId, Object loopCounter) {
        return "evolvedAgentOutputs_" + perVisit(twinActivityId, loopCounter);
    }

    public static String agentExecuted(String activityId, Object loopCounter) {
        return "agentExecuted_" + perVisit(activityId, loopCounter);
    }

    // Set when an operator has claimed this activity for component integration and the decision has
    // not resolved yet. While it is present the auto-bridge must not bind DEFAULT_BRIDGE_AGENT_TYPE
    // to this visit or advance the twin through it - otherwise the twin runs the default component
    // the instant the original reaches the activity, which is strictly before any human integration
    // can legally evolve it (evolveActivity itself requires the activity to already be reached), so
    // the integrated component could never become the one that actually executes. Cleared as soon
    // as an evolution actually binds an agent for the visit. Per-visit like every other agent
    // variable, so one sibling of a parallel multi-instance activity can be held without holding
    // the others; declared with a null loopCounter when the claim is made before any instance of
    // the activity exists yet, which holds every sibling of that activity - the honest reading of
    // a claim staked before there was anything to distinguish.
    public static String integrationPending(String twinActivityId, Object loopCounter) {
        return "integrationPending_" + perVisit(twinActivityId, loopCounter);
    }

    // written on the twin; the only signal that automation actually ran (vs. just having an agent assigned)
    public static String twinAutomation(String twinActivityId, Object loopCounter) {
        return "twinAutomation_" + perVisit(twinActivityId, loopCounter);
    }

    public static String twinAutomationOutput(String outputName, String twinActivityId, Object loopCounter) {
        return "twinAutomationOutput_" + outputName + "_" + perVisit(twinActivityId, loopCounter);
    }

    // not per-visit: gateway conditions read it without knowing which visit produced it; later visits may overwrite.
    public static String agentOutput(String activityId, String outputName) {
        return "agentOutput_" + activityId + "_" + outputName;
    }

    public static String outputIndexValue(Collection<String> outputNames) {
        return String.join(OUTPUT_NAME_SEPARATOR, outputNames);
    }

    public static List<String> outputNamesIn(Object indexValue) {
        if (!(indexValue instanceof String joined) || joined.isBlank()) {
            return List.of();
        }
        return List.of(joined.split(OUTPUT_NAME_SEPARATOR));
    }

    // Process variable holding a Map<String,Object> of explicit simulation gateway values.
    // Isolated from production semantics: tests and the demo harness write to this variable,
    // and the runtime reads from it only when no legitimate producer (ComponentExecutor,
    // external-task worker, user task completion) has already set the gateway variable.
    // The map is keyed by gateway variable name ("qualityPassed", "orderApproved", etc.)
    // and valued by the explicit deterministic value the simulation requires.
    public static final String SIMULATION_GATEWAY_VALUES = "_simulationGatewayValues";

    // multi-instance: same activity id per visit, so loop index is in the name to keep visits distinct
    private static String perVisit(String activityId, Object loopCounter) {
        return loopCounter == null ? activityId : activityId + "_" + loopCounter;
    }
}
