package com.metaml.workbench.automation;

import org.camunda.bpm.engine.delegate.DelegateExecution;

// Extension seam for project automation resolved by project ID during twin execution.
public interface ProjectAutomationService {

    AutomationResult execute(DelegateExecution execution);
}
