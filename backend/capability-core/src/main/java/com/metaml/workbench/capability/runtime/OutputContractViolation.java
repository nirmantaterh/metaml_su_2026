package com.metaml.workbench.capability.runtime;

import com.metaml.workbench.capability.IoType;

import java.util.Objects;

// One structured capability-output-contract violation (MetaML Scope 6, Phase 4). Immutable.
//
// Every field a caller could need to react programmatically is a typed field, so nothing has to be
// recovered by parsing a message string. The identity fields answer "which provider, on which
// activity, on which visit of that activity" and the output fields answer "which declaration, and
// what actually turned up".
//
// actualType deliberately holds the runtime value's *type name*, never the value itself: a
// violation is logged, put on a Camunda incident message, and surfaced to operators, so carrying
// the business value would leak it into all three. declaredType is null only for
// UNDECLARED_OUTPUT, where by definition no declaration exists; actualType is null only for
// MISSING_DECLARED_OUTPUT and INVALID_NULL_OUTPUT, where by definition no runtime value exists.
public record OutputContractViolation(OutputContractViolationKind kind, String providerId,
        String providerType, String providerVersion, String activityId, String activityInstanceId,
        String outputName, IoType declaredType, String actualType) {

    public OutputContractViolation {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(outputName, "outputName must not be null");
    }

    // Single-line, value-free rendering used to build the exception message that ends up on the
    // Camunda incident (see ADR-008). Kept here so message shape stays with the data it describes.
    public String describe() {
        return kind + " outputName=" + outputName
                + " declaredType=" + declaredType
                + " actualType=" + actualType;
    }
}
