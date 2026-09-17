package com.tp.TargetPlatform.worker.twin;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.capability.runtime.CapabilityBindingRequiredException;
import com.metaml.workbench.capability.runtime.CapabilityDispatcher;
import com.metaml.workbench.capability.runtime.ExternalTaskExecutionContext;

import com.tp.TargetPlatform.worker.GeneratedExternalTaskWorker;

// Generated for external-task topic "CheckingTwin" (BPMN activity "Checking Twin").
@Component
public class CheckingTwinWorker implements GeneratedExternalTaskWorker {

    private static final Logger logger = LoggerFactory.getLogger(CheckingTwinWorker.class);
    private static final String ACTIVITY_ID = "_D5B445AC-2B9F-46DA-9E66-15613EE4213B";
    private static final boolean REQUIRES_CAPABILITY_OUTPUT = false;

    private final ObjectProvider<TwinDecisionAgent> agentProvider;
    private final ObjectProvider<CapabilityDispatcher> capabilityDispatcherProvider;
    private final RuntimeService runtimeService;

    public CheckingTwinWorker(ObjectProvider<TwinDecisionAgent> agentProvider,
            ObjectProvider<CapabilityDispatcher> capabilityDispatcherProvider,
            RuntimeService runtimeService) {
        this.agentProvider = agentProvider;
        this.capabilityDispatcherProvider = capabilityDispatcherProvider;
        this.runtimeService = runtimeService;
    }

    @Override
    public String topic() {
        return "CheckingTwin";
    }

    @Override
    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
        CapabilityDispatcher dispatcher = capabilityDispatcherProvider.getIfAvailable();
        if (dispatcher != null) {
            ExternalTaskExecutionContext context =
                    new ExternalTaskExecutionContext(task, runtimeService);
            Optional<AutomationResult> dispatched = dispatcher.dispatch(context, ACTIVITY_ID, null);
            if (dispatched.isPresent()) {
                logger.info("[Twin] Capability-dispatched activity \"Checking Twin\" "
                        + "(process instance {})", task.getProcessInstanceId());
                externalTaskService.complete(task.getId(), "generated-worker",
                        context.completionVariables());
                return;
            }
            if (REQUIRES_CAPABILITY_OUTPUT) {
                throw new CapabilityBindingRequiredException(ACTIVITY_ID);
            }
        }
        TwinDecisionAgent agent = agentProvider.getIfAvailable();
        Map<String, Object> variables;
        if (agent != null) {
            logger.info("[Twin] Invoking decision agent {} for activity \"Checking Twin\" "
                    + "(process instance {})", agent.getClass().getSimpleName(), task.getProcessInstanceId());
            variables = new HashMap<>(agent.decide("CheckingTwin", task));
        } else {
            // No TwinDecisionAgent registered - built-in fallback.
            logger.info("[Twin] Invoking simulated ML agent for activity \"Checking Twin\" "
                    + "(process instance {})", task.getProcessInstanceId());
            variables = new HashMap<>();
            variables.put("agentTopic", "CheckingTwin");
            variables.put("agentInvocationId", UUID.randomUUID().toString());
            variables.put("agentTimestamp", System.currentTimeMillis());
            logger.info("[Twin] Agent invocation result: {}", variables);
        }
        logger.info("[Twin] Completion variables: {}", variables);
        externalTaskService.complete(task.getId(), "generated-worker", variables);
    }
}
