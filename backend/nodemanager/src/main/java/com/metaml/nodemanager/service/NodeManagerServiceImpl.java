package com.metaml.nodemanager.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.metaml.nodemanager.config.NodeManagerProperties;
import com.metaml.nodemanager.config.NodeManagerProperties.AgentConfig;
import com.metaml.nodemanager.payload.AgentAvailabilityResponse;
import com.metaml.nodemanager.payload.IoDeclarationDescriptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class NodeManagerServiceImpl implements NodeManagerService {

    private static final Logger logger = LoggerFactory.getLogger(NodeManagerServiceImpl.class);

    // Same rule enforced by the workbench's own IoDeclaration/BpmnCapabilityContractReader/
    // CapabilityProviderCatalogReader: names land as Camunda process variables, so plain camelCase
    // only (MetaML Scope 6, Provider Contract Authorship phase, section 6).
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9]*$");

    // Canonical IoType names only - no aliases, exact match after trimming (section 3).
    private static final Set<String> CANONICAL_TYPES =
            Set.of("BOOLEAN", "NUMBER", "STRING", "STRUCT", "UNKNOWN");

    private final NodeManagerProperties properties;

    public NodeManagerServiceImpl(NodeManagerProperties properties) {
        this.properties = properties;
    }

    @Override
    public AgentAvailabilityResponse checkAvailability(String agentType) {
        AgentConfig agent = properties.getAgents().get(agentType);
        if (agent == null || agent.getAgentName() == null) {
            return unavailable(agentType, "Agent type not found in node manager catalog");
        }
        if (!hasValidContract(agent)) {
            // Malformed provider configuration degrades to unavailable rather than an uncaught
            // raw server failure (section 6).
            logger.warn("Agent type '{}' has a malformed provider I/O contract; reporting unavailable",
                    agentType);
            return unavailable(agentType, "Agent configuration is malformed and cannot be published");
        }
        return new AgentAvailabilityResponse(agentType, true, agent.getAgentName(),
                "Agent registered in node manager catalog",
                agent.getOutputs() == null ? Map.of() : agent.getOutputs(),
                agent.getDescription() != null ? agent.getDescription() : "Registered agent component",
                agent.getCapabilities() != null ? agent.getCapabilities() : List.of(),
                canonicalized(agent.getRequiredInputs()),
                canonicalized(agent.getProducedOutputs()));
    }

    @Override
    public List<AgentAvailabilityResponse> listAvailableAgents() {
        List<AgentAvailabilityResponse> list = new ArrayList<>();
        for (Map.Entry<String, AgentConfig> entry : properties.getAgents().entrySet()) {
            String type = entry.getKey();
            AgentConfig config = entry.getValue();
            if (config == null || config.getAgentName() == null) {
                continue;
            }
            if (!hasValidContract(config)) {
                // One malformed catalog entry must not take down the whole list endpoint (section
                // 6) - skip it and keep every valid entry.
                logger.warn("Skipping agent type '{}' from the catalog listing: malformed provider "
                        + "I/O contract", type);
                continue;
            }
            list.add(new AgentAvailabilityResponse(
                    type,
                    true,
                    config.getAgentName(),
                    "Agent registered in node manager catalog",
                    config.getOutputs() == null ? Map.of() : config.getOutputs(),
                    config.getDescription() != null ? config.getDescription() : "Registered agent component",
                    config.getCapabilities() != null ? config.getCapabilities() : List.of(),
                    canonicalized(config.getRequiredInputs()),
                    canonicalized(config.getProducedOutputs())
            ));
        }
        return list;
    }

    private static AgentAvailabilityResponse unavailable(String agentType, String reason) {
        return new AgentAvailabilityResponse(agentType, false, null, reason, Map.of(), null, List.of(),
                List.of(), List.of());
    }

    // Syntactic validation only: declaration name syntax, canonical type, and duplicate names
    // within each list (section 6). This never converts type to IoType - that conversion is the
    // workbench CapabilityProviderCatalogReader's job (locked architecture, section 2).
    private static boolean hasValidContract(AgentConfig config) {
        return isValidDeclarationList(config.getRequiredInputs())
                && isValidDeclarationList(config.getProducedOutputs());
    }

    private static boolean isValidDeclarationList(List<IoDeclarationDescriptor> declarations) {
        if (declarations == null || declarations.isEmpty()) {
            return true;
        }
        Set<String> seenNames = new HashSet<>();
        for (IoDeclarationDescriptor declaration : declarations) {
            if (declaration == null) {
                return false;
            }
            String name = trimmedOrNull(declaration.getName());
            if (name == null || !SAFE_NAME.matcher(name).matches()) {
                return false;
            }
            if (!seenNames.add(name)) {
                // Duplicate declaration name within the same list is rejected outright.
                return false;
            }
            String type = trimmedOrNull(declaration.getType());
            if (type != null && !CANONICAL_TYPES.contains(type)) {
                // Invalid type - not omitted (which means UNKNOWN) and not one of the canonical
                // enum names.
                return false;
            }
        }
        return true;
    }

    // Trims name/type and canonicalizes null -> empty list, preserving whatever the operator wrote
    // otherwise. hasValidContract has already established every entry here is well-formed.
    private static List<IoDeclarationDescriptor> canonicalized(List<IoDeclarationDescriptor> declarations) {
        if (declarations == null || declarations.isEmpty()) {
            return List.of();
        }
        List<IoDeclarationDescriptor> result = new ArrayList<>(declarations.size());
        for (IoDeclarationDescriptor declaration : declarations) {
            result.add(new IoDeclarationDescriptor(
                    trimmedOrNull(declaration.getName()),
                    trimmedOrNull(declaration.getType()),
                    declaration.isRequired()));
        }
        return List.copyOf(result);
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
