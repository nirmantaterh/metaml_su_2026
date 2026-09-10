import { api } from "../../components/config/api";

export async function getSample() {
    try {
        const result = await api.get(`/wb/transmute/sample`);
        return result.data;
    } catch (error) {
        throw error;
    }
};

export async function saveModel(payload) {
    const result = await api.post(`/wb/transmute/model`, payload);
    return result.data;
}

// payload: { id?, name, bpmnXml, twinBpmnXml, tenantId } - persists an independently authored second BPMN (e.g. Main + Twin) alongside the primary one; see ProcessModel.hasAuthoredTwin().
export async function saveModelWithAuthoredTwin(payload) {
    const result = await api.post(`/wb/transmute/model/authored-twin`, payload);
    return result.data;
}

export async function getModel(id) {
    const result = await api.get(`/wb/transmute/model/${id}`);
    return result.data;
}

// 409 when a generated app is still running; removes model+projects but preserves twins, Camunda state, and workflow history.
export async function deleteModel(id) {
    const result = await api.delete(`/wb/transmute/model/${id}`);
    return result.data;
}

export async function listModels() {
    const result = await api.get(`/wb/transmute/model`);
    return result.data;
}

// Every saved process across every project, each row carrying its own project id/display name - backs the Transmute > Generate / Launch pickers, which listModels() above can't (it has no notion of a project at all).
export async function listModelSummaries() {
    const result = await api.get(`/wb/transmute/model/summaries`);
    return result.data;
}

// preview only — nothing written to disk; see generateProject
export async function generateDelegates(payload) {
    const result = await api.post(`/wb/transmute/generate`, payload);
    return result.data;
}

// assembles the real Target Harness Platform (a Spring Boot app under the hood) on the server; doesn't launch it
export async function generateProject(payload) {
    const result = await api.post(`/wb/transmute/generate-project`, payload);
    return result.data;
}

export async function launchProject(payload) {
    const result = await api.post(`/wb/transmute/launch-project`, payload);
    return result.data;
}

export async function stopProject(payload) {
    const result = await api.post(`/wb/transmute/stop-project`, payload);
    return result.data;
}

// flat list across every generated project; source for "connect to existing" workflows
export async function listRunningProjects() {
    const result = await api.get(`/wb/transmute/running-projects`);
    return result.data;
}

// backend's authoritative pipeline state; never 404s (no history → all pending)
export async function getWorkflowState(modelId) {
    const result = await api.get(`/wb/transmute/model/${modelId}/workflow`);
    return result.data;
}

export async function getTwin(id) {
    const result = await api.get(`/wb/transmute/twin/${id}`);
    return result.data;
}

// Fetches twin processes associated with a saved model.
export async function listTwinProcesses(modelId) {
    const result = await api.get(`/wb/transmute/twins`, { params: { modelId } });
    return result.data;
};

// Retrieves the runtime execution state for a specific activity on a twin process,
// including agent assignment, status, summary, output data, and active instances.
export async function getActivityExecutionState(twinProcessId, activityId) {
    const result = await api.get(
        `/wb/transmute/twin/${encodeURIComponent(twinProcessId)}/activity/${encodeURIComponent(activityId)}/execution`
    );
    return result.data;
};

// Aggregates execution state across connected activities for all twins associated with a model.
// Per-activity errors are captured to allow partial results for reachable activities.
export async function loadExecutionEvidence(modelId) {
    // Unwraps the standard ApiResponse envelope ({ message, data }).
    const unwrap = (res) => (res && res.data !== undefined ? res.data : res);

    const twins = unwrap(await listTwinProcesses(modelId)) || [];
    const withLinks = twins.filter((twin) => (twin.activityLinks || []).length > 0);

    return Promise.all(
        withLinks.map(async (twin) => ({
            twinId: twin.id,
            twinStatus: twin.status ?? null,
            launchedAt: twin.launchedAt ?? null,
            activities: await Promise.all(
                (twin.activityLinks || []).map(async (link) => {
                    try {
                        const state = unwrap(await getActivityExecutionState(twin.id, link.originalActivityId));
                        return { activityId: link.originalActivityId, state, error: null };
                    } catch (err) {
                        return { activityId: link.originalActivityId, state: null, error: "unavailable" };
                    }
                })
            ),
        }))
    );
};

export async function launchModel(payload) {
    const result = await api.post(`/wb/transmute/launch`, payload);
    return result.data;
}

// twinProcessId is the workbench twin identifier, not Camunda process-instance id.
export async function connectActivity(payload) {
    const result = await api.post(`/wb/transmute/connect`, payload);
    return result.data;
}

// Returns AgentDecision; check approved field for decision status.
export async function evolveActivity(payload) {
    const result = await api.post(`/wb/transmute/evolve`, payload);
    return result.data;
}

// Idempotent bridge invocation; returns AgentDecision.
export async function bridgeActivity(twinProcessId, activityId) {
    const result = await api.post(`/wb/transmute/bridge/${twinProcessId}/${activityId}`);
    return result.data;
}

// Completes open user tasks on original instance to advance execution.
export async function completeCurrentTasks(twinProcessId) {
    const result = await api.post(`/wb/transmute/complete-task/${twinProcessId}`);
    return result.data;
}

// Global server governance policy.
export async function getGovernancePolicy() {
    const result = await api.get(`/governance/policy`);
    return result.data;
}

// Replaces the policy denylist without merging.
export async function updateGovernancePolicy(deniedAgentTypes, maxEvolutionsPerTwin) {
    const result = await api.post(`/governance/policy`, { deniedAgentTypes, maxEvolutionsPerTwin });
    return result.data;
}

// Returns 404 if the twin was never launched.
export async function getGovernanceUsage(twinProcessId) {
    const result = await api.get(`/governance/usage/${twinProcessId}`);
    return result.data;
}

// Tenant-scoped policy lifecycle operations.

export async function listTenants() {
    const result = await api.get(`/governance/tenants`);
    return result.data;
}

export async function createTenant(payload) {
    const result = await api.post(`/governance/tenants`, payload);
    return result.data;
}

export async function listTenantPolicies(tenantId) {
    const result = await api.get(`/governance/tenants/${tenantId}/policies`);
    return result.data;
}

export async function createTenantPolicy(tenantId, payload) {
    const result = await api.post(`/governance/tenants/${tenantId}/policies`, payload);
    return result.data;
}

// tenantId is passed via query parameter for reads, request body for mutations.
export async function listPolicyVersions(policyId, tenantId) {
    const result = await api.get(`/governance/policies/${policyId}/policy-versions`, { params: { tenantId } });
    return result.data;
}

// Draft versions start with an empty rule set.
export async function createDraftVersion(policyId, payload) {
    const result = await api.post(`/governance/policies/${policyId}/policy-versions`, payload);
    return result.data;
}

// Returns 409 Conflict if the targeted version is not in DRAFT status.
export async function addPolicyRule(versionId, payload) {
    const result = await api.post(`/governance/policy-versions/${versionId}/rules`, payload);
    return result.data;
}

// Activates draft version and retires the previously active version.
export async function activatePolicyVersion(versionId, payload) {
    const result = await api.post(`/governance/policy-versions/${versionId}/activate`, payload);
    return result.data;
}

// Evaluates rules against the ACTIVE policy version.
export async function evaluatePolicy(payload) {
    const result = await api.post(`/governance/evaluate`, payload);
    return result.data;
}

// Approvals for policy-gated twin evolution workflows.

// Returns all approvals (pending and resolved).
export async function listApprovals(tenantId) {
    const result = await api.get(`/wb/transmute/evolve/approvals`, { params: { tenantId } });
    return result.data;
}

// Approves an evolution request; returns AgentDecision.
export async function approveEvolution(approvalId, payload) {
    const result = await api.post(`/wb/transmute/evolve/approvals/${approvalId}/approve`, payload);
    return result.data;
}

// Rejects an evolution request; returns AgentDecision.
export async function rejectApproval(approvalId, payload) {
    const result = await api.post(`/wb/transmute/evolve/approvals/${approvalId}/reject`, payload);
    return result.data;
}