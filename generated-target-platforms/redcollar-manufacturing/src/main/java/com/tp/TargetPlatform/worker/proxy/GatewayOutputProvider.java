package com.tp.TargetPlatform.worker.proxy;

import java.util.Map;

import org.camunda.bpm.engine.externaltask.LockedExternalTask;

// Pluggable legitimate-producer boundary for every generated worker whose topic precedes
// an exclusive gateway. With no implementation registered, such a worker has no legitimate
// way to produce the gateway's required output and fails explicitly rather than fabricating
// one. Register your own @Component implementing this interface with the real business
// logic that determines the outcome (e.g. an order-approval service, a quality-check
// integration); every generated worker for a gateway-guarded topic starts calling it
// instead, with no generated code to change.
public interface GatewayOutputProvider {

    // topic: the external-task topic being completed (e.g. "VerifyOrder") - lets one
    // implementation branch on which BPMN activity it is deciding for. task: the locked
    // external task itself, for id/businessKey/variable access. Returns the process
    // variables to complete the task with; include every gateway variable this topic's
    // activity precedes if you can - the calling worker fails explicitly for whichever
    // ones are still missing rather than completing with a fabricated one.
    Map<String, Object> provide(String topic, LockedExternalTask task);
}
