# Architecture Decision Records

Fifteen ADRs recording the major architectural decisions behind the Digital Twin Runtime, Version 1.0. All are `Accepted` as of this document's writing; none are proposed or superseded.

| ADR | Decision |
|---|---|
| [001](ADR-001-original-bpmn-is-sole-authority.md) | The Original process instance is the sole authority |
| [002](ADR-002-continuously-running-twin-bpmn.md) | The Twin runs a continuously-live, separately generated BPMN definition |
| [003](ADR-003-shared-h2-runtime-as-source-of-truth.md) | One shared Camunda runtime database is the source of truth |
| [004](ADR-004-event-driven-synchronization.md) | Event-driven synchronization via `AFTER_COMMIT`, never polling (in-process Original↔Twin bridge only — the separate, generated Target Platform pipeline uses a 1-second polling coordinator, see `TEAM_DEMO_GUIDE.md` §14.4) |
| [005](ADR-005-receive-service-task-separation.md) | Synchronization and automation are two separate BPMN elements |
| [006](ADR-006-runtime-derived-execution-identity.md) | Execution identity is recomputed at runtime, never cached — except one mapping |
| [007](ADR-007-execution-targeted-messaging.md) | Parallel multi-instance siblings are disambiguated by direct execution-targeted messaging |
| [008](ADR-008-incident-driven-failure-policy.md) | Automation failures are incident-driven, not automatically retried |
| [009](ADR-009-no-job-executor-workarounds.md) | Never reconfigure or disable the global Job Executor |
| [010](ADR-010-sequential-and-parallel-multi-instance-support.md) | Multi-instance activities are wrapped in an embedded sub-process, both sequential and parallel |
| [011](ADR-011-unsupported-bpmn-construct-policy.md) | Unsupported BPMN constructs fail Twin generation explicitly |
| [012](ADR-012-restart-and-recovery-philosophy.md) | Restart safety is achieved by derivation, not by persisting more state |
| [013](ADR-013-dual-governance-budgets.md) | Evolution and twin-execution are two independent governance budgets |
| [014](ADR-014-one-to-one-activity-link-mapping.md) | Activity links are enforced as a one-to-one bijection |
| [015](ADR-015-pluggable-project-automation-extension-point.md) | Twin automation is a pluggable, per-project extension point |

These records document the technical context, evaluated alternatives, and consequences of each foundational decision across the platform.

See also: [Architecture Specification](../ARCHITECTURE.md) · [Evolution Timeline](../EVOLUTION_TIMELINE.md) · [Runtime Diagrams](../DIAGRAMS.md)
