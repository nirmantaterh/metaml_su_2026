package com.metaml.workbench.capability.gap;

import com.metaml.workbench.bpmn.BpmnCapabilityContractReader;
import com.metaml.workbench.bpmn.BpmnCapabilityContractReader.ActivityCapabilityDerivation;
import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.CapabilitySatisfaction;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;
import com.metaml.workbench.governance.Approval;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.ApprovalStatus;
import com.metaml.workbench.governance.GovernanceRequest;
import com.metaml.workbench.governance.PolicyDecision;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.governance.PolicyEffect;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.service.WorkbenchService;

import jakarta.annotation.PostConstruct;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// The one generic CapabilityGapService (MetaML Scope 6, Phase 5, section 11). Enforces the locked
// lifecycle (section 3); every transition below is checked against the gap's current status before
// it is applied, and an illegal transition throws IllegalStateException - the same convention
// ApprovalService already uses for its own illegal transitions.
//
// This is not a second evolution engine (section 4): binding and execution are reached exclusively
// through the existing public WorkbenchService.evolveActivity(...), which is itself the existing
// convergence point for quota (GovernanceService, inside evolveActivity), tenant policy
// (PolicyDecisionEngine + ApprovalService, inside evolveActivity), and catalog validation (the node
// manager agent-availability check, inside evolveActivity). The only governance call this class
// makes directly is its own, separate policy gate on the RECOMMENDED -> APPROVED edge (section 15),
// reusing the exact same PolicyDecisionEngine/ApprovalService/GovernanceRequest the rest of the
// platform already uses - not a parallel governance system.
@Component
public class CapabilityGapService {

    private static final Logger logger = LoggerFactory.getLogger(CapabilityGapService.class);

    // Action name for the gap's own governance gate, parallel to WorkbenchServiceImpl's
    // EVOLVE_TWIN_ACTION constant for ordinary (non-gap) evolutions.
    private static final String RESOLVE_CAPABILITY_GAP_ACTION = "RESOLVE_CAPABILITY_GAP";

    private final CapabilityGapStore store;
    private final WorkbenchService workbenchService;
    private final ApprovalService approvalService;
    private final PolicyDecisionEngine policyDecisionEngine;
    private final CapabilityGapRecommender recommender;

    private final Map<String, CapabilityGap> gaps = new ConcurrentHashMap<>();
    // (processInstanceId, activityId, loopCounterKey) -> gapId, for the execution-success hook
    // (section 17/12). Rebuilt from persisted BOUND gaps on restart - never itself persisted, and
    // never the sole source of truth for anything beyond "which gap is this execution about".
    private final Map<String, String> boundIndex = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public CapabilityGapService(CapabilityGapStore store, WorkbenchService workbenchService,
            ApprovalService approvalService, PolicyDecisionEngine policyDecisionEngine,
            CapabilityGapRecommender recommender) {
        this.store = store;
        this.workbenchService = workbenchService;
        this.approvalService = approvalService;
        this.policyDecisionEngine = policyDecisionEngine;
        this.recommender = recommender;
    }

    @PostConstruct
    void restore() {
        for (CapabilityGap gap : store.load()) {
            gaps.put(gap.gapId(), gap);
            if (gap.status() == GapStatus.BOUND) {
                indexBound(gap);
            }
        }
        if (!gaps.isEmpty()) {
            logger.info("Restored {} capability gap(s); lifecycle states preserved, nothing auto-resolved",
                    gaps.size());
        }
    }

    // ---- Detection / idempotent reporting (sections 8, 11, 12) ----

    // Static detection over one BPMN model (section 8), reported through the same idempotent path
    // runtime origins use.
    public List<CapabilityGap> detectStatic(BpmnModelInstance model, String processDefinitionId) {
        List<CapabilityProvider> candidates = catalog();
        List<CapabilityGap> detected =
                StaticCapabilityGapDetector.detect(model, processDefinitionId, candidates, Instant.now());
        List<CapabilityGap> reported = new ArrayList<>();
        for (CapabilityGap candidate : detected) {
            reported.add(report(candidate));
        }
        return reported;
    }

    // Runtime detection seam (sections 8, 12): called from the existing runtime provider-resolution
    // failure path (WorkbenchServiceImpl.executeAfterGovernance, the node-manager
    // agent-availability check) at the exact point the platform already knows one specific
    // requested provider type is unavailable. Before concluding that is a genuine capability gap,
    // this re-derives the activity's own required contract (same BpmnCapabilityContractReader used
    // by static detection - no second parser) and checks it against the FULL current catalog (same
    // CapabilitySatisfaction used everywhere else in P5) - so a gap is opened only when NO provider
    // in the catalog can satisfy the activity, never merely because the one type a caller happened
    // to request was unavailable (section 8's "if a satisfying provider exists, do not open a gap").
    // Returns empty when the activity declares no output requirement at all, or when some provider
    // does satisfy it (no gap warranted) - never fabricates a provider or an output either way.
    public Optional<CapabilityGap> reportRuntimeGapIfUnsatisfied(BpmnModelInstance model, String activityId,
            String processDefinitionId, String activityInstanceId, Integer loopCounter, String processInstanceId,
            String tenantId, GapOrigin origin) {
        Object element = model.getModelElementById(activityId);
        if (!(element instanceof Activity activity)) {
            return Optional.empty();
        }
        ActivityCapabilityDerivation derivation = BpmnCapabilityContractReader.derive(model, activity);
        CapabilityContract requiredContract = StaticCapabilityGapDetector.requiredContractFor(derivation);
        if (requiredContract.producedOutputs().isEmpty()) {
            // Nothing this activity needs is currently unsatisfied - no gap to report.
            return Optional.empty();
        }
        Map<String, IoType> availableInputs = StaticCapabilityGapDetector.availableInputsFor(derivation);
        List<CapabilityProvider> candidates = catalog();
        CapabilitySatisfaction.Resolution resolution =
                CapabilitySatisfaction.resolve(requiredContract, candidates, availableInputs);
        if (resolution.status() == CapabilitySatisfaction.Status.RECOMMENDED) {
            // A different provider than the one originally requested can satisfy this activity -
            // this is not a capability gap (section 2: no-provider is not the same condition as one
            // specific requested provider being unavailable).
            return Optional.empty();
        }

        List<String> unsatisfiedNames = new ArrayList<>();
        for (IoDeclaration output : requiredContract.producedOutputs()) {
            unsatisfiedNames.add(output.name());
        }
        List<String> candidateIds =
                CapabilitySatisfaction.satisfyingProviderIds(requiredContract, candidates, availableInputs);
        String gapId = CapabilityGapIdentity.gapId(processDefinitionId, activityId, activityInstanceId,
                unsatisfiedNames);
        Instant now = Instant.now();
        CapabilityGap candidate = new CapabilityGap(gapId, processDefinitionId, activityId, activityInstanceId,
                loopCounter, processInstanceId, requiredContract, availableInputs, candidateIds, tenantId, origin,
                GapStatus.OPEN, now, now, null, null);
        return Optional.of(report(candidate));
    }

    // Generic report/re-report entry point for every origin (section 12, 19): the same method
    // STATIC_MODEL detection, runtime detection, and the Target Platform/VS Code HTTP boundary
    // (CapabilityGapController.report -> toCandidate) all funnel through, so there is exactly one
    // place "open or idempotently refresh a gap" is implemented.
    //
    // candidateProviderIds is always recomputed here, against the LIVE catalog, from the candidate's
    // own requiredContract/availableInputs - never trusted as the caller supplied it (MetaML Scope 6,
    // Phase 6 correction). This is what makes report() the single authoritative point for candidate
    // derivation: a caller that cannot see the catalog at all (the HTTP /report boundary - see
    // CapabilityGapController.toCandidate, which has no catalog access and rightly does not try to
    // calculate domain semantics itself) still ends up with a truthful candidate set, and a caller
    // that already computed it correctly (reportRuntimeGapIfUnsatisfied, StaticCapabilityGapDetector)
    // gets the identical answer back, since both already use the same CapabilitySatisfaction rules
    // against the same live catalog. Idempotent: re-reporting the same logical gap (same gapId) never
    // creates a duplicate, and never disturbs a gap that has progressed beyond OPEN (section 11).
    public CapabilityGap report(CapabilityGap candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        synchronized (writeLock) {
            CapabilityGap existing = gaps.get(candidate.gapId());
            if (existing != null && existing.status() != GapStatus.OPEN) {
                // Same logical gap already progressing through (or past) its lifecycle - leave it
                // alone rather than resetting state a human or governance decision already advanced.
                return existing;
            }
            List<String> authoritativeCandidateIds = CapabilitySatisfaction.satisfyingProviderIds(
                    candidate.requiredContract(), catalog(), candidate.availableInputs());
            CapabilityGap base = existing == null ? candidate : existing;
            CapabilityGap toStore = base.withRefreshedObservation(candidate.processInstanceId(),
                    candidate.availableInputs(), authoritativeCandidateIds);
            gaps.put(toStore.gapId(), toStore);
            persist();
            return toStore;
        }
    }

    // ---- Retrieval (section 11) ----

    public CapabilityGap get(String gapId) {
        CapabilityGap gap = gaps.get(gapId);
        if (gap == null) {
            throw new NoSuchElementException("Capability gap not found: " + gapId);
        }
        return gap;
    }

    public List<CapabilityGap> list() {
        return new ArrayList<>(gaps.values());
    }

    public List<CapabilityGap> listForTenant(String tenantId) {
        List<CapabilityGap> result = new ArrayList<>();
        for (CapabilityGap gap : gaps.values()) {
            if (Objects.equals(gap.tenantId(), tenantId)) {
                result.add(gap);
            }
        }
        return result;
    }

    // ---- Recommendation (section 14) ----

    // OPEN -> RECOMMENDED. Computes the recommendation fresh against the live catalog every time,
    // rather than trusting a stored snapshot - the catalog can change between calls, and a stale
    // recommendation is exactly the kind of fabricated business state section 2 forbids.
    public CapabilityGapRecommendation recommend(String gapId) {
        synchronized (writeLock) {
            CapabilityGap gap = get(gapId);
            requireStatus(gap, GapStatus.OPEN);
            CapabilityGapRecommendation recommendation = recommender.recommend(gap, catalog());
            CapabilityGap updated = gap.withStatus(GapStatus.RECOMMENDED, Instant.now(), gap.approvalId(),
                    gap.boundProviderId());
            gaps.put(gapId, updated);
            persist();
            return recommendation;
        }
    }

    // ---- Approval / governance (sections 11, 15) ----

    // RECOMMENDED -> APPROVED (ALLOW or an already-granted tenant approval) or RECOMMENDED ->
    // REJECTED (DENY). On REQUIRE_APPROVAL, creates a pending Approval through the existing
    // ApprovalService/PolicyDecisionEngine - exactly as WorkbenchServiceImpl.enforceTenantPolicy
    // already does for ordinary evolutions - and the gap stays RECOMMENDED with approvalId set until
    // a human resolves that approval (see confirmApproval).
    public AgentDecision approve(String gapId, String tenantId) {
        synchronized (writeLock) {
            CapabilityGap gap = get(gapId);
            requireStatus(gap, GapStatus.RECOMMENDED);

            if (gap.tenantId() == null) {
                // No tenant to evaluate a policy against - mirrors evolveActivity's own
                // "twin.getTenantId() != null" gate.
                markApproved(gap);
                return new AgentDecision(null, true, null, "Approved: no tenant policy to evaluate");
            }

            PolicyDecision decision;
            try {
                decision = policyDecisionEngine.evaluate(new GovernanceRequest(tenantId,
                        RESOLVE_CAPABILITY_GAP_ACTION, governanceAttributes(gap)));
            } catch (RuntimeException e) {
                // Fail closed on policy evaluation error (section 15).
                logger.warn("Tenant policy evaluation failed for capability gap {} (tenant {}): {}", gapId,
                        tenantId, e.getMessage());
                return new AgentDecision(null, false, null,
                        "Tenant policy could not be evaluated: " + e.getMessage());
            }

            if (decision.decision() == PolicyEffect.DENY) {
                gaps.put(gapId, gap.withStatus(GapStatus.REJECTED, Instant.now(), gap.approvalId(), null));
                persist();
                return new AgentDecision(null, false, null, "Denied by tenant policy: " + decision.reason());
            }
            if (decision.decision() == PolicyEffect.REQUIRE_APPROVAL) {
                Approval approval = approvalService.create(tenantId, processInstanceOrGapId(gap), gap.activityId(),
                        gap.activityId(), gap.loopCounter(), recommendedProviderTypeOrNull(gap),
                        RESOLVE_CAPABILITY_GAP_ACTION, decision.policyId(), decision.policyVersionId(),
                        decision.policyVersionNumber(), decision.matchedRuleId(), decision.reason());
                gaps.put(gapId, gap.withStatus(GapStatus.RECOMMENDED, Instant.now(), approval.id(),
                        gap.boundProviderId()));
                persist();
                return new AgentDecision(null, false, null,
                        "Approval required (id " + approval.id() + "): " + decision.reason());
            }

            markApproved(gap);
            return new AgentDecision(null, true, null, "Approved by tenant policy: " + decision.reason());
        }
    }

    // Resumes a gap whose own approval gate returned REQUIRE_APPROVAL, once a human has separately
    // approved that Approval record (through the existing governance approvals UI/API). Does not
    // re-evaluate policy - the Approval record is the single source of truth for that decision.
    public AgentDecision confirmApproval(String gapId, String tenantId) {
        synchronized (writeLock) {
            CapabilityGap gap = get(gapId);
            requireStatus(gap, GapStatus.RECOMMENDED);
            if (gap.approvalId() == null) {
                throw new IllegalStateException("Capability gap " + gapId + " has no pending approval");
            }
            Approval approval = approvalService.get(gap.approvalId(), tenantId);
            if (approval.status() != ApprovalStatus.APPROVED
                    && approval.status() != ApprovalStatus.COMPLETED) {
                return new AgentDecision(null, false, null,
                        "Approval " + gap.approvalId() + " is " + approval.status() + ", not yet APPROVED");
            }
            markApproved(gap);
            return new AgentDecision(null, true, null, "Approved");
        }
    }

    // RECOMMENDED -> REJECTED, for a human explicitly declining the recommendation (section 3).
    public void reject(String gapId, String reason) {
        synchronized (writeLock) {
            CapabilityGap gap = get(gapId);
            requireStatus(gap, GapStatus.RECOMMENDED);
            gaps.put(gapId, gap.withStatus(GapStatus.REJECTED, Instant.now(), gap.approvalId(), null));
            persist();
            logger.info("Capability gap {} rejected: {}", gapId, reason);
        }
    }

    private void markApproved(CapabilityGap gap) {
        gaps.put(gap.gapId(), gap.withStatus(GapStatus.APPROVED, Instant.now(), gap.approvalId(),
                gap.boundProviderId()));
        persist();
    }

    // ---- Binding (sections 11, 16) ----

    // APPROVED -> BOUND. Converges into the existing WorkbenchService.evolveActivity(...) - the
    // existing evolution engine (section 4) - rather than binding anything itself. Uses the existing
    // per-visit evolvedAgent/evolvedAgentType process variables as the binding mechanism (section
    // 16): this repository has no separate CapabilityBinding class to reuse (see the P5 report's
    // preflight finding on this), so the existing variable-based binding IS the binding mechanism
    // being reused here, exactly as it already is for every non-gap evolution.
    public AgentDecision bind(String gapId) {
        synchronized (writeLock) {
            CapabilityGap gap = get(gapId);
            requireStatus(gap, GapStatus.APPROVED);
            if (gap.processInstanceId() == null || gap.processInstanceId().isBlank()) {
                throw new IllegalStateException(
                        "Capability gap " + gapId + " has no running process instance to bind against "
                                + "(origin " + gap.origin() + ")");
            }

            CapabilityGapRecommendation recommendation = recommender.recommend(gap, catalog());
            if (recommendation.status() != CapabilityGapRecommendation.Status.RECOMMENDED) {
                return new AgentDecision(null, false, null,
                        "No unambiguous provider to bind: " + recommendation.rationale());
            }
            String providerType = providerTypeOf(recommendation.recommendedProviderId());
            if (providerType == null) {
                return new AgentDecision(null, false, null,
                        "Recommended provider '" + recommendation.recommendedProviderId()
                                + "' is no longer in the catalog");
            }

            AgentDecision decision = workbenchService.evolveActivity(gap.processInstanceId(), gap.activityId(),
                    gap.activityInstanceId(), providerType);
            if (decision.isApproved()) {
                String boundProviderId = boundIdentityOf(decision, recommendation.recommendedProviderId());
                CapabilityGap bound = gap.withStatus(GapStatus.BOUND, Instant.now(), gap.approvalId(),
                        boundProviderId);
                gaps.put(gapId, bound);
                indexBound(bound);
                persist();
                logger.info("Capability gap {} bound to provider {}", gapId, boundProviderId);
            } else {
                logger.info("Capability gap {} could not be bound yet: {}", gapId, decision.getReason());
            }
            return decision;
        }
    }

    // APPROVED -> BOUND using an externally supplied, already-selected provider id (MetaML Scope 6,
    // Phase 6, sections 4/8: the CapabilityGap -> AI-decision -> human-confirmation path). This is
    // NOT a second evolution engine and NOT a second selection mechanism: confirmedProviderId is
    // never trusted as-is. It must (a) exist in the live catalog and (b) satisfy the gap's required
    // contract per CapabilitySatisfaction - the same deterministic authority every other path in this
    // class already uses - or the bind is refused before evolveActivity is ever called (section 7: no
    // evolution, no binding, gap stays APPROVED). A null/blank id falls back to the existing
    // deterministic bind(gapId) unchanged, so every pre-P6 caller keeps working exactly as before.
    public AgentDecision bind(String gapId, String confirmedProviderId) {
        if (confirmedProviderId == null || confirmedProviderId.isBlank()) {
            return bind(gapId);
        }
        synchronized (writeLock) {
            CapabilityGap gap = get(gapId);
            requireStatus(gap, GapStatus.APPROVED);
            if (gap.processInstanceId() == null || gap.processInstanceId().isBlank()) {
                throw new IllegalStateException(
                        "Capability gap " + gapId + " has no running process instance to bind against "
                                + "(origin " + gap.origin() + ")");
            }

            CapabilityProvider provider = findProvider(confirmedProviderId);
            if (provider == null) {
                return new AgentDecision(null, false, null,
                        "Confirmed provider '" + confirmedProviderId + "' is not in the current catalog");
            }
            if (!CapabilitySatisfaction.satisfies(provider, gap.requiredContract(), gap.availableInputs())) {
                return new AgentDecision(null, false, null,
                        "Confirmed provider '" + confirmedProviderId
                                + "' does not satisfy the gap's required capability contract");
            }

            AgentDecision decision = workbenchService.evolveActivity(gap.processInstanceId(), gap.activityId(),
                    gap.activityInstanceId(), provider.providerType());
            if (decision.isApproved()) {
                String boundProviderId = boundIdentityOf(decision, provider.providerId());
                CapabilityGap bound = gap.withStatus(GapStatus.BOUND, Instant.now(), gap.approvalId(),
                        boundProviderId);
                gaps.put(gapId, bound);
                indexBound(bound);
                persist();
                logger.info("Capability gap {} bound to confirmed provider {}", gapId, boundProviderId);
            } else {
                logger.info("Capability gap {} could not be bound to confirmed provider {}: {}", gapId,
                        confirmedProviderId, decision.getReason());
            }
            return decision;
        }
    }

    // The identity actually bound to the visit, which is what a later execution must match to
    // resolve the gap. evolveActivity resolves the agent NAME itself, through its own node manager
    // availability check, and returns it on the AgentDecision - so that is the identity the
    // evolvedAgent_<visit> process variable really carries and therefore the identity
    // TwinAutomationDelegate will resolve a CapabilityProvider from at execution time. The
    // catalog-side providerId this bind selected is the fallback, used only when the decision
    // carries no name; the two agree whenever a provider type maps to a single agent name, and
    // where they could diverge the one that was genuinely written to process state is the truthful
    // one to record.
    private static String boundIdentityOf(AgentDecision decision, String selectedProviderId) {
        String executedName = decision.getAgentName();
        return (executedName == null || executedName.isBlank()) ? selectedProviderId : executedName;
    }

    private CapabilityProvider findProvider(String providerId) {
        for (CapabilityProvider provider : catalog()) {
            if (provider.providerId().equals(providerId)) {
                return provider;
            }
        }
        return null;
    }

    // ---- Execution-driven resolution (sections 12, 13, 17) ----

    // BOUND -> RESOLVED. The one and only caller of this method is the Phase 4 capability output
    // boundary succeeding for the bound visit (see WorkbenchServiceImpl.notifyCapabilityProviderExecutionSucceeded,
    // invoked from TwinAutomationDelegate right after CapabilityOutputPropagator.publish returns
    // normally). There is no public API to resolve a gap directly: a provider failing to execute, or
    // failing Phase 4 contract validation, simply never calls this method, so the gap stays BOUND
    // (section 17) - it is never marked RESOLVED, and never silently converted into a fabricated
    // success.
    //
    // Reaching this method is necessary but NOT sufficient. The visit coordinates
    // (processInstanceId, activityId, loopCounter) only establish WHICH gap an execution is about;
    // they say nothing about WHAT executed. executedProviderId closes that gap in the reasoning: it
    // is the identity of the provider TwinAutomationDelegate actually resolved and whose declared
    // contract the Phase 4 boundary actually enforced. A null/blank value means no capability
    // contract governed the execution at all (the default/fallback automation path, where publish()
    // validated nothing), and a different value means some other provider ran at this visit.
    // Neither is the bound provider satisfying the gap, so neither may resolve it - otherwise
    // "some automation succeeded here" would silently masquerade as "the capability was satisfied",
    // which is exactly the fabricated success section 17 forbids.
    public void onProviderExecutionSucceeded(String processInstanceId, String activityId, Integer loopCounter,
            String executedProviderId) {
        if (processInstanceId == null || activityId == null) {
            return;
        }
        if (executedProviderId == null || executedProviderId.isBlank()) {
            // No provider contract governed this execution - default/fallback automation. It cannot
            // have satisfied the gap, so the gap stays BOUND and stays indexed, leaving the real
            // bound provider free to resolve it on a later, genuine execution of this visit.
            return;
        }
        synchronized (writeLock) {
            String gapId = boundIndex.get(indexKey(processInstanceId, activityId, loopCounter));
            if (gapId == null) {
                return;
            }
            CapabilityGap gap = gaps.get(gapId);
            if (gap == null || gap.status() != GapStatus.BOUND) {
                return;
            }
            if (!executedProviderId.equals(gap.boundProviderId())) {
                // A provider other than the bound one executed at this visit. The gap is untouched
                // and stays indexed: this is not a resolution, and it is not a failure of this gap
                // either - it is simply not this gap's execution.
                logger.info("Capability gap {} not resolved: provider {} executed at this visit, but the gap is "
                        + "bound to provider {}", gapId, executedProviderId, gap.boundProviderId());
                return;
            }
            gaps.put(gapId, gap.withStatus(GapStatus.RESOLVED, Instant.now(), gap.approvalId(),
                    gap.boundProviderId()));
            boundIndex.remove(indexKey(processInstanceId, activityId, loopCounter));
            persist();
            logger.info("Capability gap {} resolved: provider {} executed successfully and passed the "
                    + "capability output contract", gapId, gap.boundProviderId());
        }
    }

    // ---- Expiration (sections 3, 11, 20) ----

    // OPEN / RECOMMENDED / APPROVED -> EXPIRED, justified by process lifecycle/reconciliation (e.g.
    // the owning process instance ended without the gap ever being resolved). Never auto-invoked by
    // this class on its own - callers (process-lifecycle/reconciliation code) decide when expiration
    // is justified.
    public void expire(String gapId, String reason) {
        synchronized (writeLock) {
            CapabilityGap gap = get(gapId);
            if (!gap.status().canExpire()) {
                throw new IllegalStateException(
                        "Cannot expire capability gap " + gapId + " - it is " + gap.status());
            }
            gaps.put(gapId, gap.withStatus(GapStatus.EXPIRED, Instant.now(), gap.approvalId(), null));
            persist();
            logger.info("Capability gap {} expired: {}", gapId, reason);
        }
    }

    // ---- Helpers ----

    private static void requireStatus(CapabilityGap gap, GapStatus required) {
        if (gap.status() != required) {
            throw new IllegalStateException(
                    "Cannot transition capability gap " + gap.gapId() + " - it is " + gap.status() + ", not "
                            + required);
        }
    }

    private List<CapabilityProvider> catalog() {
        try {
            return workbenchService.listCapabilityProviders();
        } catch (RuntimeException e) {
            // Same convention as CatalogCapabilityOutputContractSource: a transient catalog failure
            // is not a contract violation or a fabricated gap closure, just an empty candidate set
            // for this call.
            logger.warn("Could not resolve the capability provider catalog: {}", e.getMessage());
            return List.of();
        }
    }

    private String providerTypeOf(String providerId) {
        for (CapabilityProvider provider : catalog()) {
            if (provider.providerId().equals(providerId)) {
                return provider.providerType();
            }
        }
        return null;
    }

    private String recommendedProviderTypeOrNull(CapabilityGap gap) {
        CapabilityGapRecommendation recommendation = recommender.recommend(gap, catalog());
        return recommendation.status() == CapabilityGapRecommendation.Status.RECOMMENDED
                ? providerTypeOf(recommendation.recommendedProviderId())
                : null;
    }

    private static String processInstanceOrGapId(CapabilityGap gap) {
        return gap.processInstanceId() != null ? gap.processInstanceId() : gap.gapId();
    }

    // Governance attributes preserved on the gap's own GovernanceRequest (section 15): capabilityId,
    // providerId/Type/Version are filled in once a recommendation exists, gapId/gapOrigin/
    // requiredOutputs/governanceLabels are always available from the gap itself.
    private Map<String, Object> governanceAttributes(CapabilityGap gap) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("gapId", gap.gapId());
        attributes.put("gapOrigin", gap.origin().name());
        attributes.put("capabilityId", gap.requiredContract().capabilityId());
        attributes.put("requiredOutputs", outputNames(gap));
        attributes.put("governanceLabels", gap.requiredContract().governanceLabels());
        CapabilityGapRecommendation recommendation = recommender.recommend(gap, catalog());
        if (recommendation.status() == CapabilityGapRecommendation.Status.RECOMMENDED) {
            attributes.put("providerId", recommendation.recommendedProviderId());
            for (CapabilityProvider provider : catalog()) {
                if (provider.providerId().equals(recommendation.recommendedProviderId())) {
                    attributes.put("providerType", provider.providerType());
                    attributes.put("providerVersion", provider.version());
                    break;
                }
            }
        }
        return attributes;
    }

    private static List<String> outputNames(CapabilityGap gap) {
        List<String> names = new ArrayList<>();
        gap.requiredContract().producedOutputs().forEach(declaration -> names.add(declaration.name()));
        return names;
    }

    private void indexBound(CapabilityGap gap) {
        if (gap.processInstanceId() != null) {
            boundIndex.put(indexKey(gap.processInstanceId(), gap.activityId(), gap.loopCounter()), gap.gapId());
        }
    }

    private static String indexKey(String processInstanceId, String activityId, Integer loopCounter) {
        return processInstanceId + "::" + activityId + "::" + (loopCounter == null ? "-" : loopCounter);
    }

    private void persist() {
        store.save(new ArrayList<>(gaps.values()));
    }
}
