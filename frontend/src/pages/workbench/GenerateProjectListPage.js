import React, { useCallback, useEffect, useState } from "react";
import { Alert, Button, Container, Table } from "react-bootstrap";
import { Link } from "react-router-dom";

import { WorkbenchRoutes } from "../../routes";

import { listModelSummaries, generateProject, getWorkflowState } from "../../services/workbench/WorkbenchService";
import ProcessSpinner from "../../components/common/ProcessSpinner";
import NoDataAvailable from "../../components/common/NoDataAvailable";

// Transmute > Generate: lists saved process models across projects, allowing users to generate Target Platform projects from BPMN definitions.
const GenerateProjectListPage = () => {
    const [processes, setProcesses] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    // modelId -> { type: 'busy'|'ok'|'err', text }. Per-row, not a single page-wide status - one process finishing (or failing) must never overwrite what the row above it just reported.
    const [rowStatus, setRowStatus] = useState({});

    // Same GENERATE-stage lookup LaunchProjectListPage does per row, so a process generated anywhere (ModelPage's own Generate button, an earlier session, this page) shows as generated here instead of looking like it still needs doing. Returns null when the state is missing/ungenerated.
    const loadGenerated = async (modelId) => {
        try {
            const stateRes = await getWorkflowState(modelId);
            const stage = (stateRes.data || stateRes)?.stages?.GENERATE;
            if (stage?.status === "COMPLETED" && stage.detail) {
                return { projectId: stage.detail, timestamp: stage.timestamp || null };
            }
        } catch (err) {
            // workflow state missing or ungenerated
        }
        return null;
    };

    const load = useCallback(async () => {
        try {
            const response = await listModelSummaries();
            const summaries = response.data || response || [];
            const withState = await Promise.all(
                summaries.map(async (process) => ({ ...process, generated: await loadGenerated(process.id) }))
            );
            setProcesses(withState);
            setError(null);
        } catch (err) {
            setError(err.response?.data?.message || err.message);
        } finally {
            setLoading(false);
        }
    }, []);
    useEffect(() => { load(); }, [load]);

    const handleGenerate = async (modelId) => {
        setRowStatus((prev) => ({ ...prev, [modelId]: { type: "busy", text: "Generating…" } }));
        try {
            const res = await generateProject({ modelId });
            const project = res.data || res;
            setRowStatus((prev) => ({
                ...prev,
                [modelId]: {
                    type: "ok",
                    text: project.projectId
                        ? `Generate successful ("${project.displayName || project.processKey || "?"}")`
                        : "Generate successful",
                },
            }));
            // flip this row to Generated without a full reload - the other rows' state hasn't changed
            const generated = await loadGenerated(modelId);
            setProcesses((prev) => prev.map((p) => (p.id === modelId ? { ...p, generated } : p)));
        } catch (err) {
            setRowStatus((prev) => ({
                ...prev,
                [modelId]: { type: "err", text: "Generate failed: " + (err.response?.data?.message || err.message) },
            }));
        }
    };

    return (
        <Container className="pt-5 mt-4">
            <h3 className="mb-1">Generate</h3>
            <p className="text-muted">
                Clone the Target Platform template and generate delegate/event classes for a saved process.
            </p>
            {loading && <ProcessSpinner message="Loading saved processes..." />}
            {!loading && error && <Alert variant="danger">{error}</Alert>}
            {!loading && !error && processes.length === 0 && (
                <NoDataAvailable dataType="saved processes" errorMessage="Nothing saved yet - save a process model first." />
            )}
            {!loading && !error && processes.length > 0 && (
                <Table hover responsive>
                    <thead>
                        <tr>
                            <th>Process name</th>
                            <th>Project</th>
                            <th>Status</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {processes.map((process) => {
                            const rs = rowStatus[process.id];
                            return (
                                <React.Fragment key={process.id}>
                                    <tr>
                                        <td>
                                            {/* straight into the editor for this row, so Model <-> Generate stays about the same process; projectId travels in state the same way ProjectProcessListPage's "Edit model" passes it */}
                                            <Link
                                                to={WorkbenchRoutes.ModelEditor.path.replace(":id", process.id)}
                                                state={{ projectId: process.projectId != null ? Number(process.projectId) : undefined }}
                                            >
                                                {process.name || "Untitled"}
                                            </Link>
                                        </td>
                                        <td>
                                            {process.projectDisplayName
                                                ? `${process.projectDisplayName} (${process.projectId})`
                                                : process.projectId ?? "-"}
                                        </td>
                                        <td>
                                            {process.generated ? (
                                                <span
                                                    className="d-inline-flex align-items-center gap-1"
                                                    title={process.generated.timestamp
                                                        ? `Generated at ${new Date(process.generated.timestamp).toLocaleString()}`
                                                        : undefined}
                                                >
                                                    <span className="text-success" style={{ fontSize: "0.75rem" }}>●</span>
                                                    <span className="text-success fw-medium">Generated</span>
                                                </span>
                                            ) : (
                                                <span className="d-inline-flex align-items-center gap-1">
                                                    <span className="text-secondary" style={{ fontSize: "0.75rem" }}>●</span>
                                                    <span className="text-muted">Not Generated</span>
                                                </span>
                                            )}
                                        </td>
                                        <td className="text-end">
                                            <Button
                                                size="sm"
                                                variant="outline-primary"
                                                disabled={rs?.type === "busy"}
                                                onClick={() => handleGenerate(process.id)}
                                            >
                                                {rs?.type === "busy" ? "Generating…" : process.generated ? "Regenerate" : "Generate"}
                                            </Button>
                                        </td>
                                    </tr>
                                    {/* Own row below the button, same reasoning as ModelPage's own status row - a per-process result never shares a line with the button that produced it. */}
                                    {rs && (
                                        <tr>
                                            <td colSpan={4} className="pt-0">
                                                <span
                                                    className={
                                                        rs.type === "err" ? "text-danger small"
                                                            : rs.type === "ok" ? "text-success small"
                                                                : "text-muted small"
                                                    }
                                                >
                                                    {rs.text}
                                                </span>
                                            </td>
                                        </tr>
                                    )}
                                </React.Fragment>
                            );
                        })}
                    </tbody>
                </Table>
            )}
        </Container>
    );
};

export default GenerateProjectListPage;
