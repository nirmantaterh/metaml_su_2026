package com.metaml.workbench.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PolicyDecisionEngineTest {

    private TenantPolicyService newTenantPolicyService() {
        return new TenantPolicyService(new TenantPolicyStore("unused", false));
    }

    private PolicyVersion activateRule(TenantPolicyService service, String policyId, String tenantId, String field,
            String operator, String value, PolicyEffect effect) {
        PolicyVersion draft = service.createDraftVersion(policyId, tenantId);
        service.addRule(draft.id(), tenantId, field, operator, value, effect);
        return service.activateVersion(draft.id(), tenantId);
    }

    // Evaluates tenant-specific policy thresholds dynamically.
    @Test
    void allowWhenNoRuleThresholdIsCrossed() {
        TenantPolicyService service = newTenantPolicyService();
        PolicyDecisionEngine engine = new PolicyDecisionEngine(service);
        Tenant citibank = service.createTenant("CitiBank");
        Policy policy = service.createTenantPolicy(citibank.id(), "Large Payment Approval");
        activateRule(service, policy.id(), citibank.id(), "payment.amount", ">", "10000",
                PolicyEffect.REQUIRE_APPROVAL);

        PolicyDecision decision = engine.evaluate(
                new GovernanceRequest(citibank.id(), "SUBMIT_PAYMENT", Map.of("payment.amount", 7000)));

        assertThat(decision.decision()).isEqualTo(PolicyEffect.ALLOW);
    }

    @Test
    void requireApprovalWhenThresholdIsCrossed() {
        TenantPolicyService service = newTenantPolicyService();
        PolicyDecisionEngine engine = new PolicyDecisionEngine(service);
        Tenant redcollar = service.createTenant("RedCollar");
        Policy policy = service.createTenantPolicy(redcollar.id(), "Large Order Approval");
        activateRule(service, policy.id(), redcollar.id(), "order.amount", ">", "5000",
                PolicyEffect.REQUIRE_APPROVAL);

        PolicyDecision decision = engine.evaluate(
                new GovernanceRequest(redcollar.id(), "SUBMIT_ORDER", Map.of("order.amount", 7000)));

        assertThat(decision.decision()).isEqualTo(PolicyEffect.REQUIRE_APPROVAL);
        assertThat(decision.policyId()).isEqualTo(policy.id());
    }

    @Test
    void deny() {
        TenantPolicyService service = newTenantPolicyService();
        PolicyDecisionEngine engine = new PolicyDecisionEngine(service);
        Tenant redcollar = service.createTenant("RedCollar");
        Policy policy = service.createTenantPolicy(redcollar.id(), "Deletion Policy");
        activateRule(service, policy.id(), redcollar.id(), "action", "==", "DELETE_CUSTOMER", PolicyEffect.DENY);

        PolicyDecision decision = engine.evaluate(new GovernanceRequest(redcollar.id(), "DELETE_CUSTOMER", Map.of()));

        assertThat(decision.decision()).isEqualTo(PolicyEffect.DENY);
    }

    // tenant A's rule must never leak onto tenant B's evaluation of the identical action
    @Test
    void tenantIsolation() {
        TenantPolicyService service = newTenantPolicyService();
        PolicyDecisionEngine engine = new PolicyDecisionEngine(service);
        Tenant tenantA = service.createTenant("Tenant A");
        Tenant tenantB = service.createTenant("Tenant B");
        Policy policyA = service.createTenantPolicy(tenantA.id(), "Policy A");
        activateRule(service, policyA.id(), tenantA.id(), "action", "==", "X", PolicyEffect.DENY);

        PolicyDecision decisionA = engine.evaluate(new GovernanceRequest(tenantA.id(), "X", Map.of()));
        PolicyDecision decisionB = engine.evaluate(new GovernanceRequest(tenantB.id(), "X", Map.of()));

        assertThat(decisionA.decision()).isEqualTo(PolicyEffect.DENY);
        assertThat(decisionB.decision()).isEqualTo(PolicyEffect.ALLOW);
    }

    // only the ACTIVE version is ever evaluated, and activating a new one changes the
    // decision immediately - Section 16, and version 1 is never mutated to get there
    @Test
    void onlyTheActiveVersionIsEvaluated() {
        TenantPolicyService service = newTenantPolicyService();
        PolicyDecisionEngine engine = new PolicyDecisionEngine(service);
        Tenant tenant = service.createTenant("CitiBank");
        Policy policy = service.createTenantPolicy(tenant.id(), "Policy");
        activateRule(service, policy.id(), tenant.id(), "amount", ">", "5000", PolicyEffect.DENY);

        PolicyDecision first = engine.evaluate(new GovernanceRequest(tenant.id(), "ACT", Map.of("amount", 7000)));
        assertThat(first.decision()).isEqualTo(PolicyEffect.DENY);
        assertThat(first.policyVersionNumber()).isEqualTo(1);

        PolicyVersion v2 = activateRule(service, policy.id(), tenant.id(), "amount", ">", "5000", PolicyEffect.ALLOW);
        PolicyDecision second = engine.evaluate(new GovernanceRequest(tenant.id(), "ACT", Map.of("amount", 7000)));

        assertThat(second.decision()).isEqualTo(PolicyEffect.ALLOW);
        assertThat(second.policyVersionNumber()).isEqualTo(v2.versionNumber());
    }

    // Section 8: two rules in the same version both match - the earlier one in the version's
    // own rule list wins, not whichever the engine happens to see last
    @Test
    void firstMatchingRuleInVersionOrderWins() {
        TenantPolicyService service = newTenantPolicyService();
        PolicyDecisionEngine engine = new PolicyDecisionEngine(service);
        Tenant tenant = service.createTenant("CitiBank");
        Policy policy = service.createTenantPolicy(tenant.id(), "Policy");
        PolicyVersion draft = service.createDraftVersion(policy.id(), tenant.id());
        service.addRule(draft.id(), tenant.id(), "amount", ">", "5000", PolicyEffect.ALLOW);
        service.addRule(draft.id(), tenant.id(), "amount", ">", "10000", PolicyEffect.DENY);
        service.activateVersion(draft.id(), tenant.id());

        PolicyDecision decision = engine.evaluate(new GovernanceRequest(tenant.id(), "ACT", Map.of("amount", 15000)));

        assertThat(decision.decision()).isEqualTo(PolicyEffect.ALLOW);
    }

    // Section 9: a platform DENY must survive even when the tenant's own policy would ALLOW
    // the same action
    @Test
    void platformDenyBeatsTenantAllow() {
        TenantPolicyService service = newTenantPolicyService();
        PolicyDecisionEngine engine = new PolicyDecisionEngine(service);
        Policy platformPolicy = service.createPlatformPolicy("MetaML Safety Invariant");
        activateRule(service, platformPolicy.id(), null, "action", "==", "DELETE_SYSTEM_RESOURCE",
                PolicyEffect.DENY);
        Tenant tenant = service.createTenant("CitiBank");
        Policy tenantPolicy = service.createTenantPolicy(tenant.id(), "Tenant Policy");
        activateRule(service, tenantPolicy.id(), tenant.id(), "action", "==", "DELETE_SYSTEM_RESOURCE",
                PolicyEffect.ALLOW);

        PolicyDecision decision = engine.evaluate(
                new GovernanceRequest(tenant.id(), "DELETE_SYSTEM_RESOURCE", Map.of()));

        assertThat(decision.decision()).isEqualTo(PolicyEffect.DENY);
    }

    @Test
    void noMatchDefaultsToAllow() {
        TenantPolicyService service = newTenantPolicyService();
        PolicyDecisionEngine engine = new PolicyDecisionEngine(service);
        Tenant tenant = service.createTenant("CitiBank");
        Policy policy = service.createTenantPolicy(tenant.id(), "Policy");
        activateRule(service, policy.id(), tenant.id(), "amount", ">", "5000", PolicyEffect.DENY);

        PolicyDecision decision = engine.evaluate(new GovernanceRequest(tenant.id(), "UNRELATED_ACTION", Map.of()));

        assertThat(decision.decision()).isEqualTo(PolicyEffect.ALLOW);
        assertThat(decision.policyId()).isNull();
    }

    @Test
    void unsupportedOperatorFailsLoudInsteadOfSilentlyAllowing() {
        TenantPolicyService service = newTenantPolicyService();
        PolicyDecisionEngine engine = new PolicyDecisionEngine(service);
        Tenant tenant = service.createTenant("CitiBank");
        Policy policy = service.createTenantPolicy(tenant.id(), "Policy");
        activateRule(service, policy.id(), tenant.id(), "amount", "STARTS_WITH", "5", PolicyEffect.DENY);

        assertThatThrownBy(() -> engine.evaluate(new GovernanceRequest(tenant.id(), "ACT", Map.of("amount", "500"))))
                .isInstanceOf(PolicyEvaluationException.class);
    }
}
