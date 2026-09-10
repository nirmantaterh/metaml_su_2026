package com.metaml.workbench.automation;

import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// Generic quality-gate provider: computes a real, deterministic pass/fail decision from whatever
// generic order/request-state variable is actually present (orderStatus), never a fabricated or
// random one. Mirrors ValidatorExecutor's own "no evidence of a problem" default-pass convention.
// Introduced alongside OrderApprovalExecutor for P7 Final Acceptance Closure - see that class's
// comment for why a real provider was introduced rather than fabricating this decision.
@Component("qualityCheckExecutor")
public class QualityCheckExecutor implements ComponentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(QualityCheckExecutor.class);

    public static final String AGENT_TYPE = "quality-check";
    public static final String AGENT_NAME = "quality-check-agent-01";
    public static final String EXECUTOR_NAME = "QualityCheckExecutor";

    private static final Set<String> DISQUALIFYING_STATUSES = Set.of("DEFECTIVE", "REJECTED", "FAILED");

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
        Object forceFailureVar = execution.getVariable("forceQualityFailure");
        Object orderStatusVar = execution.getVariable("orderStatus");

        boolean qualityPassed;
        String message;

        if (Boolean.TRUE.equals(forceFailureVar)) {
            qualityPassed = false;
            message = String.format("Quality check failed for activity %s: forced quality failure flag is set",
                    activityId);
        } else if (orderStatusVar != null && DISQUALIFYING_STATUSES.contains(orderStatusVar.toString().toUpperCase())) {
            qualityPassed = false;
            message = String.format("Quality check failed for activity %s: order status '%s' indicates a defect",
                    activityId, orderStatusVar);
        } else {
            qualityPassed = true;
            message = String.format(
                    "Quality check passed for activity %s: no disqualifying condition found in available order data",
                    activityId);
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("qualityPassed", qualityPassed);
        outputs.put("qualityMessage", message);

        logger.info("{} executed for activity {} on instance {} (agent: {}) with qualityPassed={}",
                EXECUTOR_NAME, activityId, execution.getProcessInstanceId(), agentName, qualityPassed);

        String summary = String.format("%s executed for %s: qualityPassed=%b", EXECUTOR_NAME, activityId,
                qualityPassed);

        return new AutomationResult(summary, outputs);
    }
}
