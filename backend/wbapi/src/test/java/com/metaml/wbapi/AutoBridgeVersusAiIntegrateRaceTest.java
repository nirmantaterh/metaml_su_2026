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

// Empirically settles whether AutoBridgeTrigger can pre-empt the human-confirmed AI Integrate
// workflow - i.e. whether the component that actually executes can end up being the auto-bridge's
// DEFAULT_BRIDGE_AGENT_TYPE rather than the AI-recommended one the human confirmed.
//
// Deliberately run in the real wbapi Spring context (@IsolatedWorkbenchTest), because that is the
// only place AutoBridgeTrigger is a live bean: WbapiApplication declares
// @SpringBootApplication(scanBasePackages = "com.metaml"), and Camunda eventing is left at the
// starter default, so the ExecutionEvent the trigger listens for is genuinely published. A
// standalone process engine (as used by the workbench-module tests) never registers the trigger
// at all and therefore cannot observe this either way.
//
// Only NodeManagerClient is mocked, so the CATALOG is stubbed but the DISPATCH is real: the
// "default" DefaultProjectAutomationService bean and the real ComponentExecutor beans are used,
// which is what makes the executor identity in the recorded output trustworthy evidence of which
// component actually ran.
//
// Generic two-user-task fixture - nothing RedCollar/WireTransfer/Citibank specific.
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

    // CASE A - the activity is CONNECTED BEFORE the original process reaches it.
    // This is the ordering AutoBridgeTrigger was built for, and the one the previous audit
    // suspected of racing the human. Nothing here ever calls evolveActivity for TASK_TARGET
    // before the original reaches it, so anything that executes did so autonomously.
    @Test
    void autoBridgeExecutesTheActivityItselfWhenItIsConnectedBeforeTheOriginalReachesIt()
            throws IOException {
        stubCatalog();
        ProcessModel model = workbenchService.saveProcessModel(null, "auto-bridge race (connected first)",
                twoTaskBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        // Both connected up front - TASK_TARGET is connected while the original is still parked
        // on TASK_GATE, i.e. before TASK_TARGET's own start event ever fires.
        workbenchService.connectActivity(twin.getId(), TASK_GATE, TASK_GATE);
        workbenchService.connectActivity(twin.getId(), TASK_TARGET, TASK_TARGET);
        // TASK_GATE's start fired during launchProcess, before the twin was registered, so it is
        // bridged by hand to put the twin's own token on TASK_TARGET's receive task.
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), TASK_GATE).isApproved()).isTrue();

        TwinActivityExecutionState beforeReached =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);
        assertThat(beforeReached.getStatus())
                .as("nothing may have executed TASK_TARGET before the original even reaches it")
                .isEqualTo("NOT_STARTED");

        // Completing TASK_GATE on the ORIGINAL makes TASK_TARGET's start event fire for real,
        // which is what engages AutoBridgeTrigger. No human evolve happens anywhere in this test.
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        TwinActivityExecutionState afterReached =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);

        // THE DECISIVE OBSERVATION: did the auto-bridge bind and execute a component on its own?
        System.out.println("[RACE-EVIDENCE][caseA] status=" + afterReached.getStatus()
                + " agentName=" + afterReached.getAgentName()
                + " summary=" + afterReached.getSummary()
                + " output=" + afterReached.getOutput());

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

    // Follow-on to CASE A: the human's AI Integrate arrives LATE, after auto-bridge already ran.
    // Establishes whether the AI-selected component ever actually executes, or whether the twin
    // has already moved past the activity so the human's choice can never take effect.
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

        System.out.println("[RACE-EVIDENCE][caseA-late] evolveApproved=" + evolved.isApproved()
                + " evolveAgent=" + evolved.getAgentName()
                + " evolveReason=" + evolved.getReason()
                + " | bridgeApproved=" + bridged.isApproved()
                + " bridgeReason=" + bridged.getReason()
                + " | finalStatus=" + afterAi.getStatus()
                + " finalAgent=" + afterAi.getAgentName()
                + " finalSummary=" + afterAi.getSummary());

        // CONFIRMED BLOCKER, recorded as observed behaviour rather than desired behaviour.
        // Once the auto-bridge has advanced the twin through this activity, the human's later
        // AI Integrate cannot take effect at all:
        //  - the evolution is refused, because the twin instance has already run past/ended, so
        //    the AI-selected agent is never even bound;
        //  - the bridge reports the visit as already forwarded;
        //  - the execution on record remains the auto-bridge's own DEFAULT_BRIDGE_AGENT_TYPE run.
        // The reporting itself stays internally consistent (agent and executor still agree), so
        // this is NOT a false-attribution defect - it is a causality defect: the component the
        // human confirmed never ran, and the one that ran was chosen automatically.
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

    // CASE B - the activity is CONNECTED AFTER the original has already reached/passed it.
    // This is the ordering the VS Code AI Integrate flow actually produces when the operator picks
    // an activity on a Twin Process and the plugin auto-connects it at that moment.
    @Test
    void aiIntegrateOwnsTheExecutionWhenTheActivityIsConnectedAfterTheOriginalReachedIt()
            throws IOException {
        stubCatalog();
        ProcessModel model = workbenchService.saveProcessModel(null, "auto-bridge race (connected later)",
                twoTaskBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        // Only TASK_GATE is connected initially. TASK_TARGET is deliberately left unconnected, so
        // when the original reaches it AutoBridgeTrigger has nothing to forward.
        workbenchService.connectActivity(twin.getId(), TASK_GATE, TASK_GATE);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), TASK_GATE).isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        // Operator now picks TASK_TARGET in VS Code: it is connected at this point, then evolved
        // with the AI-recommended component, then bridged - exactly aiIntegrateActivity's order.
        workbenchService.connectActivity(twin.getId(), TASK_TARGET, TASK_TARGET);
        AgentDecision evolved =
                workbenchService.evolveActivity(twin.getId(), TASK_TARGET, "credit-risk-assessor");
        workbenchService.bridgeActivityEvent(twin.getId(), TASK_TARGET);

        TwinActivityExecutionState state =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);

        System.out.println("[RACE-EVIDENCE][caseB] evolveApproved=" + evolved.isApproved()
                + " status=" + state.getStatus()
                + " agentName=" + state.getAgentName()
                + " summary=" + state.getSummary());

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

    // CASE A AFTER THE FIX - the whole point. Same ordering as caseA above (connected before the
    // original reaches it), but the operator claims the activity for integration first. The
    // auto-bridge must now hold instead of running the default component, and the AI-selected
    // component must end up being the one that actually executes.
    @Test
    void claimedActivityIsHeldByAutoBridgeAndTheAiSelectedComponentIsTheOneThatExecutes()
            throws IOException {
        stubCatalog();
        ProcessModel model = workbenchService.saveProcessModel(null, "integration hold", twoTaskBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), TASK_GATE, TASK_GATE);
        workbenchService.connectActivity(twin.getId(), TASK_TARGET, TASK_TARGET);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), TASK_GATE).isApproved()).isTrue();

        // Operator claims TASK_TARGET before the original gets there - the only point at which it
        // is possible to get ahead of the auto-bridge.
        workbenchService.requestComponentIntegration(twin.getId(), TASK_TARGET, null);

        // Original reaches TASK_TARGET; AutoBridgeTrigger fires exactly as before.
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        TwinActivityExecutionState held =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);
        System.out.println("[RACE-EVIDENCE][held] status=" + held.getStatus()
                + " agentName=" + held.getAgentName() + " summary=" + held.getSummary());

        assertThat(held.getAgentName())
                .as("auto-bridge must not bind the default component to a claimed activity")
                .isNull();
        assertThat(held.getStatus())
                .as("auto-bridge must not execute anything on a claimed activity")
                .isEqualTo("NOT_STARTED");

        // The integration now resolves, arbitrarily long after the original arrived.
        AgentDecision evolved =
                workbenchService.evolveActivity(twin.getId(), TASK_TARGET, "credit-risk-assessor");
        assertThat(evolved.isApproved()).as(evolved.getReason()).isTrue();
        workbenchService.bridgeActivityEvent(twin.getId(), TASK_TARGET);

        TwinActivityExecutionState executed =
                workbenchService.getActivityExecutionState(twin.getId(), TASK_TARGET);
        System.out.println("[RACE-EVIDENCE][held-resolved] status=" + executed.getStatus()
                + " agentName=" + executed.getAgentName() + " summary=" + executed.getSummary());

        assertThat(executed.getAgentName()).isEqualTo("credit-risk-assessor-agent-01");
        assertThat(executed.getStatus()).isEqualTo("EXECUTED");
        assertThat(executed.getSummary())
                .as("the AI-selected component must be the one that actually executed")
                .contains("CreditRiskAssessorExecutor");
        assertThat(executed.getSummary())
                .as("the auto-bridge default must never have run on this activity")
                .doesNotContain("ValidatorExecutor");
    }

    // CASE E - governance rejection. A refused integration must not execute the refused component,
    // and must not silently fall back to the auto-bridge default either.
    @Test
    void governanceRejectionLeavesTheClaimedActivityUnexecuted() throws IOException {
        stubCatalog();
        // Node manager refuses this agent type - the same terminal path a governance denial takes:
        // runEvolution returns before executeAfterGovernance, so nothing is ever bound.
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
        System.out.println("[RACE-EVIDENCE][refused] approved=" + refused.isApproved()
                + " status=" + state.getStatus() + " agent=" + state.getAgentName()
                + " summary=" + state.getSummary());

        assertThat(refused.isApproved()).isFalse();
        assertThat(state.getStatus())
                .as("a refused integration must not execute, and must not fall back to the default")
                .isEqualTo("NOT_STARTED");
        assertThat(state.getAgentName()).isNull();
    }

    // CASE G - repeated integration on the same visit. The claim is released by the first binding;
    // a second evolve+bridge must not produce a second execution of the same visit.
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

        // A second bridge for the same visit must be refused as already forwarded, exactly as before
        // this fix - the hold mechanism must not have reopened the visit to a second execution.
        AgentDecision second = workbenchService.bridgeActivityEvent(twin.getId(), TASK_TARGET);
        System.out.println("[RACE-EVIDENCE][repeat] secondBridgeReason=" + second.getReason());
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
