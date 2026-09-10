package com.metaml.workbench.capability.runtime;

// The four ways an actual provider execution result can violate the provider's declared
// CapabilityContract.producedOutputs() (MetaML Scope 6, Phase 4).
//
// These are structured discriminators, never parsed out of a human-readable message: callers switch
// on the enum, and OutputContractViolation carries the rest of the detail as typed fields.
public enum OutputContractViolationKind {

    // The execution result carries an output name the provider's contract never declared. An empty
    // producedOutputs() therefore admits nothing at all - it is a contract that declares no outputs,
    // never a wildcard.
    UNDECLARED_OUTPUT,

    // A name declared in producedOutputs() is absent as a key from the execution result. Absence is
    // never substituted with a default, a null, a false, a zero, or an empty string.
    MISSING_DECLARED_OUTPUT,

    // A declared output is present with a non-null value whose runtime type is not compatible with
    // its declared IoType under the existing Phase 0 CapabilitySatisfaction.typeCompatible rule.
    // There is no coercion: a declared BOOLEAN is not satisfied by the String "true".
    TYPE_MISMATCH,

    // A declared output is present as a key but carries a null value. Distinct from
    // MISSING_DECLARED_OUTPUT on purpose: null is a value the provider chose to return, not an
    // absence, and no generic nullable-output contract exists in this repository to permit it.
    INVALID_NULL_OUTPUT
}
