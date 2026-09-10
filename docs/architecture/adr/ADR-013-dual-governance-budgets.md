# ADR-013: Evolution and Twin-Execution Are Two Independent Governance Budgets

**Status:** Accepted (Version 1.0)

## Context

The system needs to bound two different kinds of activity: how many times a Twin activity asks the external node manager for an agent ("evolution"), and how many steps the Twin's own token takes ("twin execution" — every activity the Twin passes through, gateways included, via `advanceTwinActivity`). Early governance used one counter and one hardcoded default (5) for a concept that turned out to mean two very different things.

## Decision

Two fully independent quotas, each with its own counter map and its own configurable maximum: `maxEvolutionsPerTwin` (default 25) via `reserveEvolutionSlot`/`releaseEvolutionSlot`, and `maxTwinExecutionsPerTwin` (default 200) via `reserveTwinExecutionSlot`/`releaseTwinExecutionSlot`, both in `GovernanceServiceImpl`.

## Alternatives Investigated

- **One shared counter for both** — the original, actual shape, and it broke immediately in practice: the `citibank-wire-transfer` walkthrough alone bridges seven activities on one twin, and — because every twin-execution step *also* triggers an evolution via the auto-bridge — sharing one counter against a hardcoded limit of 5 meant the sixth activity onward was refused with no cause visible to anyone watching the demo. Rejected once measured.
- **A single, much larger shared counter** — would have papered over the immediate symptom without addressing the actual conceptual conflation: an evolution is a real external call (to the node manager, standing in for a paid/rate-limited agent request); a twin-execution step is the Twin's own token moving, which happens for *every* activity it passes through, including gateways in some counting paths. Treating them as the same budget means ordinary automation throughput silently eats the quota meant to bound external agent requests specifically.

## Verification

`GovernanceServiceImpl`'s own constructor comment records the concrete failure mode that motivated the split, with the exact numbers (a 5-limit hardcoded default, a 7-activity walkthrough). `theTwinsOwnTokenWalksTheWireTransferAsTheOriginalIsCompleted` asserts both counters independently at the end of a full walkthrough (seven evolutions, seven twin executions on that particular model, arrived at via two separate counters, not because the numbers happen to coincide).

## Trade-offs

- **Gained:** a quota that actually protects what it's meant to protect (external agent request volume) without incidentally capping ordinary Twin automation throughput.
- **Given up:** two numbers to reason about and configure instead of one; mitigated by both defaulting sensibly and both being independently adjustable at runtime via `GovernanceController`/`updatePolicy` without a restart.

## Consequences

- Both counters use increment-then-rollback `AtomicInteger`s rather than check-then-increment, specifically to avoid a race where two concurrent requests both read "one slot left" and both take it — the same race-safety pattern applied uniformly to both budgets.
- Neither counter map is ever cleaned up for a twin that has ended (`GovernanceServiceImpl`'s own `TODO`) — an open limitation, not addressed by this decision.

## Future Reconsideration

Would be revisited if a future requirement needed per-agent-type sub-budgets rather than one flat evolution count per twin — not currently needed, current callers only ever ask "is this twin over its evolution budget," never "is this twin over its budget for agent type X."
