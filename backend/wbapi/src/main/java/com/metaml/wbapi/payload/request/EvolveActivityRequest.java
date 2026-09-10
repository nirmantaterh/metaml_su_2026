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
    // Optional: the exact runtime sibling to target when disambiguating concurrent instances
    // of a parallel multi-instance activity. When null or absent, the service targets the
    // default activity instance.
    private String activityInstanceId;
}
