package com.metaml.workbench.workflow;

// Machine-readable detail for a FAILED StageEvent, alongside its free-form `detail` message.
// Every field is optional - populated only when the catch site actually had that information, never
// guessed or parsed out of a message.
public record StageError(String errorType, String operation, String projectId, Integer port,
        Integer exitCode, String delegateExpression, String bpmnElementId) {
}
