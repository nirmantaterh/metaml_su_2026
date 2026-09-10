package com.metaml.workbench.capability.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.service.WorkbenchService;

import java.util.List;
import java.util.Optional;

// Production binding of CapabilityOutputContractSource against the authoritative node manager
// catalog, already exposed as WorkbenchService.listCapabilityProviders() (MetaML Scope 6, Phase 4).
//
// This is the one implementation of CapabilityOutputContractSource registered as a bean: no other
// catalog, no other governance path, and no store of its own - every call reads the same catalog
// CapabilitySatisfaction and the wbapi "Transmute Agents" endpoint already read.
//
// TwinAutomationDelegate.resolveProvider calls providerFor twice per execution: once with the agent
// name (instance identity), once with the agent type (family identity) if the first found nothing.
// So a single identity string here is matched against providerId first and providerType second,
// exactly mirroring how DefaultProjectAutomationService itself resolves a ComponentExecutor.
@Component
public class CatalogCapabilityOutputContractSource implements CapabilityOutputContractSource {

    private static final Logger logger = LoggerFactory.getLogger(CatalogCapabilityOutputContractSource.class);

    private final WorkbenchService workbenchService;

    public CatalogCapabilityOutputContractSource(WorkbenchService workbenchService) {
        this.workbenchService = workbenchService;
    }

    @Override
    public Optional<CapabilityProvider> providerFor(String providerIdentity) {
        if (providerIdentity == null || providerIdentity.isBlank()) {
            return Optional.empty();
        }

        List<CapabilityProvider> providers = catalog();

        for (CapabilityProvider provider : providers) {
            if (provider.providerId().equals(providerIdentity)) {
                return Optional.of(provider);
            }
        }
        for (CapabilityProvider provider : providers) {
            if (provider.providerType().equals(providerIdentity)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    // The node manager catalog is an external dependency the same as anywhere else it is read (see
    // NodeManagerClient, CapabilityProviderCatalogReader): a transient failure to reach it is not a
    // contract violation, it is the absence of a resolvable contract for this execution - the same
    // "no declared contract" case CapabilityOutputContractSource already documents, and one Phase 5
    // (capability gaps), not Phase 4, owns. So a failure here is logged and swallowed rather than
    // allowed to fail every twin automation execution that happens to run while the catalog is
    // unreachable.
    private List<CapabilityProvider> catalog() {
        try {
            return workbenchService.listCapabilityProviders();
        } catch (RuntimeException e) {
            logger.warn("Could not resolve the capability provider catalog for output contract "
                    + "validation; treating this execution as having no resolvable contract: {}",
                    e.getMessage());
            return List.of();
        }
    }
}
