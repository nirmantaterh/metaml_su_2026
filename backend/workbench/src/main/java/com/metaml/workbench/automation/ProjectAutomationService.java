package com.metaml.workbench.automation;

import org.camunda.bpm.engine.delegate.DelegateExecution;

// Extension seam: a project registers one bean named after its project id, and
// TwinAutomationDelegate resolves it by that name - adding one needs no change here.
//
// The execution passed in is the TWIN's. Write only to it; the original belongs to whoever is
// clicking through it. getCurrentActivityId() is this automation's own service task
// (Task_KYC_automate), not the activity the evolvedAgent_* variables are named after - map it with
// TwinModelGenerator.synchronizationActivityIdOf() first or every lookup silently misses.
public interface ProjectAutomationService {

    AutomationResult execute(DelegateExecution execution);
}
