package com.metaml.workbench.codegen;

// One generated external-task worker class, ready to write to a file. topic is what Camunda's
// fetch-and-lock matches on, so it is the only identity actually wiring the worker to its BPMN task.
// Parallels GeneratedDelegate: an external task carries no delegateExpression, so DelegateClassGenerator
// never sees it and something else has to make it executable.
public record GeneratedWorker(String className, String topic, String sourceCode) {
}
