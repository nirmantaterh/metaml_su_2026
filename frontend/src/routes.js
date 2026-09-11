
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
    // Legacy path retained for backward compatibility.
    ModelPage: { path: `/wb/model` },
    CreateModel: { path: `/wb/model/new` },
    // Legacy route for direct model editing.
    EditModel: { path: `/wb/model/edit` },
    ModelEditor: { path: `/wb/model/:id` },
    // Process generation listing.
    GenerateModelList: { path: `/wb/model/generate` },
    // Generated process launch listing.
    LaunchModelList: { path: `/wb/model/launch` },
    // Dedicated evolution dashboard for managing twin process connections, evolution, and event bridging.
    EvolvePage: { path: `/wb/evolve` },
    // Deployed workflow application monitoring and live integration dashboard.
    DeployedAppsPage: { path: `/wb/deployed` },
    // Tenant policy lifecycle management (Policy -> PolicyVersion -> PolicyRule).
    GovernancePolicies: { path: `/wb/governance/policies` },
    // Governance decision review - approval queue for policy-gated workflow actions.
    GovernanceApprovals: { path: `/wb/governance/approvals` },
};
