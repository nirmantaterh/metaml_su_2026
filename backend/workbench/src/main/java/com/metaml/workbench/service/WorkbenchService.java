package com.metaml.workbench.service;

import com.metaml.workbench.codegen.GeneratedDelegate;
import com.metaml.workbench.dto.ProcessModelSummaryDto;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.LaunchedProject;
import com.metaml.workbench.governance.Approval;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.workflow.WorkflowState;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.ProxyTwinActivityMapping;
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

    default ProcessModel saveProcessModelWithAuthoredTwin(String id, String name, String bpmnXml, String twinBpmnXml,
            List<ProxyTwinActivityMapping> mappings, String tenantId, Long projectId) {
        return saveProcessModelWithAuthoredTwin(id, name, bpmnXml, twinBpmnXml, tenantId, projectId);
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

    // Claims an activity for component integration to pause auto-bridge advancement until manual evolution.
    // Pass null activityInstanceId to claim all instances of the activity, or a specific ID for a single sibling.
    TwinProcess requestComponentIntegration(String twinProcessId, String activityId,
            String activityInstanceId);

    AgentDecision evolveActivity(String twinProcessId, String activityId, String agentType);

    // Evolves a specific runtime sibling of a parallel multi-instance activity using its activityInstanceId.
    // When activityInstanceId is null, delegates to standard activity-level evolution.
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

    // Core service contract for model management, twin lifecycle, governance, and project generation.
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

    // Lists the same authoritative Node Manager catalog as CapabilityProvider records, so the
    // Phase 0 CapabilitySatisfaction mechanism can evaluate it against a Phase 1-derived
    // CapabilityContract requirement (MetaML Scope 6, Phase 2). Default so no other implementer of
    // this interface (test doubles included) is forced to add it.
    default List<com.metaml.workbench.capability.CapabilityProvider> listCapabilityProviders() {
        return com.metaml.workbench.client.CapabilityProviderCatalogReader.toCapabilityProviders(
                listAvailableAgents());
    }

    // Notifies the capability-gap lifecycle (MetaML Scope 6, Phase 5) that a provider bound to this
    // twin activity visit just executed successfully and passed the Phase 4 capability output
    // contract - the one event allowed to move a BOUND capability gap to RESOLVED. Called from
    // TwinAutomationDelegate right after CapabilityOutputPropagator.publish returns normally; never
    // called at all when execution fails or the output contract is violated, so those cases never
    // resolve a gap (Phase 5 section 17). Default no-op so no other implementer of this interface
    // (test doubles included) is forced to add it - the same convention listCapabilityProviders()
    // above already establishes.
    //
    // executedProviderId is the identity of the provider that actually ran for this visit - the
    // providerId of the CapabilityProvider TwinAutomationDelegate resolved and whose declared
    // contract the Phase 4 boundary just enforced - or null when no provider contract governed this
    // execution at all (the default/fallback automation path). It is carried here rather than
    // re-derived downstream so the gap lifecycle compares the provider that genuinely executed
    // against the one the gap is bound to; without it, "some automation succeeded at this visit"
    // would be indistinguishable from "the bound provider executed", and default automation could
    // resolve a gap it never satisfied.
    default void notifyCapabilityProviderExecutionSucceeded(String twinProcessId, String activityId,
            Object loopCounter, String executedProviderId) {
    }

    // The Workbench-authoritative, model-level current CapabilityBinding for each of the given
    // activity ids of the given (portable, BPMN-key-addressed) process definition, as populated by
    // WorkbenchServiceImpl.executeAfterGovernance from either evolution path (P7 Step 5). Activities
    // with no current binding are simply absent from the result - never fabricated, never an error.
    // Default empty so no other implementer of this interface (test doubles included) is forced to
    // add it, the same convention listCapabilityProviders() and
    // notifyCapabilityProviderExecutionSucceeded above already establish.
    default List<com.metaml.workbench.capability.runtime.CapabilityBinding> listCapabilityBindings(
            String processDefinitionKey, List<String> activityIds) {
        return List.of();
    }
}
