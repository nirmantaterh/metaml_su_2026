package com.metaml.nodemanager.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import com.metaml.nodemanager.payload.IoDeclarationDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Configurable agent catalog keyed by agent type identifier.
@Data
@ConfigurationProperties(prefix = "nodemanager")
public class NodeManagerProperties {

    private Map<String, AgentConfig> agents = new LinkedHashMap<>();

    @Data
    public static class AgentConfig {
        private String agentName;
        private String description;
        private List<String> capabilities = new ArrayList<>();
        // Typed execution outputs returned by the agent, preserving native YAML scalar types.
        private Map<String, Object> outputs = new LinkedHashMap<>();
        // Truthful process-variable I/O contract the provider operator authors alongside the rest
        // of this catalog entry (MetaML Scope 6, Provider Contract Authorship phase). Flat sibling
        // fields (required-inputs / produced-outputs), never a nested "contract:" object - see the
        // design lock. Missing configuration binds to an empty list, same as an explicitly empty
        // YAML list, so an existing provider without these fields keeps working unchanged.
        private List<IoDeclarationDescriptor> requiredInputs = new ArrayList<>();
        private List<IoDeclarationDescriptor> producedOutputs = new ArrayList<>();
    }
}
