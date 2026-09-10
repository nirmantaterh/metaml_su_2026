package com.metaml.workbench.capability.runtime;

// The runtime state a capability provider reads from and writes to, independent of how the engine
// happened to invoke the activity.
//
// This exists because the same provider has to be executable from two different Camunda shapes that
// share no common type:
//   - a service task with a delegateExpression, which hands out a DelegateExecution
//   - an external task, which hands out a LockedExternalTask and no execution at all
// The Workbench only ever needed the first. A generated Target Platform needs both, because whether
// a Twin activity became a delegate or an external-task worker is decided by the model the customer
// authored, not by us - and a provider must not care which it landed on.
//
// Deliberately minimal: this is the whole surface the reference providers use (getVariable,
// setVariable, getProcessInstanceId) plus the identifiers the output boundary needs to attribute a
// contract violation. It is not a general-purpose facade over DelegateExecution, and it should not
// grow into one - anything added here has to be implementable for BOTH shapes above, and an external
// task genuinely cannot offer most of DelegateExecution's API.
public interface CapabilityExecutionContext {

    // Current value of a process variable, or null when unset. Reads must observe writes already made
    // through setVariable on this same context, so a provider can read back what it just computed.
    Object getVariable(String name);

    // Records a process variable. Whether this reaches the engine immediately or on activity
    // completion is the implementation's business; providers must not depend on the difference.
    void setVariable(String name, Object value);

    String getProcessInstanceId();

    String getProcessDefinitionId();

    // Per-visit identity. Distinguishes multi-instance and looped visits to the same activity, which
    // is what keeps a contract violation attributable to the visit that actually caused it.
    String getActivityInstanceId();

    // May be null: a process instance started without one is legal.
    String getBusinessKey();
}
