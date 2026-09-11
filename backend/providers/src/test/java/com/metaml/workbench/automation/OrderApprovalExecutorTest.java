package com.metaml.workbench.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;

// Deterministic, non-random decision coverage for OrderApprovalExecutor (introduced for P7 Final
// Acceptance Closure - see the class-level comment there for why it exists). Every branch is driven
// by a real input variable; none is time-based, random, or hardcoded to a single outcome.
class OrderApprovalExecutorTest {

    private static final String ACTIVITY = "VerifyOrder";

    private CapabilityExecutionContext contextWith(Object forceOrderRejection, Object quantity,
            Object orderStatus) {
        CapabilityExecutionContext context = mock(CapabilityExecutionContext.class);
        given(context.getVariable("forceOrderRejection")).willReturn(forceOrderRejection);
        given(context.getVariable("quantity")).willReturn(quantity);
        given(context.getVariable("orderStatus")).willReturn(orderStatus);
        given(context.getProcessInstanceId()).willReturn("pi-1");
        return context;
    }

    @Test
    void approvesByDefaultWhenNothingInAvailableDataDisqualifiesTheOrder() {
        OrderApprovalExecutor executor = new OrderApprovalExecutor();

        AutomationResult result = executor.execute(contextWith(null, null, null), ACTIVITY,
                OrderApprovalExecutor.AGENT_NAME);

        assertThat(result.outputs()).containsEntry("orderApproved", true);
        assertThat(result.outputs().get("approvalReason").toString()).contains("no disqualifying condition");
    }

    @Test
    void approvesAPositiveQuantityOrderWithNoDisqualifyingStatus() {
        OrderApprovalExecutor executor = new OrderApprovalExecutor();

        AutomationResult result = executor.execute(contextWith(null, 5, "IN_PROGRESS"), ACTIVITY,
                OrderApprovalExecutor.AGENT_NAME);

        assertThat(result.outputs()).containsEntry("orderApproved", true);
    }

    @Test
    void rejectsWhenQuantityIsNotPositive() {
        OrderApprovalExecutor executor = new OrderApprovalExecutor();

        AutomationResult result = executor.execute(contextWith(null, 0, null), ACTIVITY,
                OrderApprovalExecutor.AGENT_NAME);

        assertThat(result.outputs()).containsEntry("orderApproved", false);
        assertThat(result.outputs().get("approvalReason").toString()).contains("non-positive quantity");
    }

    @Test
    void rejectsADisqualifyingOrderStatusRegardlessOfCase() {
        OrderApprovalExecutor executor = new OrderApprovalExecutor();

        AutomationResult result = executor.execute(contextWith(null, 3, "cancelled"), ACTIVITY,
                OrderApprovalExecutor.AGENT_NAME);

        assertThat(result.outputs()).containsEntry("orderApproved", false);
        assertThat(result.outputs().get("approvalReason").toString()).contains("disqualifies approval");
    }

    @Test
    void rejectsWhenTheForceRejectionFlagIsSetRegardlessOfOtherwiseLegitimateData() {
        OrderApprovalExecutor executor = new OrderApprovalExecutor();

        AutomationResult result = executor.execute(contextWith(true, 10, "IN_PROGRESS"), ACTIVITY,
                OrderApprovalExecutor.AGENT_NAME);

        assertThat(result.outputs()).containsEntry("orderApproved", false);
        assertThat(result.outputs().get("approvalReason").toString()).contains("forced rejection flag");
    }

    @Test
    void identityMatchesTheNodeManagerCatalogEntry() {
        OrderApprovalExecutor executor = new OrderApprovalExecutor();

        assertThat(executor.getHandledAgentType()).isEqualTo("order-approval");
        assertThat(executor.getHandledAgentNames()).containsExactly("order-approval-agent-01");
    }
}
