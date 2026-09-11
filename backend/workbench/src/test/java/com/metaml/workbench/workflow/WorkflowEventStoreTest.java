package com.metaml.workbench.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowEventStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savedEventsRoundTripThroughARealFile() {
        WorkflowEventStore store = new WorkflowEventStore(tempDir.resolve("workflow-events.json").toString(), true);
        Instant timestamp = Instant.now();
        Map<String, List<StageEvent>> events = Map.of(
                "model-1", List.of(
                        new StageEvent(WorkflowStage.MODEL, StageStatus.IN_PROGRESS, timestamp, null),
                        new StageEvent(WorkflowStage.MODEL, StageStatus.COMPLETED, timestamp, null)));

        store.save(events);
        Map<String, List<StageEvent>> loaded = store.load();

        assertThat(loaded).containsKey("model-1");
        assertThat(loaded.get("model-1")).hasSize(2);
        assertThat(loaded.get("model-1").get(1).status()).isEqualTo(StageStatus.COMPLETED);
        // Compare against epoch-millis precision matching JSON DTO serialization.
        assertThat(loaded.get("model-1").get(1).timestamp())
                .isEqualTo(Instant.ofEpochMilli(timestamp.toEpochMilli()));
    }

    @Test
    void aMissingFileLoadsAsEmptyNotAnError() {
        WorkflowEventStore store = new WorkflowEventStore(
                tempDir.resolve("never-written.json").toString(), true);

        assertThat(store.load()).isEmpty();
    }

    @Test
    void aCorruptFileLoadsAsEmptyNotAnError() throws Exception {
        Path file = tempDir.resolve("corrupt.json");
        Files.writeString(file, "{ this is not valid json");
        WorkflowEventStore store = new WorkflowEventStore(file.toString(), true);

        assertThat(store.load()).isEmpty();
    }

    @Test
    void disabledStoreNeverTouchesTheFilesystem() {
        Path file = tempDir.resolve("should-never-exist.json");
        WorkflowEventStore store = new WorkflowEventStore(file.toString(), false);

        store.save(Map.of("model-1", List.of(
                new StageEvent(WorkflowStage.MODEL, StageStatus.COMPLETED, Instant.now(), null))));

        assertThat(Files.exists(file)).isFalse();
        assertThat(store.load()).isEmpty();
    }

    @Test
    void multipleModelsPersistIndependently() {
        WorkflowEventStore store = new WorkflowEventStore(tempDir.resolve("multi.json").toString(), true);
        Instant t = Instant.now();
        store.save(Map.of(
                "model-1", List.of(new StageEvent(WorkflowStage.MODEL, StageStatus.COMPLETED, t, null)),
                "model-2", List.of(new StageEvent(WorkflowStage.MODEL, StageStatus.COMPLETED, t, "different"))));

        Map<String, List<StageEvent>> loaded = store.load();

        assertThat(loaded).hasSize(2);
        assertThat(loaded.get("model-2").get(0).detail()).isEqualTo("different");
    }
}
