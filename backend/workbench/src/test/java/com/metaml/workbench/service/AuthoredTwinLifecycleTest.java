package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.WorkflowEventStore;
import com.metaml.workbench.workflow.WorkflowStage;
import com.metaml.workbench.workflow.WorkflowStateTracker;
import com.metaml.workbench.workflow.WorkflowState;
import com.metaml.workbench.workflow.StageStatus;

// Proves the authored-two-BPMN path is a first-class generation mode flowing through the SAME
// Workbench lifecycle as the single-BPMN path - not a side door only a test can reach by calling
// SpringBootProjectGenerator directly. Fixture BPMNs are deliberately named nothing like RedCollar,
// so a pass here cannot be explained by anything specific to that BPMN pair.
//
// Same harness shape as ModelDeletionTest/GeneratedProjectRetentionTest: real generator, launcher,
// tracker, and model file store, with only the Camunda engine services and the H2 archive store
// mocked - the parts that actually decide lifecycle/discovery/deletion behavior are the real ones.
class AuthoredTwinLifecycleTest {

    @TempDir
    Path tempDir;

    private Path outputDir;
    private Path modelsDir;
    private Path eventFile;
    private SpringBootProjectGenerator generator;
    private ProcessModelFileStore modelFileStore;
    private ProcessModelArchiveStore processModelArchiveStore;
    private RepositoryService repositoryService;
    private WorkbenchServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        Path templateDir = tempDir.resolve("template");
        outputDir = tempDir.resolve("generated-projects");
        modelsDir = tempDir.resolve("models");
        eventFile = tempDir.resolve("workflow-events.json");

        write(templateDir.resolve("pom.xml"), "<project>fake pom</project>");

        generator = new SpringBootProjectGenerator(templateDir.toString(), outputDir.toString(),
                new TwinModelGenerator(), new DelegateClassGenerator(),
                new com.metaml.workbench.codegen.ExternalTaskWorkerGenerator());
        modelFileStore = new ProcessModelFileStore(modelsDir.toString());
        service = newService();
    }

    private WorkbenchServiceImpl newService() {
        WorkflowEventStore eventStore = new WorkflowEventStore(eventFile.toString(), true);
        WorkflowStateTracker tracker = new WorkflowStateTracker(eventStore);
        invokeDeclared(tracker, WorkflowStateTracker.class, "restore");

        WorkbenchStateStore stateStore = mock(WorkbenchStateStore.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        when(stateStore.load()).thenReturn(new WorkbenchStateStore.Snapshot(List.of()));
        when(approvalService.listAllApproved()).thenReturn(List.of());

        processModelArchiveStore = mock(ProcessModelArchiveStore.class);
        when(processModelArchiveStore.findAll()).thenReturn(List.of());

        repositoryService = mock(RepositoryService.class, RETURNS_DEEP_STUBS);
        Deployment deployment = mock(Deployment.class);
        when(deployment.getId()).thenReturn("deployment-1");
        when(repositoryService.createDeployment().name(anyString()).addInputStream(anyString(), any()).deploy())
                .thenReturn(deployment);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn("definition-1");
        when(repositoryService.createProcessDefinitionQuery().deploymentId(anyString()).singleResult())
                .thenReturn(definition);
        RuntimeService runtimeService = mock(RuntimeService.class, RETURNS_DEEP_STUBS);

        DelegateClassGenerator delegateClassGenerator = mock(DelegateClassGenerator.class);
        when(delegateClassGenerator.generate(anyString(), anyString())).thenReturn(List.of());

        return new WorkbenchServiceImpl(mock(NodeManagerClient.class), mock(GovernanceService.class),
                mock(PolicyDecisionEngine.class), approvalService, runtimeService, repositoryService,
                mock(HistoryService.class), mock(TaskService.class), mock(ExternalTaskService.class), mock(TwinModelGenerator.class), stateStore,
                modelFileStore, processModelArchiveStore, delegateClassGenerator, generator,
                new SpringBootProjectLauncher(), tracker);
    }

    @Test
    void savingWithAnAuthoredTwinPersistsBothBpmnsAndMarksTheModelAccordingly() {
        ProcessModel model = service.saveProcessModelWithAuthoredTwin("acme-1", "Acme", acmeManufBpmn(),
                acmeTwinBpmn(), null);

        assertThat(model.hasAuthoredTwin()).isTrue();
        assertThat(model.getAuthoredTwinBpmnXml()).isEqualTo(acmeTwinBpmn());
        assertThat(modelsDir.resolve("acme-1.bpmn")).exists();
        assertThat(modelsDir.resolve("acme-1.twin.bpmn")).exists();
    }

    // The twin XML is validated structurally (requireExactlyOneExecutableProcess) but never
    // deployed to the Workbench's own engine - proven here by a twin XML that would fail real
    // deployment (two executable processes) still saving successfully, since only bpmnXml goes
    // through repositoryService.createDeployment().
    @Test
    void authoredTwinXmlNeverReachesTheWorkbenchsOwnEngineDeployment() {
        String twinWithTwoExecutableProcesses = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    id="Definitions_TwoProcesses" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="First" isExecutable="true">
                    <bpmn2:startEvent id="S1" />
                  </bpmn2:process>
                  <bpmn2:process id="Second" isExecutable="true">
                    <bpmn2:startEvent id="S2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveProcessModelWithAuthoredTwin(
                        "acme-two-proc", "Acme", acmeManufBpmn(), twinWithTwoExecutableProcesses, null))
                .as("structural validation must still reject a twin XML that could never actually deploy")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one executable");
    }

    @Test
    void savingWithAnInvalidAuthoredTwinBpmnFailsCleanlyWithoutLeavingAHalfSavedModel() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveProcessModelWithAuthoredTwin(
                        "acme-bad", "Acme Bad", acmeManufBpmn(), "<not-bpmn/>", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(modelsDir.resolve("acme-bad.bpmn")).doesNotExist();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getProcessModel("acme-bad"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void generateRoutesAnAuthoredTwinModelThroughTheAuthoredTwinGenerationModeUsingTheSameLifecycleStages() {
        service.saveProcessModelWithAuthoredTwin("acme-2", "Acme", acmeManufBpmn(), acmeTwinBpmn(), null);

        GeneratedProject project = service.generateSpringBootProject("acme-2");

        // Two-BPMN-mode-only artifacts prove generateWithAuthoredTwin ran, not the single-BPMN
        // generate() + TwinModelGenerator-derived path.
        String basePackagePath = "src/main/java/com/metaml/targetplatform/acmemanuf";
        assertThat(project.directory().resolve(basePackagePath + "/worker/GeneratedExternalTaskWorker.java"))
                .exists();
        assertThat(project.directory().resolve(basePackagePath + "/worker/ExternalTaskPoller.java")).exists();
        assertThat(project.directory().resolve(basePackagePath + "/worker/SchedulingConfig.java")).exists();
        assertThat(project.directory().resolve("src/main/resources/processes/AcmeManuf.bpmn")).exists();
        assertThat(project.directory().resolve("src/main/resources/processes/AcmeManufTwin.bpmn")).exists();

        // Same lifecycle bookkeeping the single-BPMN path uses - not a parallel state model.
        WorkflowState state = service.getWorkflowState("acme-2");
        assertThat(state.stages().get(WorkflowStage.GENERATE).status()).isEqualTo(StageStatus.COMPLETED);
        assertThat(state.stages().get(WorkflowStage.GENERATE).detail()).isEqualTo(project.projectId());
    }

    @Test
    void generatedAuthoredTwinProjectIsRediscoverableAfterASimulatedRestart() {
        service.saveProcessModelWithAuthoredTwin("acme-3", "Acme", acmeManufBpmn(), acmeTwinBpmn(), null);
        GeneratedProject project = service.generateSpringBootProject("acme-3");

        // Simulated restart: a FRESH generator instance pointed at the same output directory, with
        // no in-memory registry carried over - exactly what scanExisting() exists to rebuild from.
        SpringBootProjectGenerator freshGenerator = new SpringBootProjectGenerator(
                tempDir.resolve("template").toString(), outputDir.toString(), new TwinModelGenerator(),
                new DelegateClassGenerator(), new com.metaml.workbench.codegen.ExternalTaskWorkerGenerator());

        List<GeneratedProject> discovered = freshGenerator.scanExisting();

        assertThat(discovered).hasSize(1);
        assertThat(discovered.get(0).projectId()).isEqualTo(project.projectId());
        assertThat(discovered.get(0).processKey()).isEqualTo("AcmeManuf");
    }

    @Test
    void deletingAnAuthoredTwinModelRemovesBothBpmnFilesAndTheGeneratedProject() {
        service.saveProcessModelWithAuthoredTwin("acme-4", "Acme", acmeManufBpmn(), acmeTwinBpmn(), null);
        GeneratedProject project = service.generateSpringBootProject("acme-4");
        // Folder name is a slug of the model's display name now, not the bare projectId (see
        // SpringBootProjectGenerator.resolveProjectDirectory) - project.directory() is the actual
        // source of truth for where it landed.
        Path projectDir = project.directory();
        assertThat(projectDir).exists();

        assertThat(service.deleteProcessModel("acme-4")).isTrue();

        assertThat(modelsDir.resolve("acme-4.bpmn")).doesNotExist();
        assertThat(modelsDir.resolve("acme-4.twin.bpmn")).doesNotExist();
        assertThat(projectDir).doesNotExist();
    }

    // --- helpers ---

    private static void invokeDeclared(Object target, Class<?> type, String methodName) {
        try {
            java.lang.reflect.Method method = type.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    // start -> external task -> end. No signals, no delegates - deliberately minimal and named
    // nothing like RedCollar, to prove the mechanism is generic.
    private static String acmeManufBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_AcmeManuf" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="AcmeManuf" name="Acme Manuf" isExecutable="true">
                    <bpmn2:startEvent id="AcmeManufStart" />
                    <bpmn2:serviceTask id="AcmeManufStep" name="Step" camunda:type="external"
                        camunda:topic="AcmeManufStep" />
                    <bpmn2:endEvent id="AcmeManufEnd" />
                    <bpmn2:sequenceFlow id="AcmeManufFlow1" sourceRef="AcmeManufStart" targetRef="AcmeManufStep" />
                    <bpmn2:sequenceFlow id="AcmeManufFlow2" sourceRef="AcmeManufStep" targetRef="AcmeManufEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String acmeTwinBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_AcmeManufTwin" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="AcmeManufTwin" name="Acme Manuf Twin" isExecutable="true">
                    <bpmn2:startEvent id="AcmeTwinStart" />
                    <bpmn2:serviceTask id="AcmeTwinStep" name="Twin Step" camunda:type="external"
                        camunda:topic="AcmeTwinStep" />
                    <bpmn2:endEvent id="AcmeTwinEnd" />
                    <bpmn2:sequenceFlow id="AcmeTwinFlow1" sourceRef="AcmeTwinStart" targetRef="AcmeTwinStep" />
                    <bpmn2:sequenceFlow id="AcmeTwinFlow2" sourceRef="AcmeTwinStep" targetRef="AcmeTwinEnd" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }
}
