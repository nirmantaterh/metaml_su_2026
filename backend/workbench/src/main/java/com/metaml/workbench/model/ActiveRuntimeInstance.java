package com.metaml.workbench.model;

// One currently-active runtime sibling of a BPMN activity definition on the ORIGINAL process
// instance, straight from runtimeService.getActivityInstance() - never historic ordering, never
// currentVisitId()'s "most recently started" heuristic. activityInstanceId is the exact identity
// evolveActivity(twinId, activityId, activityInstanceId, agentType) accepts to target one specific
// sibling of a parallel (non-sequential) multi-instance activity. loopCounter is Camunda's own
// per-instance multi-instance index when the activity is a multi-instance activity, null for a
// plain (single-instance) activity - included only as human-readable disambiguation, never as an
// alternative identity: callers must round-trip activityInstanceId itself, not loopCounter.
public record ActiveRuntimeInstance(String activityInstanceId, Integer loopCounter) {
}
