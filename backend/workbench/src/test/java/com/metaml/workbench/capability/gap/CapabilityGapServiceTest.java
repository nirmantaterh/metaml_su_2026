package com.metaml.workbench.capability.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;
import com.metaml.workbench.governance.Approval;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.ApprovalStatus;
import com.metaml.workbench.governance.PolicyDecision;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.governance.PolicyEffect;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.service.WorkbenchService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

// MetaML Scope 6, Phase 5, test categories D (lifecycle) and G (runtime identity), plus the
// service-level half of category E (idempotent re-report). Synthetic invoice-validation domain
// throughout (section 21) - exercised against mocked WorkbenchService/ApprovalService/
// PolicyDecisionEngine so these tests never require a live Camunda engine.
class CapabilityGapServiceTest {

    @TempDir
    Path tempDir;

    private WorkbenchService workbenchService;
    private ApprovalService approvalService;
    private PolicyDecisionEngine policyDecisionEngine;
    private CapabilityGapService service;

    @BeforeEach
    void setUp() {
        workbenchService = mock(WorkbenchService.class);
        approvalService = mock(ApprovalService.class);
        policyDecisionEngine = mock(PolicyDecisionEngine.class);
        CapabilityGapStore store = new CapabilityGapStore(tempDir.resolve("capability-gaps.json").toString(), true);
        service = new CapabilityGapService(store, workbenchService, approvalService, policyDecisionEngine,
                new CapabilityGapRecommender());
    }

    private static CapabilityGap openGap(String gapId, String activityInstanceId, Integer loopCounter,
            String processInstanceId, String tenantId) {
        CapabilityContract contract = new CapabilityContract(null, Set.of(),
                Set.of(new IoDeclaration("riskScore", IoType.UNKNOWN, true)), ExecutionMode.SYNCHRONOUS, Map.of(),
                Set.of());
        String gapIdentity = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice",
                activityInstanceId, Set.of("riskScore"));
        Instant now = Instant.now();
        return new CapabilityGap(gapId != null ? gapId : gapIdentity, "invoiceValidationProcess", "ValidateInvoice",
                activityInstanceId, loopCounter, processInstanceId, contract, Map.of(), List.of(), tenantId,
                GapOrigin.RUNTIME_WORKBENCH_TWIN, GapStatus.OPEN, now, now, null, null);
    }

    // The agent name evolveActivity resolves and returns on its AgentDecision - and therefore the
    // identity that lands in evolvedAgent_<visit> and that a later execution must match to resolve.
    private static final String BOUND_PROVIDER_ID = "risk-scorer-01";

    private static CapabilityProvider provider() {
        return riskScorer("risk-scorer-01", "1.0.0");
    }

    private static CapabilityProvider riskScorer(String providerId, String version) {
        return new CapabilityProvider(providerId, "risk-scorer", version,
                new CapabilityContract(null, Set.of(), Set.of(new IoDeclaration("riskScore", IoType.UNKNOWN, true)),
                        ExecutionMode.SYNCHRONOUS, Map.of(), Set.of()),
                "synthetic", true, null);
    }

    // ---- D: lifecycle ----

    @Test
    void reportOpensANewGapAtOpen() {
        CapabilityGap reported = service.report(openGap(null, null, null, "twin-1", null));

        assertThat(reported.status()).isEqualTo(GapStatus.OPEN);
        assertThat(service.get(reported.gapId()).status()).isEqualTo(GapStatus.OPEN);
    }

    @Test
    void openTransitionsToRecommended() {
        CapabilityGap gap = service.report(openGap(null, null, null, "twin-1", null));
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));

        CapabilityGapRecommendation recommendation = service.recommend(gap.gapId());

        assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.RECOMMENDED);
        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.RECOMMENDED);
    }

    @Test
    void recommendedTransitionsToApprovedWhenNoTenantPolicyApplies() {
        CapabilityGap gap = service.report(openGap(null, null, null, "twin-1", null));
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));
        service.recommend(gap.gapId());

        AgentDecision decision = service.approve(gap.gapId(), "any-tenant");

        assertThat(decision.isApproved()).isTrue();
        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.APPROVED);
    }

    @Test
    void tenantPolicyDenyRejectsTheGap() {
        CapabilityGap gap = service.report(openGap(null, null, null, "twin-1", "tenant-a"));
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));
        service.recommend(gap.gapId());
        given(policyDecisionEngine.evaluate(any())).willReturn(new PolicyDecision(PolicyEffect.DENY, "tenant-a",
                "policy-1", "v1", 1, "rule-1", "synthetic denial", Instant.now()));

        AgentDecision decision = service.approve(gap.gapId(), "tenant-a");

        assertThat(decision.isApproved()).isFalse();
        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.REJECTED);
    }

    @Test
    void explicitRejectionMovesRecommendedToRejected() {
        CapabilityGap gap = service.report(openGap(null, null, null, "twin-1", null));
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));
        service.recommend(gap.gapId());

        service.reject(gap.gapId(), "synthetic reviewer declined");

        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.REJECTED);
    }

    @Test
    void approvedTransitionsToBoundThroughExistingEvolveActivity() {
        CapabilityGap gap = service.report(openGap(null, "inst-1", null, "twin-1", null));
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));
        service.recommend(gap.gapId());
        service.approve(gap.gapId(), "any-tenant");
        given(workbenchService.evolveActivity("twin-1", "ValidateInvoice", "inst-1", "risk-scorer"))
                .willReturn(new AgentDecision("risk-scorer", true, "risk-scorer-01", "bound"));

        AgentDecision decision = service.bind(gap.gapId());

        assertThat(decision.isApproved()).isTrue();
        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.BOUND);
        assertThat(service.get(gap.gapId()).boundProviderId()).isEqualTo("risk-scorer-01");
        verify(workbenchService).evolveActivity("twin-1", "ValidateInvoice", "inst-1", "risk-scorer");
    }

    @Test
    void boundDoesNotResolveUntilExecutionSucceeds() {
        CapabilityGap gap = boundGap();

        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.BOUND);
        // No execution-success notification has happened yet.
        assertThat(service.get(gap.gapId()).status()).isNotEqualTo(GapStatus.RESOLVED);
    }

    @Test
    void successfulExecutionResolvesTheBoundGap() {
        CapabilityGap gap = boundGap();

        service.onProviderExecutionSucceeded("twin-1", "ValidateInvoice", null, BOUND_PROVIDER_ID);

        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.RESOLVED);
    }

    @Test
    void bindRecordsTheProviderIdentityActuallyWrittenToProcessState() {
        CapabilityGap gap = boundGap();

        // evolveActivity resolves the agent name itself and returns it on the AgentDecision; that is
        // the identity the evolvedAgent_<visit> variable really carries, so it is the identity a
        // later execution must match.
        assertThat(gap.boundProviderId()).isEqualTo(BOUND_PROVIDER_ID);
    }

    // ---- F1: only the bound provider's own execution may resolve the gap ----

    @Test
    void executionWithNoResolvedProviderNeverResolvesTheGap() {
        CapabilityGap gap = boundGap();

        // A null executedProviderId is exactly what TwinAutomationDelegate reports when no
        // capability contract governed the execution - the default/fallback automation path, where
        // CapabilityOutputPropagator.publish validated nothing at all.
        service.onProviderExecutionSucceeded("twin-1", "ValidateInvoice", null, null);
        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.BOUND);

        service.onProviderExecutionSucceeded("twin-1", "ValidateInvoice", null, "  ");
        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.BOUND);

        // The gap must also still be indexed as BOUND: a default-automation run is not a failure of
        // this gap, so the genuinely bound provider must remain able to resolve it afterwards. If
        // the rejected notification had dropped the bound-index entry, this would silently no-op.
        service.onProviderExecutionSucceeded("twin-1", "ValidateInvoice", null, BOUND_PROVIDER_ID);
        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.RESOLVED);
    }

    @Test
    void executionByADifferentProviderNeverResolvesTheGap() {
        CapabilityGap gap = boundGap();

        // Right visit coordinates, wrong provider: the coordinates only say WHICH gap an execution
        // is about, never WHAT executed.
        service.onProviderExecutionSucceeded("twin-1", "ValidateInvoice", null, "validator-agent-01");

        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.BOUND);
        assertThat(service.get(gap.gapId()).boundProviderId()).isEqualTo(BOUND_PROVIDER_ID);

        // Still indexed, so the real bound provider can still resolve it.
        service.onProviderExecutionSucceeded("twin-1", "ValidateInvoice", null, BOUND_PROVIDER_ID);
        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.RESOLVED);
    }

    @Test
    void executionFailureNeverResolvesTheGap() {
        // Modeling "provider execution failed / violated the Phase 4 contract": the success hook is
        // simply never called for this visit - exactly what TwinAutomationDelegate does when
        // CapabilityOutputPropagator.publish throws instead of returning.
        CapabilityGap gap = boundGap();

        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.BOUND);
    }

    @Test
    void executionSuccessNotificationForAnUnrelatedVisitDoesNotResolveThisGap() {
        CapabilityGap gap = boundGap();

        // A different activity / different twin reports success - must not resolve this gap.
        service.onProviderExecutionSucceeded("twin-1", "SomeOtherActivity", null, BOUND_PROVIDER_ID);
        service.onProviderExecutionSucceeded("some-other-twin", "ValidateInvoice", null, BOUND_PROVIDER_ID);

        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.BOUND);
    }

    @Test
    void illegalTransitionOpenToApprovedIsRejected() {
        CapabilityGap gap = service.report(openGap(null, null, null, "twin-1", null));

        assertThatThrownBy(() -> service.approve(gap.gapId(), "any-tenant"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void illegalTransitionOpenToBoundIsRejected() {
        CapabilityGap gap = service.report(openGap(null, null, null, "twin-1", null));

        assertThatThrownBy(() -> service.bind(gap.gapId())).isInstanceOf(IllegalStateException.class);
        verify(workbenchService, never()).evolveActivity(anyString(), anyString(), any(), anyString());
    }

    @Test
    void approvedCannotBeResolvedWithoutFirstBeingBound() {
        CapabilityGap gap = service.report(openGap(null, null, null, "twin-1", null));
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));
        service.recommend(gap.gapId());
        service.approve(gap.gapId(), "any-tenant");
        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.APPROVED);

        // The execution-success hook only resolves a gap it finds indexed as BOUND; an APPROVED gap
        // was never indexed, so this is a silent no-op, not a resolution.
        service.onProviderExecutionSucceeded("twin-1", "ValidateInvoice", null, BOUND_PROVIDER_ID);

        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.APPROVED);
    }

    @Test
    void unknownGapIdIsReportedAsNotFound() {
        assertThatThrownBy(() -> service.get("does-not-exist")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void expireIsAllowedFromOpenRecommendedAndApprovedButNotFromBoundOrResolved() {
        CapabilityGap open = service.report(openGap(null, "inst-open", null, "twin-1", null));
        service.expire(open.gapId(), "process instance ended");
        assertThat(service.get(open.gapId()).status()).isEqualTo(GapStatus.EXPIRED);

        CapabilityGap bound = boundGap();
        assertThatThrownBy(() -> service.expire(bound.gapId(), "should not be allowed"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- Idempotent re-report (category E, service-level half) ----

    @Test
    void reReportingTheSameLogicalGapDoesNotDuplicateIt() {
        CapabilityGap first = service.report(openGap(null, null, null, "twin-1", null));
        CapabilityGap second = service.report(openGap(null, null, null, "twin-1", null));

        assertThat(first.gapId()).isEqualTo(second.gapId());
        assertThat(service.list()).hasSize(1);
    }

    @Test
    void reReportingAfterTheGapHasProgressedLeavesItUndisturbed() {
        CapabilityGap gap = service.report(openGap(null, null, null, "twin-1", null));
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));
        service.recommend(gap.gapId());

        CapabilityGap reReported = service.report(openGap(gap.gapId(), null, null, "twin-1", null));

        assertThat(reReported.status()).isEqualTo(GapStatus.RECOMMENDED);
        assertThat(service.list()).hasSize(1);
    }

    // ---- P6 candidate-derivation regression (MetaML Scope 6, Phase 6 correction): report() is the
    // single authoritative point that derives candidateProviderIds against the live catalog, never
    // trusting whatever the caller supplied - in particular never trusting the empty placeholder the
    // /report HTTP boundary always sends (CapabilityGapController.toCandidate has no catalog access
    // and must not calculate satisfaction itself; openGap() below mirrors that placeholder by always
    // building its candidate with an empty candidateProviderIds, exactly like toCandidate() does). ----

    // Test 1 (zero candidates): a genuine NO_SUITABLE_PROVIDER stays empty and blocks AI resolution.
    @Test
    void reportWithNoSatisfyingProviderYieldsEmptyCandidatesAndNoSuitableRecommendation() {
        CapabilityGap candidate = openGap(null, null, null, "twin-1", null);

        CapabilityGap reported = service.report(candidate);

        assertThat(reported.candidateProviderIds()).isEmpty();
        CapabilityGapRecommendation recommendation = service.recommend(reported.gapId());
        assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.NO_SUITABLE_PROVIDER);
    }

    // Test 2 (exactly one candidate): the exact P6 regression. Before this fix, a candidate arriving
    // with the /report boundary's empty placeholder was stored and returned as-is, so
    // gap.candidateProviderIds.length === 0 on the VS Code side even though a real provider satisfied
    // the gap - which is exactly the guard in commands.ts that stopped "MetaML: Resolve Capability Gap
    // (AI)" before Ollama was ever called.
    @Test
    void reportWithExactlyOneSatisfyingProviderPopulatesTheCandidateSetEvenThoughTheCallerSuppliedNone() {
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));
        CapabilityGap candidate = openGap(null, null, null, "twin-1", null);
        assertThat(candidate.candidateProviderIds())
                .as("mirrors CapabilityGapController.toCandidate()'s empty placeholder")
                .isEmpty();

        CapabilityGap reported = service.report(candidate);

        assertThat(reported.candidateProviderIds()).containsExactly("risk-scorer-01");
        assertThat(service.get(reported.gapId()).candidateProviderIds()).containsExactly("risk-scorer-01");
    }

    // Test 3 (multiple candidates): every satisfying provider must be present - never reduced to a
    // single pick, ambiguous or not.
    @Test
    void reportWithMultipleSatisfyingProvidersPopulatesTheFullCandidateSet() {
        given(workbenchService.listCapabilityProviders())
                .willReturn(List.of(riskScorer("risk-scorer-01", "1.0.0"), riskScorer("risk-scorer-02", "3.0.0"),
                        riskScorer("risk-scorer-03", "2.0.0")));

        CapabilityGap reported = service.report(openGap(null, null, null, "twin-1", null));

        assertThat(reported.candidateProviderIds())
                .containsExactlyInAnyOrder("risk-scorer-01", "risk-scorer-02", "risk-scorer-03");
    }

    // Test 4 (recommendation separation): recommending must never collapse candidateProviderIds down
    // to the recommended id - the two remain distinct concepts (candidate set vs. AI/recommender pick).
    @Test
    void recommendingNeverCollapsesCandidateProviderIdsDownToTheRecommendedId() {
        given(workbenchService.listCapabilityProviders())
                .willReturn(List.of(riskScorer("risk-scorer-01", "1.0.0"), riskScorer("risk-scorer-02", "3.0.0"),
                        riskScorer("risk-scorer-03", "2.0.0")));
        CapabilityGap reported = service.report(openGap(null, null, null, "twin-1", null));

        CapabilityGapRecommendation recommendation = service.recommend(reported.gapId());

        assertThat(recommendation.status()).isEqualTo(CapabilityGapRecommendation.Status.RECOMMENDED);
        assertThat(recommendation.recommendedProviderId())
                .as("highest semver among the tied provider type wins the recommendation")
                .isEqualTo("risk-scorer-02");
        assertThat(service.get(reported.gapId()).candidateProviderIds())
                .as("candidateProviderIds is untouched by recommend() - still every satisfying provider")
                .containsExactlyInAnyOrder("risk-scorer-01", "risk-scorer-02", "risk-scorer-03");
    }

    // ---- G: runtime identity / isolation ----

    @Test
    void multiInstanceSiblingsAtTheSameActivityGetIsolatedGaps() {
        CapabilityGap first = service.report(openGap(null, "inst-1", 0, "twin-1", null));
        CapabilityGap second = service.report(openGap(null, "inst-2", 1, "twin-1", null));

        assertThat(first.gapId()).isNotEqualTo(second.gapId());
        assertThat(service.list()).hasSize(2);
    }

    @Test
    void bindingOneSiblingDoesNotAffectTheParallelSibling() {
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));
        CapabilityGap first = service.report(openGap(null, "inst-1", 0, "twin-1", null));
        CapabilityGap second = service.report(openGap(null, "inst-2", 1, "twin-1", null));
        service.recommend(first.gapId());
        service.approve(first.gapId(), "any-tenant");
        given(workbenchService.evolveActivity("twin-1", "ValidateInvoice", "inst-1", "risk-scorer"))
                .willReturn(new AgentDecision("risk-scorer", true, "risk-scorer-01", "bound"));

        service.bind(first.gapId());

        assertThat(service.get(first.gapId()).status()).isEqualTo(GapStatus.BOUND);
        assertThat(service.get(second.gapId()).status()).isEqualTo(GapStatus.OPEN);
    }

    @Test
    void runtimeWorkbenchTwinOriginIsPreservedThroughReporting() {
        CapabilityGap twinGap = service.report(openGap("twin-gap", "inst-1", null, "twin-1", null));

        assertThat(twinGap.origin()).isEqualTo(GapOrigin.RUNTIME_WORKBENCH_TWIN);
        assertThat(service.get("twin-gap").origin()).isEqualTo(GapOrigin.RUNTIME_WORKBENCH_TWIN);
    }

    @Test
    void runtimeWorkbenchOriginalOriginIsTrackedSeparatelyFromTheTwinOrigin() {
        CapabilityContract contract = new CapabilityContract(null, Set.of(),
                Set.of(new IoDeclaration("riskScore", IoType.UNKNOWN, true)), ExecutionMode.SYNCHRONOUS, Map.of(),
                Set.of());
        Instant now = Instant.now();
        CapabilityGap originalGap = new CapabilityGap("original-gap", "invoiceValidationProcess", "ValidateInvoice",
                "inst-1", null, "original-1", contract, Map.of(), List.of(), null,
                GapOrigin.RUNTIME_WORKBENCH_ORIGINAL, GapStatus.OPEN, now, now, null, null);

        CapabilityGap twinGap = service.report(openGap("twin-gap", "inst-1", null, "twin-1", null));
        CapabilityGap reportedOriginal = service.report(originalGap);

        assertThat(reportedOriginal.origin()).isEqualTo(GapOrigin.RUNTIME_WORKBENCH_ORIGINAL);
        assertThat(twinGap.origin()).isEqualTo(GapOrigin.RUNTIME_WORKBENCH_TWIN);
        assertThat(service.list()).hasSize(2);
    }

    // ---- P6: bind(gapId, confirmedProviderId) - the AI/human-confirmed provider seam (MetaML
    // Scope 6, Phase 6, sections 4, 7, 8). CapabilitySatisfaction remains the deterministic
    // authority: a confirmed id is only ever accepted after independently passing the exact same
    // catalog+satisfaction check the deterministic recommender itself is built on. ----

    @Test
    void confirmedProviderIdBindsThroughTheExistingEvolveActivityJustLikeTheDeterministicPath() {
        CapabilityGap gap = service.report(openGap(null, "inst-1", null, "twin-1", null));
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));
        service.recommend(gap.gapId());
        service.approve(gap.gapId(), "any-tenant");
        given(workbenchService.evolveActivity("twin-1", "ValidateInvoice", "inst-1", "risk-scorer"))
                .willReturn(new AgentDecision("risk-scorer", true, "risk-scorer-01", "bound"));

        AgentDecision decision = service.bind(gap.gapId(), "risk-scorer-01");

        assertThat(decision.isApproved()).isTrue();
        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.BOUND);
        assertThat(service.get(gap.gapId()).boundProviderId()).isEqualTo("risk-scorer-01");
        verify(workbenchService).evolveActivity("twin-1", "ValidateInvoice", "inst-1", "risk-scorer");
    }

    @Test
    void nullOrBlankConfirmedProviderIdFallsBackToTheDeterministicRecommender() {
        CapabilityGap gap = service.report(openGap(null, "inst-1", null, "twin-1", null));
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));
        service.recommend(gap.gapId());
        service.approve(gap.gapId(), "any-tenant");
        given(workbenchService.evolveActivity("twin-1", "ValidateInvoice", "inst-1", "risk-scorer"))
                .willReturn(new AgentDecision("risk-scorer", true, "risk-scorer-01", "bound"));

        AgentDecision decision = service.bind(gap.gapId(), "  ");

        assertThat(decision.isApproved()).isTrue();
        assertThat(service.get(gap.gapId()).boundProviderId()).isEqualTo("risk-scorer-01");
    }

    // Failure-safety category B: an AI/human-confirmed provider id that is not in the live catalog
    // must be rejected before evolveActivity is ever called - no evolution, no binding, gap stays
    // APPROVED (never fabricated into BOUND).
    @Test
    void confirmedProviderIdNotInTheCatalogIsRejectedWithoutBindingOrEvolution() {
        CapabilityGap gap = service.report(openGap(null, "inst-1", null, "twin-1", null));
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));
        service.recommend(gap.gapId());
        service.approve(gap.gapId(), "any-tenant");

        AgentDecision decision = service.bind(gap.gapId(), "invented-provider-that-does-not-exist");

        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.getReason()).contains("not in the current catalog");
        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.APPROVED);
        verify(workbenchService, never()).evolveActivity(anyString(), anyString(), any(), anyString());
    }

    // Failure-safety category B (continued): a real, cataloged provider that does not actually
    // satisfy the gap's required contract must also be rejected - the AI can never talk its way
    // past CapabilitySatisfaction just because the id it picked is real.
    @Test
    void confirmedProviderIdThatDoesNotSatisfyTheContractIsRejectedWithoutBindingOrEvolution() {
        CapabilityGap gap = service.report(openGap(null, "inst-1", null, "twin-1", null));
        CapabilityProvider mismatched = new CapabilityProvider("notifier-01", "notifier", "1.0.0",
                new CapabilityContract(null, Set.of(), Set.of(new IoDeclaration("sent", IoType.BOOLEAN, true)),
                        ExecutionMode.SYNCHRONOUS, Map.of(), Set.of()),
                "synthetic", true, null);
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider(), mismatched));
        service.recommend(gap.gapId());
        service.approve(gap.gapId(), "any-tenant");

        AgentDecision decision = service.bind(gap.gapId(), "notifier-01");

        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.getReason()).contains("does not satisfy");
        assertThat(service.get(gap.gapId()).status()).isEqualTo(GapStatus.APPROVED);
        verify(workbenchService, never()).evolveActivity(anyString(), anyString(), any(), anyString());
    }

    @Test
    void confirmedProviderIdIsRejectedWhenTheGapIsNotYetApproved() {
        CapabilityGap gap = service.report(openGap(null, "inst-1", null, "twin-1", null));

        assertThatThrownBy(() -> service.bind(gap.gapId(), "risk-scorer-01"))
                .isInstanceOf(IllegalStateException.class);
        verify(workbenchService, never()).evolveActivity(anyString(), anyString(), any(), anyString());
    }

    // ---- helper ----

    private CapabilityGap boundGap() {
        CapabilityGap gap = service.report(openGap(null, null, null, "twin-1", null));
        given(workbenchService.listCapabilityProviders()).willReturn(List.of(provider()));
        service.recommend(gap.gapId());
        service.approve(gap.gapId(), "any-tenant");
        given(workbenchService.evolveActivity("twin-1", "ValidateInvoice", null, "risk-scorer"))
                .willReturn(new AgentDecision("risk-scorer", true, "risk-scorer-01", "bound"));
        service.bind(gap.gapId());
        return service.get(gap.gapId());
    }
}
