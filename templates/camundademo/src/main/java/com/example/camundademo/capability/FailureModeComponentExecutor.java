package com.example.camundademo.capability;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.automation.ComponentExecutor;
import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;

/** Adds an operator-controlled technical failure mode without changing the wrapped provider. */
public final class FailureModeComponentExecutor implements ComponentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(FailureModeComponentExecutor.class);

    private final ComponentExecutor delegate;
    private final ProviderTechnicalModeRegistry modes;

    public FailureModeComponentExecutor(ComponentExecutor delegate, ProviderTechnicalModeRegistry modes) {
        this.delegate = delegate;
        this.modes = modes;
    }

    @Override
    public String getHandledAgentType() {
        return delegate.getHandledAgentType();
    }

    @Override
    public Set<String> getHandledAgentNames() {
        return delegate.getHandledAgentNames();
    }

    @Override
    public Set<String> providedOutputNames() {
        return delegate.providedOutputNames();
    }

    @Override
    public AutomationResult execute(CapabilityExecutionContext context, String activityId, String providerIdentity) {
        ProviderTechnicalMode mode = modes.modeOf(providerIdentity);
        if (mode == ProviderTechnicalMode.TECHNICAL_FAILURE) {
            ProviderTechnicalFailureException failure = new ProviderTechnicalFailureException(providerIdentity);
            logger.error("CAPABILITY TECHNICAL_FAILURE: activity={} processInstanceId={} businessKey={} "
                            + "providerIdentity={} exception={}", activityId, context.getProcessInstanceId(),
                    context.getBusinessKey(), providerIdentity, failure.getMessage());
            throw failure;
        }
        return delegate.execute(context, activityId, providerIdentity);
    }
}
