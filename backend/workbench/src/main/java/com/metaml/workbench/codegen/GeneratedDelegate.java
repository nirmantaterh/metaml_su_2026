package com.metaml.workbench.codegen;

// Represents a generated Java delegate or task listener class.
// Maps unwrapped delegate expressions to @Component bean names, with null bpmnElementId for shared beans.
public record GeneratedDelegate(String beanName, String className, String taskName, DelegateKind kind,
        String sourceCode, String bpmnElementId) {

    public GeneratedDelegate(String beanName, String className, String taskName, DelegateKind kind,
            String sourceCode) {
        this(beanName, className, taskName, kind, sourceCode, null);
    }
}
