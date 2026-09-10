package com.metaml.workbench.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// Synthetic, business-domain-independent coverage of the Phase 0 capability vocabulary records.
class CapabilityContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- IoDeclaration ---

    @Test
    void validIoDeclaration() {
        IoDeclaration declaration = new IoDeclaration("inputA", IoType.STRING, true);

        assertThat(declaration.name()).isEqualTo("inputA");
        assertThat(declaration.type()).isEqualTo(IoType.STRING);
        assertThat(declaration.required()).isTrue();
    }

    @Test
    void invalidIoDeclarationNameRejected() {
        assertThatThrownBy(() -> new IoDeclaration("input-a", IoType.STRING, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IoDeclaration("1input", IoType.STRING, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IoDeclaration("", IoType.STRING, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IoDeclaration(null, IoType.STRING, true))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void unknownTypeIsAllowedAndCarriesNoValue() {
        IoDeclaration declaration = new IoDeclaration("inputA", IoType.UNKNOWN, false);

        assertThat(declaration.type()).isEqualTo(IoType.UNKNOWN);
    }

    @Test
    void ioDeclarationHasNoValueCarryingField() {
        for (Field field : IoDeclaration.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            String lowerName = field.getName().toLowerCase();
            assertThat(lowerName).doesNotContain("default").doesNotContain("value").doesNotContain("fallback");
        }
    }

    // --- CapabilityProvider ---

    @Test
    void validProviderType() {
        CapabilityProvider provider = provider("provider-a", "decision-maker", "1.0.0");

        assertThat(provider.providerType()).isEqualTo("decision-maker");
    }

    @Test
    void invalidProviderTypeRejected() {
        assertThatThrownBy(() -> provider("provider-a", "Decision_Maker", "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider("provider-a", "decision maker", "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unspecifiedVersionCanonicalizesToZeroZeroZero() {
        CapabilityProvider unversioned = provider("provider-a", "decision-maker", null);
        CapabilityProvider blankVersioned = provider("provider-b", "decision-maker", "  ");

        assertThat(unversioned.version()).isEqualTo("0.0.0");
        assertThat(blankVersioned.version()).isEqualTo("0.0.0");
    }

    // --- CapabilityContract ---

    @Test
    void nullableCapabilityIdIsAllowed() {
        CapabilityContract derived = contract(null, Set.of(), Set.of(), Map.of());

        assertThat(derived.capabilityId()).isNull();
    }

    @Test
    void structuralContractEquality() {
        IoDeclaration outputA = new IoDeclaration("result", IoType.BOOLEAN, true);
        CapabilityContract first = contract("decision.binary", Set.of(), Set.of(outputA), Map.of("k", "v"));
        CapabilityContract second = contract("decision.binary", Set.of(), Set.of(outputA), Map.of("k", "v"));
        CapabilityContract different = contract("decision.other", Set.of(), Set.of(outputA), Map.of("k", "v"));

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first).isNotEqualTo(different);
    }

    @Test
    void contractCollectionsAreImmutableAndDefensivelyCopied() {
        Set<IoDeclaration> mutableInputs = new java.util.HashSet<>();
        mutableInputs.add(new IoDeclaration("inputA", IoType.STRING, true));
        Map<String, Object> mutableConstraints = new HashMap<>();
        mutableConstraints.put("k", "v");

        CapabilityContract contract = new CapabilityContract("cap", mutableInputs, Set.of(),
                ExecutionMode.SYNCHRONOUS, mutableConstraints, Set.of());

        // mutating the caller's collections after construction must not affect the contract
        mutableInputs.add(new IoDeclaration("inputB", IoType.STRING, true));
        mutableConstraints.put("k2", "v2");
        assertThat(contract.requiredInputs()).hasSize(1);
        assertThat(contract.constraints()).hasSize(1);

        assertThatThrownBy(() -> contract.requiredInputs().add(new IoDeclaration("inputC", IoType.STRING, true)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> contract.constraints().put("k3", "v3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void contractHasNoVersionOrProviderIdentityField() {
        for (Method accessor : CapabilityContract.class.getDeclaredMethods()) {
            String name = accessor.getName();
            assertThat(name).isNotEqualTo("version");
            assertThat(name).isNotEqualTo("providerId");
            assertThat(name).isNotEqualTo("providerType");
            assertThat(name.toLowerCase()).doesNotContain("default").doesNotContain("fallback");
        }
    }

    @Test
    void jacksonRoundTripsIoDeclarationAndContract() throws Exception {
        CapabilityContract original = contract("decision.binary",
                Set.of(new IoDeclaration("inputA", IoType.NUMBER, true)),
                Set.of(new IoDeclaration("result", IoType.BOOLEAN, true)),
                Map.of("threshold", 5));

        String json = MAPPER.writeValueAsString(original);
        CapabilityContract restored = MAPPER.readValue(json, CapabilityContract.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void jacksonRoundTripsProvider() throws Exception {
        CapabilityProvider original = provider("provider-a", "decision-maker", "1.2.3");

        String json = MAPPER.writeValueAsString(original);
        CapabilityProvider restored = MAPPER.readValue(json, CapabilityProvider.class);

        assertThat(restored).isEqualTo(original);
    }

    private static CapabilityProvider provider(String providerId, String providerType, String version) {
        return new CapabilityProvider(providerId, providerType, version,
                contract(null, Set.of(), Set.of(), Map.of()), "synthetic test provider", true, null);
    }

    private static CapabilityContract contract(String capabilityId, Set<IoDeclaration> requiredInputs,
            Set<IoDeclaration> producedOutputs, Map<String, Object> constraints) {
        return new CapabilityContract(capabilityId, requiredInputs, producedOutputs, ExecutionMode.SYNCHRONOUS,
                constraints, Set.of());
    }
}
