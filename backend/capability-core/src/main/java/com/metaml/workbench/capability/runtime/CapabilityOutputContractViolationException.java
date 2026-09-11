package com.metaml.workbench.capability.runtime;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

// Thrown when an actual provider execution result does not satisfy the provider's declared
// CapabilityContract.producedOutputs() (MetaML Scope 6, Phase 4).
//
// Unchecked on purpose, and that is a runtime-architecture decision rather than a convenience one:
// the only place this is raised is inside CapabilityOutputPropagator, called from
// TwinAutomationDelegate, which runs inside a synchronous Camunda command. An unchecked throw there
// rolls that whole command back - correlation and every setVariable made during it - and surfaces
// at WorkbenchServiceImpl.advanceTwinActivity, which releases the governance slot and records a
// real "twinAutomationFailure" Camunda incident against the exact leaf execution. That is the
// repository's existing, ADR-008-locked failure mechanism for a synchronous automation failure;
// this exception joins it rather than introducing a second one.
//
// violations() is the programmatic contract. getMessage() is for the incident/operator log only -
// never parse it.
public class CapabilityOutputContractViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<OutputContractViolation> violations;

    public CapabilityOutputContractViolationException(List<OutputContractViolation> violations) {
        super(buildMessage(violations));
        this.violations = List.copyOf(violations);
    }

    public List<OutputContractViolation> violations() {
        return violations;
    }

    private static String buildMessage(List<OutputContractViolation> violations) {
        Objects.requireNonNull(violations, "violations must not be null");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("a contract violation exception needs at least one violation");
        }
        OutputContractViolation first = violations.get(0);
        return "Capability output contract violated by provider '" + first.providerId()
                + "' (type " + first.providerType() + ", version " + first.providerVersion()
                + ") on activity " + first.activityId()
                + " [activityInstance " + first.activityInstanceId() + "]: "
                + violations.stream().map(OutputContractViolation::describe)
                        .collect(Collectors.joining("; "));
    }
}
