package com.metaml.wbapi.controller.workbench;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.http.HttpStatus.*;

import java.util.List;
import java.util.NoSuchElementException;

import com.metaml.workbench.client.NodeManagerUnavailableException;
import com.metaml.workbench.codegen.GeneratedDelegate;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.LaunchedProject;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.TwinActivityExecutionState;
import com.metaml.workbench.workflow.WorkflowState;
import com.metaml.workbench.dto.ProcessModelSummaryDto;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;
import com.metaml.wbapi.payload.request.ConnectActivityRequest;
import com.metaml.wbapi.payload.request.EvolveActivityRequest;
import com.metaml.wbapi.payload.request.GenerateDelegatesRequest;
import com.metaml.wbapi.payload.request.GenerateProjectRequest;
import com.metaml.wbapi.payload.request.LaunchProcessRequest;
import com.metaml.wbapi.payload.request.LaunchProjectRequest;
import com.metaml.wbapi.payload.request.SaveProcessModelRequest;
import com.metaml.wbapi.payload.request.StopProjectRequest;
import com.metaml.wbapi.payload.response.ApiResponse;
import com.metaml.wbapi.payload.response.GeneratedProjectResponse;
import com.metaml.wbapi.utils.WorkbenchUrlMapping;
import com.metaml.wbapi.utils.FeedbackMessage;

@RestController
@RequestMapping(WorkbenchUrlMapping.WORKBENCH)
@RequiredArgsConstructor
public class WorkbenchController {
    private final WorkbenchService workbenchService;

    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_SAMPLE_ONLY)
    public ResponseEntity<ApiResponse> getSampleMethod() {
        try {
            String result = workbenchService.sampleMethod();
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, result));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_MODELE)
    public ResponseEntity<ApiResponse> saveModel(@RequestBody SaveProcessModelRequest request) {
        try {
            if (request.getProjectId() == null) {
                throw new IllegalArgumentException("A project must be selected before saving a process model");
            }
            ProcessModel model = workbenchService.saveProcessModel(request.getId(), request.getName(),
                    request.getBpmnXml(), request.getTenantId(), request.getProjectId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, model));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Entry point for a model with its own independently authored second BPMN (Manufacturing + Twin as two
    // files); without this, saveProcessModelWithAuthoredTwin was only reachable by calling the service
    // directly. Generation needs no separate endpoint - TRANSMUTE_GENERATE_PROJECT already branches on
    // whether the model has an authored twin.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_MODELE_AUTHORED_TWIN)
    public ResponseEntity<ApiResponse> saveModelWithAuthoredTwin(
            @RequestBody com.metaml.wbapi.payload.request.SaveAuthoredTwinProcessModelRequest request) {
        try {
            if (request.getProjectId() == null) {
                throw new IllegalArgumentException("A project must be selected before saving a process model");
            }
            ProcessModel model = workbenchService.saveProcessModelWithAuthoredTwin(request.getId(), request.getName(),
                    request.getBpmnXml(), request.getTwinBpmnXml(), request.getTenantId(), request.getProjectId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, model));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Backs "Edit Existing Project": a picker needs something to list, not just a lookup by an id the
    // user already has to know.
    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_MODELE)
    public ResponseEntity<ApiResponse> listModels() {
        try {
            List<ProcessModel> models = workbenchService.listProcessModels();
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, models));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Backs the Transmute > Generate / Launch pickers - see WorkbenchService.listProcessModelSummaries.
    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_MODEL_SUMMARIES)
    public ResponseEntity<ApiResponse> listModelSummaries() {
        try {
            List<ProcessModelSummaryDto> summaries = workbenchService.listProcessModelSummaries();
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, summaries));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Resolves model workflow execution state.
    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_WORKFLOW)
    public ResponseEntity<ApiResponse> getWorkflowState(@PathVariable String id) {
        try {
            WorkflowState state = workbenchService.getWorkflowState(id);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, state));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_MODELE + "/{id}")
    public ResponseEntity<ApiResponse> getModel(@PathVariable String id) {
        try {
            ProcessModel model = workbenchService.getProcessModel(id);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, model));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Deletes a process model from the catalog.
    @DeleteMapping(WorkbenchUrlMapping.TRANSMUTE_MODELE + "/{id}")
    public ResponseEntity<ApiResponse> deleteModel(@PathVariable String id) {
        try {
            workbenchService.deleteProcessModel(id);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(CONFLICT).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // First step of Model -> Generate -> Launch. Returns one generated Java Delegate class per
    // delegateExpression in the saved model.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_GENERATE)
    public ResponseEntity<ApiResponse> generateDelegates(@RequestBody GenerateDelegatesRequest request) {
        try {
            List<GeneratedDelegate> delegates = workbenchService.generateDelegates(request.getModelId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, delegates));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Generates a standalone Target Harness Platform project.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_GENERATE_PROJECT)
    public ResponseEntity<ApiResponse> generateSpringBootProject(@RequestBody GenerateProjectRequest request) {
        try {
            GeneratedProject project = workbenchService.generateSpringBootProject(request.getModelId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, GeneratedProjectResponse.from(project)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Launches a generated Target Platform project as a background process.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_LAUNCH_PROJECT)
    public ResponseEntity<ApiResponse> launchGeneratedProject(@RequestBody LaunchProjectRequest request) {
        try {
            LaunchedProject launched = workbenchService.launchGeneratedProject(request.getProjectId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, launched));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Stops a running generated Target Platform background process.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_STOP_PROJECT)
    public ResponseEntity<ApiResponse> stopGeneratedProject(@RequestBody StopProjectRequest request) {
        try {
            boolean wasRunning = workbenchService.stopGeneratedProject(request.getProjectId());
            if (!wasRunning) {
                return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(
                        "No running generated project with id " + request.getProjectId(), false));
            }
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Lists all currently running generated Target Platform projects.
    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_RUNNING_PROJECTS)
    public ResponseEntity<ApiResponse> listRunningProjects() {
        try {
            List<LaunchedProject> running = workbenchService.listRunningProjects();
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, running));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_LAUNCH)
    public ResponseEntity<ApiResponse> launchProcess(@RequestBody LaunchProcessRequest request) {
        try {
            TwinProcess twin = workbenchService.launchProcess(request.getModelId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, twin));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_TWIN + "/{id}")
    public ResponseEntity<ApiResponse> getTwinProcess(@PathVariable String id) {
        try {
            TwinProcess twin = workbenchService.getTwinProcess(id);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, twin));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Read-only: bound agent, whether automation has actually run for this activity, and its real
    // output (if any) - never mutates the twin. See WorkbenchService#getActivityExecutionState.
    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_TWIN + "/{id}/activity/{activityId}/execution")
    public ResponseEntity<ApiResponse> getActivityExecutionState(@PathVariable String id,
            @PathVariable String activityId) {
        try {
            TwinActivityExecutionState state = workbenchService.getActivityExecutionState(id, activityId);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, state));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_TWINS)
    public ResponseEntity<ApiResponse> listTwinProcesses(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String modelId) {
        try {
            List<TwinProcess> twins = workbenchService.listTwinProcesses(modelId);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, twins));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_CONNECT)
    public ResponseEntity<ApiResponse> connectActivity(@RequestBody ConnectActivityRequest request) {
        try {
            TwinProcess twin = workbenchService.connectActivity(request.getTwinProcessId(),
                    request.getOriginalActivityId(), request.getTwinActivityId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, twin));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Claims an activity for component integration so the auto-bridge holds the twin at it instead
    // of autonomously running DEFAULT_BRIDGE_AGENT_TYPE the moment the original reaches it. The
    // claim is released by the evolution that binds an agent. Reuses EvolveActivityRequest's shape
    // (twinProcessId/activityId/optional activityInstanceId); agentType is not required here
    // because the whole point is that the component has not been chosen yet.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_INTEGRATION_CLAIM)
    public ResponseEntity<ApiResponse> requestComponentIntegration(
            @RequestBody EvolveActivityRequest request) {
        try {
            TwinProcess twin = workbenchService.requestComponentIntegration(request.getTwinProcessId(),
                    request.getActivityId(), request.getActivityInstanceId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, twin));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_EVOLVE)
    public ResponseEntity<ApiResponse> evolveActivity(@RequestBody EvolveActivityRequest request) {
        try {
            // Scope 6 identity fix: an explicit activityInstanceId routes to the instance-scoped
            // overload so it targets exactly that runtime sibling; its absence preserves the
            // existing 3-argument behavior (currentVisitId()'s resolution) unchanged.
            String activityInstanceId = request.getActivityInstanceId();
            AgentDecision decision = (activityInstanceId != null && !activityInstanceId.isBlank())
                    ? workbenchService.evolveActivity(request.getTwinProcessId(), request.getActivityId(),
                            activityInstanceId, request.getAgentType())
                    : workbenchService.evolveActivity(request.getTwinProcessId(), request.getActivityId(),
                            request.getAgentType());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, decision));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (NodeManagerUnavailableException e) {
            return ResponseEntity.status(SERVICE_UNAVAILABLE).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_AGENTS)
    public ResponseEntity<ApiResponse> listAgents() {
        try {
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, workbenchService.listAvailableAgents()));
        } catch (NodeManagerUnavailableException e) {
            return ResponseEntity.status(SERVICE_UNAVAILABLE).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Lists pending evolution approvals for a tenant.
    @GetMapping(WorkbenchUrlMapping.TRANSMUTE_EVOLVE_APPROVALS)
    public ResponseEntity<ApiResponse> listApprovals(@org.springframework.web.bind.annotation.RequestParam String tenantId) {
        try {
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, workbenchService.listApprovals(tenantId)));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }


    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_EVOLVE_APPROVALS + "/{approvalId}/approve")
    public ResponseEntity<ApiResponse> approveEvolution(@PathVariable String approvalId,
            @RequestBody com.metaml.wbapi.payload.request.ResolveApprovalRequest request) {
        try {
            AgentDecision decision = workbenchService.approveEvolution(approvalId, request.getTenantId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, decision));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(CONFLICT).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (NodeManagerUnavailableException e) {
            return ResponseEntity.status(SERVICE_UNAVAILABLE).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_EVOLVE_APPROVALS + "/{approvalId}/reject")
    public ResponseEntity<ApiResponse> rejectApproval(@PathVariable String approvalId,
            @RequestBody com.metaml.wbapi.payload.request.ResolveApprovalRequest request) {
        try {
            AgentDecision decision = workbenchService.rejectApproval(approvalId, request.getTenantId());
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, decision));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(CONFLICT).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Bridges an activity event to trigger Twin advancement.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_BRIDGE + "/{twinId}/{activityId}")
    public ResponseEntity<ApiResponse> bridgeActivityEvent(@PathVariable String twinId,
            @PathVariable String activityId) {
        try {
            AgentDecision decision = workbenchService.bridgeActivityEvent(twinId, activityId);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, decision));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (NodeManagerUnavailableException e) {
            return ResponseEntity.status(SERVICE_UNAVAILABLE).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }

    // Completes open tasks for the original process instance.
    @PostMapping(WorkbenchUrlMapping.TRANSMUTE_COMPLETE_TASK + "/{twinId}")
    public ResponseEntity<ApiResponse> completeCurrentTasks(@PathVariable String twinId) {
        try {
            List<String> completed = workbenchService.completeCurrentTasks(twinId);
            return ResponseEntity.ok(new ApiResponse(FeedbackMessage.SUCCESS, completed));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(BAD_REQUEST).body(new ApiResponse(e.getMessage(), null));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(NOT_FOUND).body(new ApiResponse(e.getMessage(), null));
        } catch (Exception e) {
            return ResponseEntity.status(INTERNAL_SERVER_ERROR).body(new ApiResponse(e.getMessage(), null));
        }
    }
}
