package com.metaml.wbapi.utils;

public class WorkbenchUrlMapping {
    public static final String HOME = "/";
    public static final String API = "/api/v1";

    public static final String WORKBENCH = API + "/wb";

    /* ======== Start Transmute API ======== */
    public static final String WB_TRANSMUTE = "/transmute";
    public static final String TRANSMUTE_CREATE = WB_TRANSMUTE + "/create";
    public static final String TRANSMUTE_CONNECT = WB_TRANSMUTE + "/connect";
    public static final String TRANSMUTE_MODELE = WB_TRANSMUTE + "/model";
    // Endpoint for process model summary listings across projects.
    public static final String TRANSMUTE_MODEL_SUMMARIES = TRANSMUTE_MODELE + "/summaries";
    // Endpoint for saving process models with an independently authored twin.
    public static final String TRANSMUTE_MODELE_AUTHORED_TWIN = TRANSMUTE_MODELE + "/authored-twin";
    public static final String TRANSMUTE_GENERATE = WB_TRANSMUTE + "/generate";
    public static final String TRANSMUTE_GENERATE_PROJECT = WB_TRANSMUTE + "/generate-project";
    // Endpoint for launching a generated Spring Boot Target Harness project.
    public static final String TRANSMUTE_LAUNCH_PROJECT = WB_TRANSMUTE + "/launch-project";
    public static final String TRANSMUTE_STOP_PROJECT = WB_TRANSMUTE + "/stop-project";
    public static final String TRANSMUTE_RUNNING_PROJECTS = WB_TRANSMUTE + "/running-projects";
    // Endpoint for retrieving model workflow execution state.
    public static final String TRANSMUTE_WORKFLOW = TRANSMUTE_MODELE + "/{id}/workflow";
    public static final String TRANSMUTE_LAUNCH = WB_TRANSMUTE + "/launch";
    public static final String TRANSMUTE_EVOLVE = WB_TRANSMUTE + "/evolve";
    // Approval workflow - nested under evolve for paused evolutions
    public static final String TRANSMUTE_EVOLVE_APPROVALS = TRANSMUTE_EVOLVE + "/approvals";
    public static final String TRANSMUTE_TWIN = WB_TRANSMUTE + "/twin";
    public static final String TRANSMUTE_TWINS = WB_TRANSMUTE + "/twins";
    public static final String TRANSMUTE_BRIDGE = WB_TRANSMUTE + "/bridge";
    // Claims an activity for component integration so the auto-bridge holds at it (see
    // WorkbenchService#requestComponentIntegration).
    public static final String TRANSMUTE_INTEGRATION_CLAIM = WB_TRANSMUTE + "/integration-claim";
    public static final String TRANSMUTE_COMPLETE_TASK = WB_TRANSMUTE + "/complete-task";
    public static final String TRANSMUTE_AGENTS = WB_TRANSMUTE + "/agents";


    public static final String TRANSMUTE_SAMPLE_ONLY = WB_TRANSMUTE + "/sample";
    /* ======== End Transmute API ======== */

    /* ======== Start Governance API ======== */
    public static final String GOVERNANCE = API + "/governance";
    public static final String GOVERNANCE_POLICY = "/policy";
    public static final String GOVERNANCE_USAGE = "/usage";

    // Multi-tenant governance policy management endpoints
    public static final String GOVERNANCE_TENANTS = "/tenants";
    public static final String GOVERNANCE_PLATFORM_POLICIES = "/platform-policies";
    public static final String GOVERNANCE_POLICIES = "/policies";
    public static final String GOVERNANCE_POLICY_VERSIONS = "/policy-versions";

    // Policy decision evaluation endpoint
    public static final String GOVERNANCE_EVALUATE = "/evaluate";
    /* ======== End Governance API ======== */

    public static final String PROJECT = API + "/projects";
    public static final String CREATE_PROJECT = "/create";
    public static final String UPDATE_PROJECT = "/update/{projectId}";
    public static final String DELETE_PROJECT = "/delete/{projectId}";
    public static final String GET_PROJECT_DETAILS = "/{projectId}";
    public static final String GET_ALL_PROJECTS = "/all";
    public static final String GET_PROJECT_PROCESSES = "/{projectId}/process-models";
    public static final String GET_ALL_PROJECTS_BY_TEAM = "/{teamId}/all";
}
