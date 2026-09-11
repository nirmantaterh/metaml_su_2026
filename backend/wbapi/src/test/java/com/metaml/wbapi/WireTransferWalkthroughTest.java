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

// Integration walkthrough using an embedded Camunda engine with stubbed NodeManagerClient.
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

    private static final String BRIDGE_AGENT = "validator-agent-01";
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
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog",
                    RISK_AGENT_TYPE.equals(type));
        });
        governanceService.updatePolicy(Set.of(), 20);
    }

    @Test
    void savingAModelWritesItsBpmnAsARealFileOnTheServerFilesystem() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "file store test", citibankBpmn());

        assertThat(modelFileStore.exists(model.getId())).isTrue();
        String onDisk = java.nio.file.Files.readString(modelFileStore.pathFor(model.getId()));
        assertThat(onDisk).isEqualTo(citibankBpmn());
    }

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
        try (var entries = Files.list(modelsDir)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.contains("escaped"));
        }
    }

    @Test
    void listProcessModelsReturnsEveryModelNewestFirst() throws IOException {
        ProcessModel first = workbenchService.saveProcessModel(null, "first saved", citibankBpmn());
        ProcessModel second = workbenchService.saveProcessModel(null, "second saved", loanApprovalBpmn());

        List<ProcessModel> models = workbenchService.listProcessModels();

        assertThat(models).extracting(ProcessModel::getId).contains(first.getId(), second.getId());
        int firstIndex = models.indexOf(models.stream().filter(m -> m.getId().equals(first.getId())).findFirst().get());
        int secondIndex = models.indexOf(models.stream().filter(m -> m.getId().equals(second.getId())).findFirst().get());
        assertThat(secondIndex).isLessThan(firstIndex);
    }

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

    @Test
    void generateSpringBootProjectProducesADelegateWhosePackageMatchesWhereItsActuallyWritten() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "project generation test",
                loanApprovalBpmn());

        com.metaml.workbench.generation.GeneratedProject project =
                workbenchService.generateSpringBootProject(model.getId());

        assertThat(project.processKey()).isEqualTo("loanApproval");
        java.nio.file.Path delegateFile = project.directory().resolve(
                "src/main/java/com/metaml/targetplatform/loanapproval/delegate/manufacturing/"
                        + "CalculateInterestService.java");
        assertThat(delegateFile).exists();
        assertThat(java.nio.file.Files.readString(delegateFile))
                .contains("package com.metaml.targetplatform.loanapproval.delegate.manufacturing;");
        assertThat(project.directory().resolve("src/main/resources/processes/loanApproval.bpmn")).exists();
    }

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
        assertThat(afterLaunch.history()).hasSize(6);

        workbenchService.stopGeneratedProject(project.projectId());

        com.metaml.workbench.workflow.WorkflowState afterStop = workbenchService.getWorkflowState(model.getId());
        assertThat(afterStop.stages().get(com.metaml.workbench.workflow.WorkflowStage.LAUNCH).status())
                .isEqualTo(com.metaml.workbench.workflow.StageStatus.STOPPED);
    }

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
        assertThat(state.history()).hasSize(7);
    }

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

        try {
            com.metaml.workbench.generation.LaunchedProject launched =
                    restartedService.launchGeneratedProject(project.projectId());

            assertThat(launched.projectId()).isEqualTo(project.projectId());
            assertThat(launched.processKey()).isEqualTo(project.processKey());
            assertThat(launched.port()).isPositive();
            assertThat(launched.modelId()).isEqualTo(model.getId());

            com.metaml.workbench.workflow.WorkflowState state = restartedService.getWorkflowState(model.getId());
            assertThat(state.stages().get(com.metaml.workbench.workflow.WorkflowStage.LAUNCH).status())
                    .isEqualTo(com.metaml.workbench.workflow.StageStatus.COMPLETED);
        } finally {
            restartedService.stopGeneratedProject(project.projectId());
        }
    }

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
        // Compare against deserialized createdAt to account for persistence timestamp truncation.
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

        // Manually bridge initial task since process start event fires before twin mapping registration.
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        AgentDecision kyc = workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        assertThat(kyc.isApproved()).isTrue();
        assertThat(kyc.getAgentName()).isEqualTo(BRIDGE_AGENT);
        assertThat(evolvedAgent(twin, KYC)).isEqualTo(BRIDGE_AGENT);

        // Connect activities before completing predecessor to enable auto-bridge on start.
        connect(twin, AML, OFAC, CREDIT);
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        assertThat(agentExecuted(twin, KYC)).isEqualTo(BRIDGE_AGENT);
        assertThat(twin.getEventLog()).anyMatch(entry -> entry.contains("agentExecuted_" + KYC));

        assertThat(openActivities(twin)).containsExactlyInAnyOrder(AML, OFAC, CREDIT);

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
        assertThat(reached(twin, "EndEvent_RejectedIdentity")).isFalse();
        assertThat(reached(twin, "EndEvent_RejectedCompliance")).isFalse();
        assertThat(reached(twin, "Task_EscalateTimeout")).isFalse();

        assertThat(workbenchService.getTwinProcess(twin.getId()).getStatus()).isEqualTo("ENDED");
        assertThat(twinReached(twin, KYC)).isTrue();
        assertThat(twinReached(twin, "Gateway_ParallelJoin")).isTrue();
        assertThat(twinReached(twin, "EndEvent_Success")).isTrue();
        assertThat(twinAutomation(twin, NOTIFY)).isNotNull();

        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(7);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).isEmpty();
    }

    // Twins launched from tenant-owned models inherit the model's tenantId.
    @Test
    void savingAModelWithATenantIdCarriesItThroughToTheLaunchedTwin() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer owned",
                citibankBpmn(), "tenant-citibank");
        assertThat(model.getTenantId()).isEqualTo("tenant-citibank");

        TwinProcess twin = workbenchService.launchProcess(model.getId());

        assertThat(twin.getTenantId()).isEqualTo("tenant-citibank");
        assertThat(workbenchService.getTwinProcess(twin.getId()).getTenantId()).isEqualTo("tenant-citibank");
    }

    // Untenanted models produce unowned twins without defaulting to an implicit tenant.
    @Test
    void legacyModelsWithNoTenantIdProduceUnownedTwinsNotAnInventedDefault() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer legacy",
                citibankBpmn());
        assertThat(model.getTenantId()).isNull();

        TwinProcess twin = workbenchService.launchProcess(model.getId());

        assertThat(twin.getTenantId()).isNull();
    }

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

    // Reconciles approved governance actions on restart if interrupted prior to execution.
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

    // Restart reconciliation: recognizes when an approval operation completed prior to shutdown,
    // reconciling status without re-executing external agent calls.
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

        AgentDecision realDecision = beforeCrash.approveEvolution(approvalId, tenant.id());
        assertThat(realDecision.isApproved()).isTrue();
        assertThat(evolvedAgent(twin, KYC)).isEqualTo("validator-agent-01");

        Approval completed = realApprovalService.get(approvalId, tenant.id());
        Approval revertedToApproved = completed.withStatus(ApprovalStatus.APPROVED, completed.resolvedAt(), null);
        realApprovalStore.save(List.of(revertedToApproved));

        com.metaml.workbench.workflow.WorkflowStateTracker restartedTracker =
                new com.metaml.workbench.workflow.WorkflowStateTracker(realEventStore);
        invokePostConstructOn(restartedTracker, "restore");
        ApprovalService restartedApprovalService = new ApprovalService(realApprovalStore);
        invokePostConstructOn(restartedApprovalService, "restore");
        assertThat(restartedApprovalService.get(approvalId, tenant.id()).status())
                .isEqualTo(ApprovalStatus.APPROVED);

        WorkbenchServiceImpl restartedService = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                policyDecisionEngine, restartedApprovalService, runtimeService, repositoryService, historyService,
                taskService, externalTaskService, twinModelGenerator, realStateStore, modelFileStore, processModelArchiveStore, delegateClassGenerator,
                springBootProjectGenerator, springBootProjectLauncher, restartedTracker);
        invokePostConstruct(restartedService, "restoreState");

        Approval reconciled = restartedApprovalService.get(approvalId, tenant.id());
        assertThat(reconciled.status()).isEqualTo(ApprovalStatus.COMPLETED);
        org.mockito.Mockito.verify(nodeManagerClient, org.mockito.Mockito.times(1)).checkAgentAvailability("validator");
    }

    // Configures and activates a tenant policy containing an EVOLVE_TWIN rule.
    private Tenant tenantWithEvolveTwinRule(String tenantName, PolicyEffect effect) {
        Tenant tenant = tenantPolicyService.createTenant(tenantName);
        Policy policy = tenantPolicyService.createTenantPolicy(tenant.id(), "Evolve Policy");
        PolicyVersion draft = tenantPolicyService.createDraftVersion(policy.id(), tenant.id());
        tenantPolicyService.addRule(draft.id(), tenant.id(), "action", "==", "EVOLVE_TWIN", effect);
        tenantPolicyService.activateVersion(draft.id(), tenant.id());
        return tenant;
    }

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
        org.mockito.Mockito.verify(nodeManagerClient, org.mockito.Mockito.never()).checkAgentAvailability(anyString());
        assertThat(evolvedAgent(twin, KYC)).isNull();
    }

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

        List<Approval> pending = approvalService.listForTenant(tenant.id());
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(pending.get(0).twinId()).isEqualTo(twin.getId());
        assertThat(pending.get(0).activityId()).isEqualTo(KYC);
    }

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

        assertThatThrownBy(() -> workbenchService.approveEvolution(approvalId, tenant.id()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(evolvedAgent(twin, KYC)).isNull();
    }

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

    // Approvals are tenant-scoped and return not found across tenant boundaries.
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
        assertThat(approvalService.get(approvalId, tenantA.id()).status()).isEqualTo(ApprovalStatus.PENDING);
    }

    // Pending approvals execute under their pinned policy version, unaffected by subsequent version activations.
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

        PolicyVersion v2 = tenantPolicyService.createDraftVersion(policy.id(), tenant.id());
        tenantPolicyService.addRule(v2.id(), tenant.id(), "action", "==", "EVOLVE_TWIN", PolicyEffect.DENY);
        tenantPolicyService.activateVersion(v2.id(), tenant.id());

        AgentDecision decision = workbenchService.approveEvolution(approvalId, tenant.id());
        assertThat(decision.isApproved()).isTrue();
        assertThat(evolvedAgent(twin, KYC)).isEqualTo("validator-agent-01");
    }

    // Tests routing when agent decision sets riskFlagged variable driving exclusive gateway evaluation.
    @Test
    void aRiskFlaggingAgentSendsTheTransferToTheComplianceOfficer() throws IOException {
        TwinProcess plain = walkToTheComplianceChecks("citi wire transfer plain credit check");
        assertThat(workbenchService.completeCurrentTasks(plain.getId())).hasSize(3);

        assertThat(originalVariable(plain, RISK_FLAG)).isNull();
        assertThat(openActivities(plain)).containsExactly(APPROVE);
        assertThat(reached(plain, ESCALATE)).isFalse();
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

    // Advances process execution to the parallel compliance gateway.
    private TwinProcess walkToTheComplianceChecks(String modelName) throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, modelName, citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        connect(twin, KYC, AML, OFAC, CREDIT);

        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).containsExactlyInAnyOrder(AML, OFAC, CREDIT);
        return twin;
    }

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

        // First iteration starts during launchProcess prior to twin registration; manually bridged.
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), "Task_Redo").isApproved()).isTrue();
        assertThat(evolvedAgent(twin, "Task_Redo")).isEqualTo("validator-agent-01");

        Task firstVisit = taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId()).singleResult();
        taskService.complete(firstVisit.getId(), Map.of("redo", true));

        // Looping back creates a new activity instance for the same activity definition.
        Task secondVisit = taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId()).singleResult();
        assertThat(secondVisit.getId()).isNotEqualTo(firstVisit.getId());

        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(2);
        assertThat(evolvedAgent(twin, "Task_Redo")).isEqualTo("validator-agent-02");

        taskService.complete(secondVisit.getId(), Map.of("redo", false));
        assertThat(reached(twin, "EndEvent_1")).isTrue();
    }

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
        org.mockito.Mockito.verify(nodeManagerClient, org.mockito.Mockito.times(1))
                .checkAgentAvailability(anyString());
    }

    // Gracefully handles missing twin process instance during delegate execution.
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

    // Unconnected activities must not report agent executions from unlinked twins.
    @Test
    void anUnconnectedActivityNeverReportsAnAgentExecution() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer unconnected",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        workbenchService.connectActivity(twin.getId(), KYC, AML);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();
        assertThat(evolvedAgent(twin, AML)).isEqualTo(BRIDGE_AGENT);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);

        assertThat(agentExecuted(twin, AML)).isNull();
    }

    @Test
    void completingTwiceAtOnceDoesNotBlowUp() throws Exception {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer race", citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).hasSize(3);

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
            assertThat(all).hasSize(3);
        } finally {
            pool.shutdownNow();
        }

        assertThat(openActivities(twin)).containsExactly(APPROVE);
    }

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
            AgentDecision secondDecision = secondCall.get(10, TimeUnit.SECONDS);
            assertThat(secondDecision.isApproved()).isFalse();

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

    @Test
    void multiInstanceActivityBridgesEveryVisitNotJustTheFirst() throws IOException {
        AtomicInteger nextAgent = new AtomicInteger();
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true,
                    type + "-agent-0" + nextAgent.incrementAndGet(), "stub catalog", false);
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "loop task test", loopBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), "Task_Loop", "Task_Loop");

        // Initial iteration starts before twin tracking is active; manually bridged.
        AgentDecision firstVisit = workbenchService.bridgeActivityEvent(twin.getId(), "Task_Loop");
        assertThat(firstVisit.isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        assertThat(reached(twin, "EndEvent_1")).isTrue();
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(2);

        assertThat(evolvedAgent(twin, "Task_Loop_0")).isEqualTo("validator-agent-01");
        assertThat(evolvedAgent(twin, "Task_Loop_1")).isEqualTo("validator-agent-02");
        assertThat(agentExecuted(twin, "Task_Loop_0")).isEqualTo("validator-agent-01");
        assertThat(evolvedAgent(twin, "Task_Loop")).isNull();
        assertThat(agentExecuted(twin, "Task_Loop")).isNull();

        // Twin may reach completion before the original; finished twin instances are ignored.
        assertThat(twinAutomation(twin, "Task_Loop_0")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Loop_1")).isNotNull();
        assertThat(twinReached(twin, "EndEvent_1")).isTrue();
        assertThat(agentExecuted(twin, "Task_Loop_1")).isNull();
    }

    // Parallel multi-instance activities require execution-scoped message delivery;
    // correlate() throws when multiple siblings wait on the same message name.
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

        // Initial task starts prior to twin registration; manually bridged.
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), "Task_Gate").isApproved()).isTrue();

        // Completing gate task spawns parallel branches correlated individually via loopCounter.
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openActivities(twin)).containsExactly("Task_Parallel", "Task_Parallel", "Task_Parallel");

        assertThat(twinAutomation(twin, "Task_Parallel_0")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Parallel_1")).isNotNull();
        assertThat(twinAutomation(twin, "Task_Parallel_2")).isNotNull();
        assertThat(evolvedAgent(twin, "Task_Parallel_0")).isNotNull();
        assertThat(evolvedAgent(twin, "Task_Parallel_1")).isNotNull();
        assertThat(evolvedAgent(twin, "Task_Parallel_2")).isNotNull();
        assertThat(List.of(evolvedAgent(twin, "Task_Parallel_0"), evolvedAgent(twin, "Task_Parallel_1"),
                evolvedAgent(twin, "Task_Parallel_2"))).doesNotHaveDuplicates();

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);
        assertThat(reached(twin, "EndEvent_1")).isTrue();
        assertThat(twinReached(twin, "EndEvent_1")).isTrue();
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(4);
    }

    // Calling bridgeActivityEvent without an activity instance ID resolves to the first active sibling.
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

        AgentDecision second = workbenchService.bridgeActivityEvent(twin.getId(), "Task_Parallel");
        assertThat(second.isApproved()).isFalse();
        assertThat(second.getReason()).contains("already forwarded");
        assertThat(governanceService.getUsage(twin.getId()).getTwinExecutionCount()).isEqualTo(usedAfterFirst);
    }

    // Supplying an explicit activityInstanceId bridges each parallel sibling independently.
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

        // Deployments are rolled back if model validation fails post-deployment.
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

    // Queried via HistoryService because completed instances cannot be read from RuntimeService.
    private Object evolvedAgent(TwinProcess twin, String twinActivityId) {
        HistoricVariableInstance variable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .variableName("evolvedAgent_" + twinActivityId)
                .singleResult();
        return variable == null ? null : variable.getValue();
    }

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

    // Queried via HistoryService because completed process instances throw in RuntimeService.
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

    // BPMN fixture for an exclusive-gateway loop-back without multi-instance characteristics.
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

    // Leading task gates parallel execution until after transaction commit.
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

    // Ungated parallel multi-instance fixture testing manual bridge before sibling completion.
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
