package com.example.camundademo.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// Message envelope exchanged between Manufacturing, Twin, Gateway, and Machine services.
public class HarnessMessage {

    public enum Type {
        MACHINE_REQUEST,
        MACHINE_COMPLETION,
        STAGE_UPDATE_REQUEST,
        STAGE_UPDATE_RESPONSE,
        QC_REQUEST,
        QC_RESPONSE
    }

    // Unique identifier for this message.
    private String messageId;
    // Correlation identifier preserved across request-response exchanges.
    private String correlationId;
    private Type type;
    private String source;
    private String destination;
    // Target Camunda process instance identifier.
    private String processInstanceId;
    // BPMN activity identifier.
    private String activityId;
    // Response status or outcome (e.g. PASS).
    private String status;
    private Map<String, Object> payload = new LinkedHashMap<>();
    // UTC ISO-8601 timestamp.
    private String timestamp;

    public HarnessMessage() {
    }

    public static HarnessMessage request(Type type, String source, String destination,
            String processInstanceId, String activityId) {
        HarnessMessage message = new HarnessMessage();
        message.messageId = UUID.randomUUID().toString();
        message.correlationId = UUID.randomUUID().toString();
        message.type = type;
        message.source = source;
        message.destination = destination;
        message.processInstanceId = processInstanceId;
        message.activityId = activityId;
        message.timestamp = Instant.now().toString();
        return message;
    }

    public HarnessMessage reply(Type replyType, String replySource, String replyDestination, String replyStatus) {
        HarnessMessage message = new HarnessMessage();
        message.messageId = UUID.randomUUID().toString();
        message.correlationId = this.correlationId;
        message.type = replyType;
        message.source = replySource;
        message.destination = replyDestination;
        message.processInstanceId = this.processInstanceId;
        message.activityId = this.activityId;
        message.status = replyStatus;
        message.timestamp = Instant.now().toString();
        return message;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload == null ? new LinkedHashMap<>() : payload;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "HarnessMessage{" + type + " " + source + "->" + destination
                + " activity=" + activityId + " correlation=" + correlationId
                + (status == null ? "" : " status=" + status) + "}";
    }
}
