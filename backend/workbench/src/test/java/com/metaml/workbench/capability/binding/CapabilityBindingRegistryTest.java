package com.metaml.workbench.capability.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.runtime.CapabilityBinding;

// P7 Step 5. Genericity: a synthetic process/activity invented for this test.
class CapabilityBindingRegistryTest {

    private static final String PROCESS_KEY = "SyntheticProcess";
    private static final String ACTIVITY = "Activity_Under_Test";

    @TempDir
    Path tempDir;

    private static CapabilityBinding binding(String providerId) {
        return new CapabilityBinding(PROCESS_KEY, ACTIVITY, providerId, "synthetic-type", "1.0.0",
                new CapabilityContract("synthetic-capability", Set.of(), Set.of(), ExecutionMode.SYNCHRONOUS,
                        java.util.Map.of(), Set.of()),
                null, Instant.now());
    }

    private CapabilityBindingRegistry registryAt(Path file) {
        CapabilityBindingRegistry registry = new CapabilityBindingRegistry(
                new CapabilityBindingStore(file.toString(), true));
        registry.restore();
        return registry;
    }

    @Test
    void aBoundActivityIsCurrentImmediatelyAfterUpsert() {
        CapabilityBindingRegistry registry = registryAt(tempDir.resolve("bindings.json"));

        registry.upsert(binding("provider-x"));

        assertThat(registry.current(PROCESS_KEY, ACTIVITY)).isPresent();
        assertThat(registry.current(PROCESS_KEY, ACTIVITY).get().providerId()).isEqualTo("provider-x");
    }

    @Test
    void rebindingReplacesTheCurrentBindingRatherThanAddingASecondOne() {
        CapabilityBindingRegistry registry = registryAt(tempDir.resolve("bindings.json"));

        registry.upsert(binding("provider-x"));
        registry.upsert(binding("provider-y"));

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.current(PROCESS_KEY, ACTIVITY).get().providerId()).isEqualTo("provider-y");
    }

    @Test
    void aBindingSurvivesRestartViaANewRegistryOverTheSameFile() {
        Path file = tempDir.resolve("bindings.json");
        registryAt(file).upsert(binding("provider-x"));

        CapabilityBindingRegistry restarted = registryAt(file);

        assertThat(restarted.current(PROCESS_KEY, ACTIVITY)).isPresent();
        assertThat(restarted.current(PROCESS_KEY, ACTIVITY).get().providerId()).isEqualTo("provider-x");
    }

    @Test
    void aRebindThatSurvivesRestartLeavesOnlyTheNewProviderCurrent() {
        Path file = tempDir.resolve("bindings.json");
        CapabilityBindingRegistry registry = registryAt(file);
        registry.upsert(binding("provider-x"));
        registry.upsert(binding("provider-y"));

        CapabilityBindingRegistry restarted = registryAt(file);

        assertThat(restarted.all()).hasSize(1);
        assertThat(restarted.current(PROCESS_KEY, ACTIVITY).get().providerId()).isEqualTo("provider-y");
    }

    @Test
    void unknownActivityHasNoCurrentBindingRatherThanAFabricatedOne() {
        CapabilityBindingRegistry registry = registryAt(tempDir.resolve("bindings.json"));

        assertThat(registry.current(PROCESS_KEY, "SomeOtherActivity")).isEmpty();
    }

    // ---- the rebind-failure correctness bug: compensation must restore, not merely clear ----

    @Test
    void aFirstBindThatFailsToPersistLeavesTheInMemoryIndexUntouched() throws IOException {
        Path blocker = tempDir.resolve("blocked-parent");
        Files.writeString(blocker, "not a directory");
        Path file = blocker.resolve("bindings.json");
        CapabilityBindingRegistry registry = new CapabilityBindingRegistry(
                new CapabilityBindingStore(file.toString(), true));
        registry.restore(); // nothing to restore; the store cannot even be created as a directory

        assertThatThrownBy(() -> registry.upsert(binding("provider-x")))
                .isInstanceOf(CapabilityBindingPersistenceException.class);

        // The failed candidate was never committed to the live index.
        assertThat(registry.current(PROCESS_KEY, ACTIVITY)).isEmpty();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void aRebindThatFailsToPersistLeavesThePreviousBindingCurrent() throws IOException {
        Path file = tempDir.resolve("bindings.json");
        CapabilityBindingRegistry registry = registryAt(file);
        registry.upsert(binding("provider-x"));

        // Make the NEXT save() fail by replacing the backing file with a directory of the same name -
        // the store's tmp-then-move write can no longer land there.
        Files.delete(file);
        Files.createDirectory(file);

        assertThatThrownBy(() -> registry.upsert(binding("provider-y")))
                .isInstanceOf(CapabilityBindingPersistenceException.class);

        // The candidate (provider-y) was discarded; the previously current binding (provider-x) is
        // exactly what a fresh caller still sees - never cleared, never replaced by the failed value.
        assertThat(registry.current(PROCESS_KEY, ACTIVITY)).isPresent();
        assertThat(registry.current(PROCESS_KEY, ACTIVITY).get().providerId()).isEqualTo("provider-x");
        assertThat(registry.all()).hasSize(1);
    }
}
