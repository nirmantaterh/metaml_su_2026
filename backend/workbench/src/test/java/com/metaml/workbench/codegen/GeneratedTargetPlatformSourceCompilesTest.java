package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// The delegate/listener bodies are built by String.formatted() inside the generator, so a wrong
// argument count or order produces source that only fails when a generated project is compiled -
// minutes later, in a child Maven process, with the error buried in build.log. This compiles what the
// generator emits, in-process, against the same Camunda/SLF4J/Spring API the generated project uses.
class GeneratedTargetPlatformSourceCompilesTest {

    private final TargetPlatformSourceGenerator generator = new TargetPlatformSourceGenerator();

    // Covers all three renderers at once: a delegated serviceTask, an execution listener and a task
    // listener on a userTask (task listeners only ever attach to one).
    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn2:process id="Process_Sample" name="Sample" isExecutable="true">
                <bpmn2:startEvent id="Start">
                  <bpmn2:outgoing>Flow_1</bpmn2:outgoing>
                </bpmn2:startEvent>
                <bpmn2:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="Activity_Evaluate" />
                <bpmn2:serviceTask id="Activity_Evaluate" name="Evaluate Application"
                    camunda:delegateExpression="${activity_Evaluate}">
                  <bpmn2:extensionElements>
                    <camunda:executionListener event="end" delegateExpression="${auditListener}" />
                  </bpmn2:extensionElements>
                  <bpmn2:incoming>Flow_1</bpmn2:incoming>
                  <bpmn2:outgoing>Flow_2</bpmn2:outgoing>
                </bpmn2:serviceTask>
                <bpmn2:sequenceFlow id="Flow_2" sourceRef="Activity_Evaluate" targetRef="Activity_Review" />
                <bpmn2:userTask id="Activity_Review" name="Review">
                  <bpmn2:extensionElements>
                    <camunda:taskListener event="create" delegateExpression="${reviewListener}" />
                  </bpmn2:extensionElements>
                  <bpmn2:incoming>Flow_2</bpmn2:incoming>
                  <bpmn2:outgoing>Flow_3</bpmn2:outgoing>
                </bpmn2:userTask>
                <bpmn2:sequenceFlow id="Flow_3" sourceRef="Activity_Review" targetRef="End" />
                <bpmn2:endEvent id="End">
                  <bpmn2:incoming>Flow_3</bpmn2:incoming>
                </bpmn2:endEvent>
              </bpmn2:process>
            </bpmn2:definitions>
            """;

    @Test
    void everySourceTheGeneratorEmitsForTheProxySideIsValidJava(@TempDir Path workspace) throws IOException {
        assertCompiles(generator.generate(BPMN, false).sources(), workspace);
    }

    @Test
    void everySourceTheGeneratorEmitsForTheTwinSideIsValidJava(@TempDir Path workspace) throws IOException {
        assertCompiles(generator.generate(BPMN, true).sources(), workspace);
    }

    @Test
    void generatedDelegatesLogThroughSlf4jWithGenericProcessContext() {
        // A bare System.out line carries no timestamp, level or process context, which makes it
        // unusable as the runtime evidence the Target Platform's own log has to provide.
        String delegate = sourceOf(generator.generate(BPMN, false).sources(), "Activity_Evaluate");

        assertThat(delegate)
                .doesNotContain("System.out")
                .contains("org.slf4j.Logger")
                .contains("DELEGATE INVOKED")
                .contains("DELEGATE COMPLETED")
                .contains("getProcessDefinitionId()")
                .contains("getProcessInstanceId()")
                .contains("getCurrentActivityId()")
                .contains("getActivityInstanceId()")
                .contains("getProcessBusinessKey()");
    }

    @Test
    void generatedListenersLogThroughSlf4jToo() {
        List<TargetPlatformSourceGenerator.GeneratedSource> sources = generator.generate(BPMN, false).sources();

        assertThat(sourceOf(sources, "AuditListener"))
                .doesNotContain("System.out")
                .contains("getEventName()")
                .contains("getActivityInstanceId()");
        assertThat(sourceOf(sources, "ReviewListener"))
                .doesNotContain("System.out")
                .contains("getTaskDefinitionKey()")
                .contains("getEventName()");
    }

    @Test
    void noGeneratedSourceCarriesAProcessSpecificName() {
        // Genericity guard: everything the generator writes is derived from the model it was handed.
        for (TargetPlatformSourceGenerator.GeneratedSource source : generator.generate(BPMN, true).sources()) {
            assertThat(source.source().toLowerCase(Locale.ROOT))
                    .as("generated source %s", source.className())
                    .doesNotContain("redcollar")
                    .doesNotContain("manufactur")
                    .doesNotContain("orderapproved")
                    .doesNotContain("qualitypassed");
        }
    }

    private static String sourceOf(List<TargetPlatformSourceGenerator.GeneratedSource> sources, String classHint) {
        return sources.stream()
                .filter(source -> source.className().equalsIgnoreCase(TargetPlatformSourceGenerator.pascal(classHint)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no generated source for " + classHint + " among "
                        + sources.stream().map(TargetPlatformSourceGenerator.GeneratedSource::className).toList()))
                .source();
    }

    private static void assertCompiles(List<TargetPlatformSourceGenerator.GeneratedSource> sources, Path workspace)
            throws IOException {
        assertThat(sources).as("generator produced nothing to compile").isNotEmpty();
        List<Path> files = new ArrayList<>();
        for (TargetPlatformSourceGenerator.GeneratedSource source : sources) {
            Path directory = workspace.resolve("com/tp/TargetPlatform").resolve(source.relativeDirectory());
            Files.createDirectories(directory);
            Path file = directory.resolve(source.className() + ".java");
            Files.writeString(file, source.source(), StandardCharsets.UTF_8);
            files.add(file);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("this test needs a JDK, not a JRE").isNotNull();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            // The generated project compiles against Camunda, SLF4J and Spring; this module already has
            // all three on its own test classpath, so reusing it needs no separate dependency set.
            List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString());
            boolean succeeded = compiler.getTask(null, fileManager, diagnostics, options, null,
                    fileManager.getJavaFileObjectsFromPaths(files)).call();
            assertThat(succeeded)
                    .as("generated sources did not compile:%n%s", diagnostics.getDiagnostics().stream()
                            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                            .map(Object::toString)
                            .reduce("", (a, b) -> a + b + System.lineSeparator()))
                    .isTrue();
        }
    }
}
