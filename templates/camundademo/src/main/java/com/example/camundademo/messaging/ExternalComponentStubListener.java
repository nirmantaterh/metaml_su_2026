package com.example.camundademo.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Stub listener simulating external worker responses for development and integration testing.
@Component
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class ExternalComponentStubListener {

    private static final Logger logger = LoggerFactory.getLogger(ExternalComponentStubListener.class);

    // Default stub outcomes for QC and machine allocation.
    private static final String STUB_QC_STATUS = "PASS";
    private static final String STUB_MACHINE_STATUS = "ACQUIRED";

    private final HarnessMessagePublisher publisher;

    public ExternalComponentStubListener(HarnessMessagePublisher publisher) {
        this.publisher = publisher;
    }

    // Responds to QC requests from the Twin.
    @RabbitListener(queues = MessagingTopology.GATEWAY_QC_REQUEST_QUEUE)
    public void onQcRequest(HarnessMessage message) {
        if (message == null || message.getActivityId() == null) {
            logger.error("[gateway stub] discarding malformed QC request: {}", message);
            return;
        }
        logger.info("[gateway stub] executing QC for activity '{}' (correlationId={}) - returning stub status {}",
                message.getActivityId(), message.getCorrelationId(), STUB_QC_STATUS);

        HarnessMessage response = message.reply(HarnessMessage.Type.QC_RESPONSE,
                MessagingTopology.COMPONENT_GATEWAY, MessagingTopology.COMPONENT_TWIN, STUB_QC_STATUS);
        publisher.publish(MessagingTopology.GATEWAY_EXCHANGE, MessagingTopology.GATEWAY_QC_RESPONSE_KEY,
                response);
    }

    // Responds to machine acquisition requests from Manufacturing.
    @RabbitListener(queues = MessagingTopology.MACHINES_REQUEST_QUEUE)
    public void onMachineRequest(HarnessMessage message) {
        if (message == null || message.getActivityId() == null) {
            logger.error("[machines stub] discarding malformed machine request: {}", message);
            return;
        }
        logger.info("[machines stub] acquiring machines for activity '{}' (correlationId={}) - returning {}",
                message.getActivityId(), message.getCorrelationId(), STUB_MACHINE_STATUS);

        HarnessMessage completion = message.reply(HarnessMessage.Type.MACHINE_COMPLETION,
                MessagingTopology.COMPONENT_MACHINES, MessagingTopology.COMPONENT_MANUFACTURING,
                STUB_MACHINE_STATUS);
        publisher.publish(MessagingTopology.MACHINES_EXCHANGE, MessagingTopology.MACHINES_COMPLETION_KEY,
                completion);
    }
}
