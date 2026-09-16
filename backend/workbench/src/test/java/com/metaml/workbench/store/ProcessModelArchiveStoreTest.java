package com.metaml.workbench.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.ArgumentCaptor;

import com.metaml.workbench.dto.ProcessModelSummaryDto;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.Project;
import com.metaml.workbench.model.ProcessModelArchive;
import com.metaml.workbench.model.ProxyTwinActivityMapping;
import com.metaml.workbench.repository.ProcessModelArchiveRepository;
import com.metaml.workbench.repository.ProjectRepository;

@ExtendWith(MockitoExtension.class)
class ProcessModelArchiveStoreTest {

    @Mock
    private ProcessModelArchiveRepository archiveRepository;
    @Mock
    private ProjectRepository projectRepository;

    private ProcessModelArchiveStore store() {
        return new ProcessModelArchiveStore(archiveRepository, projectRepository);
    }

    private static ProcessModelArchive archive(String modelId, String name, LocalDateTime createdAt,
            Project project) {
        ProcessModelArchive archive = new ProcessModelArchive();
        archive.setModelId(modelId);
        archive.setName(name);
        archive.setCreatedAt(createdAt);
        archive.setProject(project);
        return archive;
    }

    private static Project project(Long id, String displayName) {
        Project project = new Project();
        project.setId(id);
        project.setDisplayName(displayName);
        return project;
    }

    // save() is an upsert on modelId - a re-saved model must land on its existing row (see WorkbenchServiceImpl's update path), or the pickers list it twice.
    @Test
    void savingAModelIdThatAlreadyHasARowUpdatesThatRowAndBumpsTheMinorVersion() {
        Project project = project(5L, "RedCollar Suits");
        ProcessModelArchive existing = archive("m-1", "New Process", LocalDateTime.of(2026, 1, 1, 0, 0), project);
        existing.setMajor(1);
        existing.setMinor(0);
        existing.setPatch(0);
        when(archiveRepository.findByModelId("m-1")).thenReturn(Optional.of(existing));
        when(archiveRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store().save(new ProcessModel("m-1", "ProcessSample", "<xml/>", Instant.now(), "def-2"),
                Path.of("models/m-1.bpmn"), null, 5L);

        ArgumentCaptor<ProcessModelArchive> saved = ArgumentCaptor.forClass(ProcessModelArchive.class);
        verify(archiveRepository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(saved.getValue().getName()).isEqualTo("ProcessSample");
        assertThat(saved.getValue().getProcessDefinitionId()).isEqualTo("def-2");
        assertThat(saved.getValue().getMinor()).isEqualTo(1);
    }

    @Test
    void savingANewModelIdInsertsAFreshRowAtVersion1_0_0() {
        Project project = project(5L, "RedCollar Suits");
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project));
        when(archiveRepository.findByModelId("m-1")).thenReturn(Optional.empty());
        when(archiveRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProcessModelArchive saved = store().save(new ProcessModel("m-1", "New Process", "<xml/>", Instant.now(), "def-1"),
                Path.of("models/m-1.bpmn"), null, 5L);

        assertThat(saved.getModelId()).isEqualTo("m-1");
        assertThat(saved.getMajor()).isEqualTo(1);
        assertThat(saved.getMinor()).isEqualTo(0);
        assertThat(saved.getPatch()).isEqualTo(0);
    }

    @Test
    void carriesTheProjectIdAndDisplayNameOntoEachSummary() {
        when(archiveRepository.findAll()).thenReturn(List.of(
                archive("m-1", "Wire Transfer Review", LocalDateTime.of(2026, 1, 1, 0, 0),
                        project(5L, "RedCollar Suits"))));

        List<ProcessModelSummaryDto> summaries = store().findAllSummaries();

        assertThat(summaries).hasSize(1);
        ProcessModelSummaryDto summary = summaries.get(0);
        assertThat(summary.getId()).isEqualTo("m-1");
        assertThat(summary.getName()).isEqualTo("Wire Transfer Review");
        assertThat(summary.getProjectId()).isEqualTo(5L);
        assertThat(summary.getProjectDisplayName()).isEqualTo("RedCollar Suits");
    }

    // Fallback handling for archive records lacking project associations.
    @Test
    void anArchiveWithNoProjectAssociationStillProducesASummaryWithNullProjectFields() {
        when(archiveRepository.findAll()).thenReturn(List.of(
                archive("m-1", "Orphaned", LocalDateTime.of(2026, 1, 1, 0, 0), null)));

        List<ProcessModelSummaryDto> summaries = store().findAllSummaries();

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getProjectId()).isNull();
        assertThat(summaries.get(0).getProjectDisplayName()).isNull();
    }

    @Test
    void ordersNewestFirst() {
        when(archiveRepository.findAll()).thenReturn(List.of(
                archive("older", "Older", LocalDateTime.of(2026, 1, 1, 0, 0), project(1L, "P1")),
                archive("newer", "Newer", LocalDateTime.of(2026, 6, 1, 0, 0), project(1L, "P1"))));

        List<ProcessModelSummaryDto> summaries = store().findAllSummaries();

        assertThat(summaries).extracting(ProcessModelSummaryDto::getId).containsExactly("newer", "older");
    }

    @Test
    void updatesTheExistingArchiveRowAndPreservesItsProject() {
        Project owner = project(7L, "Owner");
        ProcessModelArchive existing = archive("model-1", "Before", LocalDateTime.of(2026, 1, 1, 0, 0), owner);
        when(archiveRepository.findByModelId("model-1")).thenReturn(java.util.Optional.of(existing));
        when(archiveRepository.save(existing)).thenReturn(existing);
        ProcessModel updated = new ProcessModel("model-1", "After", "<bpmn>updated</bpmn>",
                java.time.Instant.now(), "definition-2", null);

        ProcessModelArchive saved = store().save(updated, Path.of("model-1.bpmn"), null, owner.getId());

        assertThat(saved).isSameAs(existing);
        assertThat(existing.getName()).isEqualTo("After");
        assertThat(existing.getBpmnXml()).isEqualTo("<bpmn>updated</bpmn>");
        assertThat(existing.getProject()).isSameAs(owner);
        verify(archiveRepository).save(existing);
    }

    @Test
    void persistsAndReloadsAuthoredActivityMappingsOnUpdate() {
        Project owner = project(7L, "Owner");
        ProcessModelArchive existing = archive("model-1", "Before", LocalDateTime.of(2026, 1, 1, 0, 0), owner);
        when(archiveRepository.findByModelId("model-1")).thenReturn(java.util.Optional.of(existing));
        when(archiveRepository.save(existing)).thenReturn(existing);
        List<ProxyTwinActivityMapping> mappings = List.of(
                new ProxyTwinActivityMapping("proxy-one", "twin-one", "order-one"),
                new ProxyTwinActivityMapping("proxy-two", "twin-two", "order-two"));
        ProcessModel updated = new ProcessModel("model-1", "After", "<bpmn/>", "<twin/>", mappings,
                java.time.Instant.now(), "definition-2", null);

        store().save(updated, Path.of("model-1.bpmn"), Path.of("model-1-twin.bpmn"), owner.getId());

        assertThat(existing.getProxyTwinActivityMappings()).containsExactlyElementsOf(mappings);
        verify(archiveRepository).save(existing);
    }

    @Test
    void rejectsAnUpdateThatWouldMoveTheModelToAnotherProject() {
        Project owner = project(7L, "Owner");
        ProcessModelArchive existing = archive("model-1", "Before", LocalDateTime.of(2026, 1, 1, 0, 0), owner);
        when(archiveRepository.findByModelId("model-1")).thenReturn(java.util.Optional.of(existing));
        ProcessModel updated = new ProcessModel("model-1", "After", "<bpmn>updated</bpmn>",
                java.time.Instant.now(), "definition-2", null);

        assertThatThrownBy(() -> store().save(updated, Path.of("model-1.bpmn"), null, 8L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be moved by save");
        assertThat(existing.getProject()).isSameAs(owner);
    }

    @Test
    void findAllUsesDedicatedFetchMethodAndPreservesProxyTwinActivityMappings() {
        ProcessModelArchive archive = archive("model-mapped", "Mapped", LocalDateTime.of(2026, 1, 1, 0, 0), null);
        List<ProxyTwinActivityMapping> mappings = List.of(
                new ProxyTwinActivityMapping("proxy-a", "twin-a", "sync-a"));
        archive.setProxyTwinActivityMappings(mappings);
        when(archiveRepository.findAllWithProxyTwinActivityMappings()).thenReturn(List.of(archive));

        List<ProcessModel> models = store().findAll();

        assertThat(models).hasSize(1);
        assertThat(models.get(0).getId()).isEqualTo("model-mapped");
        assertThat(models.get(0).getProxyTwinActivityMappings()).containsExactlyElementsOf(mappings);
        verify(archiveRepository).findAllWithProxyTwinActivityMappings();
    }

    @Test
    void findByModelIdUsesDedicatedFetchMethodAndPreservesProxyTwinActivityMappings() {
        ProcessModelArchive archive = archive("model-mapped", "Mapped", LocalDateTime.of(2026, 1, 1, 0, 0), null);
        List<ProxyTwinActivityMapping> mappings = List.of(
                new ProxyTwinActivityMapping("proxy-a", "twin-a", "sync-a"));
        archive.setProxyTwinActivityMappings(mappings);
        when(archiveRepository.findWithProxyTwinActivityMappingsByModelId("model-mapped")).thenReturn(Optional.of(archive));

        Optional<ProcessModel> modelOpt = store().findByModelId("model-mapped");

        assertThat(modelOpt).isPresent();
        assertThat(modelOpt.get().getId()).isEqualTo("model-mapped");
        assertThat(modelOpt.get().getProxyTwinActivityMappings()).containsExactlyElementsOf(mappings);
        verify(archiveRepository).findWithProxyTwinActivityMappingsByModelId("model-mapped");
    }
}
