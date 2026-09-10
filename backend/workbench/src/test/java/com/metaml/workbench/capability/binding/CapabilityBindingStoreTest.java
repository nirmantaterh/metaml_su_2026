package com.metaml.workbench.capability.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.runtime.CapabilityBinding;

// P7 Step 5. Genericity: a synthetic process/activity invented for this test.
class CapabilityBindingStoreTest {

    @TempDir
    Path tempDir;

    private static CapabilityBinding binding(String providerId) {
        return new CapabilityBinding("SyntheticProcess", "Activity_Under_Test", providerId, "synthetic-type",
                "1.0.0", new CapabilityContract("synthetic-capability", Set.of(), Set.of(),
                        ExecutionMode.SYNCHRONOUS, java.util.Map.of(), Set.of()),
                null, Instant.now());
    }

    private CapabilityBindingStore storeAt(Path file) {
        return new CapabilityBindingStore(file.toString(), true);
    }

    @Test
    void loadOnAMissingFileReturnsEmptyRatherThanFailing() {
        CapabilityBindingStore store = storeAt(tempDir.resolve("nonexistent.json"));
        assertThat(store.load()).isEmpty();
    }

    @Test
    void aBindingSurvivesReloadFromANewStoreInstance() {
        Path file = tempDir.resolve("bindings.json");
        storeAt(file).save(List.of(binding("provider-x")));

        List<CapabilityBinding> reloaded = storeAt(file).load();

        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.get(0).providerId()).isEqualTo("provider-x");
        assertThat(reloaded.get(0).activityId()).isEqualTo("Activity_Under_Test");
        assertThat(reloaded.get(0).processDefinitionKey()).isEqualTo("SyntheticProcess");
    }

    @Test
    void savingReplacesTheWholeCollectionRatherThanAppending() {
        Path file = tempDir.resolve("bindings.json");
        CapabilityBindingStore store = storeAt(file);

        store.save(List.of(binding("provider-x")));
        store.save(List.of(binding("provider-y")));

        List<CapabilityBinding> reloaded = storeAt(file).load();
        assertThat(reloaded).hasSize(1);
        assertThat(reloaded.get(0).providerId()).isEqualTo("provider-y");
    }

    @Test
    void whenDisabledSaveIsANoOpAndLoadReturnsEmpty() {
        Path file = tempDir.resolve("bindings.json");
        CapabilityBindingStore disabled = new CapabilityBindingStore(file.toString(), false);

        disabled.save(List.of(binding("provider-x")));

        assertThat(Files.exists(file)).isFalse();
        assertThat(disabled.load()).isEmpty();
    }

    @Test
    void saveThrowsWhenTheDestinationCannotBeWritten() throws IOException {
        // A regular file where save() needs to create a directory: Files.createDirectories fails.
        Path blocker = tempDir.resolve("blocked-parent");
        Files.writeString(blocker, "not a directory");
        Path file = blocker.resolve("bindings.json");
        CapabilityBindingStore store = storeAt(file);

        assertThatThrownBy(() -> store.save(List.of(binding("provider-x"))))
                .isInstanceOf(CapabilityBindingPersistenceException.class);
    }
}
