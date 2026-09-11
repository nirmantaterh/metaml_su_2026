package com.metaml.workbench.capability.runtime;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.CapabilitySatisfaction;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;
import com.metaml.workbench.model.AgentVariables;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// The single authoritative runtime boundary between what a provider computed and what BPMN control
// flow is allowed to see (MetaML Scope 6, Phase 4).
//
// Ownership, and nothing either side of it:
//   provider  -> business computation, business decisions, actual output values
//   this      -> output-contract validation, then atomic publication into Camunda process state
//   Camunda   -> process variables after publication, gateways, sequence flows, token movement
//
// This class evaluates no gateway, makes no business decision, and invents no value. It is also not
// a Map-copying utility: publish() writes nothing at all unless the *complete* output set satisfied
// the declared CapabilityContract.producedOutputs() of the provider first.
//
// Two things it deliberately reuses rather than reinvents:
// - Type compatibility is Phase 0 CapabilitySatisfaction.typeCompatible, unmodified. A runtime
//   value is classified into an IoType and compared with the declared one under exactly the rule
//   that already governs provider/requirement matching, so UNKNOWN keeps its Phase 0 meaning
//   ("the source did not declare a type") and widens rather than narrows. UNKNOWN still is not
//   "any runtime value is accepted": an UNKNOWN-typed output must still be declared, present, and
//   non-null.
// - Variable naming and per-visit scoping stay in AgentVariables, unmodified, so multi-instance and
//   parallel siblings stay isolated exactly as they already are (the loop counter is part of the
//   per-visit variable name) and no new global output store is introduced.
public final class CapabilityOutputPropagator {

    private static final Logger logger = LoggerFactory.getLogger(CapabilityOutputPropagator.class);

    private CapabilityOutputPropagator() {
    }

    // Pure, side-effect-free reconciliation of one execution result against one provider contract.
    // Returns every violation found rather than the first, so an operator sees the whole picture on
    // a single incident. An empty list means the result satisfied the contract.
    //
    // Success requires exact name-set equality - every declared produced output present, and no
    // output the contract did not declare - plus a compatible non-null value for each. Ordering is
    // deterministic: declared outputs in contract order first, then undeclared actuals in result
    // order.
    public static List<OutputContractViolation> validate(CapabilityProvider provider,
            Map<String, Object> actualOutputs, String activityId, String activityInstanceId) {
        Objects.requireNonNull(provider, "provider must not be null");
        Map<String, Object> actual = actualOutputs == null ? Map.of() : actualOutputs;

        List<OutputContractViolation> violations = new ArrayList<>();
        Set<IoDeclaration> declared = provider.contract().producedOutputs();

        for (IoDeclaration declaration : declared) {
            String name = declaration.name();
            // containsKey, not get() != null: a declared output that is absent and one that is
            // present-but-null are different contract failures and must not collapse into one.
            if (!actual.containsKey(name)) {
                violations.add(violation(OutputContractViolationKind.MISSING_DECLARED_OUTPUT, provider,
                        activityId, activityInstanceId, name, declaration.type(), null));
                continue;
            }
            Object value = actual.get(name);
            if (value == null) {
                violations.add(violation(OutputContractViolationKind.INVALID_NULL_OUTPUT, provider,
                        activityId, activityInstanceId, name, declaration.type(), null));
                continue;
            }
            if (!CapabilitySatisfaction.typeCompatible(declaration.type(), runtimeTypeOf(value))) {
                violations.add(violation(OutputContractViolationKind.TYPE_MISMATCH, provider,
                        activityId, activityInstanceId, name, declaration.type(),
                        value.getClass().getSimpleName()));
            }
        }

        for (Map.Entry<String, Object> output : actual.entrySet()) {
            if (isDeclared(declared, output.getKey())) {
                continue;
            }
            Object value = output.getValue();
            violations.add(violation(OutputContractViolationKind.UNDECLARED_OUTPUT, provider,
                    activityId, activityInstanceId, output.getKey(), null,
                    value == null ? null : value.getClass().getSimpleName()));
        }

        return List.copyOf(violations);
    }

    // Validates the complete output set, then - only if nothing violated the contract - publishes
    // every output into Camunda process state in one pass. Throws before the first write when any
    // output is invalid, so partial publication is impossible by construction rather than by
    // relying on the surrounding command rolling back (which does also hold - see ADR-008).
    //
    // provider may be null: that means no declared capability contract was resolvable for this
    // execution, so there is no contract for this boundary to enforce. That case is explicitly
    // Phase 5 territory (a required capability with no legitimate satisfying provider is a
    // CapabilityGap), not this one, and the pre-existing publication behaviour is preserved
    // unchanged for it.
    //
    // Two kinds of write happen, both already established in this repository:
    // - the per-visit bookkeeping record twinAutomationOutput_<name>_<activity>[_<loop>] for every
    //   output, which is what makes parallel and multi-instance siblings independently readable;
    // - the bare <name> process variable, but only for the names processVisibleOutputNames says a
    //   downstream BPMN gateway actually reads. Which names those are stays a BPMN question
    //   answered by the caller from the model itself; this class never decides it and never
    //   substitutes a Java mapping for a BPMN one.
    //
    // Returns the bare process variables actually published, for logging.
    // Overload for a caller that already holds a DelegateExecution, which is every Workbench call
    // site. Note the pair is only unambiguous because both call forms pass a statically-typed first
    // argument; a caller passing an untyped null would have to say which one it means.
    public static Map<String, Object> publish(DelegateExecution execution, CapabilityProvider provider,
            Map<String, Object> actualOutputs, Set<String> processVisibleOutputNames,
            String activityId, Object loopCounter) {
        Objects.requireNonNull(execution, "execution must not be null");
        return publish(new DelegateExecutionContext(execution), provider, actualOutputs,
                processVisibleOutputNames, activityId, loopCounter);
    }

    // The implementation. Takes a CapabilityExecutionContext rather than a DelegateExecution so the
    // boundary is identical whether the provider ran behind a delegateExpression service task or
    // behind an external-task worker - a generated Target Platform produces both, decided by the
    // authored model. Nothing about contract validation or publication differs between them: an
    // external task's writes are buffered until the task completes, so a rejected output set is
    // never applied there either.
    public static Map<String, Object> publish(CapabilityExecutionContext context, CapabilityProvider provider,
            Map<String, Object> actualOutputs, Set<String> processVisibleOutputNames,
            String activityId, Object loopCounter) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(activityId, "activityId must not be null");
        Map<String, Object> actual = actualOutputs == null ? Map.of() : actualOutputs;
        Set<String> processVisible = processVisibleOutputNames == null ? Set.of() : processVisibleOutputNames;

        if (provider != null) {
            List<OutputContractViolation> violations = validate(provider, actual, activityId,
                    context.getActivityInstanceId());
            if (!violations.isEmpty()) {
                // Nothing has been written at this point and nothing will be: this is the whole
                // point of validating the complete set before publishing any of it.
                CapabilityOutputContractViolationException failure =
                        new CapabilityOutputContractViolationException(violations);
                logger.error("CAPABILITY_OUTPUT_CONTRACT_VIOLATION: no provider output published for "
                        + "activity {} on instance {}: {}", activityId, context.getProcessInstanceId(),
                        failure.getMessage());
                throw failure;
            }
        }

        for (Map.Entry<String, Object> output : actual.entrySet()) {
            context.setVariable(
                    AgentVariables.twinAutomationOutput(output.getKey(), activityId, loopCounter),
                    output.getValue());
        }

        Map<String, Object> published = new LinkedHashMap<>();
        for (String name : processVisible) {
            if (!actual.containsKey(name)) {
                continue;
            }
            Object value = actual.get(name);
            if (value == null) {
                // Unreachable once a contract governs this execution (a null would already have
                // failed validation). Retained for the no-contract case, where it is the existing
                // behaviour: a null was never written as a gateway variable.
                continue;
            }
            context.setVariable(name, value);
            published.put(name, value);
        }
        if (!published.isEmpty()) {
            logger.info("PRODUCTION_STATE: executor output propagated as gateway variables {} "
                    + "on twin {} for activity {}", published, context.getProcessInstanceId(), activityId);
        }
        return Map.copyOf(published);
    }

    // Classifies a non-null runtime value into the Phase 0 IoType vocabulary. Deliberately no
    // coercion in either direction: "true" is a STRING and stays one, "42" is a STRING and stays
    // one. STRUCT is the structured/object representation this repository already uses - it is what
    // BpmnCapabilityContractReader maps a BPMN structureRef of Object to, sitting alongside the
    // three scalar mappings - so anything that is not one of the three scalars is a structured
    // value.
    static IoType runtimeTypeOf(Object value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value instanceof Boolean) {
            return IoType.BOOLEAN;
        }
        if (value instanceof Number) {
            return IoType.NUMBER;
        }
        if (value instanceof String) {
            return IoType.STRING;
        }
        return IoType.STRUCT;
    }

    private static boolean isDeclared(Set<IoDeclaration> declared, String name) {
        for (IoDeclaration declaration : declared) {
            if (declaration.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static OutputContractViolation violation(OutputContractViolationKind kind,
            CapabilityProvider provider, String activityId, String activityInstanceId,
            String outputName, IoType declaredType, String actualType) {
        return new OutputContractViolation(kind, provider.providerId(), provider.providerType(),
                provider.version(), activityId, activityInstanceId, outputName, declaredType, actualType);
    }
}
