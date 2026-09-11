# ADR-012: Restart Safety Is Achieved by Derivation From Camunda History

**Status:** Accepted (Version 1.0)

## Context

Bridge deduplication tracking (`forwardedBridgeActivities`) initially relied on an in-memory `Set<String>` on the `TwinProcess` domain model. While functional during a single JVM session, this state did not survive application restarts, risking duplicate bridge forwarding for already-processed activity instances. In accordance with the runtime principle that the Camunda engine database is the authoritative source of truth ([ADR-003](ADR-003-shared-h2-runtime-as-source-of-truth.md)), bridge forwarding status must be derivable directly from engine runtime and historic records rather than redundant external storage.

## Decision

Bridge deduplication is **fully derived** from Camunda engine state without maintaining independent in-memory sets:

- **Multi-Instance Activities:** Multi-instance activities maintain iteration-specific variables (`evolvedAgent_<twinActivityId>_<loopCounter>`). Deduplication evaluates whether this variable is set via `runtimeService.getVariable` (or `historyService` for completed instances).
- **Looped and Single Activities:** In workflows where an activity may be revisited via loop-back gateways without multi-instance characteristics, variable names remain constant across visits. Deduplication compares the visit ordinal (ordered by start timestamp of the `activityInstanceId`) against the count of discrete variable assignments recorded in `historyService.createHistoricDetailQuery().variableUpdates()`.

The in-memory tracking collection was removed from `TwinProcess`, ensuring that restart recovery relies solely on authoritative database records.

## Alternatives Investigated

- **Persisting bridge sets in JSON state stores:** Storing duplicate tracking structures in secondary configuration stores was rejected because it introduces split-brain synchronization hazards between external storage and Camunda's transactional database.
- **Variable existence checks without historical counts:** A simple existence check on `evolvedAgent_<twinActivityId>` fails on looped activities, where subsequent iterations reuse the same variable name and would be incorrectly treated as duplicate executions.
- **Counting completed automation tasks:** Tracking automation completions fails when automation commands encounter transient errors and roll back while the evolution state write remains committed.
- **Counting discrete variable updates via `HistoricDetailQuery` (Chosen):** Querying historic variable updates reliably accounts for every discrete evolution assignment across loops while remaining unaffected by downstream automation task rollbacks.

## Technical Validation

Verified through automated test suites:
- Restart resilience validation (`FilePersistenceRestartRecoveryTest`, `StaleExecutionStateOnRebindTest`), verifying that newly initialized service instances correctly infer bridge state from the shared database.
- Loop-back regression testing, confirming that repeated visits to the same activity in cyclic graphs execute and bridge sequentially.
- Failure recovery testing, ensuring that automation task exceptions allow retry while preserving the committed evolution binding.

## Trade-offs

- **Gained:** Resilient recovery across restarts; elimination of redundant application caches; alignment with database-backed engine truth.
- **Given up:** Increased query overhead during bridge forwarding evaluation due to `HistoricDetail` lookups in loop-back evaluation branches.

## Consequences

- Bridge forwarding operates idempotently across application restarts and lifecycle transitions.
- The engine's transactional history acts as the unified audit and state record for process evolutions.

## Future Reconsideration

If high-frequency process loops require microsecond bridge evaluations, caching strategies backed by database triggers or event streams may be considered.
