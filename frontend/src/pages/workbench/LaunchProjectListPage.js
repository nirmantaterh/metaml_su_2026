import React, { useCallback, useEffect, useState } from "react";
import { Alert, Button, Container, Table, Spinner } from "react-bootstrap";

import {
    listModelSummaries,
    getWorkflowState,
    launchProject,
    stopProject,
    listRunningProjects,
} from "../../services/workbench/WorkbenchService";
import { openCockpitUrl } from "../../components/workbench/openCockpitUrl";
import ProcessSpinner from "../../components/common/ProcessSpinner";
import NoDataAvailable from "../../components/common/NoDataAvailable";

const LaunchProjectListPage = () => {
    const [rows, setRows] = useState(null); // null while loading, [] once loaded
    const [error, setError] = useState(null);
    // modelId -> { phase, launchedBaseUrl, targetPlatformUrl, port, processKey, pairResult, pairWarning, error, isRunning, hasOpened, stopping }
    const [rowState, setRowState] = useState({});
    // modelId -> boolean (true if details expanded, false/undefined if collapsed)
    const [expandedRows, setExpandedRows] = useState({});

    const load = useCallback(async () => {
        setRows(null);
        setError(null);
        try {
            const [modelRes, runningRes] = await Promise.all([
                listModelSummaries(),
                listRunningProjects().catch(() => []),
            ]);
            const processes = modelRes.data || modelRes || [];
            const runningList = Array.isArray(runningRes?.data)
                ? runningRes.data
                : Array.isArray(runningRes)
                ? runningRes
                : [];

            const withState = await Promise.all(
                processes.map(async (process) => {
                    let isGenerated = false;
                    let generatedProjectId = null;
                    try {
                        const stateRes = await getWorkflowState(process.id);
                        const state = stateRes.data || stateRes;
                        const generateStage = state.stages?.GENERATE;
                        if (generateStage?.status === "COMPLETED" && generateStage.detail) {
                            isGenerated = true;
                            generatedProjectId = generateStage.detail;
                        }
                    } catch (err) {
                        // workflow state missing or ungenerated
                    }

                    const runningEntry = runningList.find(
                        (r) =>
                            (generatedProjectId && r.projectId === generatedProjectId) ||
                            r.modelId === process.id
                    );

                    return {
                        ...process,
                        isGenerated,
                        generatedProjectId,
                        initialRunningEntry: runningEntry || null,
                    };
                })
            );
            setRows(withState);
        } catch (err) {
            setError(err.response?.data?.message || err.message);
            setRows([]);
        }
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    const patchRow = (modelId, patch) => {
        setRowState((prev) => ({ ...prev, [modelId]: { ...prev[modelId], ...patch } }));
    };

    const toggleExpanded = (modelId) => {
        setExpandedRows((prev) => ({ ...prev, [modelId]: !prev[modelId] }));
    };

    const handleLaunch = async (row) => {
        patchRow(row.id, {
            phase: "launching",
            error: null,
            pairWarning: null,
            launchedBaseUrl: null,
            pairResult: null,
            hasOpened: false,
            isRunning: false,
        });

        let baseUrl;
        let targetPlatformUrl;
        let port;
        let processKey;
        try {
            const res = await launchProject({ projectId: row.generatedProjectId });
            const launched = res.data || res;
            if (!launched.port) {
                throw new Error("Launch succeeded but no port was returned");
            }
            port = launched.port;
            processKey = launched.processKey;
            targetPlatformUrl = launched.targetPlatformUrl || null;
            baseUrl = `${window.location.protocol}//${window.location.hostname}:${port}/`;
        } catch (err) {
            patchRow(row.id, {
                phase: null,
                isRunning: false,
                error: "Launch failed: " + (err.response?.data?.message || err.message),
            });
            return;
        }

        patchRow(row.id, {
            phase: "pairing",
            launchedBaseUrl: baseUrl,
            targetPlatformUrl,
            port,
            processKey,
            isRunning: true,
        });

        const businessKey = `sync-${Date.now()}`;
        try {
            const startSide = async (side) => {
                const response = await fetch(`${baseUrl}api/${side}/start?businessKey=${businessKey}`, {
                    method: "POST",
                });
                if (!response.ok) {
                    throw new Error(`${side} start failed (HTTP ${response.status})`);
                }
                return response.json();
            };
            const [proxy, twin] = await Promise.all([startSide("proxy"), startSide("twin")]);
            patchRow(row.id, {
                phase: "done",
                pairResult: { businessKey, proxy, twin },
                isRunning: true,
            });
        } catch (err) {
            patchRow(row.id, {
                phase: "done",
                isRunning: true,
                pairWarning:
                    "Launched, but could not auto-start proxy + twin (" +
                    err.message +
                    ") - this generated platform may not support pairing.",
            });
        }

        openCockpitUrl(`${baseUrl}camunda/app/cockpit/engine/`);
    };

    const handleOpen = (row) => {
        const rs = rowState[row.id] || {};
        const baseUrl =
            rs.launchedBaseUrl ||
            (row.initialRunningEntry?.port
                ? `${window.location.protocol}//${window.location.hostname}:${row.initialRunningEntry.port}/`
                : null);
        if (baseUrl) {
            openCockpitUrl(`${baseUrl}camunda/app/cockpit/engine/`);
        }
        patchRow(row.id, { hasOpened: true });
    };

    const targetPlatformUrlFor = (row) =>
        rowState[row.id]?.targetPlatformUrl || row.initialRunningEntry?.targetPlatformUrl || null;

    const handleOpenTargetPlatform = (row) => {
        const targetPlatformUrl = targetPlatformUrlFor(row);
        if (targetPlatformUrl) {
            openCockpitUrl(targetPlatformUrl);
        }
    };

    const handleStop = async (row) => {
        patchRow(row.id, { stopping: true });
        try {
            if (row.generatedProjectId) {
                await stopProject({ projectId: row.generatedProjectId });
            }
        } catch (err) {
            // ignore 404 or transient stop error
        } finally {
            patchRow(row.id, {
                phase: null,
                isRunning: false,
                stopping: false,
                hasOpened: false,
                launchedBaseUrl: null,
                port: null,
                pairResult: null,
                pairWarning: null,
                error: null,
            });
            await load();
        }
    };

    return (
        <Container className="pt-5 mt-4">
            <h3 className="mb-1">Launch</h3>
            <p className="text-muted">
                Generate, launch, and run your Target Platform.
            </p>
            {rows === null && <ProcessSpinner message="Loading saved processes..." />}
            {rows !== null && error && <Alert variant="danger">{error}</Alert>}
            {rows !== null && !error && rows.length === 0 && (
                <NoDataAvailable
                    dataType="saved processes"
                    errorMessage="Nothing saved yet - save a process model first."
                />
            )}
            {rows !== null && !error && rows.length > 0 && (
                <Table hover responsive>
                    <thead>
                        <tr>
                            <th>Process</th>
                            <th>Project</th>
                            <th>Status</th>
                            <th className="text-end">Action</th>
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((row) => {
                            const rs = rowState[row.id] || {};
                            const isRunning =
                                rs.isRunning ||
                                (row.initialRunningEntry && rs.phase !== null && rs.isRunning !== false);
                            const targetPlatformUrl = targetPlatformUrlFor(row);
                            const isGenerated = row.isGenerated;
                            const busy = rs.phase === "launching" || rs.phase === "pairing";
                            const launchLabel =
                                rs.phase === "launching"
                                    ? "Launching…"
                                    : rs.phase === "pairing"
                                    ? "Starting proxy + twin…"
                                    : "Launch";
                            const isExpanded = Boolean(expandedRows[row.id]);
                            const showDetailRow = rs.error || (isRunning && isExpanded);

                            return (
                                <React.Fragment key={row.id}>
                                    <tr>
                                        <td>{row.name || row.processKey || "Untitled"}</td>
                                        <td>{row.projectDisplayName || row.projectId || "-"}</td>
                                        <td>
                                            {!isGenerated && (
                                                <span className="d-inline-flex align-items-center gap-1">
                                                    <span className="text-secondary" style={{ fontSize: "0.75rem" }}>
                                                        ●
                                                    </span>
                                                    <span className="text-muted">Not Generated</span>
                                                </span>
                                            )}
                                            {isGenerated && isRunning && (
                                                <span className="d-inline-flex align-items-center gap-1">
                                                    <span className="text-success" style={{ fontSize: "0.75rem" }}>
                                                        ●
                                                    </span>
                                                    <span className="text-success fw-medium">Running</span>
                                                </span>
                                            )}
                                            {isGenerated && !isRunning && (
                                                <span className="d-inline-flex align-items-center gap-1">
                                                    <span className="text-warning" style={{ fontSize: "0.75rem" }}>
                                                        ●
                                                    </span>
                                                    <span className="text-dark">Generated / Stopped</span>
                                                </span>
                                            )}
                                        </td>
                                        <td className="text-end">
                                            <div className="d-inline-flex align-items-center justify-content-end gap-2">
                                                {!isGenerated && <span className="text-muted">—</span>}
                                                {isGenerated && !isRunning && !busy && (
                                                    <Button
                                                        size="sm"
                                                        variant="primary"
                                                        onClick={() => handleLaunch(row)}
                                                    >
                                                        Launch
                                                    </Button>
                                                )}
                                                {busy && (
                                                    <Button size="sm" variant="primary" disabled>
                                                        <Spinner
                                                            as="span"
                                                            animation="border"
                                                            size="sm"
                                                            role="status"
                                                            aria-hidden="true"
                                                            className="me-2"
                                                        />
                                                        {launchLabel}
                                                    </Button>
                                                )}
                                                {isGenerated && isRunning && !rs.hasOpened && !busy && (
                                                    <Button
                                                        size="sm"
                                                        variant="outline-primary"
                                                        onClick={() => handleOpen(row)}
                                                    >
                                                        Open
                                                    </Button>
                                                )}
                                                {isGenerated && isRunning && rs.hasOpened && !busy && (
                                                    <div className="d-inline-flex gap-2">
                                                        <Button
                                                            size="sm"
                                                            variant="outline-primary"
                                                            onClick={() => handleOpen(row)}
                                                        >
                                                            Open
                                                        </Button>
                                                        <Button
                                                            size="sm"
                                                            variant="outline-danger"
                                                            disabled={rs.stopping}
                                                            onClick={() => handleStop(row)}
                                                        >
                                                            {rs.stopping ? "Stopping…" : "Stop"}
                                                        </Button>
                                                    </div>
                                                )}
                                                {isRunning && (
                                                    <Button
                                                        size="sm"
                                                        variant="link"
                                                        className="text-decoration-none p-0 ms-1 text-secondary"
                                                        onClick={() => toggleExpanded(row.id)}
                                                        aria-expanded={isExpanded}
                                                        aria-label={isExpanded ? "Collapse details" : "Expand details"}
                                                        title={isExpanded ? "Collapse details" : "Expand details"}
                                                        style={{ fontSize: "1.1rem", lineHeight: "1", width: "20px" }}
                                                    >
                                                        {isExpanded ? "▼" : "›"}
                                                    </Button>
                                                )}
                                            </div>
                                        </td>
                                    </tr>
                                    {showDetailRow && (
                                        <tr>
                                            <td colSpan={4} className="pt-0 border-top-0">
                                                {rs.error && <div className="text-danger small mb-2">{rs.error}</div>}
                                                {isRunning && isExpanded && (
                                                    <div className="bg-light p-3 rounded my-1 border">
                                                        <div className="d-flex justify-content-between align-items-start flex-wrap gap-3">
                                                            <div>
                                                                <div className="d-flex align-items-center gap-2 small mb-1">
                                                                    <span className="text-muted" style={{ minWidth: "110px" }}>Proxy + Twin:</span>
                                                                    <span className="fw-medium text-success">Started</span>
                                                                </div>
                                                                <div className="d-flex align-items-center gap-2 small mb-1">
                                                                    <span className="text-muted" style={{ minWidth: "110px" }}>Process:</span>
                                                                    <span className="fw-medium">{rs.processKey || row.name || "Untitled"}</span>
                                                                </div>
                                                                <div className="d-flex align-items-center gap-2 small mb-1">
                                                                    <span className="text-muted" style={{ minWidth: "110px" }}>Engine Port:</span>
                                                                    <span className="font-monospace">{rs.port || row.initialRunningEntry?.port || "-"}</span>
                                                                </div>
                                                                {targetPlatformUrl && (
                                                                    <div className="d-flex align-items-center gap-2 small mb-1">
                                                                        <span className="text-muted" style={{ minWidth: "110px" }}>Target Platform:</span>
                                                                        <span className="font-monospace text-break">{targetPlatformUrl}</span>
                                                                    </div>
                                                                )}
                                                                {rs.pairResult?.businessKey && (
                                                                    <div className="d-flex align-items-center gap-2 small">
                                                                        <span className="text-muted" style={{ minWidth: "110px" }}>Business Key:</span>
                                                                        <span className="font-monospace text-primary">{rs.pairResult.businessKey}</span>
                                                                    </div>
                                                                )}
                                                            </div>
                                                            <div>
                                                                <Button
                                                                    size="sm"
                                                                    variant="outline-primary"
                                                                    onClick={() => handleOpen(row)}
                                                                >
                                                                    Open Cockpit ↗
                                                                </Button>
                                                                {targetPlatformUrl && (
                                                                    <Button
                                                                        size="sm"
                                                                        variant="outline-primary"
                                                                        className="ms-2"
                                                                        onClick={() => handleOpenTargetPlatform(row)}
                                                                    >
                                                                        Go to Target Platform ↗
                                                                    </Button>
                                                                )}
                                                            </div>
                                                        </div>
                                                        {rs.pairWarning && (
                                                            <div className="text-warning small mt-2">{rs.pairWarning}</div>
                                                        )}
                                                    </div>
                                                )}
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

export default LaunchProjectListPage;
