package com.metaml.workbench.capability.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.junit.jupiter.api.Test;

// The two shapes a generated Target Platform can invoke a provider from must be indistinguishable to
// that provider. These tests pin the parts where the two genuinely differ underneath.
class CapabilityExecutionContextTest {

    @Test
    void delegateShapeReadsAndWritesStraightThroughToTheExecution() {
        DelegateExecution execution = mock(DelegateExecution.class);
        given(execution.getVariable("applicantIncome")).willReturn(52000);
        given(execution.getProcessInstanceId()).willReturn("pi-1");
        given(execution.getProcessDefinitionId()).willReturn("pd-1");
        given(execution.getActivityInstanceId()).willReturn("ai-1");
        given(execution.getProcessBusinessKey()).willReturn("bk-1");

        CapabilityExecutionContext context = new DelegateExecutionContext(execution);
        context.setVariable("assessmentCleared", true);

        assertThat(context.getVariable("applicantIncome")).isEqualTo(52000);
        assertThat(context.getProcessInstanceId()).isEqualTo("pi-1");
        assertThat(context.getProcessDefinitionId()).isEqualTo("pd-1");
        assertThat(context.getActivityInstanceId()).isEqualTo("ai-1");
        assertThat(context.getBusinessKey()).isEqualTo("bk-1");
        // the execution already IS the live variable scope, so there is nothing to buffer
        verify(execution).setVariable("assessmentCleared", true);
    }

    @Test
    void externalTaskShapeReadsThroughRuntimeServiceAgainstTheTasksOwnExecution() {
        // Not LockedExternalTask.getVariables(): the generated poller's fetchAndLock does not request
        // variables, and reading by executionId also keeps activity-scoped variables correctly scoped.
        LockedExternalTask task = mock(LockedExternalTask.class);
        RuntimeService runtimeService = mock(RuntimeService.class);
        given(task.getExecutionId()).willReturn("exec-9");
        given(runtimeService.getVariable("exec-9", "applicantIncome")).willReturn(52000);

        CapabilityExecutionContext context = new ExternalTaskExecutionContext(task, runtimeService);

        assertThat(context.getVariable("applicantIncome")).isEqualTo(52000);
    }

    @Test
    void externalTaskShapeBuffersWritesForCompletionRatherThanApplyingThem() {
        // An external task has no live variable scope; its variables are supplied when the task is
        // completed. Buffering is what makes a rejected output set leave no trace: the worker simply
        // never calls complete().
        LockedExternalTask task = mock(LockedExternalTask.class);
        RuntimeService runtimeService = mock(RuntimeService.class);

        ExternalTaskExecutionContext context = new ExternalTaskExecutionContext(task, runtimeService);
        context.setVariable("assessmentCleared", true);
        context.setVariable("assessmentScore", 71);

        assertThat(context.completionVariables())
                .containsEntry("assessmentCleared", true)
                .containsEntry("assessmentScore", 71)
                .hasSize(2);
        verify(runtimeService, never()).setVariable(any(), any(), any());
    }

    @Test
    void externalTaskShapeLetsAProviderReadBackItsOwnUncommittedWrite() {
        // Same guarantee the delegate shape gives for free. Without it, a provider that computes a
        // value and then reads it back would silently see the stale engine value instead.
        LockedExternalTask task = mock(LockedExternalTask.class);
        RuntimeService runtimeService = mock(RuntimeService.class);
        given(task.getExecutionId()).willReturn("exec-9");
        given(runtimeService.getVariable("exec-9", "assessmentCleared")).willReturn(false);

        CapabilityExecutionContext context = new ExternalTaskExecutionContext(task, runtimeService);
        context.setVariable("assessmentCleared", true);

        assertThat(context.getVariable("assessmentCleared")).isEqualTo(true);
    }

    @Test
    void externalTaskShapeTreatsADeliberateNullWriteAsTheCurrentValue() {
        // containsKey, not a null check: a provider that explicitly wrote null must not have that
        // silently replaced by whatever the engine still holds.
        LockedExternalTask task = mock(LockedExternalTask.class);
        RuntimeService runtimeService = mock(RuntimeService.class);
        given(task.getExecutionId()).willReturn("exec-9");
        given(runtimeService.getVariable(eq("exec-9"), any())).willReturn("stale");

        CapabilityExecutionContext context = new ExternalTaskExecutionContext(task, runtimeService);
        context.setVariable("cleared", null);

        assertThat(context.getVariable("cleared")).isNull();
    }

    @Test
    void externalTaskShapeExposesTheSameIdentifiersTheOutputBoundaryNeeds() {
        LockedExternalTask task = mock(LockedExternalTask.class);
        given(task.getProcessInstanceId()).willReturn("pi-2");
        given(task.getProcessDefinitionId()).willReturn("pd-2");
        given(task.getActivityInstanceId()).willReturn("ai-2");
        given(task.getBusinessKey()).willReturn("bk-2");

        CapabilityExecutionContext context = new ExternalTaskExecutionContext(task, mock(RuntimeService.class));

        assertThat(context.getProcessInstanceId()).isEqualTo("pi-2");
        assertThat(context.getProcessDefinitionId()).isEqualTo("pd-2");
        assertThat(context.getActivityInstanceId()).isEqualTo("ai-2");
        assertThat(context.getBusinessKey()).isEqualTo("bk-2");
    }

    @Test
    void completionVariablesCannotBeMutatedByTheCaller() {
        ExternalTaskExecutionContext context = new ExternalTaskExecutionContext(
                mock(LockedExternalTask.class), mock(RuntimeService.class));

        assertThatThrownBy(() -> context.completionVariables().put("smuggled", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void neitherShapeAcceptsMissingCollaborators() {
        assertThatThrownBy(() -> new DelegateExecutionContext(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExternalTaskExecutionContext(null, mock(RuntimeService.class)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExternalTaskExecutionContext(mock(LockedExternalTask.class), null))
                .isInstanceOf(NullPointerException.class);
    }
}
