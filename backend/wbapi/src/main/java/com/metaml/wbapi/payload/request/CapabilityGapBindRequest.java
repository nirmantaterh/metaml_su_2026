package com.metaml.wbapi.payload.request;

import lombok.Data;
import lombok.NoArgsConstructor;

// MetaML Scope 6, Phase 6: optional body for POST .../capability-gaps/{gapId}/bind. When
// confirmedProviderId is present, it carries an already AI-assisted, human-confirmed providerId
// (from the existing VS Code AiDecisionProvider/Ollama path, section 4) into the existing bind
// lifecycle step. CapabilityGapService independently re-validates it against the live catalog and
// CapabilitySatisfaction before ever binding (section 7) - this DTO carries identity only, never a
// business value. Omitting the body (or confirmedProviderId) falls back to the existing
// deterministic CapabilityGapRecommender-driven bind, unchanged.
@Data
@NoArgsConstructor
public class CapabilityGapBindRequest {
    private String confirmedProviderId;
}
