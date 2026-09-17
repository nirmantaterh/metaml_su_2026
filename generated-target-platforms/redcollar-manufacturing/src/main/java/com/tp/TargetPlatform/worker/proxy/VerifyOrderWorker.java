package com.tp.TargetPlatform.worker.proxy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.capability.runtime.CapabilityDispatcher;
import com.metaml.workbench.capability.runtime.ExternalTaskExecutionContext;

import com.tp.TargetPlatform.worker.GeneratedExternalTaskWorker;

// Generated for external-task topic "VerifyOrder" (BPMN activity "Verify Order Details").
@Component
public class VerifyOrderWorker implements GeneratedExternalTaskWorker {

    private static final Logger logger = LoggerFactory.getLogger(VerifyOrderWorker.class);
    private static final String ACTIVITY_ID = "_BF94795D-C812-42FC-987C-536FFF9BFDB1";

    private final ObjectProvider<CapabilityDispatcher> capabilityDispatcherProvider;
    private final ObjectProvider<GatewayOutputProvider> outputProvider;
    private final RuntimeService runtimeService;

    public VerifyOrderWorker(ObjectProvider<CapabilityDispatcher> capabilityDispatcherProvider,
            ObjectProvider<GatewayOutputProvider> outputProvider, RuntimeService runtimeService) {
        this.capabilityDispatcherProvider = capabilityDispatcherProvider;
        this.outputProvider = outputProvider;
        this.runtimeService = runtimeService;
    }

    @Override
    public String topic() {
        return "VerifyOrder";
    }

    @Override
    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
        logger.info("Executing generated external-task worker for activity \"Verify Order Details\" "
                + "(process instance {})", task.getProcessInstanceId());
        Map<String, Object> variables = new HashMap<>();
        boolean satisfiedByCapability = false;
        CapabilityDispatcher dispatcher = capabilityDispatcherProvider.getIfAvailable();
        if (dispatcher != null) {
            ExternalTaskExecutionContext context =
                    new ExternalTaskExecutionContext(task, runtimeService);
            Optional<AutomationResult> dispatched = dispatcher.dispatch(context, ACTIVITY_ID, null);
            if (dispatched.isPresent()) {
                logger.info("Capability-dispatched activity \"Verify Order Details\" (process instance {})",
                        task.getProcessInstanceId());
                variables.putAll(context.completionVariables());
                satisfiedByCapability = true;
            }
        }
        if (!satisfiedByCapability) {
            GatewayOutputProvider provider = outputProvider.getIfAvailable();
            if (provider != null) {
                logger.info("Invoking registered GatewayOutputProvider {} for activity \"Verify Order Details\" "
                        + "(process instance {})", provider.getClass().getSimpleName(),
                        task.getProcessInstanceId());
                Map<String, Object> provided = provider.provide("VerifyOrder", task);
                if (provided != null) {
                    variables.putAll(provided);
                }
            }
        }
            if (!variables.containsKey("orderApproved")) {
                throw new IllegalStateException("Gateway variable 'orderApproved' must be set by a legitimate producer — bind a capability provider to this activity, or register a @Component implementing GatewayOutputProvider for topic 'VerifyOrder', or set it directly in this worker");
            }
        logger.info("Worker completion variables: {}", variables);
        externalTaskService.complete(task.getId(), "generated-worker", variables);
    }
}
