package com.metaml.workbench.governance;

// PENDING -> APPROVED|REJECTED, then APPROVED -> COMPLETED|FAILED.
// No EXECUTING state: execution happens synchronously inside the call that marks APPROVED, and nothing
// auto-retries on restart, so a third state would carry no recovery behaviour.
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    COMPLETED,
    FAILED
}
