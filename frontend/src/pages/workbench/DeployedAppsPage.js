import React, { useCallback, useEffect, useState } from "react";
import { Container, Table, Button, Badge } from "react-bootstrap";
import { Link } from "react-router-dom";

import { listRunningProjects, stopProject } from "../../services/workbench/WorkbenchService";
import { WorkbenchRoutes } from "../../routes";
import ProcessSpinner from "../../components/common/ProcessSpinner";
import NoDataAvailable from "../../components/common/NoDataAvailable";

// Management page for active generated Target Platform applications, providing status monitoring, termination, and model navigation.
const DeployedAppsPage = () => {
    const [running, setRunning] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [stoppingId, setStoppingId] = useState(null);

    const refresh = useCallback(async () => {
        try {
            const res = await listRunningProjects();
            const list = res.data || res;
            setRunning(Array.isArray(list) ? list : []);
            setError(null);
        } catch (err) {
            setError(err.response?.data?.message || err.message);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        refresh();
    }, [refresh]);

    const handleStop = async (projectId) => {
        setStoppingId(projectId);
        try {
            await stopProject({ projectId });
        } catch (err) {
            // a 404 here just means it stopped itself (crashed, or someone else stopped it) between the last refresh and this click - either way, the list refresh below is what actually corrects the display, not this catch block
        } finally {
            setStoppingId(null);
            await refresh();
        }
    };

    return (
        <Container className="pt-5 mt-4">
            <div className="d-flex justify-content-between align-items-center mb-4">
                <h3 className="mb-0">Deployed Applications</h3>
                <Button size="sm" variant="outline-secondary" onClick={refresh} disabled={loading}>
                    Refresh
                </Button>
            </div>
            {loading && <ProcessSpinner message="Loading running applications..." />}
            {!loading && error && <NoDataAvailable dataType="deployed applications" errorMessage={error} />}
            {!loading && !error && running.length === 0 && (
                <NoDataAvailable
                    dataType="deployed applications"
                    errorMessage="Nothing is currently running - generate and launch a project first."
                />
            )}
            {!loading && !error && running.length > 0 && (
                <Table hover responsive>
                    <thead>
                        <tr>
                            <th>Process</th>
                            <th>Port</th>
                            <th>Launched</th>
                            <th>Project Id</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {running.map((project) => (
                            <tr key={project.projectId}>
                                <td>
                                    {project.displayName || project.processKey || "?"}{" "}
                                    <Badge bg="success">running</Badge>
                                </td>
                                <td>{project.port}</td>
                                <td>{project.launchedAt ? new Date(project.launchedAt).toLocaleString() : "-"}</td>
                                <td className="text-muted small" title={`Backend ID: ${project.projectId}`}>{project.projectId}</td>
                                <td className="text-end">
                                    {project.port && (
                                        <Button
                                            size="sm"
                                            variant="success"
                                            className="me-2"
                                            onClick={() =>
                                                window.open(
                                                    `${window.location.protocol}//${window.location.hostname}:${project.port}/`,
                                                    "_blank",
                                                    "noopener,noreferrer"
                                                )
                                            }
                                            title="Opens this project's own standalone target platform, not a Workbench page"
                                        >
                                            Open Target Platform
                                        </Button>
                                    )}
                                    {project.modelId ? (
                                        <Button
                                            as={Link}
                                            to={WorkbenchRoutes.ModelEditor.path.replace(":id", project.modelId)}
                                            size="sm"
                                            variant="outline-primary"
                                            className="me-2"
                                        >
                                            Evolve this
                                        </Button>
                                    ) : (
                                        <span
                                            className="text-muted small me-2"
                                            title="Generated before this backend session started - nothing to link back to"
                                        >
                                            (model unknown)
                                        </span>
                                    )}
                                    <Button
                                        size="sm"
                                        variant="outline-danger"
                                        onClick={() => handleStop(project.projectId)}
                                        disabled={stoppingId === project.projectId}
                                    >
                                        {stoppingId === project.projectId ? "Stopping..." : "Stop"}
                                    </Button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </Table>
            )}
        </Container>
    );
};

export default DeployedAppsPage;
