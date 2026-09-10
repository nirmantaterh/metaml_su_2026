package com.metaml.workbench.automation;

import com.metaml.workbench.capability.runtime.DelegateExecutionContext;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.model.AgentVariables;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Dispatching automation service: routes to exact ComponentExecutor when an evolved agent is bound,
// fails closed if an evolved agent has no matching executor, and runs default timestamping only when unevolved.
@Service("default")
public class DefaultProjectAutomationService implements ProjectAutomationService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultProjectAutomationService.class);

    static final String RAN_AT_OUTPUT = "ranAt";

    private final List<ComponentExecutor> executors;

    public DefaultProjectAutomationService(List<ComponentExecutor> executors) {
        this.executors = executors != null ? executors : new ArrayList<>();
    }

    @Override
    public AutomationResult execute(DelegateExecution execution) {
        Instant ranAt = Instant.now();
        // getCurrentActivityId() is the automation task's id; variables are keyed off the receive task's id
        String activityId = TwinModelGenerator.synchronizationActivityIdOf(execution.getCurrentActivityId());

        Object agent = execution.getVariable(AgentVariables.evolvedAgent(activityId,
                execution.getVariable("loopCounter")));

        // If an evolved agent is bound, resolve and invoke its exact component executor
        if (agent != null && !agent.toString().isBlank()) {
            String agentNameOrType = agent.toString();
            ComponentExecutor matching = findExecutor(agentNameOrType);

            // Multi-instance parallel activities: each sibling gets a distinct agent name
            // from the catalog but they all share one type that maps to one ComponentExecutor.
            // Fall back to type-level dispatch when no executor handles the specific name.
            if (matching == null) {
                Object agentTypeVar = execution.getVariable(AgentVariables.evolvedAgentType(activityId,
                        execution.getVariable("loopCounter")));
                if (agentTypeVar != null && !agentTypeVar.toString().isBlank()) {
                    matching = findExecutor(agentTypeVar.toString());
                }
            }

            if (matching != null) {
                logger.info("Twin activity {} on instance {} dispatching to exact executor {} for agent '{}'",
                        activityId, execution.getProcessInstanceId(), matching.getClass().getSimpleName(), agentNameOrType);
                return matching.execute(new DelegateExecutionContext(execution), activityId, agentNameOrType);
            }

            // Fail closed: an evolved agent was explicitly assigned but has no executor
            logger.error("No ComponentExecutor found for evolved agent '{}' on activity {} (instance {})",
                    agentNameOrType, activityId, execution.getProcessInstanceId());
            throw new IllegalStateException("No ComponentExecutor found for evolved agent '" + agentNameOrType
                    + "' on activity " + activityId);
        }

        // Backward-compatible default automation when no agent is evolved
        logger.info("Twin activity {} on instance {} ran the default automation at {} (agent: none)",
                activityId, execution.getProcessInstanceId(), ranAt);

        return new AutomationResult("default automation ran at " + ranAt,
                Map.of(RAN_AT_OUTPUT, ranAt.toString()));
    }

    private ComponentExecutor findExecutor(String agentNameOrType) {
        ComponentExecutor resolved = null;
        for (ComponentExecutor executor : executors) {
            if (executor.handles(agentNameOrType)) {
                if (resolved != null) {
                    throw new IllegalStateException("Ambiguous executor resolution: multiple executors ("
                            + resolved.getClass().getSimpleName() + ", " + executor.getClass().getSimpleName()
                            + ") claim agent identity '" + agentNameOrType + "'");
                }
                resolved = executor;
            }
        }
        return resolved;
    }
}
