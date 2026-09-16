package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.StageEvent;
import com.metaml.workbench.workflow.StageStatus;
import com.metaml.workbench.workflow.WorkflowEventStore;
import com.metaml.workbench.workflow.WorkflowStage;
import com.metaml.workbench.workflow.WorkflowStateTracker;

/**
 * Saving a model again under the id the editor already holds is an edit of that model - one entry in the
 * catalogue, a new process-definition version behind it - not a second model. Deleted ids stay retired
 * (see ModelDeletionTest); this covers the live-id path only.
 */
class ModelUpdateInPlaceTest {

    @TempDir
    Path tempDir;

    private ProcessModelArchiveStore processModelArchiveStore;
    private RepositoryService repositoryService;
    private WorkbenchServiceImpl service;
    // each deploy() gets its own deployment/definition id, so the test can tell the versions apart
    private final AtomicInteger deployments = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        Path templateDir = tempDir.resolve("template");
        write(templateDir.resolve("pom.xml"), "<project>fake pom</project>");
        write(templateDir.resolve("src/main/resources/processes/loanApproval.bpmn"), "<bpmn>placeholder</bpmn>");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(templateDir.toString(),
                tempDir.resolve("generated-projects").toString(), new TwinModelGenerator(),
                new DelegateClassGenerator(), new com.metaml.workbench.codegen.ExternalTaskWorkerGenerator());
        ProcessModelFileStore modelFileStore = new ProcessModelFileStore(tempDir.resolve("models").toString());

        WorkflowEventStore eventStore = new WorkflowEventStore(tempDir.resolve("workflow-events.json").toString(), true);
        WorkflowStateTracker tracker = new WorkflowStateTracker(eventStore);

        WorkbenchStateStore stateStore = mock(WorkbenchStateStore.class);
        when(stateStore.load()).thenReturn(new WorkbenchStateStore.Snapshot(List.of()));
        ApprovalService approvalService = mock(ApprovalService.class);
        when(approvalService.listAllApproved()).thenReturn(List.of());
        processModelArchiveStore = mock(ProcessModelArchiveStore.class);
        when(processModelArchiveStore.findAll()).thenReturn(List.of());

        repositoryService = mock(RepositoryService.class, RETURNS_DEEP_STUBS);
        when(repositoryService.createDeployment().name(anyString()).addInputStream(anyString(), any()).deploy())
                .thenAnswer(invocation -> {
                    int n = deployments.incrementAndGet();
                    Deployment deployment = mock(Deployment.class);
                    when(deployment.getId()).thenReturn("deployment-" + n);
                    return deployment;
                });
        when(repositoryService.createProcessDefinitionQuery().deploymentId(anyString()).singleResult())
                .thenAnswer(invocation -> {
                    ProcessDefinition definition = mock(ProcessDefinition.class);
                    when(definition.getId()).thenReturn("definition-" + deployments.get());
                    return definition;
                });

        DelegateClassGenerator delegateClassGenerator = mock(DelegateClassGenerator.class);
        when(delegateClassGenerator.generate(anyString(), anyString())).thenReturn(List.of());

        service = new WorkbenchServiceImpl(mock(NodeManagerClient.class), mock(GovernanceService.class),
                mock(PolicyDecisionEngine.class), approvalService, mock(RuntimeService.class, RETURNS_DEEP_STUBS),
                repositoryService, mock(HistoryService.class), mock(TaskService.class), mock(ExternalTaskService.class),
                mock(TwinModelGenerator.class), stateStore, modelFileStore, processModelArchiveStore,
                delegateClassGenerator, generator, new SpringBootProjectLauncher(), tracker);
    }

    @Test
    void savingAgainUnderTheSameIdUpdatesThatModelInsteadOfAddingASecondOne() {
        ProcessModel first = service.saveProcessModel(null, "New Process", loanApprovalBpmn("New Process"), null, 7L);
        String modelId = first.getId();

        ProcessModel updated = service.saveProcessModel(modelId, "ProcessSample", loanApprovalBpmn("ProcessSample"),
                null, 7L);

        assertThat(updated.getId()).isEqualTo(modelId);
        assertThat(updated.getName()).isEqualTo("ProcessSample");
        assertThat(updated.getBpmnXml()).contains("name=\"ProcessSample\"");
        assertThat(updated.getCreatedAt()).as("an edit keeps the original creation time").isEqualTo(first.getCreatedAt());
        assertThat(service.listProcessModels()).extracting(ProcessModel::getId).containsExactly(modelId);
        assertThat(service.getProcessModel(modelId).getName()).isEqualTo("ProcessSample");
    }

    @Test
    void anUpdateDeploysANewDefinitionVersionAndKeepsTheOldDeployment() {
        ProcessModel first = service.saveProcessModel(null, "New Process", loanApprovalBpmn("New Process"), null, 7L);
        String modelId = first.getId();

        ProcessModel updated = service.saveProcessModel(modelId, "ProcessSample", loanApprovalBpmn("ProcessSample"),
                null, 7L);

        assertThat(first.getProcessDefinitionId()).isEqualTo("definition-1");
        assertThat(updated.getProcessDefinitionId()).isEqualTo("definition-2");
        // twins built from the first version still point at deployment-1's definition - it must survive the edit
        verify(repositoryService, never()).deleteDeployment(eq("deployment-1"), any(Boolean.class));
    }

    @Test
    void anUpdateWritesTheArchiveRowUnderTheSameModelIdWithTheNewProject() {
        ProcessModel first = service.saveProcessModel(null, "New Process", loanApprovalBpmn("New Process"), null, 7L);
        String modelId = first.getId();
        service.saveProcessModel(modelId, "ProcessSample", loanApprovalBpmn("ProcessSample"), null, 8L);

        ArgumentCaptor<ProcessModel> saved = ArgumentCaptor.forClass(ProcessModel.class);
        ArgumentCaptor<Long> projectId = ArgumentCaptor.forClass(Long.class);
        verify(processModelArchiveStore, times(2)).save(saved.capture(), any(), isNull(), projectId.capture());
        assertThat(saved.getAllValues()).extracting(ProcessModel::getId).containsExactly(modelId, modelId);
        assertThat(saved.getAllValues().get(1).getName()).isEqualTo("ProcessSample");
        assertThat(projectId.getAllValues()).containsExactly(7L, 8L);
    }

    @Test
    void anUpdateResetsGenerateAndLaunchWhileKeepingEarlierHistory() {
        ProcessModel first = service.saveProcessModel(null, "New Process", loanApprovalBpmn("New Process"), null, 7L);
        String modelId = first.getId();
        service.generateSpringBootProject(modelId);

        service.saveProcessModel(modelId, "ProcessSample", loanApprovalBpmn("ProcessSample"), null, 7L);

        List<StageEvent> history = service.getWorkflowState(modelId).history();
        assertThat(history).filteredOn(e -> e.stage() == WorkflowStage.MODEL && e.status() == StageStatus.COMPLETED)
                .hasSize(2);
        assertThat(history).filteredOn(e -> e.stage() == WorkflowStage.GENERATE && e.status() == StageStatus.COMPLETED)
                .as("the earlier generation stays on record").hasSize(1);
        // ...but no longer counts: the edit restarted the pipeline, so the pickers show the model as needing
        // generation again rather than offering the stale generation
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).status())
                .isEqualTo(StageStatus.PENDING);
        assertThat(service.getWorkflowState(modelId).currentStage()).isEqualTo(WorkflowStage.GENERATE);
    }

    @Test
    void generatingAgainAfterAnUpdateCompletesGenerateNormallyAndRetiresTheStaleGeneration() {
        ProcessModel first = service.saveProcessModel(null, "New Process", loanApprovalBpmn("New Process"), null, 7L);
        String modelId = first.getId();
        com.metaml.workbench.generation.GeneratedProject stale = service.generateSpringBootProject(modelId);
        service.saveProcessModel(modelId, "ProcessSample", loanApprovalBpmn("ProcessSample"), null, 7L);
        assertThat(stale.directory()).as("an edit alone keeps the stale generation on disk").exists();

        com.metaml.workbench.generation.GeneratedProject fresh = service.generateSpringBootProject(modelId);

        assertThat(fresh.projectId()).isNotEqualTo(stale.projectId());
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).status())
                .isEqualTo(StageStatus.COMPLETED);
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).detail())
                .isEqualTo(fresh.projectId());
        assertThat(fresh.directory()).exists();
        assertThat(stale.directory()).as("the generation from the older version is collected once superseded")
                .doesNotExist();
    }

    @Test
    void aRejectedUpdateLeavesThePreviousVersionInPlace() {
        ProcessModel first = service.saveProcessModel(null, "New Process", loanApprovalBpmn("New Process"), null, 7L);
        String modelId = first.getId();
        when(repositoryService.createProcessDefinitionQuery().deploymentId(anyString()).singleResult())
                .thenReturn(null);

        assertThatThrownBy(() -> service.saveProcessModel(modelId, "Broken", loanApprovalBpmn("Broken"), null, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isExecutable");

        ProcessModel kept = service.getProcessModel(modelId);
        assertThat(kept.getName()).isEqualTo("New Process");
        assertThat(kept.getProcessDefinitionId()).isEqualTo("definition-1");
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.MODEL).status())
                .isEqualTo(StageStatus.FAILED);
    }

    @Test
    void unknownSuppliedIdIsRejected() {
        assertThatThrownBy(() -> service.saveProcessModel("unknown-id", "Broken", loanApprovalBpmn("Broken"), null, 7L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Process model not found: unknown-id");
    }

    @Test
    void fullLifecycleModelSaveGenerateResaveRegenerateContract() {
        // 1. Save new model -> Not Generated
        ProcessModel model = service.saveProcessModel(null, "Process A", loanApprovalBpmn("Process A"), null, 7L);
        String modelId = model.getId();
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).status())
                .as("freshly saved model must be Not Generated (PENDING)").isEqualTo(StageStatus.PENDING);
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).detail())
                .isNull();

        // 2. Generate model -> Generated
        com.metaml.workbench.generation.GeneratedProject gen1 = service.generateSpringBootProject(modelId);
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).status())
                .as("generated model must be Generated (COMPLETED)").isEqualTo(StageStatus.COMPLETED);
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).detail())
                .isEqualTo(gen1.projectId());

        // 3. Reload/query again -> Generated remains
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).status())
                .as("querying again without edits preserves Generated state").isEqualTo(StageStatus.COMPLETED);

        // 4. Modify BPMN XML and save SAME model ID -> status becomes Not Generated / stale
        service.saveProcessModel(modelId, "Process A (modified)", loanApprovalBpmn("Process A (modified)"), null, 7L);
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).status())
                .as("saving modifications invalidates previous generation back to PENDING").isEqualTo(StageStatus.PENDING);
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).detail())
                .as("detail is cleared so pickers report ungenerated").isNull();

        // 5. Reload/query Generate state -> still Not Generated / stale
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).status())
                .as("reloading state still shows Not Generated").isEqualTo(StageStatus.PENDING);

        // 6. Regenerate -> Generated
        com.metaml.workbench.generation.GeneratedProject gen2 = service.generateSpringBootProject(modelId);
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).status())
                .as("regenerated model becomes Generated again").isEqualTo(StageStatus.COMPLETED);
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).detail())
                .isEqualTo(gen2.projectId());
        assertThat(gen2.projectId()).isNotEqualTo(gen1.projectId());

        // 7. Save again without creating duplicate logical model -> same model ID
        ProcessModel savedAgain = service.saveProcessModel(modelId, "Process A (final)", loanApprovalBpmn("Process A (final)"), null, 7L);
        assertThat(savedAgain.getId()).isEqualTo(modelId);
        assertThat(service.listProcessModels()).extracting(ProcessModel::getId).containsExactly(modelId);
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).status())
                .isEqualTo(StageStatus.PENDING);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String loanApprovalBpmn(String processName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="loanApproval" name="%s" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:userTask id="Review" name="Review" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(processName);
    }
}
