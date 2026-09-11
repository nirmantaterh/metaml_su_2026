package com.metaml.workbench.capability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Pure, deterministic, side-effect-free provider satisfaction and resolution. No Camunda, no
// RabbitMQ, no AI, no governance, no process state, no business-domain logic - just set/type
// comparison over the capability records in this package.
public final class CapabilitySatisfaction {

    private CapabilitySatisfaction() {
    }

    public enum Status {
        NO_SUITABLE_PROVIDER,
        RECOMMENDED,
        AMBIGUOUS
    }

    // The dedicated result type this algorithm needs, kept nested here rather than as its own file
    // per the Phase 0 file allowlist. selected is non-null iff status == RECOMMENDED.
    public record Resolution(Status status, CapabilityProvider selected) {
        public Resolution {
            Objects.requireNonNull(status, "status must not be null");
            if (status == Status.RECOMMENDED && selected == null) {
                throw new IllegalArgumentException("RECOMMENDED resolution requires a selected provider");
            }
            if (status != Status.RECOMMENDED && selected != null) {
                throw new IllegalArgumentException(status + " resolution must not carry a selected provider");
            }
        }
    }

    // UNKNOWN means "not declared", never a business fallback, so it widens compatibility in both
    // directions rather than narrowing it.
    public static boolean typeCompatible(IoType a, IoType b) {
        return a == b || a == IoType.UNKNOWN || b == IoType.UNKNOWN;
    }

    // availableInputs carries names and types only, mirroring CapabilityGap.availableInputs - never
    // business values. Neither the provider's collections, the requirement's, nor availableInputs
    // are mutated.
    public static boolean satisfies(CapabilityProvider provider, CapabilityContract requirement,
            Map<String, IoType> availableInputs) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(requirement, "requirement must not be null");
        Map<String, IoType> inputs = availableInputs == null ? Map.of() : availableInputs;

        if (!provider.available()) {
            return false;
        }
        if (requirement.capabilityId() != null
                && !requirement.capabilityId().equals(provider.contract().capabilityId())) {
            return false;
        }
        for (IoDeclaration requiredOutput : requirement.producedOutputs()) {
            if (!requiredOutput.required()) {
                continue;
            }
            IoDeclaration produced = findByName(provider.contract().producedOutputs(), requiredOutput.name());
            if (produced == null || !typeCompatible(produced.type(), requiredOutput.type())) {
                return false;
            }
        }
        for (IoDeclaration requiredInput : provider.contract().requiredInputs()) {
            if (!requiredInput.required()) {
                continue;
            }
            IoType availableType = inputs.get(requiredInput.name());
            if (availableType == null || !typeCompatible(availableType, requiredInput.type())) {
                return false;
            }
        }
        for (Map.Entry<String, Object> constraint : requirement.constraints().entrySet()) {
            Object providerValue = provider.contract().constraints().get(constraint.getKey());
            if (!Objects.equals(providerValue, constraint.getValue())) {
                return false;
            }
        }
        return true;
    }

    // Every provider in `providers` that individually satisfies `requirement` (per satisfies()
    // above), in input order. This is the SAME set resolve() itself computes internally to decide
    // RECOMMENDED/AMBIGUOUS/NO_SUITABLE_PROVIDER (resolve() is implemented in terms of this method,
    // not a second copy of the loop) - the one authoritative place "which providers legitimately
    // satisfy this requirement" is computed, so a CapabilityGap's candidateProviderIds and a
    // Resolution's status/selected can never disagree about who satisfies. Never mutates `providers`
    // or `availableInputs`.
    public static List<CapabilityProvider> satisfyingProviders(CapabilityContract requirement,
            Collection<CapabilityProvider> providers, Map<String, IoType> availableInputs) {
        Objects.requireNonNull(requirement, "requirement must not be null");
        Collection<CapabilityProvider> candidates = providers == null ? List.of() : providers;

        List<CapabilityProvider> satisfying = new ArrayList<>();
        for (CapabilityProvider provider : candidates) {
            if (satisfies(provider, requirement, availableInputs)) {
                satisfying.add(provider);
            }
        }
        return satisfying;
    }

    // Convenience projection of satisfyingProviders() onto provider identity alone - the shape
    // CapabilityGap.candidateProviderIds and every other identity-only candidate list in this
    // codebase need (section 7: candidate lists are identity only, never the provider's contract or
    // a business output).
    public static List<String> satisfyingProviderIds(CapabilityContract requirement,
            Collection<CapabilityProvider> providers, Map<String, IoType> availableInputs) {
        List<String> ids = new ArrayList<>();
        for (CapabilityProvider provider : satisfyingProviders(requirement, providers, availableInputs)) {
            ids.add(provider.providerId());
        }
        return ids;
    }

    // 0 satisfying -> NO_SUITABLE_PROVIDER. 1 -> RECOMMENDED. >1 across different providerTypes ->
    // AMBIGUOUS, never auto-picked. >1 within one providerType -> highest semver wins; an equal-top
    // tie is AMBIGUOUS rather than silently resolved. The order of `providers` never affects the
    // outcome.
    public static Resolution resolve(CapabilityContract requirement, Collection<CapabilityProvider> providers,
            Map<String, IoType> availableInputs) {
        List<CapabilityProvider> satisfying = satisfyingProviders(requirement, providers, availableInputs);

        if (satisfying.isEmpty()) {
            return new Resolution(Status.NO_SUITABLE_PROVIDER, null);
        }
        if (satisfying.size() == 1) {
            return new Resolution(Status.RECOMMENDED, satisfying.get(0));
        }

        String firstProviderType = satisfying.get(0).providerType();
        for (CapabilityProvider provider : satisfying) {
            if (!provider.providerType().equals(firstProviderType)) {
                // Different provider families never get auto-picked across each other.
                return new Resolution(Status.AMBIGUOUS, null);
            }
        }

        CapabilityProvider highest = satisfying.get(0);
        int tiesAtHighest = 1;
        for (int i = 1; i < satisfying.size(); i++) {
            CapabilityProvider candidate = satisfying.get(i);
            int comparison = compareVersions(candidate.version(), highest.version());
            if (comparison > 0) {
                highest = candidate;
                tiesAtHighest = 1;
            } else if (comparison == 0) {
                tiesAtHighest++;
            }
        }
        if (tiesAtHighest > 1) {
            return new Resolution(Status.AMBIGUOUS, null);
        }
        return new Resolution(Status.RECOMMENDED, highest);
    }

    private static IoDeclaration findByName(Set<IoDeclaration> declarations, String name) {
        for (IoDeclaration declaration : declarations) {
            if (declaration.name().equals(name)) {
                return declaration;
            }
        }
        return null;
    }

    // Dot-separated numeric-part comparison (major.minor.patch, extendable to further parts).
    // Missing trailing parts compare as 0; a non-numeric part compares as 0 against its counterpart
    // rather than throwing, so an unparsable version never breaks determinism - it simply falls
    // through to the next part, or ties if none decide it.
    private static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int length = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < length; i++) {
            long valueA = numericPart(partsA, i);
            long valueB = numericPart(partsB, i);
            int comparison = Long.compare(valueA, valueB);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static long numericPart(String[] parts, int index) {
        if (index >= parts.length) {
            return 0L;
        }
        try {
            return Long.parseLong(parts[index].trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
