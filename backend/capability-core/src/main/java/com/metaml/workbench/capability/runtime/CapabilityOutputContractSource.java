package com.metaml.workbench.capability.runtime;

import com.metaml.workbench.capability.CapabilityProvider;

import java.util.Optional;

// Resolves the CapabilityProvider whose declared contract governs one automation execution
// (MetaML Scope 6, Phase 4).
//
// TwinAutomationDelegate knows which provider ran - the agent name in
// evolvedAgent_<activity>[_<loop>], and the agent type in evolvedAgentType_<activity>[_<loop>] -
// but not what that provider declared it produces. This is the one seam that answers that, so
// CapabilityOutputPropagator stays a pure boundary and the delegate does not grow a catalog
// dependency of its own.
//
// An empty Optional means no declared contract was resolvable for this execution. That is not a
// silent pass: it means there is no capability contract in existence for this boundary to enforce,
// which is a capability-gap question owned by Phase 5, not a contract-violation question owned by
// Phase 4. See CapabilityOutputPropagator.publish.
//
// PRODUCTION BINDING: CatalogCapabilityOutputContractSource, in this same package, registers
// against the authoritative node manager catalog already exposed as
// WorkbenchService.listCapabilityProviders(), matching providerId against the agent name and
// falling back to providerType against the agent type, exactly as DefaultProjectAutomationService
// already resolves a ComponentExecutor.
//
// That binding is only sound because the five reference ComponentExecutor implementations return
// exactly the business outputs their authored provider contract in
// backend/nodemanager/src/main/resources/application.yml declares - execution metadata ("executor"
// plus a per-executor timestamp) was removed from producer output, not added to the authored
// contract, because none of it was ever process/business output: "executor" duplicates identity
// AutomationResult.summary() already carries, and the per-executor timestamp fields were never
// read by any BPMN gateway, data association, controller, DTO, or downstream process logic.
public interface CapabilityOutputContractSource {

    // providerIdentity is the agent name or agent type bound to the executing activity.
    Optional<CapabilityProvider> providerFor(String providerIdentity);
}
