package com.metaml.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Projection of execution and automation state for a single Twin activity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TwinActivityExecutionState {
    private String activityId;
    private String twinActivityId;
    private String agentName;
    private String status;
    private String summary;
    // Activity automation outputs produced by ComponentExecutor.
    private Map<String, Object> output;
    // Active runtime execution instances for the activity.
    private List<ActiveRuntimeInstance> activeInstances;
}
