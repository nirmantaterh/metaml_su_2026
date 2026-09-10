# ADR-009: Never Reconfigure or Disable the Global Job Executor

**Status:** Accepted (Version 1.0)

## Context

An early design for making the Twin's activities executable used an `asyncBefore` Service Task, with the bridge running the parked job directly. It worked — but only with `camunda.bpm.job-execution.enabled=false`, because Camunda's own Job Executor would otherwise pick the parked job up on its own within a second or two, walking the Twin to its end while the human was still on the very first Original task.

## Decision

**Never reconfigure Camunda's global Job Executor configuration**, in any form, to make Twin synchronization work. `application.properties` carries an explicit comment recording this as a standing constraint, not merely a past mistake: *"No job-execution setting here on purpose, and please don't add one."*

## Alternatives Investigated

- **Disabling the Job Executor** (`camunda.bpm.job-execution.enabled=false`) — the actual first attempt. It fixed the premature-completion race, but broke `Task_Approve`'s `PT8H` boundary timer, the grad-admission model's `PT4H` boundary timer, and Camunda's own history cleanup job — all of which depend on the executor running normally. Rejected once the collateral damage was measured, not merely suspected.
- **Per-job-definition suspension** (suspending only the Twin's own async jobs rather than the whole executor) — not pursued once the Receive Task redesign (ADR-005) made the whole question moot: a wait state that produces no `ACT_RU_JOB` row has nothing for any Job Executor configuration to interact with, correctly or otherwise.
- **A dedicated, separate Job Executor instance scoped only to Twin definitions** — not investigated; would have been a large increase in infrastructure complexity to solve a problem the Receive Task redesign eliminated by construction.

## Verification

`BoundaryTimerWalkthroughTest` verifies this behavior by testing an Original's boundary timer to prevent regressions from Job Executor interference. The Receive Task shape (ADR-005) was verified directly: a Receive Task is a wait state in `ACT_RU_EXECUTION` with an event subscription and no corresponding row in `ACT_RU_JOB`, confirmed by inspecting the generated model and execution logs.

## Trade-offs

- **Gained:** the Job Executor, boundary timers, and Camunda's own history cleanup all continue to work exactly as Camunda ships them, with zero special-casing anywhere in this application's configuration.
- **Given up:** none identified — the Receive Task redesign (ADR-005) eliminated the underlying trade-off.

## Consequences

- Any future feature proposal that would require touching `camunda.bpm.job-execution.*` properties, or any global Job Executor configuration, must be treated as a signal that the proposed design has taken a wrong turn — the correct fix is almost certainly a wait-state-shaped mechanism (Receive Task, message event, signal event) rather than an async job whose timing needs to be fought.

## Future Reconsideration

Not expected to be revisited; this is a hard constraint discovered through direct, measured collateral damage, not a soft preference.
