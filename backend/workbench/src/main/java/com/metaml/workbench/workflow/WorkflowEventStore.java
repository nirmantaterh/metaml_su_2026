package com.metaml.workbench.workflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Durable half of WorkflowStateTracker's event log; same file pattern as WorkbenchStateStore next to
// it. Kept separate because the two serialize different shapes - workflow events are keyed by model
// id and grow over a model's lifetime, twins are a flat list.
// Instants are stored as epoch millis rather than left to Jackson, so a round-tripped value cannot
// disagree with the in-memory one on nanos-vs-millis precision.
@Component
public class WorkflowEventStore {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowEventStore.class);

    private final Path file;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Object writeLock = new Object();

    public WorkflowEventStore(
            @Value("${workbench.workflow.file:./data/workflow-events.json}") String path,
            @Value("${workbench.workflow.persist:true}") boolean enabled) {
        this.file = Path.of(path);
        this.enabled = enabled;
    }

    public Map<String, List<StageEvent>> load() {
        if (!enabled || !Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            EventStoreDto dto = mapper.readValue(file.toFile(), EventStoreDto.class);
            Map<String, List<StageEvent>> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<StageEventDto>> entry : nullToEmptyMap(dto.eventsByModelId).entrySet()) {
                List<StageEvent> events = new ArrayList<>();
                for (StageEventDto eventDto : entry.getValue()) {
                    events.add(eventDto.toEvent());
                }
                result.put(entry.getKey(), events);
            }
            logger.info("Restored workflow event history for {} model(s) from {}", result.size(),
                    file.toAbsolutePath());
            return result;
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read workflow event history from {}, carrying on with nothing restored: {}",
                    file.toAbsolutePath(), e.toString());
            return Map.of();
        }
    }

    public void save(Map<String, List<StageEvent>> eventsByModelId) {
        if (!enabled) {
            return;
        }
        synchronized (writeLock) {
            EventStoreDto dto = new EventStoreDto();
            dto.eventsByModelId = new LinkedHashMap<>();
            for (Map.Entry<String, List<StageEvent>> entry : eventsByModelId.entrySet()) {
                List<StageEventDto> events = new ArrayList<>();
                for (StageEvent event : entry.getValue()) {
                    events.add(StageEventDto.of(event));
                }
                dto.eventsByModelId.put(entry.getKey(), events);
            }

            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                mapper.writeValue(tmp.toFile(), dto);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not write workflow event history to {}: {}", file.toAbsolutePath(), e.toString());
            }
        }
    }

    private static <K, V> Map<K, V> nullToEmptyMap(Map<K, V> map) {
        return map == null ? Map.of() : map;
    }

    static final class EventStoreDto {
        public Map<String, List<StageEventDto>> eventsByModelId;
    }

    static final class StageEventDto {
        public String stage;
        public String status;
        public Long timestampEpochMillis;
        public String detail;
        // Optional structured error metadata DTO.
        public StageErrorDto error;

        static StageEventDto of(StageEvent event) {
            StageEventDto dto = new StageEventDto();
            dto.stage = event.stage().name();
            dto.status = event.status().name();
            dto.timestampEpochMillis = event.timestamp() == null ? null : event.timestamp().toEpochMilli();
            dto.detail = event.detail();
            dto.error = StageErrorDto.of(event.error());
            return dto;
        }

        StageEvent toEvent() {
            return new StageEvent(WorkflowStage.valueOf(stage), StageStatus.valueOf(status),
                    timestampEpochMillis == null ? null : Instant.ofEpochMilli(timestampEpochMillis), detail,
                    error == null ? null : error.toStageError());
        }
    }

    static final class StageErrorDto {
        public String errorType;
        public String operation;
        public String projectId;
        public Integer port;
        public Integer exitCode;
        public String delegateExpression;
        public String bpmnElementId;

        static StageErrorDto of(StageError error) {
            if (error == null) {
                return null;
            }
            StageErrorDto dto = new StageErrorDto();
            dto.errorType = error.errorType();
            dto.operation = error.operation();
            dto.projectId = error.projectId();
            dto.port = error.port();
            dto.exitCode = error.exitCode();
            dto.delegateExpression = error.delegateExpression();
            dto.bpmnElementId = error.bpmnElementId();
            return dto;
        }

        StageError toStageError() {
            return new StageError(errorType, operation, projectId, port, exitCode, delegateExpression,
                    bpmnElementId);
        }
    }
}
