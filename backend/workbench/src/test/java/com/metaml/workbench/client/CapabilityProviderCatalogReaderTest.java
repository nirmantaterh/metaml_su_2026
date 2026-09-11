package com.metaml.workbench.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

// Focused unit tests for CapabilityProviderCatalogReader (MetaML Scope 6, Phase 2).
//
// Every fixture below invents its own agent type/name ("widget-*") independent of RedCollar's
// registered agents (credit-risk-assessor, validator, ...), proving the reader is generic catalog
// interpretation rather than a RedCollar-specific rule engine.
class CapabilityProviderCatalogReaderTest {

    // ---- Catalog representation ----

    @Test
    void catalogEntryExposesNonNullContractAndIdentity() {
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "widget-inspector", true, "widget-inspector-01",
                "Agent registered in node manager catalog", Map.of("inspectionPassed", true),
                "Widget inspection agent", List.of("inspection", "quality control"));

        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(result);

        assertThat(provider).isNotNull();
        assertThat(provider.contract()).isNotNull();
        assertThat(provider.providerId()).isEqualTo("widget-inspector-01");
        assertThat(provider.providerType()).isEqualTo("widget-inspector");
        assertThat(provider.description()).isEqualTo("Widget inspection agent");
        assertThat(provider.available()).isTrue();
        assertThat(provider.reason()).isEqualTo("Agent registered in node manager catalog");
    }

    @Test
    void missingVersionBecomesZeroZeroZero() {
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "widget-inspector", true, "widget-inspector-01", "Available", Map.of());

        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(result);

        // AgentAvailabilityResult carries no version field at all today - this exercises Phase 0's
        // own unspecified-version fallback rather than reimplementing it.
        assertThat(provider.version()).isEqualTo("0.0.0");
    }

    @Test
    void unavailableEntryWithNoAgentNameIsSkippedRatherThanFabricatingIdentity() {
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "widget-inspector", false, null, "Agent type not found in node manager catalog", Map.of());

        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(result);

        // No agent name means no instance identity to report as providerId - see class javadoc.
        assertThat(provider).isNull();
    }

    @Test
    void unavailableProviderWithAnAgentNameIsRepresentedAsUnavailable() {
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "widget-inspector", false, "widget-inspector-01", "Agent is offline for maintenance", Map.of());

        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(result);

        assertThat(provider).isNotNull();
        assertThat(provider.available()).isFalse();
        assertThat(provider.reason()).isEqualTo("Agent is offline for maintenance");
    }

    @Test
    void invalidProviderTypeIsSkippedRatherThanFailingTheWholeCatalog() {
        // Node manager agent-catalog keys are operator-configured YAML keys, not guaranteed to
        // satisfy CapabilityProvider's ^[a-z0-9-]+$ rule.
        AgentAvailabilityResult malformed = new AgentAvailabilityResult(
                "Widget_Inspector", true, "widget-inspector-01", "Available", Map.of());
        AgentAvailabilityResult valid = new AgentAvailabilityResult(
                "widget-inspector", true, "widget-inspector-01", "Available", Map.of());

        assertThat(CapabilityProviderCatalogReader.toCapabilityProvider(malformed)).isNull();
        assertThat(CapabilityProviderCatalogReader.toCapabilityProviders(List.of(malformed, valid)))
                .hasSize(1);
    }

    // ---- Provider contract ----

    @Test
    void requiredInputsAndProducedOutputsAreRepresentedAsIoDeclarationSets() {
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "widget-inspector", true, "widget-inspector-01", "Available", Map.of());

        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(result);

        // Correctly typed (Set<IoDeclaration>) and empty here because this fixture declares no
        // requiredInputs/producedOutputs at all - missing configuration canonicalizes to empty
        // (design lock section 5), it is not that the catalog can never declare genuine I/O shape.
        assertThat(provider.contract().requiredInputs()).isEmpty();
        assertThat(provider.contract().producedOutputs()).isEmpty();
    }

    @Test
    void catalogOutputsAndCapabilityLabelsAreNeverUsedAsProducedOutputs() {
        // outputs carries a static fixture value (riskFlagged: true) that gets overwritten by the
        // executor's real computation once bound; capabilities carries free-text display labels.
        // Neither is a declared output shape - see Phase 2 design lock section 9.
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "credit-risk-assessor", true, "credit-risk-agent-01", "Available",
                Map.of("riskFlagged", true), "Credit risk assessor agent",
                List.of("risk assessment", "credit check", "fraud detection"));

        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(result);

        assertThat(provider.contract().producedOutputs()).isEmpty();
        assertThat(provider.contract().requiredInputs()).isEmpty();
        assertThat(provider.contract().capabilityId()).isNull();
        assertThat(provider.contract().constraints()).isEmpty();
        assertThat(provider.contract().governanceLabels()).isEmpty();
    }

    @Test
    void contractConstructionIsDeterministic() {
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "widget-inspector", true, "widget-inspector-01", "Available",
                Map.of("inspectionPassed", true), "Widget inspection agent", List.of("inspection"));

        CapabilityProvider first = CapabilityProviderCatalogReader.toCapabilityProvider(result);
        CapabilityProvider second = CapabilityProviderCatalogReader.toCapabilityProvider(result);

        assertThat(second).isEqualTo(first);
        assertThat(second.contract()).isEqualTo(first.contract());
    }

    @Test
    void repeatedCatalogConstructionProducesEquivalentContracts() {
        List<AgentAvailabilityResult> results = List.of(
                new AgentAvailabilityResult("widget-inspector", true, "widget-inspector-01",
                        "Available", Map.of()),
                new AgentAvailabilityResult("widget-packer", true, "widget-packer-01",
                        "Available", Map.of()));

        List<CapabilityProvider> first = CapabilityProviderCatalogReader.toCapabilityProviders(results);
        List<CapabilityProvider> second = CapabilityProviderCatalogReader.toCapabilityProviders(results);

        assertThat(second).isEqualTo(first);
    }

    // ---- Declared I/O contract conversion (MetaML Scope 6, Provider Contract Authorship phase) ----

    @Test
    void requiredInputsAndProducedOutputsAreConvertedFromStringTypesToIoType() {
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "widget-inspector", true, "widget-inspector-01", "Available", Map.of(), "Widget inspector",
                List.of(),
                List.of(new IoDeclarationDescriptor("batchId", "STRING", true)),
                List.of(new IoDeclarationDescriptor("widgetGrade", "STRING", true),
                        new IoDeclarationDescriptor("inspectionPassed", "BOOLEAN", true)));

        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(result);

        assertThat(provider).isNotNull();
        assertThat(provider.contract().requiredInputs())
                .containsExactly(new IoDeclaration("batchId", IoType.STRING, true));
        assertThat(provider.contract().producedOutputs()).containsExactlyInAnyOrder(
                new IoDeclaration("widgetGrade", IoType.STRING, true),
                new IoDeclaration("inspectionPassed", IoType.BOOLEAN, true));
    }

    @Test
    void anOmittedOrBlankTypeConvertsToUnknown() {
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "widget-inspector", true, "widget-inspector-01", "Available", Map.of(), "Widget inspector",
                List.of(),
                List.of(new IoDeclarationDescriptor("batchId", null, true)),
                List.of(new IoDeclarationDescriptor("widgetGrade", "  ", true)));

        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(result);

        assertThat(provider.contract().requiredInputs())
                .containsExactly(new IoDeclaration("batchId", IoType.UNKNOWN, true));
        assertThat(provider.contract().producedOutputs())
                .containsExactly(new IoDeclaration("widgetGrade", IoType.UNKNOWN, true));
    }

    @Test
    void anInvalidTypeSkipsTheWholeMalformedProviderEntryWhileValidProvidersSurvive() {
        AgentAvailabilityResult malformed = new AgentAvailabilityResult(
                "widget-inspector", true, "widget-inspector-01", "Available", Map.of(), "Widget inspector",
                List.of(), List.of(),
                List.of(new IoDeclarationDescriptor("widgetGrade", "STRINGIFIED", true)));
        AgentAvailabilityResult valid = new AgentAvailabilityResult(
                "widget-packer", true, "widget-packer-01", "Available", Map.of(), "Widget packer",
                List.of(), List.of(),
                List.of(new IoDeclarationDescriptor("packedCount", "NUMBER", true)));

        assertThat(CapabilityProviderCatalogReader.toCapabilityProvider(malformed)).isNull();
        List<CapabilityProvider> providers =
                CapabilityProviderCatalogReader.toCapabilityProviders(List.of(malformed, valid));
        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).providerType()).isEqualTo("widget-packer");
    }

    @Test
    void aDuplicateDeclarationNameWithinOneListSkipsTheWholeProviderEntry() {
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "widget-inspector", true, "widget-inspector-01", "Available", Map.of(), "Widget inspector",
                List.of(),
                List.of(new IoDeclarationDescriptor("batchId", "STRING", true),
                        new IoDeclarationDescriptor("batchId", "NUMBER", false)),
                List.of());

        assertThat(CapabilityProviderCatalogReader.toCapabilityProvider(result)).isNull();
    }

    @Test
    void anUnsafeDeclarationNameSkipsTheWholeProviderEntry() {
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "widget-inspector", true, "widget-inspector-01", "Available", Map.of(), "Widget inspector",
                List.of(), List.of(),
                List.of(new IoDeclarationDescriptor("widget grade", "STRING", true)));

        assertThat(CapabilityProviderCatalogReader.toCapabilityProvider(result)).isNull();
    }

    @Test
    void missingRequiredInputsAndProducedOutputsCanonicalizeToEmptySets() {
        // AgentAvailabilityResult constructed via the legacy 5-arg constructor - exactly what an
        // existing provider without the new fields looks like (design lock section 5 and 16).
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "widget-inspector", true, "widget-inspector-01", "Available", Map.of());

        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(result);

        assertThat(provider.contract().requiredInputs()).isEmpty();
        assertThat(provider.contract().producedOutputs()).isEmpty();
    }

    // ---- Genericity ----

    @Test
    void catalogConversionIsGenericWithNoEnterpriseSpecificBranching() {
        // An entirely invented domain (widget manufacturing), never RedCollar's own agent types
        // (credit-risk-assessor, validator, data-enricher, recommender, notifier), converts through
        // the exact same code path with no special-casing.
        AgentAvailabilityResult result = new AgentAvailabilityResult(
                "widget-classifier", true, "widget-classifier-01", "Available",
                Map.of("widgetGrade", "A"), "Widget classification agent", List.of("classification"));

        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(result);

        assertThat(provider.providerType()).isEqualTo("widget-classifier");
        assertThat(provider.contract().requiredInputs()).isEmpty();
        assertThat(provider.contract().producedOutputs()).isEmpty();
    }

    @Test
    void nullResultsListRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> CapabilityProviderCatalogReader.toCapabilityProviders(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullEntriesInTheCatalogListAreSkipped() {
        List<AgentAvailabilityResult> results = new java.util.ArrayList<>();
        results.add(null);
        results.add(new AgentAvailabilityResult("widget-inspector", true, "widget-inspector-01",
                "Available", Map.of()));

        assertThat(CapabilityProviderCatalogReader.toCapabilityProviders(results)).hasSize(1);
    }
}
