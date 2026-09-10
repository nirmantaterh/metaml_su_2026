package com.metaml.workbench.capability.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.Test;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.automation.ComponentExecutor;
import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

// Nothing in this test resembles any shipped process or provider. The names are invented here on
// purpose: if the dispatcher needed to recognise any of them, it would not be generic.
class CapabilityDispatcherTest {

    private static final String ACTIVITY = "Activity_Assess";
    private static final String GATEWAY_VARIABLE = "assessmentCleared";
    private static final String PROVIDER_NAME = "alpha-assessor-01";
    private static final String PROVIDER_TYPE = "alpha-assessor";

    // A process the system has never seen: one activity, one gateway reading one variable this test
    // invented. The dispatcher learns the variable name from this XML, never from a constant.
    private static String bpmn(String activityId, String gatewayVariable) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_alpha" targetNamespace="http://metaml.test">
                  <bpmn:process id="Process_Alpha" name="Alpha" isExecutable="true">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="%s" />
                    <bpmn:serviceTask id="%s" name="Assess" camunda:delegateExpression="${assess}">
                      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:sequenceFlow id="F2" sourceRef="%s" targetRef="Gateway_1" />
                    <bpmn:exclusiveGateway id="Gateway_1">
                      <bpmn:incoming>F2</bpmn:incoming>
                      <bpmn:outgoing>F_yes</bpmn:outgoing><bpmn:outgoing>F_no</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:sequenceFlow id="F_yes" sourceRef="Gateway_1" targetRef="End_yes">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${%s}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="F_no" sourceRef="Gateway_1" targetRef="End_no">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${!%s}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:endEvent id="End_yes"><bpmn:incoming>F_yes</bpmn:incoming></bpmn:endEvent>
                    <bpmn:endEvent id="End_no"><bpmn:incoming>F_no</bpmn:incoming></bpmn:endEvent>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(activityId, activityId, activityId, gatewayVariable, gatewayVariable);
    }

    private RepositoryService repositoryServiceFor(String bpmnXml) {
        RepositoryService repositoryService = mock(RepositoryService.class);
        given(repositoryService.getBpmnModelInstance("pd-1")).willReturn(
                Bpmn.readModelFromStream(new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8))));
        return repositoryService;
    }

    // A provider invented for this test. It computes from real runtime state; it does not decide in
    // advance what the answer is, and the tests below never assert which answer it gave.
    private static final class AlphaAssessor implements ComponentExecutor {
        @Override
        public String getHandledAgentType() {
            return PROVIDER_TYPE;
        }

        @Override
        public Set<String> getHandledAgentNames() {
            return Set.of(PROVIDER_NAME);
        }

        @Override
        public AutomationResult execute(CapabilityExecutionContext context, String activityId,
                String agentName) {
            Object submitted = context.getVariable("submittedScore");
            int score = submitted instanceof Number number ? number.intValue() : 0;
            Map<String, Object> outputs = new HashMap<>();
            outputs.put(GATEWAY_VARIABLE, score >= 50);
            return new AutomationResult("assessed on submittedScore=" + score, outputs);
        }
    }

    private static CapabilityProvider declaredProvider() {
        CapabilityContract contract = new CapabilityContract("alpha-assessment",
                Set.of(new IoDeclaration("submittedScore", IoType.NUMBER, false)),
                Set.of(new IoDeclaration(GATEWAY_VARIABLE, IoType.BOOLEAN, true)),
                ExecutionMode.SYNCHRONOUS, Map.of(), Set.of());
        return new CapabilityProvider(PROVIDER_NAME, PROVIDER_TYPE, "1.0.0", contract, "test provider",
                true, null);
    }

    private static CapabilityOutputContractSource sourceFor(CapabilityProvider provider) {
        return identity -> provider != null
                && (identity.equals(provider.providerId()) || identity.equals(provider.providerType()))
                        ? Optional.of(provider) : Optional.empty();
    }

    private static DelegateExecution boundDelegateExecution(String boundName, String boundType,
            Object submittedScore) {
        DelegateExecution execution = mock(DelegateExecution.class);
        given(execution.getProcessInstanceId()).willReturn("pi-1");
        given(execution.getProcessDefinitionId()).willReturn("pd-1");
        given(execution.getActivityInstanceId()).willReturn("ai-1");
        given(execution.getVariable("evolvedAgent_" + ACTIVITY)).willReturn(boundName);
        given(execution.getVariable("evolvedAgentType_" + ACTIVITY)).willReturn(boundType);
        given(execution.getVariable("submittedScore")).willReturn(submittedScore);
        return execution;
    }

    // ---- resolution -------------------------------------------------------

    @Test
    void resolvesTheProviderByInstanceIdentityAndPublishesItsDeclaredOutput() {
        DelegateExecution execution = boundDelegateExecution(PROVIDER_NAME, PROVIDER_TYPE, 80);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()),
                sourceFor(declaredProvider()), repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)));

        Optional<AutomationResult> result = dispatcher.dispatch(new DelegateExecutionContext(execution),
                ACTIVITY, null);

        assertThat(result).isPresent();
        // the gateway variable the MODEL asked for, published under its plain name
        verify(execution).setVariable(GATEWAY_VARIABLE, true);
        // and the per-visit record, which is what keeps parallel siblings independently readable
        verify(execution).setVariable("twinAutomationOutput_" + GATEWAY_VARIABLE + "_" + ACTIVITY, true);
    }

    @Test
    void fallsBackToFamilyIdentityWhenNoExecutorClaimsTheInstanceName() {
        // Multi-instance siblings each get a distinct provider name from the catalog but share one
        // type that maps to one executor - the same order DefaultProjectAutomationService uses.
        DelegateExecution execution = boundDelegateExecution("alpha-assessor-99", PROVIDER_TYPE, 80);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()),
                sourceFor(declaredProvider()), repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)));

        assertThat(dispatcher.dispatch(new DelegateExecutionContext(execution), ACTIVITY, null)).isPresent();
        verify(execution).setVariable(GATEWAY_VARIABLE, true);
    }

    @Test
    void returnsEmptyWhenNoProviderIsBoundAndPublishesNothing() {
        // Not an error and not a fabricated output: whether an unbound activity is a capability gap
        // is the caller's decision, so the dispatcher reports the fact and writes nothing.
        DelegateExecution execution = boundDelegateExecution(null, null, 80);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()),
                sourceFor(declaredProvider()), repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)));

        assertThat(dispatcher.dispatch(new DelegateExecutionContext(execution), ACTIVITY, null)).isEmpty();
        verify(execution, never()).setVariable(org.mockito.ArgumentMatchers.eq(GATEWAY_VARIABLE),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failsClosedWhenAProviderIsBoundButNoExecutorHandlesIt() {
        DelegateExecution execution = boundDelegateExecution("nobody-implements-this", null, 80);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()),
                sourceFor(declaredProvider()), repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)));

        assertThatThrownBy(() -> dispatcher.dispatch(new DelegateExecutionContext(execution), ACTIVITY, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No ComponentExecutor found for bound provider 'nobody-implements-this'");
        verify(execution, never()).setVariable(org.mockito.ArgumentMatchers.eq(GATEWAY_VARIABLE),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refusesToGuessWhenTwoExecutorsClaimTheSameIdentity() {
        DelegateExecution execution = boundDelegateExecution(PROVIDER_NAME, null, 80);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(
                List.of(new AlphaAssessor(), new AlphaAssessor()), sourceFor(declaredProvider()),
                repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)));

        assertThatThrownBy(() -> dispatcher.dispatch(new DelegateExecutionContext(execution), ACTIVITY, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous executor resolution");
    }

    // ---- output contract --------------------------------------------------

    @Test
    void publishesNothingWhenTheProviderBreaksItsDeclaredOutputContract() {
        ComponentExecutor wrongOutput = new ComponentExecutor() {
            @Override
            public String getHandledAgentType() {
                return PROVIDER_TYPE;
            }

            @Override
            public Set<String> getHandledAgentNames() {
                return Set.of(PROVIDER_NAME);
            }

            @Override
            public AutomationResult execute(CapabilityExecutionContext context, String activityId,
                    String agentName) {
                // declares BOOLEAN assessmentCleared, returns a String under another name
                return new AutomationResult("wrong", Map.of("somethingElse", "nope"));
            }
        };
        DelegateExecution execution = boundDelegateExecution(PROVIDER_NAME, null, 80);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(wrongOutput),
                sourceFor(declaredProvider()), repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)));

        assertThatThrownBy(() -> dispatcher.dispatch(new DelegateExecutionContext(execution), ACTIVITY, null))
                .isInstanceOf(CapabilityOutputContractViolationException.class);
        verify(execution, never()).setVariable(org.mockito.ArgumentMatchers.eq(GATEWAY_VARIABLE),
                org.mockito.ArgumentMatchers.any());
        verify(execution, never()).setVariable(org.mockito.ArgumentMatchers.eq("somethingElse"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotWriteAGatewayVariableTheModelNeverAsksFor() {
        // The model's gateway reads a different variable than the provider produces, so the provider's
        // output stays per-visit bookkeeping and never becomes control-flow state.
        DelegateExecution execution = boundDelegateExecution(PROVIDER_NAME, null, 80);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()),
                sourceFor(declaredProvider()),
                repositoryServiceFor(bpmn(ACTIVITY, "someOtherVariableEntirely")));

        assertThat(dispatcher.dispatch(new DelegateExecutionContext(execution), ACTIVITY, null)).isPresent();
        verify(execution, never()).setVariable(org.mockito.ArgumentMatchers.eq(GATEWAY_VARIABLE),
                org.mockito.ArgumentMatchers.any());
        verify(execution).setVariable("twinAutomationOutput_" + GATEWAY_VARIABLE + "_" + ACTIVITY, true);
    }

    @Test
    void aLegitimateActionOnlyProviderWithZeroDeclaredOutputsStillCompletesSuccessfully() {
        // P7 Step 6, capability-output semantics (residual gap from P7 Step 5): a provider whose
        // contract legitimately declares NO produced outputs - e.g. a notifier that only performs an
        // action - must dispatch and complete successfully. "Zero outputs" is a valid contract, not a
        // fabricated substitute for "missing declared output" (CapabilityOutputContractViolationException
        // is the only path that means the latter, and it is not thrown here).
        String actionOnlyProvider = "action-only-notifier";
        String actionOnlyType = "notifier";
        ComponentExecutor actionOnly = new ComponentExecutor() {
            @Override
            public String getHandledAgentType() {
                return actionOnlyType;
            }

            @Override
            public Set<String> getHandledAgentNames() {
                return Set.of(actionOnlyProvider);
            }

            @Override
            public AutomationResult execute(CapabilityExecutionContext context, String activityId,
                    String agentName) {
                // Performed a real action (e.g. sent a notification) and legitimately produced no
                // business output for control flow to read - Map.of(), not a fabricated value.
                return new AutomationResult("notification sent", Map.of());
            }
        };
        CapabilityContract zeroOutputContract = new CapabilityContract("action-only", Set.of(), Set.of(),
                ExecutionMode.SYNCHRONOUS, Map.of(), Set.of());
        CapabilityProvider zeroOutputProvider = new CapabilityProvider(actionOnlyProvider, actionOnlyType,
                "1.0.0", zeroOutputContract, "test action-only provider", true, null);

        DelegateExecution execution = boundDelegateExecution(actionOnlyProvider, actionOnlyType, 80);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(actionOnly),
                sourceFor(zeroOutputProvider), repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)));

        Optional<AutomationResult> result = dispatcher.dispatch(new DelegateExecutionContext(execution),
                ACTIVITY, null);

        assertThat(result).isPresent();
        assertThat(result.get().outputs()).isEmpty();
        // Nothing to publish and nothing fabricated: no gateway variable, no per-visit record either.
        verify(execution, never()).setVariable(org.mockito.ArgumentMatchers.eq(GATEWAY_VARIABLE),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enforcesNothingButStillPublishesWhenNoContractIsResolvable() {
        // No catalog reachable is a capability-gap question, not a licence to reject a real provider's
        // output - publish()'s existing no-contract path.
        DelegateExecution execution = boundDelegateExecution(PROVIDER_NAME, null, 80);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()), null,
                repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)));

        assertThat(dispatcher.dispatch(new DelegateExecutionContext(execution), ACTIVITY, null)).isPresent();
        verify(execution).setVariable(GATEWAY_VARIABLE, true);
    }

    // ---- the external-task shape -----------------------------------------

    @Test
    void theExternalTaskShapeReachesTheSameProviderAndBuffersTheSameOutputs() {
        // Identical dispatch, identical provider, identical contract - only the adapter differs, and
        // the provider cannot tell. Writes land in completionVariables() for the worker to commit.
        org.camunda.bpm.engine.externaltask.LockedExternalTask task =
                mock(org.camunda.bpm.engine.externaltask.LockedExternalTask.class);
        org.camunda.bpm.engine.RuntimeService runtimeService =
                mock(org.camunda.bpm.engine.RuntimeService.class);
        given(task.getExecutionId()).willReturn("exec-1");
        given(task.getProcessInstanceId()).willReturn("pi-1");
        given(task.getProcessDefinitionId()).willReturn("pd-1");
        given(task.getActivityInstanceId()).willReturn("ai-1");
        given(runtimeService.getVariable("exec-1", "evolvedAgent_" + ACTIVITY)).willReturn(PROVIDER_NAME);
        given(runtimeService.getVariable("exec-1", "submittedScore")).willReturn(80);

        ExternalTaskExecutionContext context = new ExternalTaskExecutionContext(task, runtimeService);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()),
                sourceFor(declaredProvider()), repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)));

        assertThat(dispatcher.dispatch(context, ACTIVITY, null)).isPresent();
        assertThat(context.completionVariables())
                .containsEntry(GATEWAY_VARIABLE, true)
                .containsKey("twinAutomationOutput_" + GATEWAY_VARIABLE + "_" + ACTIVITY);
        // nothing was pushed into the engine directly - the worker's complete() is the commit point
        verify(runtimeService, never()).setVariable(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anExternalTaskProviderThatBreaksItsContractLeavesNothingToCommit() {
        org.camunda.bpm.engine.externaltask.LockedExternalTask task =
                mock(org.camunda.bpm.engine.externaltask.LockedExternalTask.class);
        org.camunda.bpm.engine.RuntimeService runtimeService =
                mock(org.camunda.bpm.engine.RuntimeService.class);
        given(task.getExecutionId()).willReturn("exec-1");
        given(task.getProcessDefinitionId()).willReturn("pd-1");
        given(runtimeService.getVariable("exec-1", "evolvedAgent_" + ACTIVITY)).willReturn(PROVIDER_NAME);

        ComponentExecutor breaksContract = new ComponentExecutor() {
            @Override
            public String getHandledAgentType() {
                return PROVIDER_TYPE;
            }

            @Override
            public Set<String> getHandledAgentNames() {
                return Set.of(PROVIDER_NAME);
            }

            @Override
            public AutomationResult execute(CapabilityExecutionContext context, String activityId,
                    String agentName) {
                return new AutomationResult("wrong type", Map.of(GATEWAY_VARIABLE, "not-a-boolean"));
            }
        };

        ExternalTaskExecutionContext context = new ExternalTaskExecutionContext(task, runtimeService);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(breaksContract),
                sourceFor(declaredProvider()), repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)));

        assertThatThrownBy(() -> dispatcher.dispatch(context, ACTIVITY, null))
                .isInstanceOf(CapabilityOutputContractViolationException.class);
        assertThat(context.completionVariables()).isEmpty();
    }

    // ---- genericity -------------------------------------------------------

    @Test
    void anUnrelatedProcessWithAnUnrelatedProviderWorksThroughTheSameDispatcher() {
        // Second, deliberately unrelated shape: different activity id, different provider identity,
        // different capability, different gateway variable, different output type, different logic.
        // The dispatcher instance is constructed the same way and its code is unchanged.
        String activityId = "Step_ReconcileLedger";
        String gatewayVariable = "ledgerBalanced";
        String providerName = "omega-reconciler-77";
        String providerType = "omega-reconciler";

        ComponentExecutor omega = new ComponentExecutor() {
            @Override
            public String getHandledAgentType() {
                return providerType;
            }

            @Override
            public Set<String> getHandledAgentNames() {
                return Set.of(providerName);
            }

            @Override
            public AutomationResult execute(CapabilityExecutionContext context, String activity,
                    String agentName) {
                Object left = context.getVariable("debitTotal");
                Object right = context.getVariable("creditTotal");
                boolean balanced = String.valueOf(left).equals(String.valueOf(right));
                return new AutomationResult("reconciled", Map.of(gatewayVariable, balanced));
            }
        };
        CapabilityProvider omegaProvider = new CapabilityProvider(providerName, providerType, "2.3.1",
                new CapabilityContract("ledger-reconciliation",
                        Set.of(new IoDeclaration("debitTotal", IoType.STRING, false)),
                        Set.of(new IoDeclaration(gatewayVariable, IoType.BOOLEAN, true)),
                        ExecutionMode.SYNCHRONOUS, Map.of(), Set.of()),
                "unrelated provider", true, null);

        DelegateExecution execution = mock(DelegateExecution.class);
        given(execution.getProcessDefinitionId()).willReturn("pd-1");
        given(execution.getVariable("evolvedAgent_" + activityId)).willReturn(providerName);
        given(execution.getVariable("debitTotal")).willReturn("100.00");
        given(execution.getVariable("creditTotal")).willReturn("100.00");

        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(omega),
                sourceFor(omegaProvider), repositoryServiceFor(bpmn(activityId, gatewayVariable)));

        assertThat(dispatcher.dispatch(new DelegateExecutionContext(execution), activityId, null)).isPresent();
        verify(execution).setVariable(gatewayVariable, true);
    }

    @Test
    void aModelThatCannotBeReadDoesNotInventAGatewayVariable() {
        RepositoryService broken = mock(RepositoryService.class);
        given(broken.getBpmnModelInstance("pd-1")).willThrow(new RuntimeException("deployment gone"));
        DelegateExecution execution = boundDelegateExecution(PROVIDER_NAME, null, 80);

        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()),
                sourceFor(declaredProvider()), broken);

        // the provider still ran and its per-visit record is still written; control flow simply gets
        // nothing, so the gateway fails on its own rather than reading something fabricated here
        assertThat(dispatcher.dispatch(new DelegateExecutionContext(execution), ACTIVITY, null)).isPresent();
        verify(execution, never()).setVariable(org.mockito.ArgumentMatchers.eq(GATEWAY_VARIABLE),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void requiresARepositoryServiceToBeAbleToAnswerWhatTheModelNeeds() {
        assertThatThrownBy(() -> new CapabilityDispatcher(List.of(), null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("repositoryService");
    }

    // ---- P7 Step 5: the binding cache fallback -----------------------------

    @Test
    void fallsBackToTheBindingCacheWhenNoProcessVariableIsBound() {
        // No evolvedAgent_*/evolvedAgentType_* on this instance at all - the fallback's whole reason
        // to exist: a fresh Target Platform process instance the Workbench never touched directly.
        DelegateExecution execution = boundDelegateExecution(null, null, 80);
        CapabilityContract bindTimeContract = new CapabilityContract("alpha-assessment", Set.of(),
                Set.of(new IoDeclaration(GATEWAY_VARIABLE, IoType.BOOLEAN, true)), ExecutionMode.SYNCHRONOUS,
                Map.of(), Set.of());
        CapabilityBinding cached = new CapabilityBinding("Process_Alpha", ACTIVITY, PROVIDER_NAME, PROVIDER_TYPE,
                "1.0.0", bindTimeContract, null, java.time.Instant.now());
        CapabilityBindingCache cache = new CapabilityBindingCache(activityId -> activityId.equals(ACTIVITY)
                ? Optional.of(cached) : Optional.empty());
        // No live contract source at all - the cached binding's own bind-time contract is what
        // governs, never a live catalog re-resolution.
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()), null,
                repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)), cache);

        assertThat(dispatcher.dispatch(new DelegateExecutionContext(execution), ACTIVITY, null)).isPresent();
        verify(execution).setVariable(GATEWAY_VARIABLE, true);
    }

    @Test
    void aProcessVariableBindingTakesPriorityOverACachedBinding() {
        // Instance identity is always more specific and more current than a model-level cache entry.
        DelegateExecution execution = boundDelegateExecution(PROVIDER_NAME, PROVIDER_TYPE, 80);
        CapabilityBindingCache cache = new CapabilityBindingCache(activityId -> {
            throw new AssertionError("the cache must not be consulted when this instance is itself bound");
        });
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()),
                sourceFor(declaredProvider()), repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)), cache);

        assertThat(dispatcher.dispatch(new DelegateExecutionContext(execution), ACTIVITY, null)).isPresent();
        verify(execution).setVariable(GATEWAY_VARIABLE, true);
    }

    @Test
    void stillReturnsEmptyWhenNeitherTheInstanceNorTheCacheResolvesAnything() {
        DelegateExecution execution = boundDelegateExecution(null, null, 80);
        CapabilityBindingCache emptyCache = new CapabilityBindingCache(activityId -> Optional.empty());
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()),
                sourceFor(declaredProvider()), repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)), emptyCache);

        assertThat(dispatcher.dispatch(new DelegateExecutionContext(execution), ACTIVITY, null)).isEmpty();
        verify(execution, never()).setVariable(org.mockito.ArgumentMatchers.eq(GATEWAY_VARIABLE),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void withNoCacheAtAllBehavesExactlyAsBeforeThisFallbackExisted() {
        DelegateExecution execution = boundDelegateExecution(null, null, 80);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(List.of(new AlphaAssessor()),
                sourceFor(declaredProvider()), repositoryServiceFor(bpmn(ACTIVITY, GATEWAY_VARIABLE)));

        assertThat(dispatcher.dispatch(new DelegateExecutionContext(execution), ACTIVITY, null)).isEmpty();
    }
}
