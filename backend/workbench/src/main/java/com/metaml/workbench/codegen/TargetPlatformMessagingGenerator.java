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

    // messagingNamespace scopes queue and exchange names so two independently generated projects can never
    // share a queue even with identical signal names.
    // sharedSignalNames are present in BOTH proxy and twin - real sync points, each getting a task+response
    // queue pair. allSignalNames is every signal in either BPMN: SignalBroadcaster polls them all, and one
    // declared on only one side simply has no partner and falls back to direct delivery.
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

    // Same kebab-case queue-name convention as SpringBootProjectGenerator's sibling helpers, restricted
    // to safe RabbitMQ identifier characters. Signal names are the same kind of author-controlled BPMN
    // identifier, so the same sanitisation applies.
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

                // Pairs a proxy instance with its twin by the caller-supplied business key both /start endpoints accept - no BPMN-specific knowledge. The first process instance to register a given business key is the "initiator" (proxy, in this generated platform's own usage); the next instance to register the SAME key is the "responder" (twin). A business key is pairing/correlation data only, not the communication mechanism itself - see SignalBroadcaster for how these roles turn each shared signal into a real, targeted proxy -> twin -> proxy handoff instead of an undifferentiated broadcast.
                @Component
                public class PairRegistry {

                    private final ConcurrentMap<String, String> initiators = new ConcurrentHashMap<>();
                    private final ConcurrentMap<String, String> responders = new ConcurrentHashMap<>();

                    // Returns "initiator" for the first instance registered under businessKey, "responder" for the second, and null for a blank key or a third-or-later instance sharing an already-claimed key - unpaired, callers fall back to their own default behavior.
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
        // Every task/response queue is dead-letter-wired to this project's own DLX (see dlxExchangeName above) and declared as a RabbitMQ quorum queue (x-queue-type=quorum) for high availability across broker clusters.
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

        // DLX + the two shared DLQs (one for TASK messages, one for RESPONSE messages) - declared as quorum queues for HA replication.
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

                // RabbitMQ topology for this generated platform's proxy<->twin synchronization: one task queue and one response queue per shared BPMN signal (see TargetPlatformMessagingGenerator.assignSignalQueues), scoped to this generated project so two independently generated platforms can never physically share a queue. Enabled only with metaml.messaging.enabled=true. Reliability hardening (Pass 1): every task/response queue dead-letters to this project's own DLX (see DLX_EXCHANGE) instead of a message that exhausts consumer retries (spring.rabbitmq.listener.simple.retry.* in this project's application.properties) silently vanishing. The RabbitTemplate wiring below (mandatory + a returns callback) is configured exactly once here, not per-publisher, since TaskQueuePublisher and ResponseQueuePublisher share the one autoconfigured RabbitTemplate bean - setting it in more than one place would just have the last constructor to run silently win.
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

                // Publishes "proxy is ready to advance past this signal" to that signal's own dedicated task queue. TaskQueueListener performs the actual Camunda signal delivery that releases twin's waiting execution, on consume. Always present as a bean, but isEnabled() is false unless metaml.messaging.enabled=true. Reliability hardening (Pass 1): publish() now blocks on a publisher confirm (rabbitTemplate.invoke + waitForConfirmsOrDie, which requires spring.rabbitmq.publisher-confirm-type=simple - see this project's application.properties) before returning or logging success. SignalBroadcaster.deliverTo() only marks a signal as everDelivered AFTER publish() returns normally, so a NACKed or unconfirmed publish throws here, deliverTo() never marks it delivered, and the next broadcaster tick simply retries - this preserves the existing "safe to re-attempt" behavior rather than adding a second, separate retry mechanism on top of it.
                @Component
                public class TaskQueuePublisher {

                    private static final Logger logger = LoggerFactory.getLogger(TaskQueuePublisher.class);

                    // Long enough for a broker under normal load to ack/nack; short enough that a genuinely unreachable broker fails this attempt and lets the next broadcaster tick (1s later) retry, rather than blocking the single-threaded scheduler indefinitely.
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

                // Publishes "twin has advanced past this signal" to that signal's own dedicated response queue. ResponseQueueListener performs the actual Camunda signal delivery that releases proxy's waiting execution, on consume. Reliability hardening (Pass 1): see TaskQueuePublisher's own comment - identical publisher-confirm + explicit-persistence treatment, for the same reason.
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

                            // Reliability hardening (Pass 1): a malformed payload used to be logged and silently dropped (acked as if processed). It now throws instead, so spring.rabbitmq.listener.simple.retry.* retries it (pointlessly, since a malformed payload never becomes valid, but consistently with every other failure path below) and then dead-letters it to RabbitMqConfig.DLQ_TASKS_QUEUE once retries are exhausted - observable there and in this log line, rather than disappearing.
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
                                    // Reliability hardening (Pass 1): distinguishes the expected, harmless cases - this execution already advanced past signalName (still active, but subscribed to something else now: "has not subscribed") or has completed/gone entirely (execution id no longer exists at all: "cannot find execution") - a genuine redelivery of an already-consumed message, or a rework-loop revisit, either way - from every other Camunda failure, which must NOT be swallowed the same way. Camunda has no single dedicated exception subtype covering both; message text is the only signal for either, same as the pre-hardening code relied on implicitly via a blanket catch.
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

                            // Reliability hardening (Pass 1): see TaskQueueListener's own comment - identical malformed-payload and already-advanced-vs-genuine-failure treatment.
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
                // The real consumer for task messages - the Camunda signal delivery that releases twin's waiting execution happens here, triggered by consuming the message. Enabled only with metaml.messaging.enabled=true; when disabled, SignalBroadcaster delivers signals directly instead.
                @Component
                @ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
                public class TaskQueueListener {

                %s%s
                }
                """.formatted(listenerImports, taskListenerFields, taskListenerBody);

        String responseListenerSource = """
                package com.tp.TargetPlatform.messaging;

                %s
                // The real consumer for response messages - the Camunda signal delivery that releases proxy's waiting execution happens here, triggered by consuming the message.
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

        // Makes a dead-lettered TASK/RESPONSE message observable in the application log itself, not only via broker inspection (RabbitMQ management API/UI) - only generated when there is at least one shared signal, matching the DLX/DLQ topology above, which is itself only declared in that same case.
        if (hasQueuesForDlq) {
            String dlqListenerSource = """
                    package com.tp.TargetPlatform.messaging;

                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.springframework.amqp.rabbit.annotation.RabbitListener;
                    import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                    import org.springframework.stereotype.Component;

                    // Consumes both project-scoped DLQs purely to surface a dead-lettered TASK/RESPONSE message in this application's own log - the message itself is already durably held in RabbitMqConfig.DLQ_TASKS_QUEUE / DLQ_RESPONSES_QUEUE and inspectable via the broker's management API regardless of whether anything ever consumes it here.
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

                // Delivers BPMN-defined signals to the specific executions currently waiting on each one, so proxy's and twin's signal catch events can both advance. Neither throws its own signals, so delivery has to happen externally - this is that external driver. Ported from the equivalent mechanism in the older camundademo-based Target Harness Platform (see SpringBootProjectGenerator.writeSignalBroadcaster's own, more detailed comment) with one simplification: RedCollarTP has no external-task topic to route through, so a shared signal's own name is directly what RabbitMqConfig's queues are keyed by. For a paired proxy+twin (same business key - see PairRegistry and the generated /start endpoints), each shared signal becomes a genuine two-step, targeted handoff instead of an undifferentiated broadcast: 1. REQUEST (proxy -> twin): once both sides of a pair are simultaneously waiting on the same signal, only twin's execution is released. 2. RESPONSE (twin -> proxy): proxy's execution is deliberately left waiting until twin is observed to have moved on - subscribed to a different signal, or completed entirely - proving its gated activity actually ran, not merely that the signal arrived. Only then is proxy's execution released. A signal declared on only one side, or whose partner is not currently waiting on it (unpaired, or a rework-loop revisit), is delivered to immediately instead - this is what lets a lone proxy instance (no twin started) still run to completion.
                @Component
                public class SignalBroadcaster {

                    private static final Logger logger = LoggerFactory.getLogger(SignalBroadcaster.class);
                    private static final List<String> SIGNAL_NAMES = List.of(%s);

                    private final RuntimeService runtimeService;
                    private final PairRegistry pairRegistry;
                    private final TaskQueuePublisher taskQueuePublisher;
                    private final ResponseQueuePublisher responseQueuePublisher;
                    private final Set<String> awaitingResponse = ConcurrentHashMap.newKeySet();
                    private final Set<String> everDelivered = ConcurrentHashMap.newKeySet();
                    private final Map<String, Integer> partnerArrivalTicks = new ConcurrentHashMap<>();
                    private static final int MAX_PARTNER_ARRIVAL_TICKS = 5;
                    // Reliability hardening (Pass 2): handoffKeys already logged as stuck-on-a-failed- partner, so the ERROR log below fires once per stuck period rather than once per second for as long as the incident is open. Cleared alongside awaitingResponse's own removal (whether the eventual outcome is a genuine advance or the handoff simply ending some other way) so a LATER stall on the same handoffKey logs again.
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
                        if (awaitingResponse.contains(handoffKey)) {
                            if (responderHasAdvancedPast(signalName, partnerInstanceId)) {
                                awaitingResponse.remove(handoffKey);
                                stuckOnIncidentLogged.remove(handoffKey);
                                deliverTo(signalName, subscription, businessKey, "RESPONSE");
                            } else {
                                // Reliability hardening (Pass 2): unlike partnerNotComing (bounded, self-releasing - the responder legitimately may not have arrived yet), this wait has no such bound, because there is no safe fallback here - the Twin's gated activity may genuinely still be running, and releasing the Proxy without proof it finished is exactly the "pretend completion" this broadcaster must never do. What CAN be told apart, using real Camunda state rather than an invented timeout, is "still legitimately in progress" from "provably stuck" - a Camunda incident (job retries exhausted, or a failed external task) on the responder's own process instance means it will NOT resolve on its own. Logging that once makes an otherwise silent, indefinite wait observable instead of indistinguishable from a merely slow partner; the proxy still does not advance - only a genuine, later responderHasAdvancedPast()==true (the incident gets resolved and the responder's execution actually moves on) does that.
                                boolean partnerHasOpenIncident = runtimeService.createIncidentQuery()
                                        .processInstanceId(partnerInstanceId).count() > 0;
                                if (partnerHasOpenIncident) {
                                    // add() itself is what makes this fire only the FIRST tick an incident is observed for this handoffKey - checking the incident BEFORE calling add() (rather than relying on add()'s own return value to short- circuit the query) is what keeps a legitimately-slow, incident-free tick from ever marking this handoffKey "already logged".
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
                                    // No incident currently open (never had one, or a prior one was already resolved) - clear any stale suppression so a LATER incident on this same handoffKey logs again instead of staying silenced forever.
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
                                awaitingResponse.add(handoffKey);
                            }
                            return;
                        }

                        if (partnerNotComing(waitKey, partnerInstanceId, signalName)) {
                            deliverTo(signalName, subscription, businessKey, "DELIVERED");
                        }
                    }

                    private boolean partnerNotComing(String waitKey, String partnerInstanceId, String signalName) {
                        if (everDelivered.contains(partnerInstanceId + "|" + signalName)) {
                            partnerArrivalTicks.remove(waitKey);
                            return true;
                        }
                        int ticks = partnerArrivalTicks.merge(waitKey, 1, Integer::sum);
                        if (ticks >= MAX_PARTNER_ARRIVAL_TICKS) {
                            partnerArrivalTicks.remove(waitKey);
                            return true;
                        }
                        return false;
                    }

                    // True once the responder has provably moved past the gated task behind signalName - subscribed to a different signal, or completed entirely - rather than merely having received the signal itself, which happens before its gated task ever runs. Relies on the responder's own JavaDelegate.execute() running synchronously, inside the same Camunda command/transaction as the signal delivery that triggers it - only that makes "no longer subscribed to signalName" (checked here via a separate query, on a later broadcaster tick) proof that the gated task actually finished, rather than merely that it started. A delegate that hands work to another thread and returns early would make this method return true before the real work is done.
                    private boolean responderHasAdvancedPast(String signalName, String responderInstanceId) {
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
                        boolean stillOnSameSignal = responderSignals.stream()
                                .anyMatch(s -> s.getEventName().equals(signalName));
                        if (stillOnSameSignal) {
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

    // Replaces the template's placeholder ProxyProcessController with a real /start (registering with
    // PairRegistry so SignalBroadcaster can pair it with its twin) and an /instances listing.
    // processKey is baked in as a literal because a generated project deploys exactly one proxy definition.
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

                // Starts and registers %2$s process instances. businessKey is what pairs a %2$s instance with its counterpart (see PairRegistry / SignalBroadcaster) - the first instance registered under a key is the initiator, the second is the responder, so starting a proxy and a twin with the SAME businessKey is what makes them synchronize.
                @RestController
                @RequestMapping("%3$s")
                public class %4$s {

                    private final RuntimeService runtimeService;
                    private final PairRegistry pairRegistry;

                    public %4$s(RuntimeService runtimeService, PairRegistry pairRegistry) {
                        this.runtimeService = runtimeService;
                        this.pairRegistry = pairRegistry;
                    }

                    @GetMapping("/health")
                    public String health() {
                        return "%1$s ok";
                    }

                    // businessKey is optional - omit it to run a lone %1$s instance with nothing to synchronize against (every signal falls back to immediate delivery); supply the SAME key on both sides' /start calls to pair them.
                    @PostMapping("/start")
                    public Map<String, Object> start(@RequestParam(required = false) String businessKey) {
                        String key = (businessKey == null || businessKey.isBlank())
                                ? UUID.randomUUID().toString() : businessKey;
                        ProcessInstance instance = runtimeService.startProcessInstanceByKey("%5$s", key);
                        String role = pairRegistry.registerAndClassify(key, instance.getProcessInstanceId());
                        Map<String, Object> body = new HashMap<>();
                        body.put("processInstanceId", instance.getProcessInstanceId());
                        body.put("businessKey", key);
                        body.put("role", role == null ? "unpaired" : role);
                        return body;
                    }
                }
                """.formatted(side, side, mapping, className, processKey);
        return new GeneratedSource(side + "/controller", className, source);
    }
}
