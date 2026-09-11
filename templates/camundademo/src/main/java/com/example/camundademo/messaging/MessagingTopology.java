package com.example.camundademo.messaging;

// RabbitMQ exchanges, routing keys, and queue names for the Target Platform topology.
public final class MessagingTopology {

    private MessagingTopology() {
    }

    // Flow A: Manufacturing and machines messaging constants.
    public static final String MACHINES_EXCHANGE = "machines.exchange";
    public static final String MACHINES_REQUEST_KEY = "machines.request";
    public static final String MACHINES_REQUEST_QUEUE = "machines.requests";
    public static final String MACHINES_COMPLETION_KEY = "machines.completion";
    public static final String MACHINES_COMPLETION_QUEUE = "machines.completions.manuf";

    // Flow B: Manufacturing and twin messaging constants.
    public static final String TWIN_EXCHANGE = "twin.exchange";
    public static final String TWIN_STAGE_UPDATE_KEY = "twin.stage.update";
    public static final String TWIN_STAGE_UPDATE_QUEUE = "twin.stage.updates";
    public static final String TWIN_STAGE_RESPONSE_KEY = "twin.stage.response";
    public static final String TWIN_STAGE_RESPONSE_QUEUE = "twin.stage.responses.manuf";

    // Flow C: Twin and gateway messaging constants.
    public static final String GATEWAY_EXCHANGE = "gateway.exchange";
    public static final String GATEWAY_QC_REQUEST_KEY = "gateway.qc.request";
    public static final String GATEWAY_QC_REQUEST_QUEUE = "gateway.qc.requests";
    public static final String GATEWAY_QC_RESPONSE_KEY = "gateway.qc.response";
    public static final String GATEWAY_QC_RESPONSE_QUEUE = "gateway.qc.responses.twin";

    // Camunda message name used to resume a process waiting for the Twin result.
    public static final String TWIN_STAGE_RESPONSE_BPMN_MESSAGE = "TwinStageResponse";

    public static final String TWIN_QC_RESULT_VARIABLE = "twinQcResult";

    // Component identifiers for message routing.
    public static final String COMPONENT_MANUFACTURING = "manufacturing";
    public static final String COMPONENT_TWIN = "twin";
    public static final String COMPONENT_GATEWAY = "gateway";
    public static final String COMPONENT_MACHINES = "machines";
}
