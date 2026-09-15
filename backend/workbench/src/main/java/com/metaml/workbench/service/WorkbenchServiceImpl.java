package com.metaml.workbench.service;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.externaltask.ExternalTask;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.history.HistoricVariableUpdate;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.EventSubscription;
import org.camunda.bpm.engine.runtime.Execution;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.capability.binding.CapabilityBindingPersistenceException;
import com.metaml.workbench.capability.binding.CapabilityBindingRegistry;
import com.metaml.workbench.capability.gap.CapabilityGap;
import com.metaml.workbench.capability.gap.CapabilityGapService;
import com.metaml.workbench.capability.gap.GapOrigin;
import com.metaml.workbench.capability.runtime.CapabilityBinding;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
import com.metaml.workbench.codegen.GeneratedDelegate;
import com.metaml.workbench.codegen.InvalidDelegateExpressionException;
import com.metaml.workbench.dto.ProcessModelSummaryDto;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.client.NodeManagerUnavailableException;
import com.metaml.workbench.generation.DelegateWriteException;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.GeneratedProjectLaunchException;
import com.metaml.workbench.generation.LaunchedProject;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.governance.Approval;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.ApprovalStatus;
import com.metaml.workbench.governance.GovernanceRequest;
import com.metaml.workbench.governance.PolicyDecision;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.governance.PolicyEffect;
import com.metaml.workbench.governance.PolicyEvaluationException;
import com.metaml.workbench.model.ActivityLink;
import com.metaml.workbench.model.ActiveRuntimeInstance;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.AgentVariables;
import com.metaml.workbench.model.BusinessKeys;
import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.ProxyTwinActivityMapping;
import com.metaml.workbench.generation.ProxyTwinActivityMappingValidator;
import com.metaml.workbench.model.TwinAdvance;
import com.metaml.workbench.model.TwinActivityExecutionState;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.StageError;
import com.metaml.workbench.workflow.StageEvent;
import com.metaml.workbench.workflow.StageStatus;
import com.metaml.workbench.workflow.WorkflowStage;
import com.metaml.workbench.workflow.WorkflowState;
import com.metaml.workbench.workflow.WorkflowStateTracker;

import jakarta.annotation.PostConstruct;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class WorkbenchServiceImpl implements WorkbenchService {

    private static final Logger logger = LoggerFactory.getLogger(WorkbenchServiceImpl.class);

    // Default agent type used during automatic activity bridging.
    private static final String DEFAULT_BRIDGE_AGENT_TYPE = "validator";

    // Action name used in EVOLVE_TWIN GovernanceRequest evaluations.
    private static final String EVOLVE_TWIN_ACTION = "EVOLVE_TWIN";

    // Allowed model ID pattern (alphanumeric, dash, underscore).
    private static final Pattern SAFE_MODEL_ID = Pattern.compile("[A-Za-z0-9_-]+");

    // In-memory cache mirrored to disk by WorkbenchStateStore and persisted to H2 by ProcessModelArchiveStore.
    private final Map<String, ProcessModel> processModels = new ConcurrentHashMap<>();
    private final Map<String, TwinProcess> twinProcesses = new ConcurrentHashMap<>();
    // Tracks in-flight evolutions per visit so multi-instance activity instances do not block one another.
    private final Map<String, Boolean> evolutionsInFlight = new ConcurrentHashMap<>();
    // Restored on startup from disk and model workflow stage records.
    private final Map<String, GeneratedProject> generatedProjects = new ConcurrentHashMap<>();
    // Maps generated project IDs to their originating model ID.
    private final Map<String, String> modelIdByProjectId = new ConcurrentHashMap<>();
    // Serializes generate and delete operations per model ID.
    private final Map<String, Object> modelLocks = new ConcurrentHashMap<>();
    private final NodeManagerClient nodeManagerClient;
    private final GovernanceService governanceService;
    private final PolicyDecisionEngine policyDecisionEngine;
    private final ApprovalService approvalService;
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final ExternalTaskService externalTaskService;
    private final TwinModelGenerator twinModelGenerator;
    private final WorkbenchStateStore stateStore;
    private final ProcessModelFileStore modelFileStore;
    private final ProcessModelArchiveStore processModelArchiveStore;
    private final DelegateClassGenerator delegateClassGenerator;
    private final SpringBootProjectGenerator springBootProjectGenerator;
    private final SpringBootProjectLauncher springBootProjectLauncher;
    // Authoritative tracker for Model -> Generate -> Launch workflow pipeline progression.
    private final WorkflowStateTracker workflowStateTracker;

    // An enterprise deployment may point the Workbench at an externally managed Target Platform.
    // When absent, generated projects retain their actual local launcher endpoint below.
    @Value("${workbench.target-platform.runtime-url:}")
    private String configuredTargetPlatformRuntimeUrl;
    // Optional by construction (MetaML Scope 6, Phase 5): field-injected rather than a constructor
    // parameter so every existing test that builds this class with `new WorkbenchServiceImpl(...)`
    // keeps compiling and running unchanged. CapabilityGapService itself depends on WorkbenchService
    // (this class, through the interface) to reach evolveActivity/findTwinProcess/
    // listCapabilityProviders, so a direct field reference here is circular at the Spring wiring
    // level - confirmed by a real ApplicationContext startup failure
    // (BeanCurrentlyInCreationException) when a wbapi bean happened to request capabilityGapService
    // before workbenchServiceImpl. An ObjectProvider defers the actual getBean(...) call to first
    // use, after the whole context has finished starting, exactly like TwinAutomationDelegate's own
    // ObjectProvider<CapabilityOutputContractSource> already does for the same reason (see that
    // class). Empty in every test that does not register a CapabilityGapService bean, in which case
    // notifyCapabilityProviderExecutionSucceeded below is a no-op - exactly as if no capability gap
    // were ever open for that execution.
    @Autowired(required = false)
    private ObjectProvider<CapabilityGapService> capabilityGapService;

    // Optional by construction, same reasoning as capabilityGapService above: field-injected so
    // every existing `new WorkbenchServiceImpl(...)` test keeps compiling unchanged. No circularity
    // risk here (CapabilityBindingRegistry depends only on CapabilityBindingStore, never on
    // WorkbenchService), so a direct reference rather than an ObjectProvider is enough. Null in any
    // test that does not register a CapabilityBindingRegistry bean, in which case
    // recordCapabilityBindingOrCompensate is a no-op and evolution behaves exactly as it did before
    // P7 Step 5 existed.
    @Autowired(required = false)
    private CapabilityBindingRegistry capabilityBindingRegistry;

    public WorkbenchServiceImpl(NodeManagerClient nodeManagerClient, GovernanceService governanceService,
            PolicyDecisionEngine policyDecisionEngine, ApprovalService approvalService,
            RuntimeService runtimeService, RepositoryService repositoryService, HistoryService historyService,
            TaskService taskService, ExternalTaskService externalTaskService, TwinModelGenerator twinModelGenerator,
            WorkbenchStateStore stateStore, ProcessModelFileStore modelFileStore,
            ProcessModelArchiveStore processModelArchiveStore,
            DelegateClassGenerator delegateClassGenerator, SpringBootProjectGenerator springBootProjectGenerator,
            SpringBootProjectLauncher springBootProjectLauncher, WorkflowStateTracker workflowStateTracker) {
        this.nodeManagerClient = nodeManagerClient;
        this.governanceService = governanceService;
        this.policyDecisionEngine = policyDecisionEngine;
        this.approvalService = approvalService;
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.externalTaskService = externalTaskService;
        this.twinModelGenerator = twinModelGenerator;
        this.stateStore = stateStore;
        this.modelFileStore = modelFileStore;
        this.processModelArchiveStore = processModelArchiveStore;
        this.delegateClassGenerator = delegateClassGenerator;
        this.springBootProjectGenerator = springBootProjectGenerator;
        this.springBootProjectLauncher = springBootProjectLauncher;
        this.workflowStateTracker = workflowStateTracker;
    }

    @PostConstruct
    void restoreState() {
        WorkbenchStateStore.Snapshot snapshot = stateStore.load();
        // The H2-backed archive is the authoritative source for process models.
        for (ProcessModel model : processModelArchiveStore.findAll()) {
            processModels.put(model.getId(), model);
            // Only backfill a model with no history; preserving existing history maintains recorded progress.
            if (workflowStateTracker.hasNoHistory(model.getId())) {
                workflowStateTracker.record(model.getId(), WorkflowStage.MODEL, StageStatus.COMPLETED, null,
                        model.getCreatedAt());
            }
        }
        for (TwinProcess twin : snapshot.twins()) {
            twinProcesses.put(twin.getId(), twin);
        }
        restoreGeneratedProjects();
        reconcileApprovedApprovals();
    }

    private void restoreGeneratedProjects() {
        for (GeneratedProject project : springBootProjectGenerator.scanExisting()) {
            generatedProjects.put(project.projectId(), project);
        }
        if (generatedProjects.isEmpty()) {
            return;
        }
        for (ProcessModel model : processModels.values()) {
            WorkflowState state = workflowStateTracker.stateFor(model.getId());
            for (String projectId : allGeneratedProjectIdsOf(state)) {
                // Only associate when the project directory exists on disk.
                if (projectId != null && generatedProjects.containsKey(projectId)) {
                    modelIdByProjectId.put(projectId, model.getId());
                }
            }
            if (aRecordedLaunchPortIsStillListening(state)) {
                logger.warn("Skipping generated-project cleanup for model {} on startup - a port it previously "
                        + "launched on is still listening, so a generated app from before this restart may still "
                        + "be running. Its projects will be collected once that port is free.", model.getId());
                continue;
            }
            cleanupSupersededProjects(model.getId(), state);
            promoteCurrentGeneration(model.getId());
        }
    }

    // Checks whether any recorded launch port is currently active.
    private boolean aRecordedLaunchPortIsStillListening(WorkflowState state) {
        for (StageEvent event : state.history()) {
            if (event.stage() != WorkflowStage.LAUNCH || event.detail() == null
                    || !event.detail().startsWith("port ")) {
                continue;
            }
            try {
                int port = Integer.parseInt(event.detail().substring("port ".length()).trim());
                if (springBootProjectLauncher.somethingIsListeningOn(port)) {
                    return true;
                }
            } catch (NumberFormatException notAPort) {
                // a LAUNCH detail that isn't "port N" is nothing to probe, not an error
            }
        }
        return false;
    }

    // Computes current project ID from the latest completed GENERATE workflow stage.
    private static String currentProjectIdOf(WorkflowState state) {
        String current = null;
        for (StageEvent event : state.history()) {
            if (event.stage() == WorkflowStage.GENERATE && event.status() == StageStatus.COMPLETED
                    && event.detail() != null) {
                current = event.detail();
            }
        }
        return current;
    }

    private static List<String> supersededProjectIdsOf(WorkflowState state) {
        List<String> superseded = new ArrayList<>(allGeneratedProjectIdsOf(state));
        superseded.remove(currentProjectIdOf(state));
        return superseded;
    }

    private static List<String> allGeneratedProjectIdsOf(WorkflowState state) {
        List<String> projectIds = new ArrayList<>();
        for (StageEvent event : state.history()) {
            if (event.stage() != WorkflowStage.GENERATE || event.status() != StageStatus.COMPLETED) {
                continue;
            }
            String projectId = event.detail();
            if (projectId != null && !projectIds.contains(projectId)) {
                projectIds.add(projectId);
            }
        }
        return projectIds;
    }

    private void cleanupSupersededProjects(String modelId) {
        cleanupSupersededProjects(modelId, workflowStateTracker.stateFor(modelId));
    }

    private void cleanupSupersededProjects(String modelId, WorkflowState state) {
        for (String projectId : supersededProjectIdsOf(state)) {
            try {
                deleteIfSuperseded(modelId, projectId);
            } catch (RuntimeException e) {
                logger.warn("Could not clean up superseded generated project {} for model {}: {}",
                        projectId, modelId, e.toString());
            }
        }
    }

    private void deleteIfSuperseded(String modelId, String projectId) {
        // Do not delete a project recorded as belonging to a different model.
        String owner = modelIdByProjectId.get(projectId);
        if (owner != null && !owner.equals(modelId)) {
            logger.warn("Not deleting generated project {} while cleaning up model {} - it is recorded as "
                    + "belonging to model {}", projectId, modelId, owner);
            return;
        }
        boolean wasIdle = springBootProjectLauncher.runIfIdle(projectId, () -> {
            // Re-check current project inside the lock to ensure it was not updated concurrently.
            if (projectId.equals(currentProjectIdOf(workflowStateTracker.stateFor(modelId)))) {
                logger.info("Generated project {} became the current generation for model {} before it could be "
                        + "cleaned up - retaining it", projectId, modelId);
                return;
            }
            if (springBootProjectGenerator.delete(projectId)) {
                // Remove from in-memory index once directory deletion succeeds.
                generatedProjects.remove(projectId);
                modelIdByProjectId.remove(projectId, modelId);
            }
        });
        if (!wasIdle) {
            // Retain running superseded projects until they stop.
            logger.info("Retaining superseded generated project {} for model {} - it is still running or "
                    + "being launched; it will be collected when it next stops", projectId, modelId);
        }
    }

    // Reconciles interrupted approvals on restart against committed process variables.
    private void reconcileApprovedApprovals() {
        List<Approval> approved = approvalService.listAllApproved();
        if (approved.isEmpty()) {
            return;
        }
        for (Approval approval : approved) {
            TwinProcess twin = twinProcesses.get(approval.twinId());
            if (twin == null) {
                approvalService.markFailed(approval.id(), "twin no longer exists after restart");
                logger.warn("Reconciled approval {} as FAILED: twin {} no longer exists", approval.id(),
                        approval.twinId());
                continue;
            }
            String evolvedAgentVariable = AgentVariables.evolvedAgent(approval.twinActivityId(),
                    approval.loopCounter());
            if (evolvedAgentVariableIsSet(twin, evolvedAgentVariable)) {
                // Side effect already executed prior to restart; mark COMPLETED without re-executing.
                approvalService.markCompleted(approval.id(),
                        "reconciled on restart - '" + evolvedAgentVariable + "' was already set");
                twin.getEventLog().add("Approval " + approval.id()
                        + " reconciled as COMPLETED on restart (already executed before crash)");
                logger.info("Reconciled approval {} as COMPLETED: '{}' already set", approval.id(),
                        evolvedAgentVariable);
                continue;
            }
            // Variable was not set prior to restart; execute evolution.
            GovernanceDecision reservation = governanceService.reserveEvolutionSlot(approval.twinId(),
                    approval.agentType());
            if (!reservation.isAllowed()) {
                approvalService.markFailed(approval.id(), "reconciliation: " + reservation.getReason());
                logger.warn("Reconciled approval {} as FAILED: {}", approval.id(), reservation.getReason());
                continue;
            }
            boolean succeeded = false;
            try {
                twin.getEventLog().add("Approval " + approval.id()
                        + " reconciled on restart - never executed before the crash, running it now");
                AgentDecision decision = executeAfterGovernance(twin, approval.twinId(), approval.activityId(),
                        approval.twinActivityId(), approval.loopCounter(), approval.agentType());
                succeeded = decision.isApproved();
                if (succeeded) {
                    approvalService.markCompleted(approval.id(), decision.getAgentName());
                } else {
                    approvalService.markFailed(approval.id(), decision.getReason());
                }
                logger.info("Reconciled approval {} as {}", approval.id(), succeeded ? "COMPLETED" : "FAILED");
            } catch (RuntimeException e) {
                approvalService.markFailed(approval.id(), "reconciliation failed: " + e.getMessage());
                logger.warn("Reconciled approval {} as FAILED: {}", approval.id(), e.getMessage());
            } finally {
                if (!succeeded) {
                    governanceService.releaseEvolutionSlot(approval.twinId());
                }
            }
        }
        persistState();
    }

    // Persists twin processes. Process models are persisted by processModelArchiveStore (H2) at their own save site, not here.
    private void persistState() {
        stateStore.save(twinProcesses.values());
    }

    @Override
    public String sampleMethod() {
        return "this is a sample method";
    }

    @Override
    public ProcessModel saveProcessModel(String id, String name, String bpmnXml, String tenantId) {
        return doSaveProcessModelEntry(id, name, bpmnXml, null, tenantId, null);
    }

    @Override
    public ProcessModel saveProcessModel(String id, String name, String bpmnXml, String tenantId, Long projectId) {
        return doSaveProcessModelEntry(id, name, bpmnXml, null, tenantId, projectId);
    }

    @Override
    public ProcessModel saveProcessModelWithAuthoredTwin(String id, String name, String bpmnXml,
            String twinBpmnXml, String tenantId) {
        if (twinBpmnXml == null || twinBpmnXml.isBlank()) {
            throw new IllegalArgumentException("Authored twin bpmnXml must not be blank");
        }
        return doSaveProcessModelEntry(id, name, bpmnXml, twinBpmnXml, tenantId, null);
    }

    @Override
    public ProcessModel saveProcessModelWithAuthoredTwin(String id, String name, String bpmnXml,
            String twinBpmnXml, String tenantId, Long projectId) {
        return saveProcessModelWithAuthoredTwin(id, name, bpmnXml, twinBpmnXml, List.of(), tenantId, projectId);
    }

    @Override
    public ProcessModel saveProcessModelWithAuthoredTwin(String id, String name, String bpmnXml,
            String twinBpmnXml, List<ProxyTwinActivityMapping> mappings, String tenantId, Long projectId) {
        if (twinBpmnXml == null || twinBpmnXml.isBlank()) {
            throw new IllegalArgumentException("Authored twin bpmnXml must not be blank");
        }
        ProxyTwinActivityMappingValidator.validate(bpmnXml, twinBpmnXml, mappings);
        return doSaveProcessModelEntry(id, name, bpmnXml, twinBpmnXml, mappings, tenantId, projectId);
    }

    private ProcessModel doSaveProcessModelEntry(String id, String name, String bpmnXml, String twinBpmnXml,
            String tenantId, Long projectId) {
        return doSaveProcessModelEntry(id, name, bpmnXml, twinBpmnXml, List.of(), tenantId, projectId);
    }

    private ProcessModel doSaveProcessModelEntry(String id, String name, String bpmnXml, String twinBpmnXml,
            List<ProxyTwinActivityMapping> mappings, String tenantId, Long projectId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Process model name must not be blank");
        }
        if (bpmnXml == null || bpmnXml.isBlank()) {
            throw new IllegalArgumentException("Process model bpmnXml must not be blank");
        }
        String modelId;
        if (id != null && !id.isBlank()) {
            if (!SAFE_MODEL_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Process model id may only contain letters, digits, "
                        + "'-' and '_': " + id);
            }
            // A supplied ID is an update request. Creation intentionally has no caller-supplied ID,
            // so a stale or invented ID can never create a second logical model.
            if (!processModels.containsKey(id)) {
                throw new NoSuchElementException("Process model not found: " + id);
            }
            modelId = id;
        } else {
            modelId = UUID.randomUUID().toString();
        }

        workflowStateTracker.record(modelId, WorkflowStage.MODEL, StageStatus.IN_PROGRESS, null);
        try {
            return doSaveProcessModel(modelId, name, bpmnXml, twinBpmnXml, mappings, tenantId, projectId);
        } catch (RuntimeException e) {
            workflowStateTracker.record(modelId, WorkflowStage.MODEL, StageStatus.FAILED, e.getMessage(),
                    new StageError(e.getClass().getSimpleName(), "SAVE_MODEL", null, null, null, null, null));
            throw e;
        }
    }

    private ProcessModel doSaveProcessModel(String modelId, String name, String bpmnXml, String twinBpmnXml,
            String tenantId, Long projectId) {
        return doSaveProcessModel(modelId, name, bpmnXml, twinBpmnXml, List.of(), tenantId, projectId);
    }

    private ProcessModel doSaveProcessModel(String modelId, String name, String bpmnXml, String twinBpmnXml,
            List<ProxyTwinActivityMapping> mappings, String tenantId, Long projectId) {
        Deployment deployment;
        try {
            deployment = repositoryService.createDeployment()
                    .name(name)
                    .addInputStream(modelId + ".bpmn",
                            new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)))
                    .deploy();
        } catch (ProcessEngineException e) {
            throw new IllegalArgumentException("Invalid BPMN XML, could not deploy to process engine: "
                    + e.getMessage());
        }

        ProcessDefinition definition;
        try {
            definition = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deployment.getId())
                    .singleResult();
        } catch (ProcessEngineException e) {
            // singleResult() throws if the XML declares multiple executable processes.
            discardDeployment(deployment.getId());
            throw new IllegalArgumentException(
                    "BPMN must declare exactly one executable bpmn:process element: " + e.getMessage());
        }
        if (definition == null) {
            discardDeployment(deployment.getId());
            throw new IllegalArgumentException(
                    "BPMN process must have isExecutable=\"true\" on the bpmn:process element");
        }
        if (twinBpmnXml != null) {
            try {
                requireExactlyOneExecutableProcess(twinBpmnXml);
            } catch (RuntimeException e) {
                discardDeployment(deployment.getId());
                throw e;
            }
        }

        ProcessModel model = new ProcessModel(modelId, name, bpmnXml, twinBpmnXml, mappings, Instant.now(),
                definition.getId(), tenantId);
        // A new save creates the ID once; an existing ID replaces its current in-memory definition.
        // Archive persistence below performs the matching one-row upsert and preserves project ownership.
        ProcessModel existing = processModels.put(modelId, model);
        Path bpmnFilePath;
        Path twinBpmnFilePath = null;
        try {
            // Code generation requires a .bpmn file on disk.
            bpmnFilePath = modelFileStore.save(modelId, bpmnXml);
            if (twinBpmnXml != null) {
                twinBpmnFilePath = modelFileStore.saveTwin(modelId, twinBpmnXml);
            }
        } catch (RuntimeException e) {
            // Roll back in-memory state and deployment if file persistence fails.
            if (existing == null) {
                processModels.remove(modelId, model);
            } else {
                processModels.replace(modelId, model, existing);
            }
            discardDeployment(deployment.getId());
            throw e;
        }
        // ProcessModelArchiveStore (H2) is the authoritative persistence for process models.
        processModelArchiveStore.save(model, bpmnFilePath, twinBpmnFilePath, projectId);
        persistState();
        workflowStateTracker.record(modelId, WorkflowStage.MODEL, StageStatus.COMPLETED, null);
        logger.info("Saved process model {} and deployed process definition {}", modelId, definition.getId());
        detectStaticCapabilityGapsIfApplicable(modelId, definition.getId());
        return model;
    }

    // MetaML Scope 6, Phase 5: static capability-gap detection, wired into the one existing point in
    // the Workbench model lifecycle where a BPMN model is validated, deployed, and fully available -
    // the same place already used to record MODEL workflow-stage completion two lines above. Purely
    // additive: reads the just-deployed BpmnModelInstance back from the process engine (the same
    // repositoryService.getBpmnModelInstance(definitionId) pattern TwinAutomationDelegate already
    // uses) rather than reparsing bpmnXml, and never changes `model`, the workflow stage already
    // recorded, or anything returned to the caller. No scheduler, no polling: this runs exactly once
    // per successful save, exactly like every other post-save side effect in this method. No-op when
    // no CapabilityGapService bean is registered, or on any failure - a detection failure must never
    // fail an otherwise-valid model save.
    private void detectStaticCapabilityGapsIfApplicable(String modelId, String processDefinitionId) {
        CapabilityGapService gapService = capabilityGapService == null ? null : capabilityGapService.getIfAvailable();
        if (gapService == null) {
            return;
        }
        try {
            BpmnModelInstance deployedModel = repositoryService.getBpmnModelInstance(processDefinitionId);
            if (deployedModel == null) {
                return;
            }
            List<CapabilityGap> gaps = gapService.detectStatic(deployedModel, modelId);
            if (!gaps.isEmpty()) {
                logger.info("Static capability-gap detection recorded {} gap(s) for process model {}",
                        gaps.size(), modelId);
            }
        } catch (RuntimeException e) {
            logger.warn("Could not run static capability-gap detection for process model {}: {}",
                    modelId, e.getMessage());
        }
    }

    // Validates that the BPMN XML declares exactly one executable process definition.
    private static void requireExactlyOneExecutableProcess(String bpmnXml) {
        BpmnModelInstance model;
        try {
            model = org.camunda.bpm.model.bpmn.Bpmn.readModelFromStream(
                    new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid BPMN XML: " + e.getMessage());
        }
        long executableCount = model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Process.class)
                .stream()
                .filter(org.camunda.bpm.model.bpmn.instance.Process::isExecutable)
                .count();
        if (executableCount != 1) {
            throw new IllegalArgumentException(
                    "BPMN must declare exactly one executable bpmn:process element (found " + executableCount + ")");
        }
    }

    // Delete deployment from engine repository if model validation or persistence fails.
    private void discardDeployment(String deploymentId) {
        try {
            repositoryService.deleteDeployment(deploymentId, true);
        } catch (ProcessEngineException e) {
            logger.warn("Could not remove deployment {} after rejecting the model: {}",
                    deploymentId, e.getMessage());
        }
    }

    @Override
    public ProcessModel getProcessModel(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Process model id must not be blank");
        }
        ProcessModel model = processModels.get(id);
        if (model == null) {
            throw new NoSuchElementException("Process model not found: " + id);
        }
        return model;
    }

    private Object modelLockFor(String modelId) {
        return modelLocks.computeIfAbsent(modelId, id -> new Object());
    }

    @Override
    public boolean deleteProcessModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Process model id must not be blank");
        }
        synchronized (modelLockFor(modelId)) {
            ProcessModel model = processModels.get(modelId);
            if (model == null) {
                throw new NoSuchElementException("Process model not found: " + modelId);
            }
            // Remove all generations belonging to this model once deleted.
            List<String> projectIds = allGeneratedProjectIdsOf(workflowStateTracker.stateFor(modelId));
            boolean deleted = springBootProjectLauncher.runIfAllIdle(projectIds, () -> {
                for (String projectId : projectIds) {
                    // Avoid deleting a project directory belonging to another model.
                    String owner = modelIdByProjectId.get(projectId);
                    if (owner != null && !owner.equals(modelId)) {
                        logger.warn("Not deleting generated project {} while deleting model {} - it is recorded "
                                + "as belonging to model {}", projectId, modelId, owner);
                        continue;
                    }
                    springBootProjectGenerator.delete(projectId);
                    generatedProjects.remove(projectId);
                    modelIdByProjectId.remove(projectId, modelId);
                }
                processModels.remove(modelId, model);
                modelFileStore.delete(modelId);
                processModelArchiveStore.deleteByModelId(modelId);
                persistState();
            });
            if (!deleted) {
                throw new IllegalStateException("Cannot delete process model " + modelId
                        + " - one of its generated applications is running or is being launched. Stop it first, "
                        + "then delete the model.");
            }
            // Retain workflow history so the retired model ID is not reused.
            logger.info("Deleted process model {} and {} generated project(s); its workflow history, twins, "
                    + "Camunda state and approvals are retained", modelId, projectIds.size());
            return true;
        }
    }

    @Override
    public boolean canDeleteProcessModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        synchronized (modelLockFor(modelId)) {
            if (!processModels.containsKey(modelId)) {
                return false;
            }
            return springBootProjectLauncher.runIfAllIdle(
                    allGeneratedProjectIdsOf(workflowStateTracker.stateFor(modelId)), () -> { });
        }
    }

    @Override
    public List<ProcessModel> listProcessModels() {
        return processModels.values().stream()
                .sorted(Comparator.comparing(ProcessModel::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public List<ProcessModelSummaryDto> listProcessModelSummaries() {
        // Reads summaries directly from the archive store.
        return processModelArchiveStore.findAllSummaries();
    }

    @Override
    public List<GeneratedDelegate> generateDelegates(String modelId) {
        ProcessModel model = getProcessModel(modelId);
        return delegateClassGenerator.generate(model.getBpmnXml());
    }

    @Override
    public GeneratedProject generateSpringBootProject(String modelId) {
        // Guard against concurrent model deletion during project generation.
        synchronized (modelLockFor(modelId)) {
            return doGenerateSpringBootProject(modelId);
        }
    }

    private GeneratedProject doGenerateSpringBootProject(String modelId) {
        ProcessModel model = getProcessModel(modelId);
        workflowStateTracker.record(modelId, WorkflowStage.GENERATE, StageStatus.IN_PROGRESS, null);
        try {
            GeneratedProject project;
            if (model.hasAuthoredTwin()) {
                // Authored twin BPMN is packaged directly into the generated target platform.
                project = springBootProjectGenerator.generateWithAuthoredTwin(model.getBpmnXml(),
                        model.getAuthoredTwinBpmnXml(), model.getProxyTwinActivityMappings(),
                        projectDisplayNameFor(modelId), model.getName());
            } else {
                List<GeneratedDelegate> delegates = delegateClassGenerator.generate(model.getBpmnXml(),
                        SpringBootProjectGenerator.DELEGATE_PACKAGE);
                project = springBootProjectGenerator.generate(model.getBpmnXml(), delegates,
                        projectDisplayNameFor(modelId), model.getName());
            }
            generatedProjects.put(project.projectId(), project);
            modelIdByProjectId.put(project.projectId(), modelId);
            // Record project ID in the stage detail for reference during project launch.
            workflowStateTracker.record(modelId, WorkflowStage.GENERATE, StageStatus.COMPLETED, project.projectId());
            logger.info("Generated Target Harness Platform {} for model {}", project.projectId(), modelId);
            // Clean up earlier generations once new generation completes successfully.
            cleanupSupersededProjects(modelId);
            promoteCurrentGeneration(modelId);
            project = generatedProjects.get(project.projectId());
            return project;
        } catch (RuntimeException e) {
            workflowStateTracker.record(modelId, WorkflowStage.GENERATE, StageStatus.FAILED, e.getMessage(),
                    generateErrorFrom(e));
            throw e;
        }
    }

    // Preserves element ID when available for targeted navigation in the editor.
    private static StageError generateErrorFrom(RuntimeException e) {
        if (e instanceof DelegateWriteException dwe) {
            return new StageError(e.getClass().getSimpleName(), "GENERATE_PROJECT", null, null, null,
                    "${" + dwe.beanName() + "}", dwe.bpmnElementId());
        }
        // Capture invalid delegate expression element for targeted editor navigation.
        if (e instanceof InvalidDelegateExpressionException bad) {
            return new StageError(e.getClass().getSimpleName(), "GENERATE_PROJECT", null, null, null,
                    bad.rawExpression(), bad.bpmnElementId());
        }
        return new StageError(e.getClass().getSimpleName(), "GENERATE_PROJECT", null, null, null, null, null);
    }

    @Override
    public LaunchedProject launchGeneratedProject(String projectId) {
        GeneratedProject project = generatedProjects.get(projectId);
        if (project == null) {
            throw new NoSuchElementException("Generated project not found: " + projectId
                    + " - it may not exist, or its generated-project directory may be missing or unreadable");
        }
        // If model ID is unresolvable, launch proceeds without recording workflow state.
        String modelId = modelIdByProjectId.get(projectId);
        if (modelId != null) {
            workflowStateTracker.record(modelId, WorkflowStage.LAUNCH, StageStatus.IN_PROGRESS, null);
        }
        try {
            // Target platform messaging is only enabled when a twin process is present.
            boolean generatedProjectHasMessaging = projectHasMessagingLayer(project.directory());
            Map<String, String> extraEnv = generatedProjectHasMessaging
                    ? Map.of("METAML_MESSAGING_ENABLED", "true")
                    : Map.of();
            LaunchedProject launched = springBootProjectLauncher.launch(project, extraEnv);
            ProcessModel model = modelId != null ? processModels.get(modelId) : null;
            String displayName = model != null ? model.getName() : project.displayName();
            LaunchedProject result = withTargetPlatformUrl(launched, modelId,
                    displayName != null ? displayName : launched.processKey());
            if (modelId != null) {
                workflowStateTracker.record(modelId, WorkflowStage.LAUNCH, StageStatus.COMPLETED,
                        "port " + launched.port());
            }
            return result;
        } catch (RuntimeException e) {
            if (modelId != null && workflowStateTracker.stateFor(modelId).stages()
                    .get(WorkflowStage.LAUNCH).status() == StageStatus.IN_PROGRESS) {
                workflowStateTracker.record(modelId, WorkflowStage.LAUNCH, StageStatus.FAILED, e.getMessage(),
                        launchErrorFrom(e, projectId));
            }
            throw e;
        }
    }

    // Checks whether the project directory contains generated messaging configuration.
    private static boolean projectHasMessagingLayer(Path projectDirectory) {
        if (!Files.isDirectory(projectDirectory)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(projectDirectory)) {
            return walk.anyMatch(path -> path.getFileName() != null
                    && "RabbitMqConfig.java".equals(path.getFileName().toString()));
        } catch (IOException e) {
            return false;
        }
    }

    // projectId is always known here; port and exitCode only when the launcher attached them, so a launch
    // that failed before a port was chosen leaves them null rather than reporting one never attempted.
    private static StageError launchErrorFrom(RuntimeException e, String projectId) {
        if (e instanceof GeneratedProjectLaunchException launchFailure) {
            return new StageError(e.getClass().getSimpleName(), "LAUNCH_PROJECT", projectId, launchFailure.port(),
                    launchFailure.exitCode(), null, null);
        }
        return new StageError(e.getClass().getSimpleName(), "LAUNCH_PROJECT", projectId, null, null, null, null);
    }

    @Override
    public boolean stopGeneratedProject(String projectId) {
        // Stopping queries the launcher directly; process state does not survive restart.
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        // Capture port before stop drops the launcher entry.
        String portDetail = springBootProjectLauncher.find(projectId)
                .map(launched -> "port " + launched.port())
                .orElse(null);
        boolean wasRunning = springBootProjectLauncher.stop(projectId);
        String modelId = modelIdByProjectId.get(projectId);
        if (wasRunning && modelId != null) {
            workflowStateTracker.record(modelId, WorkflowStage.LAUNCH, StageStatus.STOPPED, portDetail);
        }
        // Clean up superseded projects once running process terminates.
        if (modelId != null) {
            cleanupSupersededProjects(modelId);
            promoteCurrentGeneration(modelId);
        }
        return wasRunning;
    }

    private String projectDisplayNameFor(String modelId) {
        return processModelArchiveStore.findProjectDisplayName(modelId).orElse("Project");
    }

    private void promoteCurrentGeneration(String modelId) {
        String currentProjectId = currentProjectIdOf(workflowStateTracker.stateFor(modelId));
        if (currentProjectId == null) {
            return;
        }
        springBootProjectLauncher.runIfIdle(currentProjectId, () -> {
            GeneratedProject current = generatedProjects.get(currentProjectId);
            if (current != null) {
                generatedProjects.replace(currentProjectId,
                        springBootProjectGenerator.promoteToCanonicalPath(current));
            }
        });
    }

    @Override
    public WorkflowState getWorkflowState(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        return workflowStateTracker.stateFor(modelId);
    }

    @Override
    public List<LaunchedProject> listRunningProjects() {
        return springBootProjectLauncher.listRunning().stream()
                .map(launched -> {
                    String modelId = modelIdByProjectId.get(launched.projectId());
                    ProcessModel model = modelId != null ? processModels.get(modelId) : null;
                    GeneratedProject gp = generatedProjects.get(launched.projectId());
                    String displayName = model != null ? model.getName() : (gp != null ? gp.displayName() : launched.displayName());
                    return withTargetPlatformUrl(launched, modelId,
                            displayName != null ? displayName : launched.processKey());
                })
                .toList();
    }

    private LaunchedProject withTargetPlatformUrl(LaunchedProject launched, String modelId, String displayName) {
        return new LaunchedProject(launched.projectId(), launched.processKey(), launched.port(), launched.launchedAt(),
                modelId, displayName, resolveTargetPlatformUrl(configuredTargetPlatformRuntimeUrl, launched.port()));
    }

    static String resolveTargetPlatformUrl(String configuredUrl, int localPort) {
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            String candidate = configuredUrl.trim();
            try {
                URI endpoint = URI.create(candidate);
                String scheme = endpoint.getScheme();
                if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                        && endpoint.getRawAuthority() != null) {
                    return endpoint.toString();
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to the known local runtime instead of returning an unusable link.
            }
            logger.warn("Ignoring invalid workbench.target-platform.runtime-url value");
        }
        return localPort > 0 ? "http://127.0.0.1:" + localPort : null;
    }

    @Override
    public TwinProcess launchProcess(String modelId) {
        ProcessModel model = getProcessModel(modelId);
        boolean twinWasAlreadyDeployed = repositoryService.createDeploymentQuery()
                .deploymentName(twinDeploymentName(model)).count() > 0;
        ProcessDefinition twinDefinition = deployTwinDefinition(model);
        try {
            return launch(model, twinDefinition.getId());
        } catch (RuntimeException e) {
            // Only delete deployment if created by this launch call to avoid cascading live instances.
            if (!twinWasAlreadyDeployed) {
                discardDeployment(twinDefinition.getDeploymentId());
            }
            throw e;
        }
    }

    private static String twinDeploymentName(ProcessModel model) {
        return model.getName() + " (twin " + model.getId() + ")";
    }

    // Twin is generated from deployed definition to prevent drift from original.
    private ProcessDefinition deployTwinDefinition(ProcessModel model) {
        BpmnModelInstance twinModel;
        try {
            twinModel = twinModelGenerator.generate(
                    repositoryService.getBpmnModelInstance(model.getProcessDefinitionId()));
        } catch (ProcessEngineException e) {
            throw new IllegalArgumentException("Could not read the deployed definition "
                    + model.getProcessDefinitionId() + " to build a twin from it: " + e.getMessage());
        }

        Deployment deployment;
        try {
            // Duplicate filtering requires stable deployment and resource names across launches.
            deployment = repositoryService.createDeployment()
                    .name(twinDeploymentName(model))
                    .enableDuplicateFiltering(true)
                    .addModelInstance(model.getId() + "-twin.bpmn", twinModel)
                    .deploy();
        } catch (ProcessEngineException e) {
            throw new IllegalArgumentException("Generated twin BPMN did not deploy: " + e.getMessage());
        }

        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .singleResult();
        if (definition == null) {
            discardDeployment(deployment.getId());
            throw new IllegalStateException("Generated twin BPMN deployed but produced no process definition");
        }
        logger.info("Deployed generated twin definition {} for model {}", definition.getId(), model.getId());
        return definition;
    }

    // Launches original and twin process instances with correlated business keys.
    private TwinProcess launch(ProcessModel model, String twinDefinitionId) {
        String twinId = UUID.randomUUID().toString();

        ProcessInstance original = runtimeService.startProcessInstanceById(
                model.getProcessDefinitionId(), BusinessKeys.originalKey(twinId));
        ProcessInstance twinInstance;
        try {
            twinInstance = runtimeService.startProcessInstanceById(twinDefinitionId, BusinessKeys.twinKey(twinId));
        } catch (RuntimeException e) {
            // Terminate original instance if twin instance startup fails.
            try {
                runtimeService.deleteProcessInstance(original.getProcessInstanceId(),
                        "Twin process instance failed to start; rolling back the original");
            } catch (ProcessEngineException cleanupFailure) {
                logger.warn("Could not roll back original process instance {} after twin start failed: {}",
                        original.getProcessInstanceId(), cleanupFailure.getMessage());
            }
            throw e;
        }

        TwinProcess twin = new TwinProcess();
        twin.setId(twinId);
        twin.setModelId(model.getId());
        // Twin inherits tenant ID from originating process model.
        twin.setTenantId(model.getTenantId());
        twin.setProcessDefinitionId(model.getProcessDefinitionId());
        twin.setTwinProcessDefinitionId(twinDefinitionId);
        twin.setOriginalProcessId(original.getProcessInstanceId());
        twin.setTwinProcessId(twinInstance.getProcessInstanceId());
        twin.setStatus("RUNNING");
        twin.setLaunchedAt(Instant.now());
        twin.getEventLog().add("Deployed process definition " + model.getProcessDefinitionId()
                + "; started original process instance " + original.getProcessInstanceId()
                + " and twin process instance " + twinInstance.getProcessInstanceId()
                + " on definition " + twinDefinitionId);

        twinProcesses.put(twin.getId(), twin);
        persistState();
        logger.info("Launched twin {} (original instance {}, twin instance {} on definition {}) for model {}",
                twin.getId(), original.getProcessInstanceId(), twinInstance.getProcessInstanceId(),
                twinDefinitionId, model.getId());
        return twin;
    }

    @Override
    public TwinProcess getTwinProcess(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Twin process id must not be blank");
        }
        TwinProcess twin = twinProcesses.get(id);
        if (twin == null) {
            throw new NoSuchElementException("Twin process not found: " + id);
        }
        // Recompute status from live instances on every read.
        twin.setStatus(computeStatus(twin));
        return twin;
    }

    @Override
    public List<TwinProcess> listTwinProcesses(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        return twinProcesses.values().stream()
                .filter(twin -> modelId.equals(twin.getModelId()))
                .peek(twin -> twin.setStatus(computeStatus(twin)))
                .toList();
    }

    @Override
    public TwinProcess findTwinProcess(String id) {
        return id == null ? null : twinProcesses.get(id);
    }

    private String computeStatus(TwinProcess twin) {
        boolean originalRunning = isInstanceRunning(twin.getOriginalProcessId());
        boolean twinRunning = isInstanceRunning(twin.getTwinProcessId());
        if (originalRunning && twinRunning) {
            return "RUNNING";
        }
        if (!originalRunning && !twinRunning) {
            return "ENDED";
        }
        return originalRunning ? "ORIGINAL_RUNNING_TWIN_ENDED" : "TWIN_RUNNING_ORIGINAL_ENDED";
    }

    private boolean isInstanceRunning(String processInstanceId) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult() != null;
    }

    private void requireActivityInDefinition(String processDefinitionId, String activityId, String fieldName) {
        BpmnModelInstance modelInstance = repositoryService.getBpmnModelInstance(processDefinitionId);
        if (modelInstance.getModelElementById(activityId) == null) {
            throw new IllegalArgumentException(
                    fieldName + " '" + activityId + "' does not exist in the deployed process definition");
        }
    }

    @Override
    public TwinProcess connectActivity(String twinProcessId, String originalActivityId, String twinActivityId) {
        if (twinProcessId == null || twinProcessId.isBlank()) {
            throw new IllegalArgumentException("twinProcessId must not be blank");
        }
        if (originalActivityId == null || originalActivityId.isBlank()) {
            throw new IllegalArgumentException("originalActivityId must not be blank");
        }
        if (twinActivityId == null || twinActivityId.isBlank()) {
            throw new IllegalArgumentException("twinActivityId must not be blank");
        }

        TwinProcess twin = twinProcesses.get(twinProcessId);
        if (twin == null) {
            throw new NoSuchElementException("Twin process not found: " + twinProcessId);
        }

        requireActivityInDefinition(twin.getProcessDefinitionId(), originalActivityId, "originalActivityId");
        // Validate activity exists in the twin's deployed definition.
        requireActivityInDefinition(twin.getTwinProcessDefinitionId(), twinActivityId, "twinActivityId");

        synchronized (twin) {
            twin.getActivityLinks().stream()
                    .filter(link -> link.getTwinActivityId().equals(twinActivityId))
                    .filter(link -> !link.getOriginalActivityId().equals(originalActivityId))
                    .findFirst()
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("Twin activity '" + twinActivityId
                                + "' is already connected to original activity '" + existing.getOriginalActivityId()
                                + "'; connect original activity '" + originalActivityId
                                + "' to a different twin activity instead of sharing this one");
                    });

            // Replace existing link to maintain one-to-one mapping.
            twin.getActivityLinks().removeIf(link -> link.getOriginalActivityId().equals(originalActivityId));
            twin.getActivityLinks().add(new ActivityLink(originalActivityId, twinActivityId));
        }
        twin.getEventLog().add("Connected original activity " + originalActivityId
                + " to twin activity " + twinActivityId);
        persistState();
        logger.info("Connected activity {} to twin activity {} on twin process {}",
                originalActivityId, twinActivityId, twinProcessId);
        return twin;
    }

    @Override
    public TwinProcess requestComponentIntegration(String twinProcessId, String activityId,
            String activityInstanceId) {
        if (activityId == null || activityId.isBlank()) {
            throw new IllegalArgumentException("activityId must not be blank");
        }
        TwinProcess twin = getTwinProcess(twinProcessId);
        String twinActivityId = twin.findTwinActivityId(activityId).orElseThrow(
                () -> new IllegalArgumentException("Activity " + activityId
                        + " is not connected to a twin activity"));

        // Only resolve loop counter when targeting a specific runtime instance.
        Object loopCounter = activityInstanceId == null ? null
                : loopCounterOf(twin, activityId, activityInstanceId);
        String pendingVariable = AgentVariables.integrationPending(twinActivityId, loopCounter);
        try {
            runtimeService.setVariable(twin.getTwinProcessId(), pendingVariable, Boolean.TRUE);
        } catch (ProcessEngineException e) {
            twin.getEventLog().add("Could not claim activity " + activityId
                    + " for integration on twin instance " + twin.getTwinProcessId()
                    + " (it may have already ended): " + e.getMessage());
            persistState();
            throw new IllegalStateException("Twin process instance " + twin.getTwinProcessId()
                    + " could not be updated (it may have already ended), so activity " + activityId
                    + " could not be claimed for integration", e);
        }
        twin.getEventLog().add("Activity " + activityId
                + " claimed for component integration; auto-bridge will hold at it until the"
                + " integration resolves");
        persistState();
        logger.info("Activity {} on twin {} claimed for component integration (variable {})",
                activityId, twinProcessId, pendingVariable);
        return twin;
    }

    // Checks whether an integration claim is pending for this activity visit or activity.
    private boolean integrationPendingFor(TwinProcess twin, String twinActivityId, Object loopCounter) {
        Map<String, Object> variables;
        try {
            variables = runtimeService.getVariables(twin.getTwinProcessId());
        } catch (ProcessEngineException e) {
            return false;
        }
        return Boolean.TRUE.equals(variables.get(AgentVariables.integrationPending(twinActivityId, null)))
                || (loopCounter != null && Boolean.TRUE.equals(
                        variables.get(AgentVariables.integrationPending(twinActivityId, loopCounter))));
    }

    // Release both per-visit and whole-activity claims upon binding.
    private void releaseIntegrationClaim(TwinProcess twin, String twinActivityId, Object loopCounter) {
        try {
            runtimeService.removeVariable(twin.getTwinProcessId(),
                    AgentVariables.integrationPending(twinActivityId, null));
            if (loopCounter != null) {
                runtimeService.removeVariable(twin.getTwinProcessId(),
                        AgentVariables.integrationPending(twinActivityId, loopCounter));
            }
        } catch (ProcessEngineException e) {
            logger.debug("Could not release integration claim for twin activity {} on twin {}: {}",
                    twinActivityId, twin.getId(), e.getMessage());
        }
    }

    @Override
    public AgentDecision evolveActivity(String twinProcessId, String activityId, String agentType) {
        return evolveActivity(twinProcessId, activityId, null, agentType);
    }

    @Override
    public AgentDecision evolveActivity(String twinProcessId, String activityId, String activityInstanceId,
            String agentType) {
        if (agentType == null || agentType.isBlank()) {
            throw new IllegalArgumentException("agentType must not be blank");
        }
        if (activityId == null || activityId.isBlank()) {
            throw new IllegalArgumentException("activityId must not be blank");
        }

        TwinProcess twin = getTwinProcess(twinProcessId);
        try {
            return evolveOnce(twin, twinProcessId, activityId, activityInstanceId, agentType);
        } finally {
            persistState();
        }
    }

    private AgentDecision evolveOnce(TwinProcess twin, String twinProcessId, String activityId,
            String activityInstanceId, String agentType) {
        twin.getEventLog().add("Original activity " + activityId
                + " requested evolution with agent type " + agentType);

        String twinActivityId = twin.findTwinActivityId(activityId).orElse(null);
        if (twinActivityId == null) {
            twin.getEventLog().add("Evolution blocked: activity " + activityId
                    + " is not connected to a twin activity");
            logger.warn("TWIN_SKIPPED: evolve blocked for activity {} on twin {}: activity not connected",
                    activityId, twinProcessId);
            return new AgentDecision(agentType, false, null,
                    "Activity not connected to twin process");
        }

        String visitId = activityInstanceId != null && !activityInstanceId.isBlank()
                ? activityInstanceId
                : currentVisitId(twin, activityId);
        if (visitId == null) {
            twin.getEventLog().add("Evolution blocked: activity " + activityId
                    + " has not actually been reached in the original process instance "
                    + twin.getOriginalProcessId());
            logger.info("Evolve blocked for activity {} on twin {}: activity not reached in real process instance",
                    activityId, twinProcessId);
            return new AgentDecision(agentType, false, null,
                    "Activity not yet reached in the original process instance");
        }

        // Reject evolution if original process instance has already completed.
        if (!isInstanceRunning(twin.getOriginalProcessId())) {
            twin.getEventLog().add("Evolution blocked: original process instance "
                    + twin.getOriginalProcessId() + " has already ended");
            logger.warn("TWIN_SKIPPED: evolve blocked for activity {} on twin {}: "
                            + "original process instance {} has ended",
                    activityId, twinProcessId, twin.getOriginalProcessId());
            return new AgentDecision(agentType, false, null,
                    "Original process instance has ended; activity cannot be evolved");
        }

        twin.getEventLog().add("Twin activity received evolution request");

        String claim = evolutionClaim(twinProcessId, visitId);
        if (evolutionsInFlight.putIfAbsent(claim, Boolean.TRUE) != null) {
            twin.getEventLog().add("Evolution skipped: activity " + activityId
                    + " is already being evolved right now");
            logger.info("Evolve skipped for activity {} on twin {}: another evolution is in flight",
                    activityId, twinProcessId);
            return new AgentDecision(agentType, false, null,
                    "Activity " + activityId + " is already being evolved");
        }
        try {
            // Sets evolvedAgent variable upon approval, signaling that this visit has been bound.
            return runEvolution(twin, twinProcessId, activityId, twinActivityId,
                    loopCounterOf(twin, activityId, visitId), agentType);
        } finally {
            evolutionsInFlight.remove(claim);
        }
    }

    private static String evolutionClaim(String twinProcessId, String activityInstanceId) {
        return twinProcessId + ":" + activityInstanceId;
    }

    @Override
    public AgentDecision bridgeActivityEvent(String twinProcessId, String activityId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        String visitId = currentVisitId(twin, activityId);
        return bridgeAndAdvance(twin, twinProcessId, activityId, visitId);
    }

    private String originalExecutionIdForVisit(TwinProcess twin, String activityId, String activityInstanceId) {
        if (activityInstanceId == null) {
            return null;
        }
        ActivityInstance tree = runtimeService.getActivityInstance(twin.getOriginalProcessId());
        if (tree == null) {
            return null;
        }
        for (ActivityInstance visit : tree.getActivityInstances(activityId)) {
            if (visit.getId().equals(activityInstanceId) && visit.getExecutionIds().length > 0) {
                return visit.getExecutionIds()[0];
            }
        }
        return null;
    }

    @Override
    public AgentDecision bridgeActivityEvent(String twinProcessId, String activityId, String activityInstanceId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        return bridgeAndAdvance(twin, twinProcessId, activityId, activityInstanceId);
    }

    // Reconstructs execution state from process variables recorded by TwinAutomationDelegate.
    @Override
    public TwinActivityExecutionState getActivityExecutionState(String twinProcessId, String activityId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        if (activityId == null || activityId.isBlank()) {
            throw new IllegalArgumentException("activityId must not be blank");
        }

        // Query active runtime instances from the original process instance.
        List<ActiveRuntimeInstance> activeInstances = activeInstancesOf(twin, activityId);

        String twinActivityId = twin.findTwinActivityId(activityId).orElse(null);
        if (twinActivityId == null) {
            return new TwinActivityExecutionState(activityId, null, null, "NOT_STARTED", null, Map.of(),
                    activeInstances);
        }

        // Fetch all variables in a single query from runtime or history.
        Map<String, Object> variables = readTwinVariables(twin);

        Object agentNameValue = variables.get(AgentVariables.evolvedAgent(twinActivityId, null));
        String agentName = agentNameValue == null ? null : agentNameValue.toString();

        Object summaryValue = variables.get(AgentVariables.twinAutomation(twinActivityId, null));
        String summary = summaryValue == null ? null : summaryValue.toString();

        Map<String, Object> output = summary == null ? Map.of() : activityOutputsFrom(variables, twinActivityId);

        String status;
        if (summary != null) {
            status = "EXECUTED";
        } else if (activityFailedToExecute(twin, twinActivityId)) {
            status = "FAILED";
        } else if (agentName != null) {
            status = "BOUND";
        } else {
            status = "NOT_STARTED";
        }

        return new TwinActivityExecutionState(activityId, twinActivityId, agentName, status, summary, output,
                activeInstances);
    }

    private List<ActiveRuntimeInstance> activeInstancesOf(TwinProcess twin, String activityId) {
        ActivityInstance tree = runtimeService.getActivityInstance(twin.getOriginalProcessId());
        if (tree == null) {
            return List.of();
        }
        List<ActiveRuntimeInstance> instances = new ArrayList<>();
        for (ActivityInstance visit : tree.getActivityInstances(activityId)) {
            Integer loopCounter = null;
            if (visit.getExecutionIds().length > 0) {
                Object value = runtimeService.getVariableLocal(visit.getExecutionIds()[0], "loopCounter");
                if (value instanceof Integer i) {
                    loopCounter = i;
                }
            }
            instances.add(new ActiveRuntimeInstance(visit.getId(), loopCounter));
        }
        return instances;
    }

    // Reads runtime variables, falling back to history if the instance has already ended.
    private Map<String, Object> readTwinVariables(TwinProcess twin) {
        Map<String, Object> runtime = null;
        try {
            runtime = runtimeService.getVariables(twin.getTwinProcessId());
        } catch (ProcessEngineException e) {
        }
        if (runtime != null && !runtime.isEmpty()) {
            return runtime;
        }
        Map<String, Object> historic = new LinkedHashMap<>();
        for (HistoricVariableInstance instance : historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .list()) {
            historic.put(instance.getName(), instance.getValue());
        }
        return historic;
    }

    private Map<String, Object> activityOutputsFrom(Map<String, Object> variables, String twinActivityId) {
        String prefix = "twinAutomationOutput_";
        String suffix = "_" + twinActivityId;
        Map<String, Object> output = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith(prefix) && name.endsWith(suffix) && name.length() > prefix.length() + suffix.length()) {
                String outputName = name.substring(prefix.length(), name.length() - suffix.length());
                output.put(outputName, entry.getValue());
            }
        }
        return output;
    }

    // Check twin event log for execution failure prefix.
    private boolean activityFailedToExecute(TwinProcess twin, String twinActivityId) {
        String marker = "Twin activity " + twinActivityId + " failed to execute:";
        return twin.getEventLog().stream().anyMatch(line -> line.startsWith(marker));
    }

    private AgentDecision bridgeAndAdvance(TwinProcess twin, String twinProcessId, String activityId,
            String activityInstanceId) {
        if (activityInstanceId == null) {
            AgentDecision decision;
            try {
                decision = bridgeOnce(twin, twinProcessId, activityId, null);
            } finally {
                persistState();
            }
            return decision;
        }

        // Hold bridging if an integration claim is pending for this activity instance.
        String heldTwinActivityId = twin.findTwinActivityId(activityId).orElse(null);
        if (heldTwinActivityId != null && integrationPendingFor(twin, heldTwinActivityId,
                loopCounterOf(twin, activityId, activityInstanceId))) {
            twin.getEventLog().add("Bridge held: activity " + activityId
                    + " is awaiting a component integration decision");
            logger.info("TWIN_HELD: bridge held for activity {} on twin {}: awaiting component integration",
                    activityId, twinProcessId);
            persistState();
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity is awaiting a component integration decision");
        }

        String claim = evolutionClaim(twinProcessId, activityInstanceId);
        if (evolutionsInFlight.putIfAbsent(claim, Boolean.TRUE) != null) {
            twin.getEventLog().add("Bridge skipped: activity " + activityId
                    + " is already being evolved right now");
            logger.info("Bridge skipped for activity {} on twin {}: another evolution is in flight",
                    activityId, twinProcessId);
            persistState();
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity event already being forwarded to twin");
        }

        try {
            AgentDecision decision;
            try {
                decision = bridgeOnce(twin, twinProcessId, activityId, activityInstanceId);
            } finally {
                persistState();
            }
            try {
                // Resolve original execution ID so parallel multi-instance siblings correlate to their specific twin counterpart.
                advanceTwinActivity(twinProcessId, activityId,
                        originalExecutionIdForVisit(twin, activityId, activityInstanceId));
            } catch (RuntimeException e) {
                // Log warning if advance fails; bridging decision remains recorded.
                logger.warn("Bridged activity {} on twin {} but could not move the twin through it: {}",
                        activityId, twinProcessId, e.toString());
            }
            return decision;
        } finally {
            evolutionsInFlight.remove(claim);
        }
    }

    private AgentDecision bridgeOnce(TwinProcess twin, String twinProcessId, String activityId,
            String activityInstanceId) {
        String twinActivityId = twin.findTwinActivityId(activityId).orElse(null);
        if (twinActivityId == null) {
            twin.getEventLog().add("Bridge skipped: activity " + activityId
                    + " is not connected to a twin activity");
            logger.warn("TWIN_SKIPPED: bridge skipped for activity {} on twin {}: activity not connected",
                    activityId, twinProcessId);
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity not connected to twin process");
        }

        if (activityInstanceId == null) {
            twin.getEventLog().add("Bridge skipped: activity " + activityId
                    + " has not been reached in the original process instance "
                    + twin.getOriginalProcessId() + " yet");
            logger.info("Bridge skipped for activity {} on twin {}: not reached yet",
                    activityId, twinProcessId);
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity not yet reached in the original process instance");
        }

        Object loopCounter = loopCounterOf(twin, activityId, activityInstanceId);
        if (alreadyEvolved(twin, activityId, activityInstanceId, twinActivityId, loopCounter)) {
            twin.getEventLog().add("Bridge skipped: activity " + activityId
                    + " was already forwarded to twin");
            logger.info("Bridge skipped for activity {} on twin {}: already forwarded",
                    activityId, twinProcessId);
            return new AgentDecision(DEFAULT_BRIDGE_AGENT_TYPE, false, null,
                    "Activity event already forwarded to twin");
        }

        twin.getEventLog().add("Original activity " + activityId + " reached");
        twin.getEventLog().add("Forwarded event to twin activity " + twinActivityId);
        twin.getEventLog().add("Bridge using default agent type '" + DEFAULT_BRIDGE_AGENT_TYPE
                + "' (no agent type supplied by the triggering event)");
        logger.info("Bridge forwarding activity {} to twin activity {} on twin {} with default agent type {}",
                activityId, twinActivityId, twinProcessId, DEFAULT_BRIDGE_AGENT_TYPE);

        return runEvolution(twin, twinProcessId, activityId, twinActivityId, loopCounter,
                DEFAULT_BRIDGE_AGENT_TYPE);
    }

    // Determines if this activity visit was already evolved, deriving state from history.
    private boolean alreadyEvolved(TwinProcess twin, String originalActivityId, String activityInstanceId,
            String twinActivityId, Object loopCounter) {
        if (loopCounter != null) {
            return evolvedAgentVariableIsSet(twin, AgentVariables.evolvedAgent(twinActivityId, loopCounter));
        }
        List<HistoricActivityInstance> visits = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .activityId(originalActivityId)
                .orderByHistoricActivityInstanceStartTime().asc()
                .list();
        int visitOrdinal = -1;
        for (int i = 0; i < visits.size(); i++) {
            if (visits.get(i).getId().equals(activityInstanceId)) {
                visitOrdinal = i;
                break;
            }
        }
        if (visitOrdinal < 0) {
            // Fall back to not-yet-evolved if visit ordinal cannot be determined.
            return false;
        }
        String evolvedAgentVariable = AgentVariables.evolvedAgent(twinActivityId, null);
        long evolutionCount = historyService.createHistoricDetailQuery()
                .processInstanceId(twin.getTwinProcessId())
                .variableUpdates()
                .list()
                .stream()
                .filter(HistoricVariableUpdate.class::isInstance)
                .map(HistoricVariableUpdate.class::cast)
                .filter(update -> evolvedAgentVariable.equals(update.getVariableName()))
                .count();
        return evolutionCount > visitOrdinal;
    }

    // Checks whether the evolved agent variable is set in runtime or historic variable instances.
    private boolean evolvedAgentVariableIsSet(TwinProcess twin, String evolvedAgentVariable) {
        try {
            if (runtimeService.getVariable(twin.getTwinProcessId(), evolvedAgentVariable) != null) {
                return true;
            }
        } catch (ProcessEngineException e) {
        }
        return historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .variableName(evolvedAgentVariable)
                .count() > 0;
    }

    @Override
    public TwinAdvance advanceTwinActivity(String twinProcessId, String activityId) {
        return advanceTwinActivity(twinProcessId, activityId, null);
    }

    @Override
    public TwinAdvance advanceTwinActivity(String twinProcessId, String activityId, String originalExecutionId) {
        TwinProcess twin = twinProcesses.get(twinProcessId);
        if (twin == null || activityId == null || activityId.isBlank()) {
            return TwinAdvance.skipped(null, "No such twin process: " + twinProcessId);
        }
        String twinActivityId = twin.findTwinActivityId(activityId).orElse(null);
        if (twinActivityId == null) {
            return TwinAdvance.skipped(null, "Activity " + activityId + " is not connected to a twin activity");
        }

        // In parallel multi-instance activities, resolve the specific twin execution sibling.
        String messageName = TwinModelGenerator.twinMessageName(twinActivityId);
        String parallelSiblingExecutionId = originalExecutionId == null ? null
                : resolveParallelSibling(twin, messageName, originalExecutionId);

        // Skip advance if the twin has not yet reached the receive task.
        if (parallelSiblingExecutionId == null && !isTwinWaitingAt(twin, twinActivityId)) {
            logger.debug("TWIN_SKIP: twin {} is not waiting at {} — twin will reach it "
                    + "naturally when earlier activities complete",
                    twinProcessId, twinActivityId);
            return TwinAdvance.skipped(twinActivityId,
                    "Twin has not reached activity " + twinActivityId + " yet");
        }

        // Verifies twin execution quota before proceeding with activity advance.
        GovernanceDecision reservation = governanceService.reserveTwinExecutionSlot(twinProcessId);
        if (!reservation.isAllowed()) {
            twin.getEventLog().add("Twin activity " + twinActivityId
                    + " left parked by governance: " + reservation.getReason());
            persistState();
            logger.warn("TWIN_SKIPPED: twin activity {} on twin {} left parked by governance: {}",
                    twinActivityId, twinProcessId, reservation.getReason());
            return TwinAdvance.skipped(twinActivityId, reservation.getReason());
        }

        // Populate gateway variables from simulation context if configured.
        try {
            BpmnModelInstance originalModel = repositoryService.getBpmnModelInstance(
                    twin.getProcessDefinitionId());
            Map<String, Set<String>> gatewayVarsByActivity =
                    ExternalTaskWorkerGenerator.detectGatewayVariablesByActivityId(originalModel);
            Set<String> requiredVars = gatewayVarsByActivity.getOrDefault(activityId, Set.of());
            if (!requiredVars.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> simulationValues = (Map<String, Object>) runtimeService
                        .getVariable(twin.getTwinProcessId(), AgentVariables.SIMULATION_GATEWAY_VALUES);
                Map<String, Object> appliedSimulation = new java.util.LinkedHashMap<>();
                Map<String, String> missingVars = new java.util.LinkedHashMap<>();
                for (String varName : requiredVars) {
                    Object existing = runtimeService.getVariable(twin.getTwinProcessId(), varName);
                    if (existing != null) {
                        logger.debug("Gateway variable '{}' already set to {} on twin {} — "
                                + "production value preserved", varName, existing, twinProcessId);
                        continue;
                    }
                    Object simValue = simulationValues != null ? simulationValues.get(varName) : null;
                    if (simValue != null) {
                        runtimeService.setVariable(twin.getTwinProcessId(), varName, simValue);
                        appliedSimulation.put(varName, simValue);
                    } else {
                        missingVars.put(varName, "no simulation value — executor must provide or gateway fails");
                    }
                }
                if (!appliedSimulation.isEmpty()) {
                    twin.getEventLog().add("Simulation: set gateway variables " + appliedSimulation
                            + " on twin process for activity " + activityId);
                    logger.info("EXPLICIT_SIMULATION: set gateway variables {} on twin {} "
                            + "for activity {}", appliedSimulation, twinProcessId, activityId);
                }
                if (!missingVars.isEmpty()) {
                    logger.debug("GATEWAY_VARS_DEFERRED: {} on twin {} for activity {} — "
                            + "executor must produce during correlation or gateway fails explicitly",
                            missingVars.keySet(), twinProcessId, activityId);
                }
            }
        } catch (ProcessEngineException e) {
            logger.warn("Could not set gateway variables on twin {} for activity {}: {}",
                    twinProcessId, activityId, e.getMessage());
        }

        try {
            if (parallelSiblingExecutionId != null) {
                // Target specific execution directly to release exactly one parallel sibling.
                runtimeService.messageEventReceived(messageName, parallelSiblingExecutionId);
            } else {
                // Correlate message scoped to this twin process instance.
                runtimeService.createMessageCorrelation(messageName)
                        .processInstanceId(twin.getTwinProcessId())
                        .correlate();
            }
        } catch (RuntimeException e) {
            governanceService.releaseTwinExecutionSlot(twinProcessId);
            twin.getEventLog().add("Twin activity " + twinActivityId + " failed to execute: " + e.getMessage());
            recordTwinAutomationIncident(twin, twinActivityId, parallelSiblingExecutionId, e);
            persistState();
            throw e;
        }

        twin.getEventLog().add("Twin activity " + twinActivityId + " executed on twin process instance "
                + twin.getTwinProcessId());
        persistState();
        logger.info("Twin activity {} executed on twin {} (message {})",
                twinActivityId, twinProcessId, messageName);
        return TwinAdvance.advanced(twinActivityId, messageName);
    }

    private static final String TWIN_AUTOMATION_INCIDENT_TYPE = "twinAutomationFailure";

    private void recordTwinAutomationIncident(TwinProcess twin, String twinActivityId,
            String knownExecutionId, RuntimeException failure) {
        String executionId = knownExecutionId != null ? knownExecutionId
                : findWaitingExecutionId(twin, twinActivityId);
        if (executionId == null) {
            logger.warn("Twin activity {} on twin {} failed but no waiting execution was found to "
                    + "attach an incident to: {}", twinActivityId, twin.getId(), failure.toString());
            return;
        }
        try {
            runtimeService.createIncident(TWIN_AUTOMATION_INCIDENT_TYPE, executionId, twinActivityId,
                    failure.getMessage());
        } catch (RuntimeException incidentFailure) {
            // Log warning if incident creation fails without suppressing the primary failure.
            logger.warn("Could not record an incident for twin activity {} on twin {}: {}",
                    twinActivityId, twin.getId(), incidentFailure.toString());
            return;
        }
        logger.warn("Twin activity {} on twin {} failed to execute; incident recorded on execution {}: {}",
                twinActivityId, twin.getId(), executionId, failure.toString());
    }

    // Resolves waiting execution ID via ActivityInstance tree.
    private String findWaitingExecutionId(TwinProcess twin, String twinActivityId) {
        ActivityInstance tree = runtimeService.getActivityInstance(twin.getTwinProcessId());
        if (tree == null) {
            return null;
        }
        for (ActivityInstance visit : tree.getActivityInstances(twinActivityId)) {
            if (visit.getExecutionIds().length > 0) {
                return visit.getExecutionIds()[0];
            }
        }
        return null;
    }

    // Matches twin sibling executions by loopCounter in parent iteration scope.
    private String resolveParallelSibling(TwinProcess twin, String messageName, String originalExecutionId) {
        Object originalLoopCounter = runtimeService.getVariable(originalExecutionId, "loopCounter");
        if (originalLoopCounter == null) {
            return null;
        }
        List<EventSubscription> subscriptions = runtimeService
                .createEventSubscriptionQuery()
                .processInstanceId(twin.getTwinProcessId())
                .eventName(messageName)
                .list();
        if (subscriptions.size() <= 1) {
            return null;
        }
        for (EventSubscription subscription : subscriptions) {
            if (originalLoopCounter.equals(
                    runtimeService.getVariable(subscription.getExecutionId(), "loopCounter"))) {
                return subscription.getExecutionId();
            }
        }
        logger.warn("Twin {} has {} candidates waiting on {} but none share loopCounter {} with "
                + "original execution {}; falling back to the single-candidate path",
                twin.getId(), subscriptions.size(), messageName, originalLoopCounter, originalExecutionId);
        return null;
    }

    // Uses getActiveActivityIds to check child executions in multi-instance constructs.
    private boolean isTwinWaitingAt(TwinProcess twin, String twinActivityId) {
        for (Execution execution : runtimeService.createExecutionQuery()
                .processInstanceId(twin.getTwinProcessId()).list()) {
            if (runtimeService.getActiveActivityIds(execution.getId()).contains(twinActivityId)) {
                return true;
            }
        }
        return false;
    }

    // Completes open tasks on the original process instance to advance execution.
    @Override
    public List<String> completeCurrentTasks(String twinProcessId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        try {
            return completeOpenTasks(twin, twinProcessId);
        } finally {
            persistState();
        }
    }

    private List<String> completeOpenTasks(TwinProcess twin, String twinProcessId) {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .list();
        List<ExternalTask> externalTasks = externalTaskService.createExternalTaskQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .notLocked()
                .list();
        // Extract downstream gateway variables to supply default evaluation values if needed.
        Map<String, Set<String>> gatewayVarsByTopic = Map.of();
        if (!externalTasks.isEmpty()) {
            try {
                BpmnModelInstance model = repositoryService.getBpmnModelInstance(
                        twin.getProcessDefinitionId());
                gatewayVarsByTopic = ExternalTaskWorkerGenerator.detectGatewayVariables(model);
            } catch (Exception e) {
                logger.warn("Could not analyze BPMN for gateway variables on {}: {}",
                        twin.getOriginalProcessId(), e.getMessage());
            }
        }
        List<EventSubscription> eventSubscriptions = runtimeService.createEventSubscriptionQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .list();
        if (tasks.isEmpty() && externalTasks.isEmpty() && eventSubscriptions.isEmpty()) {
            // Fallback: use process instance modification if normal flow advancement is blocked.
            List<String> jumped = new ArrayList<>();
            for (ActivityLink link : twin.getActivityLinks()) {
                if (currentVisitId(twin, link.getOriginalActivityId()) == null) {
                    try {
                        runtimeService.createProcessInstanceModification(twin.getOriginalProcessId())
                                .startBeforeActivity(link.getOriginalActivityId())
                                .execute();
                        jumped.add(link.getOriginalActivityId());
                        twin.getEventLog().add("Advanced to activity " + link.getOriginalActivityId()
                                + " via process modification (normal flow blocked)");
                        logger.info("Jumped to activity {} on original instance {} of twin {}",
                                link.getOriginalActivityId(), twin.getOriginalProcessId(), twinProcessId);
                    } catch (ProcessEngineException e) {
                        logger.warn("Could not jump to activity {} on original instance {}: {}",
                                link.getOriginalActivityId(), twin.getOriginalProcessId(), e.getMessage());
                    }
                }
            }
            if (!jumped.isEmpty()) {
                return jumped;
            }
            twin.getEventLog().add("No open user or external tasks to complete on original process instance "
                    + twin.getOriginalProcessId());
            logger.info("No open tasks to complete on original instance {} of twin {}",
                    twin.getOriginalProcessId(), twinProcessId);
            return List.of();
        }

        // Track completions across individual transactions to preserve progress on partial failure.
        List<String> completed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        RuntimeException firstRealFailure = null;
        for (Task task : tasks) {
            String label = task.getName() == null
                    ? task.getTaskDefinitionKey()
                    : task.getName() + " (" + task.getTaskDefinitionKey() + ")";
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    taskService.complete(task.getId());
                    completed.add(label);
                    break;
                } catch (ProcessEngineException e) {
                    if (isTaskGone(task.getId())) {
                        skipped.add(label);
                        break;
                    }
                    // Concurrency conflict on join gateway; retry once.
                    if (attempt == 2 && firstRealFailure == null) {
                        firstRealFailure = e;
                        logger.warn("Could not complete task {} ({}) on original instance {}: {}",
                                task.getId(), label, twin.getOriginalProcessId(), e.getMessage());
                    }
                }
            }
        }

        if (!completed.isEmpty()) {
            twin.getEventLog().add("Completed " + completed.size()
                    + " open user task(s) on original process instance " + twin.getOriginalProcessId()
                    + ": " + String.join(", ", completed));
        }
        if (!skipped.isEmpty()) {
            twin.getEventLog().add("Skipped " + skipped.size()
                    + " user task(s) that were already gone by the time we got to them: "
                    + String.join(", ", skipped));
            logger.info("Skipped {} already-gone task(s) on original instance {} of twin {}: {}",
                    skipped.size(), twin.getOriginalProcessId(), twinProcessId, skipped);
        }

        // Complete external tasks, passing required gateway variables from simulation context.
        String workerId = "metaml-workbench-advance-" + twinProcessId;
        for (ExternalTask et : externalTasks) {
            String etLabel = et.getActivityId() + " (topic: " + et.getTopicName() + ")";
            try {
                externalTaskService.lock(et.getId(), workerId, 10_000L);
                Set<String> requiredVars = gatewayVarsByTopic.getOrDefault(
                        et.getTopicName(), Set.of());
                if (requiredVars.isEmpty()) {
                    externalTaskService.complete(et.getId(), workerId);
                } else {
                    // Populate gateway variables from the twin's simulation context.
                    @SuppressWarnings("unchecked")
                    Map<String, Object> simulationContext = (Map<String, Object>) runtimeService
                            .getVariable(twin.getTwinProcessId(), AgentVariables.SIMULATION_GATEWAY_VALUES);
                    Map<String, Object> simulationVars = new HashMap<>();
                    for (String varName : requiredVars) {
                        Object simValue = simulationContext != null
                                ? simulationContext.get(varName) : null;
                        if (simValue != null) {
                            simulationVars.put(varName, simValue);
                        } else {
                            // No explicit simulation value configured; complete without variable.
                            logger.warn("MISSING_SIMULATION: no explicit value for gateway "
                                    + "variable '{}' (topic '{}') on original {} — gateway may "
                                    + "fail explicitly",
                                    varName, et.getTopicName(), twin.getOriginalProcessId());
                        }
                    }
                    if (simulationVars.isEmpty()) {
                        externalTaskService.complete(et.getId(), workerId);
                    } else {
                        externalTaskService.complete(et.getId(), workerId, simulationVars);
                    }
                    if (!simulationVars.isEmpty()) {
                        twin.getEventLog().add("Simulation: set gateway variables "
                                + simulationVars + " on original process"
                                + " (external task topic: " + et.getTopicName() + ")");
                        logger.info("EXPLICIT_SIMULATION: set gateway variables {} on original {} "
                                + "(topic {})",
                                simulationVars, twin.getOriginalProcessId(), et.getTopicName());
                    }
                }
                completed.add(etLabel);
            } catch (ProcessEngineException e) {
                skipped.add(etLabel);
                if (firstRealFailure == null) {
                    firstRealFailure = e;
                }
                logger.warn("Could not complete external task {} on original instance {}: {}",
                        etLabel, twin.getOriginalProcessId(), e.getMessage());
            }
        }
        if (!externalTasks.isEmpty()) {
            long etCompleted = completed.stream().filter(s -> s.contains("(topic:")).count();
            if (etCompleted > 0) {
                twin.getEventLog().add("Completed " + etCompleted
                        + " external task(s) on original process instance " + twin.getOriginalProcessId());
            }
        }

        // Deliver pending signals and messages to advance past catch events.
        for (EventSubscription sub : eventSubscriptions) {
            String subLabel = sub.getEventType() + ":" + sub.getEventName()
                    + " (activity: " + sub.getActivityId() + ")";
            try {
                if ("signal".equals(sub.getEventType())) {
                    runtimeService.signalEventReceived(sub.getEventName(), sub.getExecutionId());
                    completed.add(subLabel);
                } else if ("message".equals(sub.getEventType())) {
                    runtimeService.messageEventReceived(sub.getEventName(), sub.getExecutionId());
                    completed.add(subLabel);
                }
            } catch (ProcessEngineException e) {
                skipped.add(subLabel);
                if (firstRealFailure == null) {
                    firstRealFailure = e;
                }
                logger.warn("Could not deliver event {} on original instance {}: {}",
                        subLabel, twin.getOriginalProcessId(), e.getMessage());
            }
        }
        if (!eventSubscriptions.isEmpty()) {
            long evtDelivered = completed.stream()
                    .filter(s -> s.startsWith("signal:") || s.startsWith("message:"))
                    .count();
            if (evtDelivered > 0) {
                twin.getEventLog().add("Delivered " + evtDelivered
                    + " event subscription(s) on original process instance "
                    + twin.getOriginalProcessId());
            }
        }

        // Surface error only if no task completed; partial progress is preserved.
        if (completed.isEmpty() && firstRealFailure != null) {
            throw firstRealFailure;
        }
        if (firstRealFailure != null) {
            twin.getEventLog().add("At least one task could not be completed: "
                    + firstRealFailure.getMessage());
        }

        logger.info("Completed {} open task(s) on original instance {} of twin {}: {}",
                completed.size(), twin.getOriginalProcessId(), twinProcessId, completed);
        return completed;
    }

    private boolean isTaskGone(String taskId) {
        return taskService.createTaskQuery().taskId(taskId).singleResult() == null;
    }

    @Override
    public void recordAgentExecution(String twinProcessId, String variableName, Object agentName) {
        TwinProcess twin = twinProcesses.get(twinProcessId);
        if (twin == null) {
            return;
        }
        twin.getEventLog().add("Set process variable '" + variableName + "' = " + agentName
                + " on original process instance " + twin.getOriginalProcessId());
        persistState();
    }

    // Resolves runtime activityInstanceId to distinguish loop iterations sharing an activity ID.
    private String currentVisitId(TwinProcess twin, String activityId) {
        List<HistoricActivityInstance> visits = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .activityId(activityId)
                .orderByHistoricActivityInstanceStartTime().desc()
                .list();
        // Prefer the active visit; if already completed, fall back to the most recent completed visit.
        for (HistoricActivityInstance visit : visits) {
            if (visit.getEndTime() == null) {
                return visit.getId();
            }
        }
        return visits.isEmpty() ? null : visits.get(0).getId();
    }

    // Resolves loopCounter for the activity visit; null for non-multi-instance activities.
    private Object loopCounterOf(TwinProcess twin, String activityId, String activityInstanceId) {
        ActivityInstance tree = runtimeService.getActivityInstance(twin.getOriginalProcessId());
        if (tree == null) {
            return null;
        }
        for (ActivityInstance visit : tree.getActivityInstances(activityId)) {
            if (visit.getId().equals(activityInstanceId) && visit.getExecutionIds().length > 0) {
                return runtimeService.getVariableLocal(visit.getExecutionIds()[0], "loopCounter");
            }
        }
        return null;
    }

    // The exact inverse of loopCounterOf above: given a loop index, recovers the genuine Camunda
    // activity-instance id of the live sibling carrying it (MetaML Scope 6, Phase 5).
    //
    // A multi-instance activity has one activityId but many concurrently live visits, and the only
    // thing that distinguishes them at runtime is the loopCounter local variable on each visit's
    // own execution. evolveActivity's 4-arg overload exists precisely because currentVisitId()'s
    // most-recently-started heuristic cannot tell concurrent siblings apart (see WorkbenchService)
    // - so a capability gap opened for one specific sibling must carry that sibling's real
    // activity-instance id, never a fabricated stand-in. A synthesized descriptor cannot be matched
    // back against the runtime tree by loopCounterOf, which silently collapses the binding onto the
    // unsuffixed evolvedAgent_<activity> variable that no sibling ever reads.
    //
    // Reuses activeInstancesOf - the repository's own existing (activityInstanceId, loopCounter)
    // pairing over the live activity-instance tree, already used to offer operators a targeted
    // sibling to evolve - rather than introducing a second traversal of the same data.
    //
    // Returns null when loopCounter is null (a non-multi-instance activity has no sibling to
    // disambiguate, and the existing null-activityInstanceId behaviour is already correct there)
    // and when no live sibling carries that loop index.
    private String activityInstanceIdForLoopCounter(TwinProcess twin, String activityId, Integer loopCounter) {
        if (loopCounter == null) {
            return null;
        }
        for (ActiveRuntimeInstance instance : activeInstancesOf(twin, activityId)) {
            if (loopCounter.equals(instance.loopCounter())) {
                return instance.activityInstanceId();
            }
        }
        return null;
    }

    private AgentDecision runEvolution(TwinProcess twin, String twinProcessId, String activityId,
            String twinActivityId, Object loopCounter, String agentType) {
        // Check platform governance slot reservation before invoking node manager.
        GovernanceDecision reservation = governanceService.reserveEvolutionSlot(twinProcessId, agentType);
        if (!reservation.isAllowed()) {
            twin.getEventLog().add("Evolution blocked by governance: " + reservation.getReason());
            logger.info("Evolve blocked by governance for activity {} on twin {} with agent type {}: {}",
                    activityId, twinProcessId, agentType, reservation.getReason());
            return new AgentDecision(agentType, false, null, reservation.getReason());
        }

        boolean evolutionSucceeded = false;
        try {
            // Enforce tenant policy if tenant is defined on the twin process.
            if (twin.getTenantId() != null) {
                AgentDecision tenantDecision = enforceTenantPolicy(twin, activityId, twinProcessId, twinActivityId,
                        loopCounter, agentType);
                if (tenantDecision != null) {
                    return tenantDecision;
                }
            }

            AgentDecision decision = executeAfterGovernance(twin, twinProcessId, activityId, twinActivityId,
                    loopCounter, agentType);
            evolutionSucceeded = decision.isApproved();
            return decision;
        } finally {
            // Release platform evolution slot if evolution did not succeed.
            if (!evolutionSucceeded) {
                governanceService.releaseEvolutionSlot(twinProcessId);
            }
        }
    }

    // Evaluates tenant policy; returns null if evolution is permitted to proceed.
    private AgentDecision enforceTenantPolicy(TwinProcess twin, String activityId, String twinProcessId,
            String twinActivityId, Object loopCounter, String agentType) {
        GovernanceRequest request = new GovernanceRequest(twin.getTenantId(), EVOLVE_TWIN_ACTION,
                Map.of("twinId", twinProcessId, "activityId", activityId, "agentType", agentType));

        PolicyDecision policyDecision;
        try {
            policyDecision = policyDecisionEngine.evaluate(request);
        } catch (NoSuchElementException | PolicyEvaluationException e) {
            // Fail closed on tenant policy evaluation error to prevent unauthorized execution.
            twin.getEventLog().add("Evolution blocked: tenant policy could not be evaluated: " + e.getMessage());
            logger.warn("Tenant policy evaluation failed for activity {} on twin {} (tenant {}): {}",
                    activityId, twinProcessId, twin.getTenantId(), e.getMessage());
            return new AgentDecision(agentType, false, null,
                    "Tenant policy could not be evaluated: " + e.getMessage(), false, PolicyEffect.DENY.name());
        }

        if (policyDecision.decision() == PolicyEffect.DENY) {
            twin.getEventLog().add("Evolution denied by tenant policy: " + policyDecision.reason());
            logger.info("Evolve denied by tenant policy for activity {} on twin {} (tenant {}): {}",
                    activityId, twinProcessId, twin.getTenantId(), policyDecision.reason());
            return new AgentDecision(agentType, false, null, "Denied by tenant policy: " + policyDecision.reason(),
                    false, PolicyEffect.DENY.name());
        }
        if (policyDecision.decision() == PolicyEffect.REQUIRE_APPROVAL) {
            // Pin approval to the specific policy version and rule matched at evaluation time.
            Integer loopCounterValue = loopCounter instanceof Integer i ? i : null;
            Approval approval = approvalService.create(twin.getTenantId(), twinProcessId, activityId,
                    twinActivityId, loopCounterValue, agentType, EVOLVE_TWIN_ACTION, policyDecision.policyId(),
                    policyDecision.policyVersionId(), policyDecision.policyVersionNumber(),
                    policyDecision.matchedRuleId(), policyDecision.reason());
            twin.getEventLog().add("Evolution requires approval per tenant policy (approval " + approval.id()
                    + "): " + policyDecision.reason());
            logger.info("Evolve requires approval for activity {} on twin {} (tenant {}, approval {}): {}",
                    activityId, twinProcessId, twin.getTenantId(), approval.id(), policyDecision.reason());
            return new AgentDecision(agentType, false, null,
                    "Approval required (id " + approval.id() + "): " + policyDecision.reason(), false,
                    PolicyEffect.REQUIRE_APPROVAL.name());
        }
        return null;
    }

    private AgentDecision executeAfterGovernance(TwinProcess twin, String twinProcessId, String activityId,
            String twinActivityId, Object loopCounter, String agentType) {
        String evolvedAgentVariable = AgentVariables.evolvedAgent(twinActivityId, loopCounter);
        String evolvedAgentTypeVariable = AgentVariables.evolvedAgentType(twinActivityId, loopCounter);
        twin.getEventLog().add("Contacting node manager for agent type " + agentType);

        AgentAvailabilityResult availability;
        try {
            availability = nodeManagerClient.checkAgentAvailability(agentType);
        } catch (NodeManagerUnavailableException e) {
            twin.getEventLog().add("Node manager unavailable: " + e.getMessage());
            logger.warn("Node manager unavailable while evolving activity {} on twin {}: {}",
                    activityId, twinProcessId, e.getMessage());
            throw e;
        }

        if (!availability.isAvailable()) {
            twin.getEventLog().add("Node manager reports agent type " + agentType
                    + " unavailable: " + availability.getReason());
            logger.info("Evolve blocked for activity {} on twin {} with agent type {}",
                    activityId, twinProcessId, agentType);
            reportRuntimeCapabilityGapIfApplicable(twin, twinProcessId, activityId, loopCounter);
            return new AgentDecision(agentType, false, null, availability.getReason());
        }

        // Evolution is only marked approved if the variable update was accepted by the engine.
        boolean variableSet = false;
        // Captured BEFORE either write, so a durable-persistence failure below can restore EXACTLY
        // this - a rebind's compensation must put the previous provider back, never merely clear the
        // activity (P7 Step 5). "Present" is tracked separately from the value itself because
        // Map.get returning null is ambiguous between "unset" and "genuinely set to null".
        Map<String, Object> priorEvolvedVars;
        try {
            priorEvolvedVars = runtimeService.getVariables(twin.getTwinProcessId(),
                    List.of(evolvedAgentVariable, evolvedAgentTypeVariable));
        } catch (ProcessEngineException e) {
            priorEvolvedVars = Map.of();
        }
        boolean priorAgentPresent = priorEvolvedVars.containsKey(evolvedAgentVariable);
        Object priorAgentValue = priorEvolvedVars.get(evolvedAgentVariable);
        boolean priorAgentTypePresent = priorEvolvedVars.containsKey(evolvedAgentTypeVariable);
        Object priorAgentTypeValue = priorEvolvedVars.get(evolvedAgentTypeVariable);

        try {
            runtimeService.setVariable(twin.getTwinProcessId(), evolvedAgentVariable,
                    availability.getAgentName());
            // Store the agent type alongside the name so automation dispatch can fall back
            // to type-level executor matching for multi-instance parallel activities where
            // each sibling gets a distinct agent name from the catalog.
            runtimeService.setVariable(twin.getTwinProcessId(), evolvedAgentTypeVariable, agentType);
            twin.getEventLog().add("Set process variable '" + evolvedAgentVariable
                    + "' = " + availability.getAgentName() + " on twin process instance "
                    + twin.getTwinProcessId());
            variableSet = true;

            // Clear prior execution records to prevent stale attribution before new execution runs.
            clearPriorExecutionRecord(twin, twinActivityId, loopCounter);

            // Release the integration hold now that binding has resolved.
            releaseIntegrationClaim(twin, twinActivityId, loopCounter);

            writeAgentOutputs(twin, twinActivityId, loopCounter, availability.getOutputs());
        } catch (ProcessEngineException e) {
            twin.getEventLog().add("Could not set process variable on twin instance "
                    + twin.getTwinProcessId() + " (it may have already ended): " + e.getMessage());
            logger.warn("Could not set process variable on twin instance {}: {}",
                    twin.getTwinProcessId(), e.getMessage());
        }

        if (!variableSet) {
            logger.info("Evolve not approved for activity {} on twin {}: twin instance {} could not be updated",
                    activityId, twinProcessId, twin.getTwinProcessId());
            return new AgentDecision(agentType, false, null,
                    "Twin process instance " + twin.getTwinProcessId()
                            + " could not be updated (it may have already ended), so no agent was assigned");
        }

        // P7 Step 5: only non-loop-scoped evolutions feed the model-level, Workbench-authoritative
        // CapabilityBinding registry a standalone Target Platform can read (GET /transmute/bindings) -
        // see CapabilityBinding's own documentation for why loop-scoped visits are deliberately out of
        // this store's scope. A loop-scoped evolution (loopCounter != null) still succeeds exactly as
        // before; it simply does not additionally become a model-level current binding.
        if (loopCounter == null) {
            AgentDecision persistenceFailure = recordCapabilityBindingOrCompensate(twin, twinActivityId, agentType,
                    availability, evolvedAgentVariable, evolvedAgentTypeVariable, priorAgentPresent, priorAgentValue,
                    priorAgentTypePresent, priorAgentTypeValue);
            if (persistenceFailure != null) {
                return persistenceFailure;
            }
        }

        AgentDecision decision = new AgentDecision(agentType, true, availability.getAgentName(),
                availability.getReason(), availability.isRiskFlagged(), null);
        twin.getEventLog().add("Node manager reports agent type " + agentType
                + " available; selected agent " + availability.getAgentName());
        logger.info("Evolve approved for activity {} on twin {} with agent type {}",
                activityId, twinProcessId, agentType);
        return decision;
    }

    // Populates the durable, model-level CapabilityBinding registry so a standalone Target Platform
    // can retrieve this decision without knowing this Workbench twin instance's id (P7 Step 5). On a
    // durable-persistence failure, restores the EXACT prior evolvedAgent_*/evolvedAgentType_* state
    // captured before this evolution wrote anything - never a blind removal, which would destroy a
    // still-valid previous binding on a rebind failure - and returns the AgentDecision the caller
    // must report instead of success. Returns null when there is nothing to compensate for (no
    // CapabilityBindingRegistry bean registered - the common case in a focused unit test - or the
    // durable write succeeded).
    private AgentDecision recordCapabilityBindingOrCompensate(TwinProcess twin, String twinActivityId,
            String agentType, AgentAvailabilityResult availability, String evolvedAgentVariable,
            String evolvedAgentTypeVariable, boolean priorAgentPresent, Object priorAgentValue,
            boolean priorAgentTypePresent, Object priorAgentTypeValue) {
        if (capabilityBindingRegistry == null) {
            return null;
        }
        String processDefinitionKey;
        try {
            processDefinitionKey = processDefinitionKeyOf(twin.getTwinProcessId());
        } catch (RuntimeException e) {
            logger.warn("Could not resolve the process definition key for twin instance {} - capability "
                    + "binding not recorded for activity {}: {}", twin.getTwinProcessId(), twinActivityId,
                    e.getMessage());
            return null;
        }
        if (processDefinitionKey == null) {
            return null;
        }
        try {
            CapabilityBinding binding = new CapabilityBinding(processDefinitionKey, twinActivityId,
                    availability.getAgentName(), agentType, providerVersionOf(availability.getAgentName()),
                    providerContractOf(availability.getAgentName()), null, Instant.now());
            capabilityBindingRegistry.upsert(binding);
            return null;
        } catch (CapabilityBindingPersistenceException e) {
            logger.error("Capability binding for activity {} could not be durably persisted; restoring the "
                    + "prior binding state on twin instance {}: {}", twinActivityId, twin.getTwinProcessId(),
                    e.getMessage(), e);
            restorePriorEvolvedAgentState(twin.getTwinProcessId(), evolvedAgentVariable, evolvedAgentTypeVariable,
                    priorAgentPresent, priorAgentValue, priorAgentTypePresent, priorAgentTypeValue);
            return new AgentDecision(agentType, false, null,
                    "Binding could not be durably persisted; no agent was assigned");
        }
    }

    // Best-effort: a failure here is logged, not rethrown, so it can never mask the persistence
    // failure that caused it to run. It is the smallest correct compensation given the actual
    // transaction boundary here - the Camunda write already committed by the time persistence is
    // attempted, so undoing it is a second, explicit write, not a rollback.
    private void restorePriorEvolvedAgentState(String twinProcessId, String evolvedAgentVariable,
            String evolvedAgentTypeVariable, boolean priorAgentPresent, Object priorAgentValue,
            boolean priorAgentTypePresent, Object priorAgentTypeValue) {
        try {
            if (priorAgentPresent) {
                runtimeService.setVariable(twinProcessId, evolvedAgentVariable, priorAgentValue);
            } else {
                runtimeService.removeVariable(twinProcessId, evolvedAgentVariable);
            }
            if (priorAgentTypePresent) {
                runtimeService.setVariable(twinProcessId, evolvedAgentTypeVariable, priorAgentTypeValue);
            } else {
                runtimeService.removeVariable(twinProcessId, evolvedAgentTypeVariable);
            }
        } catch (RuntimeException compensationFailure) {
            logger.error("Compensation failed while restoring prior capability binding state on twin instance "
                    + "{} for {}/{}: {}", twinProcessId, evolvedAgentVariable, evolvedAgentTypeVariable,
                    compensationFailure.getMessage(), compensationFailure);
        }
    }

    // The BPMN process key (portable across engines, unlike a deployment-specific process definition
    // id) of the process definition this twin process instance is currently running - the identity a
    // standalone generated Target Platform can also derive for itself from its own bundled BPMN, and
    // therefore the only identity CapabilityBinding can safely be keyed by.
    private String processDefinitionKeyOf(String twinProcessInstanceId) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(twinProcessInstanceId).singleResult();
        if (instance == null) {
            return null;
        }
        ProcessDefinition definition = repositoryService.getProcessDefinition(instance.getProcessDefinitionId());
        return definition == null ? null : definition.getKey();
    }

    // Binding-time provider version/contract snapshot, resolved from the same catalog
    // CatalogCapabilityOutputContractSource already reads - never re-resolved live by the Target
    // Platform later (see CapabilityBinding's own documentation on why).
    private String providerVersionOf(String providerId) {
        com.metaml.workbench.capability.CapabilityProvider provider = catalogProviderFor(providerId);
        return provider == null ? null : provider.version();
    }

    private com.metaml.workbench.capability.CapabilityContract providerContractOf(String providerId) {
        com.metaml.workbench.capability.CapabilityProvider provider = catalogProviderFor(providerId);
        return provider == null
                ? new com.metaml.workbench.capability.CapabilityContract(null, Set.of(), Set.of(),
                        com.metaml.workbench.capability.ExecutionMode.SYNCHRONOUS, Map.of(), Set.of())
                : provider.contract();
    }

    private com.metaml.workbench.capability.CapabilityProvider catalogProviderFor(String providerId) {
        if (providerId == null) {
            return null;
        }
        try {
            for (com.metaml.workbench.capability.CapabilityProvider provider : listCapabilityProviders()) {
                if (provider.providerId().equals(providerId)) {
                    return provider;
                }
            }
        } catch (RuntimeException e) {
            logger.warn("Could not resolve provider '{}' from the capability catalog: {}", providerId,
                    e.getMessage());
        }
        return null;
    }

    // MetaML Scope 6, Phase 5: runtime capability-gap detection seam. Called only from the existing
    // node-manager agent-availability failure branch above - the one point in the existing runtime
    // architecture where the platform already knows a requested provider is unavailable. Delegates
    // the actual capability-vs-catalog determination to CapabilityGapService (whether some OTHER
    // provider could still satisfy the activity, in which case this is not a gap at all); this
    // method only resolves the values CapabilityGapService needs from data already available here.
    // No-op when no CapabilityGapService bean is registered (tests, by default) or on any failure -
    // never allowed to change the AgentDecision already being returned or block the caller.
    // The gap's per-visit identity is the genuine Camunda activity-instance id, resolved from the
    // loop index this evolution is actually running for (see activityInstanceIdForLoopCounter);
    // it is deliberately not a descriptor synthesized from the activity id and loop counter, since
    // CapabilityGapService.bind feeds this value straight back into evolveActivity, which can only
    // match a real activity-instance id against the live runtime tree.
    private void reportRuntimeCapabilityGapIfApplicable(TwinProcess twin, String twinProcessId,
            String activityId, Object loopCounter) {
        CapabilityGapService gapService = capabilityGapService == null ? null : capabilityGapService.getIfAvailable();
        if (gapService == null) {
            return;
        }
        try {
            BpmnModelInstance originalModel = repositoryService.getBpmnModelInstance(twin.getProcessDefinitionId());
            if (originalModel == null) {
                return;
            }
            Integer loopCounterValue = loopCounter instanceof Integer i ? i : null;
            // The gap's activityInstanceId must be a genuine Camunda activity-instance id, because
            // CapabilityGapService.bind hands it straight back to evolveActivity's 4-arg overload,
            // which resolves the visit's loopCounter from it against the live activity tree. For a
            // non-multi-instance activity there is no sibling to disambiguate and null is already
            // the correct, proven value (evolveOnce then resolves the single current visit itself);
            // for a multi-instance one it is resolved from the loop index the evolution is
            // genuinely running for.
            String visitId = activityInstanceIdForLoopCounter(twin, activityId, loopCounterValue);
            gapService.reportRuntimeGapIfUnsatisfied(originalModel, activityId, twin.getModelId(), visitId,
                    loopCounterValue, twinProcessId, twin.getTenantId(), GapOrigin.RUNTIME_WORKBENCH_TWIN);
        } catch (RuntimeException e) {
            logger.warn("Could not evaluate capability-gap detection for activity {} on twin {}: {}",
                    activityId, twinProcessId, e.getMessage());
        }
    }

    // Reject pending approval; prevents subsequent resolution.
    @Override
    public AgentDecision rejectApproval(String approvalId, String tenantId) {
        Approval approval = approvalService.markRejected(approvalId, tenantId);
        TwinProcess twin = twinProcesses.get(approval.twinId());
        if (twin != null) {
            twin.getEventLog().add("Approval " + approvalId + " rejected: " + approval.reason());
            persistState();
        }
        logger.info("Approval {} rejected for tenant {}", approvalId, tenantId);
        return new AgentDecision(approval.agentType(), false, null, "Rejected: " + approval.reason(), false,
                ApprovalStatus.REJECTED.name());
    }

    // Transitions approval to APPROVED to prevent duplicate execution.
    @Override
    public AgentDecision approveEvolution(String approvalId, String tenantId) {
        Approval approval = approvalService.markApproved(approvalId, tenantId);
        TwinProcess twin = twinProcesses.get(approval.twinId());
        if (twin == null) {
            approvalService.markFailed(approvalId, "twin " + approval.twinId() + " no longer exists");
            return new AgentDecision(approval.agentType(), false, null,
                    "Twin " + approval.twinId() + " no longer exists", false, ApprovalStatus.FAILED.name());
        }

        GovernanceDecision reservation = governanceService.reserveEvolutionSlot(approval.twinId(),
                approval.agentType());
        if (!reservation.isAllowed()) {
            twin.getEventLog().add("Approval " + approvalId + " could not execute: " + reservation.getReason());
            approvalService.markFailed(approvalId, reservation.getReason());
            persistState();
            return new AgentDecision(approval.agentType(), false, null, reservation.getReason(), false,
                    ApprovalStatus.FAILED.name());
        }
        boolean succeeded = false;
        try {
            twin.getEventLog().add("Approval " + approvalId + " approved, resuming the original evolution");
            AgentDecision decision = executeAfterGovernance(twin, approval.twinId(), approval.activityId(),
                    approval.twinActivityId(), approval.loopCounter(), approval.agentType());
            succeeded = decision.isApproved();
            if (succeeded) {
                approvalService.markCompleted(approvalId, decision.getAgentName());
            } else {
                approvalService.markFailed(approvalId, decision.getReason());
            }
            persistState();
            logger.info("Approval {} resolved for tenant {}: {}", approvalId, tenantId,
                    succeeded ? "COMPLETED" : "FAILED");
            return decision;
        } finally {
            if (!succeeded) {
                governanceService.releaseEvolutionSlot(approval.twinId());
            }
        }
    }

    @Override
    public List<Approval> listApprovals(String tenantId) {
        return approvalService.listForTenant(tenantId);
    }

    // MetaML Scope 6, Phase 5: the execution-driven BOUND -> RESOLVED hook. Called only from
    // TwinAutomationDelegate, only after CapabilityOutputPropagator.publish has already returned
    // normally for this visit - so only a provider that actually ran and passed the Phase 4 output
    // contract ever reaches here. No-op when no CapabilityGapService is wired (see the field javadoc
    // above) or when this visit has no matching BOUND gap - CapabilityGapService itself is
    // responsible for making that determination and for the lifecycle transition.
    //
    // executedProviderId is forwarded exactly as TwinAutomationDelegate resolved it, never
    // re-derived here: this method has no way of knowing which provider that delegate actually
    // dispatched to, and inventing a second derivation would reintroduce the very ambiguity the
    // parameter exists to remove.
    @Override
    public void notifyCapabilityProviderExecutionSucceeded(String twinProcessId, String activityId,
            Object loopCounter, String executedProviderId) {
        CapabilityGapService gapService = capabilityGapService == null ? null : capabilityGapService.getIfAvailable();
        if (gapService == null) {
            return;
        }
        Integer loopCounterValue = loopCounter instanceof Integer i ? i : null;
        try {
            gapService.onProviderExecutionSucceeded(twinProcessId, activityId, loopCounterValue, executedProviderId);
        } catch (RuntimeException e) {
            // A capability-gap bookkeeping failure must never fail the automation that already
            // succeeded - the provider output has already been validated and published by this
            // point, so the twin's process token must still be allowed to advance.
            logger.warn("Could not update capability gap lifecycle for twin {} activity {}: {}", twinProcessId,
                    activityId, e.getMessage());
        }
    }

    // P7 Step 5: what GET /transmute/bindings serves. Reads only the durable, model-level registry
    // recordCapabilityBindingOrCompensate populates - never the Workbench's own live twin-instance
    // process variables, which is precisely the thing a standalone Target Platform cannot address (it
    // shares no processInstanceId/activityInstanceId space with this Workbench). Activities with no
    // current binding are simply absent from the result.
    @Override
    public List<CapabilityBinding> listCapabilityBindings(String processDefinitionKey, List<String> activityIds) {
        if (capabilityBindingRegistry == null || processDefinitionKey == null || activityIds == null) {
            return List.of();
        }
        List<CapabilityBinding> result = new ArrayList<>();
        for (String activityId : activityIds) {
            capabilityBindingRegistry.current(processDefinitionKey, activityId).ifPresent(result::add);
        }
        return result;
    }

    // Clears prior execution variables for the activity instance visit.
    private void clearPriorExecutionRecord(TwinProcess twin, String twinActivityId, Object loopCounter) {
        String summaryVariable = AgentVariables.twinAutomation(twinActivityId, loopCounter);
        // Suffix identifies variables scoped to this specific activity visit.
        String perVisitSuffix = "_" + summaryVariable.substring("twinAutomation_".length());
        String outputPrefix = "twinAutomationOutput_";

        Map<String, Object> variables;
        try {
            variables = runtimeService.getVariables(twin.getTwinProcessId());
        } catch (ProcessEngineException e) {
            return;
        }

        List<String> stale = new ArrayList<>();
        if (variables.containsKey(summaryVariable)) {
            stale.add(summaryVariable);
        }
        for (String name : variables.keySet()) {
            if (name.startsWith(outputPrefix) && name.endsWith(perVisitSuffix)
                    && name.length() > outputPrefix.length() + perVisitSuffix.length()) {
                stale.add(name);
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        for (String name : stale) {
            runtimeService.removeVariable(twin.getTwinProcessId(), name);
        }
        twin.getEventLog().add("Superseded previous execution record for twin activity "
                + twinActivityId + " on re-binding (cleared " + stale.size() + " variable(s))");
        logger.info("Cleared {} superseded execution variable(s) for twin activity {} on twin {}",
                stale.size(), twinActivityId, twin.getId());
    }

    private void writeAgentOutputs(TwinProcess twin, String twinActivityId, Object loopCounter,
            Map<String, Object> outputs) {
        Map<String, Object> current = outputs == null ? Map.of() : outputs;
        String indexVariable = AgentVariables.evolvedAgentOutputIndex(twinActivityId, loopCounter);

        for (String previousName : AgentVariables.outputNamesIn(
                runtimeService.getVariable(twin.getTwinProcessId(), indexVariable))) {
            if (!current.containsKey(previousName)) {
                runtimeService.removeVariable(twin.getTwinProcessId(),
                        AgentVariables.evolvedAgentOutput(previousName, twinActivityId, loopCounter));
            }
        }

        for (Map.Entry<String, Object> output : current.entrySet()) {
            String outputVariable = AgentVariables.evolvedAgentOutput(output.getKey(), twinActivityId,
                    loopCounter);
            runtimeService.setVariable(twin.getTwinProcessId(), outputVariable, output.getValue());
            twin.getEventLog().add("Set process variable '" + outputVariable + "' = " + output.getValue()
                    + " on twin process instance " + twin.getTwinProcessId());
        }

        // Remove index variable if no outputs were produced.
        if (current.isEmpty()) {
            runtimeService.removeVariable(twin.getTwinProcessId(), indexVariable);
        } else {
            runtimeService.setVariable(twin.getTwinProcessId(), indexVariable,
                    AgentVariables.outputIndexValue(current.keySet()));
        }
    }

    @Override
    public List<AgentAvailabilityResult> listAvailableAgents() {
        return nodeManagerClient.listAgents();
    }
}
