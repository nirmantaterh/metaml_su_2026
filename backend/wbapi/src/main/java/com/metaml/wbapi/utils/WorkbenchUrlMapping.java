package com.metaml.wbapi.utils;

public class WorkbenchUrlMapping {
    public static final String HOME = "/";
    public static final String API = "/api/v1";

    public static final String WORKBENCH = API + "/wb";

    public static final String WB_TRANSMUTE = "/transmute";
    public static final String TRANSMUTE_CREATE = WB_TRANSMUTE + "/create";
    public static final String TRANSMUTE_CONNECT = WB_TRANSMUTE + "/connect";
    public static final String TRANSMUTE_MODELE = WB_TRANSMUTE + "/model";
    public static final String TRANSMUTE_MODEL_SUMMARIES = TRANSMUTE_MODELE + "/summaries";
    public static final String TRANSMUTE_MODELE_AUTHORED_TWIN = TRANSMUTE_MODELE + "/authored-twin";
    public static final String TRANSMUTE_GENERATE = WB_TRANSMUTE + "/generate";
    public static final String TRANSMUTE_GENERATE_PROJECT = WB_TRANSMUTE + "/generate-project";
    public static final String TRANSMUTE_LAUNCH_PROJECT = WB_TRANSMUTE + "/launch-project";
    public static final String TRANSMUTE_STOP_PROJECT = WB_TRANSMUTE + "/stop-project";
    public static final String TRANSMUTE_RUNNING_PROJECTS = WB_TRANSMUTE + "/running-projects";
    public static final String TRANSMUTE_WORKFLOW = TRANSMUTE_MODELE + "/{id}/workflow";
    public static final String TRANSMUTE_LAUNCH = WB_TRANSMUTE + "/launch";
    public static final String TRANSMUTE_EVOLVE = WB_TRANSMUTE + "/evolve";
    public static final String TRANSMUTE_EVOLVE_APPROVALS = TRANSMUTE_EVOLVE + "/approvals";
    public static final String TRANSMUTE_TWIN = WB_TRANSMUTE + "/twin";
    public static final String TRANSMUTE_TWINS = WB_TRANSMUTE + "/twins";
    public static final String TRANSMUTE_BRIDGE = WB_TRANSMUTE + "/bridge";
    // Claims an activity to pause auto-bridge advancement until an explicit component is bound.
    public static final String TRANSMUTE_INTEGRATION_CLAIM = WB_TRANSMUTE + "/integration-claim";
    public static final String TRANSMUTE_COMPLETE_TASK = WB_TRANSMUTE + "/complete-task";
    public static final String TRANSMUTE_AGENTS = WB_TRANSMUTE + "/agents";

    // P7 Step 5: the endpoint a standalone generated Target Platform calls to retrieve its own
    // Workbench-authoritative current CapabilityBinding(s) - by process key (portable across engines)
    // and activity id, never by any Workbench-internal twin process/instance id the Target Platform
    // has no way to know. See WorkbenchService.listCapabilityBindings.
    public static final String TRANSMUTE_BINDINGS = WB_TRANSMUTE + "/bindings";

    public static final String TRANSMUTE_SAMPLE_ONLY = WB_TRANSMUTE + "/sample";

    public static final String GOVERNANCE = API + "/governance";
    public static final String GOVERNANCE_POLICY = "/policy";
    public static final String GOVERNANCE_USAGE = "/usage";

    public static final String GOVERNANCE_TENANTS = "/tenants";
    public static final String GOVERNANCE_PLATFORM_POLICIES = "/platform-policies";
    public static final String GOVERNANCE_POLICIES = "/policies";
    public static final String GOVERNANCE_POLICY_VERSIONS = "/policy-versions";

    public static final String GOVERNANCE_EVALUATE = "/evaluate";

    // MetaML Scope 6, Phase 5: Capability Gap Lifecycle. CAPABILITY_GAP_REPORT is the "Workbench
    // gap endpoint" (locked architecture section 19) a generated Target Platform would call -
    // Workbench remains authoritative, the Target Platform never runs its own governance, approval,
    // evolution, or provider catalog. Wiring a Target Platform to actually call it is deferred (see
    // the P5 final report); this endpoint is the seam it would call into.
    public static final String CAPABILITY_GAPS = API + "/capability-gaps";
    public static final String CAPABILITY_GAP_REPORT = "/report";
    public static final String CAPABILITY_GAP_RECOMMEND = "/{gapId}/recommend";
    public static final String CAPABILITY_GAP_APPROVE = "/{gapId}/approve";
    public static final String CAPABILITY_GAP_REJECT = "/{gapId}/reject";
    public static final String CAPABILITY_GAP_BIND = "/{gapId}/bind";

    public static final String PROJECT = API + "/projects";
    public static final String CREATE_PROJECT = "/create";
    public static final String UPDATE_PROJECT = "/update/{projectId}";
    public static final String DELETE_PROJECT = "/delete/{projectId}";
    public static final String GET_PROJECT_DETAILS = "/{projectId}";
    public static final String GET_ALL_PROJECTS = "/all";
    public static final String GET_PROJECT_PROCESSES = "/{projectId}/process-models";
    public static final String GET_ALL_PROJECTS_BY_TEAM = "/{teamId}/all";
}
