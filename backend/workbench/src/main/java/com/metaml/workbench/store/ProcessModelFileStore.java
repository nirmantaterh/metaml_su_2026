package com.metaml.workbench.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Writes each saved model's BPMN as a real .bpmn file, one per model id, because the generation step
// needs a file it can copy into a generated project - not just the XML embedded in a state snapshot.
// Unlike WorkbenchStateStore, a write failure here is thrown rather than swallowed: the caller cannot
// continue without the file.
@Component
public class ProcessModelFileStore {

    private static final Logger logger = LoggerFactory.getLogger(ProcessModelFileStore.class);

    private final Path directory;

    public ProcessModelFileStore(
            @Value("${workbench.models.directory:./data/models}") String directory) {
        this.directory = Path.of(directory);
    }

    public Path save(String modelId, String bpmnXml) {
        return save(pathFor(modelId), modelId, bpmnXml);
    }

    // Same file-per-model convention as save(), for a model's independently authored second BPMN (see ProcessModel.authoredTwinBpmnXml). Kept as a distinct method/file rather than folding into save() - callers that only ever deal in single-BPMN models (the common case) should never need to pass a null twin XML through this class's main entry point.
    public Path saveTwin(String modelId, String twinBpmnXml) {
        return save(pathForTwin(modelId), modelId, twinBpmnXml);
    }

    private Path save(Path target, String modelId, String bpmnXml) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        try {
            Files.createDirectories(directory.toAbsolutePath());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, bpmnXml, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("Saved BPMN file for model {} to {}", modelId, target.toAbsolutePath());
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not write BPMN file for model " + modelId + " to " + target.toAbsolutePath(), e);
        }
    }

    // Path containment is re-checked here rather than trusted from the caller: this is a plain @Component
    // whose whole job is turning a string into a filesystem path, and a modelId of "../../evil" resolves
    // cleanly to somewhere outside the models directory.
    public Path pathFor(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        Path root = directory.toAbsolutePath().normalize();
        Path resolved = root.resolve(modelId + ".bpmn").normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "modelId must not resolve outside the models directory: " + modelId);
        }
        return resolved;
    }

    public boolean exists(String modelId) {
        return Files.isRegularFile(pathFor(modelId));
    }

    public boolean existsTwin(String modelId) {
        return Files.isRegularFile(pathForTwin(modelId));
    }

    // Same containment guarantee as pathFor(), for the authored-twin file.
    public Path pathForTwin(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        Path root = directory.toAbsolutePath().normalize();
        Path resolved = root.resolve(modelId + ".twin.bpmn").normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "modelId must not resolve outside the models directory: " + modelId);
        }
        return resolved;
    }

    // Failure is logged rather than thrown, unlike save(): the caller is removing the model outright, and
    // a leftover .bpmn file is inert and referenced by nothing - not worth failing a deletion over.
    public boolean delete(String modelId) {
        boolean deleted = deleteIfExists(pathFor(modelId), modelId);
        deleteIfExists(pathForTwin(modelId), modelId);
        return deleted;
    }

    private boolean deleteIfExists(Path target, String modelId) {
        try {
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) {
                logger.info("Deleted BPMN file for model {} at {}", modelId, target.toAbsolutePath());
            }
            return deleted;
        } catch (IOException e) {
            logger.warn("Could not delete BPMN file for model {} at {}: {}", modelId, target.toAbsolutePath(),
                    e.toString());
            return false;
        }
    }
}
