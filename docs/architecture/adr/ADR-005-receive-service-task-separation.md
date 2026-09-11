# ADR-005: Synchronization and Automation Are Two Separate BPMN Elements

**Status:** Accepted (Version 1.0)

## Context

Once every Twin activity needed to (a) wait for the Original's matching step and (b) run that project's automation, the question was whether one BPMN element should do both jobs or whether they should be modeled as two distinct responsibilities.

## Decision

Every Twin activity is generated as a **Receive Task** (waits on `TwinAdvance_<activityId>`, answers "is the Twin allowed to continue") immediately followed by a **Service Task** (`camunda:delegateExpression="${twinAutomationDelegate}"`, answers "what should it do now that it can"), both without any async marker, executing inside the *same* Camunda command.

## Alternatives Investigated

- **One element wearing both hats** (e.g. an execution listener on a Receive Task doing the automation work) — this was the actual first implementation and was deliberately replaced. It conflated two different questions ("can I proceed" vs "what do I do") into one BPMN node, which made the generated model harder to reason about and, more concretely, made a later requirement (attaching an Incident to a *specific* leaf execution on automation failure) ambiguous about which execution "owns" the failure.
- **An `asyncBefore` Service Task with the bridge running the parked job directly** — the actual original shape of "automation," predating even the receive/service split. It worked only with `camunda.bpm.job-execution.enabled=false`, because the Job Executor picked the parked job up on its own within a second or two, walking the Twin to completion while the human was still on the first task. Turning the executor off fixed that but broke `Task_Approve`'s boundary timer and Camunda's own history cleanup — both of which need it. Rejected; this is the origin of [ADR-009](ADR-009-no-job-executor-workarounds.md).

## Verification

Measured both shapes (one-element vs two-element) before choosing; the two-element shape's zero-latency claim is directly testable and tested: `theGeneratedDefinitionKeepsTheShapeOfTheOriginalWithoutTheHumansOrTheTimer` asserts the generated BPMN contains no `asyncBefore` anywhere (`Bpmn.convertToString(generated)).doesNotContain("asyncBefore")`), and every walkthrough test observes the Twin land on its *next* Receive Task within the same call that released the previous one — no job-executor round trip visible anywhere in the test's own thread.

For multi-instance activities specifically, a throwaway probe (two correlated visits, asserting the Service Task read `loopCounter` 0 then 1, and the outer flow only continued once both visits finished) confirmed the built-in multi-instance variables remain visible to *both* elements inside the wrapping embedded sub-process, before this shape was relied upon for that case.

## Trade-offs

- **Gained:** a clean separation of concerns that maps directly onto the two distinct questions the Twin needs answered; an unambiguous execution to attach a failure Incident to (the Service Task's own activity instance, resolvable independently of the Receive Task's).
- **Given up:** every generated Twin model now has twice as many activity nodes as the Original, which makes the generated BPMN visually busier if anyone ever needs to read it directly (nobody currently does — the generated definition has no modeler-facing purpose beyond execution).

## Consequences

- `TwinModelGenerator.automationTaskId`/`synchronizationActivityIdOf` exist specifically to let code recover "which synchronization point does this automation task belong to" without a second persisted mapping — every consumer of `execution.getCurrentActivityId()` inside `TwinAutomationDelegate`/`DefaultProjectAutomationService` must call `synchronizationActivityIdOf` first, or every `evolvedAgent_*`/`twinAutomation_*` variable lookup silently misses (documented directly in `ProjectAutomationService`'s own interface Javadoc as a warning to future extension-point authors).

## Future Reconsideration

None anticipated; this split is foundational to the failure-semantics model (ADR-008) and would need to be re-litigated as a whole if ever revisited, not adjusted incrementally.
