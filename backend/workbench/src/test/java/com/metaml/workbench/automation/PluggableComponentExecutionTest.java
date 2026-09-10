package com.metaml.workbench.automation;

import com.metaml.workbench.capability.runtime.DelegateExecutionContext;
import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PluggableComponentExecutionTest {

    private CreditRiskAssessorExecutor creditRiskExecutor;
    private ValidatorExecutor validatorExecutor;
    private DataEnricherExecutor dataEnricherExecutor;
    private NotifierExecutor notifierExecutor;
    private RecommenderExecutor recommenderExecutor;
    private DefaultProjectAutomationService automationService;
    private DelegateExecution execution;

    @BeforeEach
    void setUp() {
        creditRiskExecutor = new CreditRiskAssessorExecutor();
        validatorExecutor = new ValidatorExecutor();
        dataEnricherExecutor = new DataEnricherExecutor();
        notifierExecutor = new NotifierExecutor();
        recommenderExecutor = new RecommenderExecutor();

        automationService = new DefaultProjectAutomationService(List.of(
                creditRiskExecutor,
                validatorExecutor,
                dataEnricherExecutor,
                notifierExecutor,
                recommenderExecutor
        ));

        execution = mock(DelegateExecution.class);
        given(execution.getCurrentActivityId()).willReturn("Task_KYC_automate");
        given(execution.getProcessInstanceId()).willReturn("twin-proc-123");
    }

    @Test
    void creditRiskAssessorExecutesWithHighRiskContextAndSetsProcessVariables() {
        given(execution.getVariable("transferAmount")).willReturn(15000.0);

        AutomationResult result = creditRiskExecutor.execute(new DelegateExecutionContext(execution), "Task_KYC", "credit-risk-agent-01");

        assertThat(result.summary()).contains("CreditRiskAssessorExecutor");
        assertThat(result.outputs()).containsEntry("riskFlagged", true);
        assertThat(result.outputs()).containsEntry("riskScore", 85);
        assertThat(result.outputs().get("assessmentReason").toString()).contains("Elevated risk");

        verify(execution).setVariable("agentFlaggedRisk", true);
        verify(execution).setVariable("agentRiskScore", 85);
    }

    @Test
    void creditRiskAssessorExecutesWithLowRiskCreditScoreContext() {
        given(execution.getVariable("creditScore")).willReturn(780);
        given(execution.getVariable("transferAmount")).willReturn(3500.0);

        AutomationResult result = creditRiskExecutor.execute(new DelegateExecutionContext(execution), "Task_KYC", "credit-risk-agent-01");

        assertThat(result.outputs()).containsEntry("riskFlagged", false);
        assertThat(result.outputs()).containsEntry("riskScore", 20);
        assertThat(result.outputs().get("assessmentReason").toString()).contains("Low risk: High credit score");

        verify(execution).setVariable("agentFlaggedRisk", false);
        verify(execution).setVariable("agentRiskScore", 20);
    }

    @Test
    void creditRiskAssessorExecutesWithSubprimeCreditScoreContext() {
        given(execution.getVariable("creditScore")).willReturn(580);
        given(execution.getVariable("transferAmount")).willReturn(5000.0);

        AutomationResult result = creditRiskExecutor.execute(new DelegateExecutionContext(execution), "Task_KYC", "credit-risk-agent-01");

        assertThat(result.outputs()).containsEntry("riskFlagged", true);
        assertThat(result.outputs()).containsEntry("riskScore", 90);
        assertThat(result.outputs().get("assessmentReason").toString()).contains("High risk: Credit score");

        verify(execution).setVariable("agentFlaggedRisk", true);
        verify(execution).setVariable("agentRiskScore", 90);
    }

    @Test
    void validatorExecutesAndReturnsPassedForValidContext() {
        given(execution.getVariable("customerId")).willReturn("CUST-98721");

        AutomationResult result = validatorExecutor.execute(new DelegateExecutionContext(execution), "Task_KYC", "validator-agent-01");

        assertThat(result.summary()).contains("ValidatorExecutor");
        assertThat(result.outputs()).containsEntry("validationPassed", true);
        assertThat(result.outputs()).containsEntry("validationStatus", "PASSED");
        assertThat(result.outputs()).containsEntry("schemaVersion", "v2.4");

        verify(execution).setVariable("validationPassed", true);
    }

    @Test
    void validatorExecutesAndReturnsFailedForInvalidCustomerId() {
        given(execution.getVariable("customerId")).willReturn("INVALID");

        AutomationResult result = validatorExecutor.execute(new DelegateExecutionContext(execution), "Task_KYC", "validator-agent-01");

        assertThat(result.outputs()).containsEntry("validationPassed", false);
        assertThat(result.outputs()).containsEntry("validationStatus", "FAILED");
        assertThat(result.outputs().get("validationMessage").toString()).contains("failed format integrity check");

        verify(execution).setVariable("validationPassed", false);
    }

    @Test
    void validatorExecutesAndReturnsFailedWhenForceValidationFailureIsSet() {
        given(execution.getVariable("forceValidationFailure")).willReturn(true);

        AutomationResult result = validatorExecutor.execute(new DelegateExecutionContext(execution), "Task_KYC", "validator-agent-01");

        assertThat(result.outputs()).containsEntry("validationPassed", false);
        assertThat(result.outputs()).containsEntry("validationStatus", "FAILED");

        verify(execution).setVariable("validationPassed", false);
    }

    @Test
    void defaultAutomationDispatchesToCreditRiskExecutorForExactAgentName() {
        given(execution.getVariable("evolvedAgent_Task_KYC")).willReturn("credit-risk-agent-01");

        AutomationResult result = automationService.execute(execution);

        assertThat(result.summary()).contains("CreditRiskAssessorExecutor");
    }

    @Test
    void defaultAutomationDispatchesToCreditRiskExecutorForExactAgentType() {
        given(execution.getVariable("evolvedAgent_Task_KYC")).willReturn("credit-risk-assessor");

        AutomationResult result = automationService.execute(execution);

        assertThat(result.summary()).contains("CreditRiskAssessorExecutor");
    }

    @Test
    void defaultAutomationDispatchesToValidatorExecutorForExactAgentName() {
        given(execution.getVariable("evolvedAgent_Task_KYC")).willReturn("validator-agent-01");

        AutomationResult result = automationService.execute(execution);

        assertThat(result.summary()).contains("ValidatorExecutor");
    }

    @Test
    void deterministicIdentityRejectsFuzzySubstringMatches() {
        // Must NOT match "credit-risk-assessor" or "credit-risk-agent-01" via substring
        given(execution.getVariable("evolvedAgent_Task_KYC")).willReturn("credit-risk-fake-agent");

        assertThatThrownBy(() -> automationService.execute(execution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No ComponentExecutor found for evolved agent 'credit-risk-fake-agent'");
    }

    @Test
    void defaultAutomationThrowsWhenEvolvedAgentHasNoRegisteredExecutor() {
        given(execution.getVariable("evolvedAgent_Task_KYC")).willReturn("unregistered-agent-xyz");

        assertThatThrownBy(() -> automationService.execute(execution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No ComponentExecutor found for evolved agent 'unregistered-agent-xyz'");
    }

    @Test
    void defaultAutomationThrowsWhenDuplicateExecutorsClaimSameIdentity() {
        ComponentExecutor duplicateExecutor = new ComponentExecutor() {
            @Override
            public String getHandledAgentType() {
                return "validator";
            }
            @Override
            public AutomationResult execute(CapabilityExecutionContext context, String activityId,
                    String agentName) {
                return null;
            }
        };

        DefaultProjectAutomationService ambiguousService = new DefaultProjectAutomationService(List.of(
                validatorExecutor,
                duplicateExecutor
        ));

        given(execution.getVariable("evolvedAgent_Task_KYC")).willReturn("validator");

        assertThatThrownBy(() -> ambiguousService.execute(execution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous executor resolution");
    }

    @Test
    void defaultAutomationRunsDefaultTimestampingWhenNoAgentIsEvolved() {
        given(execution.getVariable("evolvedAgent_Task_KYC")).willReturn(null);

        AutomationResult result = automationService.execute(execution);

        assertThat(result.summary()).contains("default automation ran at");
        assertThat(result.outputs()).containsKey("ranAt");
    }
}
