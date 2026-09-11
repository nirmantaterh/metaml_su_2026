package com.tp.TargetPlatform.portal;

import java.util.List;
import java.util.Set;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metaml.workbench.automation.ComponentExecutor;
import com.metaml.workbench.bpmn.BpmnCapabilityContractReader;

/** Safely seeds an instance binding only when installed providers have one unambiguous output match. */
@Component
public class StandaloneCapabilityResolver {
    private static final Logger log = LoggerFactory.getLogger(StandaloneCapabilityResolver.class);
    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final List<ComponentExecutor> executors;

    public StandaloneCapabilityResolver(RuntimeService runtimeService, RepositoryService repositoryService,
            List<ComponentExecutor> executors) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.executors = executors;
    }

    public void resolveIfUnambiguous(LockedExternalTask task) {
        String activityId = task.getActivityId();
        Set<String> required = BpmnCapabilityContractReader.gatewayConditionVariablesByActivityId(
                repositoryService.getBpmnModelInstance(task.getProcessDefinitionId())).getOrDefault(activityId, Set.of());
        if (required.isEmpty()
                || runtimeService.getVariable(task.getProcessInstanceId(), "evolvedAgentType_" + activityId) != null
                || runtimeService.getVariable(task.getProcessInstanceId(), "evolvedAgent_" + activityId) != null) return;
        List<ComponentExecutor> matches = executors.stream()
                .filter(executor -> executor.providedOutputNames().containsAll(required)).toList();
        if (matches.size() == 1) {
            runtimeService.setVariable(task.getProcessInstanceId(), "evolvedAgentType_" + activityId,
                    matches.get(0).getHandledAgentType());
            log.info("CAPABILITY AUTO-BIND: activityId={} processInstanceId={} providerIdentity={} requiredOutputs={}",
                    activityId, task.getProcessInstanceId(), matches.get(0).getHandledAgentType(), required);
        } else {
            log.warn("CAPABILITY RESOLUTION REQUIRED: activityId={} processInstanceId={} requiredOutputs={} compatibleProviders={}. "
                            + "No provider was selected automatically.",
                    activityId, task.getProcessInstanceId(), required,
                    matches.stream().map(ComponentExecutor::getHandledAgentType).toList());
        }
    }
}
