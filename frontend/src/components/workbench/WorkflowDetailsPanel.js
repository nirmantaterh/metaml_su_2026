import React, { useEffect, useState } from "react";
import "./WorkflowDetailsPanel.css";

const PHASES = ["MODEL", "GENERATE", "LAUNCH"];
const PHASE_LABELS = { MODEL: "Model", GENERATE: "Generate", LAUNCH: "Launch" };
const STATUS_ICON = { PENDING: "○", IN_PROGRESS: "●", COMPLETED: "✓", STOPPED: "✓", FAILED: "✕" };
const STATUS_WORD = {
    PENDING: "Pending",
    IN_PROGRESS: "In progress",
    COMPLETED: "Completed",
    STOPPED: "Stopped",
    FAILED: "Failed",
};

// Runtime execution statuses are the backend's own (TwinActivityExecutionState), deliberately kept
// separate from the MODEL/GENERATE/LAUNCH workflow vocabulary above - they describe different things
// and must never be conflated. Anything unrecognised renders verbatim rather than being guessed at.
const EXECUTION_ICON = { NOT_STARTED: "○", BOUND: "◐", EXECUTED: "✓", FAILED: "✕" };

const formatTime = (isoTimestamp) => (isoTimestamp ? new Date(isoTimestamp).toLocaleTimeString() : null);

// The executor writes its own timestamp into its output under a name it chooses (validatedAt,
// assessedAt, enrichedAt, recommendedAt, notifiedAt, ...). Rather than hardcoding that list - which
// would break for any future component - pick up whichever *At key the executor actually emitted.
const executedAtFrom = (output) => {
    if (!output) return null;
    const key = Object.keys(output).find((k) => /At$/.test(k) && typeof output[k] === "string");
    return key ? output[key] : null;
};

const formatDuration = (ms) => {
    if (ms < 1000) return `${ms}ms`;
    const seconds = ms / 1000;
    if (seconds < 60) return `${seconds.toFixed(1)}s`;
    const minutes = Math.floor(seconds / 60);
    const remainder = Math.round(seconds % 60);
    return `${minutes}m ${remainder}s`;
};

// Everything here is read straight off the same WorkflowState ModelPage already polls for and hands to WorkflowProgress - this panel doesn't fetch anything of its own and doesn't infer anything the backend didn't say. The one thing it computes locally is "when did the current run of this stage start" and the duration that implies - that's arithmetic over real recorded timestamps (find the IN_PROGRESS event immediately before the stage's current terminal event in its own history), not an invented value.
const startOf = (history, stage) => {
    const forStage = (history || []).filter((event) => event.stage === stage);
    for (let i = forStage.length - 2; i >= 0; i--) {
        if (forStage[i].status === "IN_PROGRESS") return forStage[i].timestamp;
    }
    return null;
};

// Renders ONLY what the runtime execution-state API reported. Every field below is read straight off
// TwinActivityExecutionState - agentName, status, summary, output - with no inference: an activity is
// never shown as EXECUTED because it was bound, reached, or recommended, only because the backend
// itself said status === "EXECUTED". `execution` is null/undefined while nothing has been loaded yet,
// carries an error flag when the API could not be reached, and holds an empty twins list when the
// model genuinely has no twin with connected activities.
const ExecutionSection = ({ execution, openOutputs, onToggleOutput }) => {
    if (execution === null || execution === undefined) {
        return null;
    }

    if (execution.error) {
        return (
            <>
                <div className="workflow-details-eyebrow workflow-details-execution-heading">Execution</div>
                <div className="workflow-details-empty">Execution state is currently unavailable.</div>
            </>
        );
    }

    const twins = execution.twins || [];
    const hasAnyActivity = twins.some((twin) => (twin.activities || []).length > 0);

    return (
        <>
            <div className="workflow-details-eyebrow workflow-details-execution-heading">Execution</div>
            {!hasAnyActivity ? (
                <div className="workflow-details-empty">No connected Twin activities yet.</div>
            ) : (
                twins.map((twin) => (
                    <div key={twin.twinId} className="workflow-details-execution-twin">
                        {twins.length > 1 && (
                            <div className="workflow-details-execution-twinid" title={twin.twinId}>
                                Twin {String(twin.twinId).slice(0, 8)}
                                {twin.twinStatus ? ` · ${twin.twinStatus}` : ""}
                            </div>
                        )}
                        {(twin.activities || []).map((activity) => {
                            const state = activity.state || {};
                            const status = activity.error ? "UNAVAILABLE" : state.status || "NOT_STARTED";
                            const output = state.output || {};
                            const outputKeys = Object.keys(output);
                            const executedAt = executedAtFrom(output);
                            const rowKey = `${twin.twinId}:${activity.activityId}`;
                            const outputOpen = Boolean(openOutputs[rowKey]);

                            return (
                                <div
                                    key={rowKey}
                                    className={`workflow-details-stage workflow-details-execution-activity workflow-details-execution-${status.toLowerCase()}`}
                                >
                                    <div className="workflow-details-stage-title">
                                        <span className="workflow-details-stage-icon">{EXECUTION_ICON[status] || "○"}</span>
                                        <span className="workflow-details-execution-activityid" title={activity.activityId}>
                                            {activity.activityId}
                                        </span>
                                        <span className="workflow-details-stage-status">{status}</span>
                                    </div>

                                    {activity.error ? (
                                        <div className="workflow-details-stage-line">
                                            Execution state could not be read for this activity.
                                        </div>
                                    ) : (
                                        <>
                                            {state.agentName && (
                                                <div className="workflow-details-stage-line">Component: {state.agentName}</div>
                                            )}
                                            {output.executor && (
                                                <div className="workflow-details-stage-line">Executor: {output.executor}</div>
                                            )}
                                            {executedAt && (
                                                <div className="workflow-details-stage-line">
                                                    Executed at: {formatTime(executedAt)}
                                                </div>
                                            )}
                                            {state.summary && (
                                                <div className="workflow-details-stage-line workflow-details-execution-summary">
                                                    {state.summary}
                                                </div>
                                            )}
                                            {outputKeys.length > 0 && (
                                                <>
                                                    <button
                                                        type="button"
                                                        className="workflow-details-output-toggle"
                                                        onClick={() => onToggleOutput(rowKey)}
                                                        aria-expanded={outputOpen}
                                                    >
                                                        Output {outputOpen ? "▴" : "▾"}
                                                    </button>
                                                    {outputOpen && (
                                                        <pre className="workflow-details-output">
                                                            {JSON.stringify(output, null, 2)}
                                                        </pre>
                                                    )}
                                                </>
                                            )}
                                        </>
                                    )}
                                </div>
                            );
                        })}
                    </div>
                ))
            )}
        </>
    );
};

// onGoToError is a synchronous (bpmnElementId) => boolean supplied by the caller, true when the
// modeler found and selected the element. This component knows nothing about bpmn-js: it renders the
// structured error the backend sent and shows a message when the click did not land.
const WorkflowDetailsPanel = ({ workflowState, onClose, onGoToError, execution }) => {
    const [notFoundElementId, setNotFoundElementId] = useState(null);
    const [openOutputs, setOpenOutputs] = useState({});

    useEffect(() => {
        const onKeyDown = (e) => {
            if (e.key === "Escape") onClose();
        };
        document.addEventListener("keydown", onKeyDown);
        return () => document.removeEventListener("keydown", onKeyDown);
    }, [onClose]);

    const stages = workflowState?.stages || {};
    const history = workflowState?.history || [];

    const handleGoToError = (bpmnElementId) => {
        const found = Boolean(onGoToError && onGoToError(bpmnElementId));
        setNotFoundElementId(found ? null : bpmnElementId);
    };

    return (
        <div className="workflow-details-panel" role="dialog" aria-label="Workflow details">
            <div className="workflow-details-header">
                <span>Workflow Details</span>
                <button type="button" className="workflow-details-close" onClick={onClose} aria-label="Close">
                    &times;
                </button>
            </div>

            <div className="workflow-details-body">
                {PHASES.map((phase) => {
                    const info = stages[phase] || { status: "PENDING" };
                    const started = info.status === "IN_PROGRESS" ? info.timestamp : startOf(history, phase);
                    const completed =
                        info.status === "COMPLETED" || info.status === "FAILED" || info.status === "STOPPED"
                            ? info.timestamp
                            : null;
                    const durationMs =
                        started && completed ? new Date(completed).getTime() - new Date(started).getTime() : null;

                    return (
                        <div key={phase} className={`workflow-details-stage workflow-details-stage-${info.status.toLowerCase()}`}>
                            <div className="workflow-details-stage-title">
                                <span className="workflow-details-stage-icon">{STATUS_ICON[info.status]}</span>
                                {PHASE_LABELS[phase]}
                                <span className="workflow-details-stage-status">{STATUS_WORD[info.status]}</span>
                            </div>
                            {started && (
                                <div className="workflow-details-stage-line">
                                    {phase === "MODEL" ? "Saved" : "Started"}: {formatTime(started)}
                                </div>
                            )}
                            {completed && info.status !== "FAILED" && (
                                <div className="workflow-details-stage-line">Completed: {formatTime(completed)}</div>
                            )}
                            {info.status === "FAILED" && <div className="workflow-details-stage-line">Failed: {formatTime(completed)}</div>}
                            {durationMs !== null && (
                                <div className="workflow-details-stage-line">Duration: {formatDuration(durationMs)}</div>
                            )}
                            {info.status === "FAILED" && (info.detail || info.error) && (
                                <div className="workflow-details-error">
                                    {info.error?.errorType && (
                                        <div className="workflow-details-error-field">
                                            <span className="workflow-details-eyebrow">Error type</span>
                                            {info.error.errorType}
                                        </div>
                                    )}
                                    {info.detail && (
                                        <div className="workflow-details-error-field">
                                            <span className="workflow-details-eyebrow">Message</span>
                                            {info.detail}
                                        </div>
                                    )}
                                    {info.error?.operation && (
                                        <div className="workflow-details-error-field">
                                            <span className="workflow-details-eyebrow">Operation</span>
                                            {info.error.operation}
                                        </div>
                                    )}
                                    {info.error?.delegateExpression && (
                                        <div className="workflow-details-error-field">
                                            <span className="workflow-details-eyebrow">Delegate</span>
                                            {info.error.delegateExpression}
                                        </div>
                                    )}
                                    {info.error?.delegateExpression && info.error.bpmnElementId && (
                                        <>
                                            <div className="workflow-details-error-field">
                                                <span className="workflow-details-eyebrow">BPMN element</span>
                                                {info.error.bpmnElementId}
                                            </div>
                                            <button
                                                type="button"
                                                className="workflow-details-goto-error"
                                                onClick={() => handleGoToError(info.error.bpmnElementId)}
                                            >
                                                Go to error
                                            </button>
                                            {notFoundElementId === info.error.bpmnElementId && (
                                                <div className="workflow-details-notfound">
                                                    Source BPMN element could not be found.
                                                </div>
                                            )}
                                        </>
                                    )}
                                    {info.error?.delegateExpression && !info.error.bpmnElementId && (
                                        <div className="workflow-details-error-field">
                                            <span className="workflow-details-eyebrow">Source</span>
                                            Not uniquely identifiable
                                        </div>
                                    )}
                                </div>
                            )}
                        </div>
                    );
                })}

                <ExecutionSection
                    execution={execution}
                    openOutputs={openOutputs}
                    onToggleOutput={(rowKey) => setOpenOutputs((prev) => ({ ...prev, [rowKey]: !prev[rowKey] }))}
                />

                <div className="workflow-details-eyebrow workflow-details-history-heading">Event History</div>
                {history.length === 0 ? (
                    <div className="workflow-details-empty">Nothing recorded yet.</div>
                ) : (
                    <div className="workflow-details-history">
                        {history.map((event, index) => (
                            // stage+status can repeat (a retry) so index is part of the key, not a workaround for missing data - there's no event id from the backend
                            <div key={`${event.stage}-${event.status}-${index}`} className="workflow-details-history-row">
                                <span className="workflow-details-history-time">{formatTime(event.timestamp)}</span>
                                <span className="workflow-details-history-stage">{PHASE_LABELS[event.stage]}</span>
                                <span className="workflow-details-history-status">{event.status}</span>
                            </div>
                        ))}
                    </div>
                )}
            </div>
        </div>
    );
};

export default WorkflowDetailsPanel;
