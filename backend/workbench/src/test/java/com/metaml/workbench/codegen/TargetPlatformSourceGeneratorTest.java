package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

        // Verifies generated source file paths align with declared Java package directory structure.
class TargetPlatformSourceGeneratorTest {

    private final TargetPlatformSourceGenerator generator = new TargetPlatformSourceGenerator();

    private static String bpmn(String activityXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="rc_proxy_process" name="Process_proxy" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    %s
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(activityXml);
    }

    @Test
    void discoversAServiceTaskWithADelegateExpressionAsAProxyDelegate() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="Cutting" name="Cutting"
                    camunda:delegateExpression="${cutting}" />
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        assertThat(result.sources()).hasSize(1);
        TargetPlatformSourceGenerator.GeneratedSource source = result.sources().get(0);
        assertThat(source.relativeDirectory()).isEqualTo("proxy/delegates");
        assertThat(source.className()).isEqualTo("Cutting");
        assertThat(source.source())
                .contains("package com.tp.TargetPlatform.proxy.delegates;")
                .contains("@Component(\"cutting\")")
                .contains("public class Cutting implements JavaDelegate");
        // the bean reference must stay in sync with the id above, whatever it's named
        assertThat(result.bpmnXml()).contains("camunda:delegateExpression=\"${cutting}\"");
    }

    @Test
    void normalisesACamundaClassActivityToADelegateExpressionBean() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="Stitching" name="Stitching"
                    camunda:class="com.legacy.StitchingHandler" />
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().get(0).className()).isEqualTo("Stitching");
        // camunda:class must be gone, replaced by a delegateExpression the generated bean satisfies
        assertThat(result.bpmnXml())
                .doesNotContain("camunda:class")
                .contains("camunda:delegateExpression=\"${stitching}\"");
    }

    @Test
    void discoversASignalCatchEventWithADelegateExpressionAsATwinEvent() {
        String xml = bpmn("""
                <bpmn2:intermediateCatchEvent id="SamplingSignal" name="Sampling Signal"
                    camunda:delegateExpression="${samplingSignal}">
                  <bpmn2:signalEventDefinition />
                </bpmn2:intermediateCatchEvent>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, true);

        assertThat(result.sources()).hasSize(1);
        TargetPlatformSourceGenerator.GeneratedSource source = result.sources().get(0);
        assertThat(source.relativeDirectory()).isEqualTo("twin/events");
        assertThat(source.className()).isEqualTo("SamplingSignal");
        assertThat(source.source())
                .contains("package com.tp.TargetPlatform.twin.events;")
                .contains("TWIN (MSG)");
    }

    @Test
    void anActivityAndAnEventInTheSameBpmnBothGetGenerated() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="Marking" name="Marking"
                    camunda:delegateExpression="${marking}" />
                <bpmn2:intermediateCatchEvent id="MarkingSignal" name="Marking Signal"
                    camunda:delegateExpression="${markingSignal}">
                  <bpmn2:signalEventDefinition />
                </bpmn2:intermediateCatchEvent>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        List<String> directories = result.sources().stream()
                .map(TargetPlatformSourceGenerator.GeneratedSource::relativeDirectory).toList();
        assertThat(directories).containsExactlyInAnyOrder("proxy/delegates", "proxy/events");
    }

    @Test
    void anExecutionListenerDelegateExpressionGetsAStubBeanEvenThoughItsNotOnTheActivityItself() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="Cutting" name="Cutting" camunda:type="external" camunda:topic="Cutting">
                  <bpmn2:extensionElements>
                    <camunda:executionListener event="end" delegateExpression="${manufTaskCompletionListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:serviceTask>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        // the external task itself has no delegateExpression/class of its own, so it must not
        // produce a delegates/events entry - only the listener bean is generated
        assertThat(result.sources()).hasSize(1);
        TargetPlatformSourceGenerator.GeneratedSource source = result.sources().get(0);
        assertThat(source.relativeDirectory()).isEqualTo("proxy/listeners");
        assertThat(source.className()).isEqualTo("ManufTaskCompletionListener");
        assertThat(source.source())
                .contains("package com.tp.TargetPlatform.proxy.listeners;")
                .contains("@Component(\"manufTaskCompletionListener\")")
                .contains("implements ExecutionListener");
        // the BPMN itself is left untouched on the proxy side - unlike the activity/event rewrite
        // above, a listener reference is already a clean bean name and needs no normalisation
        assertThat(result.bpmnXml()).contains("delegateExpression=\"${manufTaskCompletionListener}\"");
    }

        // Verifies generated CamundaConfig enables duplicate filtering to prevent redundant deployments.
    @Test
    void theTwinSidesListenerBeanIsRenamedSoItNeverCollidesWithTheProxysOwnBeanOfTheSameOriginalName() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="CuttingTwin" name="Cutting Twin" camunda:type="external" camunda:topic="CuttingTwin">
                  <bpmn2:extensionElements>
                    <camunda:executionListener event="end" delegateExpression="${manufTaskCompletionListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:serviceTask>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, true);

        assertThat(result.sources()).hasSize(1);
        TargetPlatformSourceGenerator.GeneratedSource source = result.sources().get(0);
        assertThat(source.relativeDirectory()).isEqualTo("twin/listeners");
        assertThat(source.className()).isEqualTo("ManufTaskCompletionListenerTwin");
        assertThat(source.source())
                .contains("package com.tp.TargetPlatform.twin.listeners;")
                .contains("@Component(\"manufTaskCompletionListenerTwin\")");
        // the twin BPMN IS rewritten (unlike the proxy-side case above) - it has to stop pointing
        // at the bare original name, which only the proxy's own bean is now registered under
        assertThat(result.bpmnXml())
                .doesNotContain("delegateExpression=\"${manufTaskCompletionListener}\"")
                .contains("delegateExpression=\"${manufTaskCompletionListenerTwin}\"");
    }

    @Test
    void theSameExecutionListenerBeanReferencedByManyActivitiesOnlyGeneratesOneClass() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="Cutting" name="Cutting" camunda:type="external" camunda:topic="Cutting">
                  <bpmn2:extensionElements>
                    <camunda:executionListener event="end" delegateExpression="${manufTaskCompletionListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:serviceTask>
                <bpmn2:serviceTask id="Marking" name="Marking" camunda:type="external" camunda:topic="Marking">
                  <bpmn2:extensionElements>
                    <camunda:executionListener event="end" delegateExpression="${manufTaskCompletionListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:serviceTask>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        assertThat(result.sources()).hasSize(1);
    }

        // Verifies SignalBroadcaster is generated as a scheduled Spring @Component in signal package.
    @Test
    void discoversATaskListenerDelegateExpressionAsAProxyListenerImplementingTheRightInterface() {
        String xml = bpmn("""
                <bpmn2:userTask id="ApproveOrder" name="Approve Order">
                  <bpmn2:extensionElements>
                    <camunda:taskListener event="complete" delegateExpression="${orderApprovalListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:userTask>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        assertThat(result.sources()).hasSize(1);
        TargetPlatformSourceGenerator.GeneratedSource source = result.sources().get(0);
        assertThat(source.relativeDirectory()).isEqualTo("proxy/listeners");
        assertThat(source.className()).isEqualTo("OrderApprovalListener");
        assertThat(source.source())
                .contains("package com.tp.TargetPlatform.proxy.listeners;")
                .contains("@Component(\"orderApprovalListener\")")
                .contains("implements TaskListener")
                .contains("import org.camunda.bpm.engine.delegate.DelegateTask;")
                .contains("import org.camunda.bpm.engine.delegate.TaskListener;")
                .contains("public void notify(DelegateTask delegateTask)")
                .doesNotContain("ExecutionListener");
        assertThat(result.bpmnXml()).contains("delegateExpression=\"${orderApprovalListener}\"");
    }

        // Verifies GeneratedProcessStatusController exposes GET /status with runtime process details.
    @Test
    void theTwinSidesTaskListenerBeanIsRenamedSoItNeverCollidesWithTheProxysOwnBeanOfTheSameOriginalName() {
        String xml = bpmn("""
                <bpmn2:userTask id="ApproveOrderTwin" name="Approve Order Twin">
                  <bpmn2:extensionElements>
                    <camunda:taskListener event="complete" delegateExpression="${orderApprovalListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:userTask>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, true);

        assertThat(result.sources()).hasSize(1);
        TargetPlatformSourceGenerator.GeneratedSource source = result.sources().get(0);
        assertThat(source.relativeDirectory()).isEqualTo("twin/listeners");
        assertThat(source.className()).isEqualTo("OrderApprovalListenerTwin");
        assertThat(source.source())
                .contains("package com.tp.TargetPlatform.twin.listeners;")
                .contains("@Component(\"orderApprovalListenerTwin\")");
        assertThat(result.bpmnXml())
                .doesNotContain("delegateExpression=\"${orderApprovalListener}\"")
                .contains("delegateExpression=\"${orderApprovalListenerTwin}\"");
    }

    @Test
    void theSameTaskListenerBeanReferencedByManyUserTasksOnlyGeneratesOneClass() {
        String xml = bpmn("""
                <bpmn2:userTask id="ApproveOrder" name="Approve Order">
                  <bpmn2:extensionElements>
                    <camunda:taskListener event="complete" delegateExpression="${orderApprovalListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:userTask>
                <bpmn2:userTask id="ReviewOrder" name="Review Order">
                  <bpmn2:extensionElements>
                    <camunda:taskListener event="create" delegateExpression="${orderApprovalListener}" />
                  </bpmn2:extensionElements>
                </bpmn2:userTask>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        assertThat(result.sources()).hasSize(1);
    }

    // camunda:class and a raw UEL "expression" attribute are both left unrewritten and ungenerated
    // - neither is a form this generator can safely turn into a class (see scanTaskListeners' own
    // comment) - rather than silently claiming support it cannot actually provide.
    @Test
    void aTaskListenerClassOrExpressionFormProducesNoGeneratedSourceRatherThanSilentlyMisgenerating() {
        String xml = bpmn("""
                <bpmn2:userTask id="ApproveOrder" name="Approve Order">
                  <bpmn2:extensionElements>
                    <camunda:taskListener event="complete" class="com.example.SomeTaskListener" />
                    <camunda:taskListener event="create" expression="${someUelExpression}" />
                  </bpmn2:extensionElements>
                </bpmn2:userTask>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        assertThat(result.sources()).isEmpty();
        assertThat(result.bpmnXml())
                .contains("class=\"com.example.SomeTaskListener\"")
                .contains("expression=\"${someUelExpression}\"");
    }
    // When an authored twin reuses the exact same activity ID as its proxy, the generator
    // differentiates the Spring bean names to avoid ConflictingBeanDefinitionException at startup.
    @Test
    void theTwinSidesDelegateBeanIsRenamedSoItNeverCollidesWithTheProxysOwnBeanOfTheSameActivityId() {
        String sharedActivityId = "add_rice";
        String xml = bpmn("""
                <bpmn2:serviceTask id="%s" name="Add Rice"
                    camunda:delegateExpression="${%s}" />
                """.formatted(sharedActivityId, sharedActivityId));

        TargetPlatformSourceGenerator.Result proxyResult = generator.generate(xml, false);
        TargetPlatformSourceGenerator.Result twinResult = generator.generate(xml, true);

        assertThat(proxyResult.sources()).hasSize(1);
        assertThat(twinResult.sources()).hasSize(1);
        TargetPlatformSourceGenerator.GeneratedSource proxySource = proxyResult.sources().get(0);
        TargetPlatformSourceGenerator.GeneratedSource twinSource = twinResult.sources().get(0);

        // Both sides still discover the same activity id and land in their own directory...
        assertThat(proxySource.relativeDirectory()).isEqualTo("proxy/delegates");
        assertThat(twinSource.relativeDirectory()).isEqualTo("twin/delegates");

        // ...but the twin's Spring bean name must be distinct from the proxy's, so both classes
        // can be component-scanned into the same application context without colliding.
        assertThat(proxySource.source()).contains("@Component(\"add_rice\")");
        assertThat(twinSource.source()).contains("@Component(\"add_riceTwin\")");
        assertThat(proxySource.source()).doesNotContain("@Component(\"add_riceTwin\")");

        // Each BPMN's own delegateExpression is rewritten to match its own bean, not the other
        // side's - the twin BPMN must stop pointing at the bare name the proxy bean now owns alone.
        assertThat(proxyResult.bpmnXml()).contains("camunda:delegateExpression=\"${add_rice}\"");
        assertThat(twinResult.bpmnXml())
                .doesNotContain("camunda:delegateExpression=\"${add_rice}\"")
                .contains("camunda:delegateExpression=\"${add_riceTwin}\"");
    }

    @Test
    void anElementWithNeitherClassNorDelegateExpressionIsSkipped() {
        String xml = bpmn("""
                <bpmn2:serviceTask id="Plain" name="Plain, no implementation" />
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        assertThat(result.sources()).isEmpty();
    }

    // ── Human-activity lockstep (P7) ───────────────────────────────────────────

    private static String flowBpmn(String processId, String activityXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="%s" name="Flow" isExecutable="true">
                    <bpmn2:startEvent id="Start"><bpmn2:outgoing>f0</bpmn2:outgoing></bpmn2:startEvent>
                    <bpmn2:sequenceFlow id="f0" sourceRef="Start" targetRef="StepOne" />
                    %s
                    <bpmn2:sequenceFlow id="f1" sourceRef="StepOne" targetRef="End" />
                    <bpmn2:endEvent id="End"><bpmn2:incoming>f1</bpmn2:incoming></bpmn2:endEvent>
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(processId, activityXml);
    }

    // A human task carries no delegateExpression, so the delegate scan skips it. It must still be
    // gated: the Twin mirrors it as an automated task, and without a rendezvous the Twin would run
    // that activity - and every later one - while the person had not yet finished this one.
    @Test
    void aProxyUserTaskIsGatedBySyncSignalEvenThoughItHasNoDelegate() {
        String xml = flowBpmn("proxy_process", """
                <bpmn2:userTask id="StepOne" name="Step One">
                  <bpmn2:incoming>f0</bpmn2:incoming><bpmn2:outgoing>f1</bpmn2:outgoing>
                </bpmn2:userTask>
                """);

        TargetPlatformSourceGenerator.Result result = generator.generate(xml, false);

        assertThat(result.sources()).as("a userTask still gets no generated delegate").isEmpty();
        assertThat(result.syncSignalNames()).containsExactly("sync_StepOne");
        assertThat(result.syncActivityIds()).containsExactly("StepOne");
        // The catch event goes AFTER the human task: the Proxy parks only once the task is done.
        assertThat(result.bpmnXml())
                .contains("sourceRef=\"StepOne\" targetRef=\"sync_evt_StepOne\"")
                .contains("sourceRef=\"sync_evt_StepOne\" targetRef=\"End\"");
    }

    // manualTask and a bare task are mirrored into automated Twin activities too, so they are gated
    // on the same rule - by BPMN element type, not by anything about the model's subject matter.
    @Test
    void manualAndInertProxyTasksAreGatedOnTheSameRule() {
        assertThat(generator.generate(flowBpmn("proxy_process", """
                <bpmn2:manualTask id="StepOne"><bpmn2:incoming>f0</bpmn2:incoming>
                  <bpmn2:outgoing>f1</bpmn2:outgoing></bpmn2:manualTask>
                """), false).syncSignalNames()).containsExactly("sync_StepOne");
        assertThat(generator.generate(flowBpmn("proxy_process", """
                <bpmn2:task id="StepOne"><bpmn2:incoming>f0</bpmn2:incoming>
                  <bpmn2:outgoing>f1</bpmn2:outgoing></bpmn2:task>
                """), false).syncSignalNames()).containsExactly("sync_StepOne");
    }

    // The mirrored Twin has no receiveTask to rewrite - the mirror produced a serviceTask under the
    // same activity id - so the rendezvous is inserted in FRONT of it. That ordering is the whole
    // invariant: the Twin cannot execute activity N until the signal for N arrives.
    @Test
    void aMirroredTwinActivityGetsItsSyncCatchEventInsertedBeforeIt() {
        String twinXml = flowBpmn("proxy_process_twin", """
                <bpmn2:serviceTask id="StepOne" name="Step One"
                    camunda:delegateExpression="${stepOne}">
                  <bpmn2:incoming>f0</bpmn2:incoming><bpmn2:outgoing>f1</bpmn2:outgoing>
                </bpmn2:serviceTask>
                """);

        TargetPlatformSourceGenerator.Result result =
                generator.generate(twinXml, true, java.util.Set.of("StepOne"));

        assertThat(result.syncSignalNames()).containsExactly("sync_StepOne");
        assertThat(result.bpmnXml())
                .contains("sourceRef=\"Start\" targetRef=\"sync_evt_StepOne\"")
                .contains("sourceRef=\"sync_evt_StepOne\" targetRef=\"StepOne\"");
    }

    // An authored Twin that models its own wait state as a receiveTask keeps the pre-existing
    // in-place rewrite, and must not additionally get an inserted catch event for the same activity.
    @Test
    void anAuthoredTwinReceiveTaskIsStillRewrittenInPlaceAndNotDuplicated() {
        String twinXml = flowBpmn("proxy_process_twin", """
                <bpmn2:receiveTask id="StepOne" name="Step One">
                  <bpmn2:incoming>f0</bpmn2:incoming><bpmn2:outgoing>f1</bpmn2:outgoing>
                </bpmn2:receiveTask>
                """);

        TargetPlatformSourceGenerator.Result result =
                generator.generate(twinXml, true, java.util.Set.of("StepOne"));

        assertThat(result.syncSignalNames()).containsExactly("sync_StepOne");
        assertThat(result.bpmnXml()).doesNotContain("receiveTask");
        assertThat(result.bpmnXml()).doesNotContain("sync_evt_StepOne");
        // exactly one signal declaration for this activity, not one per insertion path
        assertThat(result.bpmnXml().split("name=\"sync_StepOne\"", -1)).hasSize(2);
    }

    // A Twin that mirrors only part of the Proxy must not fail generation: the unmatched signal is a
    // case SignalBroadcaster already handles, not a generation error.
    @Test
    void aProxyActivityTheTwinDoesNotMirrorIsSkippedRatherThanFailing() {
        String twinXml = flowBpmn("proxy_process_twin", """
                <bpmn2:serviceTask id="StepOne" camunda:delegateExpression="${stepOne}">
                  <bpmn2:incoming>f0</bpmn2:incoming><bpmn2:outgoing>f1</bpmn2:outgoing>
                </bpmn2:serviceTask>
                """);

        TargetPlatformSourceGenerator.Result result =
                generator.generate(twinXml, true, new java.util.LinkedHashSet<>(
                        java.util.List.of("StepOne", "NotMirroredHere")));

        assertThat(result.syncSignalNames()).containsExactly("sync_StepOne");
    }
}
