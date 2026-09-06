# MetaML Comprehensive E2E BPMN Acceptance Report

## Executive Status

```
PASS WITH LIMITATIONS
```

Every construct classified below as "SUPPORTED AND IN SCOPE" was proven end-to-end this run: fresh-generated from current HEAD, cleanly compiled, deployed to a real Camunda engine, and executed with externally observable evidence (real HTTP calls, real log output, real RabbitMQ broker traffic — not mocked). Two constructs (Script Task, and Receive/Send Task as first-class proxy constructs) were found genuinely unsupported and are reported honestly below rather than implemented during this acceptance run.

## Environment

```
Git HEAD:            800beb8 + uncommitted
                      Pass 1-3/DLQ/Phase-5 hardening (unchanged by this run)
Repository:          https://github.com/nirmantaterh/metaml_su_2026/
RedCollarTP template: backend/RedCollarTP (Camunda 7.20.0, Spring Boot 3.1.12, Java 25)
RabbitMQ broker:      real Docker container (rabbitmq:3-management), localhost:5672/15672
Generation mechanism: SpringBootProjectGenerator.generate(bpmnXml, List.of()) - the auto-derived
                      Twin (mirror) path, same one production Generate/Launch uses
```

## Fixture

Two dedicated fixtures were authored for this run. The professor-supplied RedCollar BPMN was **not** modified.

- **`fixtures/kitchen-sink-process.bpmn`** — Start Event → User Task (2 Task Listeners: `create` + `complete`) → Exclusive Gateway (default + conditional routing) → Service Task with Execution Listener / Service Task (unmet-condition branch, not taken) → Exclusive Join → Parallel Gateway (split) → 2 Service Tasks → Parallel Join → Signal Intermediate Catch Event → End Event. Rendered to [`kitchen-sink-process.svg`](kitchen-sink-process.svg).
- **`fixtures/additional-constructs-process.bpmn`** — Start Event → Manual Task → Inclusive Gateway (both branches' conditions true) → 2 Service Tasks (both taken) → Inclusive Join → End Event.

Both were auto-derived into paired Proxy/Twin Target Platforms via the same `TargetPlatformTwinMirrorGenerator` path production uses (not hand-authored twins).

## 1. Actual Supported BPMN Matrix (investigated before fixture design)

| Construct | Classification | Basis |
|---|---|---|
| Service Task (external) | SUPPORTED AND IN SCOPE | `ExternalTaskWorkerGenerator`; RedCollarTP's entire real BPMN is built from these |
| User Task | SUPPORTED AND IN SCOPE | Native Camunda; Task Listener generation added in Phase 5 |
| Task Listener (`delegateExpression` form) | SUPPORTED AND IN SCOPE | `TargetPlatformSourceGenerator.scanTaskListeners` (Phase 5) |
| Task Listener (`class=`/`expression=` forms) | UNSUPPORTED (by design) | Deliberately left ungenerated rather than silently mis-generated |
| Execution Listener | SUPPORTED AND IN SCOPE | `TargetPlatformSourceGenerator.scanExecutionListeners` |
| Exclusive Gateway | SUPPORTED AND IN SCOPE (declarative) | Camunda-native `conditionExpression`; zero MetaML Java needed |
| Parallel Gateway | SUPPORTED AND IN SCOPE (declarative) | Same as above; no generator code touches gateways at all |
| Inclusive Gateway | SUPPORTED AND IN SCOPE (declarative) | Same as above |
| Start / End Event | SUPPORTED AND IN SCOPE | Native Camunda |
| Intermediate Signal Catch Event | SUPPORTED AND IN SCOPE | `TargetPlatformMessagingGenerator` + `SignalBroadcaster` (real RabbitMQ transport) |
| Manual Task | SUPPORTED (declarative, zero configuration) | Camunda auto-completes; not scanned by any generator, proven this run |
| Script Task | **UNSUPPORTED in this template** | No JSR-223 scripting engine (Groovy/JS) dependency in `backend/RedCollarTP/pom.xml`; a live attempt produced a real 500 at process-start (see §4) |
| Business Rule Task | NOT VERIFIED | Not exercised this run; would need a deployed DMN table |
| Send Task | NOT VERIFIED / effectively unsupported | No MetaML wiring exists for it; not exercised this run |
| Receive Task (as a first-class Proxy construct) | UNSUPPORTED | Only reachable as a rewrite target for an older authored-twin convention (`TargetPlatformSourceGenerator.replaceTwinReceiveTasksWithSignals`), established in Phase 2; a bare Proxy-side receiveTask has no publisher and would hang |
| Message / Timer / Boundary Event | NOT VERIFIED | No generator-specific handling exists; would deploy natively but has no RabbitMQ-sync semantics; not tested to avoid overclaiming |
| SubProcess / Multi-Instance | NOT VERIFIED | No generator-specific handling in the RedCollarTP pipeline; not tested this run |

## 2. Generated Output

Full tree: [`results/generated-tree.txt`](results/generated-tree.txt). Key locations (kitchen-sink fixture):

```
proxy/listeners/KitchenSinkExecutionListener.java
proxy/listeners/KitchenSinkTaskCreateListener.java
proxy/listeners/KitchenSinkTaskCompleteListener.java
twin/listeners/KitchenSinkExecutionListenerTwin.java
twin/listeners/KitchenSinkTaskCreateListenerTwin.java
twin/listeners/KitchenSinkTaskCompleteListenerTwin.java
worker/proxy/{ServiceA,ServiceB,ServiceC,ServiceD}Worker.java
worker/twin/{ServiceA,ServiceB,ServiceC,ServiceD}TwinWorker.java + TwinDecisionAgent.java
messaging/{RabbitMqConfig,TaskQueuePublisher,TaskQueueListener,ResponseQueuePublisher,
           ResponseQueueListener,DeadLetterQueueListener}.java
coordination/PairRegistry.java
signal/SignalBroadcaster.java
src/main/resources/processes/KitchenSinkManuf.bpmn + KitchenSinkManuf_twin.bpmn
```

Both Task Listeners and the Execution Listener land in the same `listeners/` directory — the current architecture's correct, existing convention (not a new directory invented for this run). Generated proxy/twin BPMN copies: [`generated/kitchen-sink-KitchenSinkManuf.bpmn`](generated/kitchen-sink-KitchenSinkManuf.bpmn), [`generated/kitchen-sink-KitchenSinkManuf_twin.bpmn`](generated/kitchen-sink-KitchenSinkManuf_twin.bpmn).

## 3. Construct Matrix (this run's actual proof)

| Construct | Generated | Compiled | Runtime | Observable Evidence | Result |
|---|---:|---:|---:|---|---|
| Service Task | Yes | Yes | Yes | `worker/{proxy,twin}` logs: "Executing generated external-task worker..." | PASS |
| User Task | Yes | Yes | Yes | Real completion via acceptance-harness controller (200 OK), activity-history count | PASS |
| Task Listener (create+complete) | Yes | Yes | Yes | Both `PROXY`/`TWIN (TASK LISTENER)` log markers observed, both events | PASS |
| Execution Listener | Yes | Yes | Yes | `PROXY (LISTENER) - kitchenSinkExecutionListener` log marker observed | PASS |
| Exclusive Gateway | Yes | Yes | Yes | ServiceA (default) visited exactly once; ServiceB (unmet condition) never visited | PASS |
| Parallel Gateway | Yes | Yes | Yes | ServiceC and ServiceD both visited exactly once | PASS |
| Inclusive Gateway | Yes | Yes | Yes | ServiceE and ServiceF (additional-constructs fixture) both visited exactly once | PASS |
| Manual Task | N/A (no Java) | N/A | Yes | `ReviewManually` activity-history count = 1, zero configuration | PASS |
| Signal Event / Proxy-Twin lockstep | Yes | Yes | Yes | Real RabbitMQ TASK+RESPONSE round-trip (see `results/lockstep-evidence.json`) | PASS |
| Twin mirroring | Yes | Yes | Yes | `results/proxy-vs-twin.json` | PASS |
| Script Task | N/A | N/A | **Failed (500)** | `logs/additional-constructs-build.log`, absence of scripting-engine dep in pom.xml | **UNSUPPORTED (documented, not implemented)** |
| Receive Task, Send Task, Business Rule Task | — | — | — | Not exercised (established unsupported/not-verified in Phase 2) | UNSUPPORTED / NOT VERIFIED |

## 4. Confirmed Non-Defects (things that failed during iteration, but are not MetaML generator defects)

Three real issues surfaced while building this acceptance harness. Each was investigated and none required a MetaML production-code change:

1. **BPMN authoring bug** (mine): a `<bpmn2:signal>` declaration placed inside `<bpmn2:process>` instead of at `<definitions>` level — real BPMN 2.0 XSD violation, fixed in the fixture. Not a MetaML defect.
2. **JUEL evaluation on an undefined variable**: `${orderApproved == true}` where `orderApproved` was never set caused a 500 on task completion. Fixed by referencing the always-present implicit `execution` variable instead. Not a MetaML defect — a fixture-authoring mistake.
3. **Script Task**: `scriptFormat="groovy"` failed at process-start because `backend/RedCollarTP/pom.xml` has no scripting-engine dependency. This is an accurate, honest finding: **Script Task is unsupported in the current RedCollarTP template**, not a MetaML generator bug (no generator claims to support it, and the gap is a missing runtime dependency, not missing generation logic). Documented in §1, not implemented.

## 5. Proxy/Twin

`TargetPlatformTwinMirrorGenerator` produced a near-verbatim structural mirror of the Proxy BPMN for the Twin, exactly as established in Phases 0-6:

- **Intentional transformations**: process id (`KitchenSinkManuf` → `KitchenSinkManuf_twin`), Task/Execution Listener bean names (`Twin` suffix, avoiding a Spring `ConflictingBeanDefinitionException`).
- **Expected preservation**: activity ids, gateway types and conditions, signal name/definitions, sequence flows, DI.

Full comparison: [`results/proxy-vs-twin.json`](results/proxy-vs-twin.json).

## 6. Runtime Evidence

- [`logs/build.log`](logs/build.log) — clean `mvn clean install` success.
- [`logs/application.log`](logs/application.log) — full launch + runtime log, Spring context, Camunda init, BPMN deployment, RabbitMQ connection.
- [`logs/runtime.log`](logs/runtime.log), [`logs/task-listener.log`](logs/task-listener.log) — filtered evidence excerpts.
- [`results/runtime-results.json`](results/runtime-results.json), [`results/task-listener-result.json`](results/task-listener-result.json), [`results/execution-listener-result.json`](results/execution-listener-result.json), [`results/lockstep-evidence.json`](results/lockstep-evidence.json).

## 7. Regression

Full named suite, run fresh against current HEAD as part of this acceptance run:

| Test class | Result |
|---|---|
| `TargetPlatformSourceGeneratorTest` | 13/13 PASS |
| `TargetPlatformTwinMirrorGeneratorTest` | 4/4 PASS |
| `TargetPlatformMessagingGeneratorTest` | 10/10 PASS |
| `LockstepTimingInvariantTest` | 1/1 PASS |
| `LockstepSyncIntegrationTest` | 4/4 PASS |
| `ExternalTaskWorkerGeneratorTest` | 6/6 PASS |
| `TaskListenerTargetPlatformEndToEndTest` | 1/1 PASS (47.44s) |
| `RedCollarTargetPlatformSyncEndToEndTest` | 6/6 PASS (564.6s) |
| **Total** | **45/45 PASS, 0 failures, 0 errors, 0 skipped** |

No regression in any previously validated mechanism (RabbitMQ reliability, Proxy/Twin lockstep, Execution Listener, Twin mirroring, Task Listener).

## 8. Limitations

- Script Task, Business Rule Task, Send Task, Receive Task (proxy-side), Message/Timer/Boundary Events, SubProcess, and Multi-Instance are not currently supported or not verified for the RedCollarTP pipeline — evidence-backed, listed in §1, none implemented during this run per the explicit "do not implement unsupported constructs" instruction.
- The generated project ships no `/engine-rest`; this acceptance run added a small hand-written `AcceptanceTestController` directly to the *generated project's own source tree* (not to any MetaML generator) solely to complete User Tasks over HTTP — the same thing any developer extending a generated project would do.
- Multi-pair isolation was not independently re-verified for the RedCollarTP pipeline specifically in this run (carried over from Phase 6's finding).
