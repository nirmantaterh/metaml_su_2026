package com.tp.TargetPlatform.messaging;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// The real consumer for response messages - the Camunda signal delivery that releases proxy's waiting execution happens here, triggered by consuming the message.
@Component
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class ResponseQueueListener {

    private static final Logger logger = LoggerFactory.getLogger(ResponseQueueListener.class);

    private final RuntimeService runtimeService;

    public ResponseQueueListener(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }
    // Validates message payload and routes to DLQ on failure, handling idempotent delivery states consistently with TaskQueueListener.
    @RabbitListener(queues = { "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.responses.sampling-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.responses.laying-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.responses.marking-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.responses.cutting-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.responses.stitching-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.responses.checking-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.responses.pressing-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.responses.packaging-signal", "redcollarmanuf.996d36c2-286a-419a-b7c5-68d9a3808b3c.sync.responses.shipping-signal" })
    public void onResponseMessage(String payload) {
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 4) {
            logger.error("[response-queue] malformed message, routing to DLQ: {}", payload);
            throw new IllegalArgumentException(
                    "Malformed response-queue payload (expected 4 '|'-delimited fields): "
                            + payload);
        }
        String signalName = parts[0];
        String executionId = parts[1];
        String processInstanceId = parts[2];
        String businessKey = parts[3];
        try {
            runtimeService.signalEventReceived(signalName, executionId);
            logger.info("RESPONSE: delivered signal '{}' to execution {} "
                    + "(processInstanceId={}, businessKey={}) via RabbitMQ", signalName,
                    executionId, processInstanceId, businessKey);
        } catch (ProcessEngineException e) {
            // See TaskQueueListener's own comment on isAlreadyAdvanced - identical reasoning, applied to the proxy's execution instead of the twin's.
            if (isAlreadyAdvanced(e)) {
                logger.info("RESPONSE: signal '{}' delivery to execution {} skipped - "
                        + "already advanced past this signal (processInstanceId={}, "
                        + "businessKey={}): {}", signalName, executionId, processInstanceId,
                        businessKey, e.toString());
            } else {
                logger.error("RESPONSE: signal '{}' delivery to execution {} FAILED "
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
