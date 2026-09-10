package com.metaml.workbench.capability.gap;

import static org.assertj.core.api.Assertions.assertThat;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

// MetaML Scope 6, Phase 5, test category C: provider resolution through the recommender. This is
// deliberately thin coverage of wiring, not a re-test of CapabilitySatisfaction itself (already
// locked and covered by CapabilitySatisfactionTest) - it proves CapabilityGapRecommender reports
// exactly what that algorithm resolves, never inventing a provider id (section 14).
class CapabilityGapRecommenderTest {

    private final CapabilityGapRecommender recommender = new CapabilityGapRecommender();

    private static CapabilityGap gap(String... requiredOutputNames) {
        Set<IoDeclaration> outputs = new java.util.LinkedHashSet<>();
        for (String name : requiredOutputNames) {
            outputs.add(new IoDeclaration(name, IoType.UNKNOWN, true));
        }
        CapabilityContract contract = new CapabilityContract(null, Set.of(), outputs, ExecutionMode.SYNCHRONOUS,
                Map.of(), Set.of());
        Instant now = Instant.now();
        return new CapabilityGap("gap-1", "invoiceValidationProcess", "ValidateInvoice", null, null, null, contract,
                Map.of(), List.of(), null, GapOrigin.STATIC_MODEL, GapStatus.OPEN, now, now, null, null);
    }

    private static CapabilityProvider provider(String id, String type, String version) {
        return new CapabilityProvider(id, type, version,
                new CapabilityContract(null, Set.of(), Set.of(new IoDeclaration("riskScore", IoType.UNKNOWN, true)),
                        ExecutionMode.SYNCHRONOUS, Map.of(), Set.of()),
                "synthetic", true, null);
    }

    @Test
    void zeroCandidatesIsNoSuitableProvider() {
        CapabilityGapRecommendation recommendation = recommender.recommend(gap("riskScore"), List.of());

        assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.NO_SUITABLE_PROVIDER);
        assertThat(recommendation.recommendedProviderId()).isNull();
    }

    @Test
    void oneCandidateIsRecommended() {
        CapabilityProvider provider = provider("risk-scorer-01", "risk-scorer", "1.0.0");

        CapabilityGapRecommendation recommendation = recommender.recommend(gap("riskScore"), List.of(provider));

        assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.RECOMMENDED);
        assertThat(recommendation.recommendedProviderId()).isEqualTo("risk-scorer-01");
        assertThat(recommendation.confidence()).isEqualTo(1.0);
    }

    @Test
    void unavailableCandidateIsExcluded() {
        CapabilityProvider unavailable = new CapabilityProvider("risk-scorer-01", "risk-scorer", "1.0.0",
                new CapabilityContract(null, Set.of(), Set.of(new IoDeclaration("riskScore", IoType.UNKNOWN, true)),
                        ExecutionMode.SYNCHRONOUS, Map.of(), Set.of()),
                "synthetic", false, "offline");

        CapabilityGapRecommendation recommendation = recommender.recommend(gap("riskScore"), List.of(unavailable));

        assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.NO_SUITABLE_PROVIDER);
    }

    @Test
    void sameProviderTypeResolvesToHighestSemver() {
        CapabilityProvider v1 = provider("risk-scorer-01", "risk-scorer", "1.0.0");
        CapabilityProvider v2 = provider("risk-scorer-02", "risk-scorer", "2.0.0");

        CapabilityGapRecommendation recommendation = recommender.recommend(gap("riskScore"), List.of(v1, v2));

        assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.RECOMMENDED);
        assertThat(recommendation.recommendedProviderId()).isEqualTo("risk-scorer-02");
        assertThat(recommendation.alternatives()).containsExactly("risk-scorer-01");
    }

    @Test
    void equalTopVersionWithinOneProviderTypeIsAmbiguous() {
        CapabilityProvider a = provider("risk-scorer-01", "risk-scorer", "1.0.0");
        CapabilityProvider b = provider("risk-scorer-02", "risk-scorer", "1.0.0");

        CapabilityGapRecommendation recommendation = recommender.recommend(gap("riskScore"), List.of(a, b));

        assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.AMBIGUOUS);
        assertThat(recommendation.recommendedProviderId()).isNull();
        assertThat(recommendation.alternatives()).containsExactlyInAnyOrder("risk-scorer-01", "risk-scorer-02");
    }

    @Test
    void differentProviderTypesAreAmbiguousRegardlessOfVersion() {
        CapabilityProvider a = provider("risk-scorer-01", "risk-scorer", "1.0.0");
        CapabilityProvider b = provider("risk-assessor-01", "risk-assessor", "9.0.0");

        CapabilityGapRecommendation recommendation = recommender.recommend(gap("riskScore"), List.of(a, b));

        assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.AMBIGUOUS);
    }
}
