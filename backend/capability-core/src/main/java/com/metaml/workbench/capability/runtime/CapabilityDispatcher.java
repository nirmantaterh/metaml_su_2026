package com.metaml.workbench.capability.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.camunda.bpm.engine.RepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.automation.ComponentExecutor;
import com.metaml.workbench.bpmn.BpmnCapabilityContractReader;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.model.AgentVariables;

// Runs the provider bound to an activity, inside whichever application owns the engine - in practice
// a generated standalone Target Platform.
//
// It composes primitives that already existed, plus one new one added for P7 Step 5:
//   provider identity   <- the evolvedAgent_/evolvedAgentType_ process variables, tried first, always -
//                          the repository's original, instance-scoped binding representation
//                       <- falling back, only when the instance carries neither, to a
//                          CapabilityBindingCache entry: a model-level, Workbench-authoritative
//                          CapabilityBinding, addressed by activity id rather than process instance,
//                          for exactly the case an instance-scoped variable cannot answer - a fresh
//                          Target Platform process instance the Workbench never touched directly.
//   -> ComponentExecutor.handles(identity)    exact, case-insensitive, no fuzzy matching
//   -> ComponentExecutor.execute(context, ..) the provider, which sees only CapabilityExecutionContext
//   -> CapabilityOutputPropagator.publish(..) validate the whole declared output set, then publish
//
// WHAT THIS DELIBERATELY DOES NOT DO
//
// No governance, no approval, no catalog, no gap lifecycle, no recommendation. Those stay with the
// Workbench, which remains the sole authority for deciding that a provider MAY be bound. This class
// only executes a binding that already exists, which is what lets a generated Target Platform run
// ordinary work with the Workbench switched off - a CapabilityBindingCache entry is itself only ever
// populated from something the Workbench already approved (see CapabilityBindingCache), never decided
// here.
//
// It also holds no policy about a MISSING binding. dispatch() returns an empty Optional and lets the
// caller decide, because "nothing is bound" means different things at different activities: harmless
// for an activity that requires no capability, and a reportable capability gap for one whose outputs
// downstream control flow depends on. Deciding that here would put policy in the wrong place, and
// inventing an output to paper over it is precisely what must never happen.
//
// RELATIONSHIP TO TwinAutomationDelegate
//
// The Workbench composes the same three primitives in its own delegate, and that composition is left
// untouched: it additionally resolves a per-project ProjectAutomationService, notifies the gap
// lifecycle on success, writes its own summary bookkeeping, and walks predecessors to find the
// activity a Twin automation task stands in for. None of that applies to a Target Platform, where the
// delegate or worker IS the activity. The sequence is therefore expressed in two places, but the
// primitives - and so the semantics of resolution, contract enforcement and failure - are shared,
// which is what keeps the two from drifting. Collapsing the Workbench delegate onto this class is
// possible later; it is not required for a Target Platform to execute and is not attempted here.
public final class CapabilityDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(CapabilityDispatcher.class);

    private final List<ComponentExecutor> executors;
    private final CapabilityOutputContractSource contractSource;
    private final RepositoryService repositoryService;
    private final CapabilityBindingCache bindingCache;

    // contractSource may be null: an application with no catalog reachable has no declared contract
    // to enforce, which publish() already treats as "enforce nothing" rather than "allow anything"
    // (an undeclared output still cannot become a gateway variable unless the model reads it).
    public CapabilityDispatcher(List<ComponentExecutor> executors,
            CapabilityOutputContractSource contractSource, RepositoryService repositoryService) {
        this(executors, contractSource, repositoryService, null);
    }

    // P7 Step 5: bindingCache may be null, exactly like contractSource - a deployment with no
    // Workbench-authoritative binding source reachable falls back to process-variable-only
    // resolution, unchanged from before this constructor existed. When supplied, it is consulted
    // ONLY as a fallback after the process instance's own evolvedAgent_*/evolvedAgentType_*
    // variables, never instead of them: an instance-specific binding (however it got there) is
    // always the more specific, more current answer.
    public CapabilityDispatcher(List<ComponentExecutor> executors,
            CapabilityOutputContractSource contractSource, RepositoryService repositoryService,
            CapabilityBindingCache bindingCache) {
        this.executors = executors == null ? List.of() : List.copyOf(executors);
        this.contractSource = contractSource;
        this.repositoryService = Objects.requireNonNull(repositoryService,
                "repositoryService must not be null - it is how the dispatcher reads the deployed "
                        + "model to learn which outputs control flow actually requires");
        this.bindingCache = bindingCache;
    }

    /**
     * Executes whatever provider is bound to this activity and publishes its outputs.
     *
     * @return the provider's result, or empty when no provider is bound to this activity at all.
     * @throws IllegalStateException when a provider IS bound but no executor on the classpath
     *         handles its identity - fail closed, never a substitute provider.
     * @throws CapabilityOutputContractViolationException when the provider ran but its output set
     *         did not satisfy its declared contract. Nothing is published in that case.
     */
    public Optional<AutomationResult> dispatch(CapabilityExecutionContext context, String activityId,
            Object loopCounter) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(activityId, "activityId must not be null");

        String boundName = variableAsIdentity(context, AgentVariables.evolvedAgent(activityId, loopCounter));
        String boundType = variableAsIdentity(context, AgentVariables.evolvedAgentType(activityId, loopCounter));

        // P7 Step 5: the process instance's own variables are always the more specific, more current
        // answer and are tried first, unchanged from before this fallback existed. Only when this
        // instance carries neither is the model-level, Workbench-authoritative CapabilityBinding cache
        // consulted - never the reverse, so an instance that was itself explicitly (re)bound can never
        // be overridden by a stale cached model-level decision.
        CapabilityBinding cachedBinding = null;
        if (boundName == null && boundType == null) {
            cachedBinding = bindingCache == null ? null : bindingCache.get(activityId).orElse(null);
            if (cachedBinding == null) {
                logger.debug("No provider bound to activity {} on instance {} - nothing to dispatch",
                        activityId, context.getProcessInstanceId());
                return Optional.empty();
            }
        }

        String identity;
        ComponentExecutor executor;
        CapabilityProvider provider;
        if (cachedBinding != null) {
            // The cache already resolved a single, unambiguous identity - the bind-time providerId -
            // so instance/family fallback (below) does not apply to it.
            identity = cachedBinding.providerId();
            executor = findExecutor(identity);
            if (executor == null) {
                identity = cachedBinding.providerType();
                executor = findExecutor(identity);
            }
            // The binding's OWN bind-time contract snapshot, never re-resolved from a live catalog -
            // this is the whole point of caching a CapabilityBinding rather than a bare identity.
            provider = new CapabilityProvider(cachedBinding.providerId(), cachedBinding.providerType(),
                    cachedBinding.version(), cachedBinding.contract(), "Workbench-authoritative binding "
                            + "(bound " + cachedBinding.boundAt() + ")", true, null);
        } else {
            // Instance identity first, then family identity - the same order, and for the same reason,
            // as DefaultProjectAutomationService: multi-instance siblings each get a distinct provider
            // name from the catalog while sharing one type that maps to one executor.
            identity = boundName;
            executor = boundName == null ? null : findExecutor(boundName);
            if (executor == null && boundType != null) {
                identity = boundType;
                executor = findExecutor(boundType);
            }
            provider = executor == null ? null : declaredContractFor(identity);
        }
        if (executor == null) {
            String attempted = identity != null ? identity
                    : (boundName != null ? boundName : boundType);
            logger.error("No ComponentExecutor found for bound provider '{}' on activity {} (instance {})",
                    attempted, activityId, context.getProcessInstanceId());
            throw new IllegalStateException("No ComponentExecutor found for bound provider '" + attempted
                    + "' on activity " + activityId);
        }

        logger.info("CAPABILITY DISPATCH: activity={} processInstanceId={} providerIdentity={} "
                        + "executor={} contract={}", activityId, context.getProcessInstanceId(), identity,
                executor.getClass().getSimpleName(), provider == null ? "none" : provider.providerId());

        AutomationResult result = executor.execute(context, activityId, identity);
        // A sequence belongs to the provider this instance actually resolved, not to the executor's
        // broad family.  That keeps a rebind/replacement honest: the configured response follows
        // the same concrete identity that dispatch and provider-use evidence report.
        result = CapabilityResponseSequences.apply(context, identity, result);

        // The single boundary between what the provider computed and what control flow may see.
        // publish() throws before this line is reached if the output set violated the contract, so
        // reaching here always means the dispatch legitimately succeeded - including a legitimate
        // action-only provider whose contract declares zero outputs (e.g. SendNotification): "zero
        // outputs" must still be observable as a completed dispatch, not silence indistinguishable
        // from a dispatch that never ran. logged with the provider's raw declared outputs (empty map
        // included) rather than only the subset republished as gateway variables, so an operator can
        // tell "this ran and produced nothing" from "this ran and produced X" at a glance.
        CapabilityOutputPropagator.publish(context, provider, result.outputs(),
                requiredOutputNames(context, activityId), activityId, loopCounter);
        logger.info("CAPABILITY COMPLETE: activity={} processInstanceId={} providerIdentity={} outputs={}",
                activityId, context.getProcessInstanceId(), identity, result.outputs());
        return Optional.of(result);
    }

    // Which of this activity's outputs may be published under their plain process-variable names:
    // exactly the ones the deployed model's own gateway conditions read. Derived from the model at
    // runtime, so an unfamiliar process with unfamiliar variables needs no change here.
    //
    // Unlike the Workbench's delegate this does not walk predecessors: there, a Twin automation task
    // stands in for a separate receive task and the gateway variables are registered against that
    // other id. Here the delegate or worker IS the activity.
    private Set<String> requiredOutputNames(CapabilityExecutionContext context, String activityId) {
        try {
            return BpmnCapabilityContractReader
                    .gatewayConditionVariablesByActivityId(
                            repositoryService.getBpmnModelInstance(context.getProcessDefinitionId()))
                    .getOrDefault(activityId, Set.of());
        } catch (RuntimeException e) {
            // Losing this must not invent process state. Publication of the per-visit record still
            // happens; a gateway variable simply is not written, and the gateway then fails loudly on
            // its own rather than evaluating something fabricated here.
            logger.error("Could not read the deployed model for process definition {} to determine which "
                            + "outputs activity {} is required to publish - no gateway variable will be "
                            + "written from this dispatch", context.getProcessDefinitionId(), activityId, e);
            return Set.of();
        }
    }

    private CapabilityProvider declaredContractFor(String identity) {
        if (contractSource == null) {
            return null;
        }
        try {
            return contractSource.providerFor(identity).orElse(null);
        } catch (RuntimeException e) {
            // A contract source that cannot answer is not permission to skip enforcement, but it is
            // also not this class's call to make - surfaced, and left to publish()'s no-contract path.
            logger.error("Capability contract source failed for provider identity '{}'", identity, e);
            return null;
        }
    }

    private static String variableAsIdentity(CapabilityExecutionContext context, String variableName) {
        Object value = context.getVariable(variableName);
        if (value == null) {
            return null;
        }
        String identity = value.toString().trim();
        return identity.isEmpty() ? null : identity;
    }

    // Ambiguity is an error, not a preference: two executors claiming one identity means the
    // deployment is wrong, and silently picking one would make which provider ran unpredictable.
    private ComponentExecutor findExecutor(String identity) {
        List<ComponentExecutor> matches = new ArrayList<>();
        for (ComponentExecutor executor : executors) {
            if (executor.handles(identity)) {
                matches.add(executor);
            }
        }
        if (matches.size() > 1) {
            List<String> names = matches.stream().map(e -> e.getClass().getSimpleName()).toList();
            throw new IllegalStateException("Ambiguous executor resolution: multiple executors " + names
                    + " claim provider identity '" + identity + "'");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }
}
