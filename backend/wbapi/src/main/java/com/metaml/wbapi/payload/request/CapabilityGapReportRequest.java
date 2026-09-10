package com.metaml.wbapi.payload.request;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

// Generic capability-gap report payload (MetaML Scope 6, Phase 5, sections 12 and 19). The same
// shape services every runtime origin - the workbench twin, the original process, and a generated
// Target Platform calling over HTTP - so there is exactly one reporting contract, not one per
// origin. Carries no business value: requiredInputs/requiredOutputs are name/type declarations
// only (section 7), exactly like CapabilityGap.availableInputs.
@Data
@NoArgsConstructor
public class CapabilityGapReportRequest {
    private String processDefinitionId;
    private String activityId;
    private String activityInstanceId;
    private Integer loopCounter;
    // The twin process instance id (WorkbenchService's "twinProcessId") this report's eventual
    // binding/execution will go through. Workbench remains authoritative for binding regardless of
    // where the gap was first observed (locked architecture section 19).
    private String processInstanceId;
    private String tenantId;
    // One of GapOrigin's names: STATIC_MODEL, RUNTIME_WORKBENCH_TWIN, RUNTIME_WORKBENCH_ORIGINAL,
    // RUNTIME_TARGET_PLATFORM.
    private String origin;
    private String capabilityId;
    // name -> IoType name (e.g. "riskScore" -> "NUMBER"). Never an actual value.
    private Map<String, String> requiredInputs;
    // The outputs a new provider must produce to close this gap - i.e. the activity's currently
    // unsatisfied outputs, not whatever it already declares. Never an actual value.
    private Map<String, String> requiredOutputs;
    // name -> IoType name of inputs actually available to a candidate provider right now. Never an
    // actual value (section 7).
    private Map<String, String> availableInputs;
    private List<String> governanceLabels;
}
