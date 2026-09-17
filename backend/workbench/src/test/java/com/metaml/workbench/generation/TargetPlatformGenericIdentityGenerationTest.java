package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
import com.metaml.workbench.codegen.TargetPlatformMessagingGenerator;
import com.metaml.workbench.codegen.TargetPlatformSourceGenerator;
import com.metaml.workbench.codegen.TargetPlatformTwinMirrorGenerator;

/**
 * Regression test verifying generic identity generation, clean UTF-8 README without
 * encoding artifacts or stale template names, and stack contract preservation
 * against the ACTUAL production template configured by workbench.generation.template-directory (../RedCollarTP).
 */
class TargetPlatformGenericIdentityGenerationTest {

    @TempDir
    Path tempDir;

    private static final Path PRODUCTION_TEMPLATE = Path.of("../RedCollarTP");

    private Path outputDir;
    private SpringBootProjectGenerator generator;

    @BeforeEach
    void setUp() {
        assertThat(Files.isDirectory(PRODUCTION_TEMPLATE))
                .as("Actual production template directory must exist at %s", PRODUCTION_TEMPLATE.toAbsolutePath())
                .isTrue();

        outputDir = tempDir.resolve("generated-target-platforms");
        generator = new SpringBootProjectGenerator(
                PRODUCTION_TEMPLATE.toString(),
                outputDir.toString(),
                new TwinModelGenerator(),
                new DelegateClassGenerator(),
                new ExternalTaskWorkerGenerator(),
                new TargetPlatformSourceGenerator(),
                new TargetPlatformMessagingGenerator(),
                new TargetPlatformTwinMirrorGenerator());
    }

    private static final String SOLAR_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn2:process id="solarGridControlProcess" name="Solar Grid Control Process" isExecutable="true">
                <bpmn2:startEvent id="Start" />
                <bpmn2:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="MonitorInverter" />
                <bpmn2:serviceTask id="MonitorInverter" name="Monitor Inverter" camunda:delegateExpression="${monitorInverterDelegate}" />
                <bpmn2:sequenceFlow id="Flow_2" sourceRef="MonitorInverter" targetRef="End" />
                <bpmn2:endEvent id="End" />
              </bpmn2:process>
            </bpmn2:definitions>
            """;

    @Test
    void freshGenerationProducesGenericIdentityAndPreservesStackContract() throws Exception {
        String projectDisplayName = "SolarEnergyGridMonitoring";
        String processDisplayName = "Solar Grid Control Process";

        GeneratedProject project = generator.generate(SOLAR_BPMN, List.of(), projectDisplayName, processDisplayName);

        assertThat(project).isNotNull();
        Path projectDir = project.directory();
        assertThat(projectDir).exists();

        // 1. Canonical project/process folder layout
        Path expectedDir = outputDir.resolve("SolarEnergyGridMonitoring").resolve("Solar-Grid-Control-Process");
        assertThat(projectDir.toAbsolutePath().normalize())
                .isEqualTo(expectedDir.toAbsolutePath().normalize());

        // 2. BPMN filenames
        Path processesDir = projectDir.resolve("src/main/resources/processes");
        assertThat(processesDir.resolve("Solar-Grid-Control-Process.bpmn")).isRegularFile();
        assertThat(processesDir.resolve("Solar-Grid-Control-Process-twin.bpmn")).isRegularFile();

        // 3. Maven wrapper files and metadata
        assertThat(projectDir.resolve("mvnw")).isRegularFile();
        assertThat(projectDir.resolve("mvnw.cmd")).isRegularFile();
        assertThat(projectDir.resolve(".mvn/wrapper/maven-wrapper.properties")).isRegularFile();

        // 4. README verification
        Path readmeFile = projectDir.resolve("README.md");
        assertThat(readmeFile).isRegularFile();
        String readmeContent = Files.readString(readmeFile, StandardCharsets.UTF_8);

        // Zero RedCollar / RedCollarTP in README
        assertThat(readmeContent).doesNotContain("RedCollar");
        assertThat(readmeContent).doesNotContain("RedCollarTP");
        // Zero corrupted dashes
        assertThat(readmeContent).doesNotContain("\u00e2\u20ac\u201c");
        assertThat(readmeContent).doesNotContain("\u2014");
        // Correct generic process title
        assertThat(readmeContent).startsWith("# Target Platform (Solar Grid Control Process)");
        // Accurate standalone contract wording
        assertThat(readmeContent).contains("The generated Target Platform can run independently of the MetaML Workbench.");

        // 5. pom.xml textual verification
        Path pomFile = projectDir.resolve("pom.xml");
        assertThat(pomFile).isRegularFile();
        String pomContent = Files.readString(pomFile, StandardCharsets.UTF_8);

        // Zero RedCollar / RedCollarTP in pom.xml
        assertThat(pomContent).doesNotContain("RedCollar");
        assertThat(pomContent).doesNotContain("RedCollarTP");

        // 6. pom.xml structural verification via DOM
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(pomFile.toFile());
        Element root = doc.getDocumentElement();
        assertThat(root.getNodeName()).isEqualTo("project");

        // Direct children of <project>
        NodeList children = root.getChildNodes();
        String projectArtifactId = null;
        String projectName = null;
        String projectDescription = null;
        String parentGroupId = null;
        String parentArtifactId = null;
        String parentVersion = null;

        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                String name = node.getNodeName();
                if ("artifactId".equals(name)) {
                    projectArtifactId = node.getTextContent().trim();
                } else if ("name".equals(name)) {
                    projectName = node.getTextContent().trim();
                } else if ("description".equals(name)) {
                    projectDescription = node.getTextContent().trim();
                } else if ("parent".equals(name)) {
                    NodeList parentChildren = node.getChildNodes();
                    for (int j = 0; j < parentChildren.getLength(); j++) {
                        Node pNode = parentChildren.item(j);
                        if (pNode.getNodeType() == Node.ELEMENT_NODE) {
                            if ("groupId".equals(pNode.getNodeName())) parentGroupId = pNode.getTextContent().trim();
                            if ("artifactId".equals(pNode.getNodeName())) parentArtifactId = pNode.getTextContent().trim();
                            if ("version".equals(pNode.getNodeName())) parentVersion = pNode.getTextContent().trim();
                        }
                    }
                }
            }
        }

        // Generic project identity
        assertThat(projectArtifactId).isEqualTo("solar-grid-control-process");
        assertThat(projectName).isEqualTo("Solar Grid Control Process");
        assertThat(projectDescription).isEqualTo("Target Platform - Solar Grid Control Process");

        // Parent coordinates intact and unmodified by structural rewrite
        assertThat(parentGroupId).isEqualTo("org.springframework.boot");
        assertThat(parentArtifactId).isEqualTo("spring-boot-starter-parent");
        assertThat(parentVersion).isEqualTo("4.1.0");

        // Stack contract baseline: Spring Boot 4.1.0, Java 24, Camunda 7.24.0
        assertThat(pomContent).contains("<java.version>24</java.version>");
        assertThat(pomContent).contains("<camunda.version>7.24.0</camunda.version>");
        assertThat(pomContent).contains("<version>4.1.0</version>");

        // ---- Joanna's authoritative dependency contract ----
        // Camunda starters
        assertThat(pomContent).contains("<artifactId>camunda-bpm-spring-boot-starter</artifactId>");
        assertThat(pomContent).contains("<artifactId>camunda-bpm-spring-boot-starter-webapp</artifactId>");

        // Spring Boot compile-scope starters
        assertThat(pomContent).contains("<artifactId>spring-boot-starter-actuator</artifactId>");
        assertThat(pomContent).contains("<artifactId>spring-boot-h2console</artifactId>");
        assertThat(pomContent).contains("<artifactId>spring-boot-starter-amqp</artifactId>");
        assertThat(pomContent).contains("<artifactId>spring-boot-starter-data-jpa</artifactId>");
        assertThat(pomContent).contains("<artifactId>spring-boot-starter-micrometer-metrics</artifactId>");
        assertThat(pomContent).contains("<artifactId>spring-boot-starter-restclient</artifactId>");
        assertThat(pomContent).contains("<artifactId>spring-boot-starter-security</artifactId>");
        assertThat(pomContent).contains("<artifactId>spring-boot-starter-webmvc</artifactId>");
        assertThat(pomContent).contains("<artifactId>spring-rabbit-stream</artifactId>");
        assertThat(pomContent).contains("<artifactId>lombok</artifactId>");

        // Legacy spring-boot-starter-web removed (superseded by webmvc)
        assertThat(pomContent).doesNotContain("<artifactId>spring-boot-starter-web</artifactId>");

        // ---- MetaML-specific dependencies preserved ----
        assertThat(pomContent).contains("<artifactId>capability-core</artifactId>");
        assertThat(pomContent).contains("<artifactId>reference-providers</artifactId>");

        // ---- Build plugins contract ----
        assertThat(pomContent).contains("<artifactId>asciidoctor-maven-plugin</artifactId>");
        assertThat(pomContent).contains("<artifactId>spring-boot-maven-plugin</artifactId>");
        assertThat(pomContent).contains("<artifactId>maven-compiler-plugin</artifactId>");

        // 7. application.properties verification
        Path appProperties = projectDir.resolve("src/main/resources/application.properties");
        assertThat(appProperties).isRegularFile();
        String props = Files.readString(appProperties, StandardCharsets.UTF_8);

        // Joanna's baseline configuration properties
        assertThat(props).contains("spring.application.name=TargetPlatform");
        assertThat(props).contains("management.endpoints.web.exposure.include=*");
        assertThat(props).contains("spring.jpa.hibernate.ddl-auto=update");
        assertThat(props).contains("camunda.bpm.webapp.enabled=true");
        assertThat(props).contains("camunda.bpm.deployment-resource-pattern=classpath*:/processes/*.bpmn");
        assertThat(props).contains("camunda.bpm.admin-user.id=admin");

        // MetaML-specific runtime properties preserved
        assertThat(props).contains("spring.rabbitmq.host=localhost");
        assertThat(props).contains("spring.rabbitmq.port=5672");
        assertThat(props).contains("metaml.messaging.enabled=true");
        assertThat(props).contains("spring.rabbitmq.listener.simple.retry.enabled=true");
        assertThat(props).contains("spring.rabbitmq.publisher-confirm-type=simple");
        assertThat(props).contains("spring.rabbitmq.publisher-returns=true");
        assertThat(props).contains("camunda.bpm.webapp.index-redirect-enabled=false");
        assertThat(props).contains("spring.datasource.url=jdbc:h2:mem:camunda;DB_CLOSE_DELAY=-1");
    }
}
