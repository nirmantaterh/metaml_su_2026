package com.metaml.wbapi;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.GovernanceService;
import com.metaml.workbench.service.WorkbenchService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

// Integration test verifying generic agent output propagation and BPMN output variable mapping.
@IsolatedWorkbenchTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-test;DB_CLOSE_DELAY=-1"
})
class AgentOutputWalkthroughTest {

    private static final String KYC = "Task_KYC";
    private static final String CREDIT = "Task_Credit";
    private static final String HOLDS = "Task_CheckHolds";
    private static final String RISK_AGENT_TYPE = "credit-risk-assessor";
    private static final String RISK_FLAG = "agentFlaggedRisk";

    @MockitoBean
    private NodeManagerClient nodeManagerClient;

    @Autowired
    private WorkbenchService workbenchService;
    @Autowired
    private GovernanceService governanceService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private HistoryService historyService;

    @BeforeEach
    void openTheQuota() {
        governanceService.updatePolicy(Set.of(), 20);
    }

    @Test
    void outputsNobodyWroteCodeForStillReachTheOriginal() throws IOException {
        stubCatalog(Map.of("riskFlagged", true, "confidence", 42, "tier", "gold"));

        TwinProcess twin = launchAndBridgeKyc("citi wire transfer named outputs");
        workbenchService.connectActivity(twin.getId(), CREDIT, CREDIT);
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(workbenchService.evolveActivity(twin.getId(), CREDIT, RISK_AGENT_TYPE).isApproved()).isTrue();

        assertThat(twinVariable(twin, "evolvedAgentOutput_riskFlagged_" + CREDIT)).isEqualTo(true);
        assertThat(twinVariable(twin, "evolvedAgentOutput_confidence_" + CREDIT)).isEqualTo(42);
        assertThat(twinVariable(twin, "evolvedAgentOutput_tier_" + CREDIT)).isEqualTo("gold");

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);

        assertThat(originalVariable(twin, "agentOutput_" + CREDIT + "_confidence")).isEqualTo(42);
        assertThat(originalVariable(twin, "agentOutput_" + CREDIT + "_tier")).isEqualTo("gold");
        assertThat(originalVariable(twin, "agentOutput_" + CREDIT + "_riskFlagged")).isEqualTo(true);
        assertThat(originalVariable(twin, RISK_FLAG)).isEqualTo(true);
        assertThat(twin.getEventLog()).anyMatch(entry -> entry.contains("agentOutput_" + CREDIT + "_tier"));
    }

    // Generic outputs record explicit false values, unlike legacy flags that rely on variable absence.
    @Test
    void anExplicitFalseIsWrittenGenericallyAndSuppressedOnTheLegacyName() throws IOException {
        stubCatalog(Map.of("riskFlagged", false));

        TwinProcess twin = launchAndBridgeKyc("citi wire transfer explicit false");
        workbenchService.connectActivity(twin.getId(), CREDIT, CREDIT);
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(workbenchService.evolveActivity(twin.getId(), CREDIT, RISK_AGENT_TYPE).isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);

        assertThat(originalVariable(twin, "agentOutput_" + CREDIT + "_riskFlagged")).isEqualTo(false);
        assertThat(originalVariable(twin, RISK_FLAG)).isNull();
        assertThat(twin.getEventLog()).noneMatch(entry -> entry.contains("'" + RISK_FLAG + "'"));
    }

    // Re-evolving an activity clears previously recorded agent outputs using the recorded output index.
    @Test
    void reEvolvingClearsAnOutputTheNewAgentDoesNotReport() throws IOException {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            Map<String, Object> outputs = RISK_AGENT_TYPE.equals(type)
                    ? Map.of("riskFlagged", true, "tier", "gold")
                    : Map.of("tier", "silver");
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", outputs);
        });

        TwinProcess twin = launchAndBridgeKyc("citi wire transfer dropped output");

        workbenchService.evolveActivity(twin.getId(), KYC, RISK_AGENT_TYPE);
        assertThat(twinVariable(twin, "evolvedAgentOutput_riskFlagged_" + KYC)).isEqualTo(true);

        workbenchService.evolveActivity(twin.getId(), KYC, "validator");
        assertThat(twinVariable(twin, "evolvedAgentOutput_riskFlagged_" + KYC)).isNull();
        assertThat(twinVariable(twin, "evolvedAgentOutput_tier_" + KYC)).isEqualTo("silver");
        assertThat(twinVariable(twin, "evolvedAgentOutputs_" + KYC)).isEqualTo("tier");
    }

    @Test
    void aDeclaredOutputAlsoLandsUnderTheNameTheModelAskedFor() throws IOException {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog",
                    Map.of("overdueFlagged", true, "holdCount", 3));
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "library holds", libraryBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), HOLDS, HOLDS);
        assertThat(workbenchService.evolveActivity(twin.getId(), HOLDS, "validator").isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        assertThat(originalVariable(twin, "overdueFlagged")).isEqualTo(true);
        assertThat(originalVariable(twin, "agentOutput_" + HOLDS + "_overdueFlagged")).isEqualTo(true);

        // Undeclared outputs remain prefixed with the activity ID.
        assertThat(originalVariable(twin, "agentOutput_" + HOLDS + "_holdCount")).isEqualTo(3);
        assertThat(originalVariable(twin, "holdCount")).isNull();
    }

    // Undeclared riskFlagged outputs must write only to scoped variable names,
    // not to legacy agentFlaggedRisk unless explicitly declared in metaml:agentOutputs.
    @Test
    void anUndeclaredRiskFlaggedOutputNeverReachesTheLegacyVariable() throws IOException {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog",
                    Map.of("riskFlagged", true));
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "library holds undeclared risk",
                libraryBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), HOLDS, HOLDS);
        assertThat(workbenchService.evolveActivity(twin.getId(), HOLDS, "validator").isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        assertThat(originalVariable(twin, "agentOutput_" + HOLDS + "_riskFlagged")).isEqualTo(true);
        assertThat(originalVariable(twin, RISK_FLAG)).isNull();
    }

    private void stubCatalog(Map<String, ?> riskAgentOutputs) {
        Map<String, Object> outputs = new LinkedHashMap<>(riskAgentOutputs);
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog",
                    RISK_AGENT_TYPE.equals(type) ? outputs : Map.of());
        });
    }

    // Initial task start event fires before twin registration; bridge manually.
    private TwinProcess launchAndBridgeKyc(String modelName) throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, modelName, citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();
        return twin;
    }

    private Object twinVariable(TwinProcess twin, String variableName) {
        return runtimeService.getVariable(twin.getTwinProcessId(), variableName);
    }

    private Object originalVariable(TwinProcess twin, String variableName) {
        HistoricVariableInstance variable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .variableName(variableName)
                .singleResult();
        return variable == null ? null : variable.getValue();
    }

    // Test BPMN fixture declaring an agent output variable mapping.
    private static String libraryBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   xmlns:metaml="http://metaml.com/schema/bpmn/metaml"
                                   id="Definitions_Library" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_LibraryHolds" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_CheckHolds" name="Check Holds">
                      <bpmn:extensionElements>
                        <metaml:agentOutputs>
                          <metaml:agentOutput name="overdueFlagged" variable="overdueFlagged" />
                        </metaml:agentOutputs>
                        <camunda:taskListener event="complete" delegateExpression="${agentExecutionDelegate}" />
                      </bpmn:extensionElements>
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_CheckHolds" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_CheckHolds" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String citibankBpmn() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("examples").resolve("citibank-wire-transfer.bpmn");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("no examples/citibank-wire-transfer.bpmn above "
                + Path.of("").toAbsolutePath());
    }
}
