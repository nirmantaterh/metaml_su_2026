package com.metaml.workbench.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.metaml.workbench.dto.ProcessModelSummaryDto;
import com.metaml.workbench.model.Project;
import com.metaml.workbench.model.ProcessModelArchive;
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
}
