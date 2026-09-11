package com.metaml.wbapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinActivityExecutionState;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

// Integration tests verifying arbitration between AutoBridgeTrigger and human-initiated component integration.
@IsolatedWorkbenchTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-autobridge-race-test;DB_CLOSE_DELAY=-1"
})
class AutoBridgeVersusAiIntegrateRaceTest {

    private static final String TASK_GATE = "Task_Gate";
    private static final String TASK_TARGET = "Task_Target";

    @MockitoBean
    private NodeManagerClient nodeManagerClient;

    @Autowired
    private WorkbenchService workbenchService;

    private void stubCatalog() {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });
    }

    // Scenario: original process advances autonomously past an unlinked activity.
    // Verifies that auto-bridge executes default component without human intervention.
    @Test
    void unheldActivityAdvancesAutonomouslyWithDefaultBridgeComponent() throws IOException {
        stubCatalog();
        ProcessModel model = workbenchService.saveProcessModel(null, "auto-bridge race (default)",
                twoTaskBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), TASK_GATE, TASK_GATE);
        workbenchService.connectActivity(twin.getId(), TASK_TARGET, TASK_TARGET);

        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), TASK_GATE).isApproved()).isTrue();

        // Completing TASK_GATE on the original triggers AutoBridgeTrigger for TASK_TARGET.
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        TwinActivityExecutionState afterReached =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);

        assertThat(afterReached.getAgentName())
                .as("auto-bridge binds DEFAULT_BRIDGE_AGENT_TYPE ('validator') with no human involved")
                .isEqualTo("validator-agent-01");
        assertThat(afterReached.getStatus())
                .as("auto-bridge did not merely bind - it drove the twin through actual execution")
                .isEqualTo("EXECUTED");
        assertThat(afterReached.getSummary())
                .as("the component that actually ran was the auto-bridge default, not any AI choice")
                .contains("ValidatorExecutor");
    }

    // Scenario: component integration arrives after autonomous bridge execution has already completed.
    // Verifies that the completed execution cannot be overwritten by a subsequent late integration.
    @Test
    void aiIntegrateArrivingAfterAutoBridgeCannotMakeItsOwnComponentExecute() throws IOException {
        stubCatalog();
        ProcessModel model = workbenchService.saveProcessModel(null, "auto-bridge race (late AI integrate)",
                twoTaskBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), TASK_GATE, TASK_GATE);
        workbenchService.connectActivity(twin.getId(), TASK_TARGET, TASK_TARGET);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), TASK_GATE).isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        TwinActivityExecutionState autoBridged =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);
        assertThat(autoBridged.getStatus()).isEqualTo("EXECUTED");
        assertThat(autoBridged.getSummary()).contains("ValidatorExecutor");

        // The human now completes AI Integrate, choosing a DIFFERENT component.
        AgentDecision evolved =
                workbenchService.evolveActivity(twin.getId(), TASK_TARGET, "credit-risk-assessor");
        AgentDecision bridged = workbenchService.bridgeActivityEvent(twin.getId(), TASK_TARGET);

        TwinActivityExecutionState afterAi =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);

        assertThat(evolved.isApproved())
                .as("AI-selected component cannot be bound once auto-bridge has advanced the twin")
                .isFalse();
        assertThat(bridged.getReason()).isEqualTo("Activity event already forwarded to twin");
        assertThat(afterAi.getAgentName())
                .as("binding on record is still the auto-bridge default, not the human's choice")
                .isEqualTo("validator-agent-01");
        assertThat(afterAi.getSummary())
                .as("the executor that actually ran was the auto-bridge default")
                .contains("ValidatorExecutor");
        assertThat(afterAi.getSummary())
                .as("the AI-selected component never executed")
                .doesNotContain("CreditRiskAssessorExecutor");
    }

    // Scenario: activity is connected after the original process has already reached it.
    // Verifies that operator-selected component integration executes correctly.
    @Test
    void aiIntegrateOwnsTheExecutionWhenTheActivityIsConnectedAfterTheOriginalReachedIt()
            throws IOException {
        stubCatalog();
        ProcessModel model = workbenchService.saveProcessModel(null, "auto-bridge race (connected later)",
                twoTaskBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        workbenchService.connectActivity(twin.getId(), TASK_GATE, TASK_GATE);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), TASK_GATE).isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        // Connect, evolve, and bridge TASK_TARGET.
        workbenchService.connectActivity(twin.getId(), TASK_TARGET, TASK_TARGET);
        AgentDecision evolved =
                workbenchService.evolveActivity(twin.getId(), TASK_TARGET, "credit-risk-assessor");
        workbenchService.bridgeActivityEvent(twin.getId(), TASK_TARGET);

        TwinActivityExecutionState state =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);

        assertThat(evolved.isApproved()).as(evolved.getReason()).isTrue();
        assertThat(state.getAgentName()).isEqualTo("credit-risk-assessor-agent-01");
        assertThat(state.getStatus())
                .as("the AI-selected component must actually execute in this ordering")
                .isEqualTo("EXECUTED");
        assertThat(state.getSummary())
                .as("the executor that ran must be the AI-selected one, not the auto-bridge default")
                .contains("CreditRiskAssessorExecutor");
        assertThat(state.getSummary()).doesNotContain("ValidatorExecutor");
    }

    // Scenario: activity claimed before original reaches it.
    // Verifies that auto-bridge holds execution until the claimed component integration resolves.
    @Test
    void claimedActivityIsHeldByAutoBridgeAndTheAiSelectedComponentIsTheOneThatExecutes()
            throws IOException {
        stubCatalog();
        ProcessModel model = workbenchService.saveProcessModel(null, "integration hold", twoTaskBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), TASK_GATE, TASK_GATE);
        workbenchService.connectActivity(twin.getId(), TASK_TARGET, TASK_TARGET);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), TASK_GATE).isApproved()).isTrue();

        // Claim TASK_TARGET before original reaches it.
        workbenchService.requestComponentIntegration(twin.getId(), TASK_TARGET, null);
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        TwinActivityExecutionState held =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);

        assertThat(held.getAgentName())
                .as("auto-bridge must not bind the default component to a claimed activity")
                .isNull();
        assertThat(held.getStatus())
                .as("auto-bridge must not execute anything on a claimed activity")
                .isEqualTo("NOT_STARTED");

        AgentDecision evolved =
                workbenchService.evolveActivity(twin.getId(), TASK_TARGET, "credit-risk-assessor");
        assertThat(evolved.isApproved()).as(evolved.getReason()).isTrue();
        workbenchService.bridgeActivityEvent(twin.getId(), TASK_TARGET);

        TwinActivityExecutionState executed =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);

        assertThat(executed.getAgentName()).isEqualTo("credit-risk-assessor-agent-01");
        assertThat(executed.getStatus()).isEqualTo("EXECUTED");
        assertThat(executed.getSummary())
                .as("the AI-selected component must be the one that actually executed")
                .contains("CreditRiskAssessorExecutor");
        assertThat(executed.getSummary())
                .as("the auto-bridge default must never have run on this activity")
                .doesNotContain("ValidatorExecutor");
    }

    // Scenario: governance rejection leaves the claimed activity unexecuted and does not fall back to defaults.
    @Test
    void governanceRejectionLeavesTheClaimedActivityUnexecuted() throws IOException {
        stubCatalog();
        given(nodeManagerClient.checkAgentAvailability("credit-risk-assessor"))
                .willReturn(new AgentAvailabilityResult("credit-risk-assessor", false, null,
                        "refused for this test", false));

        ProcessModel model = workbenchService.saveProcessModel(null, "integration refused", twoTaskBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), TASK_GATE, TASK_GATE);
        workbenchService.connectActivity(twin.getId(), TASK_TARGET, TASK_TARGET);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), TASK_GATE).isApproved()).isTrue();
        workbenchService.requestComponentIntegration(twin.getId(), TASK_TARGET, null);
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        AgentDecision refused =
                workbenchService.evolveActivity(twin.getId(), TASK_TARGET, "credit-risk-assessor");
        workbenchService.bridgeActivityEvent(twin.getId(), TASK_TARGET);

        TwinActivityExecutionState state =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);

        assertThat(refused.isApproved()).isFalse();
        assertThat(state.getStatus())
                .as("a refused integration must not execute, and must not fall back to the default")
                .isEqualTo("NOT_STARTED");
        assertThat(state.getAgentName()).isNull();
    }

    // Scenario: repeated integration on the same visit stays idempotent.
    @Test
    void repeatedIntegrationOnTheSameVisitStaysIdempotent() throws IOException {
        stubCatalog();
        ProcessModel model = workbenchService.saveProcessModel(null, "integration repeated", twoTaskBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), TASK_GATE, TASK_GATE);
        workbenchService.connectActivity(twin.getId(), TASK_TARGET, TASK_TARGET);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), TASK_GATE).isApproved()).isTrue();
        workbenchService.requestComponentIntegration(twin.getId(), TASK_TARGET, null);
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        assertThat(workbenchService.evolveActivity(twin.getId(), TASK_TARGET, "validator")
                .isApproved()).isTrue();
        workbenchService.bridgeActivityEvent(twin.getId(), TASK_TARGET);
        TwinActivityExecutionState first =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);
        assertThat(first.getStatus()).isEqualTo("EXECUTED");

        // A second bridge for the same visit is refused as already forwarded.
        AgentDecision second = workbenchService.bridgeActivityEvent(twin.getId(), TASK_TARGET);
        assertThat(second.isApproved()).isFalse();
        assertThat(second.getReason()).isEqualTo("Activity event already forwarded to twin");
    }

    private static String twoTaskBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_AutoBridgeRace" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_AutoBridgeRace" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="Task_Gate" name="Gate">
                      <bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:userTask id="Task_Target" name="Target">
                      <bpmn:incoming>Flow_2</bpmn:incoming><bpmn:outgoing>Flow_3</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_3</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Gate"/>
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Gate" targetRef="Task_Target"/>
                    <bpmn:sequenceFlow id="Flow_3" sourceRef="Task_Target" targetRef="EndEvent_1"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }
}
