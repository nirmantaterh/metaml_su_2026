package com.metaml.workbench.automation;

import java.util.Map;

/**
 * Automation execution result for a twin activity, including an activity summary and output variables.
 */
public record AutomationResult(String summary, Map<String, Object> outputs) {

    public AutomationResult {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("An automation result needs a summary");
        }
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    // most implementations only want to say what they did
    public static AutomationResult of(String summary) {
        return new AutomationResult(summary, Map.of());
    }
}
