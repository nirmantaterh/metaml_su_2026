# ADR-004: Event-Driven Synchronization via `AFTER_COMMIT`, Never Polling

**Status:** Accepted (Version 1.0)

**Scope:** This decision covers only the in-process Original↔Twin bridge described in
[ARCHITECTURE.md](../ARCHITECTURE.md) (one shared Camunda engine, one JVM). It does not describe
and is not a claim about the separately-generated Target Platform pipeline (Proxy/Twin synchronized
over RabbitMQ via a generated `SignalBroadcaster`), which did not exist when this ADR was written
and uses a different mechanism: signal-driven advancement with a 1-second polling coordinator
(`@Scheduled(fixedDelay = 1000)`). See Target Platform messaging documentation for details on generated broker synchronization.

## Context

The Twin needs to learn "the Original just did something" as close to instantaneously as possible, without the two instances being coupled by shared application state, and without introducing a scheduled poll that would add latency, load, and a whole class of "did I already handle this" bugs.

## Decision

Synchronization is driven by `AutoBridgeTrigger.onActivityStarted`, a `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)` on Camunda's own `ExecutionEvent`, republished by the Camunda Spring Boot Starter's `EventPublisherPlugin`. The Original committing an activity-start is the *only* trigger; nothing in this system ever asks "has the Original moved yet?"

## Alternatives Investigated

- **Polling:** Rejected because scheduled polling adds unnecessary latency, load, and synchronization race conditions compared to Camunda's native event streams.
- **A plain `@EventListener` (non-transactional):** Fires before the engine's database transaction commits, causing subsequent `runtimeService` and `historyService` queries to read stale execution state where the activity appears not yet reached.
- **A custom message bus / webhook between the two instances:** Rejected because it duplicates what Spring transaction synchronization and Camunda native event publishing provide in-process.

## Verification

`AutoBridgeTrigger.java` documents the transaction boundary requirements. Walkthrough tests (`WireTransferWalkthroughTest`, `TwinExecutionWalkthroughTest`) validate this path by completing an Original task and asserting the Twin has advanced immediately without artificial delays or polling intervals.

## Trade-offs

- **Gained:** near-instant synchronization with zero added latency budget, no polling infrastructure, no "missed event" window beyond ordinary transaction-commit semantics.
- **Given up:** synchronization logic is now coupled to Spring's transaction-synchronization lifecycle, which is a more specialized mechanism than a generic listener and required the `AFTER_COMMIT` phase to be discovered empirically rather than being obvious from the API surface.

## Consequences

- Any code that needs to react to an Original activity being reached must go through this same `AFTER_COMMIT`-phase listener pattern, or it will inherit the exact "reads as not-yet-reached" race condition described above.
- The listener must never let an exception escape to the caller (Section 4 of the Architecture Specification) — a design constraint that flows directly from choosing a synchronous, in-process trigger over a decoupled queue.

## Future Reconsideration

Would be revisited only if this system needed to synchronize across process boundaries (e.g. a Twin running in a different JVM/engine than the Original), at which point `AFTER_COMMIT` alone would no longer suffice and a real message broker would need its own ADR.
