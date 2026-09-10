# ADR-007: Parallel Multi-Instance Siblings Are Disambiguated by Direct Execution-Targeted Messaging

**Status:** Accepted (Version 1.0)

## Context

A parallel multi-instance Twin activity can have more than one sibling execution waiting on the *identical* message name at the same time. Advancing "the right one" needs a mechanism that can target a single execution unambiguously.

## Decision

`WorkbenchServiceImpl.advanceTwinActivity` disambiguates by the Multi-Instance body's own `loopCounter`: when more than one candidate execution is subscribed to the message, it matches the Original's `loopCounter` against each candidate's, then releases exactly that one via `runtimeService.messageEventReceived(messageName, executionId)` — which targets a named execution directly, bypassing correlation-key matching entirely. When there is only one candidate (the ordinary case — plain or sequential activities never have more than one), the existing scoped `createMessageCorrelation(...).correlate()` path is used unchanged.

## Alternatives Investigated

- **`MessageCorrelationBuilder.localVariableEquals(...)`** — tried and empirically disproven: matches against the wrong scope for this shape of execution tree, failing to disambiguate.
- **`MessageCorrelationBuilder.processInstanceVariableEquals(...)`** — tried and empirically disproven: matches at the process-instance level, which does not distinguish between siblings that all share the same process instance by definition.
- **`Execution.getParentId()`** to walk to a shared identifying ancestor — assumed to exist based on general Camunda familiarity, then verified absent from the actual public API via `javap` against the real jar. Replaced by the non-local `getVariable(executionId, "loopCounter")` read, which resolves up the scope chain correctly without needing a parent id at all.
- **Falling back every parallel activity to a single visit** (the interim state before this was solved) — rejected as the final answer; it was accepted only until the correct mechanism was found and verified, then explicitly lifted once `messageEventReceived` was proven to work.

## Verification

Verified via direct `javap` inspection of the actual `camunda-engine` jar that `messageEventReceived(String, String)` exists on the public `RuntimeService` interface and does exactly what was needed. Proven empirically with a throwaway probe: three parallel siblings, each released individually by its own execution id, with both `correlate()`-based variable-matching options confirmed to fail disambiguation first, before this became the shipped mechanism. `parallelMultiInstanceActivityAdvancesEachSiblingIndependently` and related tests in `WireTransferWalkthroughTest` cover this end-to-end.

## Trade-offs

- **Gained:** correct, deterministic disambiguation of parallel siblings using a fully standard, public Camunda API, with zero custom correlation-key infrastructure.
- **Given up:** `advanceTwinActivity` now has two distinct code paths (targeted `messageEventReceived` vs. scoped `correlate()`) rather than one uniform mechanism — accepted because unifying them was investigated and deferred to keep execution identity and message correlation strictly separated. Remains open as a legitimate future simplification, not a rejected idea.

## Consequences

- Any new code path that needs to advance the Twin's token must be aware that a parallel Multi-Instance activity may have more than one live candidate and must resolve `originalExecutionId` (or accept `null` and fall back to the single-candidate path) rather than assuming `correlate()` alone is always sufficient.

## Future Reconsideration

The dual-path design (`messageEventReceived` vs. `correlate()`) is a reasonable candidate for future unification into a single `messageEventReceived`-only path, since that was already proven safe for the single-candidate case — deferred, not rejected.
