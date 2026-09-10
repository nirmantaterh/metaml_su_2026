package com.tp.TargetPlatform.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Publishes "twin has advanced past this signal" to that signal's dedicated response queue.
// ResponseQueueListener performs Camunda signal delivery upon message consumption.
// Uses synchronous publisher confirmation and persistent message delivery matching TaskQueuePublisher.
@Component
public class ResponseQueuePublisher {

    private static final Logger logger = LoggerFactory.getLogger(ResponseQueuePublisher.class);

    private static final long CONFIRM_TIMEOUT_MS = 5000L;

    private final RabbitTemplate rabbitTemplate;
    private final boolean enabled;

    public ResponseQueuePublisher(RabbitTemplate rabbitTemplate,
            @Value("${metaml.messaging.enabled:false}") boolean enabled) {
        this.rabbitTemplate = rabbitTemplate;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isEligible(String signalName) {
        return RabbitMqConfig.RESPONSE_QUEUE_BY_SIGNAL.containsKey(signalName);
    }

    public void publish(String signalName, String executionId, String processInstanceId,
            String businessKey) {
        String routingKey = RabbitMqConfig.RESPONSE_ROUTING_KEY_BY_SIGNAL.get(signalName);
        if (routingKey == null) {
            throw new IllegalArgumentException("No response queue is declared for signal '"
                    + signalName + "' - callers must check isEligible(signalName) first");
        }
        String payload = signalName + "|" + executionId + "|" + processInstanceId
                + "|" + (businessKey == null ? "" : businessKey);
        try {
            rabbitTemplate.invoke(operations -> {
                operations.convertAndSend(RabbitMqConfig.EXCHANGE, routingKey, payload,
                        message -> {
                            message.getMessageProperties()
                                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                            return message;
                        });
                operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
                return null;
            }, null, null);
        } catch (RuntimeException e) {
            logger.error("RESPONSE: publish NOT confirmed for signal '{}' execution {} "
                    + "(processInstanceId={}, businessKey={}): {}", signalName, executionId,
                    processInstanceId, businessKey, e.toString());
            throw e;
        }
        logger.info("RESPONSE: published signal '{}' to RabbitMQ exchange '{}' key '{}' for "
                + "execution {} (processInstanceId={}, businessKey={})", signalName,
                RabbitMqConfig.EXCHANGE, routingKey, executionId, processInstanceId, businessKey);
    }
}
