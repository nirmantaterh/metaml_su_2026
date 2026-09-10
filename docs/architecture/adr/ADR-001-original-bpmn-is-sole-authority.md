# ADR-001: The Original Process Instance Is the Sole Authority, With One Bounded, Declared Exception

**Status:** Accepted (Version 1.0)

## Context

A digital twin architecture requires an unambiguous answer to which runtime entity possesses authority over business state. Two live process instances that could both independently drive business decisions would make execution outcomes dependent on concurrency races, defeating the purpose of a digital twin designed to mirror and evaluate rather than compete with production workflows.

## Decision

The **Original** process instance is authoritative for real-world execution state and token progression. It runs the model author's deployed BPMN definition with human-facing tasks. Synchronization events flow from Original → Twin.

**Data Channel Clarification:** While execution control flows strictly Original → Twin, a bounded data channel exists: a Twin-evolved agent may populate a specific, named, model-declared Original process variable when that activity's `metaml:agentOutputs` extension declaration explicitly maps it (`AgentOutputDeclarations`). Token progression, task assignment, and gateway structure remain strictly governed by the Original process engine; only explicitly mapped variable values are propagated.

## Alternatives Investigated

- **Unbounded bidirectional synchronization:** Allowing arbitrary Twin automation outcomes to influence Original process routing was rejected because it introduces non-deterministic race conditions between parallel executions.
- **Eliminating the write-back channel entirely:** Rejected because legitimate compliance and risk-escalation workflows require agent evaluations to provide structured input into downstream decision gateways.
- **Unbounded variable write-back:** Rejected because undeclared variable propagation would allow unexpected side effects across process definitions without explicit model author consent.
- **Model-declared variable mapping (Chosen):** Gating write-back exclusively through explicit `metaml:agentOutputs` declarations guarantees predictability, auditability, and model-level opt-in.
- **Co-authority with conflict resolution:** Rejected due to excessive operational complexity and lack of deterministic convergence guarantees across distributed engines.

## Technical Validation

`AgentExecutionDelegate` evaluates `riskFlagDeclared = RISK_FLAG_VARIABLE.equals(declared.get(AgentVariables.RISK_FLAGGED_OUTPUT))` before routing an output named `riskFlagged` into execution context. Output variables lacking explicit declarations receive namespaced attributes (`agentOutput_<activityId>_<variableName>`), preventing collision with active process variables.

## Trade-offs

- **Gained:** Strict authority invariants; isolation between execution branches; explicit model-level governance over data propagation.
- **Given up:** Dynamic undeclared runtime overrides between twin and original instances.

## Consequences

- Original process decision routing can adapt based on explicitly declared agent output variables while execution flow remains deterministic.
- Model authors retain full governance over which activities permit agent outputs to influence execution data.

## Future Reconsideration

Would be revisited only if requirements emerge for multi-master consensus or decentralized bidirectional branch synchronization, which would require dedicated conflict resolution protocols.
