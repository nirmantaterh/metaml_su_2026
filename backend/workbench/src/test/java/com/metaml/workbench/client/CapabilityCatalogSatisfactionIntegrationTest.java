package com.metaml.workbench.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.metaml.workbench.bpmn.BpmnCapabilityContractReader;
import com.metaml.workbench.bpmn.BpmnCapabilityContractReader.ActivityCapabilityDerivation;
import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.CapabilitySatisfaction;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Verifies that catalog CapabilityProvider records (MetaML Scope 6, Phase 2) are consumable by the
// existing Phase 0 CapabilitySatisfaction mechanism, and demonstrates the full causal chain:
//
//   P1 (BpmnCapabilityContractReader) derives a required CapabilityContract from BPMN
//   P2 (CapabilityProviderCatalogReader) derives CapabilityProvider records from the node manager
//       catalog (WorkbenchService.listAvailableAgents())
//   P0 (CapabilitySatisfaction) decides whether a P2 provider satisfies a P1 requirement
//
// This intentionally does not reproduce CapabilitySatisfaction's internal algorithm - each test
// calls CapabilitySatisfaction.satisfies/resolve directly, as any other P0 caller would.
class CapabilityCatalogSatisfactionIntegrationTest {

    @Test
    void catalogProviderWithATriviallyEmptyRequirementIsRecommended() {
        // Demonstrates the actual production catalog path (AgentAvailabilityResult ->
        // CapabilityProviderCatalogReader -> CapabilityProvider) flowing straight into
        // CapabilitySatisfaction, with no synthetic provider substituted in.
        AgentAvailabilityResult catalogEntry = new AgentAvailabilityResult(
                "widget-inspector", true, "widget-inspector-01", "Available", Map.of());
        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(catalogEntry);

        CapabilityContract requirement = new CapabilityContract(null, Set.of(), Set.of(),
                ExecutionMode.SYNCHRONOUS, Map.of(), Set.of());

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isTrue();

        CapabilitySatisfaction.Resolution resolution =
                CapabilitySatisfaction.resolve(requirement, List.of(provider), Map.of());
        assertThat(resolution.status()).isEqualTo(CapabilitySatisfaction.Status.RECOMMENDED);
        assertThat(resolution.selected()).isEqualTo(provider);
    }

    @Test
    void unavailableCatalogProviderNeverSatisfiesAnyRequirement() {
        AgentAvailabilityResult catalogEntry = new AgentAvailabilityResult(
                "widget-inspector", false, "widget-inspector-01", "Agent is offline", Map.of());
        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(catalogEntry);

        CapabilityContract requirement = new CapabilityContract(null, Set.of(), Set.of(),
                ExecutionMode.SYNCHRONOUS, Map.of(), Set.of());

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isFalse();
        assertThat(CapabilitySatisfaction.resolve(requirement, List.of(provider), Map.of()).status())
                .isEqualTo(CapabilitySatisfaction.Status.NO_SUITABLE_PROVIDER);
    }

    // ---- Full I/O satisfaction semantics, exercised via CapabilityProvider the way P2's catalog
    // constructs it (identity/availability from the reader, contract shape hand-built the way a
    // richer future catalog source would populate CapabilityProviderCatalogReader's contract) ----

    @Test
    void providerSupplyingTheRequiredOutputSatisfiesACompatibleRequirement() {
        CapabilityContract requirement = new CapabilityContract(null, Set.of(),
                Set.of(new IoDeclaration("widgetGrade", IoType.STRING, true)),
                ExecutionMode.SYNCHRONOUS, Map.of(), Set.of());

        CapabilityProvider provider = catalogShapedProvider("widget-classifier-01", "widget-classifier",
                Set.of(), Set.of(new IoDeclaration("widgetGrade", IoType.STRING, true)));

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isTrue();
    }

    @Test
    void providerLackingTheRequiredOutputDoesNotSatisfy() {
        CapabilityContract requirement = new CapabilityContract(null, Set.of(),
                Set.of(new IoDeclaration("widgetGrade", IoType.STRING, true)),
                ExecutionMode.SYNCHRONOUS, Map.of(), Set.of());

        CapabilityProvider provider = catalogShapedProvider("widget-classifier-01", "widget-classifier",
                Set.of(), Set.of());

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isFalse();
    }

    @Test
    void providerRequiringAnUnavailableInputDoesNotSatisfy() {
        CapabilityContract requirement = new CapabilityContract(null, Set.of(), Set.of(),
                ExecutionMode.SYNCHRONOUS, Map.of(), Set.of());

        CapabilityProvider provider = catalogShapedProvider("widget-inspector-01", "widget-inspector",
                Set.of(new IoDeclaration("batchId", IoType.STRING, true)), Set.of());

        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isFalse();
        assertThat(CapabilitySatisfaction.satisfies(provider, requirement,
                Map.of("batchId", IoType.STRING))).isTrue();
    }

    @Test
    void unknownTypeCompatibilityIsInheritedFromPhaseZeroNotReimplemented() {
        CapabilityContract requirement = new CapabilityContract(null, Set.of(),
                Set.of(new IoDeclaration("widgetGrade", IoType.UNKNOWN, true)),
                ExecutionMode.SYNCHRONOUS, Map.of(), Set.of());

        // Provider declares a concrete type where the requirement only knows UNKNOWN - Phase 0's
        // own typeCompatible widens rather than narrows; this test asserts that behavior is used
        // as-is, not that P2 re-derives it.
        CapabilityProvider provider = catalogShapedProvider("widget-classifier-01", "widget-classifier",
                Set.of(), Set.of(new IoDeclaration("widgetGrade", IoType.STRING, true)));

        assertThat(CapabilitySatisfaction.typeCompatible(IoType.STRING, IoType.UNKNOWN)).isTrue();
        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isTrue();
    }

    // ---- Genericity ----

    @Test
    void catalogAndSyntheticProvidersEvaluateWithNoEnterpriseSpecificAssumptions() {
        // Independently invented domain and capability id - no RedCollar names, no orderApproved.
        CapabilityContract requirement = new CapabilityContract("widget.classification", Set.of(),
                Set.of(new IoDeclaration("widgetGrade", IoType.STRING, true)),
                ExecutionMode.SYNCHRONOUS, Map.of(), Set.of());
        CapabilityProvider matchingId = new CapabilityProvider("widget-classifier-01", "widget-classifier",
                null, new CapabilityContract("widget.classification", Set.of(),
                        Set.of(new IoDeclaration("widgetGrade", IoType.STRING, true)),
                        ExecutionMode.SYNCHRONOUS, Map.of(), Set.of()),
                "Widget classifier", true, null);
        CapabilityProvider mismatchedId = new CapabilityProvider("widget-classifier-02", "widget-classifier",
                null, new CapabilityContract("widget.other", Set.of(),
                        Set.of(new IoDeclaration("widgetGrade", IoType.STRING, true)),
                        ExecutionMode.SYNCHRONOUS, Map.of(), Set.of()),
                "Widget classifier", true, null);

        assertThat(CapabilitySatisfaction.satisfies(matchingId, requirement, Map.of())).isTrue();
        assertThat(CapabilitySatisfaction.satisfies(mismatchedId, requirement, Map.of())).isFalse();
    }

    private static CapabilityProvider catalogShapedProvider(String providerId, String providerType,
            Set<IoDeclaration> requiredInputs, Set<IoDeclaration> producedOutputs) {
        CapabilityContract contract = new CapabilityContract(null, requiredInputs, producedOutputs,
                ExecutionMode.SYNCHRONOUS, Map.of(), Set.of());
        return new CapabilityProvider(providerId, providerType, null, contract,
                "synthetic catalog-shaped provider", true, null);
    }

    // ---- Full chain with real provider metadata (MetaML Scope 6, Provider Contract Authorship
    // phase, section 14) ----
    //
    // Demonstrates the complete flow this phase adds, end to end, using the same
    // required-inputs/produced-outputs shape backend/nodemanager's application.yml actually
    // authors for the "validator" agent type (see ValidatorExecutor, the source of truth for what
    // it genuinely reads and unconditionally produces):
    //
    //   typed node manager representation (hand-built here the way NodeManagerServiceImpl builds
    //       it from NodeManagerProperties.AgentConfig - workbench has no compile-time dependency
    //       on the node manager module, only a matching JSON/field shape)
    //   -> AgentAvailabilityResponse (node manager's own wire type - not depended on here either;
    //       AgentAvailabilityResult is what actually crosses into the workbench, see
    //       NodeManagerClient)
    //   -> AgentAvailabilityResult
    //   -> CapabilityProviderCatalogReader -> populated CapabilityProvider
    //   -> a real BPMN-derived CapabilityContract (BpmnCapabilityContractReader, P1)
    //   -> CapabilitySatisfaction (P0)

    private static AgentAvailabilityResult realValidatorCatalogEntry() {
        return new AgentAvailabilityResult(
                "validator", true, "validator-agent-01", "Agent registered in node manager catalog",
                Map.of(), "Default schema, payload integrity, and consistency validator agent.",
                List.of("validation", "verification", "schema checking", "integrity", "format compliance"),
                List.of(
                        new IoDeclarationDescriptor("customerId", "STRING", false),
                        new IoDeclarationDescriptor("payload", "STRING", false),
                        new IoDeclarationDescriptor("forceValidationFailure", "BOOLEAN", false)),
                List.of(
                        new IoDeclarationDescriptor("validationPassed", "BOOLEAN", true),
                        new IoDeclarationDescriptor("schemaVersion", "STRING", true),
                        new IoDeclarationDescriptor("validationStatus", "STRING", true),
                        new IoDeclarationDescriptor("validationMessage", "STRING", true)));
    }

    private static BpmnModelInstance parse(String xml) {
        return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    // A dataOutputAssociation targeting a typed Property, immediately followed by a gateway
    // condition on that same variable, is exactly how P1 derives a real *declared and required*
    // output (see BpmnCapabilityContractReaderTest.dataOutputAssociationSatisfiesTheMatchingGatewayRequirement)
    // - the same shape a real RedCollar/generic process puts after any binary decision task. Using
    // only a bare gateway condition (no dataOutputAssociation) would leave contract().producedOutputs()
    // empty, since that field reflects what the activity actually *declares*, not merely what a
    // downstream gateway references - see the reader's own class javadoc.
    private static String validatorProcessWithDeclaredOutput(String outputName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:itemDefinition id="_outputItem" structureRef="Boolean" />
                  <bpmn2:process id="genericValidationProcess" isExecutable="true">
                    <bpmn2:property id="%1$s" itemSubjectRef="_outputItem" name="%1$s" />
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:sequenceFlow id="f1" sourceRef="Start" targetRef="ValidateInput" />
                    <bpmn2:serviceTask id="ValidateInput" name="Validate Input"
                        camunda:type="external" camunda:topic="ValidateInput">
                      <bpmn2:dataOutputAssociation id="doa1">
                        <bpmn2:targetRef>%1$s</bpmn2:targetRef>
                      </bpmn2:dataOutputAssociation>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="f2" sourceRef="ValidateInput" targetRef="GW1" />
                    <bpmn2:exclusiveGateway id="GW1">
                      <bpmn2:incoming>f2</bpmn2:incoming>
                      <bpmn2:outgoing>f3</bpmn2:outgoing>
                      <bpmn2:outgoing>f4</bpmn2:outgoing>
                    </bpmn2:exclusiveGateway>
                    <bpmn2:sequenceFlow id="f3" sourceRef="GW1" targetRef="End1">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${%1$s}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:sequenceFlow id="f4" sourceRef="GW1" targetRef="End2">
                      <bpmn2:conditionExpression xsi:type="bpmn2:tFormalExpression">${!%1$s}</bpmn2:conditionExpression>
                    </bpmn2:sequenceFlow>
                    <bpmn2:endEvent id="End1" />
                    <bpmn2:endEvent id="End2" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(outputName);
    }

    @Test
    void realValidatorProviderSatisfiesARealBpmnDerivedGatewayRequirement() {
        AgentAvailabilityResult catalogEntry = realValidatorCatalogEntry();
        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(catalogEntry);
        assertThat(provider).isNotNull();
        assertThat(provider.contract().producedOutputs()).contains(
                new IoDeclaration("validationPassed", IoType.BOOLEAN, true));

        // The BPMN declares and gates on validationPassed, which the real Node Manager-authored
        // contract for the validator agent genuinely declares (unconditionally, per
        // ValidatorExecutor) - this is the positive, satisfying case.
        BpmnModelInstance model = parse(validatorProcessWithDeclaredOutput("validationPassed"));
        Activity activity = (Activity) model.getModelElementById("ValidateInput");
        ActivityCapabilityDerivation derivation = BpmnCapabilityContractReader.derive(model, activity);
        CapabilityContract requirement = derivation.contract();

        assertThat(requirement.producedOutputs()).contains(
                new IoDeclaration("validationPassed", IoType.BOOLEAN, true));
        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isTrue();

        CapabilitySatisfaction.Resolution resolution =
                CapabilitySatisfaction.resolve(requirement, List.of(provider), Map.of());
        assertThat(resolution.status()).isEqualTo(CapabilitySatisfaction.Status.RECOMMENDED);
        assertThat(resolution.selected()).isEqualTo(provider);
    }

    @Test
    void realValidatorProviderDoesNotSatisfyARealBpmnDerivedRequirementForAnOutputItNeverDeclares() {
        AgentAvailabilityResult catalogEntry = realValidatorCatalogEntry();
        CapabilityProvider provider = CapabilityProviderCatalogReader.toCapabilityProvider(catalogEntry);
        assertThat(provider).isNotNull();

        // The BPMN declares and gates on customerVerified, an output the validator agent's real
        // contract never declares (ValidatorExecutor has no such output) - this is the negative,
        // mismatched-output case: a genuine capability gap, not a fabricated one.
        BpmnModelInstance model = parse(validatorProcessWithDeclaredOutput("customerVerified"));
        Activity activity = (Activity) model.getModelElementById("ValidateInput");
        ActivityCapabilityDerivation derivation = BpmnCapabilityContractReader.derive(model, activity);
        CapabilityContract requirement = derivation.contract();

        assertThat(requirement.producedOutputs()).contains(
                new IoDeclaration("customerVerified", IoType.BOOLEAN, true));
        assertThat(CapabilitySatisfaction.satisfies(provider, requirement, Map.of())).isFalse();
        assertThat(CapabilitySatisfaction.resolve(requirement, List.of(provider), Map.of()).status())
                .isEqualTo(CapabilitySatisfaction.Status.NO_SUITABLE_PROVIDER);
    }
}
