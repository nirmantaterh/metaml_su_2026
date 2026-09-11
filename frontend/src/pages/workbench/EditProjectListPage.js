import React, { useCallback, useEffect, useState } from "react";
import { Alert, Container, Table, Button } from "react-bootstrap";
import { Link } from "react-router-dom";

import { deleteModel, listModels } from "../../services/workbench/WorkbenchService";
import { WorkbenchRoutes } from "../../routes";
import ProcessSpinner from "../../components/common/ProcessSpinner";
import NoDataAvailable from "../../components/common/NoDataAvailable";
import DeleteConfirmationModal from "../../components/modals/DeleteConfirmationModal";

// Model management view providing project selection for editing or deletion.
const EditProjectListPage = () => {
    const [models, setModels] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    // the model the confirmation modal is currently asking about, null when it's closed
    const [pendingDelete, setPendingDelete] = useState(null);
    const [deleting, setDeleting] = useState(false);
    // kept separate from `error` above: that one means "the list itself failed to load" and replaces the whole table, whereas a refused delete leaves a perfectly good list on screen and just needs to say why nothing happened
    const [deleteError, setDeleteError] = useState(null);
    // 409 specifically means "a generated app of this model is still running or launching", which is the one delete refusal the user can act on - and acting on it happens on another page
    const [deleteBlockedByRunningApp, setDeleteBlockedByRunningApp] = useState(false);

    const refresh = useCallback(async () => {
        try {
            const res = await listModels();
            const list = res.data || res;
            setModels(Array.isArray(list) ? list : []);
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

    const handleDelete = async () => {
        if (!pendingDelete) return;
        setDeleting(true);
        try {
            await deleteModel(pendingDelete.id);
            setDeleteError(null);
            setDeleteBlockedByRunningApp(false);
            setPendingDelete(null);
            await refresh();
        } catch (err) {
            // the 409 case (a generated app is still running) is a normal, actionable answer rather than a failure - the backend's own message already says what to do about it
            setDeleteError(err.response?.data?.message || err.message);
            setDeleteBlockedByRunningApp(err.response?.status === 409);
            setPendingDelete(null);
        } finally {
            setDeleting(false);
        }
    };

    return (
        <Container className="pt-5 mt-4">
            <h3 className="mb-4">Edit Existing Project</h3>
            {deleteError && (
                <Alert
                    variant="warning"
                    onClose={() => {
                        setDeleteError(null);
                        setDeleteBlockedByRunningApp(false);
                    }}
                    dismissible
                >
                    {deleteError}
                    {/* the 409 message says to stop the app first - this page is where to do that */}
                    {deleteBlockedByRunningApp && (
                        <>
                            {" "}
                            <Link to={WorkbenchRoutes.DeployedAppsPage.path}>
                                Go to Deployed Applications
                            </Link>{" "}
                            to stop it, then delete the model.
                        </>
                    )}
                </Alert>
            )}
            {loading && <ProcessSpinner message="Loading saved models..." />}
            {!loading && error && <NoDataAvailable dataType="saved models" errorMessage={error} />}
            {!loading && !error && models.length === 0 && (
                <NoDataAvailable dataType="saved models" errorMessage="Nothing saved yet - create a project first." />
            )}
            {!loading && !error && models.length > 0 && (
                <Table hover responsive>
                    <thead>
                        <tr>
                            <th>Name</th>
                            <th>Saved</th>
                            <th>Id</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {models.map((model) => (
                            <tr key={model.id}>
                                <td>{model.name || "Untitled"}</td>
                                <td>{model.createdAt ? new Date(model.createdAt).toLocaleString() : "-"}</td>
                                <td className="text-muted small">{model.id}</td>
                                <td className="text-end">
                                    <Button
                                        as={Link}
                                        to={WorkbenchRoutes.ModelEditor.path.replace(":id", model.id)}
                                        size="sm"
                                        variant="outline-primary"
                                        className="me-2"
                                    >
                                        Open
                                    </Button>
                                    <Button
                                        size="sm"
                                        variant="outline-danger"
                                        onClick={() => setPendingDelete(model)}
                                        disabled={deleting}
                                    >
                                        Delete
                                    </Button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </Table>
            )}
            <DeleteConfirmationModal
                show={pendingDelete !== null}
                onHide={() => setPendingDelete(null)}
                onConfirm={handleDelete}
                itemToDelete={
                    pendingDelete
                        ? `"${pendingDelete.name || "Untitled"}" and its generated projects (its process ` +
                          `history is kept, and the id can never be reused)`
                        : ""
                }
            />
        </Container>
    );
};

export default EditProjectListPage;
