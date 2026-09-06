package com.metaml.workbench.governance;

import java.util.Map;

// Context a policy rule can reference. Anything beyond tenant and action (payment.amount, ...) is an
// arbitrary dotted field name, so it goes in attributes rather than becoming a named field.
public record GovernanceRequest(String tenantId, String action, Map<String, Object> attributes) {
    public GovernanceRequest {
        attributes = attributes == null ? Map.of() : attributes;
    }
}
