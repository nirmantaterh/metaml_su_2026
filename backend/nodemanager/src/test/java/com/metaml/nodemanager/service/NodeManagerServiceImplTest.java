package com.metaml.nodemanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.metaml.nodemanager.config.NodeManagerProperties;
import com.metaml.nodemanager.payload.AgentAvailabilityResponse;
import com.metaml.nodemanager.payload.IoDeclarationDescriptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// the real application.yml, not a stubbed catalog - half of what's worth checking here is that
// the yaml binds the way the workbench expects it to
@SpringBootTest
class NodeManagerServiceImplTest {

    @Autowired
    private NodeManagerService nodeManagerService;

    @Test
    void answersFromTheConfiguredCatalog() {
        AgentAvailabilityResponse validator = nodeManagerService.checkAvailability("validator");

        assertThat(validator.isAvailable()).isTrue();
        assertThat(validator.getAgentName()).isEqualTo("validator-agent-01");
        assertThat(validator.getOutputs()).isEmpty();
    }

    // validator gets its own test above since it's the one the rest of the suite leans on. These
    // three never had anything pinning their names to the yaml until now.
    @ParameterizedTest
    @CsvSource({
            "data-enricher, data-enricher-agent-01",
            "notifier,      notifier-agent-01",
            "recommender,   recommender-agent-01"
    })
    void theOtherCatalogEntriesReportNoOutputsAndTheirOwnAgentName(String agentType, String agentName) {
        AgentAvailabilityResponse response = nodeManagerService.checkAvailability(agentType);

        assertThat(response.isAvailable()).isTrue();
        assertThat(response.getAgentName()).isEqualTo(agentName);
        assertThat(response.getOutputs()).isEmpty();
    }

    @Test
    void unknownAgentTypeIsNotAvailableAndNamesNoAgent() {
        AgentAvailabilityResponse unknown = nodeManagerService.checkAvailability("no-such-agent");

        assertThat(unknown.isAvailable()).isFalse();
        assertThat(unknown.getAgentName()).isNull();
        assertThat(unknown.getReason()).contains("not found");
        assertThat(unknown.getOutputs()).isEmpty();
    }

    // a real Boolean, not the string "true". The whole generic-output mechanism rests on this:
    // the gateway on the far end asks whether the variable == true, and a String would sail
    // past that check every time without anything looking broken.
    @Test
    void outputValuesKeepTheTypeTheYamlGaveThem() {
        AgentAvailabilityResponse credit = nodeManagerService.checkAvailability("credit-risk-assessor");

        assertThat(credit.isAvailable()).isTrue();
        assertThat(credit.getAgentName()).isEqualTo("credit-risk-agent-01");
        assertThat(credit.getOutputs()).containsOnlyKeys("riskFlagged");
        assertThat(credit.getOutputs().get("riskFlagged")).isInstanceOf(Boolean.class).isEqualTo(true);
        assertThat(credit.getDescription()).isNotBlank();
        assertThat(credit.getCapabilities()).contains("risk assessment", "credit check");
    }

    @Test
    void listAvailableAgentsReturnsAllConfiguredCatalogEntriesWithMetadata() {
        java.util.List<AgentAvailabilityResponse> list = nodeManagerService.listAvailableAgents();

        assertThat(list).hasSize(5);
        assertThat(list).extracting(AgentAvailabilityResponse::getAgentType)
                .containsExactlyInAnyOrder("validator", "credit-risk-assessor", "data-enricher", "recommender", "notifier");

        AgentAvailabilityResponse credit = list.stream()
                .filter(a -> "credit-risk-assessor".equals(a.getAgentType()))
                .findFirst().orElseThrow();
        assertThat(credit.getAgentName()).isEqualTo("credit-risk-agent-01");
        assertThat(credit.getDescription()).contains("credit risk");
        assertThat(credit.getCapabilities()).contains("fraud detection", "risk flagging");
    }

    // ---- Provider contract publication (MetaML Scope 6, Provider Contract Authorship phase) ----
    //
    // Reads the same real application.yml as every test above - these lock down the truthful
    // required-inputs/produced-outputs contracts authored for the five reference providers.

    @Test
    void validatorPublishesItsRealRequiredInputs() {
        AgentAvailabilityResponse validator = nodeManagerService.checkAvailability("validator");

        assertThat(validator.getRequiredInputs()).extracting(IoDeclarationDescriptor::getName)
                .containsExactlyInAnyOrder("customerId", "payload", "forceValidationFailure");
        IoDeclarationDescriptor customerId = declarationNamed(validator.getRequiredInputs(), "customerId");
        assertThat(customerId.getType()).isEqualTo("STRING");
        assertThat(customerId.isRequired()).isFalse();
        IoDeclarationDescriptor forceFailure = declarationNamed(validator.getRequiredInputs(), "forceValidationFailure");
        assertThat(forceFailure.getType()).isEqualTo("BOOLEAN");
    }

    @Test
    void validatorPublishesItsRealProducedOutputs() {
        AgentAvailabilityResponse validator = nodeManagerService.checkAvailability("validator");

        assertThat(validator.getProducedOutputs()).extracting(IoDeclarationDescriptor::getName)
                .containsExactlyInAnyOrder("validationPassed", "schemaVersion", "validationStatus", "validationMessage");
        IoDeclarationDescriptor validationPassed = declarationNamed(validator.getProducedOutputs(), "validationPassed");
        assertThat(validationPassed.getType()).isEqualTo("BOOLEAN");
        assertThat(validationPassed.isRequired()).isTrue();
    }

    @Test
    void creditRiskAssessorPublishesTheResolvedHardeningReviewContract() {
        AgentAvailabilityResponse credit = nodeManagerService.checkAvailability("credit-risk-assessor");

        assertThat(credit.getRequiredInputs()).extracting(IoDeclarationDescriptor::getName)
                .containsExactlyInAnyOrder("transferAmount", "amount", "creditScore", "riskThreshold");
        assertThat(credit.getRequiredInputs()).allSatisfy(d -> {
            assertThat(d.getType()).isEqualTo("NUMBER");
            assertThat(d.isRequired()).isFalse();
        });

        assertThat(credit.getProducedOutputs()).extracting(IoDeclarationDescriptor::getName)
                .containsExactlyInAnyOrder("riskScore", "riskFlagged", "riskThreshold", "assessmentReason");
        assertThat(credit.getProducedOutputs()).allSatisfy(d -> assertThat(d.isRequired()).isTrue());
        assertThat(declarationNamed(credit.getProducedOutputs(), "riskFlagged").getType()).isEqualTo("BOOLEAN");
        assertThat(declarationNamed(credit.getProducedOutputs(), "assessmentReason").getType()).isEqualTo("STRING");
    }

    @Test
    void dataEnricherHasNoRequiredInputsButRealProducedOutputs() {
        AgentAvailabilityResponse enricher = nodeManagerService.checkAvailability("data-enricher");

        assertThat(enricher.getRequiredInputs()).isEmpty();
        assertThat(enricher.getProducedOutputs()).extracting(IoDeclarationDescriptor::getName)
                .containsExactlyInAnyOrder("enrichedTier", "geoRegion", "enrichmentVerified");
    }

    @Test
    void notifierAndRecommenderHaveNoRequiredInputsButRealProducedOutputs() {
        AgentAvailabilityResponse notifier = nodeManagerService.checkAvailability("notifier");
        assertThat(notifier.getRequiredInputs()).isEmpty();
        assertThat(notifier.getProducedOutputs()).extracting(IoDeclarationDescriptor::getName)
                .containsExactlyInAnyOrder("notificationDispatched", "dispatchChannel");

        AgentAvailabilityResponse recommender = nodeManagerService.checkAvailability("recommender");
        assertThat(recommender.getRequiredInputs()).isEmpty();
        assertThat(recommender.getProducedOutputs()).extracting(IoDeclarationDescriptor::getName)
                .containsExactlyInAnyOrder("recommendedAction", "confidenceScore");
    }

    // DTO serialization/deserialization roundtrip: this is exactly the shape that crosses the wire
    // to NodeManagerClient.
    @Test
    void agentAvailabilityResponseRoundTripsThroughJsonWithItsContract() throws Exception {
        AgentAvailabilityResponse original = nodeManagerService.checkAvailability("credit-risk-assessor");
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(original);
        AgentAvailabilityResponse roundTripped = mapper.readValue(json, AgentAvailabilityResponse.class);

        assertThat(roundTripped).isEqualTo(original);
        assertThat(roundTripped.getRequiredInputs()).containsExactlyInAnyOrderElementsOf(original.getRequiredInputs());
        assertThat(roundTripped.getProducedOutputs()).containsExactlyInAnyOrderElementsOf(original.getProducedOutputs());
    }

    private static IoDeclarationDescriptor declarationNamed(List<IoDeclarationDescriptor> declarations, String name) {
        return declarations.stream().filter(d -> name.equals(d.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("No declaration named '" + name + "'"));
    }

    // ---- Validation behavior (section 6) ----
    //
    // Built against hand-crafted NodeManagerProperties/NodeManagerServiceImpl instances - not the
    // Spring-bound production catalog above - so these can exercise malformed configuration
    // without corrupting the real application.yml fixture the rest of this suite depends on.
    // NodeManagerServiceImpl needs nothing but the properties object, so no Spring context is
    // required here.

    private static NodeManagerProperties.AgentConfig agentConfig(String agentName) {
        NodeManagerProperties.AgentConfig config = new NodeManagerProperties.AgentConfig();
        config.setAgentName(agentName);
        return config;
    }

    private static NodeManagerServiceImpl serviceWithAgent(String agentType, NodeManagerProperties.AgentConfig config) {
        NodeManagerProperties properties = new NodeManagerProperties();
        properties.getAgents().put(agentType, config);
        return new NodeManagerServiceImpl(properties);
    }

    @Test
    void anOmittedTypeIsAcceptedAndPassedThroughUntouched() {
        NodeManagerProperties.AgentConfig config = agentConfig("widget-inspector-01");
        config.setRequiredInputs(List.of(new IoDeclarationDescriptor("batchId", null, true)));
        NodeManagerServiceImpl service = serviceWithAgent("widget-inspector", config);

        AgentAvailabilityResponse response = service.checkAvailability("widget-inspector");

        // Node manager performs syntactic validation only and never converts type to IoType - see
        // the locked architecture (section 2). An omitted type is syntactically valid (it means
        // UNKNOWN once the workbench converts it) so the entry is published as-is.
        assertThat(response.isAvailable()).isTrue();
        assertThat(response.getRequiredInputs()).hasSize(1);
        assertThat(response.getRequiredInputs().get(0).getType()).isNull();
    }

    @Test
    void anInvalidTypeDegradesSingleLookupToUnavailableRatherThanThrowing() {
        NodeManagerProperties.AgentConfig config = agentConfig("widget-inspector-01");
        config.setProducedOutputs(List.of(new IoDeclarationDescriptor("widgetGrade", "STRINGIFIED", true)));
        NodeManagerServiceImpl service = serviceWithAgent("widget-inspector", config);

        AgentAvailabilityResponse response = service.checkAvailability("widget-inspector");

        assertThat(response.isAvailable()).isFalse();
        assertThat(response.getAgentName()).isNull();
        assertThat(response.getReason()).contains("malformed");
    }

    @Test
    void aDuplicateDeclarationNameDegradesSingleLookupToUnavailable() {
        NodeManagerProperties.AgentConfig config = agentConfig("widget-inspector-01");
        config.setRequiredInputs(List.of(
                new IoDeclarationDescriptor("batchId", "STRING", true),
                new IoDeclarationDescriptor("batchId", "NUMBER", false)));
        NodeManagerServiceImpl service = serviceWithAgent("widget-inspector", config);

        AgentAvailabilityResponse response = service.checkAvailability("widget-inspector");

        assertThat(response.isAvailable()).isFalse();
    }

    @Test
    void anUnsafeDeclarationNameDegradesSingleLookupToUnavailable() {
        NodeManagerProperties.AgentConfig config = agentConfig("widget-inspector-01");
        config.setProducedOutputs(List.of(new IoDeclarationDescriptor("widget grade", "STRING", true)));
        NodeManagerServiceImpl service = serviceWithAgent("widget-inspector", config);

        AgentAvailabilityResponse response = service.checkAvailability("widget-inspector");

        assertThat(response.isAvailable()).isFalse();
    }

    @Test
    void aMalformedEntryInTheListEndpointIsSkippedWhileValidEntriesSurvive() {
        NodeManagerProperties properties = new NodeManagerProperties();
        NodeManagerProperties.AgentConfig malformed = agentConfig("widget-inspector-01");
        malformed.setProducedOutputs(List.of(new IoDeclarationDescriptor("widgetGrade", "NOT_A_TYPE", true)));
        NodeManagerProperties.AgentConfig valid = agentConfig("widget-packer-01");
        valid.setProducedOutputs(List.of(new IoDeclarationDescriptor("packedCount", "NUMBER", true)));
        properties.getAgents().put("widget-inspector", malformed);
        properties.getAgents().put("widget-packer", valid);
        NodeManagerServiceImpl service = new NodeManagerServiceImpl(properties);

        List<AgentAvailabilityResponse> list = service.listAvailableAgents();

        assertThat(list).extracting(AgentAvailabilityResponse::getAgentType).containsExactly("widget-packer");
    }

    @Test
    void anExistingProviderWithoutTheNewFieldsRemainsUnchanged() {
        // Backward compatibility (section 16): a provider authored before this phase, with no
        // required-inputs/produced-outputs at all, keeps working and simply gets empty canonical
        // I/O declarations rather than null or a "no contract" marker.
        NodeManagerServiceImpl service = serviceWithAgent("widget-inspector", agentConfig("widget-inspector-01"));

        AgentAvailabilityResponse response = service.checkAvailability("widget-inspector");

        assertThat(response.isAvailable()).isTrue();
        assertThat(response.getRequiredInputs()).isNotNull().isEmpty();
        assertThat(response.getProducedOutputs()).isNotNull().isEmpty();
    }
}
