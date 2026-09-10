package com.metaml.workbench.capability;

import static org.assertj.core.api.Assertions.assertThat;

import com.metaml.workbench.capability.CapabilitySatisfaction.Resolution;
import com.metaml.workbench.capability.CapabilitySatisfaction.Status;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Synthetic, business-domain-independent coverage of the Phase 0 satisfaction and resolution
// algorithm. Uses synthetic names (inputA/inputB/decision/result, provider-a/provider-b) per the
// Phase 0 scope boundary - no enterprise-specific agents or rules.
class CapabilitySatisfactionTest {

    private static final IoDeclaration REQUIRED_DECISION_OUTPUT = new IoDeclaration("decision", IoType.BOOLEAN, true);

    // --- capabilityId matching ---

    @Test
    void matchingCapabilityIdSatisfies() {
        CapabilityContract requirement = requirement("decision.binary", Set.of(), Set.of());
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract("decision.binary", Set.of(), Set.of(), Map.of()));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isTrue();
    }

    @Test
    void mismatchedCapabilityIdFailsSatisfaction() {
        CapabilityContract requirement = requirement("decision.binary", Set.of(), Set.of());
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract("decision.other", Set.of(), Set.of(), Map.of()));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isFalse();
    }

    @Test
    void nullRequirementCapabilityIdSkipsIdentityMatching() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract("anything.at.all", Set.of(), Set.of(), Map.of()));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isTrue();
    }

    // --- output name / type ---

    @Test
    void requiredOutputNameMatchSatisfies() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of(REQUIRED_DECISION_OUTPUT));
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(new IoDeclaration("decision", IoType.BOOLEAN, false)), Map.of()));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isTrue();
    }

    @Test
    void outputNameMismatchFails() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of(REQUIRED_DECISION_OUTPUT));
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(new IoDeclaration("result", IoType.BOOLEAN, false)), Map.of()));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isFalse();
    }

    @Test
    void outputTypeMatchSatisfies() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of(REQUIRED_DECISION_OUTPUT));
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(new IoDeclaration("decision", IoType.BOOLEAN, false)), Map.of()));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isTrue();
    }

    @Test
    void unknownOutputTypeIsCompatibleBothWays() {
        assertThat(CapabilitySatisfaction.typeCompatible(IoType.UNKNOWN, IoType.BOOLEAN)).isTrue();
        assertThat(CapabilitySatisfaction.typeCompatible(IoType.BOOLEAN, IoType.UNKNOWN)).isTrue();
        assertThat(CapabilitySatisfaction.typeCompatible(IoType.UNKNOWN, IoType.UNKNOWN)).isTrue();
    }

    @Test
    void incompatibleOutputTypeFails() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of(REQUIRED_DECISION_OUTPUT));
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(new IoDeclaration("decision", IoType.STRING, false)), Map.of()));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isFalse();
        assertThat(CapabilitySatisfaction.typeCompatible(IoType.STRING, IoType.BOOLEAN)).isFalse();
    }

    // --- input availability ---

    @Test
    void requiredProviderInputSatisfiedByAvailableInputs() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(new IoDeclaration("inputA", IoType.NUMBER, true)), Set.of(), Map.of()));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of("inputA", IoType.NUMBER))).isTrue();
    }

    @Test
    void requiredProviderInputUnavailableFails() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(new IoDeclaration("inputA", IoType.NUMBER, true)), Set.of(), Map.of()));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isFalse();
    }

    @Test
    void incompatibleAvailableInputTypeFails() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(new IoDeclaration("inputA", IoType.NUMBER, true)), Set.of(), Map.of()));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of("inputA", IoType.STRING)))
                .isFalse();
    }

    @Test
    void optionalInputDoesNotParticipate() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(new IoDeclaration("inputB", IoType.NUMBER, false)), Set.of(), Map.of()));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isTrue();
    }

    @Test
    void optionalOutputDoesNotParticipate() {
        CapabilityContract requirement = requirement(null, Set.of(),
                Set.of(new IoDeclaration("decision", IoType.BOOLEAN, false)));
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isTrue();
    }

    // --- constraints ---

    @Test
    void matchingConstraintsSatisfy() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of(), Map.of("threshold", 5));
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of("threshold", 5)));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isTrue();
    }

    @Test
    void mismatchedConstraintsFail() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of(), Map.of("threshold", 5));
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of("threshold", 6)));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isFalse();
    }

    // --- availability ---

    @Test
    void unavailableProviderIsExcluded() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider unavailable = new CapabilityProvider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()), null, false, "offline for maintenance");

        assertThat(CapabilitySatisfaction.satisfies(unavailable, requirement, Map.of())).isFalse();

        Resolution resolution = CapabilitySatisfaction.resolve(requirement, List.of(unavailable), Map.of());
        assertThat(resolution.status()).isEqualTo(Status.NO_SUITABLE_PROVIDER);
        assertThat(resolution.selected()).isNull();
    }

    // --- resolution ---

    @Test
    void zeroProvidersResolvesToNoSuitableProvider() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());

        Resolution resolution = CapabilitySatisfaction.resolve(requirement, List.of(), Map.of());

        assertThat(resolution.status()).isEqualTo(Status.NO_SUITABLE_PROVIDER);
        assertThat(resolution.selected()).isNull();
    }

    @Test
    void oneSatisfyingProviderResolvesToRecommended() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));

        Resolution resolution = CapabilitySatisfaction.resolve(requirement, List.of(provider), Map.of());

        assertThat(resolution.status()).isEqualTo(Status.RECOMMENDED);
        assertThat(resolution.selected()).isEqualTo(provider);
    }

    @Test
    void multipleSameProviderTypeResolvesToHighestSemver() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider low = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));
        CapabilityProvider high = provider("provider-b", "decision-maker", "2.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));

        Resolution resolution = CapabilitySatisfaction.resolve(requirement, List.of(low, high), Map.of());

        assertThat(resolution.status()).isEqualTo(Status.RECOMMENDED);
        assertThat(resolution.selected()).isEqualTo(high);

        // order must not matter
        Resolution reversed = CapabilitySatisfaction.resolve(requirement, List.of(high, low), Map.of());
        assertThat(reversed.selected()).isEqualTo(high);
    }

    @Test
    void equalHighestSemverIsAmbiguous() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider first = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));
        CapabilityProvider second = provider("provider-b", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));

        Resolution resolution = CapabilitySatisfaction.resolve(requirement, List.of(first, second), Map.of());

        assertThat(resolution.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(resolution.selected()).isNull();
    }

    @Test
    void differentProviderTypesAreAmbiguousRegardlessOfVersion() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider decisionMaker = provider("provider-a", "decision-maker", "5.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));
        CapabilityProvider enricher = provider("provider-b", "data-enricher", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));

        Resolution resolution = CapabilitySatisfaction.resolve(requirement, List.of(decisionMaker, enricher),
                Map.of());

        assertThat(resolution.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(resolution.selected()).isNull();
    }

    // --- determinism ---

    @Test
    void repeatedResolutionIsIdenticalAndSideEffectFree() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider low = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));
        CapabilityProvider high = provider("provider-b", "decision-maker", "2.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));
        List<CapabilityProvider> providers = List.of(low, high);

        Resolution first = CapabilitySatisfaction.resolve(requirement, providers, Map.of());
        Resolution second = CapabilitySatisfaction.resolve(requirement, providers, Map.of());
        Resolution third = CapabilitySatisfaction.resolve(requirement, providers, Map.of());

        assertThat(first).isEqualTo(second).isEqualTo(third);
        // resolve() must not have mutated the caller-supplied list
        assertThat(providers).containsExactly(low, high);
    }

    @Test
    void suppliedAvailableInputsMapIsNotMutated() {
        CapabilityContract requirement = requirement(null,
                Set.of(new IoDeclaration("inputA", IoType.NUMBER, true)), Set.of());
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(new IoDeclaration("inputA", IoType.NUMBER, true)), Set.of(), Map.of()));
        Map<String, IoType> availableInputs = new HashMap<>();
        availableInputs.put("inputA", IoType.NUMBER);
        int sizeBefore = availableInputs.size();

        CapabilitySatisfaction.satisfies(provider, requirement, availableInputs);

        assertThat(availableInputs).hasSize(sizeBefore);
        assertThat(availableInputs).containsEntry("inputA", IoType.NUMBER);
    }

    @Test
    void noGlobalMutableStateBetweenCalls() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider providerA = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));

        Resolution firstCallResult = CapabilitySatisfaction.resolve(requirement, List.of(providerA), Map.of());
        // a second, unrelated resolve() call must not influence a result already returned
        CapabilityProvider providerB = provider("provider-b", "decision-maker", "9.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));
        CapabilitySatisfaction.resolve(requirement, List.of(providerB), Map.of());

        assertThat(firstCallResult.status()).isEqualTo(Status.RECOMMENDED);
        assertThat(firstCallResult.selected()).isEqualTo(providerA);
    }

    // --- satisfyingProviders / satisfyingProviderIds (MetaML Scope 6, Phase 6 correction): the
    // shared candidate-derivation surface CapabilityGapService.report() now uses as the single
    // authoritative source of a CapabilityGap's candidateProviderIds. ---

    @Test
    void satisfyingProviderIdsIsEmptyWhenNoProviderSatisfies() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of(REQUIRED_DECISION_OUTPUT));
        CapabilityProvider mismatched = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));

        List<String> ids = CapabilitySatisfaction.satisfyingProviderIds(requirement, List.of(mismatched), Map.of());

        assertThat(ids).isEmpty();
    }

    @Test
    void satisfyingProviderIdsContainsExactlyTheOneSatisfyingProvider() {
        CapabilityContract requirement = requirement("decision.binary", Set.of(), Set.of());
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0",
                contract("decision.binary", Set.of(), Set.of(), Map.of()));
        CapabilityProvider other = provider("provider-b", "data-enricher", "1.0.0",
                contract("something.else", Set.of(), Set.of(), Map.of()));

        List<String> ids = CapabilitySatisfaction.satisfyingProviderIds(requirement, List.of(provider, other),
                Map.of());

        assertThat(ids).containsExactly("provider-a");
    }

    @Test
    void satisfyingProviderIdsContainsEveryProviderWhenMultipleSatisfy() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider a = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));
        CapabilityProvider b = provider("provider-b", "data-enricher", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));
        CapabilityProvider c = provider("provider-c", "notifier", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));

        List<String> ids = CapabilitySatisfaction.satisfyingProviderIds(requirement, List.of(a, b, c), Map.of());

        // Never reduced to resolve()'s eventual RECOMMENDED/AMBIGUOUS pick - every satisfying
        // provider must be present, regardless of how many provider families are represented.
        assertThat(ids).containsExactlyInAnyOrder("provider-a", "provider-b", "provider-c");
    }

    @Test
    void satisfyingProvidersAndResolveAgreeOnWhoSatisfies() {
        CapabilityContract requirement = requirement(null, Set.of(), Set.of());
        CapabilityProvider low = provider("provider-a", "decision-maker", "1.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));
        CapabilityProvider high = provider("provider-b", "decision-maker", "2.0.0",
                contract(null, Set.of(), Set.of(), Map.of()));

        List<CapabilityProvider> satisfying = CapabilitySatisfaction.satisfyingProviders(requirement,
                List.of(low, high), Map.of());
        Resolution resolution = CapabilitySatisfaction.resolve(requirement, List.of(low, high), Map.of());

        // resolve() is implemented in terms of satisfyingProviders() - its selected winner must
        // always be a member of that same set, never a provider satisfyingProviders() excluded.
        assertThat(satisfying).contains(resolution.selected());
    }

    // --- helpers ---

    private static CapabilityContract requirement(String capabilityId, Set<IoDeclaration> requiredInputs,
            Set<IoDeclaration> producedOutputs) {
        return requirement(capabilityId, requiredInputs, producedOutputs, Map.of());
    }

    private static CapabilityContract requirement(String capabilityId, Set<IoDeclaration> requiredInputs,
            Set<IoDeclaration> producedOutputs, Map<String, Object> constraints) {
        return contract(capabilityId, requiredInputs, producedOutputs, constraints);
    }

    private static CapabilityContract contract(String capabilityId, Set<IoDeclaration> requiredInputs,
            Set<IoDeclaration> producedOutputs, Map<String, Object> constraints) {
        return new CapabilityContract(capabilityId, requiredInputs, producedOutputs, ExecutionMode.SYNCHRONOUS,
                constraints, Set.of());
    }

    private static CapabilityProvider provider(String providerId, String providerType, String version,
            CapabilityContract contract) {
        return new CapabilityProvider(providerId, providerType, version, contract, "synthetic test provider",
                true, null);
    }
}
