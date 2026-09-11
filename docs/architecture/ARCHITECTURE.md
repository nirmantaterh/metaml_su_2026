# Digital Twin Runtime Architecture — Version 1.0

**Status:** Version 1.0
**Scope:** `backend/workbench` (domain/runtime logic) and `backend/wbapi` (Spring Boot host, REST API, embedded Camunda 7.22.0 engine)
**Companion documents:** [Architecture Decision Records](adr/) · [Evolution Timeline](EVOLUTION_TIMELINE.md) · [Runtime Diagrams](DIAGRAMS.md)

This document is the permanent architectural record for the MetaML Workbench Digital Twin runtime. It describes the system exactly as implemented — no proposed changes, no alternative designs, no aspirational future state. Every claim below is backed by either source code (cited by file and method) or an empirical investigation recorded in the [Evolution Timeline](EVOLUTION_TIMELINE.md).

---

## 1. Executive Summary

### Motivation

A "digital twin" of a running business process is not useful if it only records or replays what the original did after the fact. The goal of this subsystem is a twin whose own Camunda token genuinely advances in step with the original process, in real time, using only mechanisms Camunda 7 ships natively — no bespoke polling loop, no parallel state machine reimplementing what the engine already tracks.

### Goals

- The twin's execution token advances the moment the original commits a step, not on a delay and not on a poll.
- Synchronization uses a standard Camunda mechanism and never couples to or reconfigures the global Job Executor (an earlier attempt that disabled it broke boundary timers and history cleanup — see [ADR-009](adr/ADR-009-no-job-executor-workarounds.md)).
- One Camunda engine, one shared H2 datasource, is the single source of truth for both instances' runtime state.
- The Original process is always authoritative; the Twin observes and automates but never drives the Original.
- Every architectural claim is verified with automated tests (test probes, unit/integration suites, or direct API inspection), not by unverified assumptions about Camunda behavior.

### Philosophy

The standing engineering discipline for this build:

- **Derive, don't duplicate.** State that Camunda's own runtime or history tables already contain is read from there, not shadowed in a second, app-owned structure that can drift or fail to survive a restart.
- **Recomputation over persistence.** The only persisted cross-reference this system keeps is the Original-activity-id ↔ Twin-activity-id link ([ADR-006](adr/ADR-006-runtime-derived-execution-identity.md)); everything else about "which visit, which execution, which loop iteration" is recomputed fresh from Camunda's runtime tables on every synchronization event.
- **Explicit validation over silent acceptance.** An unsupported BPMN construct fails twin generation loudly ([ADR-011](adr/ADR-011-unsupported-bpmn-construct-policy.md)); a many-to-one activity link is rejected at creation time, not discovered later as corrupted state.
- **Event-driven, not polling.** The Twin never asks "has the Original moved yet?" — it is told, once, when it has. (Scoped to this document's subject, the in-process Original↔Twin bridge — see the Scope line above. The separately-generated Target Platform pipeline, Proxy/Twin synchronized over RabbitMQ — currently two process definitions in one shared engine/JVM, not two separately-deployed processes — uses a different mechanism: signal-driven advancement with a 1-second polling coordinator — see `TEAM_DEMO_GUIDE.md` §14.4.)
- **Fail fast, surface incidents.** An automation failure becomes a real, operator-visible Camunda Incident, never a silently swallowed exception and never an automatic blind retry.
- **Camunda-native mechanisms over custom infrastructure**, evaluated before any custom alternative is adopted, and each deviation documented with the operational rationale that ruled out the native approach.

### Non-Goals

- **Full BPMN coverage.** The twin generator supports a deliberately narrow set of constructs (User Task, Exclusive/Parallel/Inclusive Gateway, plain End Event, literal-cardinality Multi-Instance). Everything else fails generation explicitly rather than being silently dropped or force-supported. See [ADR-011](adr/ADR-011-unsupported-bpmn-construct-policy.md).
- **Autonomous agent reasoning.** "Agents" in this system are external entities looked up through a stub `NodeManagerClient` against a static catalog; nothing in this architecture makes decisions about *which* agent to pick beyond what the node manager's catalog returns.
- **Frontend architecture.** This document covers the backend runtime only.
- **Production hardening.** Authentication, TLS, and multi-tenant isolation are explicitly out of scope; the system runs loopback-only (`server.address=127.0.0.1`) with `permitAll` security and CSRF disabled, a deliberate demo-scope decision recorded in `WebSecurityConfig`, not an oversight.

### Guiding Principles

| Principle | What it means here |
|---|---|
| Original controls progression | The Original's own user-task/token movement is never blocked, delayed, or altered by anything the Twin does. |
| Twin observes and automates | The Twin has no independent agency; every step it takes is a reaction to an Original event. |
| Shared runtime is the source of truth | One Camunda engine, one datasource, backs both instances; nothing about "did this already happen" is trusted from app memory when Camunda's own tables can answer it. |
| Event-driven, not polling (this subsystem only) | `AFTER_COMMIT` transaction synchronization, not a scheduled poll, is what moves the in-process Twin. The generated Target Platform's Proxy/Twin sync (currently two process definitions in one shared engine/JVM, not two separately-deployed processes) is a separate mechanism and does use a 1-second polling coordinator — see `TEAM_DEMO_GUIDE.md` §14.4. |
| Fail fast, surface incidents | Failures become Camunda Incidents or thrown exceptions with a precise diagnostic, never a silent warn-and-continue. |
| Derive, don't duplicate | New state is introduced only when derivation from existing Camunda state is proven impossible. |
| Restart-safe by design | Anything that must survive an app restart either lives in Camunda's own durable tables or is explicitly, deliberately excluded with a documented reason (governance quotas). |
| Deterministic and traceable | Twin generation is idempotent (same input, byte-identical output) and every synchronization decision is logged to the twin's own event log. |

---

## 2. Runtime Architecture

### Topology

One twin **launch** creates exactly **two live Camunda `ProcessInstance`s**, sharing **one Camunda engine** and **one H2 datasource**, inside **one Spring Boot process** (`WbapiApplication`, module `backend/wbapi`, embedding the Camunda 7.22.0 Spring Boot Starter):

- **Original** — started on the model author's own deployed BPMN definition, unchanged, with real user tasks a human works through normally.
- **Twin** — started on a *separately generated and deployed* BPMN definition ([`TwinModelGenerator`](../../backend/workbench/src/main/java/com/metaml/workbench/bpmn/TwinModelGenerator.java)), in which every user task has been replaced by a Receive Task + Service Task pair (Section 5), and which carries no human-facing task at all.

Both instances are tagged with a business key that ties them back to the app's own `TwinProcess` record and to each other: `original-<twinId>` and `twin-<twinId>` (`WorkbenchServiceImpl.launch`). This is the only piece of information `AutoBridgeTrigger` needs to recognize "this event came from an Original I should be bridging" and to recover which twin it belongs to.

The domain logic (`backend/workbench`) is a plain Spring library module with no web layer of its own; `backend/wbapi` hosts it, exposes it over REST, and owns the Spring Boot application context, the Camunda engine configuration, and the embedded Camunda web apps (Cockpit/Tasklist/Admin, `demo`/`demo` credentials, loopback-only). See [Runtime Component Diagram](DIAGRAMS.md#1-runtime-component-diagram).

### External Component

A separate, small Spring Boot application, `backend/nodemanager`, stands in for a real agent catalog: `GET /api/v1/node-manager/agents/{agentType}` (port 8083) returns availability and a chosen agent name from a static, properties-file-backed catalog (`NodeManagerProperties`). `NodeManagerClient` in the workbench module is the only thing that talks to it, with a 1s connect / 2s read timeout specifically so a stuck call can never block the twin's single-threaded auto-bridge worker indefinitely.

### Major Components and Responsibilities

Full detail in [Section 8](#8-component-responsibilities); summarized here for orientation:

| Component | Role |
|---|---|
| `WorkbenchServiceImpl` | Orchestrates the whole lifecycle: launch, link activities, evolve/bridge, advance the twin's token, record incidents. |
| `TwinModelGenerator` | Synthesizes the Twin's BPMN definition from the Original's deployed definition. |
| `AutoBridgeTrigger` | The `AFTER_COMMIT` listener that turns an Original activity-start event into a bridge + advance call. |
| `TwinAutomationDelegate` | Runs as the Twin's generated Service Task; dispatches to the configured `ProjectAutomationService`. |
| `AgentExecutionDelegate` | Runs as an optional listener on the Original's own task; copies the Twin's chosen agent and outputs back onto the Original. |
| `GovernanceServiceImpl` | Two independent, race-safe quotas: evolutions per twin, twin-execution steps per twin. |
| `WorkbenchStateStore` | The app's own JSON persistence for `TwinProcess` records — explicitly *not* Camunda's engine state. (`ProcessModel` is persisted by the H2-backed `ProcessModelArchiveStore`, not here.) |
| `NodeManagerClient` | HTTP client to the external (stub) agent catalog. |

---

## 3. System Lifecycle

**Startup → Launch → Generate Twin → Deploy → Execute → Sync → Automate → Complete → Restart → Recover**

1. **Startup.** Spring Boot boots; the Camunda engine initializes against the H2 file datasource (`jdbc:h2:file:./data/camunda`, or an in-memory URL under test); the Job Executor starts completely unmodified, exactly as Camunda ships it. `WorkbenchServiceImpl.restoreState()` (`@PostConstruct`) loads previously saved `ProcessModel` records from the H2-backed `ProcessModelArchiveStore` and previously saved `TwinProcess` records from `WorkbenchStateStore`'s JSON snapshot, into in-memory maps.
2. **Launch.** `POST /api/v1/wb/transmute/launch` → `WorkbenchServiceImpl.launchProcess(modelId)`.
3. **Generate Twin.** `deployTwinDefinition` calls `TwinModelGenerator.generate()` against the Original's *deployed* `BpmnModelInstance` (never the stored XML directly, so the Twin can never drift from what the Original is actually running).
4. **Deploy.** The generated Twin model is deployed under a deployment name derived from the model id, with `enableDuplicateFiltering(true)`; because generation is deterministic (Section 5), relaunching the same model reuses the existing Twin definition instead of accumulating a new version on every launch.
5. **Execute.** `launch()` starts both process instances in the same call, rolling the Original back if the Twin fails to start so a failed launch never leaks a live instance.
6. **Sync.** As the Original's token moves, `AutoBridgeTrigger.onActivityStarted` fires once per activity-start event, after the Original's own commit, and calls `WorkbenchServiceImpl.bridgeActivityEvent`.
7. **Automate.** Inside that same call, `advanceTwinActivity` correlates the message the Twin's matching Receive Task is waiting on; `TwinAutomationDelegate.execute()` runs synchronously immediately afterward, in the same Camunda command, with no async marker anywhere in the path.
8. **Complete.** The Original reaches its own end event on its own schedule (human-paced); the Twin typically reaches its end event first, since it has no user tasks and no boundary timers to wait out.
9. **Restart.** An app restart reloads `ProcessModel` bookkeeping from the H2 archive and `TwinProcess` bookkeeping from the JSON snapshot; Camunda's own runtime and history tables in the H2 file database are untouched by the restart and remain authoritative for anything execution-related (Section 6, Section 7).
10. **Recover.** An automation failure leaves a Camunda Incident against the Twin's stalled execution; an operator resolves it by re-invoking the bridge for that activity, which is safe to repeat because the dedup guard is derived from Camunda's own state, not from anything the restart could have reset (Section 7, [ADR-012](adr/ADR-012-restart-and-recovery-philosophy.md)).

See [Runtime Sequence Diagram](DIAGRAMS.md#2-runtime-sequence-diagram).

---

## 4. Synchronization Architecture

### The `AFTER_COMMIT` Pipeline

`AutoBridgeTrigger.onActivityStarted` is a `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)` on Camunda's own `ExecutionEvent`, republished into Spring's event bus by the Camunda Spring Boot Starter's `EventPublisherPlugin`. This is not a custom event bus — it is Spring's transaction synchronization layer riding on a Camunda-native event, which is what lets synchronization be "event-driven, not polling" without inventing new infrastructure ([ADR-004](adr/ADR-004-event-driven-synchronization.md)).

`AFTER_COMMIT` specifically, not a plain `@EventListener`, because a plain listener runs *before* the engine flushes its own changes — every activity would read back as "not yet reached." This was discovered empirically, not anticipated (see [Evolution Timeline](EVOLUTION_TIMELINE.md)).

### Off-Thread, Fail-Isolated Dispatch

The listener itself never lets an exception escape (it would otherwise surface as the *Original's* task-completion call failing). The actual bridge call runs on a dedicated single-thread `ExecutorService` (`bridgeExecutor`), and the listener blocks on it with a bounded timeout (6s) purely so the calling UI's "Complete task" response always reflects the bridge's outcome. A broken Twin can, at worst, log a warning; it can never take the human's own transaction down with it — a lesson learned directly from an earlier, rejected design that ran this logic inside `AgentExecutionDelegate`, a listener on the Original's own task, where a Twin-side failure marked that transaction rollback-only.

### What "Sync" Actually Does

One call, `WorkbenchServiceImpl.bridgeActivityEvent(twinId, activityId, activityInstanceId)`, does both halves, consolidated into a single code path so a manual API caller and the automatic trigger can never drift apart in behavior:

1. **Bridge** — resolve the linked Twin activity, resolve *this specific visit* (Section 6), guard against re-forwarding it (Section 6/7), request an agent from the node manager, write `evolvedAgent_*`/`evolvedAgentOutput_*` onto the Twin instance.
2. **Advance** — resolve which Camunda execution the Twin's matching Receive Task is actually waiting on, and release it: `messageEventReceived(name, executionId)` when a specific parallel sibling must be targeted, or a scoped `createMessageCorrelation(...).correlate()` otherwise ([ADR-007](adr/ADR-007-execution-targeted-messaging.md)).

### Receive Task / Service Task Separation

Every twin activity is two BPMN elements, not one, deliberately ([ADR-005](adr/ADR-005-receive-service-task-separation.md)):

- **Receive Task** — a Camunda wait state (a row in `ACT_RU_EXECUTION` with an event subscription beside it, *no* row in `ACT_RU_JOB`). Answers "is the Twin allowed to continue."
- **Service Task** (synchronous, no `asyncBefore`/`asyncAfter`) — runs `TwinAutomationDelegate` the instant the Receive Task's message is correlated, inside the *same* Camunda command. Answers "what should the Twin do now that it can."

Both execute inside one `correlate()`/`messageEventReceived()` command, so nothing hands off to the Job Executor between them — the two-element split adds no latency and no polling, verified by inspecting the generated BPMN (`doesNotContain("asyncBefore")`) and by walkthrough tests observing the Twin transition to its next Receive Task within the same invocation.

See [Runtime Sequence Diagram](DIAGRAMS.md#2-runtime-sequence-diagram) and [Synchronization Sequence Diagram](DIAGRAMS.md#3-synchronization-sequence-diagram).

---

## 5. BPMN Transformation Rules

`TwinModelGenerator.generate()` walks the Original's deployed `BpmnModelInstance` breadth-first from its single plain start event, classifying every reachable node:

| Original construct | Twin transformation | Classification |
|---|---|---|
| User Task (no loop characteristics) | Receive Task (waits on `TwinAdvance_<id>`) → Service Task (`${twinAutomationDelegate}`) | ✅ Fully Supported |
| User Task, sequential Multi-Instance, literal cardinality | Embedded sub-process wrapping the same Receive/Service pair, `multiInstanceSequential()` | ✅ Fully Supported |
| User Task, parallel Multi-Instance, literal cardinality | Same wrapper, `multiInstanceParallel()`; siblings disambiguated by `loopCounter` at runtime | ✅ Fully Supported |
| User Task, Multi-Instance, non-literal (variable/collection) cardinality | Falls back to a single Receive/Service pair, logged | ⚠ Explicitly Unsupported (degrades safely, does not fail generation) |
| Exclusive Gateway | Copied as-is, including its default flow | ✅ Fully Supported |
| Parallel Gateway | Copied as-is | ✅ Fully Supported |
| Inclusive Gateway | Copied as-is, including its default flow | ✅ Fully Supported *([ADR-011](adr/ADR-011-unsupported-bpmn-construct-policy.md))* |
| Plain End Event (no event definitions) | Copied as-is | ✅ Fully Supported |
| Boundary Event and its outgoing flow | Dropped; the Original's own timeout/escalation is bridged over as an ordinary activity like any other the Original reaches | ⚠ Explicitly Unsupported (deliberate — see rationale below) |
| End Event with an error/escalation/terminate definition | Generation fails with a precise diagnostic | ❌ Implementation Gap |
| Event-Based Gateway | Generation fails with a precise diagnostic | ❌ Implementation Gap |
| Call Activity | Generation fails with a precise diagnostic | ❌ Implementation Gap |
| Sub-Process (embedded, transaction, event) | Generation fails with a precise diagnostic | ❌ Implementation Gap |
| Pre-existing Service/Script/Receive/Send/Business Rule/Manual Task in the Original | Generation fails with a precise diagnostic | ❌ Implementation Gap |
| Ad-Hoc Sub-Process | N/A — cannot be built at all | 🚫 Camunda Model Limitation: `camunda-bpmn-model` 7.22.0 does not provide an `AdHocSubProcess` class |

**Why Boundary Events are dropped rather than supported or fail-fast:** the Twin's copy of an activity finishes within a single command (Section 4); a boundary timer on it would fire on the Twin's own clock, sending the Twin down an escalation branch independently of the Original — the exact divergence the architecture prevents. The Original's timeout is bridged as an ordinary activity.

**Why the rest of the unsupported set fails generation rather than degrading:** Silently dropping an unrecognized construct would let a Twin deploy with entire branches missing. [ADR-011](adr/ADR-011-unsupported-bpmn-construct-policy.md) enforces an `IllegalArgumentException` naming the process, the specific activity id, and its element type. The exception fires before deployment or persistence, ensuring rejected models do not leave partial state.

### Determinism

`generate()` must produce byte-identical output for the same input across repeated calls, or `enableDuplicateFiltering` cannot recognize a relaunch of the same model as "the same deployment" and would accumulate a new Twin definition version on every launch. The Camunda builder API hands out random ids for several element kinds on every call (the `definitions` root, `conditionExpression`, `bpmn:message`, the whole BPMN DI diagram, and — discovered separately — the multi-instance loop characteristics/cardinality wrapper elements); `stabilizeMessageIds`, `stabilizeMultiInstanceIds`, and explicit id assignment on the definitions root and condition expressions replace all of them with names derived deterministically from the source element's own id. The DI diagram is dropped entirely rather than stabilized (nothing reads it — the engine ignores diagram interchange, and no modeler ever opens the generated definition).

### Metaml Extensions

`metaml:` extension elements (data-item declarations, agent output declarations) are copied through generically via the raw DOM, since the metaml namespace is never registered with the Camunda model API and its elements arrive as untyped `ModelElementInstance`s on the way in.

See [BPMN Transformation Diagram](DIAGRAMS.md#4-bpmn-transformation-diagram) and [Multi-Instance Synchronization Diagram](DIAGRAMS.md#7-multi-instance-synchronization-diagram).

---

## 6. Execution Identity Model

### The One Persisted Identity

`ActivityLink` (`originalActivityId` ↔ `twinActivityId`), held in `TwinProcess.activityLinks`, created by `connectActivity`. This is deliberately the *only* piece of cross-process identity this system ever persists. ([ADR-014](adr/ADR-014-one-to-one-activity-link-mapping.md)) The mapping is enforced as a true bijection among connected activities: one original activity links to exactly one twin activity, and one twin activity is claimed by at most one original activity at a time, with the check-then-mutate sequence made atomic (`synchronized (twin)`) because an unsynchronized version allows two concurrent links to both succeed.

### Everything Else Is Recomputed, Never Cached

| Identity concept | How it's resolved | Where |
|---|---|---|
| `activityId` | The BPMN element id — identical on both sides by construction (the generator preserves the Original's id for the Receive Task), unless remapped via `connectActivity` | `TwinModelGenerator` |
| `activityInstanceId` | Camunda's own per-visit identifier, resolved by walking the `ActivityInstance` tree (`getActivityInstances(activityId)`), never by `createExecutionQuery()` + `getActiveActivityIds()` — the latter can return a scope execution that merely *sees* the activity through a descendant without being positioned there, which `createIncident` rejects | `currentVisitId`, `loopCounterOf`, `originalExecutionIdForVisit`, `findWaitingExecutionId` |
| `executionId` (Twin side, parallel MI) | The event-subscribed execution itself, found via `createEventSubscriptionQuery()` | `resolveParallelSibling` |
| `executionId` (Original side, MI) | The "start" event's own execution — a proven asymmetry with the Twin side (see below) | `resolveParallelSibling`'s caller |
| `loopCounter` (Twin side) | Read **non-locally** (`getVariable`, not `getVariableLocal`) — it lives one scope level above the event-subscribed execution | `resolveParallelSibling` |
| `loopCounter` (Original side) | Read **locally** (`getVariableLocal`) directly off the "start" event's own execution | `loopCounterOf` |

The non-local/local asymmetry above is not derivable from Camunda's documentation and would silently return `null` if assumed symmetric.

### The Narrower, Second Form of Derived Identity

For a *plain* (non-multi-instance) activity that a token can revisit more than once through an ordinary loop-back gateway, there is no `loopCounter` to disambiguate visits by name. `alreadyEvolved` (`WorkbenchServiceImpl`) instead compares **ordinal position** — which numbered visit (by start time) this `activityInstanceId` is on the Original side, read from `historyService.createHistoricActivityInstanceQuery()` — against **how many times** `evolvedAgent_<twinActivityId>` has actually been *set*, read from `historyService.createHistoricDetailQuery().variableUpdates()` (which records every individual write, unlike `HistoricVariableInstance`, which only ever holds the current value). Both numbers come from Camunda's own history; nothing new is persisted. This still assumes visits are strictly sequential in wall-clock time, which holds for an ordinary loop-back (a single token going around a cycle) but not for two genuinely concurrent tokens re-entering the same plain activity — a documented residual gap, not a silently assumed guarantee (Section 9).

See [Execution Identity Resolution Diagram](DIAGRAMS.md#5-execution-identity-resolution-diagram).

---

## 7. Failure Semantics

### Automation Failure

The Twin's Service Task is synchronous by design (Section 4), which means a thrown exception rolls back the *entire* Camunda command — both the automation attempt and the Receive Task's own correlation — leaving the Twin's wait state exactly as it was before the attempt, its event subscription intact, safe to retry. This was proven empirically (a probe that made an automation delegate throw, confirmed the subscription survived unchanged, then manually created and resolved an incident against that execution and successfully retried the identical correlation) before being relied upon.

### Chosen Policy: Incident-Driven

Evaluated explicitly against Fail-Fast, automatic Retry, and Recoverable-without-incident alternatives ([ADR-008](adr/ADR-008-incident-driven-failure-policy.md)). On a synchronous automation failure, `WorkbenchServiceImpl.advanceTwinActivity`:

1. Releases the twin-execution governance slot it had reserved.
2. Records a real, Cockpit-visible Camunda Incident (`runtimeService.createIncident("twinAutomationFailure", executionId, twinActivityId, message)`) against the exact leaf execution the Receive Task is waiting on.
3. Leaves the Twin paused there until an operator explicitly re-bridges.

Automatic retry was rejected specifically because `ProjectAutomationService.execute()` carries no documented idempotency contract — a blanket retry could double-invoke something that charges a quota or calls an external agent with a real side effect, with no way for generic code to know whether that's safe. If a specific project's automation genuinely needs bounded retry for a known-transient dependency, that belongs inside its own `ProjectAutomationService` implementation, which is the only thing that can actually reason about its own idempotency.

### Recovery Is Restart-Safe

Re-bridging the same activity after an incident is deliberately idempotent, and that idempotence does not depend on transient in-memory state that a restart could reset (Section 6, [ADR-012](adr/ADR-012-restart-and-recovery-philosophy.md)).

### Divergence, Not Failure

An Original that takes a risk-escalation branch (because its human-facing `AgentExecutionDelegate` wrote a risk flag the Twin never sees) and a Twin that takes its own default branch are not treated as an error condition — this is a documented, accepted architectural behavior (Section 9) validated by automated regression tests.

See [Failure Recovery Diagram](DIAGRAMS.md#6-failure-recovery-diagram).

---

## 8. Component Responsibilities

| Component | File | Responsibility |
|---|---|---|
| `WorkbenchServiceImpl` | `service/WorkbenchServiceImpl.java` | Central orchestrator: model save/deploy, twin launch, activity linking, evolve/bridge, twin token advancement, incident recording, governance calls, state persistence triggers. |
| `WorkbenchService` | `service/WorkbenchService.java` | The orchestrator's public contract; what controllers and delegates call against. |
| `TwinModelGenerator` | `bpmn/TwinModelGenerator.java` | Synthesizes a deterministic, deployable Twin `BpmnModelInstance` from the Original's deployed definition (Section 5). |
| `AutoBridgeTrigger` | `bridge/AutoBridgeTrigger.java` | `AFTER_COMMIT` listener; the sole entry point that turns an Original activity-start event into a bridge call (Section 4). |
| `TwinAutomationDelegate` | `delegate/TwinAutomationDelegate.java` | Runs as the Twin's generated Service Task; resolves the synchronization activity id from the automation task's own id, dispatches to the project's `ProjectAutomationService`, writes `twinAutomation_*` outputs. |
| `AgentExecutionDelegate` | `delegate/AgentExecutionDelegate.java` | Optional `camunda:taskListener` on the Original's own user task (`event="complete"`); copies the Twin's evolved agent and its outputs back onto the Original as `agentExecuted_*`/`agentOutput_*`, including the generalized `riskFlagged` convention. Deliberately no `try/catch` — an earlier one bought nothing, since the transaction is already rollback-only by the time a listener sees the exception. |
| `ProjectAutomationService` / `DefaultProjectAutomationService` | `automation/*.java` | The pluggable per-project automation extension point. Spring collects every bean of this type into a `Map<String, ProjectAutomationService>` keyed by bean name; a twin's `projectId` selects which one runs. Exactly one real implementation exists (`"default"`), intentionally — enough to prove the extension point works without inventing requirements for hypothetical other projects. |
| `GovernanceServiceImpl` | `service/GovernanceServiceImpl.java` | Two independent quotas — evolutions per twin (agent requests) and twin-execution steps per twin (every activity the Twin's own token passes through) — both using increment-then-rollback `AtomicInteger`s for race safety, never a separate check-then-increment. |
| `WorkbenchStateStore` | `store/WorkbenchStateStore.java` | The app's own JSON persistence for `TwinProcess` bookkeeping — a twin's `activityLinks` are the one thing Camunda has no concept of, so nothing else can reconstruct them. `ProcessModel` is **not** persisted here; the H2-backed `ProcessModelArchiveStore` is its single authoritative store. Explicitly **not** where Camunda's own runtime state lives — rewrites the whole file on every mutation, deliberately excludes anything Camunda's own tables already make durable (Section 6, Section 9). |
| `NodeManagerClient` | `client/NodeManagerClient.java` | HTTP client to the external agent-catalog stub, with tight timeouts (1s connect / 2s read) so a stuck external call can never stall the single-threaded auto-bridge worker. |
| `AgentOutputDeclarations` | `bpmn/AgentOutputDeclarations.java` | Reads `metaml:agentOutputs` extension elements off a deployed definition, letting a model author republish a named agent output under a variable name of their choosing. |
| `WorkbenchController` / `GovernanceController` | `wbapi/controller/workbench/*.java` | REST surface (`/api/v1/wb/*`, `/api/v1/governance/*`) — thin, exception-to-HTTP-status translation only, no business logic. |

---

## 9. Known Limitations

Classified as: **Architectural Decision** (deliberate, would not change even with more time), **Implementation Gap** (could be closed with standard Camunda mechanisms, currently isn't), **Camunda Limitation** (genuinely not buildable with the bundled library), **Future Enhancement** (out of scope for V1.0 by choice, not by necessity).

| Limitation | Classification | Notes |
|---|---|---|
| Boundary Events dropped from the Twin | Architectural Decision | See Section 5's rationale; supporting them would reintroduce the exact divergence the architecture exists to prevent. |
| Event-Based Gateway, Call Activity, Sub-Processes, pre-existing automated task types | Implementation Gap | Now fails generation explicitly rather than silently dropping ([ADR-011](adr/ADR-011-unsupported-bpmn-construct-policy.md)); closing the gap itself is future work. |
| `AdHocSubProcess` | Camunda Limitation | Confirmed absent from `camunda-bpmn-model` 7.22.0 via direct `javap` inspection of the jar — not an oversight, not implementable with the current library version at all. |
| Two concurrent tokens re-entering the same plain activity (e.g. an Inclusive Gateway split looping back into it) | Implementation Gap (narrow, residual) | The ordinal-by-start-time disambiguation assumes strictly sequential visits, which an ordinary single-token loop-back guarantees but a genuinely concurrent re-entry would not. Not exercised by any current model or test; documented rather than silently assumed away. |
| `runEvolution` can report `approved=true` after a partial write (agent variable set, some output writes lost) | Implementation Gap (open) | Deferred; partial writes in agent variable updates require transactional boundary hardening. |
| `AutoBridgeTrigger` shutdown has a submit-vs-shutdown race | Implementation Gap (open) | Logged at `debug`, not `warn`; deferred. |
| `No optimistic-lock handling on concurrent TwinProcess mutation outside covered concurrency paths` | Implementation Gap (open) | Deferred. |
| Unbounded twin event-log growth | Implementation Gap (open) | `WorkbenchStateStore` rewrites the whole file on every mutation; a long-running twin's event log has no cap. Deferred. |
| Unbounded governance counter maps | Implementation Gap (open) | `GovernanceServiceImpl`'s own `TODO` comment: nothing ever removes a twin's counters once created. Pre-existing, deferred. |
| Governance quotas do not survive an app restart | Architectural Decision (accepted, lower severity) | Unlike the bridge dedup guard (now fully restart-safe, [ADR-012](adr/ADR-012-restart-and-recovery-philosophy.md)), a reset quota is not a correctness violation — it is a budget resetting, not state corrupting. |
| No authentication; loopback-only, `permitAll`, CSRF disabled | Architectural Decision | `WebSecurityConfig`'s own comment is explicit: anyone who can reach this API can deploy arbitrary BPMN with a `camunda:delegateExpression`/`camunda:class` and run code in this JVM. Acceptable only because `server.address=127.0.0.1`; would need real authentication before ever listening on anything else. |

---

## 10. Future Work

**Near-term** — close the open implementation gaps in priority order: partial-write false-success in `runEvolution`, the `AutoBridgeTrigger` shutdown race, then optimistic locking, event-log bounding and governance-counter cleanup).

**Long-term** — real `ProjectAutomationService` implementations beyond the one demonstration default; a real node manager replacing the stub catalog; closing the narrow concurrent-loop-back identity gap (Section 6, Section 9) if a model ever actually needs it.

**Research** — whether Camunda 8 (Zeebe)'s different execution and incident model changes any of the constraints this architecture works around (Job Executor coupling, the synchronous-rollback-based failure semantics); not investigated as part of this build, noted here as an open question rather than a recommendation either way.

**Scalability** — `WorkbenchStateStore`'s whole-file rewrite on every single mutation and the unbounded per-twin event log (Section 9) would need addressing before this runs at more than demo scale.

**Observability** — beyond Camunda's own Cockpit and application logs, there is no metrics or tracing layer; would matter for anything beyond a single-operator demo.

**Camunda 8** — not evaluated; this document makes no claim about migration feasibility either way.

---

## 11. Verification Summary

**Key empirical investigations** (recorded in the [Evolution Timeline](EVOLUTION_TIMELINE.md)): message-correlation disambiguation options for parallel Multi-Instance; the non-local/local `loopCounter` scope asymmetry; `createIncident`'s leaf-execution requirement; `HistoricDetail.variableUpdates()` vs `HistoricVariableInstance` behavior; and a cosmetic `ProcessDefinition.getId()` formatting quirk that is functionally irrelevant.

**Regression coverage:** every corrected defect has a dedicated test proving the specific behaviour it closes. The full backend suite (`wbapi` + `nodemanager`) passed clean, twice consecutively, as of the last change described in this document.

**Basis for calling this Version 1.0:** every high-priority finding from the architecture review is closed and independently re-verified, and the twin generator's synchronization, execution-identity and failure-recovery behaviour are each covered by dedicated regression tests.
