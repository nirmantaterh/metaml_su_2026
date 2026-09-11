package com.metaml.workbench.automation;

import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component("notifierExecutor")
public class NotifierExecutor implements ComponentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(NotifierExecutor.class);

    public static final String AGENT_TYPE = "notifier";
    public static final String AGENT_NAME = "notifier-agent-01";
    public static final String EXECUTOR_NAME = "NotifierExecutor";

    @Override
    public String getHandledAgentType() {
        return AGENT_TYPE;
    }

    @Override public Set<String> providedOutputNames() { return Set.of("notificationDispatched", "dispatchChannel"); }

    @Override
    public java.util.Set<String> getHandledAgentNames() {
        return java.util.Set.of(AGENT_NAME);
    }


    @Override
    public AutomationResult execute(CapabilityExecutionContext execution, String activityId,
            String agentName) {
        String channel = "EMAIL_COMPLIANCE_ALERTS";

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("notificationDispatched", true);
        outputs.put("dispatchChannel", channel);

        logger.info("{} executed for activity {} on instance {} (agent: {}) with channel={}",
                EXECUTOR_NAME, activityId, execution.getProcessInstanceId(), agentName, channel);

        String summary = String.format("%s executed for %s: dispatched=true, channel=%s",
                EXECUTOR_NAME, activityId, channel);

        return new AutomationResult(summary, outputs);
    }
}
