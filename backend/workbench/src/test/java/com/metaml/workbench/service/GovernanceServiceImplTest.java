package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.metaml.workbench.model.GovernanceUsage;

// Verifies runtime in-memory platform governance quotas and evolution slot reservations.
// Durable tenant-level governance policies are tested separately in TenantPolicyServiceTest.
class GovernanceServiceImplTest {

    private static final String TWIN = "twin-1";

    private GovernanceServiceImpl service() {
        return new GovernanceServiceImpl(3, 5);
    }

    @Test
    void quotaIsConsumedWithinOneRunningInstance() {
        GovernanceServiceImpl governance = service();

        assertThat(governance.reserveEvolutionSlot(TWIN, "validator").isAllowed()).isTrue();
        assertThat(governance.reserveEvolutionSlot(TWIN, "validator").isAllowed()).isTrue();
        assertThat(governance.reserveEvolutionSlot(TWIN, "validator").isAllowed()).isTrue();

        // fourth against a limit of three
        assertThat(governance.reserveEvolutionSlot(TWIN, "validator").isAllowed()).isFalse();
        assertThat(governance.getUsage(TWIN).getEvolutionCount()).isEqualTo(3);
    }

    // The restart contract, stated as a test: a new instance is a new run, and a run's quota starts
    // full. Constructing a second service is exactly what a restart produces for this class, since
    // nothing about it is persisted.
    @Test
    void afreshInstanceStartsWithQuotaUnusedBecauseThisLayerIsRuntimeOnly() {
        GovernanceServiceImpl before = service();
        while (before.reserveEvolutionSlot(TWIN, "validator").isAllowed()) {
            // exhaust it
        }
        assertThat(before.getUsage(TWIN).getEvolutionCount()).isEqualTo(3);

        GovernanceServiceImpl afterRestart = service();

        GovernanceUsage usage = afterRestart.getUsage(TWIN);
        assertThat(usage.getEvolutionCount()).isZero();
        assertThat(usage.getTwinExecutionCount()).isZero();
        assertThat(afterRestart.reserveEvolutionSlot(TWIN, "validator").isAllowed()).isTrue();
    }

    // Same runtime-only reading for the runtime policy override: updatePolicy changes this
    // instance, and a new instance is back to its configured defaults.
    @Test
    void aruntimePolicyOverrideDoesNotOutliveTheInstanceThatSetIt() {
        GovernanceServiceImpl before = service();
        before.updatePolicy(java.util.Set.of("forbidden"), 99);
        assertThat(before.getPolicy().getDeniedAgentTypes()).containsExactly("forbidden");
        assertThat(before.getPolicy().getMaxEvolutionsPerTwin()).isEqualTo(99);

        GovernanceServiceImpl afterRestart = service();

        assertThat(afterRestart.getPolicy().getDeniedAgentTypes()).isEmpty();
        assertThat(afterRestart.getPolicy().getMaxEvolutionsPerTwin()).isEqualTo(3);
    }

    // releasing is what keeps a denied/failed evolution from permanently costing a slot - the
    // pairing runEvolution's own finally block depends on
    @Test
    void releasingASlotGivesItBack() {
        GovernanceServiceImpl governance = service();
        governance.reserveEvolutionSlot(TWIN, "validator");
        assertThat(governance.getUsage(TWIN).getEvolutionCount()).isEqualTo(1);

        governance.releaseEvolutionSlot(TWIN);

        assertThat(governance.getUsage(TWIN).getEvolutionCount()).isZero();
    }
}
