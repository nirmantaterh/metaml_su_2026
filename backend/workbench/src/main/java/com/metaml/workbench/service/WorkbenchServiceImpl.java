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
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.metaml.workbench.bpmn.TwinModelGenerator;
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

    // auto-bridge has no caller to ask for a type, so it uses this one
    private static final String DEFAULT_BRIDGE_AGENT_TYPE = "validator";

    // The single action name every EVOLVE_TWIN GovernanceRequest uses, so a tenant policy has one stable
// string to match against.
    private static final String EVOLVE_TWIN_ACTION = "EVOLVE_TWIN";

    // what a client-supplied model id is allowed to look like. Generated ids are UUIDs, which fit this comfortably; anything with a separator, a dot, or a drive letter in it does not.
    private static final Pattern SAFE_MODEL_ID = Pattern.compile("[A-Za-z0-9_-]+");

    // still the live copy - twins are mirrored to a file by WorkbenchStateStore after each change; models are persisted to H2 by ProcessModelArchiveStore
    private final Map<String, ProcessModel> processModels = new ConcurrentHashMap<>();
    private final Map<String, TwinProcess> twinProcesses = new ConcurrentHashMap<>();
    // twin+visit being evolved right now - the evolvedAgent_* variable alone can't tell you that, since it isn't set until an evolution actually succeeds. Keyed per visit like everything else, or two visits of a multi-instance activity block each other for nothing.
    private final Map<String, Boolean> evolutionsInFlight = new ConcurrentHashMap<>();
    // Rebuilt on every restart from sources that already persist - the project directory itself and
    // each model's GENERATE stage - so these maps need no file of their own. A launched process is the
    // one thing that genuinely does not survive; that still comes from the launcher's live registry.
    private final Map<String, GeneratedProject> generatedProjects = new ConcurrentHashMap<>();
    // the only place a generated project's originating model is remembered - GeneratedProject itself carries no modelId (it's a workbench.generation concern, not a BPMN one), and both launch and stop need to know which model's breadcrumb a project's LAUNCH stage belongs to
    private final Map<String, String> modelIdByProjectId = new ConcurrentHashMap<>();
    // One lock per model id, guarding the two authoring operations that can conflict over a model's
    // existence: Generate and Delete. Never removed - bounded by the model ids seen since startup, and in
    // memory only.
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
    // single source of truth for where a model's Model -> Generate -> Launch pipeline actually is - see the class's own header comment. Every method below that IS one of those three stages records into it; nothing else should.
    private final WorkflowStateTracker workflowStateTracker;

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
        // The H2-backed archive is the single authoritative source for process models. A model that is not in
        // the archive is not a model this workbench knows about.
        for (ProcessModel model : processModelArchiveStore.findAll()) {
            processModels.put(model.getId(), model);
            // Spring finishes WorkflowStateTracker's @PostConstruct before injecting it, so this
            // reads real restored history. Only backfill a model with no history at all - backfilling
            // one that has history would wipe its real GENERATE/LAUNCH progress.
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

    // generatedProjects/modelIdByProjectId are rebuilt rather than persisted: the project directory is
    // the source of truth for its own id and process key, and each model's GENERATE stage detail
    // already records which project it produced.
    // Order matters - this runs after processModels is populated and after workflowStateTracker's own
    // restore.
    private void restoreGeneratedProjects() {
        for (GeneratedProject project : springBootProjectGenerator.scanExisting()) {
            generatedProjects.put(project.projectId(), project);
        }
        if (generatedProjects.isEmpty()) {
            return;
        }
        for (ProcessModel model : processModels.values()) {
            WorkflowState state = workflowStateTracker.stateFor(model.getId());
            String projectId = currentProjectIdOf(state);
            // Only wired up when the project this model's GENERATE stage points at still exists on disk: a stale
            // detail from a deleted directory must not silently claim whatever id now occupies that slot.
            if (projectId != null && generatedProjects.containsKey(projectId)) {
                modelIdByProjectId.put(projectId, model.getId());
            }
            // A superseded project's JVM does not normally survive a restart, so leftovers on disk
            // are collectable now. "Normally" is doing real work: a hard kill skips @PreDestroy and
            // leaves generated apps still holding their ports, invisible to this instance's empty
            // registry - hence the port probe before collecting anything.
            if (aRecordedLaunchPortIsStillListening(state)) {
                logger.warn("Skipping generated-project cleanup for model {} on startup - a port it previously "
                        + "launched on is still listening, so a generated app from before this restart may still "
                        + "be running. Its projects will be collected once that port is free.", model.getId());
                continue;
            }
            cleanupSupersededProjects(model.getId(), state);
        }
    }

    // Every port this model was recorded as launching on, newest first. A STOPPED event is not taken
    // as proof the port is free - the probe decides that - and a port never recorded cannot be
    // checked at all, which is why this is a best-effort guard rather than a liveness check.
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

    // Retention (latest generation only, chosen product policy): a model keeps exactly one generated project - its newest completed generation - and older ones are disposable once nothing is running out of them. Computes current project ID from completed GENERATE history.
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

    // Lists superseded project IDs for a process model.
    private static List<String> supersededProjectIdsOf(WorkflowState state) {
        List<String> superseded = new ArrayList<>(allGeneratedProjectIdsOf(state));
        superseded.remove(currentProjectIdOf(state));
        return superseded;
    }

    // Lists all generated project IDs for a process model.
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

    // Best-effort cleanup of superseded projects.
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
        // never another model's project. supersededProjectIdsOf() read this id out of THIS model's own history, so a conflicting owner means two models' histories disagree about who generated it - unresolvable from here, and deleting on a guess is the one outcome that can't be undone
        String owner = modelIdByProjectId.get(projectId);
        if (owner != null && !owner.equals(modelId)) {
            logger.warn("Not deleting generated project {} while cleaning up model {} - it is recorded as "
                    + "belonging to model {}", projectId, modelId, owner);
            return;
        }
        boolean wasIdle = springBootProjectLauncher.runIfIdle(projectId, () -> {
            // Re-read inside the lock: the list was computed before it was taken, and a concurrent
            // regenerate can append a GENERATE event in between. This is what makes "never delete the
            // current project" true at the moment of deletion rather than a moment earlier.
            if (projectId.equals(currentProjectIdOf(workflowStateTracker.stateFor(modelId)))) {
                logger.info("Generated project {} became the current generation for model {} before it could be "
                        + "cleaned up - retaining it", projectId, modelId);
                return;
            }
            if (springBootProjectGenerator.delete(projectId)) {
                // only after the directory is actually gone - a project still on disk must stay reachable through launchGeneratedProject, and scanExisting() would put it back on the next restart anyway
                generatedProjects.remove(projectId);
                modelIdByProjectId.remove(projectId, modelId);
            }
        });
        if (!wasIdle) {
            // the whole point of the policy's "superseded + running -> retain temporarily" arm
            logger.info("Retaining superseded generated project {} for model {} - it is still running or "
                    + "being launched; it will be collected when it next stops", projectId, modelId);
        }
    }

    // An approval is left APPROVED if the JVM died between approveEvolution() marking it so and
    // marking it COMPLETED/FAILED. Resolved on startup from Camunda's own committed variable history,
    // which is transactional with the setVariable itself: absent proves the evolution never ran,
    // present proves it did, whatever the Approval's status says. Never a blind retry.
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
                // The side effect this approval represents already happened before the crash, so marking COMPLETED
// here does not repeat it.
                approvalService.markCompleted(approval.id(),
                        "reconciled on restart - '" + evolvedAgentVariable + "' was already set");
                twin.getEventLog().add("Approval " + approval.id()
                        + " reconciled as COMPLETED on restart (already executed before crash)");
                logger.info("Reconciled approval {} as COMPLETED: '{}' already set", approval.id(),
                        evolvedAgentVariable);
                continue;
            }
            // The variable was never set, so the operation never ran. Running it now is its first execution, not
// a retry of one that may already have happened.
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
        if (twinBpmnXml == null || twinBpmnXml.isBlank()) {
            throw new IllegalArgumentException("Authored twin bpmnXml must not be blank");
        }
        return doSaveProcessModelEntry(id, name, bpmnXml, twinBpmnXml, tenantId, projectId);
    }

    private ProcessModel doSaveProcessModelEntry(String id, String name, String bpmnXml, String twinBpmnXml,
            String tenantId, Long projectId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Process model name must not be blank");
        }
        if (bpmnXml == null || bpmnXml.isBlank()) {
            throw new IllegalArgumentException("Process model bpmnXml must not be blank");
        }
        String modelId;
        if (id != null && !id.isBlank()) {
            // Validates model ID syntax.
            if (!SAFE_MODEL_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Process model id may only contain letters, digits, "
                        + "'-' and '_': " + id);
            }
            // no overwriting - twins already launched still point at the old definition
            if (processModels.containsKey(id)) {
                throw new IllegalArgumentException("Process model already exists: " + id);
            }
            // Reject reuse of retired model IDs to preserve history.
            if (isRetiredModelId(id)) {
                throw new IllegalArgumentException("Process model id '" + id + "' has already been used and "
                        + "cannot be reused - its workflow history is kept after deletion");
            }
            modelId = id;
        } else {
            modelId = UUID.randomUUID().toString();
        }

        // Records IN_PROGRESS status before saving.
        workflowStateTracker.record(modelId, WorkflowStage.MODEL, StageStatus.IN_PROGRESS, null);
        try {
            return doSaveProcessModel(modelId, name, bpmnXml, twinBpmnXml, tenantId, projectId);
        } catch (RuntimeException e) {
            // Record FAILED status on save failure.
            workflowStateTracker.record(modelId, WorkflowStage.MODEL, StageStatus.FAILED, e.getMessage(),
                    new StageError(e.getClass().getSimpleName(), "SAVE_MODEL", null, null, null, null, null));
            throw e;
        }
    }

    // twinBpmnXml is null on the ordinary single-BPMN path. When present it is validated but NOT
    // deployed here: only the primary bpmnXml runs on the Workbench's engine, so
    // processDefinitionId keeps its existing meaning. The authored twin only ever runs inside the
    // generated Target Platform, which gets its own engine.
    private ProcessModel doSaveProcessModel(String modelId, String name, String bpmnXml, String twinBpmnXml,
            String tenantId, Long projectId) {
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
            // singleResult() throws if the XML has more than one executable process. their mistake, not ours, so 400
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

        ProcessModel model = new ProcessModel(modelId, name, bpmnXml, twinBpmnXml, Instant.now(),
                definition.getId(), tenantId);
        // the containsKey above isn't enough on its own - two saves of the same id can both clear it and both deploy, and the loser would silently replace the winner's definition
        ProcessModel existing = processModels.putIfAbsent(modelId, model);
        if (existing != null) {
            discardDeployment(deployment.getId());
            throw new IllegalArgumentException("Process model already exists: " + modelId);
        }
        Path bpmnFilePath;
        Path twinBpmnFilePath = null;
        try {
            // The generation step needs a real .bpmn file on disk, not just the copy of this XML the archive
            // embeds as a string field.
            bpmnFilePath = modelFileStore.save(modelId, bpmnXml);
            if (twinBpmnXml != null) {
                twinBpmnFilePath = modelFileStore.saveTwin(modelId, twinBpmnXml);
            }
        } catch (RuntimeException e) {
            // don't leave a model that's deployed and in memory but has no matching file - roll both back rather than leave a half-saved model the Generate step would silently fail against later
            processModels.remove(modelId, model);
            discardDeployment(deployment.getId());
            throw e;
        }
        // H2-backed archive is the model's persistence, full stop - the JSON snapshot alongside it covers twins only
        processModelArchiveStore.save(model, bpmnFilePath, twinBpmnFilePath, projectId);
        persistState();
        workflowStateTracker.record(modelId, WorkflowStage.MODEL, StageStatus.COMPLETED, null);
        logger.info("Saved process model {} and deployed process definition {}", modelId, definition.getId());
        return model;
    }

    // Structural check only, no deployment: an authored twin never runs on the Workbench's engine, so
    // it should not gain a deployment footprint there. Applies the same "exactly one executable
    // process" rule the real deployment enforces for bpmnXml.
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

    // we deploy before we can check any of this, so a rejected model would otherwise leave its deployment sitting in the engine and showing up in cockpit
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
        // ConcurrentHashMap.get(null) throws NPE - used to 500 on a launch body with no modelId
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Process model id must not be blank");
        }
        ProcessModel model = processModels.get(id);
        if (model == null) {
            throw new NoSuchElementException("Process model not found: " + id);
        }
        return model;
    }

    // An id a model was once created under but no model currently holds. Keyed on MODEL/COMPLETED
    // rather than "has any history": a save rejected for bad BPMN never reaches COMPLETED, and
    // retrying that id is normal - otherwise every rejected save would burn its id permanently.
    private boolean isRetiredModelId(String modelId) {
        for (StageEvent event : workflowStateTracker.stateFor(modelId).history()) {
            if (event.stage() == WorkflowStage.MODEL && event.status() == StageStatus.COMPLETED) {
                return true;
            }
        }
        return false;
    }

    // Deletion is an authoring operation, so it only excludes the other one that can invent state for
    // the same model - Generate. Launch and cleanup already serialise on the launcher's per-project
    // lock, and Evolve touches a twin, never a model.
    // Lock order where both are held: model lock first, then project locks.
    private Object modelLockFor(String modelId) {
        return modelLocks.computeIfAbsent(modelId, id -> new Object());
    }

    // Removes what the model owns and nothing else. Twins, their Camunda instances and deployments,
    // approvals, policies and workflow history all survive: a twin holds its model id as provenance
    // only, and deployments are shared between a model's twins, so cascading would kill live instances
    // of twins that are still running fine.
    // Refuses outright if a generated app is running - deleting a model is not a reason to kill it.
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
            // every generation, not just the superseded ones - once the model is gone, its current generation has nothing left to belong to either
            List<String> projectIds = allGeneratedProjectIdsOf(workflowStateTracker.stateFor(modelId));
            boolean deleted = springBootProjectLauncher.runIfAllIdle(projectIds, () -> {
                for (String projectId : projectIds) {
                    // same ownership guard cleanupSupersededProjects uses - never delete a directory another model is recorded as owning
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
            // history is NOT touched: it is what retires this id for good (see isRetiredModelId)
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
        // Reads off the archive store rather than the in-memory processModels map, which has no notion of a
        // project.
        return processModelArchiveStore.findAllSummaries();
    }

    @Override
    public List<GeneratedDelegate> generateDelegates(String modelId) {
        ProcessModel model = getProcessModel(modelId);
        return delegateClassGenerator.generate(model.getBpmnXml());
    }

    @Override
    public GeneratedProject generateSpringBootProject(String modelId) {
        // model lock held across the whole generate, so a delete can't land between the model lookup below and the GENERATE record - which would otherwise leave a generated project and a COMPLETED event belonging to a model that no longer exists
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
                // Model saved with an authored twin: both BPMNs go into the generated Target
                // Platform as-is. Everything after this branch is identical to the single-BPMN path.
                project = springBootProjectGenerator.generateWithAuthoredTwin(model.getBpmnXml(),
                        model.getAuthoredTwinBpmnXml(), model.getName());
            } else {
                // Regenerated rather than reusing generateDelegates' output, which renders against
                // DelegateClassGenerator's default package - fine for previewing source, wrong for where the file is
                // about to be written. Must be SpringBootProjectGenerator.DELEGATE_PACKAGE, or the class compiles but
                // Spring's component scan never finds it.
                List<GeneratedDelegate> delegates = delegateClassGenerator.generate(model.getBpmnXml(),
                        SpringBootProjectGenerator.DELEGATE_PACKAGE);
                project = springBootProjectGenerator.generate(model.getBpmnXml(), delegates, model.getName());
            }
            generatedProjects.put(project.projectId(), project);
            modelIdByProjectId.put(project.projectId(), modelId);
            // projectId as the detail, not just a bare COMPLETED - stopGeneratedProject/ launchGeneratedProject both key off project ids, and the breadcrumb needs a way to hand one to the caller without a second round trip through generatedProjects
            workflowStateTracker.record(modelId, WorkflowStage.GENERATE, StageStatus.COMPLETED, project.projectId());
            logger.info("Generated Target Harness Platform {} for model {}", project.projectId(), modelId);
            // This generation is now the current one, so every earlier generation of this model is superseded.
            // Deliberately after the COMPLETED record, so "current" is read from committed history: a generate
            // that failed before this point leaves the previous generation current and collects nothing.
            cleanupSupersededProjects(modelId);
            return project;
        } catch (RuntimeException e) {
            workflowStateTracker.record(modelId, WorkflowStage.GENERATE, StageStatus.FAILED, e.getMessage(),
                    generateErrorFrom(e));
            throw e;
        }
    }

    // delegateExpression/bpmnElementId are only known for a DelegateWriteException - the one failure
    // scoped to a single BPMN element. Everything else fails the operation as a whole and stays null
    // rather than guessing.
    private static StageError generateErrorFrom(RuntimeException e) {
        if (e instanceof DelegateWriteException dwe) {
            return new StageError(e.getClass().getSimpleName(), "GENERATE_PROJECT", null, null, null,
                    "${" + dwe.beanName() + "}", dwe.bpmnElementId());
        }
        // the other failure that genuinely knows its BPMN element: one task declaring a delegateExpression that names no bean (see InvalidDelegateExpressionException). Carrying both fields is what makes the editor's "Go to error" able to select that exact task.
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
            // restart no longer loses this on its own (see restoreGeneratedProjects()) - a genuine miss here means the id was never real, or its project directory is gone/unreadable
            throw new NoSuchElementException("Generated project not found: " + projectId
                    + " - it may not exist, or its generated-project directory may be missing or unreadable");
        }
        // absent when this project's own model can no longer be identified - a legacy project whose workflow history predates persistence entirely, or one whose GENERATE detail didn't survive for some other reason. The launch still works, it just has no breadcrumb to update.
        String modelId = modelIdByProjectId.get(projectId);
        if (modelId != null) {
            workflowStateTracker.record(modelId, WorkflowStage.LAUNCH, StageStatus.IN_PROGRESS, null);
        }
        try {
            // Messaging is opt-in at the launcher level (see SpringBootProjectLauncher.launch), so it's enabled only when this generated project actually has a Twin. hasAuthoredTwin() alone isn't reliable here since an operationally-derived Twin (see OperationalTwinGenerator) never gets written back onto the ProcessModel.
            boolean generatedProjectHasMessaging = projectHasMessagingLayer(project.directory());
            Map<String, String> extraEnv = generatedProjectHasMessaging
                    ? Map.of("METAML_MESSAGING_ENABLED", "true")
                    : Map.of();
            LaunchedProject launched = springBootProjectLauncher.launch(project, extraEnv);
            if (modelId != null) {
                workflowStateTracker.record(modelId, WorkflowStage.LAUNCH, StageStatus.COMPLETED,
                        "port " + launched.port());
            }
            ProcessModel model = modelId != null ? processModels.get(modelId) : null;
            String displayName = model != null ? model.getName() : project.displayName();
            return new LaunchedProject(launched.projectId(), launched.processKey(), launched.port(),
                    launched.launchedAt(), modelId, displayName != null ? displayName : launched.processKey());
        } catch (RuntimeException e) {
            if (modelId != null) {
                workflowStateTracker.record(modelId, WorkflowStage.LAUNCH, StageStatus.FAILED, e.getMessage(),
                        launchErrorFrom(e, projectId));
            }
            throw e;
        }
    }

    // Whether generateWithAuthoredTwin wrote a RabbitMqConfig.java anywhere under this project.
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
        // Deliberately not gated on generatedProjects: a launched process never survives a restart, and
        // refusing to stop what the launcher still tracks would leave a running app nothing could reach.
        // The launcher's registry is the authority on what is running.
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        // Read before stop(), not after - the launcher drops its entry once stopped, and the port is worth
        // keeping on the STOPPED event rather than losing it to the fold's latest-event-wins rule.
        String portDetail = springBootProjectLauncher.find(projectId)
                .map(launched -> "port " + launched.port())
                .orElse(null);
        boolean wasRunning = springBootProjectLauncher.stop(projectId);
        String modelId = modelIdByProjectId.get(projectId);
        if (wasRunning && modelId != null) {
            workflowStateTracker.record(modelId, WorkflowStage.LAUNCH, StageStatus.STOPPED, portDetail);
        }
        // A project superseded while running was left alone at the time; stopping is the lifecycle
        // event that makes it collectable. Runs regardless of wasRunning - a JVM that already died
        // externally is just as collectable.
        if (modelId != null) {
            cleanupSupersededProjects(modelId);
        }
        return wasRunning;
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
                    return new LaunchedProject(launched.projectId(), launched.processKey(), launched.port(),
                            launched.launchedAt(), modelId, displayName != null ? displayName : launched.processKey());
                })
                .toList();
    }

    // One launch, one twin, and the twin always gets a definition of its own that its token can walk.
    // A second entry point for that used to exist alongside a passive default, which meant two UI buttons
    // producing twins that behaved nothing alike.
    @Override
    public TwinProcess launchProcess(String modelId) {
        ProcessModel model = getProcessModel(modelId);
        boolean twinWasAlreadyDeployed = repositoryService.createDeploymentQuery()
                .deploymentName(twinDeploymentName(model)).count() > 0;
        ProcessDefinition twinDefinition = deployTwinDefinition(model);
        try {
            return launch(model, twinDefinition.getId());
        } catch (RuntimeException e) {
            // Only clean up a deployment this call actually made. Duplicate filtering hands back the one an earlier launch created, and a twin from that launch can still be running on it - deleting it cascade-deletes a live instance.
            if (!twinWasAlreadyDeployed) {
                discardDeployment(twinDefinition.getDeploymentId());
            }
            throw e;
        }
    }

    private static String twinDeploymentName(ProcessModel model) {
        return model.getName() + " (twin " + model.getId() + ")";
    }

    // The twin is generated from what's actually deployed rather than from the stored XML, so it can't drift from the definition the original is running.
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
            // Deployment and resource name must be identical on every launch of this model or duplicate filtering
            // has nothing to compare against - hence the model id in both, since two models may share a display
            // name. Without it, ten launches left ten twin deployments behind, each its own definition version.
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

    // twinDefinitionId is the original's own on the plain path, which is the whole difference between a twin that can move and one that can't
    private TwinProcess launch(ProcessModel model, String twinDefinitionId) {
        String twinId = UUID.randomUUID().toString();

        ProcessInstance original = runtimeService.startProcessInstanceById(
                model.getProcessDefinitionId(), BusinessKeys.originalKey(twinId));
        ProcessInstance twinInstance;
        try {
            twinInstance = runtimeService.startProcessInstanceById(twinDefinitionId, BusinessKeys.twinKey(twinId));
        } catch (RuntimeException e) {
            // kill the original as well, otherwise every failed launch leaks a live instance
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
        // A twin never picks its own tenant; it inherits the one on the model it was launched from, which is
// null for a model saved before tenancy existed.
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
        // stored status goes stale as soon as either instance ends, so recompute every read
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
        // the twin's own definition, not the original's. The two happen to share activity ids today, so checking the original passed for the wrong reason - and would keep passing for an id the generator had dropped, leaving a link pointing at nothing.
        requireActivityInDefinition(twin.getTwinProcessDefinitionId(), twinActivityId, "twinActivityId");

        // One twin activity maps to one original activity: evolvedAgent_<twinActivityId> and the
        // advance message are keyed on twinActivityId alone, so a second original sharing it would
        // clobber the first. Synchronized on the twin because CopyOnWriteArrayList makes each
        // operation safe but not this check-then-add; one lock per twin, since only calls racing on
        // the same twin can conflict.
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

            // replace not append - lookups use findFirst() so a duplicate link would just sit unused
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

        // Only resolve a loop counter when the caller named a specific runtime instance. Claiming
        // the activity as a whole is deliberate when no instance exists yet - that is the only way
        // to get ahead of the auto-bridge, which fires the instant the first instance starts.
        Object loopCounter = activityInstanceId == null ? null
                : loopCounterOf(twin, activityId, activityInstanceId);
        String pendingVariable = AgentVariables.integrationPending(twinActivityId, loopCounter);
        try {
            runtimeService.setVariable(twin.getTwinProcessId(), pendingVariable, Boolean.TRUE);
        } catch (ProcessEngineException e) {
            // twin already ended - there is nothing left for the auto-bridge to advance either
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

    // True while an operator has claimed this visit (or the whole activity) for integration and no
    // evolution has bound an agent for it yet. Checked against both names so a claim staked before
    // the activity had any runtime instance still holds the sibling that later starts.
    private boolean integrationPendingFor(TwinProcess twin, String twinActivityId, Object loopCounter) {
        Map<String, Object> variables;
        try {
            variables = runtimeService.getVariables(twin.getTwinProcessId());
        } catch (ProcessEngineException e) {
            // twin already ended - nothing to hold
            return false;
        }
        return Boolean.TRUE.equals(variables.get(AgentVariables.integrationPending(twinActivityId, null)))
                || (loopCounter != null && Boolean.TRUE.equals(
                        variables.get(AgentVariables.integrationPending(twinActivityId, loopCounter))));
    }

    // Released by the evolution that actually binds an agent for this visit - both the per-visit and
    // the whole-activity claim, since either could have been the one holding it.
    private void releaseIntegrationClaim(TwinProcess twin, String twinActivityId, Object loopCounter) {
        try {
            runtimeService.removeVariable(twin.getTwinProcessId(),
                    AgentVariables.integrationPending(twinActivityId, null));
            if (loopCounter != null) {
                runtimeService.removeVariable(twin.getTwinProcessId(),
                        AgentVariables.integrationPending(twinActivityId, loopCounter));
            }
        } catch (ProcessEngineException e) {
            // twin already ended - the claim cannot hold anything any more either way
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
        // check before logging - a null agentType used to 500 after the event log was already written
        if (agentType == null || agentType.isBlank()) {
            throw new IllegalArgumentException("agentType must not be blank");
        }
        // missing activityId isn't an NPE, it quietly logs "activity null" and returns not-connected
        if (activityId == null || activityId.isBlank()) {
            throw new IllegalArgumentException("activityId must not be blank");
        }

        TwinProcess twin = getTwinProcess(twinProcessId);
        // every path below writes to the event log, so persist once at the end instead of per-return
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
            // WARN with a stable, greppable prefix: these operator-actionable skips used to log at INFO alongside
            // routine success, which is how a twin could stop silently while the human side saw only 200s.
            logger.warn("TWIN_SKIPPED: evolve blocked for activity {} on twin {}: activity not connected",
                    activityId, twinProcessId);
            return new AgentDecision(agentType, false, null,
                    "Activity not connected to twin process");
        }

        // Runtime identity fix (Scope 6 P1): currentVisitId()'s most-recently-started heuristic
        // cannot distinguish between concurrently active siblings of a parallel (non-sequential)
        // multi-instance activity that share the same activityId. When the caller supplies an
        // explicit activityInstanceId - the same runtime activity-instance identity
        // bridgeActivityEvent(twinId, activityId, activityInstanceId) already accepts - trust it
        // directly instead of resolving "the" visit by timestamp ordering. Both ids live in the
        // same Camunda identity space (HistoricActivityInstance/ActivityInstance share their id),
        // exactly like originalExecutionIdForVisit and loopCounterOf already assume below.
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

        // Scope 6 lifecycle guard: currentVisitId() falls back to the newest completed
        // historical visit when nothing is currently active - that fallback is correct
        // for bridge semantics (forwarding what already happened) but not for evolution
        // (changing what will execute next).  Evolving an activity on an ended original
        // process overwrites evolvedAgent_* without any future twin execution to consume
        // it; getActivityExecutionState then returns the new agent name alongside stale
        // automation output from a prior run, producing a misleading EXECUTED status.
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
            // runEvolution sets evolvedAgent_<twinActivityId>[_loopCounter] on approval, which is exactly the signal bridgeOnce's alreadyEvolved() checks before letting the auto-bridge (or a repeat manual bridge) stomp this visit with the default agent type - no separate bookkeeping needed here for that to work.
            return runEvolution(twin, twinProcessId, activityId, twinActivityId,
                    loopCounterOf(twin, activityId, visitId), agentType);
        } finally {
            evolutionsInFlight.remove(claim);
        }
    }

    private static String evolutionClaim(String twinProcessId, String activityInstanceId) {
        // twin ids are uuids, so the first colon here is always the separator
        return twinProcessId + ":" + activityInstanceId;
    }

    // Bridges activity event with default agent type.
    @Override
    public AgentDecision bridgeActivityEvent(String twinProcessId, String activityId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        String visitId = currentVisitId(twin, activityId);
        // Evolving has to move the twin too. The original's first activity starts inside
        // startProcessInstanceById, before the twin is registered, so the auto trigger never sees it -
        // without this the twin would wait on that first message forever.
        // Only when the original has actually visited the activity: a null visit means it has not, and
        // advancing anyway would put the twin ahead of what it mirrors. Deliberately not gated on
        // isApproved() - a refused evolution says nothing about where the original's token is.
        return bridgeAndAdvance(twin, twinProcessId, activityId, visitId);
    }

    // Resolves original execution ID for a visit instance.
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

    // Bridges activity event for a specific activity instance.
    @Override
    public AgentDecision bridgeActivityEvent(String twinProcessId, String activityId, String activityInstanceId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        return bridgeAndAdvance(twin, twinProcessId, activityId, activityInstanceId);
    }

    // Read-only: reconstructs what a bound ComponentExecutor has actually done for this activity,
    // straight from the same MetaML-owned process variables TwinAutomationDelegate itself wrote
    // (see AgentVariables) - never anything computed beyond that, never a mutation.
    @Override
    public TwinActivityExecutionState getActivityExecutionState(String twinProcessId, String activityId) {
        TwinProcess twin = getTwinProcess(twinProcessId);
        if (activityId == null || activityId.isBlank()) {
            throw new IllegalArgumentException("activityId must not be blank");
        }

        // Runtime instance discovery (Scope 6 identity fix): a fact about the ORIGINAL process
        // instance's live runtime state, independent of whether this activity is connected to a
        // twin activity yet - computed unconditionally so a caller can discover concurrent
        // siblings (and their activityInstanceId) before/without needing a completed connect step.
        List<ActiveRuntimeInstance> activeInstances = activeInstancesOf(twin, activityId);

        String twinActivityId = twin.findTwinActivityId(activityId).orElse(null);
        if (twinActivityId == null) {
            return new TwinActivityExecutionState(activityId, null, null, "NOT_STARTED", null, Map.of(),
                    activeInstances);
        }

        // Single fetch of every MetaML-owned variable currently on the twin instance (or, once it
        // has ended, its history) - agentName/summary/output below all read from this one map
        // rather than three separate engine round-trips.
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

    // Same identity space and same live-runtime source loopCounterOf()/originalExecutionIdForVisit()
    // already use (ActivityInstance.getId() from runtimeService.getActivityInstance(), not
    // currentVisitId()'s historic-query heuristic) - deliberately reused rather than a second
    // identity-resolution mechanism. Returns one entry per currently-active sibling; empty when the
    // original process has ended, hasn't reached this activity, or the activity has already
    // completed (a completed visit is no longer part of the live ActivityInstance tree).
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

    // Same runtime-then-history fallback idiom as evolvedAgentVariableIsSet above, but returning
    // every variable rather than testing one - an ended twin instance has nothing left in
    // runtimeService, only in history.
    private Map<String, Object> readTwinVariables(TwinProcess twin) {
        Map<String, Object> runtime = null;
        try {
            runtime = runtimeService.getVariables(twin.getTwinProcessId());
        } catch (ProcessEngineException e) {
            // twin instance already ended - fall through to history below
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

    // Picks out exactly the twinAutomationOutput_<name>_<twinActivityId> variables for this one
    // activity and strips the AgentVariables encoding back down to the bare output name a
    // ComponentExecutor actually wrote (see AgentVariables#twinAutomationOutput) - deliberately
    // NOT a raw variable dump: every other variable on the twin instance is ignored.
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

    // Best-effort FAILED signal reusing the twin's own event log rather than adding new tracking -
    // the exact prefix advanceTwinActivity's catch block below writes when correlate()/
    // messageEventReceived() throws for this activity.
    private boolean activityFailedToExecute(TwinProcess twin, String twinActivityId) {
        String marker = "Twin activity " + twinActivityId + " failed to execute:";
        return twin.getEventLog().stream().anyMatch(line -> line.startsWith(marker));
    }

    // Bridges and advances twin activity under concurrency control.
    private AgentDecision bridgeAndAdvance(TwinProcess twin, String twinProcessId, String activityId,
            String activityInstanceId) {
        if (activityInstanceId == null) {
            // nothing to advance either way - bridgeOnce's own "not reached yet" skip covers this
            AgentDecision decision;
            try {
                decision = bridgeOnce(twin, twinProcessId, activityId, null);
            } finally {
                persistState();
            }
            return decision;
        }

        // Integration hold. Checked here rather than inside bridgeOnce because the advance below
        // deliberately runs whatever bridgeOnce decides (an evolution refused by governance says
        // nothing about where the original's token is), so refusing only the evolution would still
        // let the twin be advanced through the activity and execute it. Both have to be suppressed
        // for the claim to mean anything. This does NOT break lockstep: the twin stays parked on
        // this activity's own receive task, which is exactly where the original is - it is the
        // correlation that would move it PAST the activity, and that is what is being deferred.
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
                // Without resolving this, a parallel multi-instance activity with more than one open sibling always
                // falls to the plain-correlate path below and throws MismatchingMessageCorrelationException - a caller
                // with no live ExecutionEvent has no execution id to read the way AutoBridgeTrigger does.
                advanceTwinActivity(twinProcessId, activityId,
                        originalExecutionIdForVisit(twin, activityId, activityInstanceId));
            } catch (RuntimeException e) {
                // the bridge itself worked and is already committed, so don't turn it into a failure - advanceTwinActivity has put the reason in the twin's event log already
                logger.warn("Bridged activity {} on twin {} but could not move the twin through it: {}",
                        activityId, twinProcessId, e.toString());
            }
            return decision;
        } finally {
            evolutionsInFlight.remove(claim);
        }
    }

    // No claim of its own any more - bridgeAndAdvance above holds one claim across both this and the advance that follows it, so a second caller for the same visit never reaches this at all.
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

    // Derived from Camunda's own history, not an in-memory set a restart would wipe. Two cases,
    // because evolvedAgent_<twinActivityId> is only visit-unique in one of them: a multi-instance
    // visit carries a loopCounter, so the name alone is enough; a plain activity revisited through a
    // loop-back gateway reuses the same name, so compare which numbered visit this is against how
    // many times the variable was actually SET.
    // Count variable SETS, not automation completions - a failed automation rolls back its own task
    // history but not the evolve write that already committed, so counting completions would
    // re-evolve on every retry. See ADR-012.
    // Not disambiguated: two concurrent tokens re-entering the same plain activity.
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
            // shouldn't happen - the caller already resolved this activityInstanceId from the same history - but treat "can't place this visit" as "not yet evolved" rather than guess
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

    // durable across restarts by construction - a row in the engine's own tables, not app memory - the same "shared Camunda runtime is the source of truth" invariant everything here depends on. Falls back to history for a twin that has since ended, where runtimeService has nothing left to read.
    private boolean evolvedAgentVariableIsSet(TwinProcess twin, String evolvedAgentVariable) {
        try {
            if (runtimeService.getVariable(twin.getTwinProcessId(), evolvedAgentVariable) != null) {
                return true;
            }
        } catch (ProcessEngineException e) {
            // twin instance already ended - fall through to history below
        }
        return historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .variableName(evolvedAgentVariable)
                .count() > 0;
    }

    // The twin's copy of an activity is a receive task, so its token stops there; correlating that
    // message runs the automation and carries the token to the next stop. None of the skips below are
    // errors - gateways and end events have no message, an unconnected activity has no twin activity,
    // and an original that has walked past its twin has nothing to correlate.
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

        // Parallel multi-instance can have more than one twin sibling waiting on the identical message at once - plain and sequential activities never do, so this only ever resolves to something when there's genuinely more than one candidate to choose between.
        String messageName = TwinModelGenerator.twinMessageName(twinActivityId);
        String parallelSiblingExecutionId = originalExecutionId == null ? null
                : resolveParallelSibling(twin, messageName, originalExecutionId);

        // The twin hasn't reached the target receive task yet. Rather than jumping
        // the token there with startBeforeActivity() — which commits in its own Camunda
        // command and creates an orphan execution if the subsequent correlation fails
        // skip. The twin will reach the activity
        // naturally as it processes earlier activities and flows through the BPMN structure.
        // Gateway variables are set explicitly, so exclusive gateways evaluate
        // correctly when the twin reaches them without needing a process modification jump.
        if (parallelSiblingExecutionId == null && !isTwinWaitingAt(twin, twinActivityId)) {
            logger.debug("TWIN_SKIP: twin {} is not waiting at {} — twin will reach it "
                    + "naturally when earlier activities complete",
                    twinProcessId, twinActivityId);
            return TwinAdvance.skipped(twinActivityId,
                    "Twin has not reached activity " + twinActivityId + " yet");
        }

        // Its own budget, not reserveEvolutionSlot's: that one limits agent requests, this counts every
        // step the twin takes. Reserved after the waiting check so gateways and end events, which have
        // nothing to advance, spend nothing.
        GovernanceDecision reservation = governanceService.reserveTwinExecutionSlot(twinProcessId);
        if (!reservation.isAllowed()) {
            twin.getEventLog().add("Twin activity " + twinActivityId
                    + " left parked by governance: " + reservation.getReason());
            persistState();
            logger.warn("TWIN_SKIPPED: twin activity {} on twin {} left parked by governance: {}",
                    twinActivityId, twinProcessId, reservation.getReason());
            return TwinAdvance.skipped(twinActivityId, reservation.getReason());
        }

        // Set gateway variables from explicit simulation context or fail explicitly.
        // The production path is handled by TwinAutomationDelegate, which propagates
        // ComponentExecutor outputs as bare gateway variables during correlation (same
        // transaction). Some executors also set variables directly via execution.setVariable()
        // (e.g. CreditRiskAssessorExecutor sets agentFlaggedRisk). This pre-correlation block
        // is ONLY for explicit simulation values — deterministic, observable, test-oriented
        // values that the caller or test has stored in _simulationGatewayValues. If a required
        // gateway variable has no simulation value and no executor produces it, the gateway
        // will fail with PropertyNotFoundException (for bare ${var} patterns) or evaluate null
        // (for execution.getVariable(...) patterns). This is correct: MetaML must not invent
        // a business decision merely because the process requires one.
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
                    // Check if already set on the twin (by a previous executor or earlier bridge)
                    Object existing = runtimeService.getVariable(twin.getTwinProcessId(), varName);
                    if (existing != null) {
                        logger.debug("Gateway variable '{}' already set to {} on twin {} — "
                                + "production value preserved", varName, existing, twinProcessId);
                        continue;
                    }
                    // Check explicit simulation context
                    Object simValue = simulationValues != null ? simulationValues.get(varName) : null;
                    if (simValue != null) {
                        runtimeService.setVariable(twin.getTwinProcessId(), varName, simValue);
                        appliedSimulation.put(varName, simValue);
                    } else {
                        // No production value, no simulation value. The executor running during
                        // correlation may still set it (production path). If it does not, the
                        // gateway will fail explicitly — which is correct behavior.
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
            // Twin instance may have already ended — not fatal, the correlation below will
            // decide whether advancement is still possible.
            logger.warn("Could not set gateway variables on twin {} for activity {}: {}",
                    twinProcessId, activityId, e.getMessage());
        }

        try {
            if (parallelSiblingExecutionId != null) {
                // targets one named execution directly, bypassing correlate()'s ambiguity - the only way proven to release exactly one parallel sibling and leave the rest waiting, since correlate() throws the instant more than one execution matches
                runtimeService.messageEventReceived(messageName, parallelSiblingExecutionId);
            } else {
                // scoped to this instance, so a second twin on the same definition waiting at the same activity is not a candidate and correlate() never has to pick between them
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

    // A real, Cockpit-visible Incident rather than a log line. The failed correlate() has already
    // rolled back, so the twin's receive task and its subscription sit untouched and re-bridging the
    // same activity is a safe retry.
    // Deliberately not a retry loop: ProjectAutomationService.execute() carries no idempotency
    // contract, so a blanket retry could double-invoke a billed or side-effecting agent. See ADR-008.
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
            // the original failure is still the one that matters and is already logged/rethrown by the caller - losing the incident record isn't worth masking it with a different one
            logger.warn("Could not record an incident for twin activity {} on twin {}: {}",
                    twinActivityId, twin.getId(), incidentFailure.toString());
            return;
        }
        logger.warn("Twin activity {} on twin {} failed to execute; incident recorded on execution {}: {}",
                twinActivityId, twin.getId(), executionId, failure.toString());
    }

    // The same question isTwinWaitingAt answers as a boolean, but keeps the execution id so an incident
    // can be attached to the right place. Uses the ActivityInstance tree, not getActiveActivityIds():
    // that can return a scope execution which merely sees the activity through a descendant, and
    // createIncident rejects it with "activity is null" because it needs the actual leaf.
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

    // Picks which twin sibling matches the original execution that just started, by loopCounter - both
    // sides create multi-instance children in the same order for the same cardinality.
    // The read must be non-local: loopCounter lives on the per-iteration scope one level above the
    // execution holding the subscription, so a local-only getVariable misses it.
    // Null when there is nothing to disambiguate, leaving the single-candidate path untouched.
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

    // getActiveActivityIds rather than an activityId() execution query: inside a sequential multi-instance the token sits on a child execution, and the query would only match if we already knew which one to ask.
    private boolean isTwinWaitingAt(TwinProcess twin, String twinActivityId) {
        for (Execution execution : runtimeService.createExecutionQuery()
                .processInstanceId(twin.getTwinProcessId()).list()) {
            if (runtimeService.getActiveActivityIds(execution.getId()).contains(twinActivityId)) {
                return true;
            }
        }
        return false;
    }

    // without this the original parks at its first task forever and evolve/bridge never unblock completes everything open, not one named task - a parallel gateway leaves several
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
        // user tasks first
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .list();
        // external tasks (process-independent: any BPMN with camunda:type="external")
        List<ExternalTask> externalTasks = externalTaskService.createExternalTaskQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .notLocked()
                .list();
        // Analyze BPMN to determine which gateway variables each external task topic must set.
        // Without these variables, downstream exclusive gateways would fail with
        // PropertyNotFoundException. The target platform's workers normally supply these;
        // the Workbench supplies explicit simulation values at the same external-task-completion
        // boundary — real Camunda process variables, not an EL-resolver fallback.
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
        // event subscriptions: signal/message catch events that block process advancement
        List<EventSubscription> eventSubscriptions = runtimeService.createEventSubscriptionQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .list();
        if (tasks.isEmpty() && externalTasks.isEmpty() && eventSubscriptions.isEmpty()) {
            // Fallback: if connected activities haven't been reached and normal advancement
            // is blocked (e.g. by gateways with missing variables, timers, conditional events),
            // use process instance modification to place a token at the target activity directly.
            // Generic — works for any BPMN whose normal flow can't be replayed in the Workbench.
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

        // each complete() is its own transaction, so this list can go stale mid-loop (a second request on the same twin, a branch finishing and taking its siblings with it) - task 3 blowing up used to throw away that tasks 1 and 2 really did complete
        List<String> completed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        RuntimeException firstRealFailure = null;
        for (Task task : tasks) {
            // definition key == the BPMN activity id, what connect/evolve key on
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
                        // somebody else completed it, or its branch got cancelled out from under us
                        skipped.add(label);
                        break;
                    }
                    // still there means the command rolled back and nothing happened. two requests racing on a shared parallel join is the way to reproduce it. one retry.
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

        // External tasks: complete each one directly by its id, supplying any gateway
        // variables that the target-platform worker would normally set. The BPMN analysis
        // (gatewayVarsByTopic above) maps each topic to the condition variables its downstream
        // exclusive gateway expects. Setting them here — as real Camunda process variables at the
        // external-task completion boundary — replaces the old EL-resolver Boolean.TRUE fallback
        // with an explicit, observable simulation mechanism.
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
                    // Use explicit simulation values instead of Math.random().
                    // The _simulationGatewayValues process variable on the TWIN holds
                    // deterministic values the caller or test has declared. These are
                    // passed as real Camunda process variables at the external-task
                    // completion boundary — the legitimate API for providing gateway
                    // state on the original process.
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
                            // No explicit simulation value for this gateway variable.
                            // The external task completes without it — the gateway will
                            // fail explicitly with PropertyNotFoundException for bare ${var}
                            // patterns, or evaluate null for execution.getVariable(...) patterns.
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

        // Event subscriptions: deliver pending signals/messages to advance past catch events.
        // Generic — any BPMN with inter-process signal/message communication benefits.
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

        // only surface an error if nothing at all moved, otherwise the partial progress is real and the caller needs to know about it more than it needs the stack trace
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

    // Every guard against doing an activity twice keys on Camunda's activity instance id, because a loop
    // or multi-instance activity returns under the same activity id. Callers that only know the activity
    // id - the manual Bridge and Evolve buttons - must resolve it here or their guard looks at a different
    // namespace than the auto-bridge's. Null means the original never got there.
    private String currentVisitId(TwinProcess twin, String activityId) {
        List<HistoricActivityInstance> visits = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .activityId(activityId)
                .orderByHistoricActivityInstanceStartTime().desc()
                .list();
        // one it's sitting on right now is what the button means. if it already walked past, the newest finished visit is the closest thing to what the caller is pointing at.
        for (HistoricActivityInstance visit : visits) {
            if (visit.getEndTime() == null) {
                return visit.getId();
            }
        }
        return visits.isEmpty() ? null : visits.get(0).getId();
    }

    // AgentExecutionDelegate reads loopCounter straight off the execution it's completing; over here all we have is the visit, so go the long way round to the same value. Null for a plain activity, which is what keeps its variable name short.
    private Object loopCounterOf(TwinProcess twin, String activityId, String activityInstanceId) {
        ActivityInstance tree = runtimeService.getActivityInstance(twin.getOriginalProcessId());
        if (tree == null) {
            // original already ended, so nothing is holding a loop counter any more
            return null;
        }
        for (ActivityInstance visit : tree.getActivityInstances(activityId)) {
            if (visit.getId().equals(activityInstanceId) && visit.getExecutionIds().length > 0) {
                return runtimeService.getVariableLocal(visit.getExecutionIds()[0], "loopCounter");
            }
        }
        return null;
    }

    // shared tail of evolve and bridge, both have already checked linked + reached by here
    private AgentDecision runEvolution(TwinProcess twin, String twinProcessId, String activityId,
            String twinActivityId, Object loopCounter, String agentType) {
        // governance before the node manager on purpose - it can deny a type the catalog is fine with
        GovernanceDecision reservation = governanceService.reserveEvolutionSlot(twinProcessId, agentType);
        if (!reservation.isAllowed()) {
            twin.getEventLog().add("Evolution blocked by governance: " + reservation.getReason());
            logger.info("Evolve blocked by governance for activity {} on twin {} with agent type {}: {}",
                    activityId, twinProcessId, agentType, reservation.getReason());
            return new AgentDecision(agentType, false, null, reservation.getReason());
        }

        boolean evolutionSucceeded = false;
        try {
            // Ask the tenant's own policy before anything the node manager or the twin would need
            // rolled back. A twin with no tenant has no policy to ask, so it stays ungoverned here
            // rather than being given a made-up tenant or silently denied.
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
            // fairly sure every early return and throw lands here, but if usage ever reads wrong this pairing is the first thing I'd go look at
            if (!evolutionSucceeded) {
                governanceService.releaseEvolutionSlot(twinProcessId);
            }
        }
    }

    // Returns null to mean "proceed as before" (ALLOW, or evaluation could not run); a real
    // AgentDecision means stop here. Called inside runEvolution's try/finally, so a DENY or
    // REQUIRE_APPROVAL still releases the platform slot already reserved.
    private AgentDecision enforceTenantPolicy(TwinProcess twin, String activityId, String twinProcessId,
            String twinActivityId, Object loopCounter, String agentType) {
        GovernanceRequest request = new GovernanceRequest(twin.getTenantId(), EVOLVE_TWIN_ACTION,
                Map.of("twinId", twinProcessId, "activityId", activityId, "agentType", agentType));

        PolicyDecision policyDecision;
        try {
            policyDecision = policyDecisionEngine.evaluate(request);
        } catch (NoSuchElementException | PolicyEvaluationException e) {
            // the tenant record is gone, or a stored rule is malformed - a real evaluation failure, not "no policy". Fails closed for the same reason the engine itself never turns a failure into a silent ALLOW: a broken policy must not be able to let something through that no one actually approved.
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
            // Pin the approval to THIS policy decision: resolving it later never re-evaluates, so a
            // tenant activating a new version cannot retroactively change what it meant.
            // loopCounter is stored as Integer so it survives the JSON round trip.
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
        // ALLOW - fall through, existing behavior continues unchanged
        return null;
    }

    // The work an evolution actually does, once platform and tenant governance have both said yes.
    // Extracted so the approval-resume path can run the same code without re-running enforceTenantPolicy:
    // pinning the policy decision on the Approval is precisely what stops resolution from re-evaluating
    // under whatever the tenant's policy says now.
    private AgentDecision executeAfterGovernance(TwinProcess twin, String twinProcessId, String activityId,
            String twinActivityId, Object loopCounter, String agentType) {
        String evolvedAgentVariable = AgentVariables.evolvedAgent(twinActivityId, loopCounter);
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
            return new AgentDecision(agentType, false, null, availability.getReason());
        }

        // this variable is the only real effect an evolution has. if it doesn't land (usually the twin already ended) then nothing happened, so don't say approved.
        boolean variableSet = false;
        try {
            runtimeService.setVariable(twin.getTwinProcessId(), evolvedAgentVariable,
                    availability.getAgentName());
            // Store the agent type alongside the name so automation dispatch can fall back
            // to type-level executor matching for multi-instance parallel activities where
            // each sibling gets a distinct agent name from the catalog.
            runtimeService.setVariable(twin.getTwinProcessId(),
                    AgentVariables.evolvedAgentType(twinActivityId, loopCounter), agentType);
            twin.getEventLog().add("Set process variable '" + evolvedAgentVariable
                    + "' = " + availability.getAgentName() + " on twin process instance "
                    + twin.getTwinProcessId());
            variableSet = true;

            // The binding just changed, so any execution record still sitting on this visit was
            // produced by whatever was bound BEFORE - not by the agent now bound. Leaving it in
            // place makes getActivityExecutionState report the new agent name alongside the old
            // executor's summary/output and still derive EXECUTED, which is the exact
            // "validator-agent-01 says CreditRiskAssessorExecutor ran" mismatch. The lifecycle
            // guard above only covers the ended-process case; this covers re-binding while the
            // original is still running. Same "supersede what this activity previously reported"
            // rule writeAgentOutputs already applies to evolvedAgentOutput_*, and it removes a
            // now-false attribution rather than inventing an outcome: status simply returns to
            // BOUND until the newly bound component actually runs.
            clearPriorExecutionRecord(twin, twinActivityId, loopCounter);

            // The integration this activity was being held for has now resolved into an actual
            // binding, so the hold is released and the next bridge advances the twin through the
            // activity with THIS agent. Only a successful binding gets here: a governance denial
            // or an unavailable agent returns earlier, leaving the claim in place so the refused
            // component still does not execute and the default does not silently take over.
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

        AgentDecision decision = new AgentDecision(agentType, true, availability.getAgentName(),
                availability.getReason(), availability.isRiskFlagged(), null);
        twin.getEventLog().add("Node manager reports agent type " + agentType
                + " available; selected agent " + availability.getAgentName());
        logger.info("Evolve approved for activity {} on twin {} with agent type {}",
                activityId, twinProcessId, agentType);
        return decision;
    }

    // PENDING -> REJECTED. The governed action must never run - ApprovalService's own PENDING-only guard
    // is what actually prevents a rejected approval from being resolved later.
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

    // PENDING -> APPROVED -> COMPLETED|FAILED. markApproved is the atomic gate: a second approve on the
    // same id throws before this touches the twin or the node manager, so the side effect happens at
    // most once. The platform quota is reserved freshly here - the original reservation was released
    // when REQUIRE_APPROVAL paused it.
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

    // Reconciled both ways, not just written. Re-evolving with an ordinary agent after a credit-risk run
    // otherwise left the old risk flag in place and the process kept escalating, while the twin showed a
    // plain agent with nothing wrong. The index variable is what makes the previous outputs knowable.
    // Removes the twinAutomation_/twinAutomationOutput_* record TwinAutomationDelegate wrote for
    // THIS visit of THIS activity, so a re-binding cannot leave the previous component's results
    // attributed to the newly bound one. Scoped exactly like activityOutputsFrom reads them -
    // same prefix and same per-visit suffix - so a different activity's record, and a different
    // visit of this same activity (which carries its own loopCounter in the name), are untouched.
    // Removing a variable that was never set is a no-op, so a first-time evolution costs nothing.
    private void clearPriorExecutionRecord(TwinProcess twin, String twinActivityId, Object loopCounter) {
        String summaryVariable = AgentVariables.twinAutomation(twinActivityId, loopCounter);
        // perVisit() is private to AgentVariables; recover the exact same encoding from the
        // summary variable's own name rather than re-deriving the convention here.
        String perVisitSuffix = "_" + summaryVariable.substring("twinAutomation_".length());
        String outputPrefix = "twinAutomationOutput_";

        Map<String, Object> variables;
        try {
            variables = runtimeService.getVariables(twin.getTwinProcessId());
        } catch (ProcessEngineException e) {
            // twin instance already ended - nothing live left to clear, and history is immutable
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

        // absence means "this evolution reported nothing", same convention the outputs themselves use
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
