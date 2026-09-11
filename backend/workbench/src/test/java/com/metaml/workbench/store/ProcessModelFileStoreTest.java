package com.metaml.workbench.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessModelFileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savingAModelWritesItsRawBpmnAsItsOwnFileNamedAfterTheModelId() throws Exception {
        Path modelsDir = tempDir.resolve("models");
        ProcessModelFileStore store = new ProcessModelFileStore(modelsDir.toString());

        Path written = store.save("model-123", "<bpmn>content</bpmn>");

        assertThat(written).isEqualTo(modelsDir.resolve("model-123.bpmn"));
        assertThat(Files.readString(written, StandardCharsets.UTF_8)).isEqualTo("<bpmn>content</bpmn>");
    }

    @Test
    void savingTwiceUnderTheSameIdOverwritesRatherThanAppendsOrFailing() throws Exception {
        Path modelsDir = tempDir.resolve("models");
        ProcessModelFileStore store = new ProcessModelFileStore(modelsDir.toString());

        store.save("model-1", "<bpmn>first</bpmn>");
        store.save("model-1", "<bpmn>second</bpmn>");

        assertThat(Files.readString(store.pathFor("model-1"))).isEqualTo("<bpmn>second</bpmn>");
    }

    @Test
    void theModelsDirectoryIsCreatedOnFirstSaveIfItDoesNotAlreadyExist() {
        Path modelsDir = tempDir.resolve("nested").resolve("models");
        ProcessModelFileStore store = new ProcessModelFileStore(modelsDir.toString());

        assertThat(Files.exists(modelsDir)).isFalse();
        store.save("model-1", "<bpmn/>");
        assertThat(Files.isDirectory(modelsDir)).isTrue();
    }

    @Test
    void existsReflectsWhetherThatModelsFileIsActuallyOnDisk() {
        Path modelsDir = tempDir.resolve("models");
        ProcessModelFileStore store = new ProcessModelFileStore(modelsDir.toString());

        assertThat(store.exists("model-1")).isFalse();
        store.save("model-1", "<bpmn/>");
        assertThat(store.exists("model-1")).isTrue();
    }

    @Test
    void aTraversalShapedModelIdCannotWriteOutsideTheModelsDirectory() {
        Path modelsDir = tempDir.resolve("models");
        Path outside = tempDir.resolve("outside");
        ProcessModelFileStore store = new ProcessModelFileStore(modelsDir.toString());

        assertThatThrownBy(() -> store.save("../outside/escaped", "<bpmn/>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the models directory");

        assertThat(outside).doesNotExist();
        assertThat(outside.resolve("escaped.bpmn")).doesNotExist();
    }

    @Test
    void anAbsolutePathModelIdCannotWriteOutsideTheModelsDirectory() {
        Path modelsDir = tempDir.resolve("models");
        Path absoluteTarget = tempDir.resolve("absolute-escape");
        ProcessModelFileStore store = new ProcessModelFileStore(modelsDir.toString());

        assertThatThrownBy(() -> store.save(absoluteTarget.toString(), "<bpmn/>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the models directory");

        assertThat(Path.of(absoluteTarget + ".bpmn")).doesNotExist();
    }

    @Test
    void blankModelIdIsRejectedRatherThanWritingAMalformedFilename() {
        ProcessModelFileStore store = new ProcessModelFileStore(tempDir.resolve("models").toString());

        assertThatThrownBy(() -> store.save("", "<bpmn/>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(null, "<bpmn/>"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
