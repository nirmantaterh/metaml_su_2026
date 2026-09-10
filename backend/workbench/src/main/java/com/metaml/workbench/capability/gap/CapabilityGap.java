package com.metaml.workbench.capability.gap;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.IoType;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// The locked capability-gap record (MetaML Scope 6, Phase 5, section 5). Immutable and
// Jackson-serializable, mirroring the existing Approval record's shape and the existing
// CapabilityContract/CapabilityProvider records' immutability style.
//
// A capability gap exists when a process requires a capability/output that cannot currently be
// satisfied by an available legitimate provider. It never carries a business value: availableInputs
// is name/type only (section 7), and candidateProviderIds is identity only, never the provider's
// contract or a business output. No provider existing is a different architectural condition from a
// provider existing and failing its output contract - a CapabilityGap represents only the former
// (section 2; see CapabilityOutputContractViolationException for the latter).
public record CapabilityGap(
        String gapId,
        String processDefinitionId,
        String activityId,
        String activityInstanceId,
        Integer loopCounter,
        String processInstanceId,
        CapabilityContract requiredContract,
        Map<String, IoType> availableInputs,
        List<String> candidateProviderIds,
        String tenantId,
        GapOrigin origin,
        GapStatus status,
        Instant detectedAt,
        Instant lastTransitionAt,
        String approvalId,
        String boundProviderId) {

    public CapabilityGap {
        Objects.requireNonNull(gapId, "gapId must not be null");
        Objects.requireNonNull(processDefinitionId, "processDefinitionId must not be null");
        Objects.requireNonNull(activityId, "activityId must not be null");
        Objects.requireNonNull(requiredContract, "requiredContract must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(detectedAt, "detectedAt must not be null");
        Objects.requireNonNull(lastTransitionAt, "lastTransitionAt must not be null");
        availableInputs = availableInputs == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(availableInputs));
        candidateProviderIds = candidateProviderIds == null ? List.of() : List.copyOf(candidateProviderIds);
    }

    // Identity-preserving transition helper. gapId, processDefinitionId, activityId,
    // activityInstanceId, and requiredContract never change after detection - only the lifecycle
    // fields below them do. CapabilityGapService is solely responsible for deciding which
    // transitions are legal; this record only carries the resulting state.
    CapabilityGap withStatus(GapStatus newStatus, Instant at, String newApprovalId, String newBoundProviderId) {
        return new CapabilityGap(gapId, processDefinitionId, activityId, activityInstanceId, loopCounter,
                processInstanceId, requiredContract, availableInputs, candidateProviderIds, tenantId, origin,
                newStatus, detectedAt, at, newApprovalId, newBoundProviderId);
    }

    // Idempotent re-report: refreshes the observational fields (candidate providers, available
    // inputs, process instance) of an already-open gap without touching its lifecycle state -
    // re-detecting the same logical gap must never duplicate it or reset its progress.
    CapabilityGap withRefreshedObservation(String newProcessInstanceId, Map<String, IoType> newAvailableInputs,
            List<String> newCandidateProviderIds) {
        return new CapabilityGap(gapId, processDefinitionId, activityId, activityInstanceId, loopCounter,
                newProcessInstanceId != null ? newProcessInstanceId : processInstanceId, requiredContract,
                newAvailableInputs, newCandidateProviderIds, tenantId, origin, status, detectedAt, lastTransitionAt,
                approvalId, boundProviderId);
    }
}
