import React, { useCallback, useEffect, useState } from "react";
import { Container, Table, Button, Badge, Form, Row, Col, Card } from "react-bootstrap";

import { listTenants, listApprovals, approveEvolution, rejectApproval } from "../../services/workbench/WorkbenchService";
import ProcessSpinner from "../../components/common/ProcessSpinner";
import NoDataAvailable from "../../components/common/NoDataAvailable";
import ApprovalActionConfirmationModal from "../../components/modals/ApprovalActionConfirmationModal";

// UI for the approval workflow. Same tenant-selection mechanism as GovernancePoliciesPage (plain selector, not an identity - see that page's own comment on why: no login exists in this system yet). Polling: unlike the Policies page (no polling - policy state only ever changes from actions this same page just took), an approval can be resolved by someone else entirely (another tab, another operator) while this page sits open, so there's a real reason to keep asking. Same setInterval/1000ms pattern as ModelPage - gated on whether the last fetch had any PENDING, not on a manual toggle. Same three-colour language the workflow breadcrumb uses (see WorkflowProgress.css): amber for waiting, green for done, red for gone wrong. FAILED was "dark", which made the one status that represents an actual error the least visible thing in the table - quieter than REJECTED, which is a deliberate decision rather than a failure. APPROVED stays blue: it is neither finished nor failed, it is in flight.
const STATUS_VARIANT = { PENDING: "warning", APPROVED: "info", REJECTED: "danger", COMPLETED: "success", FAILED: "danger" };

const GovernanceApprovalsPage = () => {
    const [tenants, setTenants] = useState([]);
    const [tenantId, setTenantId] = useState("");

    const [approvals, setApprovals] = useState([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [busy, setBusy] = useState(false);
    const [status, setStatus] = useState(null); // { type: 'ok'|'err', text }

    const [confirming, setConfirming] = useState(null); // { action: 'approve'|'reject', approval }

    const errorText = (err) => err.response?.data?.message || err.message;

    const refreshTenants = useCallback(async () => {
        try {
            const res = await listTenants();
            setTenants(res.data || res || []);
        } catch (err) {
            setStatus({ type: "err", text: "Loading tenants failed: " + errorText(err) });
        }
    }, []);

    useEffect(() => {
        refreshTenants();
    }, [refreshTenants]);

    // authoritative every time - never inferred from an approve/reject response, which returns an AgentDecision, not the Approval itself (see WorkbenchService's own comment on why). showSpinner is only true for the explicit tenant-switch load below - a poll tick or a post-approve/reject refresh must never flip the spinner on, or the pending card a user is about to click would flicker out from under their cursor every second (same reason ModelPage's own refreshWorkflowState never touches a loading flag at all).
    const refreshApprovals = useCallback(async (forTenantId, showSpinner) => {
        if (!forTenantId) {
            setApprovals([]);
            return;
        }
        if (showSpinner) setLoading(true);
        try {
            const res = await listApprovals(forTenantId);
            setApprovals(res.data || res || []);
            setError(null);
        } catch (err) {
            setApprovals([]);
            setError(errorText(err));
        } finally {
            if (showSpinner) setLoading(false);
        }
    }, []);

    // switching tenants must not leave the previous tenant's approvals on screen for even one render - clear synchronously, then refetch for the new tenant
    useEffect(() => {
        setApprovals([]);
        refreshApprovals(tenantId, true);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [tenantId]);

    const pending = approvals.filter((a) => a.status === "PENDING");
    const history = approvals
        .filter((a) => a.status !== "PENDING")
        .slice()
        .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));

    const shouldPoll = Boolean(tenantId) && pending.length > 0;

    // Same pattern as ModelPage: gated on a boolean, immediate fetch when polling starts, plain setInterval at 1000ms, cleared on cleanup. No SSE/WebSocket infrastructure exists in this backend (confirmed in the Policies page's own audit) - polling is the correct choice here, not a shortcut around a real-time mechanism that was already available.
    useEffect(() => {
        if (!shouldPoll) return undefined;
        let cancelled = false;
        const forTenantId = tenantId;
        const interval = setInterval(() => {
            if (cancelled) return;
            refreshApprovals(forTenantId, false);
        }, 1000);
        return () => {
            cancelled = true;
            clearInterval(interval);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [shouldPoll, tenantId]);

    const askConfirm = (action, approval) => setConfirming({ action, approval });

    const runConfirmedAction = async () => {
        if (!confirming) return;
        const { action, approval } = confirming;
        setConfirming(null);
        setBusy(true);
        try {
            if (action === "approve") {
                await approveEvolution(approval.id, { tenantId });
                setStatus({ type: "ok", text: `Approval ${approval.id} approved.` });
            } else {
                await rejectApproval(approval.id, { tenantId });
                setStatus({ type: "ok", text: `Approval ${approval.id} rejected.` });
            }
        } catch (err) {
            setStatus({ type: "err", text: `${action === "approve" ? "Approve" : "Reject"} failed: ` + errorText(err) });
        } finally {
            // refresh from the backend regardless of success or failure - the list is what's authoritative, not any assumption about what the call did. No spinner here either - this refresh follows a click the user is still looking at the result of.
            await refreshApprovals(tenantId, false);
            setBusy(false);
        }
    };

    const statusClass = status?.type === "err" ? "text-danger" : "text-success";
    const fmt = (iso) => (iso ? new Date(iso).toLocaleString() : "—");

    return (
        <Container className="pt-5 mt-4">
            <div className="d-flex justify-content-between align-items-center mb-3">
                <h3 className="mb-0">Governance Approvals</h3>
                {status && <span className={statusClass}>{status.text}</span>}
            </div>

            <div className="border rounded p-3 mb-4">
                <div className="text-muted small mb-2">
                    Acting as tenant (not authenticated - no login exists in this system yet):
                </div>
                <Row className="g-2 align-items-center">
                    <Col xs="auto">
                        <Form.Select size="sm" value={tenantId} onChange={(e) => setTenantId(e.target.value)}>
                            <option value="">Select a tenant...</option>
                            {tenants.map((t) => (
                                <option key={t.id} value={t.id}>
                                    {t.name} ({t.id})
                                </option>
                            ))}
                        </Form.Select>
                    </Col>
                </Row>
            </div>

            {!tenantId && <NoDataAvailable dataType="approvals" errorMessage="Select a tenant above first." />}

            {tenantId && (
                <>
                    {loading && <ProcessSpinner message="Loading approvals..." />}
                    {!loading && error && <NoDataAvailable dataType="approvals" errorMessage={error} />}

                    {!loading && !error && (
                        <>
                            <h5>Pending Approvals</h5>
                            {pending.length === 0 && (
                                <NoDataAvailable dataType="pending approvals" errorMessage="Nothing waiting on this tenant right now." />
                            )}
                            {pending.map((a) => (
                                <Card key={a.id} className="mb-3">
                                    <Card.Body>
                                        <div className="d-flex justify-content-between align-items-start">
                                            <div>
                                                <strong>{a.action}</strong>{" "}
                                                <Badge bg={STATUS_VARIANT[a.status] || "secondary"}>{a.status}</Badge>
                                            </div>
                                        </div>
                                        <div className="small text-muted mt-2">
                                            <div>Approval: {a.id}</div>
                                            <div>Twin: {a.twinId} &nbsp; Activity: {a.activityId}</div>
                                            <div>Policy: {a.policyId} &nbsp; Version: {a.policyVersionNumber != null ? `v${a.policyVersionNumber}` : "—"}</div>
                                            <div>Reason: {a.reason}</div>
                                            <div>Created: {fmt(a.createdAt)}</div>
                                        </div>
                                        <div className="mt-3 d-flex justify-content-end gap-2">
                                            <Button size="sm" variant="danger" disabled={busy} onClick={() => askConfirm("reject", a)}>
                                                Reject
                                            </Button>
                                            <Button size="sm" variant="success" disabled={busy} onClick={() => askConfirm("approve", a)}>
                                                Approve
                                            </Button>
                                        </div>
                                    </Card.Body>
                                </Card>
                            ))}

                            <h5 className="mt-4">History</h5>
                            {history.length === 0 && (
                                <NoDataAvailable dataType="approval history" errorMessage="No resolved approvals for this tenant yet." />
                            )}
                            {history.length > 0 && (
                                <Table size="sm" hover responsive>
                                    <thead>
                                        <tr>
                                            <th>Status</th>
                                            <th>Action</th>
                                            <th>Twin</th>
                                            <th>Activity</th>
                                            <th>Policy</th>
                                            <th>Version</th>
                                            <th>Reason</th>
                                            <th>Created</th>
                                            <th>Resolved</th>
                                            <th>Resolution</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {history.map((a) => (
                                            <tr key={a.id}>
                                                <td><Badge bg={STATUS_VARIANT[a.status] || "secondary"}>{a.status}</Badge></td>
                                                <td>{a.action}</td>
                                                <td>{a.twinId}</td>
                                                <td>{a.activityId}</td>
                                                <td>{a.policyId || "—"}</td>
                                                <td>{a.policyVersionNumber != null ? `v${a.policyVersionNumber}` : "—"}</td>
                                                <td className="small">{a.reason}</td>
                                                <td className="small">{fmt(a.createdAt)}</td>
                                                <td className="small">{fmt(a.resolvedAt)}</td>
                                                <td className="small">{a.resolution || "—"}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </Table>
                            )}
                        </>
                    )}
                </>
            )}

            <ApprovalActionConfirmationModal
                show={Boolean(confirming)}
                onHide={() => setConfirming(null)}
                onConfirm={runConfirmedAction}
                action={confirming?.action}
                approval={confirming?.approval}
            />
        </Container>
    );
};

export default GovernanceApprovalsPage;
