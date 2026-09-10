package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;

// Verifies the filesystem boundary invariant directly: generatedPlatformPath must not be a
// descendant of workbenchRootPath.
class GeneratedTargetPlatformFilesystemBoundaryTest {

    @TempDir
    Path sandbox;

        // Verifies generation writes strictly within project directory without modifying template files.
    private Path workbenchRoot;
    private Path templateDir;
    private Path outputDir;

    @BeforeEach
    void buildFakeWorkbenchAndTemplate() throws IOException {
        workbenchRoot = sandbox.resolve("metaml-workbench-source-of-truth");
        templateDir = workbenchRoot.resolve("templates/camundademo");
        outputDir = sandbox.resolve("generated-target-platforms");

        write(templateDir.resolve("pom.xml"), "<project>fake pom</project>");
        write(templateDir.resolve("src/main/resources/processes/loanApproval.bpmn"), "<bpmn>placeholder demo</bpmn>");
        write(templateDir.resolve("src/main/java/com/example/camundademo/delegate/CalculateInterestService.java"),
                "placeholder delegate");
        write(templateDir.resolve("src/main/java/com/example/camundademo/controller/Camundacontroller.java"),
                "placeholder controller, hardcoded to loanApproval");
        write(templateDir.resolve("src/main/java/com/example/camundademo/context/LoanApplicationContext.java"),
                "placeholder, inert in the real template too");
        write(templateDir.resolve(
                        "src/main/java/com/example/camundademo/utils/restmappings/BPMNProcessRESTMappings.java"),
                "placeholder mappings");
        write(templateDir.resolve("src/main/java/com/example/camundademo/security/WebSecurityConfig.java"),
                "unrelated file that must survive the copy untouched");
    }

    private SpringBootProjectGenerator generator() {
        return new SpringBootProjectGenerator(templateDir.toString(), outputDir.toString(),
                new TwinModelGenerator(), new DelegateClassGenerator(), new ExternalTaskWorkerGenerator());
    }

    @Test
    void generatedProjectDirectoryIsNotADescendantOfTheWorkbenchRoot() {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());

        Path generatedRoot = project.directory().toAbsolutePath().normalize();
        Path workbenchRootNormalized = workbenchRoot.toAbsolutePath().normalize();

        // The actual invariant: not a prefix match on strings, a real ancestor check on
        // normalized/canonical paths - "outside" has to survive ".." segments and symlinks, not
        // just look different as text.
        assertThat(generatedRoot.startsWith(workbenchRootNormalized)).isFalse();
        // And confirm it landed exactly where a sibling of the workbench root should - proving the
        // assertion above isn't vacuously true because generation failed to write anything.
        assertThat(generatedRoot.startsWith(outputDir.toAbsolutePath().normalize())).isTrue();
    }

    @Test
    void generatedProjectStillHasItsOwnPomEvenThoughItLivesOutsideTheWorkbench() {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());

        assertThat(project.directory().resolve("pom.xml")).exists();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String loanApprovalBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="loanApproval" name="Loan Approval" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="ServiceTask_1" name="Calculate Interest"
                        camunda:delegateExpression="${calculateInterestService}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }
}
