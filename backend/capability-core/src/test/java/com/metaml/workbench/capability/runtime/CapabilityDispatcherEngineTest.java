package com.metaml.workbench.capability.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.automation.ComponentExecutor;
import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

// Proves the dispatcher against a real Camunda engine's own commit boundary, for both shapes a
// generated Target Platform can produce, using two unrelated invented processes and providers.
//
// Every assertion here is branch-agnostic. The provider computes from real runtime state and the
// test asserts that whatever it produced is what the gateway followed - never that the business
// answer was a particular value. Both branches are correct outcomes.
class CapabilityDispatcherEngineTest {

    // Set by the test before starting an instance; the delegate below is how a real engine reaches
    // the dispatcher without a Spring context (camunda:class, not a bean expression).
    private static final AtomicReference<CapabilityDispatcher> DISPATCHER = new AtomicReference<>();
    private static final AtomicReference<String> ACTIVITY_UNDER_TEST = new AtomicReference<>();

    private ProcessEngine engine;

    @BeforeEach
    void startEngine() {
        engine = ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()
                .setJdbcUrl("jdbc:h2:mem:capability-dispatcher-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1")
                .setJobExecutorActivate(false)
                .buildProcessEngine();
    }

    @AfterEach
    void stopEngine() {
        DISPATCHER.set(null);
        ACTIVITY_UNDER_TEST.set(null);
        if (engine != null) {
            engine.close();
        }
    }

    public static final class DispatchingDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            DISPATCHER.get().dispatch(new DelegateExecutionContext(execution),
                    ACTIVITY_UNDER_TEST.get(), execution.getVariable("loopCounter"));
        }
    }

    // ---- two unrelated synthetic models ----------------------------------

    private static String delegateShapedBpmn(String processKey, String activityId, String gatewayVariable) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://metaml.test">
                  <bpmn:process id="%s" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="%s" />
                    <bpmn:serviceTask id="%s" name="Under test"
                        camunda:class="com.metaml.workbench.capability.runtime.CapabilityDispatcherEngineTest$DispatchingDelegate">
                      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:sequenceFlow id="F2" sourceRef="%s" targetRef="Gateway_1" />
                    <bpmn:exclusiveGateway id="Gateway_1">
                      <bpmn:incoming>F2</bpmn:incoming>
                      <bpmn:outgoing>F_true</bpmn:outgoing><bpmn:outgoing>F_false</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:sequenceFlow id="F_true" sourceRef="Gateway_1" targetRef="End_true">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${%s}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="F_false" sourceRef="Gateway_1" targetRef="End_false">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${!%s}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:endEvent id="End_true"><bpmn:incoming>F_true</bpmn:incoming></bpmn:endEvent>
                    <bpmn:endEvent id="End_false"><bpmn:incoming>F_false</bpmn:incoming></bpmn:endEvent>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(processKey, activityId, activityId, activityId, gatewayVariable,
                        gatewayVariable);
    }

    private static String externalTaskShapedBpmn(String processKey, String activityId, String topic,
            String gatewayVariable) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_2" targetNamespace="http://metaml.test">
                  <bpmn:process id="%s" isExecutable="true" camunda:historyTimeToLive="180">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="%s" />
                    <bpmn:serviceTask id="%s" name="Under test"
                        camunda:type="external" camunda:topic="%s">
                      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:sequenceFlow id="F2" sourceRef="%s" targetRef="Gateway_1" />
                    <bpmn:exclusiveGateway id="Gateway_1">
                      <bpmn:incoming>F2</bpmn:incoming>
                      <bpmn:outgoing>F_true</bpmn:outgoing><bpmn:outgoing>F_false</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:sequenceFlow id="F_true" sourceRef="Gateway_1" targetRef="End_true">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${%s}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="F_false" sourceRef="Gateway_1" targetRef="End_false">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${!%s}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:endEvent id="End_true"><bpmn:incoming>F_true</bpmn:incoming></bpmn:endEvent>
                    <bpmn:endEvent id="End_false"><bpmn:incoming>F_false</bpmn:incoming></bpmn:endEvent>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(processKey, activityId, activityId, topic, activityId, gatewayVariable,
                        gatewayVariable);
    }

    // ---- provider A: invented for this test ------------------------------

    private static final String A_ACTIVITY = "Activity_Assess";
    private static final String A_VARIABLE = "assessmentCleared";
    private static final String A_NAME = "alpha-assessor-01";
    private static final String A_TYPE = "alpha-assessor";

    private static final class AlphaAssessor implements ComponentExecutor {
        @Override
        public String getHandledAgentType() {
            return A_TYPE;
        }

        @Override
        public Set<String> getHandledAgentNames() {
            return Set.of(A_NAME);
        }

        @Override
        public AutomationResult execute(CapabilityExecutionContext context, String activityId,
                String agentName) {
            Object submitted = context.getVariable("submittedScore");
            int score = submitted instanceof Number n ? n.intValue() : 0;
            Map<String, Object> outputs = new HashMap<>();
            // real computation over real runtime state; the test does not care which way it lands
            outputs.put(A_VARIABLE, score >= 50);
            return new AutomationResult("assessed submittedScore=" + score, outputs);
        }
    }

    // ---- provider B: deliberately unrelated to A -------------------------

    private static final String B_ACTIVITY = "Step_ReconcileLedger";
    private static final String B_VARIABLE = "ledgerBalanced";
    private static final String B_NAME = "omega-reconciler-77";
    private static final String B_TYPE = "omega-reconciler";

    private static final class OmegaReconciler implements ComponentExecutor {
        @Override
        public String getHandledAgentType() {
            return B_TYPE;
        }

        @Override
        public Set<String> getHandledAgentNames() {
            return Set.of(B_NAME);
        }

        @Override
        public AutomationResult execute(CapabilityExecutionContext context, String activityId,
                String agentName) {
            String debit = String.valueOf(context.getVariable("debitTotal"));
            String credit = String.valueOf(context.getVariable("creditTotal"));
            return new AutomationResult("reconciled", Map.of(B_VARIABLE, debit.equals(credit)));
        }
    }

    private static CapabilityProvider provider(String name, String type, String outputName) {
        return new CapabilityProvider(name, type, "1.0.0",
                new CapabilityContract(type + "-capability", Set.of(),
                        Set.of(new IoDeclaration(outputName, IoType.BOOLEAN, true)),
                        ExecutionMode.SYNCHRONOUS, Map.of(), Set.of()),
                "invented for test", true, null);
    }

    private static CapabilityOutputContractSource sourceOf(CapabilityProvider... providers) {
        return identity -> {
            for (CapabilityProvider p : providers) {
                if (identity.equals(p.providerId()) || identity.equals(p.providerType())) {
                    return Optional.of(p);
                }
            }
            return Optional.empty();
        };
    }

    // Filtered by activity id rather than by activityType: Camunda records a plain end event as
    // "noneEndEvent", so querying activityType("endEvent") silently matches nothing.
    private List<String> completedEndEvents(String processInstanceId) {
        return engine.getHistoryService().createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId).list().stream()
                .map(HistoricActivityInstance::getActivityId)
                .filter(id -> "End_true".equals(id) || "End_false".equals(id))
                .toList();
    }

    // ---- the delegate boundary -------------------------------------------

    @Test
    void delegateShapeExecutesTheProviderAndTheGatewayFollowsItsActualOutput() {
        engine.getRepositoryService().createDeployment()
                .addString("alpha.bpmn", delegateShapedBpmn("Process_Alpha", A_ACTIVITY, A_VARIABLE))
                .deploy();
        DISPATCHER.set(new CapabilityDispatcher(List.of(new AlphaAssessor()),
                sourceOf(provider(A_NAME, A_TYPE, A_VARIABLE)), engine.getRepositoryService()));
        ACTIVITY_UNDER_TEST.set(A_ACTIVITY);

        Map<String, Object> start = new HashMap<>();
        start.put("evolvedAgent_" + A_ACTIVITY, A_NAME);
        start.put("submittedScore", 80);
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey("Process_Alpha", start);

        // the provider's actual output really is committed engine state
        Object published = engine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId()).variableName(A_VARIABLE).singleResult().getValue();
        assertThat(published).isInstanceOf(Boolean.class);

        // and control flow followed exactly that value - one branch, the one the value selects
        List<String> ends = completedEndEvents(instance.getId());
        assertThat(ends).hasSize(1);
        assertThat(ends.get(0)).isEqualTo(Boolean.TRUE.equals(published) ? "End_true" : "End_false");
        assertThat(engine.getRuntimeService().createProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult()).isNull();
    }

    @Test
    void delegateShapeFailsClosedWhenTheBoundProviderHasNoImplementation() {
        engine.getRepositoryService().createDeployment()
                .addString("alpha.bpmn", delegateShapedBpmn("Process_Alpha", A_ACTIVITY, A_VARIABLE))
                .deploy();
        // no executor for the bound identity
        DISPATCHER.set(new CapabilityDispatcher(List.of(),
                sourceOf(provider(A_NAME, A_TYPE, A_VARIABLE)), engine.getRepositoryService()));
        ACTIVITY_UNDER_TEST.set(A_ACTIVITY);

        Map<String, Object> start = new HashMap<>();
        start.put("evolvedAgent_" + A_ACTIVITY, A_NAME);

        assertThatThrownBy(() -> engine.getRuntimeService()
                .startProcessInstanceByKey("Process_Alpha", start))
                .hasMessageContaining("No ComponentExecutor found for bound provider");
        // no fabricated gateway variable was left behind, and no instance completed
        assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
                .variableName(A_VARIABLE).list()).isEmpty();
    }

    // ---- the external-task boundary --------------------------------------

    @Test
    void externalTaskShapeExecutesTheSameWayAndCommitsThroughCompletion() {
        String topic = "assess-topic";
        engine.getRepositoryService().createDeployment()
                .addString("alpha-ext.bpmn",
                        externalTaskShapedBpmn("Process_AlphaExt", A_ACTIVITY, topic, A_VARIABLE))
                .deploy();
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()),
                sourceOf(provider(A_NAME, A_TYPE, A_VARIABLE)), engine.getRepositoryService());

        Map<String, Object> start = new HashMap<>();
        start.put("evolvedAgent_" + A_ACTIVITY, A_NAME);
        start.put("submittedScore", 20);
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey("Process_AlphaExt", start);

        // exactly what the generated worker will do: lock, adapt, dispatch, complete with the buffer
        LockedExternalTask task = engine.getExternalTaskService()
                .fetchAndLock(1, "test-worker").topic(topic, 10_000L).execute().get(0);
        ExternalTaskExecutionContext context =
                new ExternalTaskExecutionContext(task, engine.getRuntimeService());
        assertThat(dispatcher.dispatch(context, A_ACTIVITY, null)).isPresent();
        engine.getExternalTaskService().complete(task.getId(), "test-worker",
                context.completionVariables());

        Object published = engine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId()).variableName(A_VARIABLE).singleResult().getValue();
        assertThat(published).isInstanceOf(Boolean.class);

        List<String> ends = completedEndEvents(instance.getId());
        assertThat(ends).hasSize(1);
        assertThat(ends.get(0)).isEqualTo(Boolean.TRUE.equals(published) ? "End_true" : "End_false");
    }

    @Test
    void externalTaskShapeCommitsNothingWhenTheContractIsBroken() {
        String topic = "assess-topic";
        engine.getRepositoryService().createDeployment()
                .addString("alpha-ext.bpmn",
                        externalTaskShapedBpmn("Process_AlphaExt", A_ACTIVITY, topic, A_VARIABLE))
                .deploy();
        // declares BOOLEAN A_VARIABLE; this provider returns a String
        ComponentExecutor breaksContract = new ComponentExecutor() {
            @Override
            public String getHandledAgentType() {
                return A_TYPE;
            }

            @Override
            public Set<String> getHandledAgentNames() {
                return Set.of(A_NAME);
            }

            @Override
            public AutomationResult execute(CapabilityExecutionContext context, String activityId,
                    String agentName) {
                return new AutomationResult("bad", Map.of(A_VARIABLE, "not-a-boolean"));
            }
        };
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(breaksContract),
                sourceOf(provider(A_NAME, A_TYPE, A_VARIABLE)), engine.getRepositoryService());

        Map<String, Object> start = new HashMap<>();
        start.put("evolvedAgent_" + A_ACTIVITY, A_NAME);
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey("Process_AlphaExt", start);
        LockedExternalTask task = engine.getExternalTaskService()
                .fetchAndLock(1, "test-worker").topic(topic, 10_000L).execute().get(0);
        ExternalTaskExecutionContext context =
                new ExternalTaskExecutionContext(task, engine.getRuntimeService());

        assertThatThrownBy(() -> dispatcher.dispatch(context, A_ACTIVITY, null))
                .isInstanceOf(CapabilityOutputContractViolationException.class);

        // the worker never completes, so the token is still parked and nothing was written
        assertThat(context.completionVariables()).isEmpty();
        assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId()).variableName(A_VARIABLE).list()).isEmpty();
        assertThat(completedEndEvents(instance.getId())).isEmpty();
    }

    // ---- genericity: a second, unrelated process and provider ------------

    @Test
    void anUnrelatedProcessAndProviderRunThroughTheIdenticalDispatcherCode() {
        engine.getRepositoryService().createDeployment()
                .addString("omega.bpmn", delegateShapedBpmn("Process_Omega", B_ACTIVITY, B_VARIABLE))
                .deploy();
        DISPATCHER.set(new CapabilityDispatcher(List.of(new OmegaReconciler()),
                sourceOf(provider(B_NAME, B_TYPE, B_VARIABLE)), engine.getRepositoryService()));
        ACTIVITY_UNDER_TEST.set(B_ACTIVITY);

        Map<String, Object> start = new HashMap<>();
        start.put("evolvedAgentType_" + B_ACTIVITY, B_TYPE);
        start.put("debitTotal", "100.00");
        start.put("creditTotal", "99.99");
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey("Process_Omega", start);

        Object published = engine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId()).variableName(B_VARIABLE).singleResult().getValue();
        assertThat(published).isInstanceOf(Boolean.class);
        List<String> ends = completedEndEvents(instance.getId());
        assertThat(ends).hasSize(1);
        assertThat(ends.get(0)).isEqualTo(Boolean.TRUE.equals(published) ? "End_true" : "End_false");
    }
}
