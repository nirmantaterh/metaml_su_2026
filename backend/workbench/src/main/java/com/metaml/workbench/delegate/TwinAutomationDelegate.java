package com.metaml.workbench.delegate;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.automation.ProjectAutomationService;
import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.runtime.CapabilityOutputContractSource;
import com.metaml.workbench.capability.runtime.CapabilityOutputPropagator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
import com.metaml.workbench.model.AgentVariables;
import com.metaml.workbench.model.BusinessKeys;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import java.util.LinkedHashSet;
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
    // Optional by construction: CatalogCapabilityOutputContractSource is the one production
    // implementation (see CapabilityOutputContractSource), but this stays an ObjectProvider rather
    // than a hard dependency so a test can still exercise this delegate with no contract source
    // registered at all. Resolved per execution rather than once, so a registration (or a test's
    // @MockitoBean override of it) is picked up without this class changing shape.
    private final ObjectProvider<CapabilityOutputContractSource> outputContractSource;

    // No capability output contract source available: nothing declares an output contract for
    // these executions, so the boundary has no contract to enforce and publication is unchanged.
    public TwinAutomationDelegate(Map<String, ProjectAutomationService> automationsByProject,
            WorkbenchService workbenchService, RepositoryService repositoryService) {
        this(automationsByProject, workbenchService, repositoryService, null);
    }

    @Autowired
    public TwinAutomationDelegate(Map<String, ProjectAutomationService> automationsByProject,
            WorkbenchService workbenchService, RepositoryService repositoryService,
            ObjectProvider<CapabilityOutputContractSource> outputContractSource) {
        this.automationsByProject = automationsByProject;
        this.workbenchService = workbenchService;
        this.repositoryService = repositoryService;
        this.outputContractSource = outputContractSource;
    }

    @Override
    public void execute(DelegateExecution execution) {
        // getCurrentActivityId() returns the automation task's id; recover the receive task's id that variables key off
        String activityId = TwinModelGenerator.synchronizationActivityIdOf(execution.getCurrentActivityId());
        ProjectAutomationService automation = automationFor(execution);
        AutomationResult result = automation.execute(execution);

        Object loopCounter = execution.getVariable(LOOP_COUNTER_VARIABLE);

        // Resolved exactly once and reused for both the Phase 4 boundary below and the Phase 5
        // notification after it, so the contract that was enforced and the identity that is
        // reported are guaranteed to describe the same provider. A second lookup for the
        // notification could observe a different catalog and report a provider whose contract was
        // never the one validated here.
        CapabilityProvider provider = resolveProvider(execution, activityId, loopCounter);

        // Every provider output in AutomationResult.outputs() reaches process state through exactly
        // one mechanism: the Phase 4 capability output boundary. It validates the complete output
        // set against the declared contract of the provider first and publishes nothing at all if
        // any of it violates that contract, so no partial or undeclared provider output can become
        // BPMN process state.
        CapabilityOutputPropagator.publish(execution, provider,
                result.outputs(), processVisibleOutputNames(execution, activityId, result.outputs()),
                activityId, loopCounter);

        // MetaML Scope 6, Phase 5: publish() above only returns normally when the provider's output
        // passed the Phase 4 contract and was actually written to process state - exactly the one
        // event allowed to move a BOUND capability gap to RESOLVED. A provider that threw out of
        // automation.execute(), or whose output publish() rejected, never reaches this line, so a
        // gap is never resolved for either of those cases (Phase 5 section 13/17).
        //
        // A null provider here means no capability contract governed this execution - the
        // default/fallback automation path - so publish() enforced nothing. Passing that null
        // through unchanged is what lets CapabilityGapService refuse to resolve a gap on the
        // strength of automation that never ran the bound provider at all.
        String twinProcessId = twinProcessIdOf(execution);
        if (twinProcessId != null) {
            workbenchService.notifyCapabilityProviderExecutionSucceeded(twinProcessId, activityId, loopCounter,
                    provider == null ? null : provider.providerId());
        }

        // The summary is delegate bookkeeping about the run, not a provider output, so it is not
        // part of the output contract - and it is written only once publication has succeeded.
        execution.setVariable(AgentVariables.twinAutomation(activityId, loopCounter), result.summary());
    }

    // The twin process instance id for this execution, or null when this execution is not running
    // under a twin business key at all (e.g. a unit test driving the delegate directly).
    private static String twinProcessIdOf(DelegateExecution execution) {
        String businessKey = execution.getProcessBusinessKey();
        return BusinessKeys.isTwinKey(businessKey) ? BusinessKeys.twinIdFromTwinKey(businessKey) : null;
    }

    // Which output names downstream BPMN logic actually reads stays a BPMN question, answered from
    // the deployed model rather than from any Java-side mapping.
    //
    // The gateway's predecessor can be any of the ids this one twin activity occupies, because the
    // twin generator shapes an activity differently depending on the original: a plain activity
    // becomes receive task -> automation task, while a multi-instance one is additionally wrapped in
    // a subprocess whose id is what the following flow actually leaves from (see
    // TwinModelGenerator.exitNodeId). So rather than guessing one or two of those ids, this walks
    // outwards from the automation task through every scope enclosing it and unions what each one
    // contributes. All of them belong to this same activity, so the union can never pick up another
    // activity's gateway variable.
    private Set<String> processVisibleOutputNames(DelegateExecution execution, String activityId,
            Map<String, Object> actualOutputs) {
        if (actualOutputs.isEmpty()) {
            // nothing to match a gateway variable against; skip the model read exactly as before
            return Set.of();
        }
        try {
            BpmnModelInstance model = repositoryService.getBpmnModelInstance(
                    execution.getProcessDefinitionId());
            Map<String, Set<String>> gatewayVarsByActivity =
                    ExternalTaskWorkerGenerator.detectGatewayVariablesByActivityId(model);
            Set<String> required = new LinkedHashSet<>();
            for (String candidate : predecessorIds(model, execution, activityId)) {
                required.addAll(gatewayVarsByActivity.getOrDefault(candidate, Set.of()));
            }
            return required;
        } catch (Exception e) {
            // BPMN model lookup failure should not break automation; the executor may have already
            // set the variables directly via execution.setVariable(). Log at ERROR with full stack
            // so the root cause is visible if the gateway later fails.
            logger.error("Could not determine gateway variables for activity {} (automation task {}): "
                    + "gateway variables may be unset if executor did not set them directly",
                    activityId, execution.getCurrentActivityId(), e);
            return Set.of();
        }
    }

    // The stripped receive task id, the automation task id, and the id of every scope enclosing the
    // automation task up to (but not including) the process itself.
    private static Set<String> predecessorIds(BpmnModelInstance model, DelegateExecution execution,
            String activityId) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(activityId);
        ids.add(execution.getCurrentActivityId());
        ModelElementInstance element = model.getModelElementById(execution.getCurrentActivityId());
        while (element != null) {
            element = element.getParentElement();
            if (element == null || element instanceof Process) {
                break;
            }
            String id = element.getAttributeValue("id");
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    // The provider whose declared contract governs this execution, or null when none is resolvable.
    // Identity is the same agent name / agent type pair DefaultProjectAutomationService dispatches
    // on, so the contract enforced is the contract of the provider that actually ran.
    private CapabilityProvider resolveProvider(DelegateExecution execution, String activityId,
            Object loopCounter) {
        CapabilityOutputContractSource source =
                outputContractSource == null ? null : outputContractSource.getIfAvailable();
        if (source == null) {
            return null;
        }
        Object agent = execution.getVariable(AgentVariables.evolvedAgent(activityId, loopCounter));
        if (agent != null && !agent.toString().isBlank()) {
            CapabilityProvider byName = source.providerFor(agent.toString()).orElse(null);
            if (byName != null) {
                return byName;
            }
        }
        Object agentType = execution.getVariable(AgentVariables.evolvedAgentType(activityId, loopCounter));
        if (agentType != null && !agentType.toString().isBlank()) {
            return source.providerFor(agentType.toString()).orElse(null);
        }
        return null;
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
