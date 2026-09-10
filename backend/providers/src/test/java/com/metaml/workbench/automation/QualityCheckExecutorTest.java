package com.metaml.workbench.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;

// Deterministic, non-random decision coverage for QualityCheckExecutor (see OrderApprovalExecutor's
// class-level comment for why a real provider was introduced for P7 Final Acceptance Closure).
class QualityCheckExecutorTest {

    private static final String ACTIVITY = "Checking";

    private CapabilityExecutionContext contextWith(Object forceQualityFailure, Object orderStatus) {
        CapabilityExecutionContext context = mock(CapabilityExecutionContext.class);
        given(context.getVariable("forceQualityFailure")).willReturn(forceQualityFailure);
        given(context.getVariable("orderStatus")).willReturn(orderStatus);
        given(context.getProcessInstanceId()).willReturn("pi-1");
        return context;
    }

    @Test
    void passesByDefaultWhenNothingInAvailableDataIndicatesADefect() {
        QualityCheckExecutor executor = new QualityCheckExecutor();

        AutomationResult result = executor.execute(contextWith(null, null), ACTIVITY,
                QualityCheckExecutor.AGENT_NAME);

        assertThat(result.outputs()).containsEntry("qualityPassed", true);
    }

    @Test
    void failsWhenOrderStatusIndicatesADefectRegardlessOfCase() {
        QualityCheckExecutor executor = new QualityCheckExecutor();

        AutomationResult result = executor.execute(contextWith(null, "defective"), ACTIVITY,
                QualityCheckExecutor.AGENT_NAME);

        assertThat(result.outputs()).containsEntry("qualityPassed", false);
        assertThat(result.outputs().get("qualityMessage").toString()).contains("indicates a defect");
    }

    @Test
    void failsWhenTheForceFailureFlagIsSetRegardlessOfOtherwiseCleanStatus() {
        QualityCheckExecutor executor = new QualityCheckExecutor();

        AutomationResult result = executor.execute(contextWith(true, "IN_PROGRESS"), ACTIVITY,
                QualityCheckExecutor.AGENT_NAME);

        assertThat(result.outputs()).containsEntry("qualityPassed", false);
        assertThat(result.outputs().get("qualityMessage").toString()).contains("forced quality failure flag");
    }

    @Test
    void identityMatchesTheNodeManagerCatalogEntry() {
        QualityCheckExecutor executor = new QualityCheckExecutor();

        assertThat(executor.getHandledAgentType()).isEqualTo("quality-check");
        assertThat(executor.getHandledAgentNames()).containsExactly("quality-check-agent-01");
    }
}
