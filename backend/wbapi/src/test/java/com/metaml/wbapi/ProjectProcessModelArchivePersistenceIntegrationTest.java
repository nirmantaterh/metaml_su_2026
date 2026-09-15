package com.metaml.wbapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.dto.ProcessModelSummaryDto;
import com.metaml.workbench.dto.ProjectDto;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.ProcessModelArchive;
import com.metaml.workbench.model.Project;
import com.metaml.workbench.repository.ProcessModelArchiveRepository;
import com.metaml.workbench.repository.ProjectRepository;
import com.metaml.workbench.service.ProjectService;
import com.metaml.workbench.service.WorkbenchService;
import com.metaml.workbench.store.ProcessModelArchiveStore;

// Joanna Final Acceptance, Phase 3: proves the real Project <-> ProcessModelArchive persistence
// contract against a real Spring context and a real (in-memory, isolated) H2 database - not mocked
// repositories. ProcessModelArchiveStoreTest and ProjectServiceImplTest already cover the mapping
// and slug/validation logic in isolation with Mockito; this test is the narrowest thing that
// additionally proves the JPA wiring itself: the project_id foreign key, cascade-free lookups, and
// that ProcessModelArchiveStore.findAll() - the exact call WorkbenchServiceImpl.restoreState() makes
// from @PostConstruct - reads a saved model back from H2 alone, with no help from the service's
// in-memory processModels cache. That is the restart-recovery contract in practice: restoreState()
// has nothing else to read from.
@IsolatedWorkbenchTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-project-archive-persistence-test;DB_CLOSE_DELAY=-1"
})
class ProjectProcessModelArchivePersistenceIntegrationTest {

    @MockitoBean
    @SuppressWarnings("unused")
    private NodeManagerClient nodeManagerClient;

    @Autowired
    private ProjectService projectService;
    @Autowired
    private WorkbenchService workbenchService;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProcessModelArchiveRepository archiveRepository;
    @Autowired
    private ProcessModelArchiveStore archiveStore;

    private static final String SIMPLE_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                id="Definitions_1" targetNamespace="http://test.metaml.com">
              <bpmn2:process id="PersistenceProbeProcess" isExecutable="true">
                <bpmn2:startEvent id="Start" />
                <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="End" />
                <bpmn2:endEvent id="End" />
              </bpmn2:process>
            </bpmn2:definitions>""";

    @Test
    void savingAModelUnderAProjectPersistsTheArchiveWithTheProjectForeignKey() {
        Project project = createProject("Persistence Probe Project", "created by the integration test");

        ProcessModel model = workbenchService.saveProcessModel(null, "Probe Process", SIMPLE_BPMN, null,
                project.getId());

        // The archive row exists in H2 with the project association set - not just in the
        // service's in-memory map.
        ProcessModelArchive archive = archiveRepository.findByModelId(model.getId())
                .orElseThrow(() -> new AssertionError("archive row was not persisted to H2"));
        assertThat(archive.getBpmnXml()).contains("PersistenceProbeProcess");
        assertThat(archive.getProject()).isNotNull();
        assertThat(archive.getProject().getId()).isEqualTo(project.getId());
        assertThat(archive.getMajor()).isEqualTo(1);
        assertThat(archive.getMinor()).isEqualTo(0);
        assertThat(archive.getPatch()).isEqualTo(0);

        // The project's own process listing (what ProjectProcessListPage renders) reflects it.
        List<ProcessModelSummaryDto> processes = projectService.getProjectProcessModels(project.getId());
        assertThat(processes).extracting(ProcessModelSummaryDto::getId).contains(model.getId());
    }

    @Test
    void restoreStateReadsTheSavedModelBackFromH2AloneNotFromAnyInMemoryCache() {
        Project project = createProject("Restart Recovery Project", null);
        ProcessModel saved = workbenchService.saveProcessModel(null, "Restart Probe", SIMPLE_BPMN, null,
                project.getId());

        // This is the exact call WorkbenchServiceImpl.restoreState() makes from @PostConstruct on a
        // fresh boot - going through the store/repository layer directly, bypassing the running
        // service's own in-memory processModels map entirely, to prove H2 (not memory) is what a
        // restart would actually recover from.
        List<ProcessModel> rehydrated = archiveStore.findAll();

        assertThat(rehydrated).extracting(ProcessModel::getId).contains(saved.getId());
        ProcessModel rehydratedModel = rehydrated.stream()
                .filter(m -> m.getId().equals(saved.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(rehydratedModel.getBpmnXml()).isEqualTo(SIMPLE_BPMN);
        assertThat(rehydratedModel.getName()).isEqualTo("Restart Probe");
    }

    @Test
    void updatingAnExistingModelReplacesItsArchiveContentWithoutChangingIdentityOrProject() {
        Project project = createProject("Update Probe Project", null);
        ProcessModel created = workbenchService.saveProcessModel(null, "Before update", SIMPLE_BPMN, null,
                project.getId());

        String updatedXml = SIMPLE_BPMN.replace("PersistenceProbeProcess", "PersistenceProbeProcessUpdated");
        ProcessModel updated = workbenchService.saveProcessModel(created.getId(), "After update", updatedXml, null,
                project.getId());
        ProcessModel updatedAgain = workbenchService.saveProcessModel(created.getId(), "After second update",
                updatedXml, null, project.getId());

        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updatedAgain.getId()).isEqualTo(created.getId());
        assertThat(workbenchService.getProcessModel(created.getId()).getName()).isEqualTo("After second update");
        assertThat(workbenchService.getProcessModel(created.getId()).getBpmnXml()).isEqualTo(updatedXml);

        ProcessModelArchive archive = archiveRepository.findByModelId(created.getId()).orElseThrow();
        assertThat(archive.getName()).isEqualTo("After second update");
        assertThat(archive.getBpmnXml()).isEqualTo(updatedXml);
        assertThat(archive.getProject().getId()).isEqualTo(project.getId());
        assertThat(archiveRepository.findAll()).extracting(ProcessModelArchive::getModelId)
                .filteredOn(created.getId()::equals).hasSize(1);
        assertThat(projectService.getProjectProcessModels(project.getId()))
                .extracting(ProcessModelSummaryDto::getId).containsExactly(created.getId());

        // restoreState reads this archive-store representation after a Workbench restart.
        ProcessModel restored = archiveStore.findAll().stream()
                .filter(model -> model.getId().equals(created.getId()))
                .findFirst().orElseThrow();
        assertThat(restored.getName()).isEqualTo("After second update");
        assertThat(restored.getBpmnXml()).isEqualTo(updatedXml);
    }

    @Test
    void suppliedUnknownModelIdFailsWithoutCreatingAnArchive() {
        Project project = createProject("Unknown Update Project", null);

        assertThatThrownBy(() -> workbenchService.saveProcessModel("missing-model", "Missing", SIMPLE_BPMN, null,
                project.getId()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Process model not found: missing-model");
        assertThat(archiveRepository.findByModelId("missing-model")).isEmpty();
    }

    @Test
    void deletingAProjectCascadesTheDeleteToItsArchivedProcessModels() {
        Project project = createProject("Deletable Project", null);
        ProcessModel model = workbenchService.saveProcessModel(null, "Doomed Process", SIMPLE_BPMN, null,
                project.getId());
        assertThat(archiveRepository.findByModelId(model.getId())).isPresent();

        projectService.deleteProject(project.getId());

        assertThat(archiveRepository.findByModelId(model.getId())).isEmpty();
        assertThat(projectRepository.findById(project.getId())).isEmpty();
        assertThatThrownBy(() -> workbenchService.getProcessModel(model.getId()))
                .isInstanceOf(NoSuchElementException.class);
    }

    private Project createProject(String displayName, String description) {
        ProjectDto dto = new ProjectDto();
        dto.setDisplayName(displayName);
        dto.setDescription(description);
        return projectService.createProject(dto);
    }
}
