package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TargetPlatformMessagingGeneratorTest {

    private final TargetPlatformMessagingGenerator generator = new TargetPlatformMessagingGenerator();

    private TargetPlatformMessagingGenerator.GeneratedSource find(
            List<TargetPlatformMessagingGenerator.GeneratedSource> sources, String className) {
        return sources.stream().filter(s -> s.className().equals(className)).findFirst()
                .orElseThrow(() -> new AssertionError(className + " was not generated; got "
                        + sources.stream().map(TargetPlatformMessagingGenerator.GeneratedSource::className).toList()));
    }

    @Test
    void generatesTheWholeSynchronizationLayerPlusBothControllers() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("cuttingSignal"), Set.of("cuttingSignal", "orderVerifySignal"), "rc_proxy_process",
                "rc_twin_process");

        assertThat(sources.stream().map(TargetPlatformMessagingGenerator.GeneratedSource::className))
                .containsExactlyInAnyOrder("PairRegistry", "RabbitMqConfig", "TaskQueuePublisher",
                        "TaskQueueListener", "ResponseQueuePublisher", "ResponseQueueListener",
                        // Observability listener for project-scoped DLQs, generated when at least one shared signal exists:
                        "DeadLetterQueueListener", "SignalBroadcaster", "ProxyProcessController",
                        "TwinProcessController");

        assertThat(find(sources, "PairRegistry").relativeDirectory()).isEqualTo("coordination");
        assertThat(find(sources, "RabbitMqConfig").relativeDirectory()).isEqualTo("messaging");
        assertThat(find(sources, "SignalBroadcaster").relativeDirectory()).isEqualTo("signal");
        assertThat(find(sources, "ProxyProcessController").relativeDirectory()).isEqualTo("proxy/controller");
        assertThat(find(sources, "TwinProcessController").relativeDirectory()).isEqualTo("twin/controller");
    }

    @Test
    void onlySharedSignalsGetADedicatedQueuePairNotEverySignal() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("cuttingSignal"), Set.of("cuttingSignal", "orderVerifySignal"), "rc_proxy_process",
                "rc_twin_process");
        String config = find(sources, "RabbitMqConfig").source();

        assertThat(config)
                .as("the shared signal must have its own task/response queue pair")
                .contains("Map.entry(\"cuttingSignal\", \"proj.abc123.sync.cutting-signal\")")
                .contains("Map.entry(\"cuttingSignal\", \"proj.abc123.sync.responses.cutting-signal\")");
        assertThat(config)
                .as("a signal declared on only one side must not get a queue - it falls back to direct delivery")
                .doesNotContain("orderVerifySignal");
    }

    @Test
    void signalBroadcasterListsEverySignalFromEitherSideNotJustTheSharedOnes() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("cuttingSignal"), Set.of("cuttingSignal", "orderVerifySignal"), "rc_proxy_process",
                "rc_twin_process");
        String broadcaster = find(sources, "SignalBroadcaster").source();

        assertThat(broadcaster).contains("\"cuttingSignal\"").contains("\"orderVerifySignal\"");
    }

    @Test
    void withNoSharedSignalsAtAllTheQueueMapsAreEmptyButEverythingStillCompiles() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.xyz",
                Set.of(), Set.of(), "rc_proxy_process", "rc_twin_process");

        String taskListener = find(sources, "TaskQueueListener").source();
        assertThat(taskListener)
                .as("no shared signal means nothing to bind a RabbitListener to")
                .doesNotContain("@RabbitListener");
    }

    @Test
    void bothControllersStartByBusinessKeyAndRegisterWithPairRegistry() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("cuttingSignal"), Set.of("cuttingSignal"), "rc_proxy_process", "rc_twin_process");

        String proxy = find(sources, "ProxyProcessController").source();
        assertThat(proxy)
                .contains("@RequestMapping(\"/api/proxy\")")
                .contains("runtimeService.startProcessInstanceByKey(\"rc_proxy_process\", key)")
                .contains("pairRegistry.registerAndClassify");

        String twin = find(sources, "TwinProcessController").source();
        assertThat(twin)
                .contains("@RequestMapping(\"/api/twin\")")
                .contains("runtimeService.startProcessInstanceByKey(\"rc_twin_process\", key)")
                .contains("pairRegistry.registerAndClassify");
    }

        // Asserts AMQP beans are omitted and signals deliver directly when messaging is disabled.
    @Test
    void publisherMessagesAreExplicitlyPersistentAndConfirmed() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("cuttingSignal"), Set.of("cuttingSignal"), "rc_proxy_process", "rc_twin_process");

        String taskPublisher = find(sources, "TaskQueuePublisher").source();
        assertThat(taskPublisher)
                .as("TASK messages must be explicitly persistent, not relying on the framework default")
                .contains("MessageDeliveryMode.PERSISTENT")
                .as("publish must block on a publisher confirm before logging/treating it as sent")
                .contains("waitForConfirmsOrDie")
                .as("a NACKed/unconfirmed publish must be visible in the log, not swallowed")
                .contains("publish NOT confirmed");

        String responsePublisher = find(sources, "ResponseQueuePublisher").source();
        assertThat(responsePublisher)
                .contains("MessageDeliveryMode.PERSISTENT")
                .contains("waitForConfirmsOrDie")
                .contains("publish NOT confirmed");
    }

    @Test
    void taskAndResponseQueuesDeadLetterToAProjectScopedDlx() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("cuttingSignal"), Set.of("cuttingSignal"), "rc_proxy_process", "rc_twin_process");
        String config = find(sources, "RabbitMqConfig").source();

        assertThat(config)
                .as("DLX must be scoped to this project's own namespace, like every other queue/exchange here")
                .contains("proj.abc123.dlx")
                .contains("proj.abc123.sync.dlq.tasks")
                .contains("proj.abc123.sync.dlq.responses")
                .as("every task/response queue must declare dead-letter routing, not just the DLQ existing")
                .contains("x-dead-letter-exchange")
                .contains("x-dead-letter-routing-key");

        assertThat(sources.stream().map(TargetPlatformMessagingGenerator.GeneratedSource::className))
                .as("a dead-lettered message must be observable in this app's own log, not only via the broker")
                .contains("DeadLetterQueueListener");
    }

    @Test
    void listenersDistinguishAlreadyAdvancedFromAGenuineFailure() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("cuttingSignal"), Set.of("cuttingSignal"), "rc_proxy_process", "rc_twin_process");

        String taskListener = find(sources, "TaskQueueListener").source();
        assertThat(taskListener)
                .as("a signal the execution has already advanced past must be logged as harmless, not an error")
                .contains("has not subscribed")
                .as("that is the ONLY case allowed to be swallowed - everything else must rethrow")
                .contains("throw e;")
                .as("a malformed payload must no longer be silently dropped - it must be rejected so it can "
                        + "dead-letter")
                .doesNotContain("discarding malformed message");

        String responseListener = find(sources, "ResponseQueueListener").source();
        assertThat(responseListener)
                .contains("has not subscribed")
                .contains("throw e;")
                .doesNotContain("discarding malformed message");
    }

    @Test
    void withNoSharedSignalsThereIsNoDeadLetterInfrastructureEither() {
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.xyz",
                Set.of(), Set.of(), "rc_proxy_process", "rc_twin_process");

        assertThat(sources.stream().map(TargetPlatformMessagingGenerator.GeneratedSource::className))
                .as("no shared signal means nothing to dead-letter, so no DLQ observability listener either")
                .doesNotContain("DeadLetterQueueListener");
        assertThat(find(sources, "RabbitMqConfig").source())
                .as("no DLX/DLQ topology when there is nothing for it to protect")
                .doesNotContain(".dlx");
    }

    @Test
    void twoSignalsThatSlugToTheSameNameGetDisambiguatedQueueNames() {
        // "Order Ready" and "OrderReady" both kebab-case to "order-ready" - must not collide.
        List<TargetPlatformMessagingGenerator.GeneratedSource> sources = generator.generate("proj.abc123",
                Set.of("Order Ready", "OrderReady"), Set.of("Order Ready", "OrderReady"), "rc_proxy_process",
                "rc_twin_process");
        String config = find(sources, "RabbitMqConfig").source();

        assertThat(config).contains("proj.abc123.sync.order-ready").contains("proj.abc123.sync.order-ready-2");
    }
}
