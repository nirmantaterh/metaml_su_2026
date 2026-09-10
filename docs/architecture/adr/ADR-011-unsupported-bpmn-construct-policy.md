# ADR-011: Unsupported BPMN Constructs Fail Twin Generation Explicitly

**Status:** Accepted (Version 1.0)

## Context

`TwinModelGenerator` supports a defined subset of BPMN constructs (Architecture Specification, Section 5). When a source process definition contains elements outside this supported set, the generator must have a consistent failure policy: either omit the element with a warning or fail generation explicitly.

## Decision

Any BPMN construct that the generator cannot translate into a synchronized twin equivalent triggers an immediate **generation failure** via an `IllegalArgumentException` identifying the process ID, the element ID, and the unsupported BPMN type.

*Exception:* Boundary Events are omitted by deliberate design (Architecture Specification, Section 5): attaching a local Boundary Timer to a twin activity that executes synchronously inside a single command would trigger on the twin's local clock, leading to premature divergence from the original process.

## Alternatives Investigated

- **Silent omission or warning-only logging:** Rejected because silent omission risks deploying incomplete twin graphs where execution branches are missing without notifying the model author. An explicit, fail-fast exception prevents hidden runtime discrepancies.
- **Best-effort semantic approximation:** Rejected because substituting unsupported flow nodes with arbitrary pass-through tasks risks misrepresenting business logic.
- **Failing on Boundary Events:** Rejected because boundary event omission is a deliberate synchronization design choice rather than an unhandled construct.

## Technical Validation

The invocation chain `launchProcess → deployTwinDefinition → generate()` ensures that validation exceptions are raised before engine deployment or persistence occurs. A rejected model leaves no partial deployments or orphaned records. Unit and integration tests (`TwinExecutionWalkthroughTest`) verify that unsupported constructs fail fast with descriptive diagnostic messages.

## Trade-offs

- **Gained:** Immediate, actionable feedback on model compatibility; prevention of silently incomplete digital twin deployments.
- **Given up:** Inability to run models containing unsupported constructs in untested flow branches.

## Consequences

- Process model authors receive immediate notification when unsupported elements are present.
- Adding support for new BPMN constructs in `isSupported()` and `append()` progressively expands the supported modeling envelope.

## Future Reconsideration

Constructs currently outside the supported set (such as Event-Based Gateways and Call Activities) may be supported in future releases as transformation rules are developed.
