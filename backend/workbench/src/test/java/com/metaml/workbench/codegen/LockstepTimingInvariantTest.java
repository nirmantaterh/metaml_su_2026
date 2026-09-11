package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.runtime.EventSubscription;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.metaml.workbench.bpmn.TwinModelGenerator;

/**
 * Verifies the lockstep synchronization timing invariant using an embedded engine.
 */
class LockstepTimingInvariantTest {

    private static final String PROXY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                id="Definitions_timing" targetNamespace="http://metaml.com/bpmn">
              <bpmn:process id="LockstepTiming" isExecutable="true" camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start">
                  <bpmn:outgoing>Flow_1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:sequenceFlow id="Flow_1" sourceRef="start" targetRef="taskA"/>
                <bpmn:serviceTask id="taskA" name="Task A" camunda:delegateExpression="${taskA}">
                  <bpmn:incoming>Flow_1</bpmn:incoming>
                  <bpmn:outgoing>Flow_2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="Flow_2" sourceRef="taskA" targetRef="taskB"/>
                <bpmn:serviceTask id="taskB" name="Task B" camunda:delegateExpression="${taskB}">
                  <bpmn:incoming>Flow_2</bpmn:incoming>
                  <bpmn:outgoing>Flow_3</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="Flow_3" sourceRef="taskB" targetRef="end"/>
                <bpmn:endEvent id="end">
                  <bpmn:incoming>Flow_3</bpmn:incoming>
                </bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private final TargetPlatformSourceGenerator generator = new TargetPlatformSourceGenerator();
    private ProcessEngine engine;

    @AfterEach
    void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    private static final class LatchedDelegate implements JavaDelegate {
        private final CountDownLatch releaseLatch;
        private final CountDownLatch startedSignal = new CountDownLatch(1);
        private volatile Instant completedAt;

        LatchedDelegate(CountDownLatch releaseLatch) {
            this.releaseLatch = releaseLatch;
        }

        @Override
        public void execute(DelegateExecution execution) throws Exception {
            startedSignal.countDown();
            if (!releaseLatch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release latch was never counted down");
            }
            completedAt = Instant.now();
        }
    }

    private static final class InstantDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            // no-op stand-in for the real generated stub
        }
    }

    @Test
    void proxyCannotEnterTaskBWhileTwinsTaskAIsStillBlocked() throws Exception {
        // 1. Real generator output for both sides
        TargetPlatformSourceGenerator.Result proxyResult = generator.generate(PROXY_BPMN, false);
        BpmnModelInstance proxyModel = Bpmn.readModelFromStream(
                new ByteArrayInputStream(PROXY_BPMN.getBytes(StandardCharsets.UTF_8)));
        String twinBpmn = Bpmn.convertToString(new TwinModelGenerator().generate(proxyModel));
        TargetPlatformSourceGenerator.Result twinResult = generator.generate(twinBpmn, true,
                proxyResult.syncActivityIds());

        // Register mock delegate beans for proxy and twin execution.
        CountDownLatch releaseLatch = new CountDownLatch(1);
        LatchedDelegate twinTaskADelegate = new LatchedDelegate(releaseLatch);
        Map<Object, Object> beans = Map.of(
                "taskA", new InstantDelegate(),
                "taskB", new InstantDelegate(),
                "taskA_automateTwin", twinTaskADelegate,
                "taskB_automateTwin", new InstantDelegate());

        ProcessEngineConfigurationImpl config = (ProcessEngineConfigurationImpl)
                ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration();
        String uniqueSuffix = UUID.randomUUID().toString();
        config.setProcessEngineName("lockstep-timing-" + uniqueSuffix);
        config.setJdbcUrl("jdbc:h2:mem:lockstep-timing-" + uniqueSuffix + ";DB_CLOSE_DELAY=-1");
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP);
        config.setJobExecutorActivate(false);
        config.setBeans(beans);
        engine = config.buildProcessEngine();

        RuntimeService runtimeService = engine.getRuntimeService();
        HistoryService historyService = engine.getHistoryService();
        RepositoryService repositoryService = engine.getRepositoryService();

        // 3. Deploy the REAL transformed BPMNs (post lockstep-sync insertion), not the raw fixture.
        repositoryService.createDeployment()
                .addString("proxy.bpmn", proxyResult.bpmnXml())
                .addString("twin.bpmn", twinResult.bpmnXml())
                .deploy();

        ProcessInstance proxy = runtimeService.startProcessInstanceByKey("LockstepTiming");
        ProcessInstance twin = runtimeService.startProcessInstanceByKey("LockstepTiming_twin");

        // 4. Wait until both proxy and twin subscribe to sync_taskA.
        awaitEventSubscription(runtimeService, proxy.getId(), "sync_taskA", Duration.ofSeconds(10));
        awaitEventSubscription(runtimeService, twin.getId(), "sync_taskA", Duration.ofSeconds(10));

        // 5. Deliver sync_taskA signal to twin asynchronously.
        AtomicReference<Throwable> deliveryFailure = new AtomicReference<>();
        Thread requestDelivery = new Thread(() -> {
            try {
                String twinExecutionId = subscriptionExecutionId(runtimeService, twin.getId(), "sync_taskA");
                runtimeService.signalEventReceived("sync_taskA", twinExecutionId);
            } catch (Throwable t) {
                deliveryFailure.set(t);
            }
        }, "twin-request-delivery");
        requestDelivery.start();

        boolean delegateStarted = twinTaskADelegate.startedSignal.await(10, TimeUnit.SECONDS);
        assertThat(delegateStarted).as("twin's taskA_automate delegate should have started").isTrue();

        // 6. Verify proxy remains blocked while twin task A is executing.
        assertThat(runtimeService.getActiveActivityIds(proxy.getId()))
                .as("proxy must remain blocked at its own sync_taskA gate while twin's Task A is still running")
                .containsExactly("sync_evt_taskA");
        assertThat(historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(proxy.getId()).activityId("taskB").count())
                .as("proxy must not have entered taskB while twin's Task A is still blocked")
                .isZero();

        // 7. Release twin task A and wait for completion.
        releaseLatch.countDown();
        requestDelivery.join(Duration.ofSeconds(10).toMillis());
        assertThat(requestDelivery.isAlive()).as("REQUEST delivery thread should have returned by now").isFalse();
        assertThat(deliveryFailure.get()).as("REQUEST delivery must not have thrown").isNull();
        assertThat(twinTaskADelegate.completedAt).as("twin's taskA_automate must have completed").isNotNull();

        // 8. RESPONSE: verify twin is no longer subscribed to sync_taskA, confirming twin's transaction
        // has committed before sending response.
        boolean twinAdvancedPastTaskA = runtimeService.createEventSubscriptionQuery()
                .processInstanceId(twin.getId()).eventType("signal").list().stream()
                .noneMatch(subscription -> "sync_taskA".equals(subscription.getEventName()));
        assertThat(twinAdvancedPastTaskA).as("twin must have moved off sync_taskA before RESPONSE is sent").isTrue();

        String proxyExecutionId = subscriptionExecutionId(runtimeService, proxy.getId(), "sync_taskA");
        runtimeService.signalEventReceived("sync_taskA", proxyExecutionId);

        // 9. Proxy Task N+1 (taskB) must now execute.
        awaitHistoricActivity(historyService, proxy.getId(), "taskB", Duration.ofSeconds(10));

        // 10. Direct ordering verification from engine history timestamps: twin's taskA_automate end time
        // must precede proxy's taskB start time.
        HistoricActivityInstance twinAutomate = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getId()).activityId("taskA_automate").singleResult();
        HistoricActivityInstance proxyTaskB = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(proxy.getId()).activityId("taskB").singleResult();
        assertThat(twinAutomate).as("twin's taskA_automate must have a recorded history entry").isNotNull();
        assertThat(proxyTaskB).as("proxy's taskB must have a recorded history entry").isNotNull();
        assertThat(twinAutomate.getEndTime())
                .as("twin's taskA_automate must have committed its end time before proxy's taskB started")
                .isBeforeOrEqualTo(proxyTaskB.getStartTime());
    }

    private static void awaitEventSubscription(RuntimeService runtimeService, String processInstanceId,
            String signalName, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            boolean waiting = !runtimeService.createEventSubscriptionQuery()
                    .processInstanceId(processInstanceId).eventType("signal").eventName(signalName)
                    .list().isEmpty();
            if (waiting) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("process instance " + processInstanceId + " never subscribed to " + signalName);
    }

    private static void awaitHistoricActivity(HistoryService historyService, String processInstanceId,
            String activityId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            long count = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(processInstanceId).activityId(activityId).count();
            if (count > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("process instance " + processInstanceId + " never entered " + activityId);
    }

    private static String subscriptionExecutionId(RuntimeService runtimeService, String processInstanceId,
            String signalName) {
        List<EventSubscription> subscriptions = runtimeService.createEventSubscriptionQuery()
                .processInstanceId(processInstanceId).eventType("signal").eventName(signalName).list();
        if (subscriptions.isEmpty()) {
            throw new IllegalStateException(
                    "no event subscription for '" + signalName + "' on process instance " + processInstanceId);
        }
        return subscriptions.get(0).getExecutionId();
    }
}
