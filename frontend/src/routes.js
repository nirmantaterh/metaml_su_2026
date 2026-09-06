
// Common Routes
export const CommonRoutes = {
    Home: { path: "/" },
};

// Workbench Routes
export const WorkbenchRoutes = {
    CreateProject: { path: `/projects/create` },
    ProjectList: { path: `/projects/list` },
    DeleteProject: { path: `/projects/delete` },
    ProjectProcesses: { path: `/projects/:projectId/processes` },
    SamplePage: { path: `/wb/sample` },
    // legacy path, kept working for anyone with it bookmarked/loaded - CreateModel below is the one the Transmute menu actually links to now
    ModelPage: { path: `/wb/model` },
    CreateModel: { path: `/wb/model/new` },
    // Kept working for anyone with it bookmarked - EditProjectListPage still opens a saved model straight into the editor - but Transmute > Generate no longer links here; see GenerateModelList below for what it links to now.
    EditModel: { path: `/wb/model/edit` },
    ModelEditor: { path: `/wb/model/:id` },
    // Transmute > Generate: every saved process across every project, one row each, with its own Generate button - not an editor. See GenerateProjectListPage.
    GenerateModelList: { path: `/wb/model/generate` },
    // Transmute > Launch: every process that has already been generated, one row each, with its own Launch button. See LaunchProjectListPage.
    LaunchModelList: { path: `/wb/model/launch` },
    // Separate top-level menu per scope item 1 - Connect/Evolve/Bridge move here, out of the model editor's own toolbar
    EvolvePage: { path: `/wb/evolve` },
    // New scope item 5 (Evolve Workflow) - a distinct thing from EvolvePage above, which is the pre-existing twin workflow. This one is "connect to an existing deployed application".
    DeployedAppsPage: { path: `/wb/deployed` },
    // Tenant policy lifecycle UI (Policy -> PolicyVersion -> PolicyRule). Unrelated to the Evolve entries
// above: this manages what a tenant's policy says, not any single twin's execution.
    GovernancePolicies: { path: `/wb/governance/policies` },
    // This phase: the other half of governance - resolving the REQUIRE_APPROVAL decisions that policy above produces, not editing the policy itself.
    GovernanceApprovals: { path: `/wb/governance/approvals` },
};
