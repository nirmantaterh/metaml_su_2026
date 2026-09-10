package com.metaml.workbench.codegen;

// Thrown when a BPMN delegateExpression is invalid or does not name a bean.
public class InvalidDelegateExpressionException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    // Fallback label to ensure non-blank error reporting in the UI.
    private static final String BLANK_LABEL = "(blank)";

    private final String bpmnElementId;
    private final String taskName;
    private final String rawExpression;

    public InvalidDelegateExpressionException(String bpmnElementId, String taskName, String rawExpression) {
        super("BPMN element '" + bpmnElementId + "'"
                + (taskName == null || taskName.isBlank() ? "" : " (" + taskName.replaceAll("\\s+", " ").trim() + ")")
                + " declares a delegateExpression of '" + (rawExpression == null ? "" : rawExpression)
                + "', which does not name a delegate bean. Give the task a delegate expression such as"
                + " ${myService}, or remove the attribute.");
        this.bpmnElementId = bpmnElementId;
        this.taskName = taskName;
        this.rawExpression = rawExpression == null || rawExpression.isBlank() ? BLANK_LABEL : rawExpression;
    }

    // Thrown when two distinct delegate expressions sanitize to the same Java class name.
    public static InvalidDelegateExpressionException collision(String bpmnElementId, String taskName,
            String rawExpression, String otherExpression, String className) {
        return new InvalidDelegateExpressionException(bpmnElementId, taskName, rawExpression,
                "BPMN element '" + bpmnElementId + "' declares delegateExpression '" + rawExpression
                        + "', which generates the same delegate class '" + className + "' as '" + otherExpression
                        + "'. Only one of them can exist, so rename one of the two expressions.");
    }

    private InvalidDelegateExpressionException(String bpmnElementId, String taskName, String rawExpression,
            String message) {
        super(message);
        this.bpmnElementId = bpmnElementId;
        this.taskName = taskName;
        this.rawExpression = rawExpression == null || rawExpression.isBlank() ? BLANK_LABEL : rawExpression;
    }

    public String bpmnElementId() {
        return bpmnElementId;
    }

    public String taskName() {
        return taskName;
    }

    public String rawExpression() {
        return rawExpression;
    }
}
