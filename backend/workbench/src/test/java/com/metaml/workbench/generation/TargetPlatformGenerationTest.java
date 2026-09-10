package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
import com.metaml.workbench.codegen.TargetPlatformSourceGenerator;

// Verifies target platform code generation across proxy and twin delegates and events.
class TargetPlatformGenerationTest {

    @TempDir
    Path sandbox;

    private Path templateDir;
    private Path outputDir;

    @BeforeEach
    void buildFakeTargetPlatformTemplate() throws IOException {
        templateDir = sandbox.resolve("RedCollarTP");
        outputDir = sandbox.resolve("generated-target-platforms");
        write(templateDir.resolve("pom.xml"), "<project>fake target platform pom</project>");
        // The one file isTargetPlatformTemplate() actually checks for.
        write(templateDir.resolve("src/main/java/com/tp/TargetPlatform/TargetPlatformApplication.java"),
                "package com.tp.TargetPlatform; class TargetPlatformApplication {}");
    }

    private SpringBootProjectGenerator generator() {
        return new SpringBootProjectGenerator(templateDir.toString(), outputDir.toString(),
                new TwinModelGenerator(), new DelegateClassGenerator(), new ExternalTaskWorkerGenerator());
    }

    private static String bpmn(String processId, String activityXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="%s" name="%s" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    %s
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(processId, processId, activityXml);
    }

    @Test
    void cloneScanAndGenerateProducesBothProxyAndTwinSourcesInTheirOwnFolders() {
        String proxyBpmn = bpmn("rc_proxy_process", """
                <bpmn2:serviceTask id="Cutting" name="Cutting" camunda:delegateExpression="${cutting}" />
                <bpmn2:intermediateCatchEvent id="CuttingSignal" name="Cutting Signal"
                    camunda:delegateExpression="${cuttingSignal}">
                  <bpmn2:signalEventDefinition />
                </bpmn2:intermediateCatchEvent>
                """);
        String twinBpmn = bpmn("rc_twin_process", """
                <bpmn2:serviceTask id="CuttingTwin" name="Cutting Twin"
                    camunda:delegateExpression="${cuttingTwin}" />
                """);

        GeneratedProject project = generator().generateWithAuthoredTwin(proxyBpmn, twinBpmn);

        // "Clone the template into project_dir" - the template's own files must be present in the
        // freshly generated, freestanding project directory.
        assertThat(project.directory().resolve("pom.xml")).exists();
        assertThat(project.directory()
                .resolve("src/main/java/com/tp/TargetPlatform/RcProxyProcessApplication.java")).exists();

        // Both BPMN files land under processes/, keyed by their own process id.
        assertThat(project.directory().resolve("src/main/resources/processes/rc_proxy_process.bpmn")).exists();
        assertThat(project.directory().resolve("src/main/resources/processes/rc_twin_process.bpmn")).exists();

        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");
        assertThat(tpRoot.resolve("proxy/delegates/Cutting.java")).exists();
        assertThat(tpRoot.resolve("proxy/events/CuttingSignal.java")).exists();
        assertThat(tpRoot.resolve("twin/delegates/CuttingTwin.java")).exists();
        // no twin event was authored above - the twin/events directory must exist (prepared) but
        // stay empty, not invent something that was never in the BPMN
        assertThat(tpRoot.resolve("twin/events")).isEmptyDirectory();
    }

    // Verifies that authored twins reusing proxy activity IDs produce distinct delegate bean names,
    // avoiding ConflictingBeanDefinitionException at Spring context startup.
    @Test
    void anAuthoredTwinReusingTheProxysOwnActivityIdGetsADistinctBeanNameInsteadOfCollidingAtStartup()
            throws IOException {
        String proxyBpmn = bpmn("rc_proxy_process", """
                <bpmn2:serviceTask id="add_rice" name="Add Rice" camunda:delegateExpression="${add_rice}" />
                """);
        // Deliberately reuses "add_rice" - an independently authored twin naming its own steps
        // after the same business activity, not the "_automate" suffix TwinModelGenerator would add.
        String twinBpmn = bpmn("rc_twin_process", """
                <bpmn2:serviceTask id="add_rice" name="Add Rice" camunda:delegateExpression="${add_rice}" />
                """);

        GeneratedProject project = generator().generateWithAuthoredTwin(proxyBpmn, twinBpmn);

        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");
        Path proxyDelegate = tpRoot.resolve("proxy/delegates/Add_rice.java");
        Path twinDelegate = tpRoot.resolve("twin/delegates/Add_rice.java");
        assertThat(proxyDelegate).exists();
        assertThat(twinDelegate).exists();

        String proxySource = Files.readString(proxyDelegate, StandardCharsets.UTF_8);
        String twinSource = Files.readString(twinDelegate, StandardCharsets.UTF_8);
        assertThat(proxySource).contains("@Component(\"add_rice\")");
        assertThat(twinSource).contains("@Component(\"add_riceTwin\")");
        assertThat(twinSource).doesNotContain("@Component(\"add_rice\")\n");

        // The twin BPMN's own delegateExpression must point at its own (renamed) bean, not the
        // proxy's - otherwise the engine would fail at runtime with "Cannot resolve identifier".
        String twinBpmnOut = Files.readString(
                project.directory().resolve("src/main/resources/processes/rc_twin_process.bpmn"),
                StandardCharsets.UTF_8);
        assertThat(twinBpmnOut).contains("camunda:delegateExpression=\"${add_riceTwin}\"");
    }

    @Test
    void regeneratingTheSameProjectStyleReplacesStaleGeneratedSourcesRatherThanAccumulatingThem() {
        // Verifies clearTargetPlatformGeneratedSources() safely clears generated delegates
        // without throwing or retaining conflicting delegates if BPMN activities change.
        String proxyBpmn = bpmn("rc_proxy_process", """
                <bpmn2:serviceTask id="Marking" name="Marking" camunda:delegateExpression="${marking}" />
                """);
        String twinBpmn = bpmn("rc_twin_process", "");

        GeneratedProject project = generator().generateWithAuthoredTwin(proxyBpmn, twinBpmn);

        Path generated = project.directory().resolve("src/main/java/com/tp/TargetPlatform/proxy/delegates/Marking.java");
        assertThat(generated).exists();
        assertThat(project.directory().resolve(".metaml-project.properties")).exists();
    }

    private static String bpmnWithSignal(String processId, String signalElementId, String signalName,
            String activityXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:signal id="%1$s" name="%2$s" />
                  <bpmn2:process id="%3$s" name="%3$s" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:intermediateCatchEvent id="%2$sCatch" name="%2$s catch">
                      <bpmn2:signalEventDefinition signalRef="%1$s" />
                    </bpmn2:intermediateCatchEvent>
                    %4$s
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(signalElementId, signalName, processId, activityXml);
    }

    // Verifies that a signal shared by both proxy and twin BPMNs generates the synchronization layer:
    // PairRegistry, RabbitMqConfig with dedicated queues, publishers/listeners, SignalBroadcaster,
    // and controllers under com.tp.TargetPlatform.
    @Test
    void aSignalSharedByProxyAndTwinGetsTheFullSynchronizationLayerGenerated() throws IOException {
        String proxyBpmn = bpmnWithSignal("rc_proxy_process", "Signal_Cutting", "cuttingSignal",
                "<bpmn2:serviceTask id=\"Cutting\" camunda:delegateExpression=\"${cutting}\" />");
        String twinBpmn = bpmnWithSignal("rc_twin_process", "Signal_CuttingTwin", "cuttingSignal", "");

        GeneratedProject project = generator().generateWithAuthoredTwin(proxyBpmn, twinBpmn);
        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");

        assertThat(tpRoot.resolve("coordination/PairRegistry.java")).exists();
        assertThat(tpRoot.resolve("signal/SignalBroadcaster.java")).exists();
        assertThat(tpRoot.resolve("proxy/controller/ProxyProcessController.java")).exists();
        assertThat(tpRoot.resolve("twin/controller/TwinProcessController.java")).exists();

        Path rabbitMqConfig = tpRoot.resolve("messaging/RabbitMqConfig.java");
        assertThat(rabbitMqConfig).exists();
        assertThat(Files.readString(rabbitMqConfig))
                .as("the shared signal must get its own dedicated queue pair, not just appear in a broadcast list")
                .contains("cuttingSignal");

        String proxyController = Files.readString(tpRoot.resolve("proxy/controller/ProxyProcessController.java"));
        assertThat(proxyController)
                .contains("startProcessInstanceByKey(\"rc_proxy_process\"")
                .contains("@RequestMapping(\"/api/proxy\")");
        String twinController = Files.readString(tpRoot.resolve("twin/controller/TwinProcessController.java"));
        assertThat(twinController)
                .contains("startProcessInstanceByKey(\"rc_twin_process\"")
                .contains("@RequestMapping(\"/api/twin\")");

        // Also reachable via the single-BPMN generate() path (auto-derived twin), not just
        // generateWithAuthoredTwin above - the messaging layer must not depend on which entry point
        // produced the twin BPMN.
        GeneratedProject singleBpmnProject = generator().generate(bpmn("rc_solo_process",
                "<bpmn2:serviceTask id=\"Solo\" camunda:delegateExpression=\"${solo}\" />"), java.util.List.of());
        assertThat(singleBpmnProject.directory()
                .resolve("src/main/java/com/tp/TargetPlatform/signal/SignalBroadcaster.java")).exists();
    }

    // Single-BPMN generation mirrors proxy models containing intermediate catch events.
    @Test
    void aSingleSavedBpmnWithSignalGatesAndExternalTasksAutoDerivesAMirroredTwinInsteadOfThrowing() {
        String proxyBpmnWithSignal = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:signal id="Signal_Cutting" name="cuttingSignal" />
                  <bpmn2:process id="rc_proxy_process" name="rc_proxy_process" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:intermediateCatchEvent id="CuttingCatch" name="Cutting Signal">
                      <bpmn2:signalEventDefinition signalRef="Signal_Cutting" />
                    </bpmn2:intermediateCatchEvent>
                    <bpmn2:serviceTask id="Cutting" name="Cutting" camunda:type="external" camunda:topic="Cutting" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        GeneratedProject project = generator().generate(proxyBpmnWithSignal, java.util.List.of());

        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");
        assertThat(tpRoot.resolve("signal/SignalBroadcaster.java")).exists();
        // the twin BPMN written to disk must carry the mirrored (Twin-suffixed) topic, and the
        // exact same shared signal name the proxy uses - not a rewritten one
        assertThat(project.directory().resolve("src/main/resources/processes"))
                .isDirectoryContaining(p -> p.getFileName().toString().endsWith("_twin.bpmn"));
    }

    // Verifies that TwinDecisionAgent interface is generated alongside twin workers,
    // with workers resolving agent beans via ObjectProvider fallbacks.
    @Test
    void twinDecisionAgentInterfaceIsGeneratedAlongsideTwinWorkersWithNoFragileConditionalFallbackBean()
            throws IOException {
        String proxyBpmn = bpmn("rc_proxy_process",
                "<bpmn2:serviceTask id=\"VerifyOrder\" name=\"Verify Order\" "
                        + "camunda:type=\"external\" camunda:topic=\"VerifyOrder\" />");
        String twinBpmn = bpmn("rc_twin_process",
                "<bpmn2:serviceTask id=\"VerifyOrderTwin\" name=\"Verify Order\" "
                        + "camunda:type=\"external\" camunda:topic=\"VerifyOrderTwin\" />");

        GeneratedProject project = generator().generateWithAuthoredTwin(proxyBpmn, twinBpmn);
        Path twinWorkerPackage = project.directory().resolve("src/main/java/com/tp/TargetPlatform/worker/twin");

        Path agentInterface = twinWorkerPackage.resolve("TwinDecisionAgent.java");
        assertThat(agentInterface).exists();
        assertThat(twinWorkerPackage.resolve("DefaultSimulatedTwinAgent.java"))
                .as("no separate fallback bean - see the comment above for why that combination is unsafe")
                .doesNotExist();

        assertThat(Files.readString(agentInterface))
                .contains("package com.tp.TargetPlatform.worker.twin;")
                .contains("public interface TwinDecisionAgent")
                .contains("Map<String, Object> decide(String topic, LockedExternalTask task);");

        // The generated worker itself must be the one calling through the agent (optionally, via
        // ObjectProvider), not the other way around - and it must still work standalone with zero
        // TwinDecisionAgent beans registered anywhere.
        Path verifyOrderTwinWorker = twinWorkerPackage.resolve("VerifyOrderTwinWorker.java");
        assertThat(Files.readString(verifyOrderTwinWorker))
                .contains("ObjectProvider<TwinDecisionAgent> agentProvider")
                .contains("agentProvider.getIfAvailable()")
                .contains("agent.decide(\"VerifyOrderTwin\", task)")
                .contains("No TwinDecisionAgent registered");
    }

    // The Original keeps its human task; the Twin cannot. A userTask mirrored into the Twin is a wait
    // state with no engine-side actor: nothing invokes it, so the Twin parks there, the proxy's
    // matching sync signal never receives its RESPONSE, and the pair deadlocks. This test previously
    // asserted the opposite ("stays a manual Tasklist step on both proxy and twin"), which is the
    // behaviour that made a Workbench-modelled process unable to run end to end at all.
    @Test
    void aUserTaskSurvivesOnTheOriginalButIsAutomatedIntoAServiceTaskOnTheTwin() throws IOException {
        String proxyBpmnWithUserTask = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="rc_loan_process" name="rc_loan_process" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="RiskScore" name="Risk Score"
                        camunda:type="external" camunda:topic="RiskScore" />
                    <bpmn2:userTask id="LoanOfficerApproval" name="Loan Officer Approval"
                        camunda:candidateGroups="loan-officers" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        GeneratedProject project = generator().generate(proxyBpmnWithUserTask, java.util.List.of());
        Path processesDir = project.directory().resolve("src/main/resources/processes");

        Path twinBpmnFile;
        Path proxyBpmnFile;
        try (var files = Files.list(processesDir)) {
            var written = files.toList();
            twinBpmnFile = written.stream().filter(p -> p.getFileName().toString().endsWith("_twin.bpmn"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no mirrored twin BPMN written to " + processesDir));
            proxyBpmnFile = written.stream().filter(p -> !p.getFileName().toString().endsWith("_twin.bpmn"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no proxy BPMN written to " + processesDir));
        }

        // The Original is never rewritten: the human task and its assignment survive verbatim.
        String proxyBpmnXml = Files.readString(proxyBpmnFile);
        assertThat(proxyBpmnXml)
                .contains("<bpmn2:userTask")
                .contains("id=\"LoanOfficerApproval\"")
                .contains("camunda:candidateGroups=\"loan-officers\"");

        // The Twin carries the same activity identity, but as something the engine can actually run.
        String twinBpmnXml = Files.readString(twinBpmnFile);
        assertThat(twinBpmnXml)
                .doesNotContain("<bpmn2:userTask")
                .contains("<bpmn2:serviceTask")
                .contains("id=\"LoanOfficerApproval\"")
                .contains("name=\"Loan Officer Approval\"")
                .contains("camunda:delegateExpression=\"${loanOfficerApprovalTwin}\"")
                // an assignment that can never happen on a service task is dropped, not carried over
                .doesNotContain("candidateGroups");

        Path tpRoot = project.directory().resolve("src/main/java/com/tp/TargetPlatform");
        // Twin side: a delegate exists, so the activity is executable and observable.
        assertThat(tpRoot.resolve("twin/delegates/LoanOfficerApproval.java")).exists();
        // Proxy side: still a manual Tasklist step, so nothing is generated to drive it.
        try (var files = Files.walk(tpRoot.resolve("proxy"))) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith("LoanOfficerApproval")).toList())
                    .as("the Original's human task must not be auto-driven")
                    .isEmpty();
        }

        // The external-task decision point next to it is auto-driven on both sides, unchanged.
        assertThat(tpRoot.resolve("worker/proxy/RiskScoreWorker.java")).exists();
        assertThat(tpRoot.resolve("worker/twin/RiskScoreTwinWorker.java")).exists();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
