package com.metaml.nodemanager.payload;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// A single named provider input or output as published by the node manager (MetaML Scope 6,
// Provider Contract Authorship phase).
//
// type is kept as a plain String here, never as the workbench's capability-domain IoType: the node
// manager configuration/transport representation of type is locked to String (see
// NodeManagerProperties.AgentConfig, which binds this same shape straight out of
// application.yml's required-inputs/produced-outputs lists), and the conversion into IoType happens
// only in the workbench's CapabilityProviderCatalogReader. This class is also used, unmodified, as
// the REST payload shape in AgentAvailabilityResponse, so a provider's authored contract flows from
// YAML to the wire without an intermediate re-mapping step.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IoDeclarationDescriptor {
    private String name;
    private String type;
    private boolean required;
}
