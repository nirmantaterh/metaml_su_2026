package com.metaml.workbench.capability.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.metaml.workbench.automation.AutomationResult;

/** Run-local, FIFO provider-output overrides used for deterministic demonstrations. */
public final class CapabilityResponseSequences {
    public static final String CONFIG_VARIABLE = "metamlCapabilityResponseSequences";
    public static final String CURSOR_VARIABLE = "metamlCapabilityResponseSequenceCursors";

    private CapabilityResponseSequences() { }

    @SuppressWarnings("unchecked")
    public static AutomationResult apply(CapabilityExecutionContext context, String providerIdentity,
            AutomationResult providerResult) {
        Object configured = context.getVariable(CONFIG_VARIABLE);
        if (!(configured instanceof Map<?, ?> all) || providerIdentity == null) return providerResult;
        Object rawSequence = all.get(providerIdentity);
        if (!(rawSequence instanceof List<?> sequence) || sequence.isEmpty()) return providerResult;
        Map<String, Integer> cursors = context.getVariable(CURSOR_VARIABLE) instanceof Map<?, ?> rawCursors
                ? copyCursors(rawCursors) : new LinkedHashMap<>();
        int index = cursors.getOrDefault(providerIdentity, 0);
        if (index >= sequence.size()) return providerResult;
        Object rawOutputs = sequence.get(index);
        if (!(rawOutputs instanceof Map<?, ?> outputs)) return providerResult;
        cursors.put(providerIdentity, index + 1);
        context.setVariable(CURSOR_VARIABLE, cursors);
        Map<String, Object> merged = new LinkedHashMap<>(providerResult.outputs());
        outputs.forEach((key, value) -> { if (key instanceof String name) merged.put(name, value); });
        return new AutomationResult(providerResult.summary(), merged);
    }

    private static Map<String, Integer> copyCursors(Map<?, ?> raw) {
        Map<String, Integer> copy = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key instanceof String name && value instanceof Number number) copy.put(name, number.intValue());
        });
        return copy;
    }

    public static Map<String, List<Map<String, Object>>> copyConfiguration(
            Map<String, List<Map<String, Object>>> configuration) {
        Map<String, List<Map<String, Object>>> copy = new LinkedHashMap<>();
        if (configuration != null) configuration.forEach((capability, sequence) -> {
            if (capability == null || capability.isBlank()) {
                throw new IllegalArgumentException("Provider identity must not be blank");
            }
            if (sequence == null || sequence.isEmpty()) {
                throw new IllegalArgumentException("Response sequence for provider '" + capability
                        + "' must contain at least one output map");
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map<String, Object> outputs : sequence) {
                if (outputs == null) {
                    throw new IllegalArgumentException("Response sequence for provider '" + capability
                            + "' contains a null output map");
                }
                entries.add(new LinkedHashMap<>(outputs));
            }
            copy.put(capability, entries);
        });
        return copy;
    }
}
