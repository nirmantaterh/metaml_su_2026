package com.metaml.workbench.automation;

import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// Generic order/request-approval gate: computes a real, deterministic approve/reject decision from
// whatever order-domain process variables are actually present (quantity, orderStatus), never a
// fabricated or random one. Reads exactly three generically-named variables - none specific to any
// one process - and defaults to approved when nothing in the available data disqualifies the order,
// the same "no evidence of a problem" convention ValidatorExecutor already uses. Introduced for P7
// Final Acceptance Closure so an activity whose downstream gateway requires a real "was this
// approved" decision has a legitimate provider to bind to, instead of either fabricating the
// decision (the removed Math.random() behavior) or being permanently unable to complete.
@Component("orderApprovalExecutor")
public class OrderApprovalExecutor implements ComponentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(OrderApprovalExecutor.class);

    public static final String AGENT_TYPE = "order-approval";
    public static final String AGENT_NAME = "order-approval-agent-01";
    public static final String EXECUTOR_NAME = "OrderApprovalExecutor";

    private static final Set<String> DISQUALIFYING_STATUSES = Set.of("REJECTED", "CANCELLED", "CANCELED", "INVALID");

    @Override
    public String getHandledAgentType() {
        return AGENT_TYPE;
    }

    @Override
    public Set<String> getHandledAgentNames() {
        return Set.of(AGENT_NAME);
    }

    @Override
    public AutomationResult execute(CapabilityExecutionContext execution, String activityId,
            String agentName) {
        Object forceRejectionVar = execution.getVariable("forceOrderRejection");
        Object quantityVar = execution.getVariable("quantity");
        Object orderStatusVar = execution.getVariable("orderStatus");

        boolean orderApproved;
        String reason;

        if (Boolean.TRUE.equals(forceRejectionVar)) {
            orderApproved = false;
            reason = String.format("Order rejected for activity %s: forced rejection flag is set", activityId);
        } else if (quantityVar instanceof Number quantityNumber && quantityNumber.doubleValue() <= 0) {
            orderApproved = false;
            reason = String.format("Order rejected for activity %s: non-positive quantity (%s)", activityId,
                    quantityVar);
        } else if (orderStatusVar != null && DISQUALIFYING_STATUSES.contains(orderStatusVar.toString().toUpperCase())) {
            orderApproved = false;
            reason = String.format("Order rejected for activity %s: order status '%s' disqualifies approval",
                    activityId, orderStatusVar);
        } else {
            orderApproved = true;
            reason = String.format(
                    "Order approved for activity %s: no disqualifying condition found in available order data",
                    activityId);
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("orderApproved", orderApproved);
        outputs.put("approvalReason", reason);

        logger.info("{} executed for activity {} on instance {} (agent: {}) with orderApproved={}",
                EXECUTOR_NAME, activityId, execution.getProcessInstanceId(), agentName, orderApproved);

        String summary = String.format("%s executed for %s: orderApproved=%b", EXECUTOR_NAME, activityId,
                orderApproved);

        return new AutomationResult(summary, outputs);
    }
}
