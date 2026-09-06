package com.metaml.workbench.generation;

import java.io.IOException;
import java.io.UncheckedIOException;

// A single generated delegate's file failed to write. The only failure in the generate pipeline
// attributable to one BPMN element, so it carries which one instead of being flattened into a bare
// UncheckedIOException like every other write in SpringBootProjectGenerator.
public class DelegateWriteException extends UncheckedIOException {

    private final String beanName;
    private final String bpmnElementId;

    public DelegateWriteException(String message, IOException cause, String beanName, String bpmnElementId) {
        super(message, cause);
        this.beanName = beanName;
        this.bpmnElementId = bpmnElementId;
    }

    public String beanName() {
        return beanName;
    }

    // Null when the failed delegate is shared by several BPMN elements - see GeneratedDelegate.
    public String bpmnElementId() {
        return bpmnElementId;
    }
}
