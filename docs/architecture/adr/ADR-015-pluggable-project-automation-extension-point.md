# ADR-015: Twin Automation Is a Pluggable, Per-Project Extension Point

**Status:** Accepted (Version 1.0)

## Context

Different projects attaching to the MetaML Workbench will want their Twin activities to do genuinely different things when automated — there is no single "correct" automation behavior this architecture could hardcode that would serve every future project.

## Decision

`ProjectAutomationService` is a plain interface (`AutomationResult execute(DelegateExecution execution)`); Spring collects every bean implementing it into a `Map<String, ProjectAutomationService>` keyed by bean name, and `TwinAutomationDelegate` selects which one runs per twin via that twin's own `projectId` field (defaulting to `"default"` when unset or when the named bean doesn't exist, logged as a configuration warning rather than failing the Twin's token). Exactly one real implementation ships (`DefaultProjectAutomationService`, bean name `"default"`) — enough to prove the extension point works, deliberately not more.

## Alternatives Investigated

- **Hardcoding automation logic directly in `TwinAutomationDelegate`** — rejected; there is no way to anticipate what any future project's automation should actually do, and hardcoding one project's needs into the generator/delegate would make every other project's Twin behave identically regardless of its own business logic.
- **A configuration-driven rules engine instead of a Java extension point** — not pursued; would be solving a generality problem nobody has asked for yet, in violation of the broader discipline against introducing abstractions the current requirements don't call for.
- **Inventing arbitrary business rules for the "default" implementation to make it look more complete** — explicitly rejected: the default automation records standard execution timestamps and variables to confirm the task executed cleanly without imposing domain-specific assumptions.

## Verification

`TwinAutomationDelegate.automationFor` resolves the bean by `projectId` with a documented, safe fallback (a misconfigured or missing project id degrades to the default automation with a warning, rather than leaving the Twin's token permanently stuck). `theDefaultProjectAutomationRunsAndSetsVariablesOnTheTwin` (`TwinExecutionWalkthroughTest`) confirms the summary and output land on the Twin instance under the documented naming convention.

## Trade-offs

- **Gained:** any future project can attach real automation logic by writing one Spring bean and naming it after its own project id, with zero changes required to `TwinAutomationDelegate`, `TwinModelGenerator`, or anything else in this architecture.
- **Given up:** the extension point currently has exactly one real implementation, so its generality is proven only in shape, not by a second, differently-behaved consumer actually exercising it yet.

## Consequences

- `ProjectAutomationService`'s own interface Javadoc carries a direct warning to future implementers about the activity-id recovery requirement (`TwinModelGenerator.synchronizationActivityIdOf`) — a real, easy-to-miss pitfall (the automation task's own id is not the id every `evolvedAgent_*`/`evolvedAgentOutput_*` variable is keyed on) that both existing implementations (`DefaultProjectAutomationService`, and `TwinAutomationDelegate`'s own dispatch logic) already had to get right, documented so the next implementation doesn't have to rediscover it.
- `execute()` carries no documented idempotency contract by design ([ADR-008](ADR-008-incident-driven-failure-policy.md)) — any implementation that needs retry safety must provide it itself.

## Future Reconsideration

Would be revisited once a second real project actually attaches its own `ProjectAutomationService` implementation and either validates the current contract or surfaces a gap in it — not something to speculatively redesign ahead of that need.
