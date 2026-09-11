package com.metaml.workbench.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// The workbench-side mirror of the node manager's own
// com.metaml.nodemanager.payload.IoDeclarationDescriptor (MetaML Scope 6, Provider Contract
// Authorship phase). The two classes are deliberately independent - the workbench module has no
// compile-time dependency on the node manager module, the same way AgentAvailabilityResult already
// mirrors AgentAvailabilityResponse - and are wired together only by matching JSON shape over the
// node manager's REST catalog.
//
// type stays a plain String here, never the capability-domain IoType: converting it to IoType is
// CapabilityProviderCatalogReader's job alone (see its class javadoc and the Phase 2 design lock).
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IoDeclarationDescriptor {
    private String name;
    private String type;
    private boolean required;
}
