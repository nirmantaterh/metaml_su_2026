package com.metaml.workbench.client;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Pure, deterministic AgentAvailabilityResult -> CapabilityProvider derivation (MetaML Scope 6,
// Phase 2).
//
// This is the provider-side counterpart to Phase 1's BpmnCapabilityContractReader: where that class
// reads a BPMN activity into the CapabilityContract a provider would need to satisfy, this class
// reads the node manager's agent catalog (the same data already served through
// WorkbenchService.listAvailableAgents() / the wbapi "Transmute Agents" endpoint) into the
// CapabilityProvider records the Phase 0 CapabilitySatisfaction mechanism evaluates against.
//
// AgentAvailabilityResult carries two kinds of metadata that were never a declared I/O shape and
// still are not read here as one:
// - outputs is a map of static, single canned values configured per agent type (e.g.
//   nodemanager.agents.credit-risk-assessor.outputs.riskFlagged: true) that is written verbatim as
//   a twin process variable the moment an agent is bound (see WorkbenchServiceImpl.writeAgentOutputs)
//   and is then overwritten by whatever ComponentExecutor.execute() actually computes. It is a
//   default/fixture value, not a declared output shape, so it is never read here - see the Phase 2
//   design lock section 9 ("never derive provider capability from one execution", "never add
//   defaultValue/fallbackProvider/sampleBusinessValue to the capability model").
// - capabilities is a free-text, human-readable label list ("credit check", "fraud detection") for
//   display purposes, not process-variable names, so it cannot become IoDeclaration data without
//   inventing structure that was never actually declared.
//
// requiredInputs and producedOutputs (MetaML Scope 6, Provider Contract Authorship phase) are the
// genuine declared I/O shape: the provider operator authors them as flat name/type/required
// entries in the node manager's own YAML (NodeManagerProperties.AgentConfig), and they arrive here
// as AgentAvailabilityResult.requiredInputs/producedOutputs with type still a plain String (see
// IoDeclarationDescriptor). Converting that String into the workbench's capability-domain IoType -
// and rejecting a catalog entry outright when a name is unsafe, a type is unparseable, or a list
// carries a duplicate name - happens only in toIoDeclarations below; nothing here fabricates a
// name, a type, or a business value that the catalog entry did not actually declare. A provider
// that omits the fields (or declares them empty) still gets an honestly empty
// Set.of()/Set.of() contract, exactly as before this phase.
public final class CapabilityProviderCatalogReader {

    private static final Logger logger = LoggerFactory.getLogger(CapabilityProviderCatalogReader.class);

    private CapabilityProviderCatalogReader() {
    }

    // Converts every entry with enough identity to be represented; entries that cannot be
    // represented without fabricating identity (see toCapabilityProvider) are skipped rather than
    // failing the whole catalog, mirroring NodeManagerClient's own "drop bad entries rather than
    // failing the whole operation" convention.
    public static List<CapabilityProvider> toCapabilityProviders(List<AgentAvailabilityResult> results) {
        Objects.requireNonNull(results, "results must not be null");
        List<CapabilityProvider> providers = new ArrayList<>();
        for (AgentAvailabilityResult result : results) {
            if (result == null) {
                continue;
            }
            CapabilityProvider provider = toCapabilityProvider(result);
            if (provider != null) {
                providers.add(provider);
            }
        }
        return List.copyOf(providers);
    }

    // Returns null (rather than throwing) when the source entry cannot be represented as a
    // CapabilityProvider without fabricating identity: providerId is instance identity (today's
    // agentName) and providerType must match CapabilityProvider's own ^[a-z0-9-]+$ rule. Both are
    // Phase 0 constraints already enforced by the CapabilityProvider constructor; this method simply
    // declines to invent a value to satisfy them.
    public static CapabilityProvider toCapabilityProvider(AgentAvailabilityResult result) {
        Objects.requireNonNull(result, "result must not be null");

        String providerId = trimmedOrNull(result.getAgentName());
        if (providerId == null) {
            logger.warn("Skipping node manager catalog entry with agent type '{}': no agent name to use as "
                    + "provider identity", result.getAgentType());
            return null;
        }
        String providerType = trimmedOrNull(result.getAgentType());
        if (providerType == null) {
            logger.warn("Skipping node manager catalog entry for agent '{}': no agent type to use as "
                    + "provider type", providerId);
            return null;
        }

        try {
            Set<IoDeclaration> requiredInputs = toIoDeclarations(result.getRequiredInputs());
            Set<IoDeclaration> producedOutputs = toIoDeclarations(result.getProducedOutputs());

            CapabilityContract contract = new CapabilityContract(
                    null,
                    requiredInputs,
                    producedOutputs,
                    // Every provider reachable through today's evolve -> bind -> automate path executes
                    // inline via DefaultProjectAutomationService/ComponentExecutor.execute(); there is no
                    // provider that completes asynchronously via a separate completion signal. Execution
                    // mode for an *activity* (sync/async external task) is a Phase 1 concern derived from
                    // the BPMN model itself, not from the provider.
                    ExecutionMode.SYNCHRONOUS,
                    Map.of(),
                    Set.of());

            return new CapabilityProvider(providerId, providerType, null, contract,
                    result.getDescription(), result.isAvailable(), result.getReason());
        } catch (IllegalArgumentException e) {
            // providerType failed CapabilityProvider's ^[a-z0-9-]+$ rule (e.g. a misconfigured
            // nodemanager.agents key), or a declared input/output failed IoDeclaration's own name
            // rule, carried an unparseable type, or collided on name with a sibling declaration in
            // the same list (see toIoDeclarations). Drop the whole entry rather than let one bad
            // catalog entry break the whole catalog read, or silently publish a corrupted contract.
            logger.warn("Skipping node manager catalog entry for agent '{}': {}", providerId, e.getMessage());
            return null;
        }
    }

    // Converts a node manager catalog entry's String-typed declaration list into the workbench's
    // capability-domain Set<IoDeclaration> - the one place this conversion happens (locked
    // architecture, section 2). Missing or explicitly empty input canonicalizes to Set.of(), never
    // null (design lock section 5). Throws IllegalArgumentException - caught by the caller's
    // existing malformed-entry handling - when any declaration cannot be represented faithfully:
    // an unsafe name (IoDeclaration's own rule), an unparseable type (exact canonical IoType name
    // only, never an alias - design lock section 3), or a duplicate name within the same list
    // (section 6), since a Set<IoDeclaration> would otherwise silently admit two declarations that
    // share a name but differ in type or required.
    private static Set<IoDeclaration> toIoDeclarations(List<IoDeclarationDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return Set.of();
        }
        Set<IoDeclaration> declarations = new LinkedHashSet<>();
        Set<String> seenNames = new HashSet<>();
        for (IoDeclarationDescriptor descriptor : descriptors) {
            if (descriptor == null) {
                throw new IllegalArgumentException("declaration entry must not be null");
            }
            String name = trimmedOrNull(descriptor.getName());
            if (name == null) {
                throw new IllegalArgumentException("declaration name must not be blank");
            }
            if (!seenNames.add(name)) {
                throw new IllegalArgumentException("duplicate declaration name '" + name + "'");
            }
            declarations.add(new IoDeclaration(name, parseType(descriptor.getType()), descriptor.isRequired()));
        }
        return Set.copyOf(declarations);
    }

    // Omitted/blank type -> UNKNOWN (never a default business value - see IoType javadoc). A
    // present type is parsed as the exact canonical IoType enum name only; anything else throws
    // IllegalArgumentException rather than being silently coerced or aliased.
    private static IoType parseType(String rawType) {
        String trimmed = trimmedOrNull(rawType);
        return trimmed == null ? IoType.UNKNOWN : IoType.valueOf(trimmed);
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
