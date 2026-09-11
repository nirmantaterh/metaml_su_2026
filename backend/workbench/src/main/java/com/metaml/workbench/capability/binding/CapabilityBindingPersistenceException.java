package com.metaml.workbench.capability.binding;

// Thrown by CapabilityBindingStore.save() when the durable current-binding representation cannot be
// written, and left to propagate through CapabilityBindingRegistry.upsert() uncaught (P7 Step 5).
//
// This is a deliberate divergence from CapabilityGapStore, which swallows a write failure and logs a
// warning: a capability gap record is advisory bookkeeping, and the same unsatisfied condition simply
// gets re-reported the next time it is hit, so losing one is inconvenient rather than unsafe. This
// store is different in kind - it is the sole external representation a standalone Target Platform
// will ever trust for "what the Workbench approved", with no Camunda process instance of its own to
// fall back on. A binding operation whose durable half silently failed must never be reported to its
// caller as successful, so this class throws instead of logging-and-continuing, and its caller
// (WorkbenchServiceImpl.executeAfterGovernance) is required to treat it as the operation failing, not
// as a background inconvenience.
public class CapabilityBindingPersistenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CapabilityBindingPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
