# ADR-003: One Shared Camunda Runtime Database Is the Source of Truth

**Status:** Accepted (Version 1.0)

## Context

Once two process instances exist, something has to decide what "the current, correct state" of the whole twin relationship actually is — especially across an application restart. Two obvious candidates exist: the app's own in-memory/persisted bookkeeping (`WorkbenchServiceImpl`'s maps, `WorkbenchStateStore`'s JSON file), or Camunda's own engine tables, which already durably track every execution, variable, and history record for both instances.

## Decision

**Camunda's own runtime and history tables, in the one shared H2 datasource both instances run against, are the authoritative source of truth for anything execution-related** — token position, variable values, activity visit history, event subscriptions. The app's own `WorkbenchStateStore` persists only what Camunda has no concept of at all: the `ProcessModel`/`TwinProcess` records, the `ActivityLink` mapping, and the twin's own human-readable event log.

## Alternatives Investigated

- **App-owned shadow state for execution facts** (e.g. tracking "which activities has the twin visited" in a Java `Set`, as an in-memory collection) — rejected because an in-memory `Set` on `TwinProcess` as a deduplication guard does not survive an application restart. The guard is derived from Camunda's own variable and history state instead — see [ADR-012](ADR-012-restart-and-recovery-philosophy.md).
- **A separate application database for twin metadata** (e.g. a real relational store instead of a JSON file) — not pursued; would improve `WorkbenchStateStore`'s own scaling concerns (Known Limitations, Architecture Specification Section 9) but does not change what class of information belongs there versus in Camunda's tables, which is the actual question this decision answers.

## Verification

This principle is load-bearing for restart and recovery safety: `alreadyEvolved` depends directly on Camunda's own `runtimeService`/`historyService` state (variable existence, and variable update counts via `HistoricDetail`, since `HistoricVariableInstance` alone collapses to only the latest value) rather than on application-side state. Every execution-identity resolution method in `WorkbenchServiceImpl` (`currentVisitId`, `loopCounterOf`, `originalExecutionIdForVisit`, `resolveParallelSibling`, `findWaitingExecutionId`) reads exclusively from `runtimeService`/`historyService`, never from an app-side cache.

## Trade-offs

- **Gained:** restart safety for anything execution-related "for free," since it was never anywhere else to begin with; a single, unambiguous place to look for the true state of either instance (Cockpit works against exactly this data).
- **Given up:** every synchronization decision costs one or more Camunda history/runtime queries instead of a cheap in-memory lookup — accepted because these queries happen once per activity-start event, not in a hot loop, and because a wrong-but-fast in-memory answer is strictly worse than a correct query.

## Consequences

- Any future feature that wants to track "has X happened" for the twin relationship should default to asking "can this be derived from Camunda's own tables" before adding a new field to `TwinProcess` or a new file to `WorkbenchStateStore`. The core design rule states: "do not introduce new state when existing Camunda runtime state can be derived."

## Future Reconsideration

Would be revisited if this system ever ran against multiple, non-colocated Camunda engines (a real distributed twin, not two instances sharing one engine) — not the case today and not designed for.
