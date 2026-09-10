package com.metaml.workbench.capability.runtime;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// MetaML Scope 6, Phase 4: the capability output contract itself, exercised directly against the
// authoritative boundary. Everything here is generic - synthetic provider identities, synthetic
// output names, synthetic activities - so nothing depends on any particular enterprise process.
class CapabilityOutputPropagatorTest {

    private static final String ACTIVITY_ID = "Task_Generic";
    private static final String ACTIVITY_INSTANCE_ID = "Task_Generic:instance-1";

    private DelegateExecution execution;

    @BeforeEach
    void setUp() {
        execution = mock(DelegateExecution.class);
        given(execution.getProcessInstanceId()).willReturn("proc-1");
        given(execution.getActivityInstanceId()).willReturn(ACTIVITY_INSTANCE_ID);
    }

    // ---------------------------------------------------------------- A. BOOLEAN

    @Test
    void declaredBooleanOutputWithABooleanValueSatisfiesTheContractAndReachesProcessState() {
        CapabilityProvider provider = provider(declaration("decision", IoType.BOOLEAN));

        assertThat(CapabilityOutputPropagator.validate(provider, Map.of("decision", true),
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID)).isEmpty();

        Map<String, Object> published = CapabilityOutputPropagator.publish(execution, provider,
                Map.of("decision", true), Set.of("decision"), ACTIVITY_ID, null);

        assertThat(published).containsExactly(Map.entry("decision", true));
        verify(execution).setVariable("decision", true);
        verify(execution).setVariable("twinAutomationOutput_decision_" + ACTIVITY_ID, true);
    }

    // ---------------------------------------------------------------- B. NUMBER

    @Test
    void declaredNumberOutputAcceptsAnyNumericRuntimeType() {
        CapabilityProvider provider = provider(declaration("score", IoType.NUMBER));

        assertThat(CapabilityOutputPropagator.validate(provider, Map.of("score", 42),
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID)).isEmpty();
        assertThat(CapabilityOutputPropagator.validate(provider, Map.of("score", 0.94d),
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID)).isEmpty();
        assertThat(CapabilityOutputPropagator.validate(provider, Map.of("score", 7L),
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID)).isEmpty();
    }

    // ---------------------------------------------------------------- C. STRING

    @Test
    void declaredStringOutputAcceptsAStringValue() {
        CapabilityProvider provider = provider(declaration("label", IoType.STRING));

        assertThat(CapabilityOutputPropagator.validate(provider, Map.of("label", "anything"),
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID)).isEmpty();
    }

    // ---------------------------------------------------------------- D. STRUCT

    @Test
    void declaredStructOutputAcceptsAStructuredValueButNotAScalar() {
        CapabilityProvider provider = provider(declaration("payload", IoType.STRUCT));

        // STRUCT is the structured/object representation BpmnCapabilityContractReader maps a BPMN
        // structureRef of Object onto, alongside the three scalar mappings.
        assertThat(CapabilityOutputPropagator.validate(provider,
                Map.of("payload", Map.of("nested", 1)), ACTIVITY_ID, ACTIVITY_INSTANCE_ID)).isEmpty();
        assertThat(CapabilityOutputPropagator.validate(provider,
                Map.of("payload", List.of("a", "b")), ACTIVITY_ID, ACTIVITY_INSTANCE_ID)).isEmpty();

        assertThat(CapabilityOutputPropagator.validate(provider, Map.of("payload", "a string"),
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.kind()).isEqualTo(OutputContractViolationKind.TYPE_MISMATCH);
                    assertThat(v.outputName()).isEqualTo("payload");
                    assertThat(v.declaredType()).isEqualTo(IoType.STRUCT);
                    assertThat(v.actualType()).isEqualTo("String");
                });
    }

    // ---------------------------------------------------------------- E. MULTIPLE OUTPUTS

    @Test
    void everyDeclaredOutputIsValidatedAndPublishedTogether() {
        CapabilityProvider provider = provider(
                declaration("a", IoType.BOOLEAN),
                declaration("b", IoType.NUMBER),
                declaration("c", IoType.STRING));

        Map<String, Object> actual = ordered("a", true, "b", 5, "c", "value");
        assertThat(CapabilityOutputPropagator.validate(provider, actual, ACTIVITY_ID, ACTIVITY_INSTANCE_ID))
                .isEmpty();

        CapabilityOutputPropagator.publish(execution, provider, actual, Set.of("a", "b", "c"),
                ACTIVITY_ID, null);

        verify(execution).setVariable("a", true);
        verify(execution).setVariable("b", 5);
        verify(execution).setVariable("c", "value");
        verify(execution).setVariable("twinAutomationOutput_a_" + ACTIVITY_ID, true);
        verify(execution).setVariable("twinAutomationOutput_b_" + ACTIVITY_ID, 5);
        verify(execution).setVariable("twinAutomationOutput_c_" + ACTIVITY_ID, "value");
    }

    // ---------------------------------------------------------------- F. UNDECLARED OUTPUT

    @Test
    void anOutputTheContractNeverDeclaredIsRejectedAndNothingIsPublished() {
        CapabilityProvider provider = provider(
                declaration("a", IoType.BOOLEAN), declaration("b", IoType.NUMBER));
        Map<String, Object> actual = ordered("a", true, "b", 5, "c", "surprise");

        assertThatThrownBy(() -> CapabilityOutputPropagator.publish(execution, provider, actual,
                Set.of("a"), ACTIVITY_ID, null))
                .isInstanceOf(CapabilityOutputContractViolationException.class)
                .satisfies(thrown -> {
                    List<OutputContractViolation> violations =
                            ((CapabilityOutputContractViolationException) thrown).violations();
                    assertThat(violations).singleElement().satisfies(v -> {
                        assertThat(v.kind()).isEqualTo(OutputContractViolationKind.UNDECLARED_OUTPUT);
                        assertThat(v.outputName()).isEqualTo("c");
                        assertThat(v.declaredType()).isNull();
                        assertThat(v.actualType()).isEqualTo("String");
                    });
                });

        verify(execution, never()).setVariable(anyString(), any());
    }

    // ---------------------------------------------------------------- G. MISSING DECLARED OUTPUT

    @Test
    void aDeclaredOutputMissingFromTheResultIsRejectedAndNothingIsPublished() {
        CapabilityProvider provider = provider(
                declaration("a", IoType.BOOLEAN),
                declaration("b", IoType.NUMBER),
                declaration("c", IoType.STRING));
        Map<String, Object> actual = ordered("a", true, "b", 5);

        assertThatThrownBy(() -> CapabilityOutputPropagator.publish(execution, provider, actual,
                Set.of("a"), ACTIVITY_ID, null))
                .isInstanceOf(CapabilityOutputContractViolationException.class)
                .satisfies(thrown -> assertThat(
                        ((CapabilityOutputContractViolationException) thrown).violations())
                        .singleElement().satisfies(v -> {
                            assertThat(v.kind())
                                    .isEqualTo(OutputContractViolationKind.MISSING_DECLARED_OUTPUT);
                            assertThat(v.outputName()).isEqualTo("c");
                            assertThat(v.declaredType()).isEqualTo(IoType.STRING);
                            assertThat(v.actualType()).isNull();
                        }));

        verify(execution, never()).setVariable(anyString(), any());
    }

    // ---------------------------------------------------------------- H. TYPE MISMATCH

    @Test
    void aStringIsNeverSilentlyCoercedIntoADeclaredBoolean() {
        CapabilityProvider provider = provider(declaration("decision", IoType.BOOLEAN));

        assertThatThrownBy(() -> CapabilityOutputPropagator.publish(execution, provider,
                Map.of("decision", "true"), Set.of("decision"), ACTIVITY_ID, null))
                .isInstanceOf(CapabilityOutputContractViolationException.class)
                .satisfies(thrown -> assertThat(
                        ((CapabilityOutputContractViolationException) thrown).violations())
                        .singleElement().satisfies(v -> {
                            assertThat(v.kind()).isEqualTo(OutputContractViolationKind.TYPE_MISMATCH);
                            assertThat(v.declaredType()).isEqualTo(IoType.BOOLEAN);
                            assertThat(v.actualType()).isEqualTo("String");
                        }));

        verify(execution, never()).setVariable(anyString(), any());
    }

    @Test
    void aNumericStringIsNeverSilentlyCoercedIntoADeclaredNumber() {
        CapabilityProvider provider = provider(declaration("score", IoType.NUMBER));

        assertThat(CapabilityOutputPropagator.validate(provider, Map.of("score", "42"),
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID))
                .singleElement()
                .satisfies(v -> assertThat(v.kind()).isEqualTo(OutputContractViolationKind.TYPE_MISMATCH));
    }

    // ---------------------------------------------------------------- I. NULL

    @Test
    void aDeclaredOutputPresentWithANullValueIsItsOwnViolationDistinctFromBeingMissing() {
        CapabilityProvider provider = provider(declaration("decision", IoType.BOOLEAN));
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("decision", null);

        assertThat(CapabilityOutputPropagator.validate(provider, actual, ACTIVITY_ID, ACTIVITY_INSTANCE_ID))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.kind()).isEqualTo(OutputContractViolationKind.INVALID_NULL_OUTPUT);
                    assertThat(v.outputName()).isEqualTo("decision");
                    assertThat(v.declaredType()).isEqualTo(IoType.BOOLEAN);
                });

        assertThatThrownBy(() -> CapabilityOutputPropagator.publish(execution, provider, actual,
                Set.of("decision"), ACTIVITY_ID, null))
                .isInstanceOf(CapabilityOutputContractViolationException.class);
        verify(execution, never()).setVariable(anyString(), any());
    }

    // ---------------------------------------------------------------- J. ATOMIC FAILURE

    @Test
    void twoValidOutputsAreNotCommittedWhenAThirdViolatesTheContract() {
        CapabilityProvider provider = provider(
                declaration("a", IoType.BOOLEAN),
                declaration("b", IoType.NUMBER),
                declaration("c", IoType.BOOLEAN));
        // a and b are individually valid; c is not.
        Map<String, Object> actual = ordered("a", true, "b", 5, "c", "not a boolean");

        assertThatThrownBy(() -> CapabilityOutputPropagator.publish(execution, provider, actual,
                Set.of("a", "b", "c"), ACTIVITY_ID, null))
                .isInstanceOf(CapabilityOutputContractViolationException.class);

        // No process variable of any kind - neither the bare gateway variables nor the per-visit
        // bookkeeping records - reached the execution.
        verify(execution, never()).setVariable(anyString(), any());
    }

    @Test
    void everyViolationIsReportedTogetherRatherThanOnlyTheFirst() {
        CapabilityProvider provider = provider(
                declaration("a", IoType.BOOLEAN),
                declaration("b", IoType.NUMBER));
        Map<String, Object> actual = ordered("a", "wrong type", "extra", true);

        List<OutputContractViolation> violations = CapabilityOutputPropagator.validate(
                provider, actual, ACTIVITY_ID, ACTIVITY_INSTANCE_ID);

        assertThat(violations).extracting(OutputContractViolation::kind, OutputContractViolation::outputName)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                OutputContractViolationKind.TYPE_MISMATCH, "a"),
                        org.assertj.core.groups.Tuple.tuple(
                                OutputContractViolationKind.MISSING_DECLARED_OUTPUT, "b"),
                        org.assertj.core.groups.Tuple.tuple(
                                OutputContractViolationKind.UNDECLARED_OUTPUT, "extra"));
    }

    // ---------------------------------------------------------------- K. EMPTY CONTRACT

    @Test
    void anEmptyProducedOutputsContractPermitsNothingRatherThanEverything() {
        CapabilityProvider provider = provider();

        assertThat(CapabilityOutputPropagator.validate(provider, Map.of("foo", true),
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.kind()).isEqualTo(OutputContractViolationKind.UNDECLARED_OUTPUT);
                    assertThat(v.outputName()).isEqualTo("foo");
                });

        // ...and an empty contract with an empty result is simply satisfied.
        assertThat(CapabilityOutputPropagator.validate(provider, Map.of(),
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID)).isEmpty();
    }

    // ---------------------------------------------------------------- L/M. INSTANCE ISOLATION

    @Test
    void twoVisitsOfTheSameActivityPublishToDistinctPerVisitVariables() {
        CapabilityProvider provider = provider(declaration("decision", IoType.BOOLEAN));

        DelegateExecution first = mock(DelegateExecution.class);
        given(first.getActivityInstanceId()).willReturn(ACTIVITY_ID + ":instance-1");
        DelegateExecution second = mock(DelegateExecution.class);
        given(second.getActivityInstanceId()).willReturn(ACTIVITY_ID + ":instance-2");

        CapabilityOutputPropagator.publish(first, provider, Map.of("decision", true),
                Set.of("decision"), ACTIVITY_ID, 0);
        CapabilityOutputPropagator.publish(second, provider, Map.of("decision", false),
                Set.of("decision"), ACTIVITY_ID, 1);

        // Per-visit records carry the loop counter, so sibling visits never overwrite each other.
        verify(first).setVariable("twinAutomationOutput_decision_" + ACTIVITY_ID + "_0", true);
        verify(second).setVariable("twinAutomationOutput_decision_" + ACTIVITY_ID + "_1", false);
        verify(first, never()).setVariable("twinAutomationOutput_decision_" + ACTIVITY_ID + "_1", false);
    }

    @Test
    void aViolationCarriesTheActivityInstanceItOccurredOnWithoutCarryingTheBusinessValue() {
        CapabilityProvider provider = provider(declaration("decision", IoType.BOOLEAN));

        List<OutputContractViolation> violations = CapabilityOutputPropagator.validate(provider,
                Map.of("decision", "sensitive-business-value"), ACTIVITY_ID, ACTIVITY_INSTANCE_ID);

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.providerId()).isEqualTo("provider-instance-01");
            assertThat(v.providerType()).isEqualTo("generic-provider");
            assertThat(v.providerVersion()).isEqualTo("1.2.3");
            assertThat(v.activityId()).isEqualTo(ACTIVITY_ID);
            assertThat(v.activityInstanceId()).isEqualTo(ACTIVITY_INSTANCE_ID);
            assertThat(v.actualType()).isEqualTo("String");
        });
        assertThat(new CapabilityOutputContractViolationException(violations).getMessage())
                .contains("provider-instance-01", "TYPE_MISMATCH", "decision")
                .doesNotContain("sensitive-business-value");
    }

    // ---------------------------------------------------------------- UNKNOWN

    @Test
    void unknownKeepsItsPhaseZeroMeaningWideningTypeCompatibilityWithoutWaivingPresence() {
        CapabilityProvider provider = provider(declaration("opaque", IoType.UNKNOWN));

        // widens: any runtime type satisfies a declaration whose source type was never declared
        assertThat(CapabilityOutputPropagator.validate(provider, Map.of("opaque", true),
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID)).isEmpty();
        assertThat(CapabilityOutputPropagator.validate(provider, Map.of("opaque", "x"),
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID)).isEmpty();

        // but does not mean "anything goes": it must still be present and non-null
        assertThat(CapabilityOutputPropagator.validate(provider, Map.of(),
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID))
                .singleElement()
                .satisfies(v -> assertThat(v.kind())
                        .isEqualTo(OutputContractViolationKind.MISSING_DECLARED_OUTPUT));

        Map<String, Object> nullValued = new LinkedHashMap<>();
        nullValued.put("opaque", null);
        assertThat(CapabilityOutputPropagator.validate(provider, nullValued,
                ACTIVITY_ID, ACTIVITY_INSTANCE_ID))
                .singleElement()
                .satisfies(v -> assertThat(v.kind())
                        .isEqualTo(OutputContractViolationKind.INVALID_NULL_OUTPUT));
    }

    // ---------------------------------------------------------------- NO RESOLVABLE CONTRACT

    @Test
    void withNoResolvableProviderContractThereIsNoContractToEnforceAndPublicationIsUnchanged() {
        // The absence of a legitimate provider is a capability-gap question (Phase 5), not an
        // output-contract question (Phase 4). The pre-existing publication behaviour is preserved.
        Map<String, Object> published = CapabilityOutputPropagator.publish(execution, null,
                ordered("anything", true, "atAll", "yes"), Set.of("anything"), ACTIVITY_ID, null);

        assertThat(published).containsExactly(Map.entry("anything", true));
        verify(execution).setVariable("anything", true);
        verify(execution).setVariable("twinAutomationOutput_atAll_" + ACTIVITY_ID, "yes");
    }

    @Test
    void onlyOutputsADownstreamGatewayActuallyReadsBecomeBareProcessVariables() {
        CapabilityProvider provider = provider(
                declaration("decision", IoType.BOOLEAN), declaration("detail", IoType.STRING));

        Map<String, Object> published = CapabilityOutputPropagator.publish(execution, provider,
                ordered("decision", true, "detail", "why"), Set.of("decision"), ACTIVITY_ID, null);

        assertThat(published).containsOnlyKeys("decision");
        verify(execution).setVariable("decision", true);
        verify(execution, never()).setVariable("detail", "why");
        // ...but every output still gets its per-visit bookkeeping record
        verify(execution).setVariable("twinAutomationOutput_detail_" + ACTIVITY_ID, "why");
    }

    // ---------------------------------------------------------------- fixtures

    private static IoDeclaration declaration(String name, IoType type) {
        return new IoDeclaration(name, type, true);
    }

    private static CapabilityProvider provider(IoDeclaration... producedOutputs) {
        Set<IoDeclaration> outputs = new LinkedHashSet<>(List.of(producedOutputs));
        return new CapabilityProvider("provider-instance-01", "generic-provider", "1.2.3",
                new CapabilityContract(null, Set.of(), outputs, ExecutionMode.SYNCHRONOUS,
                        Map.of(), Set.of()),
                "a generic synthetic provider", true, null);
    }

    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
