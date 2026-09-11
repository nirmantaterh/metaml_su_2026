# ADR-006: Execution Identity Is Recomputed at Runtime, Never Cached — Except One Mapping

**Status:** Accepted (Version 1.0)

## Context

Synchronizing two process instances requires answering, over and over, "which specific visit of which specific activity, on which specific execution, are we talking about right now." There are two ways to answer that: persist the answer once and trust it, or recompute it fresh from Camunda every time it's needed.

## Decision

Exactly **one** cross-process identity mapping is persisted: `ActivityLink` (`originalActivityId` ↔ `twinActivityId`), because it is the one piece of information Camunda's own tables have no way to reconstruct — it's an app-level *intent* ("this Original activity corresponds to this Twin activity"), not a runtime fact. Everything else — which visit, which execution, which loop iteration — is recomputed from Camunda's `runtimeService`/`historyService` on every synchronization event, deliberately never cached.

## Alternatives Investigated

- **Persisting a full visit-to-visit mapping** (e.g. "Original visit #3 of Task_X corresponds to Twin visit #3") — not pursued; it is the kind of derivable-but-duplicated state [ADR-003](ADR-003-shared-h2-runtime-as-source-of-truth.md) rules out, and would require complex restart synchronization.
- **Caching resolved executions/activity-instance-ids in app memory for the lifetime of a request** — not pursued for the same reason; every resolution method (`currentVisitId`, `loopCounterOf`, `resolveParallelSibling`, `originalExecutionIdForVisit`, `findWaitingExecutionId`) queries fresh, on the reasoning that these are not hot-loop operations (once per activity-start event) and a stale cached answer is a much worse failure mode than a slightly more expensive correct one.

## Verification

`ActivityLink`'s own field shape (`originalActivityId`, `twinActivityId`, nothing else) is the whole persisted footprint. Every other identity-resolution method in `WorkbenchServiceImpl` takes no cached input beyond ids already known to the caller and queries `runtimeService`/`historyService` directly. The non-local vs. local `loopCounter` scope asymmetry (Architecture Specification, Section 6) was discovered specifically *because* this system insists on deriving rather than assuming — an assumed-symmetric implementation would have silently returned `null` for one side.

## Trade-offs

- **Gained:** correctness that survives restarts, concurrent visits, and even application bugs in unrelated bookkeeping — because there is very little bookkeeping left to have a bug in.
- **Given up:** every synchronization event pays the cost of several Camunda queries instead of a map lookup; accepted as the right trade given the alternative's restart-safety cost, verified by restart and recovery test suites.

## Consequences

- Adding any new "which visit" concept to this system should default to a recomputation method modeled on the existing ones (walk the `ActivityInstance` tree, or use `HistoricActivityInstance`/`HistoricDetail` queries), not a new field on `TwinProcess`.
- `ActivityLink`'s one-to-one enforcement ([ADR-014](ADR-014-one-to-one-activity-link-mapping.md)) exists precisely because this *is* the one piece of state that must stay correct — everything downstream keys off it.

## Future Reconsideration

Would be revisited if a future requirement needed cross-restart audit of "exactly which visit corresponded to which" beyond what Camunda's own history retention already provides — not currently needed.
