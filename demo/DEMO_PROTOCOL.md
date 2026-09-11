# MetaML Workbench — Demo / Regression Protocol

This walkthrough guides manual end-to-end verification, platform demonstration, and
regression testing against a running MetaML Workbench instance.

## Fixtures

| File | Purpose | Save result |
|---|---|---|
| `wire-transfer-review.bpmn` | Canonical process. Model → Generate → Launch, delegate generation, per-activity endpoints. | **200** |
| `wire-transfer-review-twin.bpmn` | Same process, `Task_ExecuteTransfer` as a user task. Twin / Connect / Evolve / Bridge / governance. | **200** |
| `wire-transfer-review-BROKEN.bpmn` | Same process with `isExecutable="false"`. Real save-time rejection → RED. | **400** |
| `settlement-collision.bpmn` | Two service tasks whose different delegate expressions sanitise to one Java class name. Saves fine, fails at **generation** with the element named → "Go to error". | **200** |

Two wire-transfer variants are required, not a convenience: `TwinModelGenerator` rejects a
`serviceTask` outright (*"activity Task_ExecuteTransfer is a serviceTask, which the twin generator
does not support"*), so one file cannot drive both pipelines. The business process is identical.

`settlement-collision.bpmn` is a separate, deliberately smaller process. It exists because the
wire-transfer model has only one delegate expression and therefore cannot collide with itself; the
element-specific generation failure needs two service tasks in the same model.

## Process

```
                                    ┌── Yes ──► Screen Sanctions (OFAC) ──► Approve Amount
Start ──► Verify Identity (KYC) ──► ◇ Identity                                     │
                                    verified?                                       ▼
                                    └── No ───► Reject: Identity      Execute Wire Transfer
                                                Not Verified                        │
                                                     │                              ▼
                                                     ▼                   Notify Customer
                                              Transfer Rejected                     │
                                                                                    ▼
                                                                          Transfer Completed
```

`Flow_IdentityNo` is the gateway default — Camunda rejects a deployment where a non-default
outgoing flow of an exclusive gateway carries no condition (this was hit and fixed while building
the fixture). `Flow_IdentityYes` carries `${identityVerified}`.

Element ids for the script: `Task_KYC`, `Gateway_IdentityVerified`, `Task_RejectIdentity`,
`Task_OFAC`, `Task_ApproveAmount`, `Task_ExecuteTransfer`, `Task_NotifyCustomer`.

## Setup

Import a fixture through **Transmute ▸ Create Project** (paste the XML), or POST it:

```bash
curl -X POST http://localhost:8082/api/v1/wb/transmute/model -H "Content-Type: application/json" -d @model.json
```

For the governance states the model must carry a `tenantId` — set it with the tenant selector on
the model editor, and create the tenant first under **Governance ▸ Policies**.

Policy used below (one version, two rules, in this order — first match within a version wins):

| # | field | op | value | effect |
|---|---|---|---|---|
| 1 | `agentType` | `==` | `unvetted` | **DENY** |
| 2 | `action` | `==` | `EVOLVE_TWIN` | **REQUIRE_APPROVAL** |

## States

### GREEN — success

- **Start:** `wire-transfer-review.bpmn` saved.
- **Action:** Model editor → **Generate**.
- **Expected UI:** breadcrumb `Model ✓` `Generate ✓`, both green `#2f7d5c`.
- **Expected backend:** `MODEL COMPLETED`, `GENERATE COMPLETED`, `LAUNCH PENDING`; one delegate
  `ExecuteWireTransferService`; a project directory under `generated-projects/`.
- **Colour:** GREEN (done) + GREY `#9aa5ad` (`Launch`, not started).

### AMBER — approval required

- **Start:** `wire-transfer-review-twin.bpmn` saved with a tenant, policy active, twin deployed,
  `Task_KYC` connected.
- **Action:** Evolve page → select `Task_KYC` → Agent `validator` → **Evolve**.
- **Expected UI:** *"Evolve needs approval before it can run: Approval required (id …)"* in amber
  `#a06a00`, with a **Go to Approvals** link.
- **Expected backend:** decision `approved=false`, `governanceDecision=REQUIRE_APPROVAL`; one
  `PENDING` approval for the tenant.
- **Colour:** AMBER.
- **Recovery:** follow the link → select the tenant → **Approve** → the pinned decision executes.

### RED — denial

- **Action:** same, Agent `unvetted`.
- **Expected UI:** *"Evolve blocked: …"* in red.
- **Expected backend:** `approved=false`, `governanceDecision=DENY`; **no** approval created.
- **Colour:** RED.

### RED — real failure (save-time rejection)

- **Action:** save `wire-transfer-review-BROKEN.bpmn`.
- **Expected UI:** *"BPMN process must have isExecutable="true" on the bpmn:process element"*.
- **Expected backend:** HTTP **400**; `MODEL FAILED`; no model created.
- **Colour:** RED.
- **Recovery:** flip `isExecutable` to `true` and save again → 200.

### RED — real failure (element-specific, at generation)

- **Action:** save `settlement-collision.bpmn` (200), then **Generate**.
- **Expected UI:** `Generate ✕` red, with the element named in the message and a **Go to error**
  button in the details panel.
- **Expected backend:** `GENERATE FAILED` carrying `bpmnElementId = Task_SettleB`.
- **Colour:** RED.
- **Recovery:** the full flow is written out under *Element-aware error recovery* below.

### Per-activity endpoint generation

After Generate, the generated controller exposes exactly one endpoint per **externally-triggerable**
activity — the service task is correctly absent, because the engine runs it:

```
verify-customer-identity-kyc      reject-identity-not-verified
screen-against-sanctions-list-ofac  approve-transfer-amount
notify-customer-of-completion
```

### Other states

- **Connect** — select `Task_KYC` → **Connect** → bar switches to `● Connected` + Evolve/Bridge.
- **Bridge** — after Complete task(s), select the next activity → **Bridge**. A repeat bridge
  reports *"already forwarded … no change"* as informational, not an error.
- **Deletion refusal** — launch the generated app, then Catalog ▸ Delete → **409** with a
  *Go to Deployed Applications* link.
- **Approval rejection** — Approvals ▸ **Reject** → `REJECTED` (red badge); the action never runs.
  Re-running Evolve creates a fresh approval.

## Regression checklist

| # | Step | Expect |
|---|---|---|
| 1 | Save `-BROKEN` | 400, `MODEL FAILED` |
| 2 | Save valid | 200, `MODEL COMPLETED` |
| 3 | Generate | `GENERATE COMPLETED`, delegate `ExecuteWireTransferService` |
| 4 | Inspect controller | 5 endpoints, no service-task endpoint |
| 5 | Launch | `LAUNCH COMPLETED`, port assigned |
| 6 | Save `-twin` with tenant, deploy twin, connect `Task_KYC` | twin id returned, connect 200 |
| 7 | Evolve `unvetted` | `DENY`, red, no approval |
| 8 | Evolve `validator` | `REQUIRE_APPROVAL`, amber, 1 `PENDING` |
| 9 | Approve | executes under the pinned decision |
| 10 | Delete model with app running | 409 + recovery link |
| 11 | Save `settlement-collision.bpmn`, Generate | `GENERATE FAILED`, red, `bpmnElementId = Task_SettleB`, **Go to error** shown |
| 12 | Go to error → rename to `${settlePaymentRetry}` → Save → Generate | `GENERATE COMPLETED`, both `Settle_payment.java` and `SettlePaymentRetry.java` written |

## Element-aware error recovery — "Go to error"

MetaML provides element-aware error recovery **when a generation failure is attributable to a
specific BPMN element**. This is not a claim that every BPMN error is automatically pinpointed —
see "Element-specific vs global" below for exactly where the line falls.

The **Go to error** button in the workflow details panel renders only when the recorded
`StageError` carries **both** `delegateExpression` and `bpmnElementId`. Two failures populate them:

- `InvalidDelegateExpressionException` — a delegate expression that cannot produce its own class.
  The reachable case is a **class-name collision**: two distinct expressions that sanitise to one
  Java class name. `DelegateClassGenerator.toClassName` maps every character that is illegal in a
  Java identifier to `_`, so `${settle_payment}` and `${settle-payment}` both become
  `Settle_payment`. Only one file can be written; the other element's bean would silently never
  exist at runtime. Camunda accepts both expressions at save/deploy time, so nothing upstream
  rejects the model — generation is the first and only place this can be caught.
- `DelegateWriteException` — writing a delegate `.java` file raised an `IOException`. Real, but not
  reproducible from a fixture (it needs a filesystem failure).

### Verified flow (fixture: `settlement-collision.bpmn`)

Save the fixture through **Transmute ▸ Create Project**, then:

```
Generate
   ↓
RED  Generate ✕  "BPMN element 'Task_SettleB' declares delegateExpression '${settle-payment}',
     which generates the same delegate class 'Settle_payment' as '${settle_payment}'.
     Only one of them can exist, so rename one of the two expressions."
   ↓
View details ▾   ERROR TYPE  InvalidDelegateExpressionException
                 OPERATION   GENERATE_PROJECT
                 DELEGATE    ${settle-payment}
                 BPMN ELEMENT Task_SettleB
   ↓
Go to error      → Task_SettleB is selected and scrolled to in the live modeler;
                   the properties panel switches to "Settle Payment Retry"
   ↓
change its Delegate expression to  ${settlePaymentRetry}
   ↓
Save
   ↓
Generate
   ↓
GREEN  Generate ✓ — the project now contains BOTH Settle_payment.java and
       SettlePaymentRetry.java
```

`Task_SettleA` is encountered first and names the generated class, so **`Task_SettleB` is the
element that loses** and the one the error must point at. Verified end to end against a running
backend and the real UI.

Note: Save never overwrites — the successful regenerate lands on a **new model id**. That is the
product's designed behaviour (launched twins still reference the old definition), not a fault in
the recovery flow. This is expected behavior.

### Element-specific vs global

| | Carries `bpmnElementId` | "Go to error" | Example |
|---|---|---|---|
| **Element-specific** | Yes | Shown | Delegate class-name collision (`settlement-collision.bpmn`) |
| **Global** | No (`null`) | Hidden | No process element, missing template directory, a failure writing the project tree; and at the MODEL stage, `isExecutable="false"` (`wire-transfer-review-BROKEN.bpmn`) |

On global failures, the "Go to error" button remains hidden because the issue cannot be localized to a single element.
