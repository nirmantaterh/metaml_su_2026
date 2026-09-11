package com.tp.TargetPlatform.signal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.EventSubscription;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tp.TargetPlatform.coordination.PairRegistry;
import com.tp.TargetPlatform.messaging.RabbitMqConfig;
import com.tp.TargetPlatform.messaging.TaskQueuePublisher;
import com.tp.TargetPlatform.messaging.ResponseQueuePublisher;

// Coordinates delivery of BPMN signal events between paired Proxy and Twin process executions.
@Component
public class SignalBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(SignalBroadcaster.class);
    private static final List<String> SIGNAL_NAMES = List.of("samplingSignal", "layingSignal", "markingSignal", "cuttingSignal", "stitchingSignal", "checkingSignal", "pressingSignal", "packagingSignal", "shippingSignal", "Signal_3733ues", "orderVerifySignal");

    private final RuntimeService runtimeService;
    private final PairRegistry pairRegistry;
    private final TaskQueuePublisher taskQueuePublisher;
    private final ResponseQueuePublisher responseQueuePublisher;
    private final Set<String> awaitingResponse = ConcurrentHashMap.newKeySet();
    private final Set<String> everDelivered = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> partnerArrivalTicks = new ConcurrentHashMap<>();
    private static final int MAX_PARTNER_ARRIVAL_TICKS = 5;
    // Tracks handoffKeys logged as stalled on an incident so error logging occurs once per stall period rather than repeatedly on every tick.
    private final Set<String> stuckOnIncidentLogged = ConcurrentHashMap.newKeySet();

    public SignalBroadcaster(RuntimeService runtimeService, PairRegistry pairRegistry,
            TaskQueuePublisher taskQueuePublisher, ResponseQueuePublisher responseQueuePublisher) {
        this.runtimeService = runtimeService;
        this.pairRegistry = pairRegistry;
        this.taskQueuePublisher = taskQueuePublisher;
        this.responseQueuePublisher = responseQueuePublisher;
    }

    @Scheduled(fixedDelay = 1000)
    public void broadcastSignals() {
        for (String signalName : SIGNAL_NAMES) {
            List<EventSubscription> waiting = runtimeService.createEventSubscriptionQuery()
                    .eventType("signal")
                    .eventName(signalName)
                    .list();
            for (EventSubscription subscription : waiting) {
                handle(signalName, subscription, waiting);
            }
        }
    }

    private void handle(String signalName, EventSubscription subscription,
            List<EventSubscription> waitingForSameSignal) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(subscription.getProcessInstanceId())
                .singleResult();
        String businessKey = instance == null ? null : instance.getBusinessKey();
        String role = pairRegistry.roleOf(businessKey, subscription.getProcessInstanceId());
        String partnerInstanceId = pairRegistry.partnerOf(businessKey, subscription.getProcessInstanceId());

        if (role == null || partnerInstanceId == null) {
            deliverTo(signalName, subscription, businessKey, "DELIVERED");
            return;
        }
        boolean partnerWaitingNow = waitingForSameSignal.stream()
                .anyMatch(s -> s.getProcessInstanceId().equals(partnerInstanceId));
        String waitKey = subscription.getProcessInstanceId() + "|" + signalName;

        if ("responder".equals(role)) {
            if (partnerWaitingNow) {
                partnerArrivalTicks.remove(waitKey);
            } else if (partnerNotComing(waitKey, partnerInstanceId, signalName)) {
                deliverTo(signalName, subscription, businessKey, "DELIVERED");
            }
            return;
        }

        String handoffKey = businessKey + "|" + signalName;
        if (awaitingResponse.contains(handoffKey)) {
            if (responderHasAdvancedPast(signalName, partnerInstanceId)) {
                awaitingResponse.remove(handoffKey);
                stuckOnIncidentLogged.remove(handoffKey);
                deliverTo(signalName, subscription, businessKey, "RESPONSE");
            } else {
                // Distinguishes an in-progress partner from an unresolvable incident: checks for open Camunda incidents on the partner instance.
                // If an incident exists, logs an observable error rather than silently waiting, while preventing premature proxy progression until the partner legitimately advances.
                boolean partnerHasOpenIncident = runtimeService.createIncidentQuery()
                        .processInstanceId(partnerInstanceId).count() > 0;
                if (partnerHasOpenIncident) {
                    // add() itself is what makes this fire only the FIRST tick an incident is observed for this handoffKey - checking the incident BEFORE calling add() (rather than relying on add()'s own return value to short- circuit the query) is what keeps a legitimately-slow, incident-free tick from ever marking this handoffKey "already logged".
                    if (stuckOnIncidentLogged.add(handoffKey)) {
                        logger.error("STUCK: proxy execution {} (businessKey={}) is waiting on "
                                + "RESPONSE for signal '{}', but its twin partner "
                                + "(processInstanceId={}) has an open Camunda incident and will "
                                + "not advance on its own - this handoff will not complete until "
                                + "that incident is resolved. The proxy has NOT been advanced.",
                                subscription.getExecutionId(), businessKey, signalName,
                                partnerInstanceId);
                    }
                } else {
                    // No incident currently open (never had one, or a prior one was already resolved) - clear any stale suppression so a LATER incident on this same handoffKey logs again instead of staying silenced forever.
                    stuckOnIncidentLogged.remove(handoffKey);
                }
            }
            return;
        }

        if (partnerWaitingNow) {
            partnerArrivalTicks.remove(waitKey);
            EventSubscription responderSubscription = waitingForSameSignal.stream()
                    .filter(s -> s.getProcessInstanceId().equals(partnerInstanceId))
                    .findFirst()
                    .orElse(null);
            if (responderSubscription != null) {
                deliverTo(signalName, responderSubscription, businessKey, "REQUEST");
                awaitingResponse.add(handoffKey);
            }
            return;
        }

        if (partnerNotComing(waitKey, partnerInstanceId, signalName)) {
            deliverTo(signalName, subscription, businessKey, "DELIVERED");
        }
    }

    private boolean partnerNotComing(String waitKey, String partnerInstanceId, String signalName) {
        if (everDelivered.contains(partnerInstanceId + "|" + signalName)) {
            partnerArrivalTicks.remove(waitKey);
            return true;
        }
        int ticks = partnerArrivalTicks.merge(waitKey, 1, Integer::sum);
        if (ticks >= MAX_PARTNER_ARRIVAL_TICKS) {
            partnerArrivalTicks.remove(waitKey);
            return true;
        }
        return false;
    }

    // Checks whether the responder has completed or transitioned past the task gated by signalName.
    private boolean responderHasAdvancedPast(String signalName, String responderInstanceId) {
        ProcessInstance stillActive = runtimeService.createProcessInstanceQuery()
                .processInstanceId(responderInstanceId)
                .singleResult();
        if (stillActive == null) {
            return true;
        }
        List<EventSubscription> responderSignals = runtimeService.createEventSubscriptionQuery()
                .processInstanceId(responderInstanceId)
                .eventType("signal")
                .list();
        boolean stillOnSameSignal = responderSignals.stream()
                .anyMatch(s -> s.getEventName().equals(signalName));
        if (stillOnSameSignal) {
            return false;
        }
        return !responderSignals.isEmpty();
    }

    private void deliverTo(String signalName, EventSubscription subscription, String businessKey,
            String phase) {
        boolean gated = RabbitMqConfig.TASK_QUEUE_BY_SIGNAL.containsKey(signalName);
        if (gated) {
            if ("REQUEST".equals(phase) && taskQueuePublisher.isEnabled()
                    && taskQueuePublisher.isEligible(signalName)) {
                taskQueuePublisher.publish(signalName, subscription.getExecutionId(),
                        subscription.getProcessInstanceId(), businessKey);
                everDelivered.add(subscription.getProcessInstanceId() + "|" + signalName);
                return;
            }
            if ("RESPONSE".equals(phase) && responseQueuePublisher.isEnabled()
                    && responseQueuePublisher.isEligible(signalName)) {
                responseQueuePublisher.publish(signalName, subscription.getExecutionId(),
                        subscription.getProcessInstanceId(), businessKey);
                everDelivered.add(subscription.getProcessInstanceId() + "|" + signalName);
                return;
            }
        }
        try {
            runtimeService.signalEventReceived(signalName, subscription.getExecutionId());
            everDelivered.add(subscription.getProcessInstanceId() + "|" + signalName);
            logger.info("{}: delivered signal '{}' to execution {} (processInstanceId={}, "
                    + "businessKey={})", phase, signalName, subscription.getExecutionId(),
                    subscription.getProcessInstanceId(), businessKey);
        } catch (Exception e) {
            // Expected during normal operation - the execution may already have advanced.
        }
    }
}
