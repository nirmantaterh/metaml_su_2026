package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.generation.GeneratedProject;
import com.metaml.workbench.generation.SpringBootProjectGenerator;

class TopicCollisionTest {

    @TempDir
    Path tempDir;

    private Path outputDir;
    private static final Path REAL_TEMPLATE = Path.of("../RedCollarTP");

    private final ExternalTaskWorkerGenerator workerGenerator = new ExternalTaskWorkerGenerator();

    private static final String COLLISION_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn2:process id="Process_Collision" name="Collision Process" isExecutable="true">
                <bpmn2:startEvent id="Start" />
                <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="Task_Hyphen" />
                <bpmn2:serviceTask id="Task_Hyphen" name="Foo-Bar Task"
                    camunda:type="external" camunda:topic="Foo-Bar" />
                <bpmn2:sequenceFlow id="f2" sourceRef="Task_Hyphen" targetRef="Task_Underscore" />
                <bpmn2:serviceTask id="Task_Underscore" name="Foo_Bar Task"
                    camunda:type="external" camunda:topic="Foo_Bar" />
                <bpmn2:sequenceFlow id="f3" sourceRef="Task_Underscore" targetRef="Task_Normal" />
                <bpmn2:serviceTask id="Task_Normal" name="Verify Order Task"
                    camunda:type="external" camunda:topic="VerifyOrder" />
                <bpmn2:sequenceFlow id="f4" sourceRef="Task_Normal" targetRef="End" />
                <bpmn2:endEvent id="End" />
              </bpmn2:process>
            </bpmn2:definitions>
            """;

    @BeforeEach
    void setupOutputDir() {
        outputDir = tempDir.resolve("output");
    }

    private SpringBootProjectGenerator generator(Path templateDir) {
        return new SpringBootProjectGenerator(templateDir.toString(), outputDir.toString(),
                new TwinModelGenerator(), new DelegateClassGenerator(), workerGenerator);
    }

    private static String mvnw(Path projectDir) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = projectDir.resolve(windows ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(wrapper)) {
            return wrapper.toAbsolutePath().toString();
        }
        return windows ? "mvn.cmd" : "mvn";
    }

    @Test
    void distinctCollidingTopicsGenerateDistinctClassNamesAndRetainOriginalTopics() {
        List<GeneratedWorker> workers = workerGenerator.generate(
                COLLISION_BPMN, "com.tp.TargetPlatform.worker.proxy", false);

        assertThat(workers).hasSize(3);
        assertThat(workers).extracting(GeneratedWorker::topic)
                .containsExactly("Foo-Bar", "Foo_Bar", "VerifyOrder");

        GeneratedWorker hyphenWorker = workers.stream()
                .filter(w -> w.topic().equals("Foo-Bar")).findFirst().orElseThrow();
        GeneratedWorker underscoreWorker = workers.stream()
                .filter(w -> w.topic().equals("Foo_Bar")).findFirst().orElseThrow();
        GeneratedWorker normalWorker = workers.stream()
                .filter(w -> w.topic().equals("VerifyOrder")).findFirst().orElseThrow();

        // 1. Both workers are generated and class names are distinct
        assertThat(hyphenWorker.className()).isEqualTo("Foo_BarWorker");
        assertThat(underscoreWorker.className()).isEqualTo("Foo_Bar_2Worker");
        assertThat(normalWorker.className()).isEqualTo("VerifyOrderWorker");

        // 2. Each worker retains its original topic and implements the distinct class name
        assertThat(hyphenWorker.sourceCode())
                .contains("public class Foo_BarWorker implements GeneratedExternalTaskWorker")
                .contains("return \"Foo-Bar\";");

        assertThat(underscoreWorker.sourceCode())
                .contains("public class Foo_Bar_2Worker implements GeneratedExternalTaskWorker")
                .contains("return \"Foo_Bar\";");

        assertThat(normalWorker.sourceCode())
                .contains("public class VerifyOrderWorker implements GeneratedExternalTaskWorker")
                .contains("return \"VerifyOrder\";");
    }

    @Test
    void targetPlatformGenerationWritesBothWorkersWithoutOverwrite() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(REAL_TEMPLATE),
                "Real RedCollarTP template must exist at " + REAL_TEMPLATE.toAbsolutePath());

        SpringBootProjectGenerator projectGenerator = generator(REAL_TEMPLATE);
        GeneratedProject project = projectGenerator.generate(COLLISION_BPMN, List.of(), "topic-collision-test");

        Path workerDir = project.directory().resolve(
                "src/main/java/com/tp/TargetPlatform/worker/proxy");

        Path hyphenFile = workerDir.resolve("Foo_BarWorker.java");
        Path underscoreFile = workerDir.resolve("Foo_Bar_2Worker.java");
        Path normalFile = workerDir.resolve("VerifyOrderWorker.java");

        // Both Java source files exist simultaneously
        assertThat(hyphenFile).exists();
        assertThat(underscoreFile).exists();
        assertThat(normalFile).exists();

        // Neither worker overwrote the other
        String hyphenContent = Files.readString(hyphenFile, StandardCharsets.UTF_8);
        String underscoreContent = Files.readString(underscoreFile, StandardCharsets.UTF_8);
        String normalContent = Files.readString(normalFile, StandardCharsets.UTF_8);

        assertThat(hyphenContent)
                .contains("public class Foo_BarWorker implements GeneratedExternalTaskWorker")
                .contains("return \"Foo-Bar\";")
                .doesNotContain("return \"Foo_Bar\";");

        assertThat(underscoreContent)
                .contains("public class Foo_Bar_2Worker implements GeneratedExternalTaskWorker")
                .contains("return \"Foo_Bar\";")
                .doesNotContain("return \"Foo-Bar\";");

        assertThat(normalContent)
                .contains("public class VerifyOrderWorker implements GeneratedExternalTaskWorker")
                .contains("return \"VerifyOrder\";");
    }

    @Test
    void generatedTargetPlatformWithCollidingTopicsBuildsSuccessfully() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(REAL_TEMPLATE),
                "Real RedCollarTP template must exist at " + REAL_TEMPLATE.toAbsolutePath());

        SpringBootProjectGenerator projectGenerator = generator(REAL_TEMPLATE);
        GeneratedProject project = projectGenerator.generate(COLLISION_BPMN, List.of(), "topic-collision-build-test");

        Process build = new ProcessBuilder(mvnw(project.directory()), "compile", "-DskipTests")
                .directory(project.directory().toFile())
                .redirectErrorStream(true)
                .start();
        String buildOutput = new String(build.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean buildFinished = build.waitFor(2, TimeUnit.MINUTES);
        assertThat(buildFinished).as("mvn compile timed out").isTrue();
        assertThat(build.exitValue()).as("Generated project failed to compile:%n%s", buildOutput).isZero();
    }

    @Test
    void writeWorkersThrowsExceptionIfDuplicateWorkerClassNameAttempted() {
        // Construct two workers artificially sharing the same class name to verify writeWorkers fails fast
        GeneratedWorker worker1 = new GeneratedWorker("CollidingWorker", "Topic-A", "Activity_A",
                "package com.tp.test.worker;\npublic class CollidingWorker {}");
        GeneratedWorker worker2 = new GeneratedWorker("CollidingWorker", "Topic_A", "Activity_B",
                "package com.tp.test.worker;\npublic class CollidingWorker {}");

        Path testProjectDir = tempDir.resolve("duplicate-test-project");

        // Invoking writeWorkers via SpringBootProjectGenerator helper logic
        // We verify that having identical class names targeting the same path fails rather than silently overwriting
        assertThatThrownBy(() -> {
            java.lang.reflect.Method method = SpringBootProjectGenerator.class.getDeclaredMethod(
                    "writeWorkers", Path.class, List.class);
            method.setAccessible(true);
            try {
                method.invoke(generator(tempDir), testProjectDir, List.of(worker1, worker2));
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate worker file path would overwrite existing worker");
    }
}
