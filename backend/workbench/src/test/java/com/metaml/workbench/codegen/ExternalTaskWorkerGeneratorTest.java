package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

// Runs against the actual RedCollar BPMNs the professor supplied. RedCollar is the validation case
// for external-task-worker generation, not a fixture to be simplified: its processes are built
// entirely from camunda:type="external" tasks, which is exactly the shape DelegateClassGenerator
// produces nothing for.
//
// The BPMNs are not committed to the repository (professor-supplied, not ours to redistribute) -
// resolved from redcollar.bpmn.dir / REDCOLLAR_BPMN_DIR / the repo root, same convention as
// RedCollarEndToEndTest. Skips (not fails) when neither file is found there.
class ExternalTaskWorkerGeneratorTest {

    private static final Path REPO_ROOT = redCollarBpmnDir();

    private final ExternalTaskWorkerGenerator generator = new ExternalTaskWorkerGenerator();

    private static Path redCollarBpmnDir() {
        String configured = System.getProperty("redcollar.bpmn.dir", System.getenv("REDCOLLAR_BPMN_DIR"));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("../..");
    }

    private static void assumeFixturesPresent() {
        Assumptions.assumeTrue(Files.isRegularFile(REPO_ROOT.resolve("Manuf-camunda.bpmn"))
                        && Files.isRegularFile(REPO_ROOT.resolve("Twin-camunda.bpmn")),
                "RedCollar BPMNs not found at " + REPO_ROOT.toAbsolutePath()
                        + " - point redcollar.bpmn.dir or REDCOLLAR_BPMN_DIR at the professor-supplied files");
    }

    @Test
    void generatesOneAgentDelegatingWorkerPerTwinTopic() throws Exception {
        assumeFixturesPresent();
        String twinBpmn = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));

        List<GeneratedWorker> workers = generator.generate(twinBpmn, "com.metaml.targetplatform.twin.worker", true);

        assertThat(workers).extracting(GeneratedWorker::topic)
                .containsExactly("OrderMgmtInitializationTwin", "SamplingTwin", "MarkingTwin", "CuttingTwin",
                        "StitchingTwin", "CheckingTwin", "PressingTwin", "PackagingTwin", "ShippingTwin", "LayingTwin");

        GeneratedWorker sampling = workers.stream().filter(w -> w.topic().equals("SamplingTwin")).findFirst()
                .orElseThrow();
        assertThat(sampling.className()).isEqualTo("SamplingTwinWorker");
        assertThat(sampling.sourceCode())
                .contains("package com.metaml.targetplatform.twin.worker;")
                .contains("implements GeneratedExternalTaskWorker")
                .contains("return \"SamplingTwin\"")
                // The decision is delegated to an OPTIONALLY injected, pluggable TwinDecisionAgent -
                // not hardcoded inline - so a real model/agent can be wired in with no generated
                // code to touch (see SpringBootProjectGenerator.writeTwinDecisionAgentInterface).
                // ObjectProvider (not TwinDecisionAgent directly) is what makes zero implementations
                // a safe, non-fatal case - see renderTwinWorkerSource's own comment for why.
                .contains("private final ObjectProvider<TwinDecisionAgent> agentProvider")
                .contains("public SamplingTwinWorker(ObjectProvider<TwinDecisionAgent> agentProvider)")
                .contains("agentProvider.getIfAvailable()")
                .contains("[Twin] Invoking decision agent")
                .contains("agent.decide(\"SamplingTwin\", task)")
                // ...and the built-in fallback still exists for when nobody has registered one.
                .contains("No TwinDecisionAgent registered")
                .contains("externalTaskService.complete(task.getId(), \"generated-worker\", variables)")
                .doesNotContain("\"PASS\"")
                .doesNotContain("\"FAIL\"");
    }

    @Test
    void generatesPlainCompletingWorkerPerManufacturingTopic() throws Exception {
        assumeFixturesPresent();
        String manufBpmn = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));

        List<GeneratedWorker> workers = generator.generate(manufBpmn, "com.metaml.targetplatform.manuf.worker", false);

        assertThat(workers).extracting(GeneratedWorker::topic)
                .contains("OrderMgmtInitialization", "VerifyOrder", "EditOrderDetails", "Sampling", "Marking",
                        "Cutting", "Laying", "Stitching", "Checking", "Pressing", "Packaging", "Shipping")
                .doesNotContain("SamplingTwin");

        // Non-gateway worker: plain completion, no variables map
        GeneratedWorker sampling = workers.stream().filter(w -> w.topic().equals("Sampling")).findFirst()
                .orElseThrow();
        assertThat(sampling.sourceCode())
                .contains("implements GeneratedExternalTaskWorker")
                .contains("return \"Sampling\"")
                .contains("Executing generated external-task worker")
                .contains("externalTaskService.complete(task.getId(), \"generated-worker\")")
                .doesNotContain("simulated ML agent")
                .doesNotContain("agentResult")
                .doesNotContain("Math.random()");
    }

    @Test
    void gatewayPrecedingWorkerSetsConditionVariables() throws Exception {
        assumeFixturesPresent();
        String manufBpmn = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));

        List<GeneratedWorker> workers = generator.generate(manufBpmn, "com.metaml.targetplatform.manuf.worker", false);

        // VerifyOrder immediately precedes the gateway checking ${orderApproved}
        GeneratedWorker verifyOrder = workers.stream().filter(w -> w.topic().equals("VerifyOrder")).findFirst()
                .orElseThrow();
        // Generated workers fail explicitly when gateway variables have no
        // legitimate producer — no Math.random(), no Boolean.TRUE, no fabricated state.
        assertThat(verifyOrder.sourceCode())
                .contains("Gateway variable 'orderApproved' must be set by a legitimate producer")
                .contains("throw new IllegalStateException")
                .contains("if (!variables.containsKey(\"orderApproved\"))")
                .contains("externalTaskService.complete") // reachable after conditional throw
                .doesNotContain("Math.random()")
                .doesNotContain("Boolean.TRUE")
                .doesNotContain("\"PASS\"")
                .doesNotContain("\"FAIL\"");

        // Checking immediately precedes the gateway checking ${qualityPassed}
        GeneratedWorker checking = workers.stream().filter(w -> w.topic().equals("Checking")).findFirst()
                .orElseThrow();
        assertThat(checking.sourceCode())
                .contains("Gateway variable 'qualityPassed' must be set by a legitimate producer")
                .contains("throw new IllegalStateException")
                .doesNotContain("Math.random()");
    }

    @Test
    void detectsGatewayVariablesFromManufacturingBpmn() throws Exception {
        assumeFixturesPresent();
        String manufBpmn = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(manufBpmn.getBytes(StandardCharsets.UTF_8)));

        Map<String, Set<String>> gatewayVars = ExternalTaskWorkerGenerator.detectGatewayVariables(model);

        // VerifyOrder (topic) → orderApproved (gateway condition)
        assertThat(gatewayVars).containsKey("VerifyOrder");
        assertThat(gatewayVars.get("VerifyOrder")).contains("orderApproved");

        // Checking (topic) → qualityPassed (gateway condition)
        assertThat(gatewayVars).containsKey("Checking");
        assertThat(gatewayVars.get("Checking")).contains("qualityPassed");

        // Other topics do not precede gateways
        assertThat(gatewayVars).doesNotContainKey("Sampling");
        assertThat(gatewayVars).doesNotContainKey("OrderMgmtInitialization");
    }

    // The bug this exists to catch: RedCollar's own hand-authored Twin-camunda.bpmn happens to have
    // no gateways at all (see twinBpmnHasNoGatewayVariables above), so the RedCollar fixture tests
    // never exercised a twin worker that precedes an exclusive gateway. TargetPlatformTwinMirrorGenerator
    // auto-derives a twin by preserving the proxy's gateway structure verbatim (only topics get a
    // "Twin" suffix), so a mirrored twin worker CAN precede a gateway - and simulateMlAgent=true
    // workers were rendered from a template that never wrote the condition variable at all, so the
    // gateway threw "Cannot resolve identifier" the moment a real instance reached it. Regression
    // test for that: this incident was only found live, in Cockpit, on a real generated
    // RedCollarTP instance - not by any prior automated test.
    @Test
    void twinWorkerPrecedingAGatewaySetsTheSameConditionVariablesAsTheProxyWorkerWould() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="rc_twin_process" name="Process_twin" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="VerifyOrderTwin" />
                    <bpmn2:serviceTask id="VerifyOrderTwin" name="Verify Order Details"
                        camunda:type="external" camunda:topic="VerifyOrderTwin" />
                    <bpmn2:sequenceFlow id="f2" sourceRef="VerifyOrderTwin" targetRef="Gateway_1" />
                    <bpmn2:exclusiveGateway id="Gateway_1">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="Gateway_1" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!orderApproved}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="Gateway_1" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${orderApproved}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        List<GeneratedWorker> workers = generator.generate(xml, "com.tp.TargetPlatform.worker.twin", true);

        GeneratedWorker verifyOrderTwin = workers.stream().filter(w -> w.topic().equals("VerifyOrderTwin"))
                .findFirst().orElseThrow();
        // Twin worker fails explicitly when TwinDecisionAgent doesn't set the
        // gateway variable — no Math.random() fallback. The containsKey check remains; the
        // fallback now throws instead of fabricating a random business decision.
        assertThat(verifyOrderTwin.sourceCode())
                .contains("[Twin] Invoking decision agent")
                .contains("agent.decide(\"VerifyOrderTwin\", task)")
                .contains("if (!variables.containsKey(\"orderApproved\")) {")
                .contains("Gateway variable 'orderApproved' was not set by TwinDecisionAgent")
                .contains("throw new IllegalStateException")
                .doesNotContain("Math.random()")
                .contains("externalTaskService.complete(task.getId(), \"generated-worker\", variables)");
    }

    @Test
    void twinBpmnHasNoGatewayVariables() throws Exception {
        assumeFixturesPresent();
        String twinBpmn = Files.readString(REPO_ROOT.resolve("Twin-camunda.bpmn"));
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(twinBpmn.getBytes(StandardCharsets.UTF_8)));

        Map<String, Set<String>> gatewayVars = ExternalTaskWorkerGenerator.detectGatewayVariables(model);

        assertThat(gatewayVars).isEmpty();
    }

    // ---- detectGatewayVariablesByActivityId tests ----

    // Maps by BPMN element ID rather than topic, covering any activity type.
    // Verifies that the Workbench simulation can use an activity's element ID
    // (from ExternalTask.getActivityId() or a TwinProcess activity link) to
    // determine which gateway variables to set as explicit process variables.
    @Test
    void detectsByActivityIdMapsElementIdToConditionVariables() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="proc" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="Activity_Check" />
                    <bpmn2:serviceTask id="Activity_Check" name="Check"
                        camunda:type="external" camunda:topic="Checking" />
                    <bpmn2:sequenceFlow id="f2" sourceRef="Activity_Check" targetRef="GW1" />
                    <bpmn2:exclusiveGateway id="GW1">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="GW1" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${qualityPassed}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="GW1" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!qualityPassed}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        Map<String, Set<String>> byId = ExternalTaskWorkerGenerator.detectGatewayVariablesByActivityId(model);

        // keyed by element ID, not topic
        assertThat(byId).containsKey("Activity_Check");
        assertThat(byId.get("Activity_Check")).containsExactly("qualityPassed");
        assertThat(byId).doesNotContainKey("Checking"); // topic is NOT a key here
    }

    @Test
    void detectsByActivityIdReturnsEmptyForNoGateways() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="proc" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="Task1" />
                    <bpmn2:serviceTask id="Task1" name="Do work"
                        camunda:type="external" camunda:topic="Work" />
                    <bpmn2:sequenceFlow id="f2" sourceRef="Task1" targetRef="End" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        Map<String, Set<String>> byId = ExternalTaskWorkerGenerator.detectGatewayVariablesByActivityId(model);

        assertThat(byId).isEmpty();
    }

    @Test
    void detectsByActivityIdHandlesNonExternalActivities() {
        // User task (not external task) preceding a gateway — the by-activity-ID method
        // covers ANY activity type, not just external tasks.
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="proc" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="UserTask_Review" />
                    <bpmn2:userTask id="UserTask_Review" name="Review" />
                    <bpmn2:sequenceFlow id="f2" sourceRef="UserTask_Review" targetRef="GW" />
                    <bpmn2:exclusiveGateway id="GW">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="GW" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${approved}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="GW" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!approved}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        Map<String, Set<String>> byId = ExternalTaskWorkerGenerator.detectGatewayVariablesByActivityId(model);

        assertThat(byId).containsKey("UserTask_Review");
        assertThat(byId.get("UserTask_Review")).containsExactly("approved");
    }

    @Test
    void detectsByActivityIdFromRealManufBpmn() throws Exception {
        assumeFixturesPresent();
        String manufBpmn = Files.readString(REPO_ROOT.resolve("Manuf-camunda.bpmn"));
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(manufBpmn.getBytes(StandardCharsets.UTF_8)));

        Map<String, Set<String>> byId = ExternalTaskWorkerGenerator.detectGatewayVariablesByActivityId(model);

        // At least one activity element ID maps to a gateway variable — we don't hardcode
        // the element IDs (they're UUIDs), but verify the mapping is non-empty and the
        // values contain the known condition variables.
        assertThat(byId).isNotEmpty();
        assertThat(byId.values().stream().flatMap(Set::stream).toList())
                .contains("orderApproved", "qualityPassed");
    }

    @Test
    void elReservedWordsAreNotTreatedAsVariables() {
        // ${false} is an EL literal, not a variable reference. The WireTransfer BPMN uses
        // ${false} as a dead-path condition and ${execution.getVariable('agentFlaggedRisk') == true}
        // for the risk path. Neither should produce a "variable" in the detection result.
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="proc" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="Task_A" />
                    <bpmn2:serviceTask id="Task_A" name="A" />
                    <bpmn2:sequenceFlow id="f2" sourceRef="Task_A" targetRef="GW" />
                    <bpmn2:exclusiveGateway id="GW">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                      <bpmn2:outgoing>f5</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="GW" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${false}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="GW" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${true}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f5" sourceRef="GW" targetRef="End3">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${null}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                    <bpmn2:endEvent id="End3" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        // Neither detectGatewayVariables (by topic) nor detectGatewayVariablesByActivityId
        // should return false/true/null as variable names.
        assertThat(ExternalTaskWorkerGenerator.detectGatewayVariables(model)).isEmpty();
        assertThat(ExternalTaskWorkerGenerator.detectGatewayVariablesByActivityId(model)).isEmpty();
    }

    // Complex gateway expressions. ${execution.getVariable('agentFlaggedRisk') == true}
    // is a null-safe getter pattern that WireTransfer uses for the risk gateway. The variable name
    // ('agentFlaggedRisk') must be extracted so the simulation can set it before the gateway evaluates.
    @Test
    void complexGetterExpressionVariablesAreDetected() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="proc" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="Task_Risk" />
                    <bpmn2:serviceTask id="Task_Risk" name="Risk Check"
                        camunda:type="external" camunda:topic="RiskCheck" />
                    <bpmn2:sequenceFlow id="f2" sourceRef="Task_Risk" targetRef="GW_Risk" />
                    <bpmn2:exclusiveGateway id="GW_Risk">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="GW_Risk" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${execution.getVariable('agentFlaggedRisk') == true}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="GW_Risk" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${false}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        // by-topic: RiskCheck topic feeds GW_Risk, should detect agentFlaggedRisk
        Map<String, Set<String>> byTopic = ExternalTaskWorkerGenerator.detectGatewayVariables(model);
        assertThat(byTopic).containsKey("RiskCheck");
        assertThat(byTopic.get("RiskCheck")).containsExactly("agentFlaggedRisk");

        // by-activity-id: Task_Risk feeds GW_Risk
        Map<String, Set<String>> byActivity = ExternalTaskWorkerGenerator.detectGatewayVariablesByActivityId(model);
        assertThat(byActivity).containsKey("Task_Risk");
        assertThat(byActivity.get("Task_Risk")).containsExactly("agentFlaggedRisk");
    }

    // Both simple ${varName} and complex ${execution.getVariable('var')} in the same gateway
    @Test
    void mixedSimpleAndComplexExpressionsDetectAllVariables() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="proc" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="Task_A" />
                    <bpmn2:serviceTask id="Task_A" name="A" />
                    <bpmn2:sequenceFlow id="f2" sourceRef="Task_A" targetRef="GW" />
                    <bpmn2:exclusiveGateway id="GW">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="GW" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${qualityPassed}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="GW" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${execution.getVariable("riskLevel") == true}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        Map<String, Set<String>> byActivity = ExternalTaskWorkerGenerator.detectGatewayVariablesByActivityId(model);
        assertThat(byActivity).containsKey("Task_A");
        assertThat(byActivity.get("Task_A")).containsExactlyInAnyOrder("qualityPassed", "riskLevel");
    }
}
