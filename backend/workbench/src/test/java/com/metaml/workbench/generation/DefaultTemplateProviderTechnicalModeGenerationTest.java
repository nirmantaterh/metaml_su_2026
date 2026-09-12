package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;

class DefaultTemplateProviderTechnicalModeGenerationTest {

    private static final Path DEFAULT_TEMPLATE = Path.of("../../templates/camundademo");

    @TempDir
    Path tempDir;

    @Test
    void defaultTemplateCopiesGenericProviderTechnicalModeSupportForAnyProcess() throws Exception {
        assertThat(DEFAULT_TEMPLATE).isDirectory();
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(DEFAULT_TEMPLATE.toString(),
                tempDir.resolve("generated").toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());

        GeneratedProject project = generator.generate(bpmn("VendorOnboarding"), List.of(), "VendorOnboarding");
        Path sourceRoot = project.directory().resolve("src/main/java/com/metaml/targetplatform/vendoronboarding");
        Path capability = sourceRoot.resolve("capability");

        assertThat(capability.resolve("ProviderTechnicalMode.java")).exists();
        assertThat(capability.resolve("ProviderTechnicalModeRegistry.java")).exists();
        assertThat(capability.resolve("ProviderTechnicalFailureException.java")).exists();
        assertThat(capability.resolve("FailureModeComponentExecutor.java")).exists();
        assertThat(Files.readString(capability.resolve("CapabilityConfig.java")))
                .contains("FailureModeComponentExecutor").contains("ProviderTechnicalModeRegistry");
        assertThat(Files.readString(sourceRoot.resolve("platform/GeneratedPlatformInfoController.java")))
                .contains("/providers/technical-modes").contains("/providers/{providerIdentity}/technical-mode");
        assertThat(Files.readString(project.directory().resolve("src/main/resources/static/index.html")))
                .contains("NORMAL").contains("TECHNICAL_FAILURE").contains("Simulate Technical Failure");
    }

    private static String bpmn(String processId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="%s" name="%s" isExecutable="true">
                    <bpmn2:startEvent id="Start" /><bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(processId, processId);
    }
}
