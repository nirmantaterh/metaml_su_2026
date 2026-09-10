package com.metaml.workbench.capability.gap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

// Deterministic identity coverage (MetaML Scope 6, Phase 5, section 6, test category A). Synthetic
// names throughout (invoice validation domain) per section 21.
class CapabilityGapIdentityTest {

    @Test
    void sameLogicalGapProducesTheSameId() {
        String first = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", "instance-1",
                Set.of("riskScore"));
        String second = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", "instance-1",
                Set.of("riskScore"));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentOrderingOfTheSameUnsatisfiedNamesProducesTheSameId() {
        Set<String> forward = new LinkedHashSet<>(List.of("riskScore", "fraudFlag", "creditLimit"));
        Set<String> reversed = new LinkedHashSet<>(List.of("creditLimit", "fraudFlag", "riskScore"));
        Set<String> treeOrdered = new TreeSet<>(List.of("fraudFlag", "riskScore", "creditLimit"));

        String a = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", null, forward);
        String b = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", null, reversed);
        String c = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", null, treeOrdered);

        assertThat(a).isEqualTo(b).isEqualTo(c);
    }

    @Test
    void differentProcessDefinitionProducesADifferentId() {
        String a = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", null,
                Set.of("riskScore"));
        String b = CapabilityGapIdentity.gapId("documentClassificationProcess", "ValidateInvoice", null,
                Set.of("riskScore"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentActivityProducesADifferentId() {
        String a = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", null,
                Set.of("riskScore"));
        String b = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ClassifyDocument", null,
                Set.of("riskScore"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentActivityInstanceProducesADifferentId() {
        String a = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", "instance-1",
                Set.of("riskScore"));
        String b = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", "instance-2",
                Set.of("riskScore"));
        String staticGap = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", null,
                Set.of("riskScore"));

        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(staticGap);
        assertThat(b).isNotEqualTo(staticGap);
    }

    @Test
    void differentUnsatisfiedOutputSetProducesADifferentId() {
        String a = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", null,
                Set.of("riskScore"));
        String b = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", null,
                Set.of("riskScore", "fraudFlag"));
        String c = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", null, Set.of());

        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(b).isNotEqualTo(c);
    }

    @Test
    void idIsA64CharacterLowercaseHexSha256Digest() {
        String id = CapabilityGapIdentity.gapId("invoiceValidationProcess", "ValidateInvoice", null,
                Set.of("riskScore"));

        assertThat(id).hasSize(64);
        assertThat(id).matches("^[0-9a-f]{64}$");
    }

    // Guards against a field-boundary collision: a naive delimiter-based (rather than
    // length-prefixed) canonicalization could conflate these two logically different gaps.
    @Test
    void noFieldBoundaryCollisionBetweenAdjacentFields() {
        String a = CapabilityGapIdentity.gapId("proc", "ess:Activity", null, Set.of("x"));
        String b = CapabilityGapIdentity.gapId("proc:ess", "Activity", null, Set.of("x"));

        assertThat(a).isNotEqualTo(b);
    }
}
