package com.metaml.workbench.capability.gap;

import java.util.List;
import java.util.Objects;

// A recommendation for closing one CapabilityGap (MetaML Scope 6, Phase 5, section 14). Mirrors the
// locked shape: recommendedProviderId, rationale, confidence, alternatives, status. Never carries an
// invented provider id, capability, or output - see CapabilityGapRecommender.
public record CapabilityGapRecommendation(String gapId, String recommendedProviderId, String rationale,
        double confidence, List<String> alternatives, CapabilityGapRecommendation.Status status) {

    public enum Status {
        RECOMMENDED,
        NO_SUITABLE_PROVIDER,
        AMBIGUOUS
    }

    public CapabilityGapRecommendation {
        Objects.requireNonNull(gapId, "gapId must not be null");
        Objects.requireNonNull(rationale, "rationale must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (status == Status.RECOMMENDED && (recommendedProviderId == null || recommendedProviderId.isBlank())) {
            throw new IllegalArgumentException("RECOMMENDED recommendation requires a recommendedProviderId");
        }
        if (status != Status.RECOMMENDED && recommendedProviderId != null) {
            throw new IllegalArgumentException(status + " recommendation must not carry a recommendedProviderId");
        }
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be within [0.0, 1.0]");
        }
    }
}
