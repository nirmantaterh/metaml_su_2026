package com.metaml.workbench.store;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.metaml.workbench.constants.ProjectStatus;
import com.metaml.workbench.dto.ProcessModelSummaryDto;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.ProcessModelArchive;
import com.metaml.workbench.model.Project;
import com.metaml.workbench.repository.ProcessModelArchiveRepository;
import com.metaml.workbench.repository.ProjectRepository;

// Backs ProcessModel with the H2-persisted ProcessModelArchive/Project entities instead of the workbench-state.json snapshot. One archive row per saved ProcessModel, grouped under a Project found or created by that model's name - the plain ProcessModel workflow has no separate notion of a project to select one by.
@Component
public class ProcessModelArchiveStore {

    private final ProcessModelArchiveRepository archiveRepository;
    private final ProjectRepository projectRepository;

    public ProcessModelArchiveStore(ProcessModelArchiveRepository archiveRepository,
            ProjectRepository projectRepository) {
        this.archiveRepository = archiveRepository;
        this.projectRepository = projectRepository;
    }

    public ProcessModelArchive save(ProcessModel model, Path bpmnFilePath) {
        return save(model, bpmnFilePath, null, null);
    }

    // twinBpmnFilePath is null for the ordinary single-BPMN path. Set only alongside model.hasAuthoredTwin() - see ProcessModel.authoredTwinBpmnXml.
    public ProcessModelArchive save(ProcessModel model, Path bpmnFilePath, Path twinBpmnFilePath) {
        return save(model, bpmnFilePath, twinBpmnFilePath, null);
    }

    // projectId is supplied by the Project UI.  A null value is retained only for legacy direct service callers and old persisted-workflow tests; new HTTP requests must provide it.
    // Upserts on modelId: re-saving a model the editor already holds (see WorkbenchServiceImpl.doSaveProcessModelEntry) updates its row in place - same id, bumped minor version - rather than adding a second row, which is what the Transmute > Generate / Launch pickers would otherwise list as a duplicate process.
    public ProcessModelArchive save(ProcessModel model, Path bpmnFilePath, Path twinBpmnFilePath, Long projectId) {
        ProcessModelArchive archive = archiveRepository.findByModelId(model.getId()).orElse(null);
        Project project;
        if (archive == null) {
            project = projectId == null
                    ? projectRepository.findByName(model.getName())
                            .orElseGet(() -> createLegacyProject(model.getName()))
                    : projectRepository.findById(projectId)
                            .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
            archive = new ProcessModelArchive();
            archive.setModelId(model.getId());
            archive.setProject(project);
            archive.setMajor(1);
            archive.setMinor(0);
            archive.setPatch(0);
        } else {
            project = archive.getProject();
            if (project == null) {
                throw new IllegalStateException("Process model archive has no owning project: " + model.getId());
            }
            if (projectId != null && !projectId.equals(project.getId())) {
                throw new IllegalArgumentException("Process model " + model.getId()
                        + " belongs to project " + project.getId() + " and cannot be moved by save");
            }
            archive.setMinor((archive.getMinor() == null ? 0 : archive.getMinor()) + 1);
            archive.setPatch(0);
        }
        archive.setName(model.getName());
        archive.setBpmnXml(model.getBpmnXml());
        archive.setBpmnFilePath(bpmnFilePath == null ? null : bpmnFilePath.toString());
        archive.setTwinBpmnXml(model.getAuthoredTwinBpmnXml());
        archive.setTwinBpmnFilePath(twinBpmnFilePath == null ? null : twinBpmnFilePath.toString());
        archive.setProxyTwinActivityMappings(new java.util.ArrayList<>(model.getProxyTwinActivityMappings()));
        archive.setProcessDefinitionId(model.getProcessDefinitionId());
        archive.setTenantId(model.getTenantId());
        return archiveRepository.save(archive);
    }

    @Transactional(readOnly = true)
    public Optional<ProcessModel> findByModelId(String modelId) {
        return archiveRepository.findWithProxyTwinActivityMappingsByModelId(modelId)
                .map(ProcessModelArchiveStore::toProcessModel);
    }

    // ProcessModel deliberately has no Project field. Generation needs only the user-facing project
    // label for its output path, so keep that lookup at the archive boundary rather than coupling IDs.
    @Transactional(readOnly = true)
    public Optional<String> findProjectDisplayName(String modelId) {
        return archiveRepository.findByModelId(modelId)
                .map(ProcessModelArchive::getProject)
                .map(project -> project.getDisplayName() == null || project.getDisplayName().isBlank()
                        ? project.getName()
                        : project.getDisplayName());
    }

    @Transactional(readOnly = true)
    public List<ProcessModel> findAll() {
        return archiveRepository.findAllWithProxyTwinActivityMappings().stream()
                .map(ProcessModelArchiveStore::toProcessModel)
                .toList();
    }

    // Unlike findAll() above, keeps the project association - toProcessModel() has to drop it (ProcessModel has no notion of a project), but the Transmute > Generate / Launch pickers need exactly that to show which project each row belongs to, so this reads straight off the archive instead of round-tripping through ProcessModel.
    @Transactional(readOnly = true)
    public List<ProcessModelSummaryDto> findAllSummaries() {
        return archiveRepository.findAll().stream()
                .sorted(Comparator.comparing(ProcessModelArchive::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(archive -> new ProcessModelSummaryDto(archive.getModelId(), archive.getName(),
                        archive.getCreatedAt(),
                        archive.getProject() == null ? null : archive.getProject().getId(),
                        archive.getProject() == null ? null : archive.getProject().getDisplayName()))
                .toList();
    }

    // derived delete queries run as a select-then-remove-each, which needs its own transaction rather than the one save() gets for free from JpaRepository's own per-method wrapping
    @Transactional
    public void deleteByModelId(String modelId) {
        archiveRepository.deleteByModelId(modelId);
    }

    private static ProcessModel toProcessModel(ProcessModelArchive archive) {
        Instant createdAt = archive.getCreatedAt() == null
                ? null
                : archive.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
        return new ProcessModel(archive.getModelId(), archive.getName(), archive.getBpmnXml(),
                archive.getTwinBpmnXml(), archive.getProxyTwinActivityMappings(), createdAt,
                archive.getProcessDefinitionId(), archive.getTenantId());
    }

    private Project createLegacyProject(String name) {
        Project project = new Project();
        project.setName(name);
        project.setStatus(ProjectStatus.PROJECT_CREATED);
        Project saved = projectRepository.save(project);
        saved.setDisplayName("PROJECT-%06d".formatted(saved.getId()));
        return projectRepository.save(saved);
    }
}
