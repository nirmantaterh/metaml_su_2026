package com.tp.TargetPlatform.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Consumes project-scoped dead-letter queues to log unprocessable task and response messages.
@Component
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class DeadLetterQueueListener {

    private static final Logger logger = LoggerFactory.getLogger(DeadLetterQueueListener.class);

    @RabbitListener(queues = { RabbitMqConfig.DLQ_TASKS_QUEUE })
    public void onDeadLetteredTask(String payload) {
        logger.error("DEAD-LETTERED TASK message (unprocessable after retries): {}", payload);
    }

    @RabbitListener(queues = { RabbitMqConfig.DLQ_RESPONSES_QUEUE })
    public void onDeadLetteredResponse(String payload) {
        logger.error("DEAD-LETTERED RESPONSE message (unprocessable after retries): {}",
                payload);
    }
}
