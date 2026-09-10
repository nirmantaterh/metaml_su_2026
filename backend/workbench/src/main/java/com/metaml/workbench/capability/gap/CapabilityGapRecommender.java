package com.metaml.workbench.capability.gap;

import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.CapabilitySatisfaction;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// The "AI recommendation" boundary this phase actually has evidence for (MetaML Scope 6, Phase 5,
// section 14 and section 22 preflight finding 20/"AI boundary"). Repository evidence found no
// existing AiDecisionRequest type, AI client, or LLM boundary anywhere in this codebase (see the P5
// final report's preflight section) - RecommenderExecutor is one of the five reference business
// capability providers from the Provider Contract Authorship phase, not a platform AI subsystem.
//
// Section 14 forbids inventing provider ids, capabilities, or outputs, and forbids a second AI
// subsystem. Building a new LLM-calling subsystem here would be exactly that: a new subsystem, and
// squarely P6 scope this phase must not pull forward. The smallest generic extension that satisfies
// section 14's actual constraints - and, by construction, can never fabricate a provider id,
// capability, or output - is a deterministic recommender that wraps the already-locked Phase 0
// CapabilitySatisfaction algorithm: it reports exactly what that algorithm resolves, with a
// rationale describing the resolution, never inventing anything the catalog and contract did not
// already state. If a real AI boundary is introduced in a later phase, this class is the seam where
// it plugs in - CapabilityGapService depends on the small interface below, not on this
// implementation.
@Component
public class CapabilityGapRecommender {

    public CapabilityGapRecommendation recommend(CapabilityGap gap, Collection<CapabilityProvider> candidates) {
        CapabilitySatisfaction.Resolution resolution =
                CapabilitySatisfaction.resolve(gap.requiredContract(), candidates, gap.availableInputs());

        List<String> alternativeIds = alternativeProviderIds(gap, candidates, resolution);

        return switch (resolution.status()) {
            case RECOMMENDED -> new CapabilityGapRecommendation(gap.gapId(), resolution.selected().providerId(),
                    "Exactly one available provider ('" + resolution.selected().providerId()
                            + "', type '" + resolution.selected().providerType()
                            + "') satisfies the gap's required contract given the currently available inputs.",
                    1.0, alternativeIds, CapabilityGapRecommendation.Status.RECOMMENDED);
            case NO_SUITABLE_PROVIDER -> new CapabilityGapRecommendation(gap.gapId(), null,
                    "No available provider in the current catalog satisfies the gap's required contract "
                            + "given the currently available inputs.",
                    0.0, List.of(), CapabilityGapRecommendation.Status.NO_SUITABLE_PROVIDER);
            case AMBIGUOUS -> new CapabilityGapRecommendation(gap.gapId(), null,
                    "More than one provider satisfies the gap's required contract with no unambiguous "
                            + "highest-semver winner within a single provider type, or the satisfying providers "
                            + "span more than one provider type; human selection is required.",
                    0.0, alternativeIds, CapabilityGapRecommendation.Status.AMBIGUOUS);
        };
    }

    // Every provider that actually satisfies the requirement, for visibility into what the human
    // approver is choosing between - never a provider the satisfaction algorithm itself rejected.
    // Reuses CapabilitySatisfaction's own satisfying-provider set rather than a second copy of the
    // matching loop, so this can never disagree with the candidate set a CapabilityGap itself carries.
    private static List<String> alternativeProviderIds(CapabilityGap gap, Collection<CapabilityProvider> candidates,
            CapabilitySatisfaction.Resolution resolution) {
        List<String> ids = new ArrayList<>();
        for (CapabilityProvider provider : CapabilitySatisfaction.satisfyingProviders(gap.requiredContract(),
                candidates, gap.availableInputs())) {
            if (resolution.selected() == null || !provider.providerId().equals(resolution.selected().providerId())) {
                ids.add(provider.providerId());
            }
        }
        return ids;
    }
}
