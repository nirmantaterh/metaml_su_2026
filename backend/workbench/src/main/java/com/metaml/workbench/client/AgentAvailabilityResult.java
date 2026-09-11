package com.metaml.workbench.client;

import lombok.Data;
import lombok.NoArgsConstructor;

import com.metaml.workbench.model.AgentVariables;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class AgentAvailabilityResult {
    private String agentType;
    private boolean available;
    private String agentName;
    private String reason;
    private Map<String, Object> outputs;
    private String description;
    private List<String> capabilities;
    // Truthful process-variable I/O contract published by the provider operator, deserialized from
    // the node manager's own AgentAvailabilityResponse (MetaML Scope 6, Provider Contract
    // Authorship phase). type stays a plain String here - see IoDeclarationDescriptor;
    // CapabilityProviderCatalogReader is the only place that converts it to IoType. Missing and
    // explicitly empty configuration both canonicalize to an empty list here, never null.
    private List<IoDeclarationDescriptor> requiredInputs;
    private List<IoDeclarationDescriptor> producedOutputs;

    public AgentAvailabilityResult(String agentType, boolean available, String agentName, String reason,
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

    public AgentAvailabilityResult(String agentType, boolean available, String agentName,
            String reason, Map<String, Object> outputs, String description, List<String> capabilities) {
        this(agentType, available, agentName, reason, outputs, description, capabilities, List.of(), List.of());
    }

    public AgentAvailabilityResult(String agentType, boolean available, String agentName,
            String reason, Map<String, Object> outputs) {
        this(agentType, available, agentName, reason, outputs, null, List.of());
    }

    // the shape this had back when a raised risk flag was the only thing an agent could say. Plenty of callers still only care about that one, and the stubbed catalogs in the tests are written against it.
    public AgentAvailabilityResult(String agentType, boolean available, String agentName,
            String reason, boolean riskFlagged) {
        this(agentType, available, agentName, reason,
                riskFlagged ? Map.of(AgentVariables.RISK_FLAGGED_OUTPUT, true) : Map.of());
    }

    // derived, not stored - two places holding the same fact is how they end up disagreeing
    public boolean isRiskFlagged() {
        return outputs != null && Boolean.TRUE.equals(outputs.get(AgentVariables.RISK_FLAGGED_OUTPUT));
    }
}
