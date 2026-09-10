package com.metaml.workbench.capability.gap;

import java.util.Set;

// Locked CapabilityGap lifecycle (MetaML Scope 6, Phase 5, section 3):
//
//   OPEN -> RECOMMENDED -> APPROVED -> BOUND -> RESOLVED
//
// Rejection/expiration paths:
//   RECOMMENDED -> REJECTED
//   OPEN / RECOMMENDED / APPROVED -> EXPIRED
//
// BOUND does not mean RESOLVED: a gap becomes RESOLVED only after an approved provider is bound,
// actually executes, its output passes the Phase 4 capability output contract, and the resulting
// process outputs are successfully propagated. Every other state keeps the originating process
// token blocked - see isBlocking().
public enum GapStatus {
    OPEN,
    RECOMMENDED,
    APPROVED,
    BOUND,
    RESOLVED,
    REJECTED,
    EXPIRED;

    // Legal forward transitions. Deliberately exhaustive and checked by name, not by ordinal, so
    // reordering this enum can never silently change what CapabilityGapService allows.
    private static final Set<GapStatus> TERMINAL = Set.of(RESOLVED, REJECTED, EXPIRED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    // Only RESOLVED permits the blocked process token to continue (section 18).
    public boolean isBlocking() {
        return this != RESOLVED;
    }

    public boolean canExpire() {
        return this == OPEN || this == RECOMMENDED || this == APPROVED;
    }
}
