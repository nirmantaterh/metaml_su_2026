package com.tp.TargetPlatform.messaging;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Consumes task messages from RabbitMQ to deliver Camunda signals releasing waiting twin executions.
@Component
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class TaskQueueListener {

    private static final Logger logger = LoggerFactory.getLogger(TaskQueueListener.class);

    private final RuntimeService runtimeService;

    public TaskQueueListener(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    // A malformed payload throws an exception to trigger configured listener retries and dead-letter routing to DLQ_TASKS_QUEUE.
    @RabbitListener(queues = { "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.sampling-signal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.laying-signal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.marking-signal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.cutting-signal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.stitching-signal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.checking-signal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.pressing-signal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.packaging-signal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.shipping-signal" })
    public void onTaskMessage(String payload) {
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 4) {
            logger.error("[task-queue] malformed message, routing to DLQ: {}", payload);
            throw new IllegalArgumentException(
                    "Malformed task-queue payload (expected 4 '|'-delimited fields): "
                            + payload);
        }
        String signalName = parts[0];
        String executionId = parts[1];
        String processInstanceId = parts[2];
        String businessKey = parts[3];
        try {
            runtimeService.signalEventReceived(signalName, executionId);
            logger.info("TASK: delivered signal '{}' to execution {} (processInstanceId={}, "
                    + "businessKey={}) via RabbitMQ", signalName, executionId,
                    processInstanceId, businessKey);
        } catch (ProcessEngineException e) {
            // Distinguishes expected advancement states (execution already advanced or already completed) from unexpected engine failures, which are rethrown for retry handling.
            if (isAlreadyAdvanced(e)) {
                logger.info("TASK: signal '{}' delivery to execution {} skipped - "
                        + "already advanced past this signal (processInstanceId={}, "
                        + "businessKey={}): {}", signalName, executionId, processInstanceId,
                        businessKey, e.toString());
            } else {
                logger.error("TASK: signal '{}' delivery to execution {} FAILED "
                        + "(processInstanceId={}, businessKey={}): {}", signalName,
                        executionId, processInstanceId, businessKey, e.toString());
                throw e;
            }
        }
    }

    private static boolean isAlreadyAdvanced(ProcessEngineException e) {
        String message = e.getMessage();
        return message != null && (message.contains("has not subscribed")
                || message.contains("Cannot find execution"));
    }

}
