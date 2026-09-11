package com.metaml.nodemanager.payload;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class AgentAvailabilityResponse {
    private String agentType;
    private boolean available;
    private String agentName;
    private String reason;
    // Execution outputs and verdict variables produced by the agent.
    private Map<String, Object> outputs;
    private String description;
    private List<String> capabilities;
    // Truthful process-variable I/O contract published by the provider operator (MetaML Scope 6,
    // Provider Contract Authorship phase). type stays a plain String on the wire - see
    // IoDeclarationDescriptor. Missing and explicitly empty configuration both canonicalize to an
    // empty list here, never null (see NodeManagerServiceImpl).
    private List<IoDeclarationDescriptor> requiredInputs;
    private List<IoDeclarationDescriptor> producedOutputs;

    public AgentAvailabilityResponse(String agentType, boolean available, String agentName, String reason,
            Map<String, Object> outputs, String description, List<String> capabilities,
            List<IoDeclarationDescriptor> requiredInputs, List<IoDeclarationDescriptor> producedOutputs) {
        this.agentType = agentType;
        this.available = available;
        this.agentName = agentName;
        this.reason = reason;
        this.outputs = outputs;
        this.description = description;
        this.capabilities = capabilities;
        this.requiredInputs = requiredInputs == null ? List.of() : requiredInputs;
        this.producedOutputs = producedOutputs == null ? List.of() : producedOutputs;
    }

    public AgentAvailabilityResponse(String agentType, boolean available, String agentName, String reason,
            Map<String, Object> outputs, String description, List<String> capabilities) {
        this(agentType, available, agentName, reason, outputs, description, capabilities, List.of(), List.of());
    }

    public AgentAvailabilityResponse(String agentType, boolean available, String agentName, String reason, Map<String, Object> outputs) {
        this(agentType, available, agentName, reason, outputs, null, List.of());
    }
}
