package com.metaml.wbapi;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.automation.ProjectAutomationService;
import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

// Verifies that a hung automation delegate call on one twin does not permanently
// block auto-bridge execution for subsequent twins.
@IsolatedWorkbenchTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-hung-automation-test;DB_CLOSE_DELAY=-1"
})
class AutoBridgeHungAutomationTest {

    private static final String TASK_GATE = "Task_Gate";
    private static final String TASK_TARGET = "Task_Target";

    @MockitoBean
    private NodeManagerClient nodeManagerClient;
    @MockitoBean(name = "default")
    private ProjectAutomationService defaultAutomation;

    @Autowired
    private WorkbenchService workbenchService;
    @Autowired
    private RuntimeService runtimeService;

    @Test
    void aHungAutomationOnOneTwinDoesNotPermanentlyWedgeAnotherTwin() throws IOException {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });

        // Twin B's subsequent Task_Target call executes normally, verifying that hung automation is isolated.
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicBoolean firstTargetCallHung = new AtomicBoolean(false);
        given(defaultAutomation.execute(any(DelegateExecution.class))).willAnswer(call -> {
            DelegateExecution execution = call.getArgument(0);
            String activityId = TwinModelGenerator.synchronizationActivityIdOf(execution.getCurrentActivityId());
            if (TASK_TARGET.equals(activityId) && firstTargetCallHung.compareAndSet(false, true)) {
                neverReleased.await();
            }
            return AutomationResult.of("automation ran");
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "hung automation test", twoTaskBpmn());

        // Connect both activities on Twin A and bridge Task_Gate to park at Task_Target.
        TwinProcess twinA = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twinA.getId(), TASK_GATE, TASK_GATE);
        workbenchService.connectActivity(twinA.getId(), TASK_TARGET, TASK_TARGET);
        AgentDecision gateBridge = workbenchService.bridgeActivityEvent(twinA.getId(), TASK_GATE);
        assertThat(gateBridge.isApproved()).isTrue();
        assertThat(runtimeService.getActiveActivityIds(twinA.getTwinProcessId())).containsExactly(TASK_TARGET);

        long before = System.nanoTime();
        // blocks for ~BRIDGE_TIMEOUT_SECONDS while the AFTER_COMMIT listener waits out the hung
        // automation, times out, and replaces its executor - proven by this call returning at all
        // rather than hanging the test itself
        assertThat(workbenchService.completeCurrentTasks(twinA.getId())).hasSize(1);
        double waitedSeconds = (System.nanoTime() - before) / 1_000_000_000.0;
        assertThat(waitedSeconds).isGreaterThanOrEqualTo(5.5);
        // the hung automation's own command never finished, so the twin's token is exactly where
        // it was before the attempt - still on Task_Target, nothing consumed
        assertThat(runtimeService.getActiveActivityIds(twinA.getTwinProcessId())).containsExactly(TASK_TARGET);

        // Verifies worker thread pool recovers by advancing an independent subsequent twin.
        TwinProcess twinB = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twinB.getId(), TASK_GATE, TASK_GATE);
        workbenchService.connectActivity(twinB.getId(), TASK_TARGET, TASK_TARGET);
        AgentDecision gateBridgeB = workbenchService.bridgeActivityEvent(twinB.getId(), TASK_GATE);
        assertThat(gateBridgeB.isApproved()).isTrue();

        long beforeB = System.nanoTime();
        assertThat(workbenchService.completeCurrentTasks(twinB.getId())).hasSize(1);
        double twinBSeconds = (System.nanoTime() - beforeB) / 1_000_000_000.0;
        assertThat(twinBSeconds).isLessThan(3.0);
        // and it genuinely ran, not just skipped fast - the twin reached its own end event
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(twinB.getTwinProcessId()).singleResult()).isNull();
    }

    private static String twoTaskBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_HungAutomation" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_HungAutomation" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Gate" name="Gate">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:userTask id="Task_Target" name="Target">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                      <bpmn:outgoing>Flow_3</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_3</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Gate" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Gate" targetRef="Task_Target" />
                    <bpmn:sequenceFlow id="Flow_3" sourceRef="Task_Target" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }
}
