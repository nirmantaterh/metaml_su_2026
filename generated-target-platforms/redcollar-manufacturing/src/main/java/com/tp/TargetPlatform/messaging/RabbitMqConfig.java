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

// Configures RabbitMQ topology, dead-letter routing, and confirms for proxy-twin synchronization.
@Configuration
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class RabbitMqConfig {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMqConfig.class);

    public static final String EXCHANGE = "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.exchange";

    public static final String DLX_EXCHANGE = "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.dlx";
    public static final String DLQ_TASKS_QUEUE = "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.dlq.tasks";
    public static final String DLQ_RESPONSES_QUEUE = "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.dlq.responses";
    public static final String DLQ_TASKS_ROUTING_KEY = "dlq.tasks";
    public static final String DLQ_RESPONSES_ROUTING_KEY = "dlq.responses";

    // Shared signal name -> its dedicated task queue (proxy asks twin to advance past this signal). The single source of truth for which signals have RabbitMQ queues at all - a signal absent from this map exists on only one side and is delivered directly instead (see SignalBroadcaster.deliverTo).
    public static final Map<String, String> TASK_QUEUE_BY_SIGNAL = Map.ofEntries(
            Map.entry("samplingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.sampling-signal"),
            Map.entry("layingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.laying-signal"),
            Map.entry("markingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.marking-signal"),
            Map.entry("cuttingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.cutting-signal"),
            Map.entry("stitchingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.stitching-signal"),
            Map.entry("checkingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.checking-signal"),
            Map.entry("pressingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.pressing-signal"),
            Map.entry("packagingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.packaging-signal"),
            Map.entry("shippingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.shipping-signal")
    );

    // Shared signal name -> its dedicated response queue (twin reports it advanced).
    public static final Map<String, String> RESPONSE_QUEUE_BY_SIGNAL = Map.ofEntries(
            Map.entry("samplingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.sampling-signal"),
            Map.entry("layingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.laying-signal"),
            Map.entry("markingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.marking-signal"),
            Map.entry("cuttingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.cutting-signal"),
            Map.entry("stitchingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.stitching-signal"),
            Map.entry("checkingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.checking-signal"),
            Map.entry("pressingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.pressing-signal"),
            Map.entry("packagingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.packaging-signal"),
            Map.entry("shippingSignal", "redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.shipping-signal")
    );

    public static final Map<String, String> TASK_ROUTING_KEY_BY_SIGNAL = Map.ofEntries(
            Map.entry("samplingSignal", "sync.sampling-signal"),
            Map.entry("layingSignal", "sync.laying-signal"),
            Map.entry("markingSignal", "sync.marking-signal"),
            Map.entry("cuttingSignal", "sync.cutting-signal"),
            Map.entry("stitchingSignal", "sync.stitching-signal"),
            Map.entry("checkingSignal", "sync.checking-signal"),
            Map.entry("pressingSignal", "sync.pressing-signal"),
            Map.entry("packagingSignal", "sync.packaging-signal"),
            Map.entry("shippingSignal", "sync.shipping-signal")
    );

    public static final Map<String, String> RESPONSE_ROUTING_KEY_BY_SIGNAL = Map.ofEntries(
            Map.entry("samplingSignal", "sync.responses.sampling-signal"),
            Map.entry("layingSignal", "sync.responses.laying-signal"),
            Map.entry("markingSignal", "sync.responses.marking-signal"),
            Map.entry("cuttingSignal", "sync.responses.cutting-signal"),
            Map.entry("stitchingSignal", "sync.responses.stitching-signal"),
            Map.entry("checkingSignal", "sync.responses.checking-signal"),
            Map.entry("pressingSignal", "sync.responses.pressing-signal"),
            Map.entry("packagingSignal", "sync.responses.packaging-signal"),
            Map.entry("shippingSignal", "sync.responses.shipping-signal")
    );

    // mandatory=true ensures unroutable messages trigger returnsCallback rather than being silently dropped.
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

@Bean
public Queue q0_sampling_signalTaskQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.sampling-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_TASKS_ROUTING_KEY)
            .build();
}

@Bean
public Binding q0_sampling_signalTaskBinding() {
    return BindingBuilder.bind(q0_sampling_signalTaskQueue()).to(syncExchange()).with("sync.sampling-signal");
}

@Bean
public Queue q0_sampling_signalResponseQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.sampling-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_RESPONSES_ROUTING_KEY)
            .build();
}

@Bean
public Binding q0_sampling_signalResponseBinding() {
    return BindingBuilder.bind(q0_sampling_signalResponseQueue()).to(syncExchange()).with("sync.responses.sampling-signal");
}

@Bean
public Queue q1_laying_signalTaskQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.laying-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_TASKS_ROUTING_KEY)
            .build();
}

@Bean
public Binding q1_laying_signalTaskBinding() {
    return BindingBuilder.bind(q1_laying_signalTaskQueue()).to(syncExchange()).with("sync.laying-signal");
}

@Bean
public Queue q1_laying_signalResponseQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.laying-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_RESPONSES_ROUTING_KEY)
            .build();
}

@Bean
public Binding q1_laying_signalResponseBinding() {
    return BindingBuilder.bind(q1_laying_signalResponseQueue()).to(syncExchange()).with("sync.responses.laying-signal");
}

@Bean
public Queue q2_marking_signalTaskQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.marking-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_TASKS_ROUTING_KEY)
            .build();
}

@Bean
public Binding q2_marking_signalTaskBinding() {
    return BindingBuilder.bind(q2_marking_signalTaskQueue()).to(syncExchange()).with("sync.marking-signal");
}

@Bean
public Queue q2_marking_signalResponseQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.marking-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_RESPONSES_ROUTING_KEY)
            .build();
}

@Bean
public Binding q2_marking_signalResponseBinding() {
    return BindingBuilder.bind(q2_marking_signalResponseQueue()).to(syncExchange()).with("sync.responses.marking-signal");
}

@Bean
public Queue q3_cutting_signalTaskQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.cutting-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_TASKS_ROUTING_KEY)
            .build();
}

@Bean
public Binding q3_cutting_signalTaskBinding() {
    return BindingBuilder.bind(q3_cutting_signalTaskQueue()).to(syncExchange()).with("sync.cutting-signal");
}

@Bean
public Queue q3_cutting_signalResponseQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.cutting-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_RESPONSES_ROUTING_KEY)
            .build();
}

@Bean
public Binding q3_cutting_signalResponseBinding() {
    return BindingBuilder.bind(q3_cutting_signalResponseQueue()).to(syncExchange()).with("sync.responses.cutting-signal");
}

@Bean
public Queue q4_stitching_signalTaskQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.stitching-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_TASKS_ROUTING_KEY)
            .build();
}

@Bean
public Binding q4_stitching_signalTaskBinding() {
    return BindingBuilder.bind(q4_stitching_signalTaskQueue()).to(syncExchange()).with("sync.stitching-signal");
}

@Bean
public Queue q4_stitching_signalResponseQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.stitching-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_RESPONSES_ROUTING_KEY)
            .build();
}

@Bean
public Binding q4_stitching_signalResponseBinding() {
    return BindingBuilder.bind(q4_stitching_signalResponseQueue()).to(syncExchange()).with("sync.responses.stitching-signal");
}

@Bean
public Queue q5_checking_signalTaskQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.checking-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_TASKS_ROUTING_KEY)
            .build();
}

@Bean
public Binding q5_checking_signalTaskBinding() {
    return BindingBuilder.bind(q5_checking_signalTaskQueue()).to(syncExchange()).with("sync.checking-signal");
}

@Bean
public Queue q5_checking_signalResponseQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.checking-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_RESPONSES_ROUTING_KEY)
            .build();
}

@Bean
public Binding q5_checking_signalResponseBinding() {
    return BindingBuilder.bind(q5_checking_signalResponseQueue()).to(syncExchange()).with("sync.responses.checking-signal");
}

@Bean
public Queue q6_pressing_signalTaskQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.pressing-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_TASKS_ROUTING_KEY)
            .build();
}

@Bean
public Binding q6_pressing_signalTaskBinding() {
    return BindingBuilder.bind(q6_pressing_signalTaskQueue()).to(syncExchange()).with("sync.pressing-signal");
}

@Bean
public Queue q6_pressing_signalResponseQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.pressing-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_RESPONSES_ROUTING_KEY)
            .build();
}

@Bean
public Binding q6_pressing_signalResponseBinding() {
    return BindingBuilder.bind(q6_pressing_signalResponseQueue()).to(syncExchange()).with("sync.responses.pressing-signal");
}

@Bean
public Queue q7_packaging_signalTaskQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.packaging-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_TASKS_ROUTING_KEY)
            .build();
}

@Bean
public Binding q7_packaging_signalTaskBinding() {
    return BindingBuilder.bind(q7_packaging_signalTaskQueue()).to(syncExchange()).with("sync.packaging-signal");
}

@Bean
public Queue q7_packaging_signalResponseQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.packaging-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_RESPONSES_ROUTING_KEY)
            .build();
}

@Bean
public Binding q7_packaging_signalResponseBinding() {
    return BindingBuilder.bind(q7_packaging_signalResponseQueue()).to(syncExchange()).with("sync.responses.packaging-signal");
}

@Bean
public Queue q8_shipping_signalTaskQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.shipping-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_TASKS_ROUTING_KEY)
            .build();
}

@Bean
public Binding q8_shipping_signalTaskBinding() {
    return BindingBuilder.bind(q8_shipping_signalTaskQueue()).to(syncExchange()).with("sync.shipping-signal");
}

@Bean
public Queue q8_shipping_signalResponseQueue() {
    return QueueBuilder.durable("redcollarmanuf.e1d5e524-5a61-47b7-801e-f73d90f9cd04.sync.responses.shipping-signal")
            .withArgument("x-queue-type", "quorum")
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DLQ_RESPONSES_ROUTING_KEY)
            .build();
}

@Bean
public Binding q8_shipping_signalResponseBinding() {
    return BindingBuilder.bind(q8_shipping_signalResponseQueue()).to(syncExchange()).with("sync.responses.shipping-signal");
}

@Bean
public DirectExchange syncDlx() {
    return new DirectExchange(DLX_EXCHANGE);
}

@Bean
public Queue syncDlqTasks() {
    return QueueBuilder.durable(DLQ_TASKS_QUEUE)
            .withArgument("x-queue-type", "quorum")
            .build();
}

@Bean
public Binding syncDlqTasksBinding() {
    return BindingBuilder.bind(syncDlqTasks()).to(syncDlx()).with(DLQ_TASKS_ROUTING_KEY);
}

@Bean
public Queue syncDlqResponses() {
    return QueueBuilder.durable(DLQ_RESPONSES_QUEUE)
            .withArgument("x-queue-type", "quorum")
            .build();
}

@Bean
public Binding syncDlqResponsesBinding() {
    return BindingBuilder.bind(syncDlqResponses()).to(syncDlx()).with(DLQ_RESPONSES_ROUTING_KEY);
}

}
