package com.example.camundademo.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.camundademo.messaging.HarnessMessage;
import com.example.camundademo.messaging.HarnessMessagePublisher;
import com.example.camundademo.messaging.MessagingTopology;

// Bridges process activity completion events to RabbitMQ messaging peers.
@Component
public class NotificationBridge {

    private static final Logger logger = LoggerFactory.getLogger(NotificationBridge.class);

    private final HarnessMessagePublisher publisher;

    public NotificationBridge(HarnessMessagePublisher publisher) {
        this.publisher = publisher;
    }

    // Notifies the twin of activity completion, initiating an end-to-end correlation ID.
    public void notifyTwin(String processInstanceId, String activityId) {
        logger.info("[manufacturing -> twin] activity '{}' complete - notifying twin", activityId);
        HarnessMessage message = HarnessMessage.request(HarnessMessage.Type.STAGE_UPDATE_REQUEST,
                MessagingTopology.COMPONENT_MANUFACTURING, MessagingTopology.COMPONENT_TWIN,
                processInstanceId, activityId);
        publisher.publish(MessagingTopology.TWIN_EXCHANGE, MessagingTopology.TWIN_STAGE_UPDATE_KEY,
                message);
    }

    // Notifies manufacturing when a twin-side activity completes.
    public void notifyManufacturing(String processInstanceId, String activityId) {
        logger.info("[twin -> manufacturing] activity '{}' complete - notifying manufacturing", activityId);
        HarnessMessage message = HarnessMessage.request(HarnessMessage.Type.STAGE_UPDATE_RESPONSE,
                MessagingTopology.COMPONENT_TWIN, MessagingTopology.COMPONENT_MANUFACTURING,
                processInstanceId, activityId);
        publisher.publish(MessagingTopology.TWIN_EXCHANGE, MessagingTopology.TWIN_STAGE_RESPONSE_KEY,
                message);
    }

    // Requests machine resource allocation for an upcoming manufacturing activity.
    public void requestMachines(String processInstanceId, String activityId) {
        logger.info("[manufacturing -> machines] requesting machines for activity '{}'", activityId);
        HarnessMessage message = HarnessMessage.request(HarnessMessage.Type.MACHINE_REQUEST,
                MessagingTopology.COMPONENT_MANUFACTURING, MessagingTopology.COMPONENT_MACHINES,
                processInstanceId, activityId);
        publisher.publish(MessagingTopology.MACHINES_EXCHANGE, MessagingTopology.MACHINES_REQUEST_KEY,
                message);
    }
}
