package com.metaml.wbapi.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

// Fields are nullable to support partial policy updates.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGovernancePolicyRequest {
    private Set<String> deniedAgentTypes;
    private Integer maxEvolutionsPerTwin;
    private Integer maxTwinExecutionsPerTwin;
}
