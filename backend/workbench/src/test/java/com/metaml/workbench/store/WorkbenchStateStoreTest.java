package com.metaml.workbench.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.model.TwinProcess;

class WorkbenchStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void olderSnapshotsWithoutTenantIdStillRestoreAsUnownedNotAsAnError() throws Exception {
        Path stateFile = tempDir.resolve("workbench-state.json");
        Files.writeString(stateFile, """
                {
                  "models": [
                    {
                      "id": "model-1",
                      "name": "pre-tenancy model",
                      "bpmnXml": "<bpmn/>",
                      "processDefinitionId": "def-1"
                    }
                  ],
                  "twins": [
                    {
                      "id": "twin-1",
                      "modelId": "model-1",
                      "processDefinitionId": "original-def",
                      "originalProcessId": "original-instance",
                      "twinProcessId": "twin-instance",
                      "status": "RUNNING",
                      "eventLog": [],
                      "activityLinks": []
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        WorkbenchStateStore store = new WorkbenchStateStore(stateFile.toString(), true);

        WorkbenchStateStore.Snapshot snapshot = store.load();

        assertThat(snapshot.twins()).hasSize(1);
        assertThat(snapshot.twins().get(0).getTenantId()).isNull();
    }

    @Test
    void tenantIdRoundTripsForTwinsThatHaveOne() {
        Path stateFile = tempDir.resolve("workbench-state.json");
        WorkbenchStateStore store = new WorkbenchStateStore(stateFile.toString(), true);

        TwinProcess twin = new TwinProcess();
        twin.setId("twin-1");
        twin.setModelId("model-1");
        twin.setTenantId("tenant-abc");

        store.save(List.of(twin));
        WorkbenchStateStore.Snapshot snapshot = store.load();

        assertThat(snapshot.twins().get(0).getTenantId()).isEqualTo("tenant-abc");
    }

    @Test
    void olderSnapshotsWithoutTwinProcessDefinitionIdStillRestoreTheOriginalDefinition() throws Exception {
        Path stateFile = tempDir.resolve("workbench-state.json");
        Files.writeString(stateFile, """
                {
                  "models": [],
                  "twins": [
                    {
                      "id": "twin-1",
                      "modelId": "model-1",
                      "processDefinitionId": "original-def",
                      "originalProcessId": "original-instance",
                      "twinProcessId": "twin-instance",
                      "status": "RUNNING",
                      "eventLog": [],
                      "activityLinks": []
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        WorkbenchStateStore store = new WorkbenchStateStore(stateFile.toString(), true);

        WorkbenchStateStore.Snapshot snapshot = store.load();

        assertThat(snapshot.twins()).hasSize(1);
        assertThat(snapshot.twins().get(0).getTwinProcessDefinitionId()).isEqualTo("original-def");
    }

    @Test
    void concurrentSavesOfTheSameLiveGrowingStateNeverLoseAnAlreadyWrittenEntry() throws Exception {
        Path stateFile = tempDir.resolve("workbench-state.json");
        WorkbenchStateStore store = new WorkbenchStateStore(stateFile.toString(), true);

        TwinProcess sharedTwin = new TwinProcess();
        sharedTwin.setId("twin-1");
        sharedTwin.setModelId("model-1");
        sharedTwin.setProcessDefinitionId("original-def");
        sharedTwin.setTwinProcessDefinitionId("twin-def");
        sharedTwin.setOriginalProcessId("original-instance");
        sharedTwin.setTwinProcessId("twin-instance");
        sharedTwin.setStatus("RUNNING");

        int threadCount = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            AtomicInteger nextEntry = new AtomicInteger();
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    sharedTwin.getEventLog().add("entry-" + nextEntry.getAndIncrement());
                    store.save(List.of(sharedTwin));
                }));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        WorkbenchStateStore.Snapshot snapshot = store.load();
        assertThat(snapshot.twins()).hasSize(1);
        assertThat(snapshot.twins().get(0).getEventLog()).hasSize(threadCount);
    }
}
