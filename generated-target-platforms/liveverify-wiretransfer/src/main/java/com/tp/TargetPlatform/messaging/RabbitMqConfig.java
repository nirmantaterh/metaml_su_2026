package com.tp.TargetPlatform.messaging;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Configures RabbitMQ exchange, queues, DLX routing, and publisher confirms for synchronization.
@Configuration
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class RabbitMqConfig {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMqConfig.class);

    public static final String EXCHANGE = "processwiretransfer.69ca0a2a-087d-4021-82d5-90183837c987.exchange";

    // Shared signal name -> its dedicated task queue (proxy asks twin to advance past this signal). The single source of truth for which signals have RabbitMQ queues at all - a signal absent from this map exists on only one side and is delivered directly instead (see SignalBroadcaster.deliverTo).
    public static final Map<String, String> TASK_QUEUE_BY_SIGNAL = Map.ofEntries(

    );

    // Shared signal name -> its dedicated response queue (twin reports it advanced).
    public static final Map<String, String> RESPONSE_QUEUE_BY_SIGNAL = Map.ofEntries(

    );

    public static final Map<String, String> TASK_ROUTING_KEY_BY_SIGNAL = Map.ofEntries(

    );

    public static final Map<String, String> RESPONSE_ROUTING_KEY_BY_SIGNAL = Map.ofEntries(

    );

    // mandatory=true is what makes the broker return (rather than silently drop) a message this exchange/routing-key combination cannot route to any queue - shouldn't happen with this project's own fixed topology, but a returned message is NOT the same failure a publisher confirm NACK catches (a NACK is the broker failing to accept the message at all; a return is the broker accepting it and then finding nowhere to route it), so both are wired here for the same reason: neither must fail silently.
    public RabbitMqConfig(RabbitTemplate rabbitTemplate) {
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(returned -> logger.error(
                "SYNC MESSAGE RETURNED (unroutable): exchange={} routingKey={} replyCode={} "
                        + "replyText={} payload={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(),
                returned.getReplyText(), new String(returned.getMessage().getBody(),
                        java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Bean
    public DirectExchange syncExchange() {
        return new DirectExchange(EXCHANGE);
    }

}
