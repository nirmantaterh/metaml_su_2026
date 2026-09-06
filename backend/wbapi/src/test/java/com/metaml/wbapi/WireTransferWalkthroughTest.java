package com.metaml.wbapi;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.governance.Approval;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.ApprovalStatus;
import com.metaml.workbench.governance.Policy;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.governance.PolicyEffect;
import com.metaml.workbench.governance.PolicyVersion;
import com.metaml.workbench.governance.Tenant;
import com.metaml.workbench.governance.TenantPolicyService;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.GovernanceService;
import com.metaml.workbench.service.WorkbenchService;
import com.metaml.workbench.service.WorkbenchServiceImpl;
import com.metaml.workbench.store.WorkbenchStateStore;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

// full walkthrough against a real embedded engine - only the node manager is stubbed
// mem db, not the file one the app uses - same url as WbapiApplicationTests so they share a context
@IsolatedWorkbenchTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-test;DB_CLOSE_DELAY=-1"
})
class WireTransferWalkthroughTest {

    private static final String KYC = "Task_KYC";
    private static final String AML = "Task_AML";
    private static final String OFAC = "Task_OFAC";
    private static final String CREDIT = "Task_Credit";
    private static final String ESCALATE = "Task_Escalate";
    private static final String APPROVE = "Task_Approve";
    private static final String EXECUTE = "Task_Execute";
    private static final String NOTIFY = "Task_Notify";

    // what the bridge picks when no caller supplied a type, and what the real catalog answers
    private static final String BRIDGE_AGENT = "validator-agent-01";

    // the one catalog entry that comes back with a flag raised, and the variable that flag turns
    // into on the original once the delegate has run
    private static final String RISK_AGENT_TYPE = "credit-risk-assessor";
    private static final String RISK_FLAG = "agentFlaggedRisk";

    @MockitoBean
    private NodeManagerClient nodeManagerClient;

    @Autowired
    private WorkbenchService workbenchService;
    @Autowired
    private GovernanceService governanceService;
    @Autowired
    private PolicyDecisionEngine policyDecisionEngine;
    @Autowired
    private TenantPolicyService tenantPolicyService;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private HistoryService historyService;
    @Autowired
    private TwinModelGenerator twinModelGenerator;
    @Autowired
    private ExternalTaskService externalTaskService;
    @Autowired
    private WorkbenchStateStore stateStore;
    @Autowired
    private com.metaml.workbench.store.ProcessModelFileStore modelFileStore;
    @Autowired
    private com.metaml.workbench.store.ProcessModelArchiveStore processModelArchiveStore;
    @Autowired
    private com.metaml.workbench.codegen.DelegateClassGenerator delegateClassGenerator;
    @Autowired
    private com.metaml.workbench.generation.SpringBootProjectGenerator springBootProjectGenerator;
    @Autowired
    private com.metaml.workbench.generation.SpringBootProjectLauncher springBootProjectLauncher;
    @Autowired
    private com.metaml.workbench.workflow.WorkflowStateTracker workflowStateTracker;

    @BeforeEach
    void stubTheCatalogAndOpenTheQuota() {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            // same rule the real NodeManagerServiceImpl catalog uses: only the credit assessor
            // ever comes back flagged
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog",
                    RISK_AGENT_TYPE.equals(type));
        });
        // seven activities get bridged on one twin below, well past the default cap of 5, so
        // the quota needs raising first.
        governanceService.updatePolicy(Set.of(), 20);
    }

    // Joanna's new scope doc, item 2 (Project Saving): the Generate/Spring-Boot-Generation step
    // needs a real .bpmn file on the server filesystem it can copy into a generated project, not
    // just the copy of the XML that WorkbenchStateStore already embeds inside its own shared
    // workbench-state.json. Proven end to end here, through the real service, not just against
    // ProcessModelFileStore in isolation (that's covered separately in
    // com.metaml.workbench.store.ProcessModelFileStoreTest).
    @Test
    void savingAModelWritesItsBpmnAsARealFileOnTheServerFilesystem() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "file store test", citibankBpmn());

        assertThat(modelFileStore.exists(model.getId())).isTrue();
        String onDisk = java.nio.file.Files.readString(modelFileStore.pathFor(model.getId()));
        assertThat(onDisk).isEqualTo(citibankBpmn());
    }

    // The id on a save request is client-supplied, and it becomes a filename under
    // workbench.models.directory. Rejected up front, before the deploy, so a traversal attempt
    // can't even leave a deployment behind on its way out - and asserting the throw on its own
    // would be a weak test here, since the whole risk is a file appearing somewhere it shouldn't,
    // so this checks the target directory is genuinely still empty afterwards.
    @Test
    void aTraversalShapedModelIdIsRejectedAndWritesNothingOutsideTheModelsDirectory() throws IOException {
        Path modelsDir = Path.of("./target/test-data/models").toAbsolutePath().normalize();
        Path escapeTarget = modelsDir.getParent().resolve("escaped.bpmn");
        Files.createDirectories(modelsDir);
        Files.deleteIfExists(escapeTarget);

        assertThatThrownBy(() -> workbenchService.saveProcessModel("../escaped", "traversal", citibankBpmn()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("may only contain letters, digits");
        assertThatThrownBy(() -> workbenchService.saveProcessModel(
                modelsDir.getParent().resolve("absolute-escaped").toString(), "traversal", citibankBpmn()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("may only contain letters, digits");

        assertThat(escapeTarget).doesNotExist();
        assertThat(modelsDir.getParent().resolve("absolute-escaped.bpmn")).doesNotExist();
        // and nothing landed inside the directory under a mangled name either
        try (var entries = Files.list(modelsDir)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.contains("escaped"));
        }
    }

    // New scope item 1 (Navigation & UI): "Edit Existing Project" needs a real list to pick from.
    @Test
    void listProcessModelsReturnsEveryModelNewestFirst() throws IOException {
        ProcessModel first = workbenchService.saveProcessModel(null, "first saved", citibankBpmn());
        ProcessModel second = workbenchService.saveProcessModel(null, "second saved", loanApprovalBpmn());

        List<ProcessModel> models = workbenchService.listProcessModels();

        assertThat(models).extracting(ProcessModel::getId).contains(first.getId(), second.getId());
        int firstIndex = models.indexOf(models.stream().filter(m -> m.getId().equals(first.getId())).findFirst().get());
        int secondIndex = models.indexOf(models.stream().filter(m -> m.getId().equals(second.getId())).findFirst().get());
        // second was saved after first, so it should come back before it
        assertThat(secondIndex).isLessThan(firstIndex);
    }

    // New scope item 3 (BPMN Processing), proven through the real saved-model path rather than
    // against DelegateClassGenerator in isolation (that's covered separately in
    // com.metaml.workbench.codegen.DelegateClassGeneratorTest). Neither example model in this repo
    // exercises this - both use user tasks with a taskListener delegateExpression
    // (agentExecutionDelegate) for agent execution, not a service task with a direct
    // delegateExpression the way Joanna's own example does - so this needs its own small fixture.
    @Test
    void generateDelegatesReadsTheRealSavedModelNotJustARawXmlString() {
        ProcessModel model = workbenchService.saveProcessModel(null, "delegate generation test",
                loanApprovalBpmn());

        List<com.metaml.workbench.codegen.GeneratedDelegate> generated =
                workbenchService.generateDelegates(model.getId());

        assertThat(generated).hasSize(1);
        assertThat(generated.get(0).beanName()).isEqualTo("calculateInterestService");
        assertThat(generated.get(0).className()).isEqualTo("CalculateInterestService");
    }

    // New scope item 4 (Spring Boot Generation), proven through the real saved-model path against
    // the real template on disk - not the synthetic fixture SpringBootProjectGeneratorTest uses -
    // so this is the one place that would have caught the delegate package/directory mismatch bug
    // (see DelegateClassGenerator.DEFAULT_PACKAGE's own comment) if the earlier fix hadn't already
    // been proven by a real mvn compile of a generated project.
    @Test
    void generateSpringBootProjectProducesADelegateWhosePackageMatchesWhereItsActuallyWritten() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "project generation test",
                loanApprovalBpmn());

        com.metaml.workbench.generation.GeneratedProject project =
                workbenchService.generateSpringBootProject(model.getId());

        assertThat(project.processKey()).isEqualTo("loanApproval");
        // Generated projects are now packaged per project (com.metaml.targetplatform.<processKey
        // slug>) and split into manufacturing/twin sides, so the delegate no longer lands under the
        // template's own com.example.camundademo. The invariant this test exists for is unchanged:
        // the package statement inside the file must match the directory it was written to.
        java.nio.file.Path delegateFile = project.directory().resolve(
                "src/main/java/com/metaml/targetplatform/loanapproval/delegate/manufacturing/"
                        + "CalculateInterestService.java");
        assertThat(delegateFile).exists();
        assertThat(java.nio.file.Files.readString(delegateFile))
                .contains("package com.metaml.targetplatform.loanapproval.delegate.manufacturing;");
        assertThat(project.directory().resolve("src/main/resources/processes/loanApproval.bpmn")).exists();
    }

    // Proves the breadcrumb is real, not a UI-side guess - every stage recorded through the actual
    // service methods, not against WorkflowStateTracker in isolation (that's covered separately in
    // com.metaml.workbench.workflow.WorkflowStateTrackerTest). Each stage's real detail (the actual
    // generated project id, the actual launched port) has to show up, not just a bare COMPLETED,
    // since a caller reading this back needs those to do anything useful with it.
    @Test
    void theWorkflowBreadcrumbReflectsWhatActuallyHappenedAtEveryRealStage() {
        ProcessModel model = workbenchService.saveProcessModel(null, "breadcrumb test", loanApprovalBpmn());

        com.metaml.workbench.workflow.WorkflowState afterSave = workbenchService.getWorkflowState(model.getId());
        assertThat(afterSave.currentStage()).isEqualTo(com.metaml.workbench.workflow.WorkflowStage.GENERATE);
        assertThat(afterSave.stages().get(com.metaml.workbench.workflow.WorkflowStage.MODEL).status())
                .isEqualTo(com.metaml.workbench.workflow.StageStatus.COMPLETED);

        com.metaml.workbench.generation.GeneratedProject project =
                workbenchService.generateSpringBootProject(model.getId());

        com.metaml.workbench.workflow.WorkflowState afterGenerate = workbenchService.getWorkflowState(model.getId());
        assertThat(afterGenerate.currentStage()).isEqualTo(com.metaml.workbench.workflow.WorkflowStage.LAUNCH);
        assertThat(afterGenerate.stages().get(com.metaml.workbench.workflow.WorkflowStage.GENERATE).detail())
                .isEqualTo(project.projectId());

        com.metaml.workbench.generation.LaunchedProject launched =
                workbenchService.launchGeneratedProject(project.projectId());

        com.metaml.workbench.workflow.WorkflowState afterLaunch = workbenchService.getWorkflowState(model.getId());
        assertThat(afterLaunch.currentStage()).isEqualTo(com.metaml.workbench.workflow.WorkflowStage.LAUNCH);
        assertThat(afterLaunch.stages().get(com.metaml.workbench.workflow.WorkflowStage.LAUNCH).status())
                .isEqualTo(com.metaml.workbench.workflow.StageStatus.COMPLETED);
        assertThat(afterLaunch.stages().get(com.metaml.workbench.workflow.WorkflowStage.LAUNCH).detail())
                .contains(String.valueOf(launched.port()));
        // the full history is the actual point of an event log over a snapshot - every real
        // transition should still be there, not just the latest one per stage: MODEL/IN_PROGRESS,
        // MODEL/COMPLETED, GENERATE/IN_PROGRESS, GENERATE/COMPLETED, LAUNCH/IN_PROGRESS,
        // LAUNCH/COMPLETED
        assertThat(afterLaunch.history()).hasSize(6);

        workbenchService.stopGeneratedProject(project.projectId());

        com.metaml.workbench.workflow.WorkflowState afterStop = workbenchService.getWorkflowState(model.getId());
        assertThat(afterStop.stages().get(com.metaml.workbench.workflow.WorkflowStage.LAUNCH).status())
                .isEqualTo(com.metaml.workbench.workflow.StageStatus.STOPPED);
    }

    // New scope item 5 (Evolve Workflow): "connect to an existing deployed application" needs to
    // point back at the model that produced it, which means launchGeneratedProject and
    // listRunningProjects both have to carry the real modelId, not just projectId/port -
    // SpringBootProjectLauncher itself has no notion of a model, so this is specifically proving
    // WorkbenchServiceImpl's own enrichment layer on top of it.
    @Test
    void runningProjectsCarryTheRealModelIdTheyWereGeneratedFrom() {
        ProcessModel model = workbenchService.saveProcessModel(null, "evolve workflow test", loanApprovalBpmn());
        com.metaml.workbench.generation.GeneratedProject project =
                workbenchService.generateSpringBootProject(model.getId());

        com.metaml.workbench.generation.LaunchedProject launched =
                workbenchService.launchGeneratedProject(project.projectId());
        assertThat(launched.modelId()).isEqualTo(model.getId());

        List<com.metaml.workbench.generation.LaunchedProject> running = workbenchService.listRunningProjects();
        assertThat(running).filteredOn(p -> p.projectId().equals(project.projectId()))
                .extracting(com.metaml.workbench.generation.LaunchedProject::modelId)
                .containsExactly(model.getId());

        workbenchService.stopGeneratedProject(project.projectId());
    }

    // The actual new capability: workflow history is now genuinely persisted (WorkflowEventStore),
    // not just backfilled for MODEL. Simulates a real restart end to end - saves, generates, and
    // launches a project through one service instance sharing REAL (non-mocked, non-disabled)
    // WorkbenchStateStore and WorkflowEventStore instances against real temp files, then constructs
    // a second WorkbenchServiceImpl against those same files with fresh in-memory maps - exactly
    // what a real process restart produces - and confirms every stage's real status, timestamp,
    // and detail survives, not just MODEL.
    @Test
    void workflowHistorySurvivesARealBackendRestartForEveryStageNotJustModel(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        com.metaml.workbench.store.WorkbenchStateStore realStateStore = new com.metaml.workbench.store.WorkbenchStateStore(
                tempDir.resolve("workbench-state.json").toString(), true);
        com.metaml.workbench.workflow.WorkflowEventStore realEventStore =
                new com.metaml.workbench.workflow.WorkflowEventStore(
                        tempDir.resolve("workflow-events.json").toString(), true);

        WorkbenchServiceImpl beforeRestart = new WorkbenchServiceImpl(nodeManagerClient, governanceService, policyDecisionEngine, approvalService,
                runtimeService, repositoryService, historyService, taskService, externalTaskService, twinModelGenerator, realStateStore,
                modelFileStore, processModelArchiveStore, delegateClassGenerator, springBootProjectGenerator, springBootProjectLauncher,
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore));
        ProcessModel model = beforeRestart.saveProcessModel(null, "restart persistence test", loanApprovalBpmn());
        com.metaml.workbench.generation.GeneratedProject project =
                beforeRestart.generateSpringBootProject(model.getId());
        com.metaml.workbench.generation.LaunchedProject launched =
                beforeRestart.launchGeneratedProject(project.projectId());
        beforeRestart.stopGeneratedProject(project.projectId());

        // a fresh WorkbenchServiceImpl with fresh in-memory maps, but pointed at the SAME real
        // files on disk - this is what a real process restart produces, nothing carried over in
        // memory. WorkflowStateTracker's own @PostConstruct restore() has to be driven by hand too
        // - Spring calls it automatically for a real bean, but constructing this one directly via
        // `new` (the only way to get a SECOND, independent instance sharing the same files) bypasses
        // Spring's lifecycle entirely, same reason WorkbenchServiceImpl's restoreState() needs the
        // same reflection treatment just below.
        com.metaml.workbench.workflow.WorkflowStateTracker restartedTracker =
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore);
        invokePostConstructOn(restartedTracker, "restore");

        WorkbenchServiceImpl restartedService = new WorkbenchServiceImpl(nodeManagerClient, governanceService, policyDecisionEngine, approvalService,
                runtimeService, repositoryService, historyService, taskService, externalTaskService, twinModelGenerator, realStateStore,
                modelFileStore, processModelArchiveStore, delegateClassGenerator, springBootProjectGenerator, springBootProjectLauncher,
                restartedTracker);
        invokePostConstruct(restartedService, "restoreState");

        com.metaml.workbench.workflow.WorkflowState state = restartedService.getWorkflowState(model.getId());
        assertThat(state.stages().get(com.metaml.workbench.workflow.WorkflowStage.MODEL).status())
                .isEqualTo(com.metaml.workbench.workflow.StageStatus.COMPLETED);
        assertThat(state.stages().get(com.metaml.workbench.workflow.WorkflowStage.GENERATE).status())
                .isEqualTo(com.metaml.workbench.workflow.StageStatus.COMPLETED);
        assertThat(state.stages().get(com.metaml.workbench.workflow.WorkflowStage.GENERATE).detail())
                .isEqualTo(project.projectId());
        assertThat(state.stages().get(com.metaml.workbench.workflow.WorkflowStage.LAUNCH).status())
                .isEqualTo(com.metaml.workbench.workflow.StageStatus.STOPPED);
        assertThat(state.stages().get(com.metaml.workbench.workflow.WorkflowStage.LAUNCH).detail())
                .contains(String.valueOf(launched.port()));
        // the full real sequence, not just the latest snapshot per stage: MODEL/IN_PROGRESS,
        // MODEL/COMPLETED, GENERATE/IN_PROGRESS, GENERATE/COMPLETED, LAUNCH/IN_PROGRESS,
        // LAUNCH/COMPLETED, LAUNCH/STOPPED
        assertThat(state.history()).hasSize(7);
    }

    // Generated-project persistence: proves the actual capability, not just that a map got
    // repopulated. generateSpringBootProject + launchGeneratedProject through ONE service
    // instance, then a second instance built the same way the restart test above builds one
    // (fresh in-memory maps, real WorkbenchStateStore/WorkflowEventStore backed by the same temp
    // files) - but reusing the real, Spring-injected springBootProjectGenerator/
    // springBootProjectLauncher beans, since those two already point at the real, shared
    // output/template directories a genuine restart would leave untouched (they hold no
    // in-memory generated-project state of their own - see SpringBootProjectGenerator.scanExisting()).
    // The actual proof is launchGeneratedProject succeeding for real against the SAME projectId
    // through the restarted instance, not merely that generatedProjects.containsKey() would say yes.
    @Test
    void aGeneratedProjectSurvivesARealBackendRestartAndCanActuallyBeLaunchedAgain(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        com.metaml.workbench.store.WorkbenchStateStore realStateStore = new com.metaml.workbench.store.WorkbenchStateStore(
                tempDir.resolve("workbench-state.json").toString(), true);
        com.metaml.workbench.workflow.WorkflowEventStore realEventStore =
                new com.metaml.workbench.workflow.WorkflowEventStore(
                        tempDir.resolve("workflow-events.json").toString(), true);

        WorkbenchServiceImpl beforeRestart = new WorkbenchServiceImpl(nodeManagerClient, governanceService, policyDecisionEngine, approvalService,
                runtimeService, repositoryService, historyService, taskService, externalTaskService, twinModelGenerator, realStateStore,
                modelFileStore, processModelArchiveStore, delegateClassGenerator, springBootProjectGenerator, springBootProjectLauncher,
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore));
        ProcessModel model = beforeRestart.saveProcessModel(null, "generated project restart test", loanApprovalBpmn());
        com.metaml.workbench.generation.GeneratedProject project =
                beforeRestart.generateSpringBootProject(model.getId());

        // the physical artifact restoreGeneratedProjects() is supposed to find again
        assertThat(project.directory().resolve("src/main/resources/processes/loanApproval.bpmn")).exists();

        com.metaml.workbench.workflow.WorkflowStateTracker restartedTracker =
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore);
        invokePostConstructOn(restartedTracker, "restore");

        WorkbenchServiceImpl restartedService = new WorkbenchServiceImpl(nodeManagerClient, governanceService, policyDecisionEngine, approvalService,
                runtimeService, repositoryService, historyService, taskService, externalTaskService, twinModelGenerator, realStateStore,
                modelFileStore, processModelArchiveStore, delegateClassGenerator, springBootProjectGenerator, springBootProjectLauncher,
                restartedTracker);
        invokePostConstruct(restartedService, "restoreState");

        // Test 1: the registry itself resolves the same id to the same physical project, through
        // an instance that never called generateSpringBootProject
        assertThat(restartedService.getWorkflowState(model.getId())
                .stages().get(com.metaml.workbench.workflow.WorkflowStage.GENERATE).detail())
                .isEqualTo(project.projectId());

        // Test 2: the actual capability - launchGeneratedProject against the SAME projectId,
        // through the RESTARTED instance, genuinely starts the SAME generated application
        try {
            com.metaml.workbench.generation.LaunchedProject launched =
                    restartedService.launchGeneratedProject(project.projectId());

            assertThat(launched.projectId()).isEqualTo(project.projectId());
            assertThat(launched.processKey()).isEqualTo(project.processKey());
            assertThat(launched.port()).isPositive();
            // modelIdByProjectId reconstruction, not just generatedProjects - the breadcrumb this
            // launch records has to land on the SAME model the original generate() came from
            assertThat(launched.modelId()).isEqualTo(model.getId());

            com.metaml.workbench.workflow.WorkflowState state = restartedService.getWorkflowState(model.getId());
            assertThat(state.stages().get(com.metaml.workbench.workflow.WorkflowStage.LAUNCH).status())
                    .isEqualTo(com.metaml.workbench.workflow.StageStatus.COMPLETED);
        } finally {
            restartedService.stopGeneratedProject(project.projectId());
        }
    }

    // Missing artifact (Test 4): a project directory the registry would otherwise have resolved
    // is gone entirely - proves the restarted registry does not silently fall back to some other
    // project for the same id, it just doesn't have it, the same clear failure as an id that was
    // never real.
    @Test
    void aGeneratedProjectWhoseDirectoryIsGoneIsNotRecoveredAfterRestart(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        com.metaml.workbench.store.WorkbenchStateStore realStateStore = new com.metaml.workbench.store.WorkbenchStateStore(
                tempDir.resolve("workbench-state.json").toString(), true);
        com.metaml.workbench.workflow.WorkflowEventStore realEventStore =
                new com.metaml.workbench.workflow.WorkflowEventStore(
                        tempDir.resolve("workflow-events.json").toString(), true);

        WorkbenchServiceImpl beforeRestart = new WorkbenchServiceImpl(nodeManagerClient, governanceService, policyDecisionEngine, approvalService,
                runtimeService, repositoryService, historyService, taskService, externalTaskService, twinModelGenerator, realStateStore,
                modelFileStore, processModelArchiveStore, delegateClassGenerator, springBootProjectGenerator, springBootProjectLauncher,
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore));
        ProcessModel model = beforeRestart.saveProcessModel(null, "missing artifact test", loanApprovalBpmn());
        com.metaml.workbench.generation.GeneratedProject project =
                beforeRestart.generateSpringBootProject(model.getId());

        // simulates the directory having been cleaned up (disk cleanup, manual deletion, ...)
        // between the original generate and the restart - not simulated by mocking, the actual
        // directory recursively removed
        deleteRecursively(project.directory());

        com.metaml.workbench.workflow.WorkflowStateTracker restartedTracker =
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore);
        invokePostConstructOn(restartedTracker, "restore");

        WorkbenchServiceImpl restartedService = new WorkbenchServiceImpl(nodeManagerClient, governanceService, policyDecisionEngine, approvalService,
                runtimeService, repositoryService, historyService, taskService, externalTaskService, twinModelGenerator, realStateStore,
                modelFileStore, processModelArchiveStore, delegateClassGenerator, springBootProjectGenerator, springBootProjectLauncher,
                restartedTracker);
        invokePostConstruct(restartedService, "restoreState");

        assertThatThrownBy(() -> restartedService.launchGeneratedProject(project.projectId()))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining(project.projectId());
    }

    private static void deleteRecursively(java.nio.file.Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    // The backfill mechanism's real remaining job now that real persistence exists: a model whose
    // workflow history genuinely predates it (never recorded to WorkflowEventStore - simulated here
    // by writing the model straight into the H2 archive, bypassing saveProcessModel entirely, the
    // same way a model saved by an old build actually would have been). Confirms restoreState()
    // still recognizes "no persisted workflow history at all for this model" and backfills MODEL
    // rather than leaving it stuck PENDING - but does NOT redo the backfill for a model that
    // already has real history, which the test above already proves implicitly (its GENERATE/LAUNCH
    // details would have been wiped by a redundant backfill if it did).
    //
    // Seeded through processModelArchiveStore, the model's single authoritative persistence. This
    // used to seed through WorkbenchStateStore's JSON snapshot instead, back when that snapshot
    // carried a redundant second copy of every model and restoreState() consulted it as a fallback;
    // both were removed as implementation baggage. The behaviour under test - the MODEL-stage
    // backfill - is unchanged, and is what these assertions still check.
    @Test
    void aModelWithNoPersistedWorkflowHistoryAtAllStillGetsItsModelStageBackfilled(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        com.metaml.workbench.store.WorkbenchStateStore realStateStore = new com.metaml.workbench.store.WorkbenchStateStore(
                tempDir.resolve("workbench-state.json").toString(), true);
        java.time.Instant legacyCreatedAt = java.time.Instant.now().minusSeconds(3600);
        ProcessModel legacyModel = new ProcessModel("legacy-model-1", "pre-tracking model", loanApprovalBpmn(),
                legacyCreatedAt, "some-definition-id");
        java.nio.file.Path legacyBpmnPath = modelFileStore.save(legacyModel.getId(), legacyModel.getBpmnXml());
        processModelArchiveStore.save(legacyModel, legacyBpmnPath);

        com.metaml.workbench.workflow.WorkflowEventStore emptyEventStore =
                new com.metaml.workbench.workflow.WorkflowEventStore(
                        tempDir.resolve("workflow-events-never-written.json").toString(), true);
        com.metaml.workbench.workflow.WorkflowStateTracker restartedTracker =
                new com.metaml.workbench.workflow.WorkflowStateTracker(emptyEventStore);
        invokePostConstructOn(restartedTracker, "restore");

        WorkbenchServiceImpl restartedService = new WorkbenchServiceImpl(nodeManagerClient, governanceService, policyDecisionEngine, approvalService,
                runtimeService, repositoryService, historyService, taskService, externalTaskService, twinModelGenerator, realStateStore,
                modelFileStore, processModelArchiveStore, delegateClassGenerator, springBootProjectGenerator, springBootProjectLauncher,
                restartedTracker);
        invokePostConstruct(restartedService, "restoreState");

        com.metaml.workbench.workflow.WorkflowState state = restartedService.getWorkflowState(legacyModel.getId());
        assertThat(state.stages().get(com.metaml.workbench.workflow.WorkflowStage.MODEL).status())
                .isEqualTo(com.metaml.workbench.workflow.StageStatus.COMPLETED);
        // the RESTORED model's own createdAt, not the original in-memory legacyCreatedAt -
        // persistence truncates Instant precision, so comparing against the pre-persistence
        // nanosecond value would fail for a reason that has nothing to do with the backfill itself
        ProcessModel restoredModel = restartedService.getProcessModel(legacyModel.getId());
        assertThat(state.stages().get(com.metaml.workbench.workflow.WorkflowStage.MODEL).timestamp())
                .isEqualTo(restoredModel.getCreatedAt());
        assertThat(state.currentStage()).isEqualTo(com.metaml.workbench.workflow.WorkflowStage.GENERATE);
    }

    private static void invokePostConstruct(WorkbenchServiceImpl service, String methodName) throws Exception {
        invokePostConstructOn(service, methodName);
    }

    private static void invokePostConstructOn(Object target, String methodName) throws Exception {
        java.lang.reflect.Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    // Same reflection-construction pattern bridgeDedupeIsSafeAcrossACompletelyFreshServiceInstance
    // already uses above, here to get a SpringBootProjectGenerator pointed at a template directory
    // that doesn't exist - the one way to make generateSpringBootProject genuinely fail through
    // the real service rather than short-circuit before recording anything (a bad modelId, for
    // instance, throws before the pipeline even starts, so it never touches the breadcrumb at all).
    @Test
    void aRealGenerateFailureIsRecordedAsFailedWithTheRealErrorNotSilentlySwallowed() throws Exception {
        var brokenGenerator = new com.metaml.workbench.generation.SpringBootProjectGenerator(
                "./no-such-template-directory-anywhere", "target/test-data/generated-projects",
                twinModelGenerator, delegateClassGenerator,
                new com.metaml.workbench.codegen.ExternalTaskWorkerGenerator());
        WorkbenchServiceImpl serviceWithBrokenTemplate = new WorkbenchServiceImpl(nodeManagerClient,
                governanceService, policyDecisionEngine, approvalService, runtimeService, repositoryService,
                historyService, taskService, externalTaskService, twinModelGenerator, stateStore, modelFileStore, processModelArchiveStore, delegateClassGenerator,
                brokenGenerator, springBootProjectLauncher, workflowStateTracker);

        ProcessModel model = serviceWithBrokenTemplate.saveProcessModel(null, "failure test", loanApprovalBpmn());

        assertThatThrownBy(() -> serviceWithBrokenTemplate.generateSpringBootProject(model.getId()))
                .isInstanceOf(IllegalStateException.class);

        com.metaml.workbench.workflow.WorkflowState state =
                serviceWithBrokenTemplate.getWorkflowState(model.getId());
        assertThat(state.currentStage()).isEqualTo(com.metaml.workbench.workflow.WorkflowStage.GENERATE);
        assertThat(state.stages().get(com.metaml.workbench.workflow.WorkflowStage.GENERATE).status())
                .isEqualTo(com.metaml.workbench.workflow.StageStatus.FAILED);
        assertThat(state.stages().get(com.metaml.workbench.workflow.WorkflowStage.GENERATE).detail())
                .contains("no-such-template-directory-anywhere");
    }

    private static String loanApprovalBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_LoanApproval" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="loanApproval" name="Loan Approval" isExecutable="true">
                    <bpmn2:startEvent id="StartEvent_1" name="Loan Request Received">
                      <bpmn2:outgoing>SequenceFlow_1</bpmn2:outgoing>
                    </bpmn2:startEvent>
                    <bpmn2:serviceTask id="ServiceTask_1" name="Calculate Interest"
                        camunda:delegateExpression="${calculateInterestService}">
                      <bpmn2:incoming>SequenceFlow_1</bpmn2:incoming>
                      <bpmn2:outgoing>SequenceFlow_2</bpmn2:outgoing>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="SequenceFlow_1" sourceRef="StartEvent_1" targetRef="ServiceTask_1" />
                    <bpmn2:endEvent id="EndEvent_1" name="Loan Approved">
                      <bpmn2:incoming>SequenceFlow_2</bpmn2:incoming>
                    </bpmn2:endEvent>
                    <bpmn2:sequenceFlow id="SequenceFlow_2" sourceRef="ServiceTask_1" targetRef="EndEvent_1" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    @Test
    void walksTheWireTransferFromKycToTheEnd() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer", citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        assertThat(twin.getOriginalProcessId()).isNotBlank();
        assertThat(twin.getTwinProcessId()).isNotBlank().isNotEqualTo(twin.getOriginalProcessId());
        assertThat(twin.getStatus()).isEqualTo("RUNNING");
        assertThat(openActivities(twin)).containsExactly(KYC);

        // KYC is the one you bridge by hand. Its start event fires inside startProcessInstanceById,
        // before launchProcess has put the twin in the map, so the trigger has nothing to look up.
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        AgentDecision kyc = workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        assertThat(kyc.isApproved()).isTrue();
        assertThat(kyc.getAgentName()).isEqualTo(BRIDGE_AGENT);
        assertThat(evolvedAgent(twin, KYC)).isEqualTo(BRIDGE_AGENT);

        // connect before completing. the start event for an activity fires once and doesn't come
        // back, so anything connected afterwards needs the manual bridge button instead.
        connect(twin, AML, OFAC, CREDIT);
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        // the "complete" task listener on Task_KYC fires here, on the real (original) instance
        // finishing the task - proves the agent execution delegate actually ran, not just deployed
        assertThat(agentExecuted(twin, KYC)).isEqualTo(BRIDGE_AGENT);
        // and it's in the event log, so the UI shows it like every other operation
        assertThat(twin.getEventLog()).anyMatch(entry -> entry.contains("agentExecuted_" + KYC));

        // genuine parallel split - three tasks open at the same time, not one after another
        assertThat(openActivities(twin)).containsExactlyInAnyOrder(AML, OFAC, CREDIT);

        // nobody called bridge for any of these three. this is the whole point of the trigger.
        assertThat(evolvedAgent(twin, AML)).isEqualTo(BRIDGE_AGENT);
        assertThat(evolvedAgent(twin, OFAC)).isEqualTo(BRIDGE_AGENT);
        assertThat(evolvedAgent(twin, CREDIT)).isEqualTo(BRIDGE_AGENT);

        connect(twin, APPROVE, EXECUTE, NOTIFY);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);
        assertThat(openActivities(twin)).containsExactly(APPROVE);
        assertThat(evolvedAgent(twin, APPROVE)).isEqualTo(BRIDGE_AGENT);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).containsExactly(EXECUTE);
        assertThat(evolvedAgent(twin, EXECUTE)).isEqualTo(BRIDGE_AGENT);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).containsExactly(NOTIFY);
        assertThat(evolvedAgent(twin, NOTIFY)).isEqualTo(BRIDGE_AGENT);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).isEmpty();
        assertThat(reached(twin, "EndEvent_Success")).isTrue();
        // never went down either rejection branch or the approval timeout
        assertThat(reached(twin, "EndEvent_RejectedIdentity")).isFalse();
        assertThat(reached(twin, "EndEvent_RejectedCompliance")).isFalse();
        assertThat(reached(twin, "Task_EscalateTimeout")).isFalse();

        // This used to assert TWIN_RUNNING_ORIGINAL_ENDED, back when the twin was a second copy of
        // the human process and sat on its own KYC task forever. Launch gives the twin a generated
        // definition of its own now, so it walked the same route and finished a step ahead of the
        // original - the twin does an activity when the original reaches it, not when it leaves it.
        assertThat(workbenchService.getTwinProcess(twin.getId()).getStatus()).isEqualTo("ENDED");
        assertThat(twinReached(twin, KYC)).isTrue();
        assertThat(twinReached(twin, "Gateway_ParallelJoin")).isTrue();
        assertThat(twinReached(twin, "EndEvent_Success")).isTrue();
        assertThat(twinAutomation(twin, NOTIFY)).isNotNull();

        // seven activities, seven slots. a double-count would show up here before anywhere else.
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(7);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).isEmpty();
    }

    // A model saved with a tenantId is the owned resource. A twin launched from it never picks its
    // own tenant, it inherits the model's - exercised through the real save -> launch path rather
    // than by setting the field directly.
    @Test
    void savingAModelWithATenantIdCarriesItThroughToTheLaunchedTwin() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer owned",
                citibankBpmn(), "tenant-citibank");
        assertThat(model.getTenantId()).isEqualTo("tenant-citibank");

        TwinProcess twin = workbenchService.launchProcess(model.getId());

        assertThat(twin.getTenantId()).isEqualTo("tenant-citibank");
        // this is the same twin object runEvolution() itself receives - proves the tenant is
        // actually obtainable at the real Evolve entry point, not just on the model
        assertThat(workbenchService.getTwinProcess(twin.getId()).getTenantId()).isEqualTo("tenant-citibank");
    }

    // the existing 3-arg saveProcessModel (every pre-tenancy caller, including every other test in
    // this file) must keep producing exactly what it always did - an unowned twin, not an invented
    // "default" tenant standing in for a real one
    @Test
    void legacyModelsWithNoTenantIdProduceUnownedTwinsNotAnInventedDefault() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer legacy",
                citibankBpmn());
        assertThat(model.getTenantId()).isNull();

        TwinProcess twin = workbenchService.launchProcess(model.getId());

        assertThat(twin.getTenantId()).isNull();
    }

    // same real-restart convention as workflowHistorySurvivesARealBackendRestartForEveryStageNotJustModel
    // just below - a fresh WorkbenchServiceImpl, same files on disk, restore() driven by hand the
    // way Spring would drive it on a real process restart
    @Test
    void tenantOwnershipSurvivesARealBackendRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws Exception {
        com.metaml.workbench.store.WorkbenchStateStore realStateStore = new com.metaml.workbench.store.WorkbenchStateStore(
                tempDir.resolve("workbench-state.json").toString(), true);
        com.metaml.workbench.workflow.WorkflowEventStore realEventStore =
                new com.metaml.workbench.workflow.WorkflowEventStore(
                        tempDir.resolve("workflow-events.json").toString(), true);

        WorkbenchServiceImpl beforeRestart = new WorkbenchServiceImpl(nodeManagerClient, governanceService, policyDecisionEngine, approvalService,
                runtimeService, repositoryService, historyService, taskService, externalTaskService, twinModelGenerator, realStateStore,
                modelFileStore, processModelArchiveStore, delegateClassGenerator, springBootProjectGenerator, springBootProjectLauncher,
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore));
        ProcessModel model = beforeRestart.saveProcessModel(null, "tenant restart test", citibankBpmn(),
                "tenant-redcollar");
        TwinProcess twin = beforeRestart.launchProcess(model.getId());
        assertThat(twin.getTenantId()).isEqualTo("tenant-redcollar");

        com.metaml.workbench.workflow.WorkflowStateTracker restartedTracker =
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore);
        invokePostConstructOn(restartedTracker, "restore");

        WorkbenchServiceImpl restartedService = new WorkbenchServiceImpl(nodeManagerClient, governanceService, policyDecisionEngine, approvalService,
                runtimeService, repositoryService, historyService, taskService, externalTaskService, twinModelGenerator, realStateStore,
                modelFileStore, processModelArchiveStore, delegateClassGenerator, springBootProjectGenerator, springBootProjectLauncher,
                restartedTracker);
        invokePostConstruct(restartedService, "restoreState");

        assertThat(restartedService.getProcessModel(model.getId()).getTenantId()).isEqualTo("tenant-redcollar");
        assertThat(restartedService.getTwinProcess(twin.getId()).getTenantId()).isEqualTo("tenant-redcollar");
    }

    // A PENDING approval has to survive the same real restart everything
    // else here does. Tenant/policy state comes from the shared autowired beans (that continuity
    // is covered elsewhere) - only WorkbenchStateStore and ApprovalStore are
    // freshly file-backed and genuinely restarted, because those are what this test is actually
    // about.
    @Test
    void pendingApprovalSurvivesARealBackendRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws Exception {
        Tenant tenant = tenantWithEvolveTwinRule("Restart Approval Tenant", PolicyEffect.REQUIRE_APPROVAL);

        com.metaml.workbench.store.WorkbenchStateStore realStateStore = new com.metaml.workbench.store.WorkbenchStateStore(
                tempDir.resolve("workbench-state.json").toString(), true);
        com.metaml.workbench.workflow.WorkflowEventStore realEventStore =
                new com.metaml.workbench.workflow.WorkflowEventStore(
                        tempDir.resolve("workflow-events.json").toString(), true);
        com.metaml.workbench.governance.ApprovalStore realApprovalStore =
                new com.metaml.workbench.governance.ApprovalStore(tempDir.resolve("approvals.json").toString(), true);
        ApprovalService realApprovalService = new ApprovalService(realApprovalStore);

        WorkbenchServiceImpl beforeRestart = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                policyDecisionEngine, realApprovalService, runtimeService, repositoryService, historyService,
                taskService, externalTaskService, twinModelGenerator, realStateStore, modelFileStore, processModelArchiveStore, delegateClassGenerator,
                springBootProjectGenerator, springBootProjectLauncher,
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore));
        ProcessModel model = beforeRestart.saveProcessModel(null, "restart approval test", citibankBpmn(),
                tenant.id());
        TwinProcess twin = beforeRestart.launchProcess(model.getId());
        beforeRestart.connectActivity(twin.getId(), KYC, KYC);
        beforeRestart.evolveActivity(twin.getId(), KYC, "validator");
        String approvalId = beforeRestart.listApprovals(tenant.id()).get(0).id();

        com.metaml.workbench.workflow.WorkflowStateTracker restartedTracker =
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore);
        invokePostConstructOn(restartedTracker, "restore");
        ApprovalService restartedApprovalService = new ApprovalService(realApprovalStore);
        invokePostConstructOn(restartedApprovalService, "restore");

        WorkbenchServiceImpl restartedService = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                policyDecisionEngine, restartedApprovalService, runtimeService, repositoryService, historyService,
                taskService, externalTaskService, twinModelGenerator, realStateStore, modelFileStore, processModelArchiveStore, delegateClassGenerator,
                springBootProjectGenerator, springBootProjectLauncher, restartedTracker);
        invokePostConstruct(restartedService, "restoreState");

        List<Approval> restored = restartedService.listApprovals(tenant.id());
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).id()).isEqualTo(approvalId);
        assertThat(restored.get(0).status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(restored.get(0).twinId()).isEqualTo(twin.getId());
    }

    // JVM died between markApproved and executeAfterGovernance ever running -
    // the operation genuinely never happened. Reconciliation on restart must run it for real, not
    // pretend it already occurred.
    @Test
    void anApprovalThatNeverExecutedIsSafelyRunOnRestartReconciliation(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        Tenant tenant = tenantWithEvolveTwinRule("Reconcile Not-Run Tenant", PolicyEffect.REQUIRE_APPROVAL);

        com.metaml.workbench.store.WorkbenchStateStore realStateStore = new com.metaml.workbench.store.WorkbenchStateStore(
                tempDir.resolve("workbench-state.json").toString(), true);
        com.metaml.workbench.workflow.WorkflowEventStore realEventStore =
                new com.metaml.workbench.workflow.WorkflowEventStore(
                        tempDir.resolve("workflow-events.json").toString(), true);
        com.metaml.workbench.governance.ApprovalStore realApprovalStore =
                new com.metaml.workbench.governance.ApprovalStore(tempDir.resolve("approvals.json").toString(), true);
        ApprovalService realApprovalService = new ApprovalService(realApprovalStore);

        WorkbenchServiceImpl beforeCrash = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                policyDecisionEngine, realApprovalService, runtimeService, repositoryService, historyService,
                taskService, externalTaskService, twinModelGenerator, realStateStore, modelFileStore, processModelArchiveStore, delegateClassGenerator,
                springBootProjectGenerator, springBootProjectLauncher,
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore));
        ProcessModel model = beforeCrash.saveProcessModel(null, "reconcile not-run test", citibankBpmn(),
                tenant.id());
        TwinProcess twin = beforeCrash.launchProcess(model.getId());
        beforeCrash.connectActivity(twin.getId(), KYC, KYC);
        beforeCrash.evolveActivity(twin.getId(), KYC, "validator");
        String approvalId = beforeCrash.listApprovals(tenant.id()).get(0).id();

        // simulates the exact crash window: approve() reached "mark APPROVED" and nothing past
        // it - executeAfterGovernance never ran, no node manager call, no variable ever set.
        // Calling ApprovalService directly (not WorkbenchServiceImpl.approveEvolution) is what
        // makes that true.
        realApprovalService.markApproved(approvalId, tenant.id());
        assertThat(evolvedAgent(twin, KYC)).isNull();

        com.metaml.workbench.workflow.WorkflowStateTracker restartedTracker =
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore);
        invokePostConstructOn(restartedTracker, "restore");
        ApprovalService restartedApprovalService = new ApprovalService(realApprovalStore);
        invokePostConstructOn(restartedApprovalService, "restore");
        WorkbenchServiceImpl restartedService = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                policyDecisionEngine, restartedApprovalService, runtimeService, repositoryService, historyService,
                taskService, externalTaskService, twinModelGenerator, realStateStore, modelFileStore, processModelArchiveStore, delegateClassGenerator,
                springBootProjectGenerator, springBootProjectLauncher, restartedTracker);
        invokePostConstruct(restartedService, "restoreState");

        Approval reconciled = restartedApprovalService.get(approvalId, tenant.id());
        assertThat(reconciled.status()).isEqualTo(ApprovalStatus.COMPLETED);
        assertThat(evolvedAgent(twin, KYC)).isEqualTo("validator-agent-01");
        org.mockito.Mockito.verify(nodeManagerClient, org.mockito.Mockito.times(1)).checkAgentAvailability("validator");
    }

    // JVM died AFTER the real side effect landed but BEFORE COMPLETED was
    // persisted. Reconciliation must recognize it already happened - via Camunda's own committed
    // variable history, not the approval's own (crashed, stale) status - and must NOT run it
    // again. Node-manager call count is the proof: exactly one call total, from the original real
    // approve(), none from reconciliation.
    @Test
    void anApprovalThatAlreadyExecutedIsRecognizedNotReRunOnRestartReconciliation(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        Tenant tenant = tenantWithEvolveTwinRule("Reconcile Already-Run Tenant", PolicyEffect.REQUIRE_APPROVAL);

        com.metaml.workbench.store.WorkbenchStateStore realStateStore = new com.metaml.workbench.store.WorkbenchStateStore(
                tempDir.resolve("workbench-state.json").toString(), true);
        com.metaml.workbench.workflow.WorkflowEventStore realEventStore =
                new com.metaml.workbench.workflow.WorkflowEventStore(
                        tempDir.resolve("workflow-events.json").toString(), true);
        com.metaml.workbench.governance.ApprovalStore realApprovalStore =
                new com.metaml.workbench.governance.ApprovalStore(tempDir.resolve("approvals.json").toString(), true);
        ApprovalService realApprovalService = new ApprovalService(realApprovalStore);

        WorkbenchServiceImpl beforeCrash = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                policyDecisionEngine, realApprovalService, runtimeService, repositoryService, historyService,
                taskService, externalTaskService, twinModelGenerator, realStateStore, modelFileStore, processModelArchiveStore, delegateClassGenerator,
                springBootProjectGenerator, springBootProjectLauncher,
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore));
        ProcessModel model = beforeCrash.saveProcessModel(null, "reconcile already-run test", citibankBpmn(),
                tenant.id());
        TwinProcess twin = beforeCrash.launchProcess(model.getId());
        beforeCrash.connectActivity(twin.getId(), KYC, KYC);
        beforeCrash.evolveActivity(twin.getId(), KYC, "validator");
        String approvalId = beforeCrash.listApprovals(tenant.id()).get(0).id();

        // the real operation genuinely runs here - setVariable really happens, exactly like
        // production. This is not a simulation of execution, only of what gets persisted after.
        AgentDecision realDecision = beforeCrash.approveEvolution(approvalId, tenant.id());
        assertThat(realDecision.isApproved()).isTrue();
        assertThat(evolvedAgent(twin, KYC)).isEqualTo("validator-agent-01");

        // NOW simulate the crash: force the persisted record back to APPROVED, as if the
        // markCompleted() write never landed - the one write that COULD plausibly not survive a
        // crash occurring in that exact instant, since the real side effect (setVariable) already
        // committed to Camunda's own store by this point, independently of this file.
        Approval completed = realApprovalService.get(approvalId, tenant.id());
        Approval revertedToApproved = completed.withStatus(ApprovalStatus.APPROVED, completed.resolvedAt(), null);
        realApprovalStore.save(List.of(revertedToApproved));

        com.metaml.workbench.workflow.WorkflowStateTracker restartedTracker =
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore);
        invokePostConstructOn(restartedTracker, "restore");
        ApprovalService restartedApprovalService = new ApprovalService(realApprovalStore);
        invokePostConstructOn(restartedApprovalService, "restore");
        assertThat(restartedApprovalService.get(approvalId, tenant.id()).status())
                .isEqualTo(ApprovalStatus.APPROVED); // confirms the simulated crash state really took

        WorkbenchServiceImpl restartedService = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                policyDecisionEngine, restartedApprovalService, runtimeService, repositoryService, historyService,
                taskService, externalTaskService, twinModelGenerator, realStateStore, modelFileStore, processModelArchiveStore, delegateClassGenerator,
                springBootProjectGenerator, springBootProjectLauncher, restartedTracker);
        invokePostConstruct(restartedService, "restoreState");

        Approval reconciled = restartedApprovalService.get(approvalId, tenant.id());
        assertThat(reconciled.status()).isEqualTo(ApprovalStatus.COMPLETED);
        // the real proof: still exactly one call, from the original approveEvolution() above -
        // reconciliation recognized the variable was already set and did not call it again
        org.mockito.Mockito.verify(nodeManagerClient, org.mockito.Mockito.times(1)).checkAgentAvailability("validator");
    }

    // A small helper so the four tests below don't each
    // repeat tenant/policy/version/rule/activate by hand. Returns the real Tenant, not just its
    // id, since a couple of callers want the name too.
    private Tenant tenantWithEvolveTwinRule(String tenantName, PolicyEffect effect) {
        Tenant tenant = tenantPolicyService.createTenant(tenantName);
        Policy policy = tenantPolicyService.createTenantPolicy(tenant.id(), "Evolve Policy");
        PolicyVersion draft = tenantPolicyService.createDraftVersion(policy.id(), tenant.id());
        tenantPolicyService.addRule(draft.id(), tenant.id(), "action", "==", "EVOLVE_TWIN", effect);
        tenantPolicyService.activateVersion(draft.id(), tenant.id());
        return tenant;
    }

    // Section 5's acceptance criterion: DENY must actually stop the real side effect, not just
    // come back with a denied-looking response. evolvedAgent_<activityId> is that side effect
    // (see runEvolution's own comment) - proven absent, not just the JSON checked.
    @Test
    void tenantPolicyDenyActuallyBlocksTheRealEvolveSideEffect() throws IOException {
        Tenant tenant = tenantWithEvolveTwinRule("Deny Tenant", PolicyEffect.DENY);
        ProcessModel model = workbenchService.saveProcessModel(null, "deny enforcement test", citibankBpmn(),
                tenant.id());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        AgentDecision decision = workbenchService.evolveActivity(twin.getId(), KYC, "validator");

        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.getGovernanceDecision()).isEqualTo("DENY");
        // stopped before the node manager, not just before the variable write
        org.mockito.Mockito.verify(nodeManagerClient, org.mockito.Mockito.never()).checkAgentAvailability(anyString());
        assertThat(evolvedAgent(twin, KYC)).isNull();
    }

    // Section 6: ALLOW must not merely say yes, the existing Evolve behavior has to actually run -
    // same real path, same real side effect, nothing test-only about how it gets there.
    @Test
    void tenantPolicyAllowLetsTheRealEvolveSideEffectHappen() throws IOException {
        Tenant tenant = tenantWithEvolveTwinRule("Allow Tenant", PolicyEffect.ALLOW);
        ProcessModel model = workbenchService.saveProcessModel(null, "allow enforcement test", citibankBpmn(),
                tenant.id());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        AgentDecision decision = workbenchService.evolveActivity(twin.getId(), KYC, "validator");

        assertThat(decision.isApproved()).isTrue();
        assertThat(decision.getGovernanceDecision()).isNull();
        assertThat(evolvedAgent(twin, KYC)).isEqualTo("validator-agent-01");
    }

    // Section 7: the difference has to come entirely from which tenant owns the twin - no
    // tenant-name conditional anywhere in the production code, same engine, same rule shape,
    // opposite persisted effect
    @Test
    void tenantADenyDoesNotAffectTenantBAllow() throws IOException {
        Tenant tenantA = tenantWithEvolveTwinRule("Tenant A", PolicyEffect.DENY);
        Tenant tenantB = tenantWithEvolveTwinRule("Tenant B", PolicyEffect.ALLOW);

        ProcessModel modelA = workbenchService.saveProcessModel(null, "isolation test A", citibankBpmn(),
                tenantA.id());
        TwinProcess twinA = workbenchService.launchProcess(modelA.getId());
        workbenchService.connectActivity(twinA.getId(), KYC, KYC);

        ProcessModel modelB = workbenchService.saveProcessModel(null, "isolation test B", citibankBpmn(),
                tenantB.id());
        TwinProcess twinB = workbenchService.launchProcess(modelB.getId());
        workbenchService.connectActivity(twinB.getId(), KYC, KYC);

        AgentDecision decisionA = workbenchService.evolveActivity(twinA.getId(), KYC, "validator");
        AgentDecision decisionB = workbenchService.evolveActivity(twinB.getId(), KYC, "validator");

        assertThat(decisionA.isApproved()).isFalse();
        assertThat(evolvedAgent(twinA, KYC)).isNull();
        assertThat(decisionB.isApproved()).isTrue();
        assertThat(evolvedAgent(twinB, KYC)).isEqualTo("validator-agent-01");
    }

    // Section 8/Step 3: REQUIRE_APPROVAL is not implemented yet, but it must never quietly become
    // ALLOW - the action must not execute, and the result must say why in a way DENY doesn't
    @Test
    void requireApprovalDoesNotExecuteAndIsNotTheSameAsDeny() throws IOException {
        Tenant tenant = tenantWithEvolveTwinRule("Approval Tenant", PolicyEffect.REQUIRE_APPROVAL);
        ProcessModel model = workbenchService.saveProcessModel(null, "approval enforcement test", citibankBpmn(),
                tenant.id());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        AgentDecision decision = workbenchService.evolveActivity(twin.getId(), KYC, "validator");

        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.getGovernanceDecision()).isEqualTo("REQUIRE_APPROVAL");
        assertThat(evolvedAgent(twin, KYC)).isNull();

        // A real, persistent PENDING approval, not just a refused response
        List<Approval> pending = approvalService.listForTenant(tenant.id());
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(pending.get(0).twinId()).isEqualTo(twin.getId());
        assertThat(pending.get(0).activityId()).isEqualTo(KYC);
    }

    // Approving is not "run the evolve request again from scratch" - it resumes the
    // exact paused operation and the real side effect actually happens, same as an ALLOW would
    @Test
    void approvingAnApprovalActuallyExecutesTheOriginalOperation() throws IOException {
        Tenant tenant = tenantWithEvolveTwinRule("Approve Tenant", PolicyEffect.REQUIRE_APPROVAL);
        ProcessModel model = workbenchService.saveProcessModel(null, "approve execution test", citibankBpmn(),
                tenant.id());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        workbenchService.evolveActivity(twin.getId(), KYC, "validator");
        String approvalId = approvalService.listForTenant(tenant.id()).get(0).id();

        AgentDecision decision = workbenchService.approveEvolution(approvalId, tenant.id());

        assertThat(decision.isApproved()).isTrue();
        assertThat(evolvedAgent(twin, KYC)).isEqualTo("validator-agent-01");
        assertThat(approvalService.get(approvalId, tenant.id()).status()).isEqualTo(ApprovalStatus.COMPLETED);
    }

    // Rejection is permanent and the action must never run
    @Test
    void rejectingAnApprovalPermanentlyStopsIt() throws IOException {
        Tenant tenant = tenantWithEvolveTwinRule("Reject Tenant", PolicyEffect.REQUIRE_APPROVAL);
        ProcessModel model = workbenchService.saveProcessModel(null, "reject test", citibankBpmn(), tenant.id());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        workbenchService.evolveActivity(twin.getId(), KYC, "validator");
        String approvalId = approvalService.listForTenant(tenant.id()).get(0).id();

        AgentDecision decision = workbenchService.rejectApproval(approvalId, tenant.id());

        assertThat(decision.isApproved()).isFalse();
        assertThat(approvalService.get(approvalId, tenant.id()).status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(evolvedAgent(twin, KYC)).isNull();

        // rejected is terminal - approving it afterward must not be possible
        assertThatThrownBy(() -> workbenchService.approveEvolution(approvalId, tenant.id()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(evolvedAgent(twin, KYC)).isNull();
    }

    // The second approve() call must not be able
    // to run the operation a second time. Node-manager call count is the actual proof - if
    // executeAfterGovernance ran twice, it would have been contacted twice.
    @Test
    void approvingTheSameApprovalTwiceCannotExecuteTwice() throws IOException {
        Tenant tenant = tenantWithEvolveTwinRule("Double Approve Tenant", PolicyEffect.REQUIRE_APPROVAL);
        ProcessModel model = workbenchService.saveProcessModel(null, "double approve test", citibankBpmn(),
                tenant.id());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        workbenchService.evolveActivity(twin.getId(), KYC, "validator");
        String approvalId = approvalService.listForTenant(tenant.id()).get(0).id();

        AgentDecision first = workbenchService.approveEvolution(approvalId, tenant.id());
        assertThat(first.isApproved()).isTrue();

        assertThatThrownBy(() -> workbenchService.approveEvolution(approvalId, tenant.id()))
                .isInstanceOf(IllegalStateException.class);

        org.mockito.Mockito.verify(nodeManagerClient, org.mockito.Mockito.times(1)).checkAgentAvailability("validator");
    }

    // An approval is tenant-owned exactly like a policy is - same "not found"
    // message whether it doesn't exist or belongs to someone else
    @Test
    void tenantBCannotResolveTenantAsApproval() throws IOException {
        Tenant tenantA = tenantWithEvolveTwinRule("Isolation Tenant A", PolicyEffect.REQUIRE_APPROVAL);
        Tenant tenantB = tenantPolicyService.createTenant("Isolation Tenant B");
        ProcessModel model = workbenchService.saveProcessModel(null, "approval isolation test", citibankBpmn(),
                tenantA.id());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        workbenchService.evolveActivity(twin.getId(), KYC, "validator");
        String approvalId = approvalService.listForTenant(tenantA.id()).get(0).id();

        assertThatThrownBy(() -> workbenchService.approveEvolution(approvalId, tenantB.id()))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> workbenchService.rejectApproval(approvalId, tenantB.id()))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(approvalService.listForTenant(tenantB.id())).isEmpty();
        // still genuinely pending - tenant B's failed attempts didn't touch it
        assertThat(approvalService.get(approvalId, tenantA.id()).status()).isEqualTo(ApprovalStatus.PENDING);
    }

    // Activating a new policy version after an approval was created must not
    // retroactively change what that approval means - it executes under the decision that was
    // actually pinned when the human was asked, not whatever the tenant's policy says now
    @Test
    void approvalExecutesUnderItsOriginalPolicyVersionNotALaterOne() throws IOException {
        Tenant tenant = tenantPolicyService.createTenant("Version Pin Tenant");
        Policy policy = tenantPolicyService.createTenantPolicy(tenant.id(), "Evolve Policy");
        PolicyVersion v1 = tenantPolicyService.createDraftVersion(policy.id(), tenant.id());
        tenantPolicyService.addRule(v1.id(), tenant.id(), "action", "==", "EVOLVE_TWIN", PolicyEffect.REQUIRE_APPROVAL);
        tenantPolicyService.activateVersion(v1.id(), tenant.id());

        ProcessModel model = workbenchService.saveProcessModel(null, "version pin test", citibankBpmn(),
                tenant.id());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        workbenchService.evolveActivity(twin.getId(), KYC, "validator");
        String approvalId = approvalService.listForTenant(tenant.id()).get(0).id();
        assertThat(approvalService.get(approvalId, tenant.id()).policyVersionNumber()).isEqualTo(1);

        // now the tenant activates a stricter version - a fresh request would be denied
        PolicyVersion v2 = tenantPolicyService.createDraftVersion(policy.id(), tenant.id());
        tenantPolicyService.addRule(v2.id(), tenant.id(), "action", "==", "EVOLVE_TWIN", PolicyEffect.DENY);
        tenantPolicyService.activateVersion(v2.id(), tenant.id());

        // the OLD approval still executes - it was never asked about v2
        AgentDecision decision = workbenchService.approveEvolution(approvalId, tenant.id());
        assertThat(decision.isApproved()).isTrue();
        assertThat(evolvedAgent(twin, KYC)).isEqualTo("validator-agent-01");
    }

    // up to now an evolution was pure bookkeeping - it recorded which agent was picked and the
    // original ran exactly the same either way. Task_Credit is the one activity where the agent's
    // answer steers the process, so these two runs differ in nothing but which agent evolved it.
    @Test
    void aRiskFlaggingAgentSendsTheTransferToTheComplianceOfficer() throws IOException {
        TwinProcess plain = walkToTheComplianceChecks("citi wire transfer plain credit check");
        assertThat(workbenchService.completeCurrentTasks(plain.getId())).hasSize(3);

        assertThat(originalVariable(plain, RISK_FLAG)).isNull();
        assertThat(openActivities(plain)).containsExactly(APPROVE);
        assertThat(reached(plain, ESCALATE)).isFalse();
        // and out the far end the way it always did
        assertThat(workbenchService.completeCurrentTasks(plain.getId())).hasSize(1);
        assertThat(workbenchService.completeCurrentTasks(plain.getId())).hasSize(1);
        assertThat(workbenchService.completeCurrentTasks(plain.getId())).hasSize(1);
        assertThat(reached(plain, "EndEvent_Success")).isTrue();

        TwinProcess flagged = walkToTheComplianceChecks("citi wire transfer risk assessed credit check");
        AgentDecision credit = workbenchService.evolveActivity(flagged.getId(), CREDIT, RISK_AGENT_TYPE);
        assertThat(credit.isApproved()).isTrue();
        assertThat(credit.isRiskFlagged()).isTrue();
        assertThat(evolvedAgent(flagged, CREDIT)).isEqualTo(RISK_AGENT_TYPE + "-agent-01");

        assertThat(workbenchService.completeCurrentTasks(flagged.getId())).hasSize(3);

        assertThat(originalVariable(flagged, RISK_FLAG)).isEqualTo(true);
        assertThat(openActivities(flagged)).containsExactly(ESCALATE);
        assertThat(reached(flagged, APPROVE)).isFalse();
    }

    // Evolving with an ordinary
    // agent after a flagged one left the old flag sitting on the twin, so the UI would show a
    // plain agent assigned while the process kept escalating anyway
    @Test
    void reEvolvingWithAnOrdinaryAgentClearsAnEarlierRiskFlag() throws IOException {
        TwinProcess twin = walkToTheComplianceChecks("citi wire transfer re-evolved credit check");

        assertThat(workbenchService.evolveActivity(twin.getId(), CREDIT, RISK_AGENT_TYPE).isRiskFlagged()).isTrue();
        assertThat(workbenchService.evolveActivity(twin.getId(), CREDIT, "validator").isRiskFlagged()).isFalse();

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);

        assertThat(originalVariable(twin, RISK_FLAG)).isNull();
        assertThat(openActivities(twin)).containsExactly(APPROVE);
        assertThat(reached(twin, ESCALATE)).isFalse();
    }

    // the shared front half of both runs above: launch, bridge KYC by hand, and stop with the
    // three compliance checks open and connected
    private TwinProcess walkToTheComplianceChecks(String modelName) throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, modelName, citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        connect(twin, KYC, AML, OFAC, CREDIT);

        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).containsExactlyInAnyOrder(AML, OFAC, CREDIT);
        return twin;
    }

    // connect() above always maps id to itself, so it never catches original/twin ids differing
    @Test
    void agentExecutionResolvesThroughANonIdentityActivityLink() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null,
                "citi wire transfer mismatched link", citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        workbenchService.connectActivity(twin.getId(), KYC, AML);
        AgentDecision kyc = workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        assertThat(kyc.isApproved()).isTrue();
        assertThat(evolvedAgent(twin, AML)).isEqualTo(BRIDGE_AGENT);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        assertThat(agentExecuted(twin, KYC)).isEqualTo(BRIDGE_AGENT);
    }

    // the auto-bridge keys its already-forwarded guard on the activity instance, the manual button
    // used to key on the bare activity id, so the two guards lived in namespaces that could never
    // match and a redundant click bought a second quota slot
    @Test
    void manualBridgeAfterTheAutoBridgeChangesNothing() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer double bridge",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        connect(twin, KYC, AML);

        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(evolvedAgent(twin, AML)).isEqualTo(BRIDGE_AGENT);

        int used = governanceService.getUsage(twin.getId()).getEvolutionCount();
        AgentDecision again = workbenchService.bridgeActivityEvent(twin.getId(), AML);

        assertThat(again.isApproved()).isFalse();
        assertThat(again.getReason()).contains("already forwarded");
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(used);
    }

    // Checking evolvedAgent_<twinActivityId>[_loopCounter] alone is correct for a multi-instance
    // visit, where loopCounter makes the name visit-unique (the test above covers that), but wrong
    // for a PLAIN
    // activity revisited through an ordinary loop-back gateway: no multi-instance, no loopCounter,
    // every visit writes the identical variable name. That made visit #2 look "already forwarded"
    // the instant visit #1 succeeded - exactly what the deleted forwardedBridgeActivities Set's own
    // comment had warned about.
    @Test
    void aPlainActivityRevisitedThroughALoopBackGatewayBridgesEveryVisitNotJustTheFirst() throws IOException {
        AtomicInteger nextAgent = new AtomicInteger();
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true,
                    type + "-agent-0" + nextAgent.incrementAndGet(), "stub catalog", false);
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "loop back gateway test", loopBackBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), "Task_Redo", "Task_Redo");

        // visit #1's start event fires during launchProcess, before the twin is tracked - same
        // reason KYC gets bridged by hand elsewhere in this file
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), "Task_Redo").isApproved()).isTrue();
        assertThat(evolvedAgent(twin, "Task_Redo")).isEqualTo("validator-agent-01");

        Task firstVisit = taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId()).singleResult();
        taskService.complete(firstVisit.getId(), Map.of("redo", true));

        // completing visit #1 with redo=true sends the token back through the gateway into the
        // same activity a second time - same activityId, no loopCounter, a brand new activityInstanceId
        Task secondVisit = taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId()).singleResult();
        assertThat(secondVisit.getId()).isNotEqualTo(firstVisit.getId());

        // visit #2 must not be skipped as "already forwarded"
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(2);
        assertThat(evolvedAgent(twin, "Task_Redo")).isEqualTo("validator-agent-02");

        taskService.complete(secondVisit.getId(), Map.of("redo", false));
        assertThat(reached(twin, "EndEvent_1")).isTrue();
    }

    // The old forwardedBridgeActivities guard lived only in
    // WorkbenchServiceImpl's own memory, so a plain app restart wiped it and reopened every
    // already-bridged visit to a second evolution. It's gone now - the guard is derived from
    // evolvedAgent_<twinActivityId> on the twin's own Camunda runtime state instead, which is a row
    // in the engine's tables, not app memory. Proven here with a second WorkbenchServiceImpl built
    // directly rather than through Spring: its own twinProcesses map starts with nothing but the
    // TwinProcess object itself (id, activity links - exactly what restoreState() reconstructs from
    // the state file on a real reboot), no evolutionsInFlight claim and no per-visit dedup state of
    // any kind carried over, because TwinProcess no longer has anywhere to carry one. If the
    // duplicate is still refused here, it can only be because the check reads Camunda's own tables.
    @Test
    void bridgeDedupeIsSafeAcrossACompletelyFreshServiceInstance() throws Exception {
        ProcessModel model = workbenchService.saveProcessModel(null, "restart dedupe test", citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        AgentDecision firstBridge = workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        assertThat(firstBridge.isApproved()).isTrue();

        WorkbenchServiceImpl freshService = new WorkbenchServiceImpl(nodeManagerClient, governanceService, policyDecisionEngine, approvalService,
                runtimeService, repositoryService, historyService, taskService, externalTaskService, twinModelGenerator, stateStore,
                modelFileStore, processModelArchiveStore, delegateClassGenerator, springBootProjectGenerator, springBootProjectLauncher,
                workflowStateTracker);
        Field twinProcessesField = WorkbenchServiceImpl.class.getDeclaredField("twinProcesses");
        twinProcessesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, TwinProcess> freshTwinProcesses = (Map<String, TwinProcess>) twinProcessesField.get(freshService);
        freshTwinProcesses.put(twin.getId(), twin);

        AgentDecision secondBridge = freshService.bridgeActivityEvent(twin.getId(), KYC);
        assertThat(secondBridge.isApproved()).isFalse();
        assertThat(secondBridge.getReason()).contains("already forwarded");
        // exactly the one call the first, genuine bridge made - the fresh instance never asked again
        org.mockito.Mockito.verify(nodeManagerClient, org.mockito.Mockito.times(1))
                .checkAgentAvailability(anyString());
    }

    // the delegate used to read the twin's variables blind and let the engine throw. the catch
    // around it never helped: the transaction is rollback-only by then, so completing the task
    // failed with UnexpectedRollbackException and the real cause only showed up as a warn line
    @Test
    void completingATaskStillWorksAfterTheTwinInstanceIsGone() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer lost twin",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();

        runtimeService.deleteProcessInstance(twin.getTwinProcessId(), "twin ended before the original");
        assertThat(workbenchService.getTwinProcess(twin.getId()).getStatus())
                .isEqualTo("ORIGINAL_RUNNING_TWIN_ENDED");

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).containsExactlyInAnyOrder(AML, OFAC, CREDIT);
        assertThat(agentExecuted(twin, KYC)).isNull();
    }

    // the delegate looked the twin activity up with a resolver that fell back to the activity's
    // own id, so completing an activity nobody connected read whatever agent another link had
    // parked under that name and reported it as executed
    @Test
    void anUnconnectedActivityNeverReportsAnAgentExecution() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer unconnected",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        workbenchService.connectActivity(twin.getId(), KYC, AML);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();
        assertThat(evolvedAgent(twin, AML)).isEqualTo(BRIDGE_AGENT);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        // Task_AML is one of the three that just opened, and it was never connected to anything
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);

        assertThat(agentExecuted(twin, AML)).isNull();
    }

    @Test
    void completingTwiceAtOnceDoesNotBlowUp() throws Exception {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer race", citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).hasSize(3);

        // both requests take their task snapshot before either one completes anything, which is
        // what a double click on Complete current task(s) does at the compliance-check step
        CyclicBarrier gate = new CyclicBarrier(2);
        Callable<List<String>> complete = () -> {
            gate.await(10, TimeUnit.SECONDS);
            return workbenchService.completeCurrentTasks(twin.getId());
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<String>>> results = pool.invokeAll(List.of(complete, complete));
            List<String> all = new ArrayList<>();
            for (Future<List<String>> result : results) {
                all.addAll(result.get(30, TimeUnit.SECONDS));
            }
            // each task completed exactly once, split between the two however it landed
            assertThat(all).hasSize(3);
        } finally {
            pool.shutdownNow();
        }

        assertThat(openActivities(twin)).containsExactly(APPROVE);
    }

    // stubbed catalog parks whoever gets in first, guaranteeing the second one arrives mid-evolution
    @Test
    void evolveAndBridgeAtOnceOnlyBurnOneSlot() throws Exception {
        assertOneSlotWhenRacing(true);
        assertOneSlotWhenRacing(false);
    }

    private void assertOneSlotWhenRacing(boolean evolveGoesFirst) throws Exception {
        ProcessModel model = workbenchService.saveProcessModel(null,
                "citi wire transfer evolve race " + evolveGoesFirst, citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch secondIsDone = new CountDownLatch(1);
        AtomicBoolean parked = new AtomicBoolean(false);
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            if (parked.compareAndSet(false, true)) {
                firstIsInside.countDown();
                secondIsDone.await(20, TimeUnit.SECONDS);
            }
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });

        Callable<AgentDecision> first = evolveGoesFirst
                ? () -> workbenchService.evolveActivity(twin.getId(), KYC, "validator")
                : () -> workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        Callable<AgentDecision> second = evolveGoesFirst
                ? () -> workbenchService.bridgeActivityEvent(twin.getId(), KYC)
                : () -> workbenchService.evolveActivity(twin.getId(), KYC, "validator");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AgentDecision> firstResult = pool.submit(first);
            assertThat(firstIsInside.await(20, TimeUnit.SECONDS)).isTrue();
            AgentDecision secondDecision = second.call();
            secondIsDone.countDown();
            AgentDecision firstDecision = firstResult.get(30, TimeUnit.SECONDS);

            assertThat(firstDecision.isApproved()).isTrue();
            assertThat(secondDecision.isApproved()).isFalse();
        } finally {
            pool.shutdownNow();
        }

        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(1);
    }

    // AdvanceTwinActivity used to run unconditionally once the
    // evolutionsInFlight claim was released, so the LOSER of two concurrent bridge calls for the
    // identical visit could advance the twin's token before the WINNER's own evolution had actually
    // finished - running automation with evolvedAgent_KYC still unset, and losing the winner's real
    // node-manager round trip outright when its later setVariable() found the twin already moved on.
    @Test
    void aSecondConcurrentBridgeForTheSameVisitNeverAdvancesBeforeTheFirstEvolutionFinishes() throws Exception {
        ProcessModel model = workbenchService.saveProcessModel(null,
                "citi wire transfer advance race", citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        CountDownLatch firstIsInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean parked = new AtomicBoolean(false);
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            if (parked.compareAndSet(false, true)) {
                firstIsInside.countDown();
                releaseFirst.await(20, TimeUnit.SECONDS);
            }
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AgentDecision> firstCall = pool.submit(() -> workbenchService.bridgeActivityEvent(twin.getId(), KYC));
            assertThat(firstIsInside.await(20, TimeUnit.SECONDS)).isTrue();

            Future<AgentDecision> secondCall = pool.submit(() -> workbenchService.bridgeActivityEvent(twin.getId(), KYC));
            // loses the claim immediately and returns fast - the fix means it never even attempts
            // to advance the twin, rather than blocking until the winner finishes
            AgentDecision secondDecision = secondCall.get(10, TimeUnit.SECONDS);
            assertThat(secondDecision.isApproved()).isFalse();

            // the proof the bug is fixed: at this exact point, with the winning evolution still
            // parked mid-flight, the twin must NOT have been advanced by the loser, and no agent
            // must have been recorded yet either
            assertThat(runtimeService.getActiveActivityIds(twin.getTwinProcessId())).containsExactly(KYC);
            assertThat(evolvedAgent(twin, KYC)).isNull();

            releaseFirst.countDown();
            AgentDecision firstDecision = firstCall.get(30, TimeUnit.SECONDS);

            assertThat(firstDecision.isApproved()).isTrue();
            assertThat(evolvedAgent(twin, KYC)).isEqualTo("validator-agent-01");
            assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(1);
            assertThat(twinAutomation(twin, KYC)).isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }

    // forwardedBridgeActivities used to key on activityId alone, so visit #2 looked like a
    // duplicate. Then the guard got fixed but the variables didn't: both visits still wrote
    // evolvedAgent_Task_Loop, so visit #2 quietly overwrote visit #1 and two real evolutions
    // were indistinguishable from one.
    @Test
    void multiInstanceActivityBridgesEveryVisitNotJustTheFirst() throws IOException {
        // a different agent per call, so a variable that survived from the wrong visit shows up
        AtomicInteger nextAgent = new AtomicInteger();
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true,
                    type + "-agent-0" + nextAgent.incrementAndGet(), "stub catalog", false);
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "loop task test", loopBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), "Task_Loop", "Task_Loop");

        // visit #1's start event fires during launchProcess, before the twin is tracked - same
        // reason KYC gets bridged by hand elsewhere in this file
        AgentDecision firstVisit = workbenchService.bridgeActivityEvent(twin.getId(), "Task_Loop");
        assertThat(firstVisit.isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        // completing visit #1 opens visit #2 - same activityId, different activityInstanceId
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        assertThat(reached(twin, "EndEvent_1")).isTrue();
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(2);

        // both evolutions still there at the end, each with the agent its own visit was given
        assertThat(evolvedAgent(twin, "Task_Loop_0")).isEqualTo("validator-agent-01");
        assertThat(evolvedAgent(twin, "Task_Loop_1")).isEqualTo("validator-agent-02");
        assertThat(agentExecuted(twin, "Task_Loop_0")).isEqualTo("validator-agent-01");
        // the unsuffixed name is what the old one-per-activity write used
        assertThat(evolvedAgent(twin, "Task_Loop")).isNull();
        assertThat(agentExecuted(twin, "Task_Loop")).isNull();

        // The twin walked both visits of its own copy of the loop, one message per visit, and its
        // second visit is bridged the moment the original's second visit opens - which is before
        // the original completes it. So the twin has already reached its end event by the time
        // AgentExecutionDelegate runs for visit #2, and the delegate leaves a finished twin alone.
        assertThat(twinAutomation(twin, "Task_Loop_0")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Loop_1")).isNotNull();
        assertThat(twinReached(twin, "EndEvent_1")).isTrue();
        assertThat(agentExecuted(twin, "Task_Loop_1")).isNull();
    }

    // Parallel multi-instance through the real bridge/governance/twin-advance path.
    // Camunda's correlate() throws the instant more than one execution matches a message name,
    // which is exactly what three parallel siblings waiting on the identical name produce.
    // advanceTwinActivity resolves that by loopCounter, using messageEventReceived(name,
    // executionId) to target one sibling at a time.
    @Test
    void parallelMultiInstanceActivityAdvancesEachSiblingIndependently() throws IOException {
        AtomicInteger nextAgent = new AtomicInteger();
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true,
                    type + "-agent-0" + nextAgent.incrementAndGet(), "stub catalog", false);
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "parallel loop task test",
                parallelLoopBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), "Task_Gate", "Task_Gate");
        workbenchService.connectActivity(twin.getId(), "Task_Parallel", "Task_Parallel");

        // Task_Gate's start event fires during launchProcess, before the twin is tracked - same
        // registration-order gap the very first activity always has, sequential or not
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), "Task_Gate").isApproved()).isTrue();

        // completing the gate opens all three parallel branches on the original at once, and the
        // twin's own three siblings get created the moment its Task_Gate automation ran above -
        // AutoBridgeTrigger fires once per branch, each individually resolved by loopCounter
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).containsExactly("Task_Parallel", "Task_Parallel", "Task_Parallel");

        // all three twin siblings advanced independently - none collided, none left behind
        assertThat(twinAutomation(twin, "Task_Parallel_0")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Parallel_1")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Parallel_2")).isNotNull();
        assertThat(evolvedAgent(twin, "Task_Parallel_0")).isNotNull();
        assertThat(evolvedAgent(twin, "Task_Parallel_1")).isNotNull();
        assertThat(evolvedAgent(twin, "Task_Parallel_2")).isNotNull();
        // each branch really got its own agent rather than three writes landing on one variable
        assertThat(List.of(evolvedAgent(twin, "Task_Parallel_0"), evolvedAgent(twin, "Task_Parallel_1"),
                evolvedAgent(twin, "Task_Parallel_2"))).doesNotHaveDuplicates();

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);
        assertThat(reached(twin, "EndEvent_1")).isTrue();
        assertThat(twinReached(twin, "EndEvent_1")).isTrue();
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(4);
    }

    // Before this pass,
    // the manual "Bridge selected activity" button against a parallel activity would throw on
    // every click, since advanceTwinActivity had no execution id to disambiguate with. The
    // two-argument convenience overload (bridge "whichever visit I'm sitting on") still can't tell
    // several simultaneously-open parallel siblings apart on its own - currentVisitId resolves to
    // the same not-yet-ended visit every time, so repeated clicks with no way to name a different
    // one keep landing on the first sibling and correctly report "already forwarded" rather than
    // making anything up or corrupting state.
    @Test
    void manualBridgeWithNoVisitSelectorKeepsResolvingTheSameParallelSibling() throws IOException {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "parallel first activity test",
                parallelFirstBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), "Task_Parallel", "Task_Parallel");
        assertThat(openActivities(twin)).containsExactly("Task_Parallel", "Task_Parallel", "Task_Parallel");

        AgentDecision first = workbenchService.bridgeActivityEvent(twin.getId(), "Task_Parallel");
        assertThat(first.isApproved()).isTrue();
        int usedAfterFirst = governanceService.getUsage(twin.getId()).getTwinExecutionCount();
        assertThat(usedAfterFirst).isEqualTo(1);

        // repeated clicks resolve to the same already-forwarded visit - no crash, no slot leak,
        // but no further progress either
        AgentDecision second = workbenchService.bridgeActivityEvent(twin.getId(), "Task_Parallel");
        assertThat(second.isApproved()).isFalse();
        assertThat(second.getReason()).contains("already forwarded");
        assertThat(governanceService.getUsage(twin.getId()).getTwinExecutionCount()).isEqualTo(usedAfterFirst);
    }

    // What actually closes the gap the test above documents: bridgeActivityEvent's three-argument
    // overload (originally AutoBridgeTrigger's own entry point) takes the exact visit to bridge as
    // an activityInstanceId, and now advances the twin through that specific visit too - the same
    // consolidation that let AutoBridgeTrigger drop its separate advanceTwinActivity call entirely.
    // A caller who can name which of several open parallel siblings they mean - a future frontend
    // listing three distinct open tasks with three distinct ids, or this test reading them off
    // history the same way currentVisitId would - reaches all three individually, cleanly.
    @Test
    void bridgeActivityEventWithAnExplicitVisitReachesEveryParallelSibling() throws IOException {
        AtomicInteger nextAgent = new AtomicInteger();
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true,
                    type + "-agent-0" + nextAgent.incrementAndGet(), "stub catalog", false);
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "parallel explicit visit test",
                parallelFirstBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), "Task_Parallel", "Task_Parallel");

        List<String> visitIds = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .activityId("Task_Parallel")
                .unfinished()
                .list().stream().map(HistoricActivityInstance::getId).toList();
        assertThat(visitIds).hasSize(3);

        for (String visitId : visitIds) {
            AgentDecision decision = workbenchService.bridgeActivityEvent(twin.getId(), "Task_Parallel", visitId);
            assertThat(decision.isApproved()).isTrue();
        }

        assertThat(twinAutomation(twin, "Task_Parallel_0")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Parallel_1")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Parallel_2")).isNotNull();
        assertThat(governanceService.getUsage(twin.getId()).getTwinExecutionCount()).isEqualTo(3);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);
        assertThat(twinReached(twin, "EndEvent_1")).isTrue();
    }

    @Test
    void rejectsRubbishInsteadOf500ing() throws IOException {
        assertThatThrownBy(() -> workbenchService.launchProcess(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workbenchService.getTwinProcess(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workbenchService.saveProcessModel(null, "not bpmn", "<nope/>"))
                .isInstanceOf(IllegalArgumentException.class);

        // we can't tell a model is unusable until it's already deployed, so the reject path has
        // to take the deployment back out again or cockpit slowly fills up with junk
        long deploymentsBefore = repositoryService.createDeploymentQuery().count();
        String notExecutable = citibankBpmn().replace("isExecutable=\"true\"", "isExecutable=\"false\"");
        assertThatThrownBy(() -> workbenchService.saveProcessModel(null, "not executable", notExecutable))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repositoryService.createDeploymentQuery().count()).isEqualTo(deploymentsBefore);

        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer bad input",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        assertThatThrownBy(() -> workbenchService.evolveActivity(twin.getId(), null, "validator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workbenchService.connectActivity(twin.getId(), "Task_NotInTheDiagram", KYC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void connect(TwinProcess twin, String... activityIds) {
        for (String activityId : activityIds) {
            workbenchService.connectActivity(twin.getId(), activityId, activityId);
        }
    }

    private List<String> openActivities(TwinProcess twin) {
        return taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .list()
                .stream()
                .map(Task::getTaskDefinitionKey)
                .toList();
    }

    // via history like originalVariable below, and for the same reason: the twin now walks along
    // with the original and reaches its own end event first, so a runtimeService read on it throws
    private Object evolvedAgent(TwinProcess twin, String twinActivityId) {
        HistoricVariableInstance variable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .variableName("evolvedAgent_" + twinActivityId)
                .singleResult();
        return variable == null ? null : variable.getValue();
    }

    // what the twin's own automation left behind on the activity it walked through
    private Object twinAutomation(TwinProcess twin, String twinActivityId) {
        HistoricVariableInstance variable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .variableName("twinAutomation_" + twinActivityId)
                .singleResult();
        return variable == null ? null : variable.getValue();
    }

    private boolean twinReached(TwinProcess twin, String activityId) {
        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .activityId(activityId)
                .count() > 0;
    }

    private Object agentExecuted(TwinProcess twin, String activityId) {
        return originalVariable(twin, "agentExecuted_" + activityId);
    }

    // via history, not runtimeService - the original has already ended by the time some of these
    // assertions run and reading a variable off a finished instance throws
    private Object originalVariable(TwinProcess twin, String variableName) {
        HistoricVariableInstance variable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .variableName(variableName)
                .singleResult();
        return variable == null ? null : variable.getValue();
    }

    private boolean reached(TwinProcess twin, String activityId) {
        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .activityId(activityId)
                .count() > 0;
    }

    // walk up to find examples/ instead of copying the bpmn into test resources and letting them drift
    private static String citibankBpmn() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("examples").resolve("citibank-wire-transfer.bpmn");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("no examples/citibank-wire-transfer.bpmn above "
                + Path.of("").toAbsolutePath());
    }

    // small enough to just inline rather than another file in examples/ - only exists to give a
    // real sequential multi-instance activity for the bridge-tracking regression test above.
    // Carries the same complete listener as the two example models, or the test would only ever
    // exercise the bridge and never the delegate.
    private static String loopBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Definitions_Loop" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_LoopTask" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Loop" name="Loop Task">
                      <bpmn:extensionElements>
                        <camunda:taskListener event="complete" delegateExpression="${agentExecutionDelegate}" />
                      </bpmn:extensionElements>
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="true">
                        <bpmn:loopCardinality>2</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Loop" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Loop" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    // a plain (non-multi-instance) activity a token can revisit more than once - an ordinary
    // exclusive-gateway loop-back, not a loop characteristic - so there is no loopCounter anywhere
    // on either side to tell visit #1 and visit #2 apart by name
    private static String loopBackBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_LoopBack" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_LoopBack" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Redo" name="Redo task">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:incoming>Flow_Loop</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:exclusiveGateway id="Gateway_Redo" default="Flow_End">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                      <bpmn:outgoing>Flow_Loop</bpmn:outgoing>
                      <bpmn:outgoing>Flow_End</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_End</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Redo" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Redo" targetRef="Gateway_Redo" />
                    <bpmn:sequenceFlow id="Flow_Loop" sourceRef="Gateway_Redo" targetRef="Task_Redo">
                      <bpmn:conditionExpression>${redo == true}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_End" sourceRef="Gateway_Redo" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    // Task_Gate first so Task_Parallel's three branches open via the ordinary AFTER_COMMIT path
    // rather than the launch-time registration race every first activity has - the parallel
    // regression test above is about disambiguating three simultaneous siblings, not about that
    // separate, already-covered gap.
    private static String parallelLoopBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Definitions_ParallelLoop" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_ParallelLoopTask" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Gate" name="Gate Task">
                      <bpmn:extensionElements>
                        <camunda:taskListener event="complete" delegateExpression="${agentExecutionDelegate}" />
                      </bpmn:extensionElements>
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:userTask id="Task_Parallel" name="Parallel Task">
                      <bpmn:extensionElements>
                        <camunda:taskListener event="complete" delegateExpression="${agentExecutionDelegate}" />
                      </bpmn:extensionElements>
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                      <bpmn:outgoing>Flow_3</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="false">
                        <bpmn:loopCardinality>3</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_3</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Gate" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Gate" targetRef="Task_Parallel" />
                    <bpmn:sequenceFlow id="Flow_3" sourceRef="Task_Parallel" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    // parallel MI with nothing ahead of it, so all three branches hit the same registration-order
    // gap the very first activity always has, and only the manual bridge button can reach any of
    // them - exactly the shape needed to see what that button can and can't do against a parallel
    // activity none of whose siblings have completed yet.
    private static String parallelFirstBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Definitions_ParallelFirst" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_ParallelFirst" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Parallel" name="Parallel Task">
                      <bpmn:extensionElements>
                        <camunda:taskListener event="complete" delegateExpression="${agentExecutionDelegate}" />
                      </bpmn:extensionElements>
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="false">
                        <bpmn:loopCardinality>3</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Parallel" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Parallel" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }
}
