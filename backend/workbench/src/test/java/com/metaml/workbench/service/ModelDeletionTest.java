package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.LaunchedProject;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.governance.Approval;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.ApprovalStatus;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.StageEvent;
import com.metaml.workbench.workflow.StageStatus;
import com.metaml.workbench.workflow.WorkflowEventStore;
import com.metaml.workbench.workflow.WorkflowStage;
import com.metaml.workbench.workflow.WorkflowStateTracker;

/**
 * Tests deletion lifecycle semantics and isolation for process models and generated projects.
 */
class ModelDeletionTest {

    private static final String FAKE_LISTENER_SCRIPT = """
            @echo off
            powershell -NoProfile -Command "$l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, [int]$env:SERVER_PORT); $l.Start(); Start-Sleep -Seconds 300; $l.Stop()"
            """;

    @TempDir
    Path tempDir;

    private Path outputDir;
    private Path modelsDir;
    private Path eventFile;
    private SpringBootProjectGenerator generator;
    private SpringBootProjectLauncher launcher;
    private ProcessModelFileStore modelFileStore;
    private WorkbenchStateStore stateStore;
    private ProcessModelArchiveStore processModelArchiveStore;
    private ApprovalService approvalService;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private WorkbenchServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        Path templateDir = tempDir.resolve("template");
        outputDir = tempDir.resolve("generated-projects");
        modelsDir = tempDir.resolve("models");
        eventFile = tempDir.resolve("workflow-events.json");

        write(templateDir.resolve("pom.xml"), "<project>fake pom</project>");
        write(templateDir.resolve("src/main/resources/processes/loanApproval.bpmn"), "<bpmn>placeholder</bpmn>");

        generator = new SpringBootProjectGenerator(templateDir.toString(), outputDir.toString(),
                new TwinModelGenerator(), new DelegateClassGenerator(),
                new com.metaml.workbench.codegen.ExternalTaskWorkerGenerator());
        launcher = new SpringBootProjectLauncher();
        modelFileStore = new ProcessModelFileStore(modelsDir.toString());
        service = newService(newTracker(), List.of(), List.of());
    }

    private WorkflowStateTracker newTracker() {
        WorkflowEventStore eventStore = new WorkflowEventStore(eventFile.toString(), true);
        WorkflowStateTracker tracker = new WorkflowStateTracker(eventStore);
        invokeDeclared(tracker, WorkflowStateTracker.class, "restore");
        return tracker;
    }

    private WorkbenchServiceImpl newService(WorkflowStateTracker tracker, List<ProcessModel> models,
            List<TwinProcess> twins) {
        stateStore = mock(WorkbenchStateStore.class);
        approvalService = mock(ApprovalService.class);
        when(stateStore.load()).thenReturn(new WorkbenchStateStore.Snapshot(twins));
        processModelArchiveStore = mock(ProcessModelArchiveStore.class);
        when(processModelArchiveStore.findAll()).thenReturn(models);
        when(approvalService.listAllApproved()).thenReturn(List.of());

        repositoryService = mock(RepositoryService.class, RETURNS_DEEP_STUBS);
        Deployment deployment = mock(Deployment.class);
        when(deployment.getId()).thenReturn("deployment-1");
        when(repositoryService.createDeployment().name(anyString()).addInputStream(anyString(), any()).deploy())
                .thenReturn(deployment);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn("definition-1");
        when(repositoryService.createProcessDefinitionQuery().deploymentId(anyString()).singleResult())
                .thenReturn(definition);
        runtimeService = mock(RuntimeService.class, RETURNS_DEEP_STUBS);

        DelegateClassGenerator delegateClassGenerator = mock(DelegateClassGenerator.class);
        when(delegateClassGenerator.generate(anyString(), anyString())).thenReturn(List.of());

        return new WorkbenchServiceImpl(mock(NodeManagerClient.class), mock(GovernanceService.class),
                mock(PolicyDecisionEngine.class), approvalService, runtimeService, repositoryService,
                mock(HistoryService.class), mock(TaskService.class), mock(ExternalTaskService.class), mock(TwinModelGenerator.class), stateStore,
                modelFileStore, processModelArchiveStore, delegateClassGenerator, generator, launcher, tracker);
    }

    private String saveModel(String name) {
        ProcessModel model = service.saveProcessModel(null, name, loanApprovalBpmn(), null);
        return model.getId();
    }

    private Path directoryOf(GeneratedProject project) {
        // Folder name is a slug of the model's display name now, not the bare projectId (see
        // SpringBootProjectGenerator.resolveProjectDirectory) - project.directory() is the actual
        // source of truth for where it landed.
        return project.directory();
    }

    private Path bpmnFileOf(String modelId) {
        return modelsDir.resolve(modelId + ".bpmn");
    }

    private LaunchedProject launch(GeneratedProject project) throws IOException {
        Files.writeString(directoryOf(project).resolve("mvnw.cmd"), FAKE_LISTENER_SCRIPT, StandardCharsets.UTF_8);
        return service.launchGeneratedProject(project.projectId());
    }

    @Test
    void deletingAModelRemovesTheModelRecordAndItsBpmnArtifact() {
        String modelId = saveModel("m1");
        assertThat(bpmnFileOf(modelId)).exists();

        assertThat(service.deleteProcessModel(modelId)).isTrue();

        assertThat(bpmnFileOf(modelId)).doesNotExist();
        assertThatThrownBy(() -> service.getProcessModel(modelId)).isInstanceOf(NoSuchElementException.class);
        assertThat(service.listProcessModels()).isEmpty();
    }

    @Test
    void deletingAModelRemovesEveryGenerationItEverProducedNotJustTheLatest() {
        String modelId = saveModel("m1");
        GeneratedProject first = service.generateSpringBootProject(modelId);
        GeneratedProject second = service.generateSpringBootProject(modelId);
        // retention already collected the first one; regenerate again so there are two live
        // directories at the moment of deletion, not one
        GeneratedProject third = service.generateSpringBootProject(modelId);
        assertThat(directoryOf(third)).exists();

        service.deleteProcessModel(modelId);

        assertThat(directoryOf(first)).doesNotExist();
        assertThat(directoryOf(second)).doesNotExist();
        assertThat(directoryOf(third)).doesNotExist();
        assertThat(generator.scanExisting()).isEmpty();
    }

    @Test
    void deletingAModelThatWasNeverGeneratedIsNotRefusedForLackOfProjectsToCheck() {
        String modelId = saveModel("m1");

        assertThat(service.deleteProcessModel(modelId)).isTrue();
        assertThat(service.listProcessModels()).isEmpty();
    }

    @Test
    void deletingAModelThatDoesNotExistIsAClearNotFound() {
        assertThatThrownBy(() -> service.deleteProcessModel("never-existed"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("never-existed");
    }

    @Test
    void deletionIsRefusedWhileTheModelsGeneratedAppIsRunningAndTheAppIsLeftAlone() throws IOException {
        String modelId = saveModel("m1");
        GeneratedProject project = service.generateSpringBootProject(modelId);
        LaunchedProject running = launch(project);

        assertThatThrownBy(() -> service.deleteProcessModel(modelId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("running");

        // refused means nothing happened - not "mostly nothing"
        assertThat(service.getProcessModel(modelId)).isNotNull();
        assertThat(bpmnFileOf(modelId)).exists();
        assertThat(directoryOf(project)).exists();
        assertThat(isListening(running.port())).as("deleting a model must never kill a running app").isTrue();
        assertThat(service.listRunningProjects()).extracting(LaunchedProject::projectId)
                .containsExactly(project.projectId());

        service.stopGeneratedProject(project.projectId());
    }

    @Test
    void deletionSucceedsOnceTheRunningAppHasBeenStopped() throws IOException {
        String modelId = saveModel("m1");
        GeneratedProject project = service.generateSpringBootProject(modelId);
        launch(project);
        assertThatThrownBy(() -> service.deleteProcessModel(modelId)).isInstanceOf(IllegalStateException.class);

        service.stopGeneratedProject(project.projectId());

        assertThat(service.deleteProcessModel(modelId)).isTrue();
        assertThat(directoryOf(project)).doesNotExist();
        assertThat(bpmnFileOf(modelId)).doesNotExist();
    }

    // The in-flight case find() alone cannot answer: for the whole startup window a launching
    // project is not yet in the running registry, so deletion would see "idle" and delete the
    // directory out from under a booting JVM. Deletion takes the same per-project launch lock.
    @Test
    void deletionIsRefusedWhileALaunchIsStillInFlight() throws Exception {
        String modelId = saveModel("m1");
        GeneratedProject project = service.generateSpringBootProject(modelId);
        Files.writeString(directoryOf(project).resolve("mvnw.cmd"), """
                @echo off
                powershell -NoProfile -Command "Start-Sleep -Seconds 6; $l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, [int]$env:SERVER_PORT); $l.Start(); Start-Sleep -Seconds 300; $l.Stop()"
                """, StandardCharsets.UTF_8);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<LaunchedProject> launching = pool.submit(
                    () -> service.launchGeneratedProject(project.projectId()));
            sleep(2000);
            assertThat(service.listRunningProjects()).as("not listening yet, so not 'running'").isEmpty();

            assertThatThrownBy(() -> service.deleteProcessModel(modelId))
                    .as("must refuse while a launch is in flight, even though nothing reads as running")
                    .isInstanceOf(IllegalStateException.class);

            assertThat(directoryOf(project)).exists();
            assertThat(service.getProcessModel(modelId)).isNotNull();
            LaunchedProject launched = launching.get(90, TimeUnit.SECONDS);
            assertThat(isListening(launched.port())).isTrue();
        } finally {
            pool.shutdownNow();
            service.stopGeneratedProject(project.projectId());
        }
    }

    @Test
    void deletingOneModelLeavesAnotherModelAndItsProjectsCompletelyUntouched() {
        String doomed = saveModel("m1");
        String keeper = saveModel("m2");
        GeneratedProject doomedProject = service.generateSpringBootProject(doomed);
        GeneratedProject keeperProject = service.generateSpringBootProject(keeper);

        service.deleteProcessModel(doomed);

        assertThat(directoryOf(doomedProject)).doesNotExist();
        assertThat(directoryOf(keeperProject)).exists();
        assertThat(bpmnFileOf(keeper)).exists();
        assertThat(service.listProcessModels()).extracting(ProcessModel::getId).containsExactly(keeper);
        assertThat(service.getWorkflowState(keeper).stages().get(WorkflowStage.GENERATE).detail())
                .isEqualTo(keeperProject.projectId());
    }

    @Test
    void workflowHistorySurvivesDeletionIntact() {
        String modelId = saveModel("m1");
        GeneratedProject project = service.generateSpringBootProject(modelId);

        service.deleteProcessModel(modelId);

        List<StageEvent> history = service.getWorkflowState(modelId).history();
        assertThat(history).as("history is deliberately kept - it is what retires the id").isNotEmpty();
        assertThat(history).anyMatch(e -> e.stage() == WorkflowStage.MODEL
                && e.status() == StageStatus.COMPLETED);
        assertThat(history).anyMatch(e -> e.stage() == WorkflowStage.GENERATE
                && e.status() == StageStatus.COMPLETED && project.projectId().equals(e.detail()));
    }

    @Test
    void aTwinSurvivesTheDeletionOfTheModelItCameFrom() {
        String modelId = saveModel("m1");
        ProcessModel savedModel = service.getProcessModel(modelId);

        TwinProcess twin = new TwinProcess();
        twin.setId("twin-1");
        twin.setModelId(modelId);
        twin.setProcessDefinitionId("definition-1");
        twin.setTwinProcessDefinitionId("definition-1-twin");
        twin.setOriginalProcessId("original-instance-1");
        twin.setTwinProcessId("twin-instance-1");
        twin.setTenantId("acme");
        service = newService(newTracker(), List.of(savedModel), List.of(twin));
        service.restoreState();

        service.deleteProcessModel(modelId);

        TwinProcess survivor = service.findTwinProcess("twin-1");
        assertThat(survivor).as("model -> twin is provenance, not ownership").isNotNull();
        assertThat(survivor.getModelId()).isEqualTo(modelId);
        assertThat(survivor.getTwinProcessId()).isEqualTo("twin-instance-1");
        assertThat(survivor.getTenantId()).as("tenant ownership is untouched").isEqualTo("acme");
    }

    // Camunda deployments and process instances must not be deleted when a model is deleted.
    @Test
    void deletionNeverTouchesCamundaDeploymentsOrProcessInstances() {
        String modelId = saveModel("m1");
        service.generateSpringBootProject(modelId);

        service.deleteProcessModel(modelId);

        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());
        verify(repositoryService, never()).deleteDeployment(anyString());
        verify(runtimeService, never()).deleteProcessInstance(anyString(), anyString());
    }

    @Test
    void deletionNeverResolvesOrModifiesApprovals() {
        Approval approval = Approval.pending("a1", "acme", "twin-1", "Review", "Review", null, "validator",
                "EVOLVE_TWIN", "p1", "v1", 1, "r1", "needs approval", Instant.now());
        when(approvalService.listForTenant("acme")).thenReturn(List.of(approval));
        String modelId = saveModel("m1");

        service.deleteProcessModel(modelId);

        assertThat(service.listApprovals("acme")).containsExactly(approval);
        assertThat(service.listApprovals("acme").get(0).status()).isEqualTo(ApprovalStatus.PENDING);
        verify(approvalService, never()).markFailed(anyString(), anyString());
        verify(approvalService, never()).markRejected(anyString(), anyString());
        verify(approvalService, never()).markCompleted(anyString(), anyString());
    }

    @Test
    void aDeletedModelIdCannotBeRecreated() {
        String modelId = saveModel("m1");
        service.generateSpringBootProject(modelId);
        service.deleteProcessModel(modelId);

        assertThatThrownBy(() -> service.saveProcessModel(modelId, "m1 again", loanApprovalBpmn(), null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Process model not found: " + modelId);
    }

    @Test
    void aDeletedModelIdStaysRetiredAcrossARestart() {
        String modelId = saveModel("m1");
        service.deleteProcessModel(modelId);

        WorkbenchServiceImpl restarted = newService(newTracker(), List.of(), List.of());
        restarted.restoreState();

        assertThat(restarted.listProcessModels()).as("a deleted model must not come back").isEmpty();
        assertThatThrownBy(() -> restarted.saveProcessModel(modelId, "m1 again", loanApprovalBpmn(), null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Process model not found: " + modelId);
    }

    @Test
    void adifferentNewModelIdStillWorksNormallyAfterADeletion() {
        service.deleteProcessModel(saveModel("m1"));

        ProcessModel fresh = service.saveProcessModel(null, "m2", loanApprovalBpmn(), null);

        assertThat(fresh.getId()).isNotBlank();
        assertThat(bpmnFileOf(fresh.getId())).exists();
        GeneratedProject project = service.generateSpringBootProject(fresh.getId());
        assertThat(directoryOf(project)).exists();
    }

    @Test
    void anAutoGeneratedModelIdStillWorksAfterADeletion() {
        service.deleteProcessModel(saveModel("m1"));

        ProcessModel fresh = service.saveProcessModel(null, "auto", loanApprovalBpmn(), null);

        assertThat(fresh.getId()).isNotBlank();
        assertThat(service.listProcessModels()).extracting(ProcessModel::getId).containsExactly(fresh.getId());
    }

    // Model creation whose first save failed validation can be retried.
    @Test
    void anIdWhoseFirstSaveFailedValidationCanStillBeRetried() {
        // Mocks missing process definition to assert doSaveProcessModel rejects non-executable models.
        when(repositoryService.createProcessDefinitionQuery().deploymentId(anyString()).singleResult())
                .thenReturn(null)
                .thenReturn(executableDefinition());

        assertThatThrownBy(() -> service.saveProcessModel(null, "m1", loanApprovalBpmn(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isExecutable");

        ProcessModel retried = service.saveProcessModel(null, "m1", loanApprovalBpmn(), null);

        assertThat(retried.getId()).isNotBlank();
        assertThat(bpmnFileOf(retried.getId())).exists();
    }

    private static ProcessDefinition executableDefinition() {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn("definition-1");
        return definition;
    }

    @Test
    void supersededGenerationsAreStillCollectedOnRegenerateAfterThisChange() {
        String modelId = saveModel("m1");
        GeneratedProject first = service.generateSpringBootProject(modelId);

        GeneratedProject second = service.generateSpringBootProject(modelId);

        assertThat(generator.scanExisting()).extracting(GeneratedProject::projectId)
                .containsExactly(second.projectId());
        assertThat(directoryOf(second)).exists();
    }

    private static void invokeDeclared(Object target, Class<?> type, String methodName) {
        try {
            java.lang.reflect.Method method = type.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isListening(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String loanApprovalBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="loanApproval" name="Loan Approval" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:userTask id="Review" name="Review" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }
}
