# ADR-002: The Twin Runs a Continuously-Live, Separately Generated BPMN Definition

**Status:** Accepted (Version 1.0)

## Context

The earliest version of "the twin" in this codebase was a second instance of the *same* deployed definition as the Original — meaning every activity in it was a user task waiting for a human nobody was ever going to send there. Such a twin could be annotated or inspected, but its own token could never move on its own; "digital twin" in that shape meant little more than a second, permanently-stuck copy.

## Decision

Every twin launch generates and deploys its **own** BPMN process definition ([`TwinModelGenerator`](../../../backend/workbench/src/main/java/com/metaml/workbench/bpmn/TwinModelGenerator.java)), derived from the Original's deployed definition with every human decision point replaced by an automatable pair (ADR-005), and starts a live `ProcessInstance` on it that runs continuously alongside the Original for the life of the twin.

## Alternatives Investigated

- **Passive/annotation-only twin** (the original shape) — explicitly rejected; it cannot express "the twin's token advances," which was the stated requirement driving this entire rebuild.
- **A single shared definition with conditional automation** (one BPMN, some execution paths automated, some human) — rejected: it conflates the Original's and Twin's identities into one process instance, which breaks the one-way-authority model (ADR-001) and Camunda's own instance/version semantics (a single definition can't sensibly be "the same process" running two different ways at once).
- **Deploying the Twin under the Original's own process key** — rejected specifically because it would file the Twin as a new *version* of the Original's definition; Cockpit would then show one process key flipping between a human diagram and an automated one depending which version was opened. The Twin gets its own process id (`<originalId>_twin`) instead.

## Verification

`TwinModelGenerator.twinProcessId()` derives a distinct id; `theGeneratedDefinitionKeepsTheShapeOfTheOriginalWithoutTheHumansOrTheTimer` (in `TwinExecutionWalkthroughTest`) asserts the generated definition's key is distinct from the Original's and that no `UserTask` element survives generation.

## Trade-offs

- **Gained:** an independent Twin execution with its own Cockpit-visible process instance and history, distinguishable from the Original at a glance.
- **Given up:** deploying a second definition per model means twice the Camunda deployment/version bookkeeping; addressed by deterministic generation + duplicate filtering (ADR — see Architecture Specification, Section 5) so relaunching the same model does not accumulate versions.

## Consequences

- Every place that needs to reason about "the Original's activity id" vs "the Twin's activity id" must be explicit about which side it means — prompting the design of `ActivityLink` (ADR-006) rather than assuming the two are always interchangeable, even though the generator does preserve matching ids by default.

## Future Reconsideration

None anticipated; this is foundational to every other decision in this document.
