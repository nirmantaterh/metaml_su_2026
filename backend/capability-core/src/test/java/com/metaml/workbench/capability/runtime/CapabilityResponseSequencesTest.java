package com.metaml.workbench.capability.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.metaml.workbench.automation.AutomationResult;

class CapabilityResponseSequencesTest {
    @Test
    void consumesConfiguredResponsesFifoPerProviderAndFallsBackAfterExhaustion() {
        Context context = new Context(Map.of(CapabilityResponseSequences.CONFIG_VARIABLE,
                Map.of("arbitrary-capability", List.of(Map.of("decision", false), Map.of("decision", true)))));
        assertThat(CapabilityResponseSequences.apply(context, "arbitrary-capability", result()).outputs())
                .containsEntry("decision", false);
        assertThat(CapabilityResponseSequences.apply(context, "arbitrary-capability", result()).outputs())
                .containsEntry("decision", true);
        assertThat(CapabilityResponseSequences.apply(context, "arbitrary-capability", result()).outputs())
                .containsEntry("decision", "provider-default");
    }

    @Test
    void configurationsAndCursorsAreRunLocal() {
        Context first = new Context(Map.of(CapabilityResponseSequences.CONFIG_VARIABLE,
                Map.of("arbitrary-capability", List.of(Map.of("decision", false)))));
        Context second = new Context(Map.of(CapabilityResponseSequences.CONFIG_VARIABLE,
                Map.of("arbitrary-capability", List.of(Map.of("decision", true)))));
        assertThat(CapabilityResponseSequences.apply(first, "arbitrary-capability", result()).outputs())
                .containsEntry("decision", false);
        assertThat(CapabilityResponseSequences.apply(second, "arbitrary-capability", result()).outputs())
                .containsEntry("decision", true);
    }

    @Test
    void concurrentlyExecutingRunsDoNotConsumeEachOthersProviderSequence() throws Exception {
        Context runA = new Context(Map.of(CapabilityResponseSequences.CONFIG_VARIABLE,
                Map.of("provider-a", List.of(Map.of("decision", "a-first"), Map.of("decision", "a-second")))));
        Context runB = new Context(Map.of(CapabilityResponseSequences.CONFIG_VARIABLE,
                Map.of("provider-a", List.of(Map.of("decision", "b-first"), Map.of("decision", "b-second")))));

        CompletableFuture<List<Object>> sequenceA = CompletableFuture.supplyAsync(() -> List.of(
                CapabilityResponseSequences.apply(runA, "provider-a", result()).outputs().get("decision"),
                CapabilityResponseSequences.apply(runA, "provider-a", result()).outputs().get("decision")));
        CompletableFuture<List<Object>> sequenceB = CompletableFuture.supplyAsync(() -> List.of(
                CapabilityResponseSequences.apply(runB, "provider-a", result()).outputs().get("decision"),
                CapabilityResponseSequences.apply(runB, "provider-a", result()).outputs().get("decision")));

        assertThat(sequenceA.get(5, TimeUnit.SECONDS)).containsExactly("a-first", "a-second");
        assertThat(sequenceB.get(5, TimeUnit.SECONDS)).containsExactly("b-first", "b-second");
    }

    @Test
    void providersWithinOneRunHaveIndependentCursors() {
        Context context = new Context(Map.of(CapabilityResponseSequences.CONFIG_VARIABLE, Map.of(
                "provider-a", List.of(Map.of("decision", false), Map.of("decision", true)),
                "provider-b", List.of(Map.of("decision", "first"), Map.of("decision", "second")))));

        assertThat(CapabilityResponseSequences.apply(context, "provider-a", result()).outputs())
                .containsEntry("decision", false);
        assertThat(CapabilityResponseSequences.apply(context, "provider-b", result()).outputs())
                .containsEntry("decision", "first");
        assertThat(CapabilityResponseSequences.apply(context, "provider-a", result()).outputs())
                .containsEntry("decision", true);
        assertThat(CapabilityResponseSequences.apply(context, "provider-b", result()).outputs())
                .containsEntry("decision", "second");
    }

    @Test
    void originalAndTwinUseEquivalentButIndependentRunLocalCursors() {
        Map<String, List<Map<String, Object>>> configuration = Map.of("provider-a",
                List.of(Map.of("decision", false), Map.of("decision", true)));
        Context original = new Context(Map.of(CapabilityResponseSequences.CONFIG_VARIABLE,
                CapabilityResponseSequences.copyConfiguration(configuration)));
        Context twin = new Context(Map.of(CapabilityResponseSequences.CONFIG_VARIABLE,
                CapabilityResponseSequences.copyConfiguration(configuration)));

        assertThat(CapabilityResponseSequences.apply(original, "provider-a", result()).outputs())
                .containsEntry("decision", false);
        assertThat(CapabilityResponseSequences.apply(original, "provider-a", result()).outputs())
                .containsEntry("decision", true);
        // The twin has not consumed either of the original's entries.
        assertThat(CapabilityResponseSequences.apply(twin, "provider-a", result()).outputs())
                .containsEntry("decision", false);
    }

    @Test
    void aLaterRunWithoutConfigurationStartsCleanWithNormalProviderBehavior() {
        Context completedRun = new Context(Map.of(CapabilityResponseSequences.CONFIG_VARIABLE,
                Map.of("provider-a", List.of(Map.of("decision", false)))));
        assertThat(CapabilityResponseSequences.apply(completedRun, "provider-a", result()).outputs())
                .containsEntry("decision", false);

        Context laterRun = new Context(Map.of());
        assertThat(CapabilityResponseSequences.apply(laterRun, "provider-a", result()).outputs())
                .containsEntry("decision", "provider-default");
    }

    @Test
    void rejectsMalformedConfigurationInsteadOfSilentlyChangingExhaustionBehavior() {
        assertThatThrownBy(() -> CapabilityResponseSequences.copyConfiguration(
                Map.of("provider-a", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain at least one output map");
    }

    private static AutomationResult result() { return new AutomationResult("provider ran", Map.of("decision", "provider-default")); }

    private static final class Context implements CapabilityExecutionContext {
        private final Map<String, Object> variables = new LinkedHashMap<>();
        Context(Map<String, Object> initial) { variables.putAll(initial); }
        public Object getVariable(String name) { return variables.get(name); }
        public void setVariable(String name, Object value) { variables.put(name, value); }
        public String getProcessInstanceId() { return "run"; }
        public String getProcessDefinitionId() { return "definition"; }
        public String getActivityInstanceId() { return "activity"; }
        public String getBusinessKey() { return "key"; }
    }
}
