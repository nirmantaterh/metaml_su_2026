package com.metaml.workbench.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.metaml.workbench.model.ActivityLink;
import com.metaml.workbench.model.TwinProcess;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * File-backed store for persisting workbench twin process metadata across restarts.
 */
@Component
public class WorkbenchStateStore {

    private static final Logger logger = LoggerFactory.getLogger(WorkbenchStateStore.class);

    private final Path file;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper()
            // an older file with a field we've since dropped shouldn't blow up the read
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Object writeLock = new Object();

    public WorkbenchStateStore(
            @Value("${workbench.state.file:./data/workbench-state.json}") String path,
            @Value("${workbench.state.persist:true}") boolean enabled) {
        this.file = Path.of(path);
        this.enabled = enabled;
    }

    public record Snapshot(List<TwinProcess> twins) {
        static Snapshot empty() {
            return new Snapshot(List.of());
        }
    }

    public Snapshot load() {
        if (!enabled) {
            return Snapshot.empty();
        }
        if (!Files.isRegularFile(file)) {
            logger.info("No workbench state file at {}, starting with no twins",
                    file.toAbsolutePath());
            return Snapshot.empty();
        }
        try {
            StateDto dto = mapper.readValue(file.toFile(), StateDto.class);
            List<TwinProcess> twins = new ArrayList<>();
            for (TwinProcessDto t : nullToEmpty(dto.twins)) {
                twins.add(t.toTwin());
            }
            logger.info("Restored {} twin(s) from {}", twins.size(), file.toAbsolutePath());
            return new Snapshot(twins);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read workbench state from {}, carrying on with nothing restored: {}",
                    file.toAbsolutePath(), e.toString());
            return Snapshot.empty();
        }
    }

    public void save(Collection<TwinProcess> twins) {
        if (!enabled) {
            return;
        }
        // Synchronize snapshot creation and file write to prevent lost updates.
        synchronized (writeLock) {
            StateDto dto = new StateDto();
            dto.twins = new ArrayList<>();
            for (TwinProcess twin : twins) {
                dto.twins.add(TwinProcessDto.of(twin));
            }

            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                mapper.writeValue(tmp.toFile(), dto);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not write workbench state to {}: {}",
                        file.toAbsolutePath(), e.toString());
            }
        }
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    // DTOs decouple persistence format from runtime model collections.
    static final class StateDto {
        public List<TwinProcessDto> twins;
    }

    static final class TwinProcessDto {
        public String id;
        public String modelId;
        public String processDefinitionId;
        public String twinProcessDefinitionId;
        public String originalProcessId;
        public String twinProcessId;
        public String projectId;
        // Absent in any snapshot written before tenant identity existed; toTwin() treats that as unowned.
        public String tenantId;
        public String status;
        public Long launchedAtEpochMillis;
        public List<String> eventLog;
        public List<ActivityLinkDto> activityLinks;

        static TwinProcessDto of(TwinProcess twin) {
            TwinProcessDto dto = new TwinProcessDto();
            dto.id = twin.getId();
            dto.modelId = twin.getModelId();
            dto.processDefinitionId = twin.getProcessDefinitionId();
            dto.twinProcessDefinitionId = twin.getTwinProcessDefinitionId();
            dto.originalProcessId = twin.getOriginalProcessId();
            dto.twinProcessId = twin.getTwinProcessId();
            dto.projectId = twin.getProjectId();
            dto.tenantId = twin.getTenantId();
            dto.status = twin.getStatus();
            dto.launchedAtEpochMillis = twin.getLaunchedAt() == null
                    ? null
                    : twin.getLaunchedAt().toEpochMilli();
            dto.eventLog = new ArrayList<>(twin.getEventLog());
            dto.activityLinks = new ArrayList<>();
            for (ActivityLink link : twin.getActivityLinks()) {
                ActivityLinkDto linkDto = new ActivityLinkDto();
                linkDto.originalActivityId = link.getOriginalActivityId();
                linkDto.twinActivityId = link.getTwinActivityId();
                dto.activityLinks.add(linkDto);
            }
            return dto;
        }

        TwinProcess toTwin() {
            TwinProcess twin = new TwinProcess();
            twin.setId(id);
            twin.setModelId(modelId);
            twin.setProcessDefinitionId(processDefinitionId);
            // Older snapshots predate the dedicated twin definition field. Keep those restorable by falling back to the original definition, which is what older twins were running.
            twin.setTwinProcessDefinitionId(
                    twinProcessDefinitionId == null || twinProcessDefinitionId.isBlank()
                            ? processDefinitionId
                            : twinProcessDefinitionId);
            twin.setOriginalProcessId(originalProcessId);
            twin.setTwinProcessId(twinProcessId);
            // a snapshot written before twins had a project keeps the field's own default
            if (projectId != null && !projectId.isBlank()) {
                twin.setProjectId(projectId);
            }
            twin.setTenantId(tenantId);
            twin.setStatus(status);
            twin.setLaunchedAt(launchedAtEpochMillis == null
                    ? null
                    : Instant.ofEpochMilli(launchedAtEpochMillis));
            // add into the collections the constructor already made, don't replace them
            twin.getEventLog().addAll(nullToEmpty(eventLog));
            for (ActivityLinkDto link : nullToEmpty(activityLinks)) {
                twin.getActivityLinks().add(
                        new ActivityLink(link.originalActivityId, link.twinActivityId));
            }
            return twin;
        }
    }

    static final class ActivityLinkDto {
        public String originalActivityId;
        public String twinActivityId;
    }
}
