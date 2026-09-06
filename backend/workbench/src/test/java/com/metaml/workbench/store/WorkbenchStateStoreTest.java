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

    // A snapshot written before TwinProcess had a tenantId field must
    // still restore cleanly - null tenantId, not a read failure. The fixture below deliberately
    // still carries the "models" key that older files (written when this store also persisted
    // process models) contain, which doubles as the proof that such a file is still read without
    // error now that models have been removed from this store - the key is simply ignored.
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

    // the same field round-trips for a twin that DOES have a tenant, not just null
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

    // Save() used to build its DTO snapshot from the live twins
    // collections OUTSIDE the write lock, only synchronizing the actual file write. Since
    // WorkbenchServiceImpl always passes the SAME live, mutable collections on every call (not a
    // frozen copy per call), two concurrent persistState() calls could interleave their
    // snapshot-then-write sequences so a snapshot taken before some mutation could still win the
    // write lock AFTER a snapshot taken after that mutation had already written it - a lost update.
    // Snapshotting now happens inside the same lock as the write, so whichever caller acquires the
    // lock second always reads the CURRENT (already-mutated) live state, not a stale pre-captured
    // one - the file can only move forward, never regress, under concurrent saves of the same
    // live, growing state. Proven under real contention: many threads each append one more unique
    // entry to a SHARED TwinProcess's event log and immediately save() the same live twin
    // collection, all racing for the one write lock - every entry must still be present in the
    // final file, none silently lost to an overtaking older write.
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
        // the file's own last write might not have raced everyone, but by the time all threads
        // finish, the twin's own in-memory eventLog already holds all 24 - the property under test
        // is whether the LAST successful save() call's write reflects that full state, not a
        // regression back to some earlier, smaller snapshot
        assertThat(snapshot.twins().get(0).getEventLog()).hasSize(threadCount);
    }
}
