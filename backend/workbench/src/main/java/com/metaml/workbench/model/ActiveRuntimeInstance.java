package com.metaml.workbench.model;

// Represents an active runtime instance of an activity from runtimeService.getActivityInstance().
// Provides the exact activityInstanceId needed for targeted evolution of parallel multi-instance siblings,
// alongside an optional loopCounter index for human-readable disambiguation.
public record ActiveRuntimeInstance(String activityInstanceId, Integer loopCounter) {
}
