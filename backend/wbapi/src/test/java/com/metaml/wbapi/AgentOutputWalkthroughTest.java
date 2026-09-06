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

// The walkthrough test next door covers riskFlagged, which is the one output the citi model has
// an opinion about. This one is about the mechanism underneath it: an agent reporting outputs
// nobody wrote Java for, which is what the next project attaching to this platform will be doing.
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

        // types survive the trip, or a gateway asking whether one of these == true never fires
        assertThat(twinVariable(twin, "evolvedAgentOutput_riskFlagged_" + CREDIT)).isEqualTo(true);
        assertThat(twinVariable(twin, "evolvedAgentOutput_confidence_" + CREDIT)).isEqualTo(42);
        assertThat(twinVariable(twin, "evolvedAgentOutput_tier_" + CREDIT)).isEqualTo("gold");

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);

        assertThat(originalVariable(twin, "agentOutput_" + CREDIT + "_confidence")).isEqualTo(42);
        assertThat(originalVariable(twin, "agentOutput_" + CREDIT + "_tier")).isEqualTo("gold");
        // and riskFlagged lands twice: once generically, once under the name the citi gateway reads
        assertThat(originalVariable(twin, "agentOutput_" + CREDIT + "_riskFlagged")).isEqualTo(true);
        assertThat(originalVariable(twin, RISK_FLAG)).isEqualTo(true);
        assertThat(twin.getEventLog()).anyMatch(entry -> entry.contains("agentOutput_" + CREDIT + "_tier"));
    }

    // the generic names say what the agent said, false included. Only the legacy flag treats
    // false as "say nothing", because its gateway leans on the variable being absent. Has to run
    // this on Task_Credit specifically, since that's the one activity whose declaration also
    // names agentFlaggedRisk - anywhere else there's nothing for the two paths to collide over.
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
        // the legacy block's removeVariable() doesn't log, so agentFlaggedRisk shows up in the
        // event log at all only if the declarative write also fired for it - which would mean it
        // logged "= false" a line before the removal quietly took it back out. Variable-state
        // assertions alone can't tell the two paths apart, since the legacy block wins either way.
        assertThat(twin.getEventLog()).noneMatch(entry -> entry.contains("'" + RISK_FLAG + "'"));
    }

    // same reconciliation the risk flag already had, except now nothing knows in advance which
    // outputs there are to clear, so the twin carries an index of what the last evolution wrote
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

    // Task_Credit's riskFlagged -> agentFlaggedRisk has a hardcoded alias behind it as well, so
    // the citi model can't tell you whether a declaration on its own does anything. This one is a
    // stand-in for the next project attaching to the platform: a name nothing in Java has heard
    // of, published under a short variable purely because the BPMN said to.
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

        // the declared one, which is the whole point
        assertThat(originalVariable(twin, "overdueFlagged")).isEqualTo(true);
        // and the generic name is still written alongside it, not replaced by it
        assertThat(originalVariable(twin, "agentOutput_" + HOLDS + "_overdueFlagged")).isEqualTo(true);

        // holdCount came back from the same agent but nobody declared it, so it stays prefixed.
        // Without this the test would pass just as well if every output got a bare name.
        assertThat(originalVariable(twin, "agentOutput_" + HOLDS + "_holdCount")).isEqualTo(3);
        assertThat(originalVariable(twin, "holdCount")).isNull();
    }

    // RiskFlagged used to reach the original as agentFlaggedRisk
    // unconditionally, on any activity, in any project, purely because an output happened to be
    // named "riskFlagged" - the one Twin-to-Original write-back that wasn't gated by the
    // activity's own metaml:agentOutputs declaration the way every other output already is.
    // Task_CheckHolds declares overdueFlagged but never riskFlagged, so an agent reporting
    // riskFlagged=true for it must NOT reach agentFlaggedRisk - only the generic, always-safe
    // agentOutput_<activityId>_riskFlagged name should.
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

        // the generic, always-on name still carries it - nothing about the gate touches that path
        assertThat(originalVariable(twin, "agentOutput_" + HOLDS + "_riskFlagged")).isEqualTo(true);
        // but the legacy bare name, which a gateway elsewhere could be routing on, stays untouched
        assertThat(originalVariable(twin, RISK_FLAG)).isNull();
    }

    // same shape as the real catalog: only the credit assessor reports anything, everything else
    // just says it exists. Keeps the assertions below about the one activity that was evolved
    // with it, rather than whatever the bridge's default agent left lying around.
    private void stubCatalog(Map<String, ?> riskAgentOutputs) {
        Map<String, Object> outputs = new LinkedHashMap<>(riskAgentOutputs);
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog",
                    RISK_AGENT_TYPE.equals(type) ? outputs : Map.of());
        });
    }

    // KYC's start event fires inside launchProcess, before the twin is tracked, so it's the one
    // activity the walkthroughs bridge by hand
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

    // not in examples/ on purpose: this is a throwaway stand-in for somebody else's model, and
    // the two real examples are things people demo. Only needs one task, a listener and the
    // declaration under test.
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

    // same walk up to examples/ the walkthrough test does, rather than a second copy of the model
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
