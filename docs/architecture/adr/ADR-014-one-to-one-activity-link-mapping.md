# ADR-014: Activity Links Are Enforced as a One-to-One Bijection

**Status:** Accepted (Version 1.0)

## Context

`connectActivity` is the central method where an `ActivityLink` (`originalActivityId` ↔ `twinActivityId`) is registered ([ADR-006](ADR-006-runtime-derived-execution-identity.md)). Uniqueness must be enforced bidirectionally: no two distinct Original activities may bind to the same Twin activity, as routing tokens, variable assignments, and correlation messages rely on unique activity mappings.

## Decision

`connectActivity` enforces a strict one-to-one bijection:
1. It rejects any link request if the targeted Twin activity is already claimed by a different Original activity.
2. An Original activity may rebind its link to a new Twin activity, releasing its previous target.
3. The check-then-act sequence is synchronized per-twin (`synchronized (twin)`, using the canonical `TwinProcess` reference) to ensure atomicity across concurrent link operations.

## Alternatives Investigated

- **Enforcing the invariant during Twin model generation:** Rejected because model generation occurs statically at deployment time, whereas activity links are established and rebound dynamically during process runtime.
- **Enforcing at message correlation time:** Rejected because allowing invalid mappings to persist in state delays failure detection until runtime message dispatch, complicating debugging and recovery.
- **Relying solely on thread-safe collection operations without monitor synchronization:** Rejected because composite check-then-act operations across `CopyOnWriteArrayList` can interleave under concurrent execution, allowing two callers to bind to the same target before updates become visible. Synchronizing on the `TwinProcess` monitor ensures atomic validation and mutation.

## Technical Validation

Because variables (`evolvedAgent_<twinActivityId>`) and advance messages (`TwinAdvance_<twinActivityId>`) are keyed on `twinActivityId`, collisions would cause state overwrites without engine error indications. Unit and concurrency tests validate this invariant:
- Direct mapping validation confirms rejection when an already-claimed Twin activity is targeted.
- Concurrency stress tests using `CyclicBarrier` verify that race conditions between simultaneous connection requests to the same target activity resolve with exactly one success and one deterministic rejection.

## Trade-offs

- **Gained:** Structural prevention of many-to-one activity link ambiguity; thread-safe atomic link updates; fail-fast rejection with explicit HTTP 400 responses.
- **Given up:** Minimal synchronization overhead on link establishment operations.

## Consequences

- All callers establishing or mutating activity links must route operations through `connectActivity` to ensure policy and monitor invariants are preserved.

## Future Reconsideration

None anticipated; bidirectional uniqueness is a fundamental structural invariant for deterministic token routing.
