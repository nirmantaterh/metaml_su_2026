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

// Generated for external-task topic "SamplingTwin" (BPMN activity "Sampling Twin").
@Component
public class SamplingTwinWorker implements GeneratedExternalTaskWorker {

    private static final Logger logger = LoggerFactory.getLogger(SamplingTwinWorker.class);
    private static final String ACTIVITY_ID = "_0B2EFC2B-1BC9-41EF-9D86-EE32293D1605";
    private static final boolean REQUIRES_CAPABILITY_OUTPUT = false;

    private final ObjectProvider<TwinDecisionAgent> agentProvider;
    private final ObjectProvider<CapabilityDispatcher> capabilityDispatcherProvider;
    private final RuntimeService runtimeService;

    public SamplingTwinWorker(ObjectProvider<TwinDecisionAgent> agentProvider,
            ObjectProvider<CapabilityDispatcher> capabilityDispatcherProvider,
            RuntimeService runtimeService) {
        this.agentProvider = agentProvider;
        this.capabilityDispatcherProvider = capabilityDispatcherProvider;
        this.runtimeService = runtimeService;
    }

    @Override
    public String topic() {
        return "SamplingTwin";
    }

    @Override
    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
        CapabilityDispatcher dispatcher = capabilityDispatcherProvider.getIfAvailable();
        if (dispatcher != null) {
            ExternalTaskExecutionContext context =
                    new ExternalTaskExecutionContext(task, runtimeService);
            Optional<AutomationResult> dispatched = dispatcher.dispatch(context, ACTIVITY_ID, null);
            if (dispatched.isPresent()) {
                logger.info("[Twin] Capability-dispatched activity \"Sampling Twin\" "
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
            logger.info("[Twin] Invoking decision agent {} for activity \"Sampling Twin\" "
                    + "(process instance {})", agent.getClass().getSimpleName(), task.getProcessInstanceId());
            variables = new HashMap<>(agent.decide("SamplingTwin", task));
        } else {
            // No TwinDecisionAgent registered - built-in fallback.
            logger.info("[Twin] Invoking simulated ML agent for activity \"Sampling Twin\" "
                    + "(process instance {})", task.getProcessInstanceId());
            variables = new HashMap<>();
            variables.put("agentTopic", "SamplingTwin");
            variables.put("agentInvocationId", UUID.randomUUID().toString());
            variables.put("agentTimestamp", System.currentTimeMillis());
            logger.info("[Twin] Agent invocation result: {}", variables);
        }
        logger.info("[Twin] Completion variables: {}", variables);
        externalTaskService.complete(task.getId(), "generated-worker", variables);
    }
}
