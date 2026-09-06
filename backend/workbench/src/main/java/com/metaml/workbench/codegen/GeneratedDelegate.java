package com.metaml.workbench.codegen;

// One generated Java Delegate (or TaskListener) class, ready to write to a file.
// beanName is the unwrapped delegateExpression (calculateInterestService), which is also the
// @Component name the generated class registers under - that is what makes the BPMN's own
// delegateExpression resolve to it at runtime.
// bpmnElementId is null when several BPMN elements share one delegateExpression: the bean is then
// genuinely shared, so naming one of them as the source would be a fabricated link.
public record GeneratedDelegate(String beanName, String className, String taskName, DelegateKind kind,
        String sourceCode, String bpmnElementId) {

    public GeneratedDelegate(String beanName, String className, String taskName, DelegateKind kind,
            String sourceCode) {
        this(beanName, className, taskName, kind, sourceCode, null);
    }
}
