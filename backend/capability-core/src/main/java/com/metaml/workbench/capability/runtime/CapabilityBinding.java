package com.metaml.workbench.capability.runtime;

import java.time.Instant;
import java.util.Objects;

import com.metaml.workbench.capability.CapabilityContract;

// The Workbench-authoritative decision that one provider satisfies one activity of one model,
// independent of any specific running process instance (P7 Step 5).
//
// This is deliberately NOT the same thing as the per-visit evolvedAgent_*/evolvedAgentType_* process
// variables CapabilityDispatcher already reads: those live on one specific Camunda process instance
// (the Workbench's own Twin instance, or - once resolved through this type - a generated Target
// Platform's own instance) and are the actual runtime binding mechanism. CapabilityBinding is the
// model-level record of what the Workbench approved, portable across engines because it is keyed by
// the BPMN process key and activity id rather than by any engine's internal instance/definition id.
//
// Deliberately scoped: this type represents one current provider per (processDefinitionKey,
// activityId). It does NOT represent activity-instance or loop/multi-instance-specific bindings - a
// standalone generated Target Platform shares no processInstanceId/activityInstanceId space with the
// Workbench's own Twin engine, so there is no safe, non-fabricating way to carry an instance-specific
// decision into a model-level record. Loop-scoped evolutions stay purely Workbench-instance-scoped
// state, exactly as they already are.
//
// contract is the provider's declared CapabilityContract as it existed AT BIND TIME, not a live
// pointer into the current catalog - a generated Target Platform must keep executing against what was
// actually approved even if the catalog changes later, never silently absorb an unapproved change.
public record CapabilityBinding(String processDefinitionKey, String activityId, String providerId,
        String providerType, String version, CapabilityContract contract, String approvalId, Instant boundAt) {

    public CapabilityBinding {
        Objects.requireNonNull(processDefinitionKey, "processDefinitionKey must not be null");
        if (processDefinitionKey.isBlank()) {
            throw new IllegalArgumentException("processDefinitionKey must not be blank");
        }
        Objects.requireNonNull(activityId, "activityId must not be null");
        if (activityId.isBlank()) {
            throw new IllegalArgumentException("activityId must not be blank");
        }
        Objects.requireNonNull(providerId, "providerId must not be null");
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        Objects.requireNonNull(providerType, "providerType must not be null");
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(boundAt, "boundAt must not be null");
    }

    // The deterministic key this binding is addressed by, everywhere: the durable Workbench store,
    // the wbapi lookup, and the Target Platform's own cache all use exactly this.
    public static String key(String processDefinitionKey, String activityId) {
        return processDefinitionKey + "::" + activityId;
    }

    public String key() {
        return key(processDefinitionKey, activityId);
    }
}
