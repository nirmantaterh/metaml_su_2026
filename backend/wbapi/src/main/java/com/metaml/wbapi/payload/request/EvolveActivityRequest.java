package com.metaml.wbapi.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvolveActivityRequest {
    private String twinProcessId;
    private String activityId;
    private String agentType;
    // Optional (Scope 6 identity fix): the exact runtime sibling to target when the caller has
    // already disambiguated a parallel multi-instance activity's concurrent instances (see
    // WorkbenchController#evolveActivity). Null/absent preserves the existing 3-argument
    // WorkbenchService#evolveActivity behavior unchanged.
    private String activityInstanceId;
}
