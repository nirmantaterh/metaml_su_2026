package com.metaml.workbench.capability.gap;

import static org.assertj.core.api.Assertions.assertThat;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

// MetaML Scope 6, Phase 5, test category E: persistence. Mirrors ApprovalStoreTest's own style -
// same atomic write-and-replace pattern, exercised directly against the filesystem.
class CapabilityGapStoreTest {

    @TempDir
    Path tempDir;

    private CapabilityGapStore store(Path tempDir) {
        return new CapabilityGapStore(tempDir.resolve("capability-gaps.json").toString(), true);
    }

    private static CapabilityGap gap(String gapId) {
        CapabilityContract contract = new CapabilityContract("invoice.risk-scoring", Set.of(
                new IoDeclaration("customerId", IoType.STRING, true)),
                Set.of(new IoDeclaration("riskScore", IoType.NUMBER, true)), ExecutionMode.SYNCHRONOUS, Map.of(),
                Set.of("pii"));
        Instant now = Instant.now();
        return new CapabilityGap(gapId, "invoiceValidationProcess", "ValidateInvoice", "inst-1", 2, "twin-1",
                contract, Map.of("customerId", IoType.STRING), List.of("risk-scorer-01"), "tenant-a",
                GapOrigin.RUNTIME_WORKBENCH_TWIN, GapStatus.OPEN, now, now, null, null);
    }

    @Test
    void writesAndReplacesAtomicallyLeavingNoTempFileBehind() {
        CapabilityGapStore store = store(tempDir);
        store.save(List.of(gap("gap-1")));

        assertThat(tempDir.resolve("capability-gaps.json")).exists();
        assertThat(tempDir.resolve("capability-gaps.json.tmp")).doesNotExist();
    }

    @Test
    void reloadsExactlyWhatWasSavedAfterASimulatedRestart() {
        CapabilityGapStore writer = store(tempDir);
        CapabilityGap original = gap("gap-1");
        writer.save(List.of(original));

        // A fresh store instance, as a restart would construct, reading the same file.
        CapabilityGapStore reader = store(tempDir);
        List<CapabilityGap> reloaded = reader.load();

        assertThat(reloaded).hasSize(1);
        CapabilityGap restored = reloaded.get(0);
        assertThat(restored.gapId()).isEqualTo(original.gapId());
        assertThat(restored.processDefinitionId()).isEqualTo(original.processDefinitionId());
        assertThat(restored.activityId()).isEqualTo(original.activityId());
        assertThat(restored.activityInstanceId()).isEqualTo(original.activityInstanceId());
        assertThat(restored.loopCounter()).isEqualTo(original.loopCounter());
        assertThat(restored.processInstanceId()).isEqualTo(original.processInstanceId());
        assertThat(restored.availableInputs()).isEqualTo(original.availableInputs());
        assertThat(restored.candidateProviderIds()).isEqualTo(original.candidateProviderIds());
        assertThat(restored.tenantId()).isEqualTo(original.tenantId());
        assertThat(restored.origin()).isEqualTo(original.origin());
        assertThat(restored.status()).isEqualTo(original.status());
        assertThat(restored.requiredContract()).isEqualTo(original.requiredContract());
    }

    @Test
    void missingFileLoadsAsEmptyRatherThanFailing() {
        CapabilityGapStore store = store(tempDir);
        assertThat(store.load()).isEmpty();
    }

    @Test
    void disabledStoreNeverTouchesTheFilesystem() {
        CapabilityGapStore disabled = new CapabilityGapStore(tempDir.resolve("capability-gaps.json").toString(),
                false);
        disabled.save(List.of(gap("gap-1")));

        assertThat(tempDir.resolve("capability-gaps.json")).doesNotExist();
        assertThat(disabled.load()).isEmpty();
    }

    // Section 7 / 10: the persisted JSON carries name/type declarations only - never an actual
    // business value. availableInputs serializes to an IoType name string ("STRING"), never a raw
    // customerId value such as "12345".
    @Test
    void persistedRepresentationCarriesNoBusinessValues() throws Exception {
        CapabilityGapStore store = store(tempDir);
        store.save(List.of(gap("gap-1")));

        String json = Files.readString(tempDir.resolve("capability-gaps.json"));
        assertThat(json).contains("\"customerId\"").contains("\"STRING\"");
        assertThat(json).doesNotContain("12345");
    }
}
