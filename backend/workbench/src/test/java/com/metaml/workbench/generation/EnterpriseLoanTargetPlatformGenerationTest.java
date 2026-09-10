package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;

class EnterpriseLoanTargetPlatformGenerationTest {

    @TempDir
    Path tempDir;

    private static final Path REAL_TEMPLATE = resolveTemplate();
    private static final Path FIXTURE_PATH = resolveFixturePath();

    private static Path resolveTemplate() {
        Path[] candidates = new Path[] {
            Path.of("../RedCollarTP"),
            Path.of("backend/RedCollarTP"),
            Path.of("../../backend/RedCollarTP")
        };
        for (Path c : candidates) {
            if (Files.isDirectory(c)) return c;
        }
        return Path.of("../RedCollarTP");
    }

    private static Path resolveFixturePath() {
        Path[] candidates = new Path[] {
            Path.of("../../demo/enterprise-loan-origination.bpmn"),
            Path.of("demo/enterprise-loan-origination.bpmn"),
            Path.of("../demo/enterprise-loan-origination.bpmn"),
            Path.of("../../../demo/enterprise-loan-origination.bpmn")
        };
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) return c;
        }
        return Path.of("../../demo/enterprise-loan-origination.bpmn");
    }

    @Test
    void generatesCompleteTargetPlatformStructureFromEnterpriseLoanFixture() throws IOException {
        assertThat(Files.isDirectory(REAL_TEMPLATE))
                .as("RedCollarTP template must exist at %s", REAL_TEMPLATE.toAbsolutePath()).isTrue();
        assertThat(Files.isRegularFile(FIXTURE_PATH))
                .as("Enterprise loan fixture must exist at %s", FIXTURE_PATH.toAbsolutePath()).isTrue();

        String bpmnXml = Files.readString(FIXTURE_PATH, StandardCharsets.UTF_8);

        Path outputDir = tempDir.resolve("generated-target-platforms");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(
                REAL_TEMPLATE.toString(),
                outputDir.toString(),
                new TwinModelGenerator(),
                new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator());

        GeneratedProject project = generator.generate(bpmnXml, List.of());

        assertThat(project).isNotNull();
        assertThat(project.directory()).exists();
        assertThat(project.processKey()).isEqualTo("EnterpriseLoanOrigination");

        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");

        // 1. Verify Task Listeners (Proxy & Twin)
        Path proxyCreateListener = tpRoot.resolve("proxy/listeners/LoanApplicationCreateListener.java");
        Path proxyCompleteListener = tpRoot.resolve("proxy/listeners/LoanApplicationCompleteListener.java");
        Path twinCreateListener = tpRoot.resolve("twin/listeners/LoanApplicationCreateListenerTwin.java");
        Path twinCompleteListener = tpRoot.resolve("twin/listeners/LoanApplicationCompleteListenerTwin.java");

        assertThat(proxyCreateListener).exists();
        assertThat(proxyCompleteListener).exists();
        assertThat(twinCreateListener).exists();
        assertThat(twinCompleteListener).exists();

        // 2. Verify Execution Listeners (Proxy & Twin)
        Path proxyExecListener = tpRoot.resolve("proxy/listeners/LoanAuditExecutionListener.java");
        Path twinExecListener = tpRoot.resolve("twin/listeners/LoanAuditExecutionListenerTwin.java");
        assertThat(proxyExecListener).exists();
        assertThat(twinExecListener).exists();

        // 3. Verify External Task Workers (Proxy & Twin)
        assertThat(tpRoot.resolve("worker/proxy/StandardUnderwriteWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/proxy/EnhancedDiligenceWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/proxy/VerifyEmploymentWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/proxy/VerifyCollateralWorker.java")).exists();

        assertThat(tpRoot.resolve("worker/twin/StandardUnderwriteTwinWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/twin/EnhancedDiligenceTwinWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/twin/VerifyEmploymentTwinWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/twin/VerifyCollateralTwinWorker.java")).exists();

        // 4. Verify Delegate Beans (Proxy & Twin)
        assertThat(tpRoot.resolve("proxy/delegates/Task_DisburseFunds.java")).exists();
        assertThat(tpRoot.resolve("twin/delegates/Task_DisburseFunds.java")).exists();

        // 5. Verify Process Resources
        Path processesDir = project.directory().resolve("src/main/resources/processes");
        assertThat(processesDir.resolve("EnterpriseLoanOrigination.bpmn")).exists();
        assertThat(processesDir.resolve("EnterpriseLoanOrigination_twin.bpmn")).exists();

        // 6. Verify MetaML Properties Metadata
        Path propertiesFile = project.directory().resolve(".metaml-project.properties");
        assertThat(propertiesFile).exists();
        String propertiesContent = Files.readString(propertiesFile);
        assertThat(propertiesContent).contains("processKey=EnterpriseLoanOrigination");
    }
}
