package com.metaml.workbench.capability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// What a capability requires and produces. Immutable and Jackson-serializable.
//
// capabilityId is a stable semantic id (e.g. "decision.binary"). A null capabilityId - always true
// for a requirement derived from BPMN rather than authored explicitly - means identity matching is
// skipped in CapabilitySatisfaction and only the input/output shape is compared.
//
// Identity is structural equality over every field. There is no version field: versions belong to
// CapabilityProvider, not to the contract a provider satisfies.
public record CapabilityContract(String capabilityId, Set<IoDeclaration> requiredInputs,
        Set<IoDeclaration> producedOutputs, ExecutionMode executionMode, Map<String, Object> constraints,
        Set<String> governanceLabels) {

    public CapabilityContract {
        Objects.requireNonNull(executionMode, "executionMode must not be null");
        requiredInputs = immutableCopy(requiredInputs);
        producedOutputs = immutableCopy(producedOutputs);
        governanceLabels = immutableCopy(governanceLabels);
        constraints = constraints == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(constraints));
    }

    private static <T> Set<T> immutableCopy(Set<T> source) {
        return source == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
