# ADR-008: Automation Failures Are Incident-Driven, Not Automatically Retried

**Status:** Accepted (Version 1.0)

## Context

The Twin's automation Service Task (`TwinAutomationDelegate`, dispatching to a `ProjectAutomationService`) can throw. Camunda 7 offers several native mechanisms for handling a failure in an activity: Job Executor retry/incidents (job-based only), BPMN Error Boundary Events, transaction rollback, and manual Incident creation via `RuntimeService`. This decision required first defining what the Twin's failure semantics should even *be* — Fail-Fast, Retry, Incident-Driven, or Recoverable — before picking a mechanism to implement whichever was chosen.

## Decision

**Incident-Driven.** On a synchronous automation failure, `WorkbenchServiceImpl.advanceTwinActivity` releases the governance slot it had reserved, records a real Camunda Incident (`runtimeService.createIncident("twinAutomationFailure", executionId, twinActivityId, message)`) against the exact leaf execution, and leaves the Twin's Receive Task exactly where it was — paused, safe, waiting for an operator to explicitly re-bridge.

## Alternatives Investigated

- **Fail-Fast (surface the exception, take no further action)** — rejected as offering the operator no path back other than manually inspecting logs; loses the visibility Cockpit's own Incident view provides for free.
- **Automatic Retry** — rejected specifically because `ProjectAutomationService.execute()` carries no documented idempotency contract; a blanket retry risks double-invoking something that charges a quota or calls an external agent with a real side effect, and generic orchestration code has no way to know whether that's safe for any given project's implementation.
- **Job Executor–based retry/incident** — Camunda's native job-retry mechanism is exclusively a Job Executor concept, requiring the Service Task to be asynchronous. Making it async to get that machinery back would reopen the exact coupling ADR-009 exists to avoid (the earlier `asyncBefore` design that required disabling the Job Executor and broke boundary timers/history cleanup).
- **BPMN-native Error Boundary Event retry loop** — genuinely investigated and proven viable: Error Boundary Events were empirically confirmed to catch even a plain unchecked exception thrown inside a synchronous Service Task, not just an explicitly-thrown `BpmnError`, opening a real possibility for a BPMN-modeled retry loop. Deliberately **not** built, because it would mean generating retry-loop BPMN structure into every Twin definition regardless of whether any given project's automation actually benefits from bounded retry — a policy decision that, per the Retry rejection above, belongs inside a specific project's own automation, not imposed uniformly by the generator.

## Verification

Proven, not assumed: a throwaway probe made an automation delegate throw, confirmed the Receive Task's event subscription survived unchanged (the whole Camunda command — correlation plus automation — rolls back together), then manually created and resolved an Incident against that same execution and successfully retried the identical correlation. `TwinAutomationIncidentTest.automationFailureRecordsAnIncidentLeavesTheOriginalUntouchedAndSupportsRetry` codifies all five required properties: a real resolvable Incident is created, the Original is completely unaffected, the Twin does not advance past the failure, no duplicate execution occurs on retry, and zero Job Executor involvement occurs at any point.

## Trade-offs

- **Gained:** operator-visible, Cockpit-native failure surfacing; zero risk of double-invoking a non-idempotent automation; zero Job Executor coupling.
- **Given up:** no automatic self-healing for transient failures — an operator (or an external orchestrator) must notice and act on the Incident. Accepted as the smallest mechanism that fits without assuming automation idempotency.

## Consequences

- Recovery must be idempotent-safe to repeat, which is guaranteed by engine-backed bridge deduplication ([ADR-012](ADR-012-restart-and-recovery-philosophy.md)).
- A project whose automation requires bounded retry for a known-transient dependency must implement that retry *inside* its own `ProjectAutomationService`, where it can reason about its own idempotency.

## Future Reconsideration

If a future project's automation demonstrates a recurring need for transient-failure retry that individual `ProjectAutomationService` implementations keep reinventing, that would be grounds to revisit based on concrete operational requirements rather than hypothetical convenience.
