# MetaML Workbench — Verification and Demo Guide

Comprehensive walkthrough for running end-to-end demonstrations across MetaML Workbench, Target Platform code generation, and Proxy/Twin execution.

---

## 1. What this system does

```
Model (BPMN)  →  Generate (Java delegates + Target Harness Platform)  →  Launch (real child JVM)
     ↓
   Twin (shadow process instance)
     ↓
Connect / Evolve / Bridge  →  Governance (ALLOW / DENY / REQUIRE_APPROVAL)  →  real side effect
```

You draw or import a BPMN process, MetaML generates a complete standalone Spring Boot + Camunda
application from it, and runs it as its own JVM. Separately it can run a *twin* of that process
under tenant-scoped governance rules.

---

## 2. How to run it

Three processes. Start them in this order.

```bash
# 1. backend (port 8082)
cd backend
./mvnw -pl wbapi spring-boot:run

# 2. node manager (port 8083) - needed for Evolve
cd backend
./mvnw -pl nodemanager spring-boot:run

# 3. frontend (port 3000)
npm start --prefix frontend
```

Then open <http://localhost:3000>.

Data lives in `backend/wbapi/data/` (gitignored): models, workflow history, approvals, tenant
policies, generated projects, and the Camunda H2 file. Delete that folder for a clean slate.

### Menu map

| Menu | Items |
|---|---|
| **Transmute** | Create Project · Edit Existing Project |
| **Evolve** | Twin Workflow · Deployed Applications |
| **Governance** | Policies · Approvals |

---

## 3. Loading a BPMN file

**Transmute ▸ Create Project ▸ "Open BPMN file"** — pick any `.bpmn` from `demo/`.

This loads the file into the editor and *nothing else*: it does not save, deploy, generate, launch,
create a twin, or assign a tenant. **Save** remains the only thing that writes anything.

A successful import means only "bpmn-js could load this XML". It is **not** a statement that MetaML
accepts the model — Camunda validation still happens at Save.

---

## 4. Fixtures

| File | Use it for |
|---|---|
| `demo/wire-transfer-review.bpmn` | The normal Model → Generate → Launch demo |
| `demo/wire-transfer-review-twin.bpmn` | Twin / Connect / Evolve / Bridge / governance. `Task_ExecuteTransfer` is a **user task** because the twin generator rejects service tasks |
| `demo/wire-transfer-review-BROKEN.bpmn` | Global (non-element) error — `isExecutable="false"` |
| `demo/settlement-collision.bpmn` | Element-specific generation error → "Go to error" |

---

## 5. DEMO 1 — Model lifecycle

1. Transmute ▸ Create Project ▸ **Open BPMN file** → `demo/wire-transfer-review.bpmn`
2. The wire-transfer diagram renders. Click **Verify Customer Identity (KYC)** — the right panel
   switches to `USER TASK`
3. Name it `Wire Demo` → **Save** → green banner with a new model id
4. **Generate** → green: *"Generated Target Harness Platform for process "wireTransferReview" (id …)"*
5. **Launch** → green with a port number (takes 30–60s; it compiles and boots a real JVM)
6. **Evolve ▸ Deployed Applications** → the app is listed with a green `running` badge → **Stop**
7. **Ctrl+F5**, then Transmute ▸ Edit Existing Project ▸ Open `Wire Demo` — the diagram and the
   `Model ✓ Generate ✓ Launch ✓` history are still there
8. **Launch** again — works without regenerating

**Generated output:** one Java delegate class per unique delegate expression, and one REST endpoint
per *externally triggerable* activity (user/receive/external tasks). The service task correctly gets
no endpoint — the engine runs it.

---

## 6. DEMO 2 — Element-aware error recovery ⭐

The headline feature. Use `demo/settlement-collision.bpmn`.

**The setup:** two service tasks carry different delegate expressions that sanitise to the *same*
Java class name, because `toClassName` maps every illegal identifier character to `_`:

```
Task_SettleA   camunda:delegateExpression="${settle_payment}"   ->  Settle_payment
Task_SettleB   camunda:delegateExpression="${settle-payment}"   ->  Settle_payment
```

Only one class file can be written. `Task_SettleA` is encountered first and names the class, so
**`Task_SettleB` is the element that loses** — its bean would silently never exist at runtime.
Camunda accepts both expressions at deploy time, so nothing upstream catches this. Generation is
the first and only place it can be caught.

**Steps:**

1. Ctrl+F5 → Create Project → **Open BPMN file** → `demo/settlement-collision.bpmn`
2. Name it `Collision Demo` → **Save** → **succeeds** (this is the point — the model is valid BPMN)
3. **Generate** → **RED**:
   > BPMN element 'Task_SettleB' declares delegateExpression '${settle-payment}', which generates
   > the same delegate class 'Settle_payment' as '${settle_payment}'. Only one of them can exist,
   > so rename one of the two expressions.
4. **View details ▾** →
   - `ERROR TYPE: InvalidDelegateExpressionException`
   - `OPERATION: GENERATE_PROJECT`
   - `DELEGATE: ${settle-payment}`
   - `BPMN ELEMENT: Task_SettleB`
   - a **Go to error** button
5. Click **Go to error** → **"Settle Payment Retry" (`Task_SettleB`) is selected and scrolled to**,
   and the properties panel switches to it. *Not* `Task_SettleA` — pointing at the wrong task would
   be worse than pointing nowhere
6. Change its **Delegate expression** to `${settlePaymentRetry}`
7. **Save** → **Generate** → **GREEN**. The project now contains both `Settle_payment.java` and
   `SettlePaymentRetry.java`

> **Expect a new model id at step 7.** Save never overwrites — a new id is minted every time, by
> design, because twins already launched still point at the old definition.

---

## 7. DEMO 3 — Global error (the contrast that makes DEMO 2 credible)

Use `demo/wire-transfer-review-BROKEN.bpmn` (`isExecutable="false"`).

1. Ctrl+F5 → Create Project → **Open BPMN file** → the BROKEN fixture
2. It **renders fine** — import ≠ acceptance
3. Name it `Broken Demo` → **Save**
4. **RED**: *"Save failed: BPMN process must have isExecutable="true" on the bpmn:process element"*
5. Confirm what is **absent**: no `BPMN ELEMENT` field, **no Go to error button**, no element
   highlighted

That absence is the demonstrated behaviour. A failure that cannot be attributed to one element
never invents one.

**Live recovery:** select the process background, tick **Executable** in the properties panel
General section, **Save** → succeeds.

### Element-specific vs global

| | Fails at | Carries `bpmnElementId` | Go to error |
|---|---|---|---|
| Delegate class-name collision | Generate | yes | **shown** |
| `isExecutable="false"`, no process element, missing template dir | Save / Generate | no — explicitly `null` | **hidden** |

---

## 8. DEMO 4 — Tenant + governance

**Tenant context is not authentication.** The UI says so: *"Acting as tenant (not authenticated -
no login exists in this system yet)"*. `tenantId` is caller-supplied. It scopes governance; it does
not prove identity.

1. **Governance ▸ Policies** → type a name in **New tenant name** → **Create tenant** (e.g.
   `DemoBank`). Note the tenant id — the Approvals page needs it
2. Type a policy name → **Create policy** (e.g. `Evolve Policy`)
3. **+ New draft version** → on the **DRAFT row**, fill the inline rule controls:

   | field | operator | value | effect |
   |---|---|---|---|
   | `action` | `==` | `EVOLVE_TWIN` | `REQUIRE_APPROVAL` |

   → **+ Add rule** → confirm the row reads `action == EVOLVE_TWIN → REQUIRE_APPROVAL`
4. **Activate** the version. Older versions auto-move to `RETIRED`
5. **Test evaluation** at the bottom: action = `EVOLVE_TWIN` → **Evaluate** → **REQUIRE_APPROVAL**

> Rules only go into a **DRAFT**. Activating an empty draft gives an ACTIVE version with "no rules",
> and evaluation then falls through to `ALLOW (no matching rule)`. If the tester returns ALLOW,
> check the Rules column actually shows your rule.

**Then the twin half:**

6. Ctrl+F5 → Create Project → **Open BPMN file** → `demo/wire-transfer-review-twin.bpmn`
7. **Select your tenant in the dropdown *before* Save** → name it `Twin Demo` → **Save**
8. **Evolve ▸ Twin Workflow** → select the model → **Deploy twin** → select `Task_KYC` → **Connect**
9. Evolve with agent `unvetted` → **RED denied**, and **no approval row is created**
10. Evolve with agent `validator` → **AMBER**, *"Evolve needs approval before it can run"* with an
    approval id
11. **Governance ▸ Approvals** → **re-select the tenant** (the link does not carry it — the queue
    looks empty until you do) → the `PENDING` row → **Approve** → the pinned decision executes

Governance layers, in order: platform quota (runtime-only, per twin) → tenant policy → decision.
A twin with `tenantId == null` is deliberately ungoverned.

---

## 9. Status colours

| Colour | Hex | Meaning |
|---|---|---|
| GREEN | `#2f7d5c` | Stage completed / action allowed and executed |
| AMBER | `#a06a00` | In progress, or blocked pending a human decision |
| RED | `#c0392b` | Failed **or** denied — nothing executed |
| GREY | `#9aa5ad` | Not started |

RED covers both "governance said no" and "something broke" — the banner text disambiguates.

---

## 10. Verified by hand on 2026-08-12

- BPMN file import → render → select → edit → Save → reopen
- Full lifecycle: Save → Generate → Launch → Stop → reload → reopen → relaunch
- Element-aware recovery: collision → RED → Go to error → **correct element** → fix → GREEN
- Global error: RED, no element blamed, no Go to error offered
- Model persistence across a real backend restart
- Governance: tenant → policy → draft → rule → activate → version retirement, and
  `EVOLVE_TWIN → REQUIRE_APPROVAL` returned by the live evaluator

**Covered by the automated suite but not re-demoed by hand that day:** the approval
resolution flow, DENY end-to-end, platform quota exhaustion, generated-app liveness after an
external JVM kill, and model deletion / ID retirement.

Backend suite, re-measured 2026-08-24: **299 tests, 1 failure** (216 `workbench`, 77 `wbapi`,
6 `nodemanager`; `workbench` count excludes 17 additional `@Tag("slow")` tests — real Maven
builds/JVM launches — not re-run for this count). The 1 failure
(`SpringBootProjectLauncherMavenInstallTest`) is environment-dependent (no system-wide `mvn` on
this machine) and unrelated to product code.

---

## 11. Known limitations (deliberate or accepted)

1. **No authentication anywhere.** `tenantId` is caller-supplied. Tenant scoping catches mistakes,
   not impersonation.
2. **Save never overwrites** — every save mints a new model id, because launched twins still
   reference the old definition.
3. **Running child JVMs are not re-adopted after a backend restart** — they keep running and hold
   their port, invisible to the workbench. Don't restart the backend mid-demo.
4. **Approvals page doesn't inherit the tenant** from the "Go to Approvals" link; re-select it.
5. **A failed Save shows no details panel.** The backend records `MODEL FAILED` with a `StageError`,
   but the failed save returns no model id, so the UI has nothing to fetch — `View details` is
   disabled with the tooltip "Save the model first". The red banner is the whole UI surface for
   that failure.
6. **Generated-app `launch.log` is not surfaced in the UI.** On a launch failure the error names the
   log path; the content isn't inlined.
7. **bpmn-js import warnings are discarded** — a file that parses but only partially resolves
   imports "successfully" and renders incompletely.
8. **Platform quota is runtime-only** by design; it resets to configured defaults on restart. Tenant
   policy, by contrast, is durable. See `GovernanceServiceImpl`'s own comment for why.

---

## 12. What NOT to claim

- ❌ "Every BPMN error is automatically pinpointed." Only the class-name collision and a
  delegate-file write failure carry an element id.
- ❌ "MetaML authenticates tenants / has tenant isolation." Say *tenant-scoped governance with an
  explicit, unauthenticated tenant context*.
- ❌ "Importing a BPMN means MetaML accepts it." Import means bpmn-js loaded it; Save validates.
- ❌ "Editing a model updates it in place." Every Save creates a new model id.
- ❌ "Production ready." No auth, whole-file JSON persistence, single-JVM concurrency guards, no
  formal API contract.

---

## 13. Where things live

```
backend/wbapi/        Spring Boot app (:8082) - REST controllers
backend/workbench/    all domain logic - service, codegen, generation, governance, workflow, store
backend/nodemanager/  agent catalogue (:8083)
frontend/src/pages/workbench/    ModelPage, EvolvePage, DeployedAppsPage, Governance*
templates/camundademo/           the Spring Boot template projects are generated from
demo/                            fixtures + DEMO_PROTOCOL.md (regression script)
docs/architecture/               ARCHITECTURE.md, DIAGRAMS.md, ADRs
PROJECT_STATUS.md                current project status - read this first
```

---

## 14. DEMO 5 — Proxy/Twin synchronization (RabbitMQ)

This is a **generated Target Platform** demo, not a Workbench-UI demo — it runs against a
`RedCollar.Manuf` project already generated and launched (Generate → Launch, see DEMO 1). It needs
RabbitMQ up (`docker start metaml-rabbitmq`) and the launch started with `METAML_MESSAGING_ENABLED=true`
(the Workbench's own Launch button already sets this for you).

**Terminology, unchanged from the architecture docs:** **Proxy** = the original/user-authored BPMN
side (`RedCollar.Manuf`, reached under `/api/v1/manufacturing/...` because RedCollar Manufacturing is
the demonstration process — the path name does not rename the architectural role). **Twin** =
the corresponding digital-twin side (`RedCollar.Manuf_twin`, reached under `/api/v1/twin/...`).

The Target Platform's own port is **dynamically allocated per Launch** — read it from the Launch
response or **Evolve ▸ Deployed Applications**, and substitute it below as `<TARGET_PLATFORM_PORT>`.

### 14.1 Start Proxy and Twin

```bash
BASE_URL="http://localhost:<TARGET_PLATFORM_PORT>"
BK=demo-$(date +%s)

curl -s -X POST "$BASE_URL/api/v1/manufacturing/start?businessKey=$BK"; echo   # Proxy
curl -s -X POST "$BASE_URL/api/v1/twin/start?businessKey=$BK";           echo   # Twin
```

Expect `"role":"initiator"` (Proxy) and `"role":"responder"` (Twin), each with its own
`processInstanceId`, both carrying the same `businessKey`.

> **Note:** Start Proxy and Twin with the same `businessKey` to exercise cross-process synchronization. A Proxy-only run will execute independently without message routing.

### 14.2 Watch the pair advance

```bash
PROXY=<proxy processInstanceId>
TWIN=<twin processInstanceId>

watch -n1 "curl -s $BASE_URL/api/v1/process/$PROXY/status; echo; \
           curl -s $BASE_URL/api/v1/process/$TWIN/status"
```

(No `watch` on Windows — loop `curl` + `Start-Sleep -Seconds 1` in PowerShell instead.)

Look for: Proxy's `activeActivityIds` holding the **same value across consecutive samples**
(it's waiting) while Twin's `agentTopic`/`agentInvocationId` change (it's executing) — then Proxy's
`activeActivityIds` advancing right after. Both end at `{"active":false}`.

### 14.3 Application Logs and Event Sequence

```bash
cd <GENERATED_TARGET_PLATFORMS_ROOT>/<PROJECT_ID>
tail -f launch.log | grep -E "TASK: published|TASK: delivered|RESPONSE: published|RESPONSE: delivered|\[Twin\] Invoking"
```

Each RabbitMQ-synchronized activity logs the coordinated message exchange:
1. `TASK: published activity '<X>Twin' (signal '<y>Signal') ... (processInstanceId=<TWIN>)`
2. `TASK: delivered signal ... via RabbitMQ`
3. `[Twin] Invoking simulated ML agent for activity "<X> Twin"`
4. `RESPONSE: published activity '<X>Twin' ... (processInstanceId=<PROXY>)`
5. `RESPONSE: delivered signal ... via RabbitMQ`
6. `Executing generated external-task worker for activity "<X>" (process instance <PROXY>)`

The Proxy worker executes after the Twin completes and returns the response event.

**Not every activity crosses RabbitMQ every run.** `SignalBroadcaster` only routes through the
queue pair when both sides are concurrently waiting on the same signal; a signal with no live
counterpart on the other side (e.g. a rework-loop revisit) is released in-process and logs as
`DELIVERED: delivered signal ...` instead. That is intentional deadlock-avoidance, not a bug, and
which activities take which path can legitimately differ between otherwise-identical runs.

### 14.4 Architecture this demonstrates

- **H2** (`jdbc:h2:mem:process-engine`) persists both Proxy and Twin — same embedded engine, which
  is what `/status` and `/recent-instances` read from.
- **RabbitMQ** carries the Proxy↔Twin task/response handoff for activities that cross that
  boundary — one task queue + one response queue per activity, on a per-project exchange
  (`redcollar.<projectId>.exchange`), so two generated platforms can never collide.
- **`SignalBroadcaster`** is the coordinator that decides *when* each handoff fires — a
  generated `@Scheduled(fixedDelay = 1000)` bean that polls Camunda's own event-subscription state
  once a second to detect "both sides are now waiting on the same signal" or "the responder has
  moved on." Accurate framing for this mechanism: **signal-driven lockstep synchronization with a
  lightweight 1-second polling coordinator** — not "event-driven, not polling." (That phrase
  describes a different, in-process mechanism used by the Workbench's own Original/Twin bridge —
  see [ADR-004](docs/architecture/adr/ADR-004-event-driven-synchronization.md).) Each handoff costs
  up to ~1s of added latency for this reason; RabbitMQ delivery itself is near-instant (single-digit
  to low-double-digit milliseconds) once `SignalBroadcaster` decides to send it.

**Verified by hand on 2026-08-23** (`RedCollar.Manuf` project, port 8099, `businessKey=demo-1787512050`):
Proxy `pid 619` / Twin `pid 627`, 7 activities round-tripped over RabbitMQ (published = delivered =
7 each), 9/9 Twin workers executed, both instances completed with no incidents.
