package com.metaml.workbench.service;

import com.metaml.workbench.codegen.GeneratedDelegate;
import com.metaml.workbench.dto.ProcessModelSummaryDto;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.LaunchedProject;
import com.metaml.workbench.governance.Approval;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.workflow.WorkflowState;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinAdvance;
import com.metaml.workbench.model.TwinActivityExecutionState;
import com.metaml.workbench.model.TwinProcess;

import java.util.List;

public interface WorkbenchService {

    String sampleMethod();

    // Default overload saving an unowned process model.
    default ProcessModel saveProcessModel(String id, String name, String bpmnXml) {
        return saveProcessModel(id, name, bpmnXml, null);
    }

    // Saves a process model with tenant ownership metadata.
    ProcessModel saveProcessModel(String id, String name, String bpmnXml, String tenantId);

    // The Project UI uses this overload so a saved process belongs to the project the user chose.
    default ProcessModel saveProcessModel(String id, String name, String bpmnXml, String tenantId, Long projectId) {
        return saveProcessModel(id, name, bpmnXml, tenantId);
    }

    // Saves a process model with an independently authored twin BPMN.
    ProcessModel saveProcessModelWithAuthoredTwin(String id, String name, String bpmnXml, String twinBpmnXml,
            String tenantId);

    default ProcessModel saveProcessModelWithAuthoredTwin(String id, String name, String bpmnXml, String twinBpmnXml,
            String tenantId, Long projectId) {
        return saveProcessModelWithAuthoredTwin(id, name, bpmnXml, twinBpmnXml, tenantId);
    }

    ProcessModel getProcessModel(String id);

    // Deletes a process model, its BPMN artifact, and generated projects.
    boolean deleteProcessModel(String modelId);

    // Non-mutating preflight used by project deletion, so a project is never partly deleted.
    boolean canDeleteProcessModel(String modelId);

    // Lists all saved process models, newest first.
    List<ProcessModel> listProcessModels();

    // Lists process model summaries including project ownership.
    List<ProcessModelSummaryDto> listProcessModelSummaries();

    // Generates Java Delegate source classes for service tasks.
    List<GeneratedDelegate> generateDelegates(String modelId);

    // Assembles and generates a standalone Spring Boot project on disk.
    GeneratedProject generateSpringBootProject(String modelId);

    // Launches a generated project as a background process.
    LaunchedProject launchGeneratedProject(String projectId);

    // Stops a running generated project process.
    boolean stopGeneratedProject(String projectId);

    // Lists all currently running generated Target Platform projects.
    List<LaunchedProject> listRunningProjects();

    // Computes workflow state for the Model -> Generate -> Launch pipeline.
    WorkflowState getWorkflowState(String modelId);

    // Launches both original and twin process instances.
    TwinProcess launchProcess(String modelId);

    TwinProcess getTwinProcess(String id);

    // Lists all twin processes belonging to the specified model identifier.
    List<TwinProcess> listTwinProcesses(String modelId);

    // Resolves TwinProcess by ID without re-executing status queries.
    TwinProcess findTwinProcess(String id);

    TwinProcess connectActivity(String twinProcessId, String originalActivityId, String twinActivityId);

    // Claims an activity for component integration, so the auto-bridge holds the twin at it instead
    // of autonomously binding DEFAULT_BRIDGE_AGENT_TYPE and running it the moment the original
    // reaches it. Without this claim there is no window in which an integration can win: evolution
    // is only legal once the activity has been reached, and that is exactly when the auto-bridge
    // fires. A null activityInstanceId claims the activity as a whole (used when the claim is made
    // before the activity has any runtime instance); a non-null one claims just that sibling.
    // The claim is released by the evolution that binds an agent for the visit. An activity with no
    // claim keeps today's autonomous default-bridge behavior unchanged.
    TwinProcess requestComponentIntegration(String twinProcessId, String activityId,
            String activityInstanceId);

    AgentDecision evolveActivity(String twinProcessId, String activityId, String agentType);

    // Evolves one specific runtime sibling of a parallel (non-sequential) multi-instance
    // activity. currentVisitId()'s most-recently-started heuristic (used by the 3-arg overload
    // above) cannot distinguish between concurrently active siblings that share the same
    // activityId; this overload trusts the caller-supplied activityInstanceId directly instead,
    // the same runtime activity-instance identity bridgeActivityEvent(twinId, activityId,
    // activityInstanceId) already accepts. A null activityInstanceId reproduces the 3-arg
    // overload's existing behavior exactly.
    AgentDecision evolveActivity(String twinProcessId, String activityId, String activityInstanceId,
            String agentType);

    // Approves a pending evolution decision.
    AgentDecision approveEvolution(String approvalId, String tenantId);

    AgentDecision rejectApproval(String approvalId, String tenantId);

    // Lists governance approvals for a given tenant.
    List<Approval> listApprovals(String tenantId);

    // manual Bridge button: works out which visit of the activity the original is on
    AgentDecision bridgeActivityEvent(String twinProcessId, String activityId);

    // Bridges an activity event for a specific visit instance.
    AgentDecision bridgeActivityEvent(String twinProcessId, String activityId, String activityInstanceId);

    // Read-only: what the bound ComponentExecutor has actually done for this activity so far, if
    // anything - bound agent, whether automation ran, and its real output. Never mutates twin or
    // process state. Takes the ORIGINAL activity id, exactly like evolveActivity/
    // bridgeActivityEvent above, and resolves the twin-side activity id internally.
    TwinActivityExecutionState getActivityExecutionState(String twinProcessId, String activityId);

    // Advances a twin activity by correlating its receive message.
    TwinAdvance advanceTwinActivity(String twinProcessId, String activityId);

    // Advances a parallel multi-instance twin activity.
    TwinAdvance advanceTwinActivity(String twinProcessId, String activityId, String originalExecutionId);

    // Completes all open user tasks on the original process instance.
    List<String> completeCurrentTasks(String twinProcessId);

    // Records agent execution event on twin process log.
    void recordAgentExecution(String twinProcessId, String variableName, Object agentName);

    // Lists available candidate agents from the authoritative Node Manager catalog.
    List<com.metaml.workbench.client.AgentAvailabilityResult> listAvailableAgents();
}
