package com.metaml.workbench.codegen;

// Which Java interface the generated class must implement. Camunda expects a different one depending
// on where the delegateExpression sits: JavaDelegate when it IS the service task (SERVICE_TASK),
// TaskListener when it hangs off a user task's lifecycle (TASK_LISTENER).
public enum DelegateKind {
    SERVICE_TASK,
    TASK_LISTENER,
    JAVA_CLASS
}
