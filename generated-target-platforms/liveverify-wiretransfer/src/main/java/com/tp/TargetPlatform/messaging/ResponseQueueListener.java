package com.tp.TargetPlatform.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Consumes response messages to release proxy execution upon message delivery.
@Component
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class ResponseQueueListener {

    // No signal is shared between proxy and twin in this project's BPMNs, so there is
    // nothing to consume here.

}
