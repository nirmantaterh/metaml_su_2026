# ADR-010: Multi-Instance Activities Are Wrapped in an Embedded Sub-Process, Both Sequential and Parallel

**Status:** Accepted (Version 1.0)

## Context

The Original process definition may contain multi-instance User Tasks (sequential or parallel, such as multi-reviewer evaluation tasks). Preserving these semantics in the Twin, without diverging from the standard generator architecture, requires enabling multi-instance execution over the Receive Task + Service Task pair ([ADR-005](ADR-005-receive-service-task-separation.md)) generated for each activity.

## Decision

A multi-instance User Task is transformed into an **embedded sub-process** wrapping the `[Receive Task, Service Task]` pair. Camunda's `multiInstanceLoopCharacteristics` are attached to the sub-process scope (`.sequential()` or `.parallel()`), constrained to **literal** loop cardinalities. Non-literal (dynamic expression or collection-driven) cardinalities gracefully fall back to a single Twin visit with diagnostic logging, as the Twin environment lacks access to the Original process instance's variable state at generation time.

## Alternatives Investigated

- **Attaching multi-instance characteristics directly to flow nodes:** In Camunda BPMN models, multi-instance attributes attach to a single activity node. Spanning two sequential nodes requires an enclosing sub-process scope.
- **Evaluating dynamic collection expressions against Original process variables:** Investigated and rejected because the Twin process instance executes in its own isolated execution context. Dynamic evaluation across instances introduces fragile coupling and data inconsistency risks.
- **Rejecting all multi-instance activities:** Rejected because literal-cardinality multi-instance tasks represent standard business patterns and are reliably supported via sub-process wrapping.

## Technical Validation

Validated via integration tests demonstrating sequential multi-instance execution, where wrapped Service Tasks observe sequential `loopCounter` increments (0, 1, ...) and the enclosing flow proceeds only after all iterations finish. Parallel multi-instance execution was validated using concurrent sibling correlation ([ADR-007](ADR-007-execution-targeted-messaging.md)). Furthermore, `stabilizeMultiInstanceIds` ensures that `multiInstanceLoopCharacteristics` and `loopCardinality` elements receive deterministic identifiers across repeated `generate()` invocations.

## Trade-offs

- **Gained:** Full sequential and parallel multi-instance execution fidelity for literal cardinalities using standard Camunda constructs.
- **Given up:** Dynamic collection-based cardinality evaluates to a single iteration fallback with explicit diagnostic logging.

## Consequences

- Literal cardinality definitions execute deterministically in both sequential and parallel modes.
- Model authors using dynamic collection-based multi-instance constructs are notified via diagnostic logs that twin mirroring operates in single-visit fallback mode.

## Future Reconsideration

Extending support to dynamic collection expressions would require defining a declarative variable-mapping contract between primary and twin runtime contexts.
