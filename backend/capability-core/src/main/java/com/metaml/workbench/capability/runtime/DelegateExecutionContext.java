package com.metaml.workbench.capability.runtime;

import java.util.Objects;

import org.camunda.bpm.engine.delegate.DelegateExecution;

// CapabilityExecutionContext over a DelegateExecution - the shape the Workbench's own Twin automation
// has always used, and the shape a generated Target Platform gets for a delegateExpression service
// task. Straight delegation: the execution is already the live variable scope, so there is nothing to
// buffer and a write is visible to the next read for free.
public final class DelegateExecutionContext implements CapabilityExecutionContext {

    private final DelegateExecution execution;

    public DelegateExecutionContext(DelegateExecution execution) {
        this.execution = Objects.requireNonNull(execution, "execution must not be null");
    }

    @Override
    public Object getVariable(String name) {
        return execution.getVariable(name);
    }

    @Override
    public void setVariable(String name, Object value) {
        execution.setVariable(name, value);
    }

    @Override
    public String getProcessInstanceId() {
        return execution.getProcessInstanceId();
    }

    @Override
    public String getProcessDefinitionId() {
        return execution.getProcessDefinitionId();
    }

    @Override
    public String getActivityInstanceId() {
        return execution.getActivityInstanceId();
    }

    @Override
    public String getBusinessKey() {
        return execution.getProcessBusinessKey();
    }
}
