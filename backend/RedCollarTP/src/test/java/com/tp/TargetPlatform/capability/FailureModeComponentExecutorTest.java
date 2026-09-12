package com.tp.TargetPlatform.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.automation.ComponentExecutor;
import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;

class FailureModeComponentExecutorTest {

    @Test
    void normalModeDelegatesAndPreservesAValidFalseBusinessOutput() {
        ProviderTechnicalModeRegistry modes = new ProviderTechnicalModeRegistry();
        ComponentExecutor delegate = mock(ComponentExecutor.class);
        CapabilityExecutionContext context = mock(CapabilityExecutionContext.class);
        AutomationResult expected = new AutomationResult("valid business result", Map.of("decision", false));
        when(delegate.execute(context, "activity-1", "provider-1")).thenReturn(expected);

        AutomationResult actual = new FailureModeComponentExecutor(delegate, modes)
                .execute(context, "activity-1", "provider-1");

        assertThat(actual).isSameAs(expected);
        assertThat(actual.outputs()).containsEntry("decision", false);
        verify(delegate).execute(context, "activity-1", "provider-1");
    }

    @Test
    void technicalFailureThrowsBeforeDelegateAndFabricatesNoBusinessOutput() {
        ProviderTechnicalModeRegistry modes = new ProviderTechnicalModeRegistry();
        modes.setMode("provider-1", ProviderTechnicalMode.TECHNICAL_FAILURE);
        ComponentExecutor delegate = mock(ComponentExecutor.class);
        CapabilityExecutionContext context = mock(CapabilityExecutionContext.class);
        when(context.getProcessInstanceId()).thenReturn("instance-1");
        when(context.getBusinessKey()).thenReturn("run-1");

        assertThatThrownBy(() -> new FailureModeComponentExecutor(delegate, modes)
                .execute(context, "activity-1", "provider-1"))
                .isInstanceOf(ProviderTechnicalFailureException.class)
                .hasMessageContaining("provider-1")
                .hasMessageContaining("TECHNICAL_FAILURE");

        verify(delegate, never()).execute(context, "activity-1", "provider-1");
    }

    @Test
    void modesAreCaseInsensitiveAndRestoreToNormalWithoutChangingExecutorIdentity() {
        ProviderTechnicalModeRegistry modes = new ProviderTechnicalModeRegistry();

        modes.setMode("Provider-1", ProviderTechnicalMode.TECHNICAL_FAILURE);
        assertThat(modes.modeOf("provider-1")).isEqualTo(ProviderTechnicalMode.TECHNICAL_FAILURE);
        modes.setMode("provider-1", ProviderTechnicalMode.NORMAL);

        assertThat(modes.modeOf("PROVIDER-1")).isEqualTo(ProviderTechnicalMode.NORMAL);
    }
}
