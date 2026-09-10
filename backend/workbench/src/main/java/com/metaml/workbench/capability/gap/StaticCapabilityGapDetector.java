package com.metaml.workbench.capability.gap;

import com.metaml.workbench.bpmn.BpmnCapabilityContractReader;
import com.metaml.workbench.bpmn.BpmnCapabilityContractReader.ActivityCapabilityDerivation;
import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.CapabilitySatisfaction;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Pure, deterministic static BPMN gap detection (MetaML Scope 6, Phase 5, section 8), built
// entirely on the existing Phase 1 BpmnCapabilityContractReader - no second BPMN capability parser.
//
// REQUIRED_OUTPUTS(A), DECLARED_OUTPUTS(A), and UNSATISFIED(A) are exactly
// BpmnCapabilityContractReader's own ActivityCapabilityDerivation fields; this class does not
// recompute them. What it adds is turning "this activity has an unsatisfied output" into "does a
// satisfying provider already exist" (section 8's closing rule: "If a satisfying provider exists,
// do not open a gap") and, only when none does, building the CapabilityGap's own requiredContract -
// the contract a NEW provider would need to satisfy to close the gap.
//
// That requiredContract is deliberately not ActivityCapabilityDerivation.contract(): the reader's
// contract describes what the activity already declares (producedOutputs = DECLARED_OUTPUTS), which
// is backwards for "what must a provider produce to close this gap" - a provider closing the gap
// must produce the outputs that are currently UNSATISFIED, not duplicate ones already declared.
// requiredInputs carries over unchanged, since those are unaffected by the output gap.
//
// Never modifies the model, never touches process instance state, never auto-binds a provider, and
// never advances execution (section 8).
public final class StaticCapabilityGapDetector {

    private StaticCapabilityGapDetector() {
    }

    // One gap candidate per activity with at least one unsatisfied output and no existing provider
    // able to satisfy it. processDefinitionId here is the workbench's own stable ProcessModel id
    // (see the P5 report's "Deterministic Identity" section for why this, and not Camunda's
    // versioned deployed process-definition id, is used) - the same static value across redeploys of
    // the same logical process, so a static gap's identity never fragments on a republish.
    public static List<CapabilityGap> detect(BpmnModelInstance model, String processDefinitionId,
            Collection<CapabilityProvider> candidateProviders, java.time.Instant now) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(processDefinitionId, "processDefinitionId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Collection<CapabilityProvider> providers = candidateProviders == null ? List.of() : candidateProviders;

        List<CapabilityGap> gaps = new ArrayList<>();
        Map<String, ActivityCapabilityDerivation> derivations = BpmnCapabilityContractReader.deriveAll(model);
        for (ActivityCapabilityDerivation derivation : derivations.values()) {
            if (derivation.unsatisfiedOutputs().isEmpty()) {
                continue;
            }

            CapabilityContract requiredContract = requiredContractFor(derivation);
            Map<String, IoType> availableInputs = availableInputsFor(derivation);

            CapabilitySatisfaction.Resolution resolution =
                    CapabilitySatisfaction.resolve(requiredContract, providers, availableInputs);
            if (resolution.status() == CapabilitySatisfaction.Status.RECOMMENDED) {
                // A satisfying provider already exists - the existing evolve/bind/automate path can
                // reach this activity without any capability gap; opening one would be a false
                // positive (section 8).
                continue;
            }

            List<String> candidateIds =
                    CapabilitySatisfaction.satisfyingProviderIds(requiredContract, providers, availableInputs);
            String gapId = CapabilityGapIdentity.gapId(processDefinitionId, derivation.activityId(), null,
                    derivation.unsatisfiedOutputs());
            gaps.add(new CapabilityGap(gapId, processDefinitionId, derivation.activityId(), null, null, null,
                    requiredContract, availableInputs, candidateIds, null, GapOrigin.STATIC_MODEL, GapStatus.OPEN,
                    now, now, null, null));
        }
        return gaps;
    }

    // The contract a new provider must satisfy to close this gap: the activity's existing required
    // inputs, plus one required output declaration per currently-unsatisfied output name. Type is
    // UNKNOWN because a BPMN gateway condition or metaml:agentOutputs reference carries a variable
    // name only, never a declared type (see BpmnCapabilityContractReader) - UNKNOWN is this
    // codebase's own "not declared, never a fabricated default" convention (IoType javadoc), not a
    // stand-in business value.
    static CapabilityContract requiredContractFor(ActivityCapabilityDerivation derivation) {
        Set<IoDeclaration> missingOutputs = new LinkedHashSet<>();
        for (String name : derivation.unsatisfiedOutputs()) {
            missingOutputs.add(new IoDeclaration(name, IoType.UNKNOWN, true));
        }
        ExecutionMode mode = derivation.contract().executionMode();
        return new CapabilityContract(null, derivation.contract().requiredInputs(), missingOutputs, mode,
                Map.of(), Set.of());
    }

    // For static detection, "available" means the activity's own declared required inputs - what
    // the activity's own contract states it will have available whenever it actually executes, not
    // a business value observed at any point in time (section 7: names and types only).
    static Map<String, IoType> availableInputsFor(ActivityCapabilityDerivation derivation) {
        Map<String, IoType> inputs = new java.util.LinkedHashMap<>();
        for (IoDeclaration input : derivation.contract().requiredInputs()) {
            inputs.put(input.name(), input.type());
        }
        return inputs;
    }
}
