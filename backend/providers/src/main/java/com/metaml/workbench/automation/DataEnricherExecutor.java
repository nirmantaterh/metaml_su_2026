package com.metaml.workbench.automation;

import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component("dataEnricherExecutor")
public class DataEnricherExecutor implements ComponentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(DataEnricherExecutor.class);

    public static final String AGENT_TYPE = "data-enricher";
    public static final String AGENT_NAME = "data-enricher-agent-01";
    public static final String EXECUTOR_NAME = "DataEnricherExecutor";

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
        String tier = "PREMIUM_CORPORATE";
        String region = "US_EAST";

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("enrichedTier", tier);
        outputs.put("geoRegion", region);
        outputs.put("enrichmentVerified", true);

        logger.info("{} executed for activity {} on instance {} (agent: {}) with enrichedTier={}, geoRegion={}",
                EXECUTOR_NAME, activityId, execution.getProcessInstanceId(), agentName, tier, region);

        String summary = String.format("%s executed for %s: enrichedTier=%s, geoRegion=%s",
                EXECUTOR_NAME, activityId, tier, region);

        return new AutomationResult(summary, outputs);
    }
}
