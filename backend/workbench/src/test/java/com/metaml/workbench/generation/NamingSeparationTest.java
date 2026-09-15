package com.metaml.workbench.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
import static org.assertj.core.api.Assertions.assertThat;

class NamingSeparationTest {

    @TempDir
    Path tempDir;

    private Path templateDir;
    private Path outputDir;

    @BeforeEach
    void setUp() throws IOException {
        templateDir = tempDir.resolve("template");
        outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);
        createMockTemplate(templateDir);
    }

    private void createMockTemplate(Path root) throws IOException {
        Path mainPackage = root.resolve("src/main/java/com/example/camundademo");
        Files.createDirectories(mainPackage);
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>camundademo</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                </project>
                """);
        Files.writeString(mainPackage.resolve("CamundademoApplication.java"), """
                package com.example.camundademo;

                import org.springframework.boot.SpringApplication;

                public class CamundademoApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(CamundademoApplication.class, args);
                    }
                }
                """);
    }

    private SpringBootProjectGenerator generator() {
        return new SpringBootProjectGenerator(templateDir.toString(), outputDir.toString(),
                new com.metaml.workbench.bpmn.TwinModelGenerator(),
                new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
    }

    private static String minimalBpmnXml(String processKey) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn:process id="%s" isExecutable="true">
                    <bpmn:startEvent id="Start_1"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(processKey);
    }

    @Test
    void toJavaClassNameSanitizationRules() {
        assertThat(SpringBootProjectGenerator.toJavaClassName("Red Collar Manufacturing"))
                .isEqualTo("RedCollarManufacturingApplication");
        assertThat(SpringBootProjectGenerator.toJavaClassName("Wire Transfer Review"))
                .isEqualTo("WireTransferReviewApplication");
        assertThat(SpringBootProjectGenerator.toJavaClassName("Graduate Admissions Review"))
                .isEqualTo("GraduateAdmissionsReviewApplication");
        assertThat(SpringBootProjectGenerator.toJavaClassName("red-collar-manufacturing-2"))
                .isEqualTo("RedCollarManufacturing2Application");
        assertThat(SpringBootProjectGenerator.toJavaClassName("AI / ML Pipeline"))
                .isEqualTo("AiMlPipelineApplication");
        assertThat(SpringBootProjectGenerator.toJavaClassName("Order #42: Approval"))
                .isEqualTo("Order42ApprovalApplication");
        assertThat(SpringBootProjectGenerator.toJavaClassName("123 Approval Process"))
                .isEqualTo("App123ApprovalProcessApplication");
        assertThat(SpringBootProjectGenerator.toJavaClassName(null))
                .isEqualTo("TargetPlatformApplication");
        assertThat(SpringBootProjectGenerator.toJavaClassName("   "))
                .isEqualTo("TargetPlatformApplication");
    }

    @Test
    void nullOrBlankDisplayNameFallbackTest() throws IOException {
        SpringBootProjectGenerator gen = generator();
        String bpmn = minimalBpmnXml("sampleProcessKey"); // valid process key, but display name is null or blank
        GeneratedProject project = gen.generate(bpmn, List.of(), "   ");

        assertThat(project.displayName()).isEqualTo("sampleProcessKey");
        assertThat(project.directory().getFileName().toString()).isEqualTo("sampleprocesskey");
        assertThat(project.projectId()).isNotEqualTo("sampleProcessKey");

        Path pom = project.directory().resolve("pom.xml");
        assertThat(Files.readString(pom)).contains("<artifactId>sampleprocesskey</artifactId>");
    }

    @Test
    void legacyUuidFolderLookupWithoutMetadataFileSucceeds() throws IOException {
        SpringBootProjectGenerator gen = generator();
        String bpmn = minimalBpmnXml("legacyProcess");
        Path legacyDir = outputDir.resolve("9437d061-0bc2-4219-444a-551f31f10000");
        Path processesDir = legacyDir.resolve("src/main/resources/processes");
        Files.createDirectories(processesDir);
        Files.writeString(processesDir.resolve("legacyProcess.bpmn"), bpmn);

        Path found = gen.findProjectDirectoryById("9437d061-0bc2-4219-444a-551f31f10000");
        assertThat(found).isEqualTo(legacyDir);

        List<GeneratedProject> scanned = gen.scanExisting();
        assertThat(scanned).extracting(GeneratedProject::projectId).contains("9437d061-0bc2-4219-444a-551f31f10000");
    }

    @Test
    void generateSeperateIdentitiesAndArtifacts() throws IOException {
        SpringBootProjectGenerator gen = generator();
        String bpmn = minimalBpmnXml("redcollarmanuf");
        String displayName = "Red Collar Manufacturing";

        GeneratedProject project = gen.generate(bpmn, List.of(), displayName);

        assertThat(project.displayName()).isEqualTo(displayName);
        assertThat(project.projectId()).isNotEqualTo(displayName);
        assertThat(project.directory().getFileName().toString()).isEqualTo("red-collar-manufacturing");

        // Verify Maven pom.xml artifactId rewrite
        Path pom = project.directory().resolve("pom.xml");
        assertThat(pom).exists();
        assertThat(Files.readString(pom)).contains("<artifactId>red-collar-manufacturing</artifactId>");

        // Verify Java Application class rewrite
        Path appClass = project.directory().resolve("src/main/java/com/metaml/targetplatform/redcollarmanuf/RedCollarManufacturingApplication.java");
        assertThat(appClass).exists();
        assertThat(Files.readString(appClass)).contains("public class RedCollarManufacturingApplication");

        // Verify .metaml-project.properties metadata
        Path metadata = project.directory().resolve(".metaml-project.properties");
        assertThat(metadata).exists();
        String metaContent = Files.readString(metadata);
        assertThat(metaContent).contains("projectId=" + project.projectId());
        assertThat(metaContent).contains("processKey=redcollarmanuf");
        assertThat(metaContent).contains("displayName=Red Collar Manufacturing");

        // Verify scanExisting restores display name
        List<GeneratedProject> existing = gen.scanExisting();
        assertThat(existing).hasSize(1);
        assertThat(existing.get(0).displayName()).isEqualTo(displayName);
        assertThat(existing.get(0).projectId()).isEqualTo(project.projectId());
    }

    @Test
    void workbenchGenerationUsesProjectAndModelNamesForNestedDirectoryAndProxyResource() throws IOException {
        SpringBootProjectGenerator gen = generator();
        String bpmn = minimalBpmnXml("technicalProcessKey");

        GeneratedProject project = gen.generate(bpmn, List.of(), "TestProj", "ProcessSample");

        assertThat(project.directory()).isEqualTo(outputDir.resolve("TestProj").resolve("ProcessSample"));
        assertThat(project.directory().resolve("pom.xml")).exists();
        Path proxy = project.directory().resolve("src/main/resources/processes/ProcessSample.bpmn");
        assertThat(proxy).exists();
        assertThat(Files.readString(proxy)).contains("id=\"technicalProcessKey\"");
        assertThat(project.directory().resolve("src/main/resources/processes/technicalProcessKey.bpmn")).doesNotExist();
        assertThat(gen.scanExisting()).extracting(GeneratedProject::projectId).contains(project.projectId());
        assertThat(gen.findProjectDirectoryById(project.projectId())).isEqualTo(project.directory());
    }

    @Test
    void nestedProjectDirectoriesAvoidCollisionsAndReuseCanonicalPathAfterStoppedCleanup() throws IOException {
        SpringBootProjectGenerator gen = generator();
        String bpmn = minimalBpmnXml("technicalProcessKey");

        GeneratedProject first = gen.generate(bpmn, List.of(), "TestProj", "ProcessSample");
        GeneratedProject otherProject = gen.generate(bpmn, List.of(), "OtherProj", "ProcessSample");
        GeneratedProject otherProcess = gen.generate(bpmn, List.of(), "TestProj", "OtherProcess");

        assertThat(otherProject.directory()).isEqualTo(outputDir.resolve("OtherProj").resolve("ProcessSample"));
        assertThat(otherProcess.directory()).isEqualTo(outputDir.resolve("TestProj").resolve("OtherProcess"));

        assertThat(gen.delete(first.projectId())).isTrue();
        GeneratedProject regenerated = gen.generate(bpmn, List.of(), "TestProj", "ProcessSample");
        assertThat(regenerated.directory()).isEqualTo(outputDir.resolve("TestProj").resolve("ProcessSample"));
        assertThat(regenerated.directory().getFileName().toString()).doesNotContain("-2");
    }

    @Test
    void runningPredecessorUsesTemporarySiblingUntilItCanBePromoted() throws IOException {
        SpringBootProjectGenerator gen = generator();
        String bpmn = minimalBpmnXml("technicalProcessKey");
        GeneratedProject running = gen.generate(bpmn, List.of(), "TestProj", "ProcessSample");
        GeneratedProject replacement = gen.generate(bpmn, List.of(), "TestProj", "ProcessSample");

        assertThat(replacement.directory().getFileName().toString())
                .startsWith("ProcessSample--running-");
        assertThat(gen.scanExisting()).extracting(GeneratedProject::projectId)
                .contains(running.projectId(), replacement.projectId());

        assertThat(gen.delete(running.projectId())).isTrue();
        GeneratedProject promoted = gen.promoteToCanonicalPath(replacement);
        assertThat(promoted.directory()).isEqualTo(outputDir.resolve("TestProj").resolve("ProcessSample"));
        assertThat(gen.findProjectDirectoryById(promoted.projectId())).isEqualTo(promoted.directory());
    }

    @Test
    void collisionHandlingProducesSuffixedDirectoryAndClassName() throws IOException {
        SpringBootProjectGenerator gen = generator();
        String bpmn = minimalBpmnXml("redcollarmanuf");
        String displayName = "Red Collar Manufacturing";

        GeneratedProject first = gen.generate(bpmn, List.of(), displayName);
        GeneratedProject second = gen.generate(bpmn, List.of(), displayName);

        assertThat(first.directory().getFileName().toString()).isEqualTo("red-collar-manufacturing");
        assertThat(second.directory().getFileName().toString()).isEqualTo("red-collar-manufacturing-2");

        Path secondPom = second.directory().resolve("pom.xml");
        assertThat(Files.readString(secondPom)).contains("<artifactId>red-collar-manufacturing-2</artifactId>");

        Path secondAppClass = second.directory().resolve("src/main/java/com/metaml/targetplatform/redcollarmanuf/RedCollarManufacturing2Application.java");
        assertThat(secondAppClass).exists();
        assertThat(Files.readString(secondAppClass)).contains("public class RedCollarManufacturing2Application");
    }

    @Test
    void wireTransferReviewBpmnNamingSeparation() throws IOException {
        SpringBootProjectGenerator gen = generator();
        Path bpmnFile = Path.of("../../demo/wire-transfer-review.bpmn");
        if (!Files.exists(bpmnFile)) {
            bpmnFile = Path.of("demo/wire-transfer-review.bpmn");
        }
        assertThat(bpmnFile).exists();
        String bpmnXml = Files.readString(bpmnFile);
        String displayName = "Wire Transfer Review";

        GeneratedProject project = gen.generate(bpmnXml, List.of(), displayName);

        assertThat(project.displayName()).isEqualTo("Wire Transfer Review");
        assertThat(project.processKey()).isEqualTo("wireTransferReview");
        assertThat(project.directory().getFileName().toString()).isEqualTo("wire-transfer-review");

        Path pom = project.directory().resolve("pom.xml");
        assertThat(Files.readString(pom)).contains("<artifactId>wire-transfer-review</artifactId>");

        Path appClass = project.directory().resolve("src/main/java/com/metaml/targetplatform/wiretransferreview/WireTransferReviewApplication.java");
        assertThat(appClass).exists();
        assertThat(Files.readString(appClass)).contains("public class WireTransferReviewApplication");

        Path metadata = project.directory().resolve(".metaml-project.properties");
        String metaContent = Files.readString(metadata);
        assertThat(metaContent).contains("projectId=" + project.projectId());
        assertThat(metaContent).contains("processKey=wireTransferReview");
        assertThat(metaContent).contains("displayName=Wire Transfer Review");
    }

    @Test
    void generatedProjectMavenBuildCompiles() throws IOException, InterruptedException {
        Path realTemplate = Path.of("../../templates/camundademo");
        if (!Files.isDirectory(realTemplate)) {
            realTemplate = Path.of("templates/camundademo");
        }
        assertThat(realTemplate).isDirectory();

        SpringBootProjectGenerator gen = new SpringBootProjectGenerator(realTemplate.toString(), outputDir.toString(),
                new com.metaml.workbench.bpmn.TwinModelGenerator(),
                new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());
        Path bpmnFile = Path.of("../../demo/wire-transfer-review.bpmn");
        if (!Files.exists(bpmnFile)) {
            bpmnFile = Path.of("demo/wire-transfer-review.bpmn");
        }
        String bpmnXml = Files.readString(bpmnFile);
        String displayName = "Wire Transfer Review";

        GeneratedProject project = gen.generate(bpmnXml, List.of(), displayName);

        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "mvn.cmd compile -B");
        pb.directory(project.directory().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = p.waitFor();

        assertThat(exitCode).describedAs("Generated Target Platform maven compilation exit code. Output:\n" + output).isEqualTo(0);
        assertThat(project.directory().resolve("target/classes/com/metaml/targetplatform/wiretransferreview/WireTransferReviewApplication.class")).exists();
    }

    @Test
    void friedRiceWorkflowEndToEnd() throws IOException, InterruptedException {
        Path realTemplate = Path.of("../../templates/camundademo");
        if (!Files.isDirectory(realTemplate)) {
            realTemplate = Path.of("templates/camundademo");
        }
        assertThat(realTemplate).isDirectory();

        SpringBootProjectGenerator gen = new SpringBootProjectGenerator(realTemplate.toString(), outputDir.toString(),
                new com.metaml.workbench.bpmn.TwinModelGenerator(),
                new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());

        String friedRiceBpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                  id="Definitions_FriedRice" targetNamespace="http://metaml.com/bpmn">
                  <bpmn:process id="Process_1" name="Fried Rice Cooking" isExecutable="true">
                    <bpmn:startEvent id="start_cooking" name="Start">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="start_cooking" targetRef="prepareingredients"/>
                    <bpmn:serviceTask id="prepareingredients" name="prep ingredients" camunda:delegateExpression="${prepareingredients}">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="prepareingredients" targetRef="heatwok"/>
                    <bpmn:serviceTask id="heatwok" name="heat wok" camunda:delegateExpression="${heatwok}">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                      <bpmn:outgoing>Flow_3</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:sequenceFlow id="Flow_3" sourceRef="heatwok" targetRef="scrambleeggs"/>
                    <bpmn:serviceTask id="scrambleeggs" name="scramble eggs" camunda:delegateExpression="${scrambleeggs}">
                      <bpmn:incoming>Flow_3</bpmn:incoming>
                      <bpmn:outgoing>Flow_4</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:sequenceFlow id="Flow_4" sourceRef="scrambleeggs" targetRef="stir_fry_aromatics"/>
                    <bpmn:serviceTask id="stir_fry_aromatics" name="stir fry aromatics" camunda:delegateExpression="${stir_fry_aromatics}">
                      <bpmn:incoming>Flow_4</bpmn:incoming>
                      <bpmn:outgoing>Flow_5</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:sequenceFlow id="Flow_5" sourceRef="stir_fry_aromatics" targetRef="add_rice"/>
                    <bpmn:serviceTask id="add_rice" name="add day old rice" camunda:delegateExpression="${add_rice}">
                      <bpmn:incoming>Flow_5</bpmn:incoming>
                      <bpmn:outgoing>Flow_6</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:sequenceFlow id="Flow_6" sourceRef="add_rice" targetRef="s_and_toss"/>
                    <bpmn:serviceTask id="s_and_toss" name="season and toss" camunda:delegateExpression="${s_and_toss}">
                      <bpmn:incoming>Flow_6</bpmn:incoming>
                      <bpmn:outgoing>Flow_7</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:sequenceFlow id="Flow_7" sourceRef="s_and_toss" targetRef="taste_check"/>
                    <bpmn:serviceTask id="taste_check" name="taste check" camunda:delegateExpression="${taste_check}">
                      <bpmn:incoming>Flow_7</bpmn:incoming>
                      <bpmn:outgoing>Flow_8</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:sequenceFlow id="Flow_8" sourceRef="taste_check" targetRef="end"/>
                    <bpmn:endEvent id="end" name="End">
                      <bpmn:incoming>Flow_8</bpmn:incoming>
                    </bpmn:endEvent>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        String displayName = "Fried Rice Cooking";
        GeneratedProject project = gen.generate(friedRiceBpmn, List.of(), displayName);

        assertThat(project.displayName()).isEqualTo("Fried Rice Cooking");
        assertThat(project.processKey()).isEqualTo("Process_1");
        assertThat(project.directory().getFileName().toString()).isEqualTo("fried-rice-cooking");

        Path pom = project.directory().resolve("pom.xml");
        assertThat(Files.readString(pom)).contains("<artifactId>fried-rice-cooking</artifactId>");

        Path appClass = project.directory().resolve("src/main/java/com/metaml/targetplatform/process1/FriedRiceCookingApplication.java");
        assertThat(appClass).exists();
        assertThat(Files.readString(appClass)).contains("public class FriedRiceCookingApplication");

        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "mvn.cmd compile -B");
        pb.directory(project.directory().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = p.waitFor();

        assertThat(exitCode).describedAs("Fried Rice Target Platform maven compilation exit code. Output:\n" + output).isEqualTo(0);
        assertThat(project.directory().resolve("target/classes/com/metaml/targetplatform/process1/FriedRiceCookingApplication.class")).exists();
    }
}
