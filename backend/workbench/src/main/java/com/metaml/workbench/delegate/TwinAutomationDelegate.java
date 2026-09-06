package com.metaml.workbench.delegate;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.automation.ProjectAutomationService;
import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
import com.metaml.workbench.model.AgentVariables;
import com.metaml.workbench.model.BusinessKeys;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// Runs automation synchronously on the twin's service task, looked up by projectId bean name.
@Component("twinAutomationDelegate")
public class TwinAutomationDelegate implements JavaDelegate {

    private static final Logger logger = LoggerFactory.getLogger(TwinAutomationDelegate.class);

    private static final String DEFAULT_PROJECT_ID = "default";
    private static final String LOOP_COUNTER_VARIABLE = "loopCounter";

    private final Map<String, ProjectAutomationService> automationsByProject;
    private final WorkbenchService workbenchService;
    private final RepositoryService repositoryService;

    public TwinAutomationDelegate(Map<String, ProjectAutomationService> automationsByProject,
            WorkbenchService workbenchService, RepositoryService repositoryService) {
        this.automationsByProject = automationsByProject;
        this.workbenchService = workbenchService;
        this.repositoryService = repositoryService;
    }

    @Override
    public void execute(DelegateExecution execution) {
        // getCurrentActivityId() returns the automation task's id; recover the receive task's id that variables key off
        String activityId = TwinModelGenerator.synchronizationActivityIdOf(execution.getCurrentActivityId());
        ProjectAutomationService automation = automationFor(execution);
        AutomationResult result = automation.execute(execution);

        Object loopCounter = execution.getVariable(LOOP_COUNTER_VARIABLE);
        execution.setVariable(AgentVariables.twinAutomation(activityId, loopCounter), result.summary());
        for (Map.Entry<String, Object> output : result.outputs().entrySet()) {
            execution.setVariable(
                    AgentVariables.twinAutomationOutput(output.getKey(), activityId, loopCounter),
                    output.getValue());
        }

        // Propagate executor outputs as bare gateway variables when they match a
        // detected gateway condition variable for this activity. This is the PRODUCTION path:
        // ComponentExecutor → AutomationResult.outputs() → process variable → Camunda gateway.
        // The executor may also set variables directly via execution.setVariable() (e.g.
        // CreditRiskAssessorExecutor sets agentFlaggedRisk) — those writes already reach the
        // gateway. This block covers executors that only return outputs in the AutomationResult
        // map without setting them directly on the execution.
        propagateExecutorOutputsAsGatewayVariables(execution, activityId, result);
    }

    // Detects which gateway variables are required downstream of this activity (using the same
    // BPMN analysis as advanceTwinActivity) and sets any matching executor outputs as bare
    // process variables. Runs synchronously inside the correlation command's transaction, so
    // the gateway that evaluates immediately after this service task sees the values.
    //
    // The twin model's gateway predecessor is the automation task (Activity_X_automate), not
    // the receive task (Activity_X). detectGatewayVariablesByActivityId keys by the gateway's
    // immediate predecessor, so we check both the automation task ID (twin model) and the
    // stripped receive task ID (original model convention) to handle either model structure.
    private void propagateExecutorOutputsAsGatewayVariables(DelegateExecution execution,
            String activityId, AutomationResult result) {
        if (result.outputs().isEmpty()) {
            return;
        }
        try {
            BpmnModelInstance model = repositoryService.getBpmnModelInstance(
                    execution.getProcessDefinitionId());
            Map<String, Set<String>> gatewayVarsByActivity =
                    ExternalTaskWorkerGenerator.detectGatewayVariablesByActivityId(model);
            // Try both the stripped receive task ID and the actual automation task ID —
            // the twin model keys by automation task ID (the gateway's direct predecessor).
            String automationTaskId = execution.getCurrentActivityId();
            Set<String> requiredVars = gatewayVarsByActivity.getOrDefault(activityId, Set.of());
            if (requiredVars.isEmpty()) {
                requiredVars = gatewayVarsByActivity.getOrDefault(automationTaskId, Set.of());
            }
            if (requiredVars.isEmpty()) {
                return;
            }
            Map<String, Object> propagated = new LinkedHashMap<>();
            for (String varName : requiredVars) {
                Object value = result.outputs().get(varName);
                if (value != null) {
                    execution.setVariable(varName, value);
                    propagated.put(varName, value);
                }
            }
            if (!propagated.isEmpty()) {
                logger.info("PRODUCTION_STATE: executor output propagated as gateway variables {} "
                        + "on twin {} for activity {}", propagated,
                        execution.getProcessInstanceId(), activityId);
            }
        } catch (Exception e) {
            // BPMN model lookup failure should not break automation; the executor may have
            // already set the variables directly via execution.setVariable(). Log at ERROR
            // with full stack so the root cause is visible if the gateway later fails.
            logger.error("Could not propagate executor outputs as gateway variables for activity {} "
                    + "(automation task {}): gateway variables may be unset if executor did not "
                    + "set them directly", activityId, execution.getCurrentActivityId(), e);
        }
    }

    // falls back to default if the twin is no longer in bookkeeping but its token is still live
    private ProjectAutomationService automationFor(DelegateExecution execution) {
        String projectId = DEFAULT_PROJECT_ID;
        String businessKey = execution.getProcessBusinessKey();
        if (BusinessKeys.isTwinKey(businessKey)) {
            TwinProcess twin = workbenchService.findTwinProcess(
                    BusinessKeys.twinIdFromTwinKey(businessKey));
            if (twin != null && twin.getProjectId() != null && !twin.getProjectId().isBlank()) {
                projectId = twin.getProjectId();
            }
        }

        ProjectAutomationService automation = automationsByProject.get(projectId);
        if (automation != null) {
            return automation;
        }
        // missing bean is a config mistake; don't leave the twin's token stuck
        logger.warn("No ProjectAutomationService named '{}' for twin instance {}, falling back to '{}'. "
                + "Registered: {}", projectId, execution.getProcessInstanceId(), DEFAULT_PROJECT_ID,
                automationsByProject.keySet());
        return automationsByProject.get(DEFAULT_PROJECT_ID);
    }
}
