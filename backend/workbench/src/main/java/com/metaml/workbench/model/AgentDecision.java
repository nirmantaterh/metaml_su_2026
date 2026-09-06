package com.metaml.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentDecision {
    private String agentType;
    private boolean approved;
    private String agentName;
    private String reason;
    private boolean riskFlagged;
    // Null for every refusal unrelated to tenant policy - quota exceeded, node manager unavailable,
    // activity not connected. Set to DENY or REQUIRE_APPROVAL only when the PolicyDecisionEngine is the
    // actual reason, so a caller can tell governance apart from everything else.
    private String governanceDecision;

    // most of the paths that build a decision are refusals - no agent ran, so nothing flagged anything. Saves repeating a bare false/null at the many call sites that don't care about either.
    public AgentDecision(String agentType, boolean approved, String agentName, String reason) {
        this(agentType, approved, agentName, reason, false, null);
    }
}
