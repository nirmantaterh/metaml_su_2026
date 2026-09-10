package com.metaml.workbench.bridge;

import jakarta.annotation.PreDestroy;

import org.camunda.bpm.spring.boot.starter.event.ExecutionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.metaml.workbench.model.BusinessKeys;
import com.metaml.workbench.service.WorkbenchService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

// Advances the twin after the original commits so twin failures cannot roll back the original.
@Component
public class AutoBridgeTrigger {

    private static final Logger logger = LoggerFactory.getLogger(AutoBridgeTrigger.class);

    // activities emit start/end, sequence flows emit "take". we only want entry.
    private static final String ACTIVITY_START_EVENT_NAME = "start";
    // must exceed NodeManagerClient's connect+read timeouts (1s+2s); browser waits synchronously per visit
    private static final long BRIDGE_TIMEOUT_SECONDS = 6;
    private static final long SHUTDOWN_GRACE_SECONDS = 5;

    private final WorkbenchService workbenchService;

    // AtomicReference: timed-out executors are swapped out so one stuck thread can't block other twins.
    private final AtomicReference<ExecutorService> bridgeExecutor = new AtomicReference<>();

    // engine can still be committing during shutdown; submitting to a dead executor throws
    private volatile boolean shuttingDown = false;

    public AutoBridgeTrigger(WorkbenchService workbenchService) {
        this.workbenchService = workbenchService;
        this.bridgeExecutor.set(newBridgeExecutor());
    }

    private static ExecutorService newBridgeExecutor() {
        ThreadFactory daemonThread = runnable -> {
            Thread thread = new Thread(runnable, "auto-bridge");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(daemonThread);
    }

    // AFTER_COMMIT required: plain @EventListener runs before engine flush, so queries return stale state.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onActivityStarted(ExecutionEvent event) {
        // Swallows exceptions to prevent listener failures from aborting completed engine transactions.
        try {
            handleActivityStarted(event);
        } catch (RuntimeException e) {
            logger.warn("Auto-bridge listener swallowed an error so the engine command survives: {}",
                    e.toString());
        }
    }

    private void handleActivityStarted(ExecutionEvent event) {
        if (!ACTIVITY_START_EVENT_NAME.equals(event.getEventName())) {
            return;
        }
        String businessKey = event.getProcessBusinessKey();
        if (!BusinessKeys.isOriginalKey(businessKey)) {
            return;
        }
        String twinId = BusinessKeys.twinIdFromOriginalKey(businessKey);
        String activityId = event.getCurrentActivityId();
        if (twinId.isBlank() || activityId == null || activityId.isBlank()) {
            return;
        }
        if (shuttingDown) {
            return;
        }
        // Disambiguates per-visit runtime execution for loops and parallel multi-instance siblings.
        String activityInstanceId = event.getActivityInstanceId();

        // Executes asynchronously to ensure unbinding from committed engine transaction.
        ExecutorService executor = bridgeExecutor.get();
        Future<?> bridged;
        try {
            bridged = executor.submit(() -> runBridge(twinId, activityId, activityInstanceId));
        } catch (RejectedExecutionException e) {
            logger.debug("Auto-bridge executor is gone, skipping activity {} on twin {}", activityId, twinId);
            return;
        }

        // Awaits bridge completion so subsequent state queries observe updated twin execution.
        try {
            bridged.get(BRIDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Auto-bridge of activity {} on twin {} did not finish in time: {}",
                    activityId, twinId, e.toString());
            // Replaces executor if bridge times out to prevent stalled tasks from blocking subsequent events.
            bridged.cancel(true);
            if (bridgeExecutor.compareAndSet(executor, newBridgeExecutor())) {
                executor.shutdown();
                logger.warn("Auto-bridge worker replaced after activity {} on twin {} did not finish in time",
                        activityId, twinId);
            }
        }
    }

    // consolidated so manual callers share the same parallel-multi-instance disambiguation path
    private void runBridge(String twinId, String activityId, String activityInstanceId) {
        try {
            workbenchService.bridgeActivityEvent(twinId, activityId, activityInstanceId);
        } catch (RuntimeException e) {
            // debug not warn: unconnected activities land here normally
            logger.debug("Auto-bridge of activity {} on twin {} did nothing: {}",
                    activityId, twinId, e.toString());
        }
    }

    // graceful drain first; shutdownNow() alone can corrupt a mid-flight bridge
    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        ExecutorService executor = bridgeExecutor.get();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
