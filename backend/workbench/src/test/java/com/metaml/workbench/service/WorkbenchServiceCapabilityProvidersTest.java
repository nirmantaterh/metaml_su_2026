package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.client.AgentAvailabilityResult;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.List;
import java.util.Map;

// Verifies the production catalog path end to end (MetaML Scope 6, Phase 2):
// WorkbenchService.listAvailableAgents() (the node manager catalog already exposed through the
// wbapi "Transmute Agents" endpoint) is what listCapabilityProviders() generalizes into
// CapabilityProvider records, via its default method rather than a reimplementation. Using
// CALLS_REAL_METHODS on the interface mock exercises that default method exactly as
// WorkbenchServiceImpl (its sole implementer) inherits it.
class WorkbenchServiceCapabilityProvidersTest {

    @Test
    void listCapabilityProvidersGeneralizesTheAuthoritativeAgentCatalog() {
        WorkbenchService service = mock(WorkbenchService.class, Answers.CALLS_REAL_METHODS);
        AgentAvailabilityResult credit = new AgentAvailabilityResult(
                "credit-risk-assessor", true, "credit-risk-agent-01", "Available",
                Map.of("riskFlagged", true), "Credit risk assessor agent",
                List.of("risk assessment", "credit check"));
        given(service.listAvailableAgents()).willReturn(List.of(credit));

        List<CapabilityProvider> providers = service.listCapabilityProviders();

        assertThat(providers).hasSize(1);
        CapabilityProvider provider = providers.get(0);
        assertThat(provider.providerId()).isEqualTo("credit-risk-agent-01");
        assertThat(provider.providerType()).isEqualTo("credit-risk-assessor");
        assertThat(provider.version()).isEqualTo("0.0.0");
        assertThat(provider.available()).isTrue();
        assertThat(provider.contract()).isNotNull();
    }

    @Test
    void listCapabilityProvidersReflectsAnEmptyCatalog() {
        WorkbenchService service = mock(WorkbenchService.class, Answers.CALLS_REAL_METHODS);
        given(service.listAvailableAgents()).willReturn(List.of());

        assertThat(service.listCapabilityProviders()).isEmpty();
    }
}
