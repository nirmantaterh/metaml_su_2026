package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TargetPlatformTwinMirrorGeneratorTest {

    private final TargetPlatformTwinMirrorGenerator generator = new TargetPlatformTwinMirrorGenerator();

    private static String bpmn(String processId, String processName, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:signal id="Signal_1" name="cuttingSignal" />
                  <bpmn2:process id="%s" name="%s" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    %s
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(processId, processName, body);
    }

    @Test
    void suffixesTheProcessIdAndNameSoItDeploysAsADistinctDefinition() {
        String proxy = bpmn("RedCollar.Manuf", "Manuf", "");

        String twin = generator.mirror(proxy);

        assertThat(twin).contains("id=\"RedCollar.Manuf_twin\"").contains("name=\"Manuf (twin)\"");
        // the original must not have been mutated in place
        assertThat(proxy).contains("id=\"RedCollar.Manuf\"").doesNotContain("RedCollar.Manuf_twin");
    }

    @Test
    void leavesTheSharedSignalNameUntouched() {
        // signalRef and the signal's own name are the entire mechanism SignalBroadcaster uses to
        // recognize proxy and twin as synchronizing on the same point - changing either breaks it.
        String proxy = bpmn("RedCollar.Manuf", "Manuf", """
                <bpmn2:intermediateCatchEvent id="Event_Cut">
                  <bpmn2:signalEventDefinition signalRef="Signal_1" />
                </bpmn2:intermediateCatchEvent>
                """);

        String twin = generator.mirror(proxy);

        assertThat(twin)
                .contains("name=\"cuttingSignal\"")
                .contains("signalRef=\"Signal_1\"")
                // the catch event's own id is also untouched - only unique within one process
                // definition, never contended across two
                .contains("id=\"Event_Cut\"");
    }

    @Test
    void suffixesExternalTaskTopicsSoProxyAndTwinDontShareAWorkerSubscription() {
        String proxy = bpmn("RedCollar.Manuf", "Manuf", """
                <bpmn2:serviceTask id="Cutting" name="Cutting" camunda:type="external" camunda:topic="Cutting" />
                """);

        String twin = generator.mirror(proxy);

        assertThat(twin)
                .contains("camunda:topic=\"CuttingTwin\"")
                .doesNotContain("camunda:topic=\"Cutting\"/>")
                .doesNotContain("camunda:topic=\"Cutting\" ");
    }

    @Test
    void preservesEveryOtherElementAndAttributeVerbatim() {
        String proxy = bpmn("RedCollar.Manuf", "Manuf", """
                <bpmn2:exclusiveGateway id="Gateway_1" name="Quality OK?" default="Flow_no" />
                <bpmn2:serviceTask id="Cutting" name="Cutting" camunda:type="external" camunda:topic="Cutting" />
                """);

        String twin = generator.mirror(proxy);

        assertThat(twin)
                .contains("id=\"Gateway_1\"")
                .contains("name=\"Quality OK?\"")
                .contains("default=\"Flow_no\"")
                .contains("id=\"Cutting\"")
                .contains("name=\"Cutting\"");
    }

    @Test
    void turnsAMirroredUserTaskIntoAnExecutableServiceTaskSoTheTwinIsNotAHumanTask() {
        // A userTask surviving into the Twin is a wait state with nobody to complete it: the twin
        // parks there, the proxy's matching sync signal never gets its RESPONSE, and the pair
        // deadlocks. This is the defect that made a Workbench-modelled process unable to run E2E.
        String proxy = bpmn("Process_Onboarding", "Onboarding", """
                <bpmn2:userTask id="Activity_SeniorReview" name="Senior Compliance Review"
                    camunda:assignee="demo" camunda:candidateGroups="compliance" camunda:priority="50" />
                """);

        String twin = generator.mirror(proxy);

        assertThat(twin)
                .doesNotContain("userTask")
                .contains("serviceTask")
                .contains("id=\"Activity_SeniorReview\"")
                // name is business identity and is carried over untouched
                .contains("name=\"Senior Compliance Review\"")
                // the bean TargetPlatformSourceGenerator will emit the @Component for
                .contains("delegateExpression=\"${activity_SeniorReview}\"")
                // assignment markup that can never apply to a service task is dropped
                .doesNotContain("assignee")
                .doesNotContain("candidateGroups")
                .doesNotContain("priority");
        // the original model is never rewritten - the Original keeps its human task
        assertThat(proxy).contains("<bpmn2:userTask").contains("camunda:assignee=\"demo\"");
    }

    @Test
    void automatesManualAndBareTasksTooSoEveryTwinActivityHasADelegate() {
        // Neither blocks the token, but neither invokes anything either - so without this the Twin
        // has no delegate to observe, and nothing for an evolved component to later bind onto.
        String proxy = bpmn("Process_Onboarding", "Onboarding", """
                <bpmn2:manualTask id="Activity_CollectDocs" name="Collect Documents" />
                <bpmn2:task id="Activity_Placeholder" name="Placeholder" />
                """);

        String twin = generator.mirror(proxy);

        assertThat(twin)
                .doesNotContain("manualTask")
                .contains("delegateExpression=\"${activity_CollectDocs}\"")
                .contains("delegateExpression=\"${activity_Placeholder}\"");
    }

    @Test
    void keepsTheProducerTheModelAlreadyNamedRatherThanOverwritingIt() {
        // Only activities the model left with no executable behaviour get a generated delegate. An
        // activity that already names a producer keeps it - the mirror never invents a substitute.
        String proxy = bpmn("Process_Onboarding", "Onboarding", """
                <bpmn2:serviceTask id="Activity_Score" name="Score"
                    camunda:delegateExpression="${realScoringComponent}" />
                <bpmn2:serviceTask id="Activity_Verify" name="Verify"
                    camunda:type="external" camunda:topic="verify" />
                """);

        String twin = generator.mirror(proxy);

        assertThat(twin)
                .contains("delegateExpression=\"${realScoringComponent}\"")
                .contains("camunda:topic=\"verifyTwin\"")
                .doesNotContain("${activity_Score}")
                .doesNotContain("${activity_Verify}");
    }

    @Test
    void dropsFormDataAndTheExtensionElementsWrapperItEmptied() {
        String proxy = bpmn("Process_Onboarding", "Onboarding", """
                <bpmn2:userTask id="Activity_Review" name="Review">
                  <bpmn2:extensionElements>
                    <camunda:formData>
                      <camunda:formField id="approved" label="Approved" type="boolean" />
                    </camunda:formData>
                  </bpmn2:extensionElements>
                </bpmn2:userTask>
                """);

        String twin = generator.mirror(proxy);

        assertThat(twin)
                .doesNotContain("formData")
                .doesNotContain("formField")
                .doesNotContain("extensionElements")
                .contains("delegateExpression=\"${activity_Review}\"");
    }

    @Test
    void keepsAnExtensionElementsBlockThatStillHasRealContent() {
        // Only formData is userTask-specific. An execution listener is not, and dropping the wrapper
        // around it would silently delete behaviour the model asked for.
        String proxy = bpmn("Process_Onboarding", "Onboarding", """
                <bpmn2:userTask id="Activity_Review" name="Review">
                  <bpmn2:extensionElements>
                    <camunda:formData>
                      <camunda:formField id="approved" type="boolean" />
                    </camunda:formData>
                    <camunda:executionListener event="end" delegateExpression="${auditListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:userTask>
                """);

        String twin = generator.mirror(proxy);

        assertThat(twin)
                .doesNotContain("formData")
                .contains("executionListener")
                .contains("${auditListener}");
    }

    @Test
    void refusesAHumanTaskWithNoIdRatherThanEmittingAnUnreachableDelegate() {
        String proxy = bpmn("Process_Onboarding", "Onboarding",
                "<bpmn2:userTask name=\"Nameless\" />");

        assertThatThrownBy(() -> generator.mirror(proxy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userTask");
    }

    @Test
    void leavesAlreadyAutomatedTaskTypesAlone() {
        // scriptTask and businessRuleTask execute in the engine already. Rewriting them would replace
        // behaviour the model specified with a generated stub.
        String proxy = bpmn("Process_Onboarding", "Onboarding", """
                <bpmn2:scriptTask id="Activity_Calc" name="Calc" scriptFormat="javascript" />
                <bpmn2:businessRuleTask id="Activity_Rule" name="Rule" camunda:decisionRef="riskDecision" />
                <bpmn2:receiveTask id="Activity_Await" name="Await" />
                """);

        String twin = generator.mirror(proxy);

        assertThat(twin)
                .contains("scriptTask")
                .contains("businessRuleTask")
                .contains("receiveTask")
                .doesNotContain("${activity_Calc}");
    }
}
