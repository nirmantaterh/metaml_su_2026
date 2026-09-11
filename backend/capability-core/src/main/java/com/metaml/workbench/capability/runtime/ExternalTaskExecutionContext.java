package com.metaml.workbench.capability.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;

// CapabilityExecutionContext over an external task, so the identical provider that runs behind a
// delegateExpression service task also runs behind an external-task worker.
//
// Two things are different from the delegate shape, and both are forced by how external tasks work:
//
// Reads go through RuntimeService against the task's own executionId rather than through
// LockedExternalTask.getVariables(). The generated poller's fetchAndLock does not request variables,
// and that poller is emitted from a file this work is not permitted to modify - so relying on the
// locked task's snapshot would mean either changing that file or silently handing providers an empty
// variable map. Reading by executionId also keeps activity-scoped variables correctly scoped, which
// a process-instance-level read would not.
//
// Writes are buffered rather than applied. An external task has no live variable scope to write
// into; its variables are supplied when the task is completed. So setVariable collects into
// completionVariables(), which the calling worker hands to
// externalTaskService.complete(taskId, workerId, variables). This is also why the buffer is consulted
// on read: a provider that sets a value and reads it back must see its own write, exactly as it would
// on a DelegateExecution.
//
// The buffer is never written to the engine by this class. If the activity fails - a provider throws,
// or the output contract rejects the result - the worker never calls complete(), so nothing this
// provider computed becomes process state. That is the intended failure mode, not an oversight.
public final class ExternalTaskExecutionContext implements CapabilityExecutionContext {

    private final LockedExternalTask task;
    private final RuntimeService runtimeService;
    private final Map<String, Object> pending = new LinkedHashMap<>();

    public ExternalTaskExecutionContext(LockedExternalTask task, RuntimeService runtimeService) {
        this.task = Objects.requireNonNull(task, "task must not be null");
        this.runtimeService = Objects.requireNonNull(runtimeService, "runtimeService must not be null");
    }

    @Override
    public Object getVariable(String name) {
        // Pending first: an uncommitted write from this same execution is the newer value.
        // containsKey rather than a null check, so a deliberately-null write is not silently
        // replaced by a stale engine value.
        if (pending.containsKey(name)) {
            return pending.get(name);
        }
        return runtimeService.getVariable(task.getExecutionId(), name);
    }

    @Override
    public void setVariable(String name, Object value) {
        pending.put(name, value);
    }

    // Variables to pass to externalTaskService.complete(...). Empty until a provider writes something.
    public Map<String, Object> completionVariables() {
        return Collections.unmodifiableMap(pending);
    }

    @Override
    public String getProcessInstanceId() {
        return task.getProcessInstanceId();
    }

    @Override
    public String getProcessDefinitionId() {
        return task.getProcessDefinitionId();
    }

    @Override
    public String getActivityInstanceId() {
        return task.getActivityInstanceId();
    }

    @Override
    public String getBusinessKey() {
        return task.getBusinessKey();
    }
}
