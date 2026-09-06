import React from "react";
import "./WorkflowProgress.css";

const PHASES = ["MODEL", "GENERATE", "LAUNCH"];
const PHASE_LABELS = { MODEL: "Model", GENERATE: "Generate", LAUNCH: "Launch" };

// glyph + short caption per status - no new state mechanism, this is purely how the already-fetched status string (see ModelPage's refreshWorkflowState) gets drawn
const STATUS_ICON = {
    PENDING: "○", // ○
    IN_PROGRESS: "●", // ●
    COMPLETED: "✓", // ✓
    STOPPED: "✓",
    FAILED: "✕", // ✕
};
const STATUS_CAPTION = { PENDING: "Pending", IN_PROGRESS: "In progress", FAILED: "Failed" };

// Model -> Generate -> Launch breadcrumb. Renders the stage/status pairs the backend supplies and
// infers nothing of its own; currentStage and stages come straight off the API response shape
// { currentStage: "GENERATE", stages: { MODEL: {status, timestamp, detail}, ... } }.
const WorkflowProgress = ({ currentStage, stages }) => {
    const statusFor = (phase) => stages?.[phase]?.status || "PENDING";

    return (
        <div className="workflow-progress" role="navigation" aria-label="Model to Generate to Launch progress">
            {PHASES.map((phase, index) => {
                const status = statusFor(phase);
                const isCurrent = phase === currentStage;
                const stageInfo = stages?.[phase];
                const title = stageInfo?.detail
                    ? `${status}: ${stageInfo.detail}`
                    : stageInfo?.timestamp
                    ? `${status} at ${new Date(stageInfo.timestamp).toLocaleString()}`
                    : status;
                return (
                    <React.Fragment key={phase}>
                        {index > 0 && <span className="workflow-progress-connector" aria-hidden="true" />}
                        <span
                            className={
                                "workflow-progress-step" +
                                (status === "COMPLETED" || status === "STOPPED" ? " workflow-progress-step-done" : "") +
                                (status === "FAILED" ? " workflow-progress-step-failed" : "") +
                                (status === "IN_PROGRESS" ? " workflow-progress-step-inprogress" : "") +
                                (isCurrent ? " workflow-progress-step-current" : "")
                            }
                            title={title}
                        >
                            <span className="workflow-progress-icon">{STATUS_ICON[status] || STATUS_ICON.PENDING}</span>
                            <span className="workflow-progress-label">{PHASE_LABELS[phase]}</span>
                            {isCurrent && STATUS_CAPTION[status] && (
                                <span className="workflow-progress-caption">{STATUS_CAPTION[status]}</span>
                            )}
                        </span>
                    </React.Fragment>
                );
            })}
        </div>
    );
};

export default WorkflowProgress;
