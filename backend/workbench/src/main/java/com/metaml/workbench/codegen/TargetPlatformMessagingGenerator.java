package com.metaml.workbench.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

// Generates RabbitMQ synchronization layer for Proxy and Twin handoffs across Target Platforms.
@Component
public class TargetPlatformMessagingGenerator {

    public record GeneratedSource(String relativeDirectory, String className, String source) { }

    // messagingNamespace isolates AMQP queues across projects with identical signal names.
    // Shared signals receive queue pairs, while unilateral signals deliver directly.
    public List<GeneratedSource> generate(String messagingNamespace, Set<String> sharedSignalNames,
            Set<String> allSignalNames, String proxyProcessKey, String twinProcessKey) {
        List<GeneratedSource> sources = new ArrayList<>();
        sources.add(pairRegistry());
        sources.addAll(messaging(messagingNamespace, sharedSignalNames));
        sources.add(signalBroadcaster(allSignalNames));
        sources.add(proxyController(proxyProcessKey));
        sources.add(twinController(twinProcessKey));
        return sources;
    }

    private static String escapeJavaStringLiteral(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // Kebab-case queue naming convention for RabbitMQ queue identifier sanitization.
    private static String slug(String raw) {
        String withHyphens = raw
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
                .toLowerCase();
        String cleaned = withHyphens.replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        if (cleaned.isEmpty()) {
            cleaned = "signal";
        }
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    private GeneratedSource pairRegistry() {
        String source = """
                package com.tp.TargetPlatform.coordination;

                import java.util.concurrent.ConcurrentHashMap;
                import java.util.concurrent.ConcurrentMap;

                import org.springframework.stereotype.Component;

                // Pairs proxy and twin process instances by shared businessKey correlation identifier.
                @Component
                public class PairRegistry {

                    private final ConcurrentMap<String, String> initiators = new ConcurrentHashMap<>();
                    private final ConcurrentMap<String, String> responders = new ConcurrentHashMap<>();

                    // Classifies process instance as initiator (first) or responder (second) for the businessKey.
                    public String registerAndClassify(String businessKey, String processInstanceId) {
                        if (businessKey == null || businessKey.isBlank()) {
                            return null;
                        }
                        String initiator = initiators.putIfAbsent(businessKey, processInstanceId);
                        if (initiator == null || initiator.equals(processInstanceId)) {
                            return "initiator";
                        }
                        String responder = responders.putIfAbsent(businessKey, processInstanceId);
                        if (responder == null || responder.equals(processInstanceId)) {
                            return "responder";
                        }
                        return null;
                    }

                    // The other half of the pair for this business key, or null if unpaired.
                    public String partnerOf(String businessKey, String processInstanceId) {
                        if (businessKey == null || businessKey.isBlank()) {
                            return null;
                        }
                        String initiator = initiators.get(businessKey);
                        String responder = responders.get(businessKey);
                        if (processInstanceId.equals(initiator)) {
                            return responder;
                        }
                        if (processInstanceId.equals(responder)) {
                            return initiator;
                        }
                        return null;
                    }

                    public String roleOf(String businessKey, String processInstanceId) {
                        if (businessKey == null || businessKey.isBlank()) {
                            return null;
                        }
                        if (processInstanceId.equals(initiators.get(businessKey))) {
                            return "initiator";
                        }
                        if (processInstanceId.equals(responders.get(businessKey))) {
                            return "responder";
                        }
                        return null;
                    }
                }
                """;
        return new GeneratedSource("coordination", "PairRegistry", source);
    }

    private record SignalQueue(String signal, String taskQueue, String taskRoutingKey, String responseQueue,
            String responseRoutingKey, String javaIdentifier) { }

    private static List<SignalQueue> assignSignalQueues(String messagingNamespace, Set<String> sharedSignalNames) {
        List<SignalQueue> queues = new ArrayList<>();
        java.util.Set<String> usedSlugs = new java.util.HashSet<>();
        int index = 0;
        for (String signal : sharedSignalNames) {
            String base = slug(signal);
            String candidate = base;
            int suffix = 2;
            while (!usedSlugs.add(candidate)) {
                candidate = base + "-" + suffix++;
            }
            String taskQueue = messagingNamespace + ".sync." + candidate;
            String responseQueue = messagingNamespace + ".sync.responses." + candidate;
            queues.add(new SignalQueue(signal, taskQueue, "sync." + candidate, responseQueue,
                    "sync.responses." + candidate, "q" + (index++) + "_" + candidate.replace('-', '_')));
        }
        return queues;
    }

    private List<GeneratedSource> messaging(String messagingNamespace, Set<String> sharedSignalNames) {
        List<SignalQueue> queues = assignSignalQueues(messagingNamespace, sharedSignalNames);
        String exchangeName = messagingNamespace + ".exchange";
        boolean hasQueuesForDlq = !queues.isEmpty();
        String dlxExchangeName = messagingNamespace + ".dlx";
        String dlqTasksQueueName = messagingNamespace + ".sync.dlq.tasks";
        String dlqResponsesQueueName = messagingNamespace + ".sync.dlq.responses";
        String dlqTasksRoutingKey = "dlq.tasks";
        String dlqResponsesRoutingKey = "dlq.responses";

        String taskEntries = queues.stream()
                .map(q -> "Map.entry(\"" + escapeJavaStringLiteral(q.signal()) + "\", \"" + q.taskQueue() + "\")")
                .collect(Collectors.joining(",\n            "));
        String responseEntries = queues.stream()
                .map(q -> "Map.entry(\"" + escapeJavaStringLiteral(q.signal()) + "\", \"" + q.responseQueue() + "\")")
                .collect(Collectors.joining(",\n            "));
        String taskRoutingEntries = queues.stream()
                .map(q -> "Map.entry(\"" + escapeJavaStringLiteral(q.signal()) + "\", \"" + q.taskRoutingKey() + "\")")
                .collect(Collectors.joining(",\n            "));
        String responseRoutingEntries = queues.stream()
                .map(q -> "Map.entry(\"" + escapeJavaStringLiteral(q.signal()) + "\", \"" + q.responseRoutingKey()
                        + "\")")
                .collect(Collectors.joining(",\n            "));
        // Quorum queues wired with dead-letter exchange (DLX) routing.
        String queueBeans = queues.stream()
                .map(q -> """

                        @Bean
                        public Queue %1$sTaskQueue() {
                            return QueueBuilder.durable("%2$s")
                                    .withArgument("x-queue-type", "quorum")
                                    .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                                    .withArgument("x-dead-letter-routing-key", DLQ_TASKS_ROUTING_KEY)
                                    .build();
                        }

                        @Bean
                        public Binding %1$sTaskBinding() {
                            return BindingBuilder.bind(%1$sTaskQueue()).to(syncExchange()).with("%3$s");
                        }

                        @Bean
                        public Queue %1$sResponseQueue() {
                            return QueueBuilder.durable("%4$s")
                                    .withArgument("x-queue-type", "quorum")
                                    .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                                    .withArgument("x-dead-letter-routing-key", DLQ_RESPONSES_ROUTING_KEY)
                                    .build();
                        }

                        @Bean
                        public Binding %1$sResponseBinding() {
                            return BindingBuilder.bind(%1$sResponseQueue()).to(syncExchange()).with("%5$s");
                        }
                        """.formatted(q.javaIdentifier(), q.taskQueue(), q.taskRoutingKey(), q.responseQueue(),
                        q.responseRoutingKey()))
                .collect(Collectors.joining());

        // Dead-letter exchange and shared DLQ definitions for task and response messages.
        String dlqSection = hasQueuesForDlq ? """

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
                """ : "";

        String dlqConstants = hasQueuesForDlq ? """

                    public static final String DLX_EXCHANGE = "%s";
                    public static final String DLQ_TASKS_QUEUE = "%s";
                    public static final String DLQ_RESPONSES_QUEUE = "%s";
                    public static final String DLQ_TASKS_ROUTING_KEY = "%s";
                    public static final String DLQ_RESPONSES_ROUTING_KEY = "%s";
                """.formatted(dlxExchangeName, dlqTasksQueueName, dlqResponsesQueueName, dlqTasksRoutingKey,
                dlqResponsesRoutingKey) : "";

        String configSource = """
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

                    public static final String EXCHANGE = "%s";
                    %s
                    // Shared signal name -> its dedicated task queue (proxy asks twin to advance past this signal). The single source of truth for which signals have RabbitMQ queues at all - a signal absent from this map exists on only one side and is delivered directly instead (see SignalBroadcaster.deliverTo).
                    public static final Map<String, String> TASK_QUEUE_BY_SIGNAL = Map.ofEntries(
                            %s
                    );

                    // Shared signal name -> its dedicated response queue (twin reports it advanced).
                    public static final Map<String, String> RESPONSE_QUEUE_BY_SIGNAL = Map.ofEntries(
                            %s
                    );

                    public static final Map<String, String> TASK_ROUTING_KEY_BY_SIGNAL = Map.ofEntries(
                            %s
                    );

                    public static final Map<String, String> RESPONSE_ROUTING_KEY_BY_SIGNAL = Map.ofEntries(
                            %s
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
                    %s%s
                }
                """.formatted(exchangeName, dlqConstants, taskEntries, responseEntries, taskRoutingEntries,
                responseRoutingEntries, queueBeans, dlqSection);

        String taskPublisherSource = """
                package com.tp.TargetPlatform.messaging;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.amqp.core.MessageDeliveryMode;
                import org.springframework.amqp.rabbit.core.RabbitTemplate;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.stereotype.Component;

                // Publishes proxy advance notification and waits for publisher confirm before marking delivery.
                @Component
                public class TaskQueuePublisher {

                    private static final Logger logger = LoggerFactory.getLogger(TaskQueuePublisher.class);

                    // Timeout for broker publisher confirmation before retry on next broadcaster tick.
                    private static final long CONFIRM_TIMEOUT_MS = 5000L;

                    private final RabbitTemplate rabbitTemplate;
                    private final boolean enabled;

                    public TaskQueuePublisher(RabbitTemplate rabbitTemplate,
                            @Value("${metaml.messaging.enabled:false}") boolean enabled) {
                        this.rabbitTemplate = rabbitTemplate;
                        this.enabled = enabled;
                    }

                    public boolean isEnabled() {
                        return enabled;
                    }

                    public boolean isEligible(String signalName) {
                        return RabbitMqConfig.TASK_QUEUE_BY_SIGNAL.containsKey(signalName);
                    }

                    public void publish(String signalName, String executionId, String processInstanceId,
                            String businessKey) {
                        String routingKey = RabbitMqConfig.TASK_ROUTING_KEY_BY_SIGNAL.get(signalName);
                        if (routingKey == null) {
                            throw new IllegalArgumentException("No task queue is declared for signal '"
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
                            logger.error("TASK: publish NOT confirmed for signal '{}' execution {} "
                                    + "(processInstanceId={}, businessKey={}): {}", signalName, executionId,
                                    processInstanceId, businessKey, e.toString());
                            throw e;
                        }
                        logger.info("TASK: published signal '{}' to RabbitMQ exchange '{}' key '{}' for "
                                + "execution {} (processInstanceId={}, businessKey={})", signalName,
                                RabbitMqConfig.EXCHANGE, routingKey, executionId, processInstanceId, businessKey);
                    }
                }
                """;

        String responsePublisherSource = """
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
                """;

        boolean hasQueues = !queues.isEmpty();
        String listenerImports = hasQueues
                ? """
                        import org.camunda.bpm.engine.ProcessEngineException;
                        import org.camunda.bpm.engine.RuntimeService;
                        import org.slf4j.Logger;
                        import org.slf4j.LoggerFactory;
                        import org.springframework.amqp.rabbit.annotation.RabbitListener;
                        import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                        import org.springframework.stereotype.Component;
                        """
                : """
                        import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                        import org.springframework.stereotype.Component;
                        """;
        String noQueuesComment = "    // No signal is shared between proxy and twin in this project's BPMNs, "
                + "so there is\n    // nothing to consume here.\n";

        String taskListenerFields = hasQueues
                ? "    private static final Logger logger = LoggerFactory.getLogger(TaskQueueListener.class);\n\n"
                : "";
        String taskListenerBody = hasQueues
                ? """
                            private final RuntimeService runtimeService;

                            public TaskQueueListener(RuntimeService runtimeService) {
                                this.runtimeService = runtimeService;
                            }

                            // A malformed payload throws an exception to trigger configured listener retries and dead-letter routing to DLQ_TASKS_QUEUE.
                            @RabbitListener(queues = { %s })
                            public void onTaskMessage(String payload) {
                                String[] parts = payload.split("\\\\|", -1);
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
                        """.formatted(queues.stream().map(q -> "\"" + q.taskQueue() + "\"")
                        .collect(Collectors.joining(", ")))
                : noQueuesComment;

        String responseListenerFields = hasQueues
                ? "    private static final Logger logger = LoggerFactory.getLogger(ResponseQueueListener.class);\n\n"
                : "";
        String responseListenerBody = hasQueues
                ? """
                            private final RuntimeService runtimeService;

                            public ResponseQueueListener(RuntimeService runtimeService) {
                                this.runtimeService = runtimeService;
                            }

                            // Validates message payload and routes to DLQ on failure, handling idempotent delivery states consistently with TaskQueueListener.
                            @RabbitListener(queues = { %s })
                            public void onResponseMessage(String payload) {
                                String[] parts = payload.split("\\\\|", -1);
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
                        """.formatted(queues.stream().map(q -> "\"" + q.responseQueue() + "\"")
                        .collect(Collectors.joining(", ")))
                : noQueuesComment;

        String taskListenerSource = """
                package com.tp.TargetPlatform.messaging;

                %s
                // Consumes task messages from RabbitMQ to deliver Camunda signals releasing waiting twin executions.
                @Component
                @ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
                public class TaskQueueListener {

                %s%s
                }
                """.formatted(listenerImports, taskListenerFields, taskListenerBody);

        String responseListenerSource = """
                package com.tp.TargetPlatform.messaging;

                %s
                // Consumes response messages from RabbitMQ to deliver Camunda signals releasing waiting proxy executions.
                @Component
                @ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
                public class ResponseQueueListener {

                %s%s
                }
                """.formatted(listenerImports, responseListenerFields, responseListenerBody);

        List<GeneratedSource> messagingSources = new ArrayList<>(List.of(
                new GeneratedSource("messaging", "RabbitMqConfig", configSource),
                new GeneratedSource("messaging", "TaskQueuePublisher", taskPublisherSource),
                new GeneratedSource("messaging", "TaskQueueListener", taskListenerSource),
                new GeneratedSource("messaging", "ResponseQueuePublisher", responsePublisherSource),
                new GeneratedSource("messaging", "ResponseQueueListener", responseListenerSource)));

        // Generates DLQ listener when shared signals exist to log dead-lettered messages.
        if (hasQueuesForDlq) {
            String dlqListenerSource = """
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
                    """;
            messagingSources.add(new GeneratedSource("messaging", "DeadLetterQueueListener", dlqListenerSource));
        }

        return messagingSources;
    }

    private GeneratedSource signalBroadcaster(Set<String> allSignalNames) {
        String signalList = allSignalNames.stream()
                .map(s -> "\"" + escapeJavaStringLiteral(s) + "\"")
                .collect(Collectors.joining(", "));

        String source = """
                package com.tp.TargetPlatform.signal;

                import java.util.List;
                import java.util.Map;
                import java.util.Set;
                import java.util.concurrent.ConcurrentHashMap;

                import org.camunda.bpm.engine.RuntimeService;
                import org.camunda.bpm.engine.runtime.EventSubscription;
                import org.camunda.bpm.engine.runtime.ProcessInstance;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                import com.tp.TargetPlatform.coordination.PairRegistry;
                import com.tp.TargetPlatform.messaging.RabbitMqConfig;
                import com.tp.TargetPlatform.messaging.TaskQueuePublisher;
                import com.tp.TargetPlatform.messaging.ResponseQueuePublisher;

                // Coordinates delivery of BPMN signal events between paired Proxy and Twin process executions.
                @Component
                public class SignalBroadcaster {

                    private static final Logger logger = LoggerFactory.getLogger(SignalBroadcaster.class);
                    private static final List<String> SIGNAL_NAMES = List.of(%s);

                    private final RuntimeService runtimeService;
                    private final PairRegistry pairRegistry;
                    private final TaskQueuePublisher taskQueuePublisher;
                    private final ResponseQueuePublisher responseQueuePublisher;
                    // The responder subscription released for each handoff. A responder may loop
                    // back to the same signal after it completes its gated work; that creates a new
                    // subscription id and is proof that the released turn really completed.
                    private final Map<String, String> awaitingResponderSubscriptions = new ConcurrentHashMap<>();
                    private final Set<String> everDelivered = ConcurrentHashMap.newKeySet();
                    private final Map<String, Integer> partnerArrivalTicks = new ConcurrentHashMap<>();
                    private static final int MAX_PARTNER_ARRIVAL_TICKS = 5;
                    // Tracks handoffKeys logged as stalled on an incident so error logging occurs once per stall period rather than repeatedly on every tick.
                    private final Set<String> stuckOnIncidentLogged = ConcurrentHashMap.newKeySet();

                    public SignalBroadcaster(RuntimeService runtimeService, PairRegistry pairRegistry,
                            TaskQueuePublisher taskQueuePublisher, ResponseQueuePublisher responseQueuePublisher) {
                        this.runtimeService = runtimeService;
                        this.pairRegistry = pairRegistry;
                        this.taskQueuePublisher = taskQueuePublisher;
                        this.responseQueuePublisher = responseQueuePublisher;
                    }

                    @Scheduled(fixedDelay = 1000)
                    public void broadcastSignals() {
                        for (String signalName : SIGNAL_NAMES) {
                            List<EventSubscription> waiting = runtimeService.createEventSubscriptionQuery()
                                    .eventType("signal")
                                    .eventName(signalName)
                                    .list();
                            for (EventSubscription subscription : waiting) {
                                handle(signalName, subscription, waiting);
                            }
                        }
                    }

                    private void handle(String signalName, EventSubscription subscription,
                            List<EventSubscription> waitingForSameSignal) {
                        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                                .processInstanceId(subscription.getProcessInstanceId())
                                .singleResult();
                        String businessKey = instance == null ? null : instance.getBusinessKey();
                        String role = pairRegistry.roleOf(businessKey, subscription.getProcessInstanceId());
                        String partnerInstanceId = pairRegistry.partnerOf(businessKey, subscription.getProcessInstanceId());

                        if (role == null || partnerInstanceId == null) {
                            deliverTo(signalName, subscription, businessKey, "DELIVERED");
                            return;
                        }
                        boolean partnerWaitingNow = waitingForSameSignal.stream()
                                .anyMatch(s -> s.getProcessInstanceId().equals(partnerInstanceId));
                        String waitKey = subscription.getProcessInstanceId() + "|" + signalName;

                        if ("responder".equals(role)) {
                            if (partnerWaitingNow) {
                                partnerArrivalTicks.remove(waitKey);
                            } else if (partnerNotComing(waitKey, partnerInstanceId, signalName)) {
                                deliverTo(signalName, subscription, businessKey, "DELIVERED");
                            }
                            return;
                        }

                        String handoffKey = businessKey + "|" + signalName;
                        if (awaitingResponderSubscriptions.containsKey(handoffKey)) {
                            if (responderHasAdvancedPast(signalName, partnerInstanceId,
                                    awaitingResponderSubscriptions.get(handoffKey))) {
                                awaitingResponderSubscriptions.remove(handoffKey);
                                stuckOnIncidentLogged.remove(handoffKey);
                                deliverTo(signalName, subscription, businessKey, "RESPONSE");
                            } else {
                                // Checks whether the partner instance has an active Camunda incident blocking advancement.
                                boolean partnerHasOpenIncident = runtimeService.createIncidentQuery()
                                        .processInstanceId(partnerInstanceId).count() > 0;
                                if (partnerHasOpenIncident) {
                                    // Logs the incident once per handoffKey until resolved.
                                    if (stuckOnIncidentLogged.add(handoffKey)) {
                                        logger.error("STUCK: proxy execution {} (businessKey={}) is waiting on "
                                                + "RESPONSE for signal '{}', but its twin partner "
                                                + "(processInstanceId={}) has an open Camunda incident and will "
                                                + "not advance on its own - this handoff will not complete until "
                                                + "that incident is resolved. The proxy has NOT been advanced.",
                                                subscription.getExecutionId(), businessKey, signalName,
                                                partnerInstanceId);
                                    }
                                } else {
                                    // Clear logged state when no incidents remain.
                                    stuckOnIncidentLogged.remove(handoffKey);
                                }
                            }
                            return;
                        }

                        if (partnerWaitingNow) {
                            partnerArrivalTicks.remove(waitKey);
                            EventSubscription responderSubscription = waitingForSameSignal.stream()
                                    .filter(s -> s.getProcessInstanceId().equals(partnerInstanceId))
                                    .findFirst()
                                    .orElse(null);
                            if (responderSubscription != null) {
                                deliverTo(signalName, responderSubscription, businessKey, "REQUEST");
                                awaitingResponderSubscriptions.put(handoffKey, responderSubscription.getId());
                            }
                            return;
                        }

                        if (partnerNotComing(waitKey, partnerInstanceId, signalName)) {
                            deliverTo(signalName, subscription, businessKey, "DELIVERED");
                        }
                    }

                    // "The partner will never turn up here, stop waiting for it." Getting this wrong in
                    // the permissive direction is what breaks lockstep: releasing this side early is
                    // indistinguishable, from the outside, from a synchronization that never happened.
                    //
                    // The five-tick budget predates human activities. It assumes both sides reach a
                    // shared signal within seconds, which held while every gated activity was an
                    // engine-driven service or external task. It does not hold when the partner is a
                    // person: a Proxy parked on a userTask reaches its sync point only when someone
                    // completes the task, which is minutes or hours, not five seconds - and the budget
                    // would release the Twin long before that, letting it run the whole process
                    // through while the human had not started.
                    //
                    // So the budget is now spent only when the partner is DEMONSTRABLY not coming:
                    //   - it already passed this signal (everDelivered), or
                    //   - its process instance is gone, or
                    //   - it is itself parked on signals and none of them is this one, which is the
                    //     divergent-path case the budget was written for and still covers.
                    // A partner that is alive and still working - a human task, a long service call -
                    // is a partner that is still coming, and this side keeps waiting for it.
                    private boolean partnerNotComing(String waitKey, String partnerInstanceId, String signalName) {
                        if (everDelivered.contains(partnerInstanceId + "|" + signalName)) {
                            partnerArrivalTicks.remove(waitKey);
                            return true;
                        }
                        ProcessInstance partner = runtimeService.createProcessInstanceQuery()
                                .processInstanceId(partnerInstanceId)
                                .singleResult();
                        if (partner == null) {
                            partnerArrivalTicks.remove(waitKey);
                            return true;
                        }
                        List<EventSubscription> partnerSignals = runtimeService.createEventSubscriptionQuery()
                                .processInstanceId(partnerInstanceId)
                                .eventType("signal")
                                .list();
                        boolean partnerParkedElsewhere = !partnerSignals.isEmpty()
                                && partnerSignals.stream().noneMatch(s -> s.getEventName().equals(signalName));
                        if (!partnerParkedElsewhere) {
                            // Still working towards this rendezvous. Reset rather than accumulate, so a
                            // partner that later diverges still gets a full budget from that point.
                            partnerArrivalTicks.remove(waitKey);
                            return false;
                        }
                        int ticks = partnerArrivalTicks.merge(waitKey, 1, Integer::sum);
                        if (ticks >= MAX_PARTNER_ARRIVAL_TICKS) {
                            partnerArrivalTicks.remove(waitKey);
                            return true;
                        }
                        return false;
                    }

                    // True once the responder moved past the exact subscription that released its
                    // gated task: it may subscribe to another signal, return to this signal through
                    // a loop (with a new subscription id), or complete entirely.
                    private boolean responderHasAdvancedPast(String signalName, String responderInstanceId,
                            String releasedSubscriptionId) {
                        ProcessInstance stillActive = runtimeService.createProcessInstanceQuery()
                                .processInstanceId(responderInstanceId)
                                .singleResult();
                        if (stillActive == null) {
                            return true;
                        }
                        List<EventSubscription> responderSignals = runtimeService.createEventSubscriptionQuery()
                                .processInstanceId(responderInstanceId)
                                .eventType("signal")
                                .list();
                        boolean stillOnReleasedSubscription = responderSignals.stream()
                                .anyMatch(s -> s.getEventName().equals(signalName)
                                        && s.getId().equals(releasedSubscriptionId));
                        if (stillOnReleasedSubscription) {
                            return false;
                        }
                        return !responderSignals.isEmpty();
                    }

                    private void deliverTo(String signalName, EventSubscription subscription, String businessKey,
                            String phase) {
                        boolean gated = RabbitMqConfig.TASK_QUEUE_BY_SIGNAL.containsKey(signalName);
                        if (gated) {
                            if ("REQUEST".equals(phase) && taskQueuePublisher.isEnabled()
                                    && taskQueuePublisher.isEligible(signalName)) {
                                taskQueuePublisher.publish(signalName, subscription.getExecutionId(),
                                        subscription.getProcessInstanceId(), businessKey);
                                everDelivered.add(subscription.getProcessInstanceId() + "|" + signalName);
                                return;
                            }
                            if ("RESPONSE".equals(phase) && responseQueuePublisher.isEnabled()
                                    && responseQueuePublisher.isEligible(signalName)) {
                                responseQueuePublisher.publish(signalName, subscription.getExecutionId(),
                                        subscription.getProcessInstanceId(), businessKey);
                                everDelivered.add(subscription.getProcessInstanceId() + "|" + signalName);
                                return;
                            }
                        }
                        try {
                            runtimeService.signalEventReceived(signalName, subscription.getExecutionId());
                            everDelivered.add(subscription.getProcessInstanceId() + "|" + signalName);
                            logger.info("{}: delivered signal '{}' to execution {} (processInstanceId={}, "
                                    + "businessKey={})", phase, signalName, subscription.getExecutionId(),
                                    subscription.getProcessInstanceId(), businessKey);
                        } catch (Exception e) {
                            // Expected during normal operation - the execution may already have advanced.
                        }
                    }
                }
                """.formatted(signalList);
        return new GeneratedSource("signal", "SignalBroadcaster", source);
    }

    // Generates process controller with start and instance-query endpoints for the given processKey.
    private GeneratedSource proxyController(String processKey) {
        return sideController("proxy", "ProxyProcessController", "/api/proxy", processKey);
    }

    private GeneratedSource twinController(String processKey) {
        return sideController("twin", "TwinProcessController", "/api/twin", processKey);
    }

    private GeneratedSource sideController(String side, String className, String mapping, String processKey) {
        String source = """
                package com.tp.TargetPlatform.%1$s.controller;

                import java.util.HashMap;
                import java.util.Map;
                import java.util.UUID;

                import org.camunda.bpm.engine.RuntimeService;
                import org.camunda.bpm.engine.runtime.ProcessInstance;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                import com.tp.TargetPlatform.coordination.PairRegistry;
                import com.tp.TargetPlatform.portal.RunExecutionGate;

                // REST controller starting %2$s instances and registering their businessKey with PairRegistry.
                @RestController
                @RequestMapping("%3$s")
                public class %4$s {

                    private final RuntimeService runtimeService;
                    private final PairRegistry pairRegistry;
                    private final RunExecutionGate executionGate;

                    public %4$s(RuntimeService runtimeService, PairRegistry pairRegistry, RunExecutionGate executionGate) {
                        this.runtimeService = runtimeService;
                        this.pairRegistry = pairRegistry;
                        this.executionGate = executionGate;
                    }

                    @GetMapping("/health")
                    public String health() {
                        return "%1$s ok";
                    }

                    // Starts a process instance; matching businessKey enables coordinated signal synchronization.
                    @PostMapping("/start")
                    public Map<String, Object> start(@RequestParam(required = false) String businessKey,
                            @RequestParam(required = false) String executionMode) {
                        String key = (businessKey == null || businessKey.isBlank())
                                ? UUID.randomUUID().toString() : businessKey;
                        if (executionMode != null && !executionMode.isBlank()) {
                            executionGate.configure(key, executionMode);
                        }
                        ProcessInstance instance = runtimeService.startProcessInstanceByKey("%5$s", key);
                        String role = pairRegistry.registerAndClassify(key, instance.getProcessInstanceId());
                        Map<String, Object> body = new HashMap<>();
                        body.put("processInstanceId", instance.getProcessInstanceId());
                        body.put("businessKey", key);
                        body.put("role", role == null ? "unpaired" : role);
                        body.put("executionMode", executionGate.mode(key).name());
                        return body;
                    }
                }
                """.formatted(side, side, mapping, className, processKey);
        return new GeneratedSource(side + "/controller", className, source);
    }
}
