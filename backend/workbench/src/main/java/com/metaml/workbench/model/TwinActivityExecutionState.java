package com.metaml.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

// Read-only projection of what a single Twin activity's automation has actually done, built
// entirely from variables ComponentExecutor implementations already write via
// TwinAutomationDelegate/AgentVariables (evolvedAgent_*, twinAutomation_*, twinAutomationOutput_*)
// - nothing here is computed or inferred beyond that. Deliberately narrow: this is not a general
// process-variable dump, only the handful of MetaML-owned variables that answer "what component
// ran on this activity, and what did it produce".
//
// status is derived, not stored:
//   NOT_STARTED - activity not connected to a twin activity, or no agent has been bound yet
//   BOUND       - an agent is bound (evolvedAgent_*) but automation has not run yet
//   EXECUTED    - automation ran (twinAutomation_* is set); output reflects what it produced
//   FAILED      - automation was attempted and the twin's own event log recorded a failure for
//                 this exact activity (see WorkbenchServiceImpl#advanceTwinActivity's catch path)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TwinActivityExecutionState {
    private String activityId;
    private String twinActivityId;
    private String agentName;
    private String status;
    private String summary;
    // Whatever the resolved ComponentExecutor actually put in its AutomationResult#outputs() for
    // this activity - keys and types are entirely component-specific (e.g. credit-risk-assessor's
    // riskScore/riskFlagged vs validator's validationPassed/validationStatus). Never a fixed schema.
    private Map<String, Object> output;
    // Scope 6 P1 identity fix: currently-active runtime siblings of this activityId on the
    // ORIGINAL process instance, read live from runtimeService.getActivityInstance() - never
    // historic ordering. Empty when the activity hasn't been reached, has already completed, or
    // the original process has ended. Size 0 or 1 for an ordinary (non-multi-instance) activity;
    // size 2+ only when a parallel multi-instance activity genuinely has concurrent siblings
    // active right now. Lets a caller (the VS Code plugin) discover and target one specific
    // sibling via evolveActivity(twinId, activityId, activityInstanceId, agentType) instead of
    // falling through to currentVisitId()'s heuristic.
    private List<ActiveRuntimeInstance> activeInstances;
}
