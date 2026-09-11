package com.metaml.workbench.workflow;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// Single source of truth for where a model's Model -> Generate -> Launch pipeline is.
// The event log IS the state: current stage and per-stage status are folds over it computed on every
// read, not separate fields that could drift from what was recorded.
@Component
public class WorkflowStateTracker {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowStateTracker.class);

    private final Map<String, List<StageEvent>> eventsByModelId = new ConcurrentHashMap<>();
    private final WorkflowEventStore eventStore;

    public WorkflowStateTracker(WorkflowEventStore eventStore) {
        this.eventStore = eventStore;
    }

    @PostConstruct
    void restore() {
        for (Map.Entry<String, List<StageEvent>> entry : eventStore.load().entrySet()) {
            eventsByModelId.put(entry.getKey(), new CopyOnWriteArrayList<>(entry.getValue()));
        }
    }

    // Records a workflow stage state transition after validating lifecycle constraints.
    public void record(String modelId, WorkflowStage stage, StageStatus status, String detail) {
        record(modelId, stage, status, detail, (StageError) null);
    }

    // Records a workflow stage state transition along with structured error metadata.
    public void record(String modelId, WorkflowStage stage, StageStatus status, String detail, StageError error) {
        validateTransition(modelId, stage, status);
        append(modelId, new StageEvent(stage, status, Instant.now(), detail, error));
    }

    private void validateTransition(String modelId, WorkflowStage stage, StageStatus status) {
        Map<WorkflowStage, StageInfo> current = stateFor(modelId).stages();
        StageStatus currentStatus = current.get(stage).status();

        switch (status) {
            case PENDING -> throw new IllegalStateException(
                    "Refusing to record " + stage + "/PENDING for model " + modelId
                            + " - PENDING is the implicit default for a stage with no events, it should never be "
                            + "written explicitly");
            case IN_PROGRESS -> {
                WorkflowStage prerequisite = prerequisiteOf(stage);
                if (prerequisite != null && current.get(prerequisite).status() != StageStatus.COMPLETED) {
                    throw new IllegalStateException(stage + " cannot start for model " + modelId + " - "
                            + prerequisite + " has not completed (currently "
                            + current.get(prerequisite).status() + ")");
                }
            }
            case COMPLETED, FAILED -> {
                if (currentStatus != StageStatus.IN_PROGRESS) {
                    throw new IllegalStateException(
                            "Cannot record " + stage + "/" + status + " for model " + modelId
                                    + " - " + stage + " is not IN_PROGRESS (currently " + currentStatus + ")");
                }
            }
            case STOPPED -> {
                if (stage != WorkflowStage.LAUNCH) {
                    throw new IllegalStateException(
                            "STOPPED only applies to LAUNCH, not " + stage + " (model " + modelId + ")");
                }
                if (currentStatus != StageStatus.COMPLETED) {
                    throw new IllegalStateException("Cannot stop LAUNCH for model " + modelId
                            + " - it is not COMPLETED (currently " + currentStatus + ")");
                }
            }
        }
    }

    private static WorkflowStage prerequisiteOf(WorkflowStage stage) {
        WorkflowStage[] order = WorkflowStage.values();
        int index = stage.ordinal();
        return index == 0 ? null : order[index - 1];
    }

    // Backfills a stage event with a timestamp other than "now" - specifically MODEL/COMPLETED for a model
    // whose history predates this class having real persistence. Using the model's own createdAt rather
    // than the restart time keeps the history honest.
    public void record(String modelId, WorkflowStage stage, StageStatus status, String detail, Instant timestamp) {
        append(modelId, new StageEvent(stage, status, timestamp, detail));
    }

    private void append(String modelId, StageEvent event) {
        eventsByModelId.computeIfAbsent(modelId, id -> new CopyOnWriteArrayList<>()).add(event);
        eventStore.save(eventsByModelId);
    }

    /** Returns true if no events have been recorded for the given model ID. */
    public boolean hasNoHistory(String modelId) {
        List<StageEvent> history = eventsByModelId.get(modelId);
        return history == null || history.isEmpty();
    }

    /** Computes current workflow state for a model, defaulting to all stages pending. */
    public WorkflowState stateFor(String modelId) {
        List<StageEvent> history = eventsByModelId.getOrDefault(modelId, List.of());

        Map<WorkflowStage, StageInfo> stages = new EnumMap<>(WorkflowStage.class);
        for (WorkflowStage stage : WorkflowStage.values()) {
            stages.put(stage, latestFor(history, stage));
        }

        return new WorkflowState(modelId, resolveCurrentStage(stages), stages, new ArrayList<>(history));
    }

    // last event recorded for this stage wins - a retried Generate after a FAILED attempt overwrites the stage's resolved status back to IN_PROGRESS/COMPLETED, while the FAILED event stays in history underneath it
    private static StageInfo latestFor(List<StageEvent> history, WorkflowStage stage) {
        StageInfo latest = StageInfo.PENDING;
        for (StageEvent event : history) {
            if (event.stage() == stage) {
                latest = new StageInfo(event.status(), event.timestamp(), event.detail(), event.error());
            }
        }
        return latest;
    }

    // Which stage the breadcrumb highlights: something actively running wins, then the earliest failure
    // (that is what is actually blocking the pipeline), then the furthest stage reached.
    private static WorkflowStage resolveCurrentStage(Map<WorkflowStage, StageInfo> stages) {
        for (WorkflowStage stage : WorkflowStage.values()) {
            if (stages.get(stage).status() == StageStatus.IN_PROGRESS) {
                return stage;
            }
        }
        for (WorkflowStage stage : WorkflowStage.values()) {
            if (stages.get(stage).status() == StageStatus.FAILED) {
                return stage;
            }
        }
        WorkflowStage furthestDone = null;
        for (WorkflowStage stage : WorkflowStage.values()) {
            StageStatus status = stages.get(stage).status();
            if (status == StageStatus.COMPLETED || status == StageStatus.STOPPED) {
                furthestDone = stage;
            }
        }
        if (furthestDone == null) {
            return WorkflowStage.MODEL;
        }
        WorkflowStage[] order = WorkflowStage.values();
        int nextIndex = furthestDone.ordinal() + 1;
        return nextIndex < order.length ? order[nextIndex] : furthestDone;
    }
}
