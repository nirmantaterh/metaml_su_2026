# Architecture Evolution Timeline

A chronological record of how the Digital Twin Runtime evolved to Version 1.0 — foundational assumptions, engine behavioral constraints, architectural pivots, rejected alternatives, and structural refinements.

**Legend:** 🎯 Architectural Assumption · ❌ Disproven Assumption · 🔍 Camunda Behavior Discovery · 🔀 Architectural Pivot · 🚫 Rejected Alternative · 🐛 Identified Defect · 🧪 Empirical Validation · 🔍 Architecture Review · ✅ Architectural Resolution · 🏁 Convergence

---

## Stage 1 — Passive Process Mirroring

- 🎯 **Initial assumption:** A digital twin could be a secondary Camunda process instance executing the same deployed definition as the primary process, serving as an observational observer.
- ❌ **Disproven:** Executing the identical definition resulted in user tasks waiting for human input that was never directed to the twin, preventing autonomous execution.
- 🔀 **Pivot:** The digital twin required a distinct, generated definition where human decision points are transformed into automated, synchronizable activities ([ADR-002](adr/ADR-002-continuously-running-twin-bpmn.md)).

## Stage 2 — Asynchronous Service Task Prototype (`asyncBefore`)

- 🎯 **Initial assumption:** An `asyncBefore` Service Task, with the bridge triggering the queued job directly, would allow the twin to execute without human intervention.
- 🐛 **Identified defect:** This approach functioned only when `camunda.bpm.job-execution.enabled=false`. With the Job Executor enabled, background threads claimed and completed the jobs autonomously, completing the twin prematurely while the primary process remained at initial tasks.
- 🧪 **Empirical validation:** Disabling the Job Executor broke boundary timers (such as `PT8H` and `PT4H` timeouts) and engine history cleanup tasks.
- 🚫 **Rejected alternative:** Disabling or reconfiguring the engine Job Executor globally ([ADR-009](adr/ADR-009-no-job-executor-workarounds.md)).
- 🔀 **Pivot:** A deterministic wait-state mechanism was required instead of an asynchronous job queue, leading to the Receive Task architecture.

## Stage 3 — Receive Task / Service Task Separation

- 🔍 **Camunda behavior discovery:** A Receive Task creates a true wait state (`ACT_RU_EXECUTION` with an event subscription) without creating an entry in `ACT_RU_JOB`. The Job Executor remains unperturbed in its default configuration.
- 🔀 **Architectural pivot:** Every twin activity is structured as a Receive Task (synchronization gate) followed immediately by a Service Task (automation dispatch), executing within a single command boundary ([ADR-005](adr/ADR-005-receive-service-task-separation.md)).
- 🐛 **Identified defect:** Camunda model builder API generated non-deterministic element IDs across successive `generate()` invocations, defeating `enableDuplicateFiltering` and creating duplicate definition versions.
- ✅ **Architectural resolution:** Added deterministic ID stabilization across definitions root, condition expressions, and message elements (`stabilizeMessageIds`, `stripDiagramInterchange`).
- 🔍 **Architecture review:** Identified that `multiInstanceLoopCharacteristics` and `loopCardinality` elements exhibited similar non-deterministic ID generation. Resolved with `stabilizeMultiInstanceIds`.
- 🔍 **Architecture review:** Proactively prevented collisions where source activity IDs matched generator internal suffixes by introducing explicit model validation (`rejectReservedIdSuffixes`).
- 🔍 **Architecture review:** Addressed sequential multi-instance activities with non-literal EL cardinality expressions by providing single-visit fallback handling ([ADR-010](adr/ADR-010-sequential-and-parallel-multi-instance-support.md)).

## Stage 4 — Parallel Multi-Instance Execution

- 🎯 **Initial assumption:** Correlation builders utilizing local or process-scoped variable equality would disambiguate concurrent parallel activity instances.
- ❌ **Disproven:** Standard variable correlation failed to isolate individual execution branches within parallel multi-instance constructs.
- 🔍 **Camunda behavior discovery:** `RuntimeService.messageEventReceived(messageName, executionId)` directly targets a specific execution branch, bypassing global correlation filters.
- 🔍 **Camunda behavior discovery:** `loopCounter` scope is asymmetrical: in receive task executions, `loopCounter` resides on the parent scope execution, requiring ancestor variable resolution.
- ✅ **Architectural resolution:** Implemented targeted execution resolution (`resolveParallelSibling`) and consolidated bridge routing (`bridgeActivityEvent`) across automated and manual triggers ([ADR-007](adr/ADR-007-execution-targeted-messaging.md)).

## Stage 5 — Incident and Fault Handling

- 🎯 **Architectural assessment:** Evaluated failure modes when twin activity automation raises exceptions: Fail-Fast vs. Automatic Retry vs. Incident-Driven recovery.
- 🧪 **Empirical validation:** Verified transaction rollback semantics: when a synchronous service task throws an exception, the entire command rolls back, preserving the Receive Task wait state for replay.
- 🔀 **Architectural pivot:** Selected Incident-Driven recovery via `runtimeService.createIncident(...)` rather than unbounded automatic retry, protecting downstream services lacking idempotent contracts ([ADR-008](adr/ADR-008-incident-driven-failure-policy.md)).
- 🐛 **Identified defect:** Initial execution resolution for incident creation selected scope executions lacking direct activity bindings, triggering engine validation errors.
- ✅ **Architectural resolution:** Navigated the `ActivityInstance` tree to resolve leaf executions directly bound to the failing activity.

## Stage 6 — Structural Integrity and BPMN Model Hardening

- ✅ **One-to-One Activity Link Mapping:** Enforced bidirectional uniqueness on activity links (`connectActivity`) to prevent ambiguous message and signal routing ([ADR-014](adr/ADR-014-one-to-one-activity-link-mapping.md)).
- ✅ **Concurrency Synchronization:** Added synchronized monitor locking on twin state mutations to guarantee thread safety across concurrent binding requests.
- ✅ **BPMN Construct Coverage:** Added transformation support for Inclusive Gateways in model derivation and replaced silent omission of unsupported constructs with explicit, fail-fast diagnostic reporting ([ADR-011](adr/ADR-011-unsupported-bpmn-construct-policy.md)).

## Stage 7 — Restart Recovery and Execution State Tracking

- ✅ **Idempotent Bridge Forwarding:** Transitioned from volatile in-memory sets to engine-backed variable persistence for tracking activity execution state across application restarts.
- 🐛 **Identified defect:** Naive variable presence checks misidentified repeated visits to the same activity in loop-back topologies as duplicate executions.
- 🧪 **Empirical validation:** Verified that `HistoricDetailQuery.variableUpdates()` records every discrete update event rather than only the current snapshot.
- ✅ **Architectural resolution:** Implemented execution counter tracking based on historic variable update records (`HistoricDetail.variableUpdates()`), correctly handling both loop-back flows and retryable failure incidents ([ADR-012](adr/ADR-012-restart-and-recovery-philosophy.md)).

## Stage 8 — 🏁 System Convergence and Verification

- Comprehensive test suites passing consistently across both Workbench API (`wbapi`) and Target Platform generation modules.
- Formally documented operational constraints and supported BPMN feature boundaries across Architecture Decision Records.
- Verified end-to-end integration and recovery across standalone Target Platform runtimes and live process synchronization.
