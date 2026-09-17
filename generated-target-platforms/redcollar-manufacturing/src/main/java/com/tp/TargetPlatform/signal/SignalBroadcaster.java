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
    // The responder subscription released for each handoff. A responder may loop
    // back to the same signal after it completes its gated work; that creates a new
    // subscription id and is proof that the released turn really completed.
    private final Map<String, String> awaitingResponderSubscriptions = new ConcurrentHashMap<>();
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
        if (awaitingResponderSubscriptions.containsKey(handoffKey)) {
            if (responderHasAdvancedPast(signalName, partnerInstanceId,
                    awaitingResponderSubscriptions.get(handoffKey))) {
                awaitingResponderSubscriptions.remove(handoffKey);
                stuckOnIncidentLogged.remove(handoffKey);
                deliverTo(signalName, subscription, businessKey, "RESPONSE");
            } else {
                // Checks whether the partner instance has an active Camunda incident blocking advancement.
                boolean partnerHasOpenIncident = runtimeService.createIncidentQuery()
                        .processInstanceId(partnerInstanceId).count() > 0;
                if (partnerHasOpenIncident) {
                    // Logs the incident once per handoffKey until resolved.
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
                    // Clear logged state when no incidents remain.
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
                awaitingResponderSubscriptions.put(handoffKey, responderSubscription.getId());
            }
            return;
        }

        if (partnerNotComing(waitKey, partnerInstanceId, signalName)) {
            deliverTo(signalName, subscription, businessKey, "DELIVERED");
        }
    }

    // "The partner will never turn up here, stop waiting for it." Getting this wrong in
    // the permissive direction is what breaks lockstep: releasing this side early is
    // indistinguishable, from the outside, from a synchronization that never happened.
    //
    // The five-tick budget predates human activities. It assumes both sides reach a
    // shared signal within seconds, which held while every gated activity was an
    // engine-driven service or external task. It does not hold when the partner is a
    // person: a Proxy parked on a userTask reaches its sync point only when someone
    // completes the task, which is minutes or hours, not five seconds - and the budget
    // would release the Twin long before that, letting it run the whole process
    // through while the human had not started.
    //
    // So the budget is now spent only when the partner is DEMONSTRABLY not coming:
    //   - it already passed this signal (everDelivered), or
    //   - its process instance is gone, or
    //   - it is itself parked on signals and none of them is this one, which is the
    //     divergent-path case the budget was written for and still covers.
    // A partner that is alive and still working - a human task, a long service call -
    // is a partner that is still coming, and this side keeps waiting for it.
    private boolean partnerNotComing(String waitKey, String partnerInstanceId, String signalName) {
        if (everDelivered.contains(partnerInstanceId + "|" + signalName)) {
            partnerArrivalTicks.remove(waitKey);
            return true;
        }
        ProcessInstance partner = runtimeService.createProcessInstanceQuery()
                .processInstanceId(partnerInstanceId)
                .singleResult();
        if (partner == null) {
            partnerArrivalTicks.remove(waitKey);
            return true;
        }
        List<EventSubscription> partnerSignals = runtimeService.createEventSubscriptionQuery()
                .processInstanceId(partnerInstanceId)
                .eventType("signal")
                .list();
        boolean partnerParkedElsewhere = !partnerSignals.isEmpty()
                && partnerSignals.stream().noneMatch(s -> s.getEventName().equals(signalName));
        if (!partnerParkedElsewhere) {
            // Still working towards this rendezvous. Reset rather than accumulate, so a
            // partner that later diverges still gets a full budget from that point.
            partnerArrivalTicks.remove(waitKey);
            return false;
        }
        int ticks = partnerArrivalTicks.merge(waitKey, 1, Integer::sum);
        if (ticks >= MAX_PARTNER_ARRIVAL_TICKS) {
            partnerArrivalTicks.remove(waitKey);
            return true;
        }
        return false;
    }

    // True once the responder moved past the exact subscription that released its
    // gated task: it may subscribe to another signal, return to this signal through
    // a loop (with a new subscription id), or complete entirely.
    private boolean responderHasAdvancedPast(String signalName, String responderInstanceId,
            String releasedSubscriptionId) {
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
        boolean stillOnReleasedSubscription = responderSignals.stream()
                .anyMatch(s -> s.getEventName().equals(signalName)
                        && s.getId().equals(releasedSubscriptionId));
        if (stillOnReleasedSubscription) {
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
