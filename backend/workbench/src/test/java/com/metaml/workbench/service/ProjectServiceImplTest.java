package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.metaml.workbench.dto.EntityConverter;
import com.metaml.workbench.dto.ProjectDto;
import com.metaml.workbench.model.Project;
import com.metaml.workbench.model.ProcessModelArchive;
import com.metaml.workbench.repository.ProcessModelArchiveRepository;
import com.metaml.workbench.repository.ProjectRepository;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProcessModelArchiveRepository archiveRepository;
    @Mock
    private EntityConverter<Project, ProjectDto> entityConverter;
    @Mock
    private WorkbenchService workbenchService;

    private ProjectServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProjectServiceImpl(projectRepository, new ProjectAttributesMapper(), entityConverter,
                archiveRepository, workbenchService);
        // lenient: only the createProject* tests below actually exercise a save()
        lenient().when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            if (project.getId() == null) {
                project.setId(7L);
            }
            return project;
        });
    }

    @Test
    void createProjectUsesTheFriendlyNameAndGeneratesAnInternalSlug() {
        ProjectDto request = new ProjectDto();
        request.setDisplayName("RedCollar Suits");
        request.setDescription("RedCollar manual process for creating custom suits");

        Project created = service.createProject(request);

        assertThat(created.getName()).isEqualTo("redcollar_suits");
        assertThat(created.getDisplayName()).isEqualTo("RedCollar Suits");
        assertThat(created.getDescription()).isEqualTo("RedCollar manual process for creating custom suits");
    }

    @Test
    void createProjectStillAcceptsTheOldNameOnlyShape() {
        ProjectDto request = new ProjectDto();
        request.setName("Legacy Friendly Project");

        Project created = service.createProject(request);

        assertThat(created.getName()).isEqualTo("legacy_friendly_project");
        assertThat(created.getDisplayName()).isEqualTo("Legacy Friendly Project");
    }

    @Test
    void deleteProjectDeletesEachArchivedProcessModelThenTheProjectItself() {
        Project project = new Project();
        project.setId(7L);
        project.setDisplayName("Deletable Project");
        when(projectRepository.findById(7L)).thenReturn(java.util.Optional.of(project));
        when(archiveRepository.findAllByProjectIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(archiveWithModelId("m-1"), archiveWithModelId("m-2")));
        when(workbenchService.canDeleteProcessModel("m-1")).thenReturn(true);
        when(workbenchService.canDeleteProcessModel("m-2")).thenReturn(true);

        service.deleteProject(7L);

        verify(workbenchService).deleteProcessModel("m-1");
        verify(workbenchService).deleteProcessModel("m-2");
        verify(projectRepository).delete(project);
    }

    // Mirrors the 409 the ProjectController surfaces (IllegalStateException -> CONFLICT): a project
    // is never partly deleted while one of its process models still has a generated application
    // running or being launched.
    @Test
    void deleteProjectRefusesAndDeletesNothingWhenAnArchivedModelHasARunningGeneratedApp() {
        Project project = new Project();
        project.setId(9L);
        project.setDisplayName("Blocked Project");
        when(projectRepository.findById(9L)).thenReturn(java.util.Optional.of(project));
        when(archiveRepository.findAllByProjectIdOrderByCreatedAtDesc(9L))
                .thenReturn(List.of(archiveWithModelId("m-running")));
        when(workbenchService.canDeleteProcessModel("m-running")).thenReturn(false);

        assertThatThrownBy(() -> service.deleteProject(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("m-running");

        verify(workbenchService, never()).deleteProcessModel(eq("m-running"));
        verify(projectRepository, never()).delete(any(Project.class));
    }

    private static ProcessModelArchive archiveWithModelId(String modelId) {
        ProcessModelArchive archive = new ProcessModelArchive();
        archive.setModelId(modelId);
        return archive;
    }
}
