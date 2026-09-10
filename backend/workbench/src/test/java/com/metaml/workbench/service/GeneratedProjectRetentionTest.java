package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.NoSuchElementException;

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
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.StageEvent;
import com.metaml.workbench.workflow.StageStatus;
import com.metaml.workbench.workflow.WorkflowEventStore;
import com.metaml.workbench.workflow.WorkflowStage;
import com.metaml.workbench.workflow.WorkflowStateTracker;

/**
 * Tests lifecycle retention policies for generated projects.
 */
class GeneratedProjectRetentionTest {

    // same trick as SpringBootProjectLauncherTest: a raw TCP listener on the port the launcher
    // hands it, which is all launch() actually waits for
    private static final String FAKE_LISTENER_SCRIPT = """
            @echo off
            powershell -NoProfile -Command "$l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, [int]$env:SERVER_PORT); $l.Start(); Start-Sleep -Seconds 300; $l.Stop()"
            """;

    @TempDir
    Path tempDir;

    private Path outputDir;
    private Path eventFile;
    private SpringBootProjectGenerator generator;
    private SpringBootProjectLauncher launcher;
    private WorkbenchStateStore stateStore;
    private ProcessModelArchiveStore processModelArchiveStore;
    private ApprovalService approvalService;
    private WorkbenchServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        Path templateDir = tempDir.resolve("template");
        outputDir = tempDir.resolve("generated-projects");
        eventFile = tempDir.resolve("workflow-events.json");

        // minimum shape the generator needs to copy and rewrite
        write(templateDir.resolve("pom.xml"), "<project>fake pom</project>");
        write(templateDir.resolve("src/main/resources/processes/loanApproval.bpmn"), "<bpmn>placeholder</bpmn>");

        generator = new SpringBootProjectGenerator(templateDir.toString(), outputDir.toString(),
                new TwinModelGenerator(), new DelegateClassGenerator(),
                new com.metaml.workbench.codegen.ExternalTaskWorkerGenerator());
        launcher = new SpringBootProjectLauncher();
        service = newService(newTracker());
    }

    // a fresh tracker reading the same on-disk event file - what a real restart produces
    private WorkflowStateTracker newTracker() {
        WorkflowEventStore eventStore = new WorkflowEventStore(eventFile.toString(), true);
        WorkflowStateTracker tracker = new WorkflowStateTracker(eventStore);
        // restore() is @PostConstruct and package-private on purpose - a test that builds the bean
        // by hand has to invoke it the way Spring would. Reflection rather than widening production
        // visibility for a test's convenience.
        invokeDeclared(tracker, WorkflowStateTracker.class, "restore");
        return tracker;
    }

    private WorkbenchServiceImpl newService(WorkflowStateTracker tracker) {
        stateStore = mock(WorkbenchStateStore.class);
        approvalService = mock(ApprovalService.class);
        when(stateStore.load()).thenReturn(new WorkbenchStateStore.Snapshot(List.of()));
        processModelArchiveStore = mock(ProcessModelArchiveStore.class);
        when(processModelArchiveStore.findAll()).thenReturn(List.of());
        when(approvalService.listAllApproved()).thenReturn(List.of());

        RepositoryService repositoryService = mock(RepositoryService.class, RETURNS_DEEP_STUBS);
        Deployment deployment = mock(Deployment.class);
        when(deployment.getId()).thenReturn("deployment-1");
        when(repositoryService.createDeployment().name(anyString()).addInputStream(anyString(), any()).deploy())
                .thenReturn(deployment);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn("definition-1");
        when(repositoryService.createProcessDefinitionQuery().deploymentId(anyString()).singleResult())
                .thenReturn(definition);

        DelegateClassGenerator delegateClassGenerator = mock(DelegateClassGenerator.class);
        when(delegateClassGenerator.generate(anyString(), anyString())).thenReturn(List.of());

        return new WorkbenchServiceImpl(mock(NodeManagerClient.class), mock(GovernanceService.class),
                mock(PolicyDecisionEngine.class), approvalService, mock(RuntimeService.class), repositoryService,
                mock(HistoryService.class), mock(TaskService.class), mock(ExternalTaskService.class), mock(TwinModelGenerator.class), stateStore,
                mock(ProcessModelFileStore.class), processModelArchiveStore, delegateClassGenerator, generator,
                launcher, tracker);
    }

    // a real restart: the previous service instance is gone, the tracker reloads its event history
    // from the file it actually persisted, and the generator rescans the real output directory
    private WorkbenchServiceImpl restart(List<String> modelIds) {
        WorkbenchServiceImpl restarted = newService(newTracker());
        when(processModelArchiveStore.findAll())
                .thenReturn(modelIds.stream().map(GeneratedProjectRetentionTest::modelNamed).toList());
        restarted.restoreState();
        return restarted;
    }

    private static com.metaml.workbench.model.ProcessModel modelNamed(String modelId) {
        return new com.metaml.workbench.model.ProcessModel(modelId, modelId, loanApprovalBpmn(),
                java.time.Instant.now(), "definition-1", null);
    }

    private String saveModel(String modelId) {
        service.saveProcessModel(modelId, modelId, loanApprovalBpmn(), null);
        return modelId;
    }

    private Path directoryOf(GeneratedProject project) {
        // Folder name is a slug of the model's display name now, not the bare projectId (see
        // SpringBootProjectGenerator.resolveProjectDirectory) - project.directory() is the actual
        // source of truth for where it landed.
        return project.directory();
    }

    // Asserts retention as a complete, resolvable project (directory, pom.xml, and discovery in scanExisting)
    // to guard against partially deleted project directories.
    private void assertProjectIntact(GeneratedProject project) {
        assertThat(directoryOf(project)).exists();
        assertThat(directoryOf(project).resolve("pom.xml")).exists();
        assertThat(generator.scanExisting()).extracting(GeneratedProject::projectId)
                .as("project should still be resolvable as a real generated project, not a gutted directory")
                .contains(project.projectId());
    }

    // makes an already-generated project actually launchable by the fake-listener launcher
    private LaunchedProject launch(GeneratedProject project) throws IOException {
        Files.writeString(directoryOf(project).resolve("mvnw.cmd"), FAKE_LISTENER_SCRIPT, StandardCharsets.UTF_8);
        return service.launchGeneratedProject(project.projectId());
    }

    @Test
    void regeneratingAModelDeletesTheGenerationItSupersedes() {
        String modelId = saveModel("m1");
        GeneratedProject first = service.generateSpringBootProject(modelId);
        assertThat(directoryOf(first)).exists();

        GeneratedProject second = service.generateSpringBootProject(modelId);

        assertThat(directoryOf(first)).as("superseded generation should be gone").doesNotExist();
        assertThat(directoryOf(second)).as("current generation must survive").exists();
    }

    @Test
    void regeneratingRepeatedlyLeavesExactlyOneGenerationOnDisk() {
        String modelId = saveModel("m1");
        service.generateSpringBootProject(modelId);
        service.generateSpringBootProject(modelId);
        service.generateSpringBootProject(modelId);
        GeneratedProject latest = service.generateSpringBootProject(modelId);

        assertThat(generator.scanExisting()).extracting(GeneratedProject::projectId)
                .containsExactly(latest.projectId());
    }

    @Test
    void regeneratingDoesNotDeleteOrDisruptASupersededProjectThatIsStillRunning() throws IOException {
        String modelId = saveModel("m1");
        GeneratedProject first = service.generateSpringBootProject(modelId);
        LaunchedProject running = launch(first);

        GeneratedProject second = service.generateSpringBootProject(modelId);

        assertProjectIntact(first);
        assertThat(service.listRunningProjects()).extracting(LaunchedProject::projectId)
                .as("regenerating must not stop or disturb the running app")
                .containsExactly(first.projectId());
        assertThat(isListening(running.port())).isTrue();
        assertThat(directoryOf(second)).exists();

        service.stopGeneratedProject(first.projectId());
    }

    @Test
    void asupersededProjectIsCollectedWhenItLaterStops() throws IOException {
        String modelId = saveModel("m1");
        GeneratedProject first = service.generateSpringBootProject(modelId);
        launch(first);
        GeneratedProject second = service.generateSpringBootProject(modelId);
        assertProjectIntact(first);

        service.stopGeneratedProject(first.projectId());

        assertThat(directoryOf(first)).as("stopping is what makes it collectable").doesNotExist();
        assertThat(directoryOf(second)).exists();
    }

    @Test
    void asupersededProjectWhoseJvmDiedExternallyIsCollectedOnTheNextLifecycleEvent() throws Exception {
        String modelId = saveModel("m1");
        GeneratedProject first = service.generateSpringBootProject(modelId);
        launch(first);
        GeneratedProject second = service.generateSpringBootProject(modelId);
        killExternally(first.projectId());

        // stop reports "nothing was running" (the liveness self-heal got there first) but the
        // cleanup still runs - that honesty is the point, not an obstacle
        assertThat(service.stopGeneratedProject(first.projectId())).isFalse();

        assertThat(directoryOf(first)).doesNotExist();
        assertThat(directoryOf(second)).exists();
    }

    @Test
    void theCurrentGenerationIsNeverDeletedByAnyLifecycleEvent() throws IOException {
        String modelId = saveModel("m1");
        GeneratedProject only = service.generateSpringBootProject(modelId);

        launch(only);
        service.stopGeneratedProject(only.projectId());

        assertProjectIntact(only); // the only generation is the current one
    }

    @Test
    void aFailedRegenerateLeavesThePreviousGenerationCurrentAndOnDisk() {
        String modelId = saveModel("m1");
        GeneratedProject first = service.generateSpringBootProject(modelId);

        // the model's BPMN is fine; the template is what breaks, which is a whole-operation
        // failure rather than one attributable to a delegate
        Path templateDir = tempDir.resolve("template");
        deleteRecursively(templateDir);
        assertThatThrownBy(() -> service.generateSpringBootProject(modelId))
                .isInstanceOf(IllegalStateException.class);

        assertThat(directoryOf(first))
                .as("a failed regenerate supersedes nothing - the previous generation is still current")
                .exists();
        assertThat(service.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).status())
                .isEqualTo(StageStatus.FAILED);
    }

    @Test
    void cleaningUpOneModelNeverTouchesAnotherModelsProjects() {
        String first = saveModel("m1");
        String second = saveModel("m2");
        GeneratedProject firstOld = service.generateSpringBootProject(first);
        GeneratedProject secondOnly = service.generateSpringBootProject(second);

        GeneratedProject firstNew = service.generateSpringBootProject(first);

        assertThat(directoryOf(firstOld)).doesNotExist();
        assertThat(directoryOf(firstNew)).exists();
        assertThat(directoryOf(secondOnly)).as("a different model's project is not this model's business").exists();
    }

    // an unknown directory is not disposable - only a project a model's own history names as a
    // superseded generation is ever removed
    @Test
    void aDirectoryNoModelClaimsIsLeftAlone() throws IOException {
        String modelId = saveModel("m1");
        service.generateSpringBootProject(modelId);
        Path stranger = outputDir.resolve("not-generated-by-this-workbench");
        write(stranger.resolve("src/main/resources/processes/whatever.bpmn"), "<bpmn/>");

        service.generateSpringBootProject(modelId);

        assertThat(stranger).exists();
    }

    @Test
    void workflowHistoryKeepsEveryGenerationEvenThoughOnlyOneDirectorySurvives() {
        String modelId = saveModel("m1");
        GeneratedProject first = service.generateSpringBootProject(modelId);
        GeneratedProject second = service.generateSpringBootProject(modelId);

        List<String> generated = service.getWorkflowState(modelId).history().stream()
                .filter(event -> event.stage() == WorkflowStage.GENERATE
                        && event.status() == StageStatus.COMPLETED)
                .map(StageEvent::detail)
                .toList();

        assertThat(generated).as("the filesystem artifact goes, the history stays")
                .containsExactly(first.projectId(), second.projectId());
    }

    @Test
    void restartRestoresTheLatestGenerationAsCurrentAndCollectsWhatItSuperseded() {
        String modelId = saveModel("m1");
        GeneratedProject first = service.generateSpringBootProject(modelId);
        GeneratedProject second = service.generateSpringBootProject(modelId);

        WorkbenchServiceImpl restarted = restart(List.of(modelId));

        assertThat(directoryOf(first)).doesNotExist();
        assertThat(directoryOf(second)).exists();
        assertThat(restarted.getWorkflowState(modelId).stages().get(WorkflowStage.GENERATE).detail())
                .isEqualTo(second.projectId());
        // the restored mapping is real, not just present: regenerating after the restart supersedes
        // the generation the restart identified as current
        GeneratedProject third = restarted.generateSpringBootProject(modelId);
        assertThat(directoryOf(second)).doesNotExist();
        assertThat(directoryOf(third)).exists();
    }

    // Collect projects that were superseded while running once the application restarts.
    @Test
    void restartCollectsAProjectThatWasSupersededWhileItWasStillRunning() throws IOException {
        String modelId = saveModel("m1");
        GeneratedProject first = service.generateSpringBootProject(modelId);
        launch(first);
        GeneratedProject second = service.generateSpringBootProject(modelId);
        assertProjectIntact(first); // retained while running, as the policy requires
        // Stop process directly without service.stopGeneratedProject() to verify startup cleanup.
        launcher.stop(first.projectId());

        restart(List.of(modelId));

        assertThat(directoryOf(first)).doesNotExist();
        assertThat(directoryOf(second)).exists();
    }

    // Verify startup cleanup preserves projects if their ports are still actively listening.
    @Test
    void restartDoesNotCollectProjectsWhileAPreviouslyLaunchedPortIsStillListening() throws Exception {
        String modelId = saveModel("m1");
        GeneratedProject first = service.generateSpringBootProject(modelId);
        LaunchedProject launched = launch(first);
        GeneratedProject second = service.generateSpringBootProject(modelId);
        int leakedPort = launched.port();

        // Simulates workbench crash leaving orphaned background processes and empty launcher registry.
        SpringBootProjectLauncher leakedFrom = launcher;
        launcher = new SpringBootProjectLauncher();
        try {
            assertThat(isListening(leakedPort)).as("leaked app should still be up").isTrue();

            restart(List.of(modelId));

            assertProjectIntact(first);
            assertThat(directoryOf(second)).exists();
        } finally {
            leakedFrom.stop(first.projectId());
        }

        // Verify next restart collects the superseded generation once the port is free.
        waitUntilFree(leakedPort);
        restart(List.of(modelId));
        assertThat(directoryOf(first)).doesNotExist();
        assertThat(directoryOf(second)).exists();
    }

    private static void waitUntilFree(int port) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (!isListening(port)) {
                return;
            }
            sleep(200);
        }
        throw new AssertionError("Port " + port + " never stopped listening");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void relaunchingACollectedSupersededProjectFailsWithAClearNotFound() {
        String modelId = saveModel("m1");
        GeneratedProject first = service.generateSpringBootProject(modelId);
        service.generateSpringBootProject(modelId);

        assertThatThrownBy(() -> service.launchGeneratedProject(first.projectId()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(first.projectId())
                .hasMessageContaining("may be missing");
    }

    @Test
    void retentionDoesNotChangeHowRunningProjectsAreReported() throws IOException {
        String modelId = saveModel("m1");
        GeneratedProject only = service.generateSpringBootProject(modelId);
        LaunchedProject launched = launch(only);

        assertThat(service.listRunningProjects()).extracting(LaunchedProject::projectId)
                .containsExactly(only.projectId());
        assertThat(service.listRunningProjects()).extracting(LaunchedProject::modelId)
                .containsExactly(modelId);

        killExternally(only.projectId());

        assertThat(service.listRunningProjects()).as("a dead JVM is still not reported as running").isEmpty();
        assertProjectIntact(only); // but the current generation stays on disk, whole
        assertThat(launched.port()).isGreaterThan(0);
    }

    @SuppressWarnings("unchecked")
    private void killExternally(String projectId) throws RuntimeException {
        try {
            java.lang.reflect.Field runningField = SpringBootProjectLauncher.class.getDeclaredField("running");
            runningField.setAccessible(true);
            Object entry = ((java.util.Map<String, ?>) runningField.get(launcher)).get(projectId);
            assertThat(entry).as("launcher is not tracking %s", projectId).isNotNull();
            java.lang.reflect.Method accessor = entry.getClass().getDeclaredMethod("process");
            accessor.setAccessible(true);
            Process process = (Process) accessor.invoke(entry);
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            long deadline = System.currentTimeMillis() + 15_000;
            while (process.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            assertThat(process.isAlive()).as("process should have died externally").isFalse();
        } catch (ReflectiveOperationException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
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

    private static void deleteRecursively(Path root) {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
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
