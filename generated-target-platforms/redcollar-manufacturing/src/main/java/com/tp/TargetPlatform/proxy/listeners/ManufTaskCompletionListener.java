package com.tp.TargetPlatform.proxy.listeners;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component("manufTaskCompletionListener")
public class ManufTaskCompletionListener implements ExecutionListener {

    private static final Logger logger = LoggerFactory.getLogger(ManufTaskCompletionListener.class);

    @Override
    public void notify(DelegateExecution execution) throws Exception {
        logger.info("PROXY (LISTENER) INVOKED: listener={} bean={} event={} processDefinitionId={} "
                + "processInstanceId={} activityId={} activityName={} activityInstanceId={} "
                + "businessKey={}",
                "ManufTaskCompletionListener", "manufTaskCompletionListener", execution.getEventName(),
                execution.getProcessDefinitionId(), execution.getProcessInstanceId(),
                execution.getCurrentActivityId(), execution.getCurrentActivityName(),
                execution.getActivityInstanceId(), execution.getProcessBusinessKey());
    }
}
