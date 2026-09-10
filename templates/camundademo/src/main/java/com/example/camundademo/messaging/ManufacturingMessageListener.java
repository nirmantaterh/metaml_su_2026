package com.example.camundademo.messaging;

import org.camunda.bpm.engine.MismatchingMessageCorrelationException;
import org.camunda.bpm.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Consumes Twin responses and resumes waiting Camunda process instances via message correlation.
@Component
@ConditionalOnProperty(name = "metaml.messaging.enabled", havingValue = "true")
public class ManufacturingMessageListener {

    private static final Logger logger = LoggerFactory.getLogger(ManufacturingMessageListener.class);

    private final RuntimeService runtimeService;

    public ManufacturingMessageListener(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @RabbitListener(queues = MessagingTopology.TWIN_STAGE_RESPONSE_QUEUE)
    public void onTwinStageResponse(HarnessMessage message) {
        if (message == null || message.getActivityId() == null) {
            logger.error("[manufacturing] discarding malformed twin stage response: {}", message);
            return;
        }
        logger.info("[manufacturing] twin reported stage result '{}' for activity '{}' "
                + "(processInstanceId={}, correlationId={}) - round trip complete",
                message.getStatus(), message.getActivityId(), message.getProcessInstanceId(),
                message.getCorrelationId());
        resumeProcessIfWaiting(message);
    }

    // Correlates to a specific process instance; treats MismatchingMessageCorrelationException as benign if the process is not waiting.
    private void resumeProcessIfWaiting(HarnessMessage message) {
        String processInstanceId = message.getProcessInstanceId();
        if (processInstanceId == null) {
            logger.info("[manufacturing] no processInstanceId on the twin response - nothing to resume");
            return;
        }
        try {
            runtimeService.createMessageCorrelation(MessagingTopology.TWIN_STAGE_RESPONSE_BPMN_MESSAGE)
                    .processInstanceId(processInstanceId)
                    .setVariable(MessagingTopology.TWIN_QC_RESULT_VARIABLE, message.getStatus())
                    .correlateWithResult();
            logger.info("[manufacturing] resumed Camunda process instance {} on message '{}' "
                    + "with {}='{}' (correlationId={})",
                    processInstanceId, MessagingTopology.TWIN_STAGE_RESPONSE_BPMN_MESSAGE,
                    MessagingTopology.TWIN_QC_RESULT_VARIABLE, message.getStatus(),
                    message.getCorrelationId());
        } catch (MismatchingMessageCorrelationException e) {
            logger.info("[manufacturing] process instance {} is not waiting on '{}' - "
                    + "nothing to resume (this model does not declare that wait state)",
                    processInstanceId, MessagingTopology.TWIN_STAGE_RESPONSE_BPMN_MESSAGE);
        } catch (RuntimeException e) {
            logger.error("[manufacturing] FAILED to resume process instance {} on '{}' (correlationId={}): {}",
                    processInstanceId, MessagingTopology.TWIN_STAGE_RESPONSE_BPMN_MESSAGE,
                    message.getCorrelationId(), e.toString(), e);
        }
    }

    @RabbitListener(queues = MessagingTopology.MACHINES_COMPLETION_QUEUE)
    public void onMachineCompletion(HarnessMessage message) {
        if (message == null || message.getActivityId() == null) {
            logger.error("[manufacturing] discarding malformed machine completion: {}", message);
            return;
        }
        logger.info("[manufacturing] machines reported '{}' for activity '{}' (correlationId={})",
                message.getStatus(), message.getActivityId(), message.getCorrelationId());
    }
}
