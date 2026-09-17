package com.tp.TargetPlatform.worker;

import java.util.List;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tp.TargetPlatform.portal.RunExecutionGate;
import com.tp.TargetPlatform.portal.StandaloneCapabilityResolver;



// Polls all registered external-task topics and dispatches locked tasks to the matching GeneratedExternalTaskWorker. Uses the embedded engine's ExternalTaskService directly (fetchAndLock + complete) instead of the HTTP-based external-task client starter, which depends on Jersey — incompatible with Spring Boot 4.x.
@Component
public class ExternalTaskPoller {

    private static final Logger logger = LoggerFactory.getLogger(ExternalTaskPoller.class);
    private static final String WORKER_ID = "generated-worker";
    private static final long LOCK_DURATION_MS = 10_000L;
    // Backoff between retries of one task, and the delay before the poller's own next tick picks it back up - no job executor is running, so this poller's own polling cadence IS the retry mechanism (see handleWorkerFailure below).
    private static final long RETRY_BACKOFF_MS = 2_000L;

    private final ExternalTaskService externalTaskService;
    private final List<GeneratedExternalTaskWorker> workers;
    private final RunExecutionGate executionGate;
    private final StandaloneCapabilityResolver capabilityResolver;

    private final int maxRetries;

    public ExternalTaskPoller(ExternalTaskService externalTaskService,
            List<GeneratedExternalTaskWorker> workers,
            RunExecutionGate executionGate,
            StandaloneCapabilityResolver capabilityResolver,

            @org.springframework.beans.factory.annotation.Value(
                    "${metaml.worker.max-retries:3}") int maxRetries) {
        this.externalTaskService = externalTaskService;
        this.workers = workers;
        this.executionGate = executionGate;
        this.capabilityResolver = capabilityResolver;

        this.maxRetries = maxRetries;
        logger.info("ExternalTaskPoller initialized with {} worker(s): {} (maxRetries={})",
                workers.size(), workers.stream().map(GeneratedExternalTaskWorker::topic).toList(),
                maxRetries);
    }

    @Scheduled(fixedDelay = 500)
    public void poll() {
        for (GeneratedExternalTaskWorker worker : workers) {
            try {
                List<LockedExternalTask> tasks = externalTaskService.fetchAndLock(10, WORKER_ID)
                        .topic(worker.topic(), LOCK_DURATION_MS)
                        .execute();
                for (LockedExternalTask task : tasks) {
                    try {
                        if (!executionGate.mayExecute(task)) {
                            externalTaskService.unlock(task.getId());
                            continue;
                        }
                        capabilityResolver.resolveIfUnambiguous(task);

                        worker.execute(task, externalTaskService);
                        executionGate.afterExecution(task);
                    } catch (Exception e) {
                        handleWorkerFailure(worker, task, e);
                    }
                }
            } catch (Exception e) {
                // Topic may not have any pending tasks — expected during normal operation
            }
        }
    }

    // A task's current remaining retries is null until its first failure (Camunda's own convention), at which point maxRetries is the starting budget. Each subsequent failure decrements it by one. At zero, Camunda marks the task an incident and this poller's own fetchAndLock naturally stops returning it - the same "no job executor" reasoning that lets a positive retry count self-heal (its lockExpirationTime is pushed out by RETRY_BACKOFF_MS, and this poller's next tick past that point re-fetches it on its own, with no separate retry-timer infrastructure needed) also makes zero retries a real, generic dead-letter state rather than a silent stall: it is visible via ExternalTaskService/ExternalTaskQuery, not merely logged.
    private void handleWorkerFailure(GeneratedExternalTaskWorker worker, LockedExternalTask task,
            Exception e) {
        Integer currentRetries = task.getRetries();
        int remaining = (currentRetries == null ? maxRetries : currentRetries) - 1;
        if (remaining > 0) {
            logger.warn("TECHNICAL_TASK_FAILURE: topic={} activityId={} taskId={} processInstanceId={} "
                            + "businessKey={} retriesRemaining={} exception={}", worker.topic(),
                    task.getActivityId(), task.getId(), task.getProcessInstanceId(),
                    task.getBusinessKey(), remaining, e.getMessage(), e);
        } else {
            logger.error("TECHNICAL_TASK_FAILURE: topic={} activityId={} taskId={} processInstanceId={} "
                            + "businessKey={} retriesRemaining=0 incident=true exception={}",
                    worker.topic(), task.getActivityId(), task.getId(), task.getProcessInstanceId(),
                    task.getBusinessKey(), e.getMessage(), e);
        }
        externalTaskService.handleFailure(task.getId(), WORKER_ID, e.getMessage(),
                Math.max(remaining, 0), RETRY_BACKOFF_MS);
    }
}
