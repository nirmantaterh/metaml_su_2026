package com.metaml.workbench.automation;

import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component("recommenderExecutor")
public class RecommenderExecutor implements ComponentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(RecommenderExecutor.class);

    public static final String AGENT_TYPE = "recommender";
    public static final String AGENT_NAME = "recommender-agent-01";
    public static final String EXECUTOR_NAME = "RecommenderExecutor";

    @Override
    public String getHandledAgentType() {
        return AGENT_TYPE;
    }

    @Override
    public java.util.Set<String> getHandledAgentNames() {
        return java.util.Set.of(AGENT_NAME);
    }


    @Override
    public AutomationResult execute(CapabilityExecutionContext execution, String activityId,
            String agentName) {
        String nextAction = "APPROVE_TRANSACTION_WITH_MONITORING";

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("recommendedAction", nextAction);
        outputs.put("confidenceScore", 0.94);

        logger.info("{} executed for activity {} on instance {} (agent: {}) with nextAction={}",
                EXECUTOR_NAME, activityId, execution.getProcessInstanceId(), agentName, nextAction);

        String summary = String.format("%s executed for %s: action=%s",
                EXECUTOR_NAME, activityId, nextAction);

        return new AutomationResult(summary, outputs);
    }
}
