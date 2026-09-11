package com.metaml.wbapi;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.client.NodeManagerClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies deterministic Camunda gateway evaluation for explicit process variable values.
 */
@IsolatedWorkbenchTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-gateway-test;DB_CLOSE_DELAY=-1"
})
class DeterministicGatewayTest {

    @MockitoBean
    @SuppressWarnings("unused")
    private NodeManagerClient nodeManagerClient;

    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private HistoryService historyService;

    private static final String BOOLEAN_GATEWAY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                id="Definitions_1" targetNamespace="http://test.metaml.com">
              <bpmn2:process id="BooleanGatewayProcess" isExecutable="true">
                <bpmn2:startEvent id="Start" />
                <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="Gateway_Decision" />
                <bpmn2:exclusiveGateway id="Gateway_Decision" default="Flow_Default">
                  <bpmn2:incoming>f1</bpmn2:incoming>
                  <bpmn2:outgoing>Flow_Approved</bpmn2:outgoing>
                  <bpmn2:outgoing>Flow_Default</bpmn2:outgoing>
                </bpmn2:exclusiveGateway>
                <bpmn2:sequenceFlow id="Flow_Approved" sourceRef="Gateway_Decision" targetRef="End_Approved">
                  <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${approved}</bpmn2:conditionExpression>
                </bpmn2:sequenceFlow>
                <bpmn2:sequenceFlow id="Flow_Default" sourceRef="Gateway_Decision" targetRef="End_Default" />
                <bpmn2:endEvent id="End_Approved" />
                <bpmn2:endEvent id="End_Default" />
              </bpmn2:process>
            </bpmn2:definitions>
            """;

    @Test
    void booleanTrueTakesApprovedBranch() {
        deploy("boolean-gateway.bpmn", BOOLEAN_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "BooleanGatewayProcess", Map.of("approved", true));
        assertProcessEndedAt(pi, "End_Approved");
    }

    @Test
    void booleanFalseTakesDefaultBranch() {
        deploy("boolean-gateway-false.bpmn", BOOLEAN_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "BooleanGatewayProcess", Map.of("approved", false));
        assertProcessEndedAt(pi, "End_Default");
    }

    @Test
    void missingBooleanVariableFailsExplicitly() {
        deploy("boolean-gateway-missing.bpmn", BOOLEAN_GATEWAY_BPMN);
        // No 'approved' variable -> PropertyNotFoundException from JUEL
        assertThatThrownBy(() ->
                runtimeService.startProcessInstanceByKey("BooleanGatewayProcess", Map.of()))
                .hasMessageContaining("approved");
    }

    private static final String STRING_GATEWAY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                id="Definitions_1" targetNamespace="http://test.metaml.com">
              <bpmn2:process id="StringGatewayProcess" isExecutable="true">
                <bpmn2:startEvent id="Start" />
                <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="Gateway_Risk" />
                <bpmn2:exclusiveGateway id="Gateway_Risk" default="Flow_Normal">
                  <bpmn2:incoming>f1</bpmn2:incoming>
                  <bpmn2:outgoing>Flow_High</bpmn2:outgoing>
                  <bpmn2:outgoing>Flow_Normal</bpmn2:outgoing>
                </bpmn2:exclusiveGateway>
                <bpmn2:sequenceFlow id="Flow_High" sourceRef="Gateway_Risk" targetRef="End_High">
                  <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${riskLevel == "HIGH"}</bpmn2:conditionExpression>
                </bpmn2:sequenceFlow>
                <bpmn2:sequenceFlow id="Flow_Normal" sourceRef="Gateway_Risk" targetRef="End_Normal" />
                <bpmn2:endEvent id="End_High" />
                <bpmn2:endEvent id="End_Normal" />
              </bpmn2:process>
            </bpmn2:definitions>
            """;

    @Test
    void stringHighTakesHighBranch() {
        deploy("string-gateway-high.bpmn", STRING_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "StringGatewayProcess", Map.of("riskLevel", "HIGH"));
        assertProcessEndedAt(pi, "End_High");
    }

    @Test
    void stringLowTakesDefaultBranch() {
        deploy("string-gateway-low.bpmn", STRING_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "StringGatewayProcess", Map.of("riskLevel", "LOW"));
        assertProcessEndedAt(pi, "End_Normal");
    }

    private static final String NUMERIC_GATEWAY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                id="Definitions_1" targetNamespace="http://test.metaml.com">
              <bpmn2:process id="NumericGatewayProcess" isExecutable="true">
                <bpmn2:startEvent id="Start" />
                <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="Gateway_Score" />
                <bpmn2:exclusiveGateway id="Gateway_Score" default="Flow_Below">
                  <bpmn2:incoming>f1</bpmn2:incoming>
                  <bpmn2:outgoing>Flow_Above</bpmn2:outgoing>
                  <bpmn2:outgoing>Flow_Below</bpmn2:outgoing>
                </bpmn2:exclusiveGateway>
                <bpmn2:sequenceFlow id="Flow_Above" sourceRef="Gateway_Score" targetRef="End_Above">
                  <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${score >= 0.8}</bpmn2:conditionExpression>
                </bpmn2:sequenceFlow>
                <bpmn2:sequenceFlow id="Flow_Below" sourceRef="Gateway_Score" targetRef="End_Below" />
                <bpmn2:endEvent id="End_Above" />
                <bpmn2:endEvent id="End_Below" />
              </bpmn2:process>
            </bpmn2:definitions>
            """;

    @Test
    void numericAboveThresholdTakesAboveBranch() {
        deploy("numeric-gateway-above.bpmn", NUMERIC_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "NumericGatewayProcess", Map.of("score", 0.85));
        assertProcessEndedAt(pi, "End_Above");
    }

    @Test
    void numericBelowThresholdTakesDefaultBranch() {
        deploy("numeric-gateway-below.bpmn", NUMERIC_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "NumericGatewayProcess", Map.of("score", 0.5));
        assertProcessEndedAt(pi, "End_Below");
    }

    private static final String GETTER_GATEWAY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                id="Definitions_1" targetNamespace="http://test.metaml.com">
              <bpmn2:process id="GetterGatewayProcess" isExecutable="true">
                <bpmn2:startEvent id="Start" />
                <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="Gateway_Flag" />
                <bpmn2:exclusiveGateway id="Gateway_Flag" default="Flow_NotFlagged">
                  <bpmn2:incoming>f1</bpmn2:incoming>
                  <bpmn2:outgoing>Flow_Flagged</bpmn2:outgoing>
                  <bpmn2:outgoing>Flow_NotFlagged</bpmn2:outgoing>
                </bpmn2:exclusiveGateway>
                <bpmn2:sequenceFlow id="Flow_Flagged" sourceRef="Gateway_Flag" targetRef="End_Flagged">
                  <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${execution.getVariable('agentFlaggedRisk') == true}</bpmn2:conditionExpression>
                </bpmn2:sequenceFlow>
                <bpmn2:sequenceFlow id="Flow_NotFlagged" sourceRef="Gateway_Flag" targetRef="End_NotFlagged" />
                <bpmn2:endEvent id="End_Flagged" />
                <bpmn2:endEvent id="End_NotFlagged" />
              </bpmn2:process>
            </bpmn2:definitions>
            """;

    @Test
    void getterExpressionTrueTakesFlaggedBranch() {
        deploy("getter-gateway-true.bpmn", GETTER_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "GetterGatewayProcess", Map.of("agentFlaggedRisk", true));
        assertProcessEndedAt(pi, "End_Flagged");
    }

    @Test
    void getterExpressionFalseTakesDefaultBranch() {
        deploy("getter-gateway-false.bpmn", GETTER_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "GetterGatewayProcess", Map.of("agentFlaggedRisk", false));
        assertProcessEndedAt(pi, "End_NotFlagged");
    }

    @Test
    void getterExpressionNullSafeTakesDefaultBranch() {
        deploy("getter-gateway-null.bpmn", GETTER_GATEWAY_BPMN);
        // Unset variable evaluates to null in JUEL getter expressions
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "GetterGatewayProcess", Map.of());
        assertProcessEndedAt(pi, "End_NotFlagged");
    }

    @Test
    void repeatedExecutionsWithSameInputProduceSameResult() {
        deploy("deterministic-gateway.bpmn", BOOLEAN_GATEWAY_BPMN);
        for (int i = 0; i < 20; i++) {
            ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                    "BooleanGatewayProcess", Map.of("approved", true));
            assertProcessEndedAt(pi, "End_Approved");
        }
        for (int i = 0; i < 20; i++) {
            ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                    "BooleanGatewayProcess", Map.of("approved", false));
            assertProcessEndedAt(pi, "End_Default");
        }
    }

    private static final String EXECUTOR_GATEWAY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                id="Definitions_1" targetNamespace="http://test.metaml.com">
              <bpmn2:process id="ExecutorGatewayProcess" isExecutable="true">
                <bpmn2:startEvent id="Start" />
                <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="ExecutorTask" />
                <bpmn2:serviceTask id="ExecutorTask" camunda:expression="${execution.setVariable('approved', executorOutput)}">
                  <bpmn2:incoming>f1</bpmn2:incoming>
                  <bpmn2:outgoing>f2</bpmn2:outgoing>
                </bpmn2:serviceTask>
                <bpmn2:sequenceFlow id="f2" sourceRef="ExecutorTask" targetRef="Gateway_Decision" />
                <bpmn2:exclusiveGateway id="Gateway_Decision" default="Flow_Default">
                  <bpmn2:incoming>f2</bpmn2:incoming>
                  <bpmn2:outgoing>Flow_Approved</bpmn2:outgoing>
                  <bpmn2:outgoing>Flow_Default</bpmn2:outgoing>
                </bpmn2:exclusiveGateway>
                <bpmn2:sequenceFlow id="Flow_Approved" sourceRef="Gateway_Decision" targetRef="End_Approved">
                  <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${approved}</bpmn2:conditionExpression>
                </bpmn2:sequenceFlow>
                <bpmn2:sequenceFlow id="Flow_Default" sourceRef="Gateway_Decision" targetRef="End_Default" />
                <bpmn2:endEvent id="End_Approved" />
                <bpmn2:endEvent id="End_Default" />
              </bpmn2:process>
            </bpmn2:definitions>
            """;

    @Test
    void executorOutputTruePropagatesToGatewayAndTakesApprovedBranch() {
        deploy("executor-gateway-true.bpmn", EXECUTOR_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "ExecutorGatewayProcess", Map.of("executorOutput", true));
        assertProcessEndedAt(pi, "End_Approved");
    }

    @Test
    void executorOutputFalsePropagatesToGatewayAndTakesDefaultBranch() {
        deploy("executor-gateway-false.bpmn", EXECUTOR_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "ExecutorGatewayProcess", Map.of("executorOutput", false));
        assertProcessEndedAt(pi, "End_Default");
    }

    private static final String SIMULATION_GATEWAY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                id="Definitions_1" targetNamespace="http://test.metaml.com">
              <bpmn2:process id="SimulationGatewayProcess" isExecutable="true">
                <bpmn2:startEvent id="Start" />
                <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="SimulationReader" />
                <bpmn2:serviceTask id="SimulationReader"
                    camunda:expression="${execution.setVariable('approved', execution.getVariable('_simulationGatewayValues').get('approved'))}">
                  <bpmn2:incoming>f1</bpmn2:incoming>
                  <bpmn2:outgoing>f2</bpmn2:outgoing>
                </bpmn2:serviceTask>
                <bpmn2:sequenceFlow id="f2" sourceRef="SimulationReader" targetRef="Gateway_Decision" />
                <bpmn2:exclusiveGateway id="Gateway_Decision" default="Flow_Default">
                  <bpmn2:incoming>f2</bpmn2:incoming>
                  <bpmn2:outgoing>Flow_Approved</bpmn2:outgoing>
                  <bpmn2:outgoing>Flow_Default</bpmn2:outgoing>
                </bpmn2:exclusiveGateway>
                <bpmn2:sequenceFlow id="Flow_Approved" sourceRef="Gateway_Decision" targetRef="End_Approved">
                  <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${approved}</bpmn2:conditionExpression>
                </bpmn2:sequenceFlow>
                <bpmn2:sequenceFlow id="Flow_Default" sourceRef="Gateway_Decision" targetRef="End_Default" />
                <bpmn2:endEvent id="End_Approved" />
                <bpmn2:endEvent id="End_Default" />
              </bpmn2:process>
            </bpmn2:definitions>
            """;

    @Test
    void simulationTrueValueDrivesGatewayToApprovedBranch() {
        deploy("simulation-gateway-true.bpmn", SIMULATION_GATEWAY_BPMN);
        Map<String, Object> simValues = new java.util.HashMap<>();
        simValues.put("approved", true);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "SimulationGatewayProcess",
                Map.of("_simulationGatewayValues", simValues));
        assertProcessEndedAt(pi, "End_Approved");
    }

    @Test
    void simulationFalseValueDrivesGatewayToDefaultBranch() {
        deploy("simulation-gateway-false.bpmn", SIMULATION_GATEWAY_BPMN);
        Map<String, Object> simValues = new java.util.HashMap<>();
        simValues.put("approved", false);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "SimulationGatewayProcess",
                Map.of("_simulationGatewayValues", simValues));
        assertProcessEndedAt(pi, "End_Default");
    }

    private static final String RECEIVE_GATEWAY_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                id="Definitions_1" targetNamespace="http://test.metaml.com">
              <bpmn2:process id="ReceiveGatewayProcess" isExecutable="true">
                <bpmn2:startEvent id="Start" />
                <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="WaitForSignal" />
                <bpmn2:receiveTask id="WaitForSignal" messageRef="Message_Proceed">
                  <bpmn2:incoming>f1</bpmn2:incoming>
                  <bpmn2:outgoing>f2</bpmn2:outgoing>
                </bpmn2:receiveTask>
                <bpmn2:sequenceFlow id="f2" sourceRef="WaitForSignal" targetRef="Gateway_Decision" />
                <bpmn2:exclusiveGateway id="Gateway_Decision" default="Flow_Default">
                  <bpmn2:incoming>f2</bpmn2:incoming>
                  <bpmn2:outgoing>Flow_Approved</bpmn2:outgoing>
                  <bpmn2:outgoing>Flow_Default</bpmn2:outgoing>
                </bpmn2:exclusiveGateway>
                <bpmn2:sequenceFlow id="Flow_Approved" sourceRef="Gateway_Decision" targetRef="End_Approved">
                  <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${approved}</bpmn2:conditionExpression>
                </bpmn2:sequenceFlow>
                <bpmn2:sequenceFlow id="Flow_Default" sourceRef="Gateway_Decision" targetRef="End_Default" />
                <bpmn2:endEvent id="End_Approved" />
                <bpmn2:endEvent id="End_Default" />
              </bpmn2:process>
              <bpmn2:message id="Message_Proceed" name="msg_proceed" />
            </bpmn2:definitions>
            """;

    @Test
    void overwrittenSimulationValueIsUsedByGatewayNotStaleValue() {
        deploy("stale-sim.bpmn", RECEIVE_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("ReceiveGatewayProcess");

        runtimeService.setVariable(pi.getId(), "approved", true);
        runtimeService.setVariable(pi.getId(), "approved", false);

        runtimeService.createMessageCorrelation("msg_proceed")
                .processInstanceId(pi.getId())
                .correlate();

        assertProcessEndedAt(pi, "End_Default");
    }

    @Test
    void simulationValueRemovedBeforeRetryLeavesVariableAbsent() {
        deploy("stale-removed.bpmn", RECEIVE_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("ReceiveGatewayProcess");

        runtimeService.setVariable(pi.getId(), "approved", true);
        runtimeService.removeVariable(pi.getId(), "approved");

        assertThatThrownBy(() ->
                runtimeService.createMessageCorrelation("msg_proceed")
                        .processInstanceId(pi.getId())
                        .correlate())
                .hasMessageContaining("approved");
    }

    @Test
    void simulationMapExistsButGatewayStillFailsWhenVariableNotSetDirectly() {
        // Camunda EL resolves variables from execution scope, not nested map entries.
        deploy("sim-no-fallback.bpmn", BOOLEAN_GATEWAY_BPMN);
        Map<String, Object> simValues = new java.util.HashMap<>();
        simValues.put("approved", true);
        assertThatThrownBy(() ->
                runtimeService.startProcessInstanceByKey("BooleanGatewayProcess",
                        Map.of("_simulationGatewayValues", (Object) simValues)))
                .hasMessageContaining("approved");
    }

    @Test
    void productionValuePreservedOverSimulationValue() {
        // Existing execution variables take precedence over simulation defaults.
        deploy("prod-over-sim.bpmn", RECEIVE_GATEWAY_BPMN);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("ReceiveGatewayProcess");

        runtimeService.setVariable(pi.getId(), "approved", false);

        runtimeService.createMessageCorrelation("msg_proceed")
                .processInstanceId(pi.getId())
                .correlate();

        assertProcessEndedAt(pi, "End_Default");
    }

    private void deploy(String resourceName, String bpmn) {
        repositoryService.createDeployment()
                .addString(resourceName, bpmn)
                .deploy();
    }

    private void assertProcessEndedAt(ProcessInstance pi, String expectedEndEventId) {
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();
        assertThat(hpi).isNotNull();
        assertThat(hpi.getEndActivityId()).isEqualTo(expectedEndEventId);
    }
}
