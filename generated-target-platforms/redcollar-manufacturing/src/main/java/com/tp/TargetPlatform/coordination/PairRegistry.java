package com.tp.TargetPlatform.coordination;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

// Pairs proxy and twin process instances by shared businessKey correlation identifier.
@Component
public class PairRegistry {

    private final ConcurrentMap<String, String> initiators = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> responders = new ConcurrentHashMap<>();

    // Classifies process instance as initiator (first) or responder (second) for the businessKey.
    public String registerAndClassify(String businessKey, String processInstanceId) {
        if (businessKey == null || businessKey.isBlank()) {
            return null;
        }
        String initiator = initiators.putIfAbsent(businessKey, processInstanceId);
        if (initiator == null || initiator.equals(processInstanceId)) {
            return "initiator";
        }
        String responder = responders.putIfAbsent(businessKey, processInstanceId);
        if (responder == null || responder.equals(processInstanceId)) {
            return "responder";
        }
        return null;
    }

    // The other half of the pair for this business key, or null if unpaired.
    public String partnerOf(String businessKey, String processInstanceId) {
        if (businessKey == null || businessKey.isBlank()) {
            return null;
        }
        String initiator = initiators.get(businessKey);
        String responder = responders.get(businessKey);
        if (processInstanceId.equals(initiator)) {
            return responder;
        }
        if (processInstanceId.equals(responder)) {
            return initiator;
        }
        return null;
    }

    public String roleOf(String businessKey, String processInstanceId) {
        if (businessKey == null || businessKey.isBlank()) {
            return null;
        }
        if (processInstanceId.equals(initiators.get(businessKey))) {
            return "initiator";
        }
        if (processInstanceId.equals(responders.get(businessKey))) {
            return "responder";
        }
        return null;
    }
}
