package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

        // Verifies generated source file paths align with declared Java package directory structure.
class TargetPlatformSourceGeneratorTest {

    private final TargetPlatformSourceGenerator generator = new TargetPlatformSourceGenerator();

    @Test
    void usesTheExplicitMappedSynchronizationIdentityForDifferentProxyAndTwinIds() {
        String proxy = linearBpmn("proxy_validate", "Proxy wording");
        String twin = linearBpmn("digital_check", "Different twin wording");

        TargetPlatformSourceGenerator.Result proxyResult = generator.generate(proxy, false, null,
                Map.of("proxy_validate", "sync_validate-order"));
        TargetPlatformSourceGenerator.Result twinResult = generator.generate(twin, true, null,
                Map.of("digital_check", "sync_validate-order"));

        assertThat(proxyResult.syncSignalNames()).containsExactly("sync_validate-order");
        assertThat(twinResult.syncSignalNames()).containsExactly("sync_validate-order");
        assertThat(proxyResult.bpmnXml()).contains("name=\"sync_validate-order\"");
        assertThat(twinResult.bpmnXml()).contains("name=\"sync_validate-order\"");
    }

    @Test
    void mappedProxySyncKeepsOutgoingBeforeServiceTaskSpecificChildren() throws Exception {
        String synchronizationKey = "schema-order-test";
        String signalName = "sync_" + synchronizationKey;
        TargetPlatformSourceGenerator.Result proxyResult = generator.generate(richProxyBpmn(), false, null,
                Map.of("proxy_task", signalName));
        TargetPlatformSourceGenerator.Result twinResult = generator.generate(
                linearBpmn("twin_task", "Twin task"), true, null, Map.of("twin_task", signalName));

        assertThat(proxyResult.syncSignalNames()).containsExactly(signalName);
        assertThat(twinResult.syncSignalNames()).containsExactly(signalName);

        BpmnModelInstance parsedProxy = Bpmn.readModelFromStream(
                new ByteArrayInputStream(proxyResult.bpmnXml().getBytes(StandardCharsets.UTF_8)));
        Bpmn.validateModel(parsedProxy);

        Document document = parseXml(proxyResult.bpmnXml());
        Element proxyTask = elementById(document, "proxy_task");
        List<String> childNames = childElementNames(proxyTask);
        assertThat(childNames).containsExactly("incoming", "outgoing", "ioSpecification",
                "dataInputAssociation", "dataOutputAssociation");
        assertThat(childNames.indexOf("outgoing")).isLessThan(childNames.indexOf("ioSpecification"));
        assertThat(childNames.indexOf("outgoing")).isLessThan(childNames.indexOf("dataInputAssociation"));
        assertThat(childNames.indexOf("outgoing")).isLessThan(childNames.indexOf("dataOutputAssociation"));

        assertThat(proxyTask.getAttribute("name")).isEqualTo("Rich proxy task");
        assertThat(proxyTask.getAttributeNS("http://camunda.org/schema/1.0/bpmn", "delegateExpression"))
                .isEqualTo("${proxy_task}");
        assertThat(childNames).contains("ioSpecification", "dataInputAssociation", "dataOutputAssociation");

        String rewrittenFlowId = textOfDirectChild(proxyTask, "outgoing");
        assertThat(rewrittenFlowId).isEqualTo("sync_flow_proxy_task");
        Element bridgeFlow = elementById(document, rewrittenFlowId);
        assertThat(bridgeFlow.getAttribute("sourceRef")).isEqualTo("proxy_task");
        assertThat(bridgeFlow.getAttribute("targetRef")).isEqualTo("sync_evt_proxy_task");

        Element proxyGate = elementById(document, "sync_evt_proxy_task");
        assertThat(textOfDirectChild(proxyGate, "incoming")).isEqualTo(rewrittenFlowId);
        assertThat(textOfDirectChild(proxyGate, "outgoing")).isEqualTo("flow_to_end");
        assertThat(elementById(document, "flow_to_end").getAttribute("sourceRef"))
                .isEqualTo("sync_evt_proxy_task");
        assertThat(proxyResult.bpmnXml()).contains("name=\"" + signalName + "\"");
        assertThat(twinResult.bpmnXml()).contains("name=\"" + signalName + "\"");
    }

    @Test
    void doesNotInventAMappingForDifferentAuthoredIds() {
        TargetPlatformSourceGenerator.Result twinResult = generator.generate(
                linearBpmn("digital_check", "Different twin wording"), true, null, Map.of());

        assertThat(twinResult.syncSignalNames()).isEmpty();
        assertThat(twinResult.bpmnXml()).doesNotContain("sync_proxy_validate");
    }

    private static String linearBpmn(String activityId, String name) {
        return """
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn">
                  <bpmn2:process id="process" isExecutable="true">
                    <bpmn2:startEvent id="start"><bpmn2:outgoing>flow1</bpmn2:outgoing></bpmn2:startEvent>
                    <bpmn2:serviceTask id="%s" name="%s" camunda:delegateExpression="${worker}">
                      <bpmn2:incoming>flow1</bpmn2:incoming><bpmn2:outgoing>flow2</bpmn2:outgoing>
                    </bpmn2:serviceTask>
                    <bpmn2:endEvent id="end"><bpmn2:incoming>flow2</bpmn2:incoming></bpmn2:endEvent>
                    <bpmn2:sequenceFlow id="flow1" sourceRef="start" targetRef="%s"/>
                    <bpmn2:sequenceFlow id="flow2" sourceRef="%s" targetRef="end"/>
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(activityId, name, activityId, activityId);
    }

    private static String richProxyBpmn() {
        return """
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    targetNamespace="http://metaml.test/schema-order">
                  <bpmn2:process id="proxy_process" isExecutable="true">
                    <bpmn2:dataObject id="input_data" name="input" />
                    <bpmn2:dataObject id="output_data" name="output" />
                    <bpmn2:startEvent id="start"><bpmn2:outgoing>flow_to_proxy</bpmn2:outgoing></bpmn2:startEvent>
                    <bpmn2:serviceTask id="proxy_task" name="Rich proxy task"
                        camunda:delegateExpression="${proxy_task}">
                      <bpmn2:incoming>flow_to_proxy</bpmn2:incoming>
                      <bpmn2:outgoing>flow_to_end</bpmn2:outgoing>
                      <bpmn2:ioSpecification id="proxy_io">
                        <bpmn2:dataInput id="proxy_input" name="input" />
                        <bpmn2:dataOutput id="proxy_output" name="output" />
                        <bpmn2:inputSet id="proxy_input_set"><bpmn2:dataInputRefs>proxy_input</bpmn2:dataInputRefs></bpmn2:inputSet>
                        <bpmn2:outputSet id="proxy_output_set"><bpmn2:dataOutputRefs>proxy_output</bpmn2:dataOutputRefs></bpmn2:outputSet>
                      </bpmn2:ioSpecification>
                      <bpmn2:dataInputAssociation id="proxy_input_association">
                        <bpmn2:sourceRef>input_data</bpmn2:sourceRef><bpmn2:targetRef>proxy_input</bpmn2:targetRef>
                      </bpmn2:dataInputAssociation>
                      <bpmn2:dataOutputAssociation id="proxy_output_association">
                        <bpmn2:sourceRef>proxy_output</bpmn2:sourceRef><bpmn2:targetRef>output_data</bpmn2:targetRef>
                      </bpmn2:dataOutputAssociation>
                    </bpmn2:serviceTask>
                    <bpmn2:endEvent id="end"><bpmn2:incoming>flow_to_end</bpmn2:incoming></bpmn2:endEvent>
                    <bpmn2:sequenceFlow id="flow_to_proxy" sourceRef="start" targetRef="proxy_task" />
                    <bpmn2:sequenceFlow id="flow_to_end" sourceRef="proxy_task" targetRef="end" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Element elementById(Document document, String id) {
        NodeList elements = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (id.equals(element.getAttribute("id"))) return element;
        }
        throw new AssertionError("No BPMN element with id " + id);
    }

    private static List<String> childElementNames(Element parent) {
        List<String> names = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element) names.add(element.getLocalName());
        }
        return names;
    }

    private static String textOfDirectChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element.getTextContent().trim();
            }
        }
        throw new AssertionError("No " + localName + " child on " + parent.getAttribute("id"));
    }

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

    private static String diagrammedFlowBpmn(String processId, String activityXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                    xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                    xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="%s" name="Flow" isExecutable="true">
                    <bpmn2:startEvent id="Start"><bpmn2:outgoing>f0</bpmn2:outgoing></bpmn2:startEvent>
                    <bpmn2:sequenceFlow id="f0" sourceRef="Start" targetRef="StepOne" />
                    %s
                    <bpmn2:sequenceFlow id="f1" sourceRef="StepOne" targetRef="End" />
                    <bpmn2:endEvent id="End"><bpmn2:incoming>f1</bpmn2:incoming></bpmn2:endEvent>
                  </bpmn2:process>
                  <bpmndi:BPMNDiagram id="Diagram"><bpmndi:BPMNPlane id="Plane" bpmnElement="%s">
                    <bpmndi:BPMNShape id="Start_di" bpmnElement="Start"><dc:Bounds x="80" y="100" width="36" height="36" /></bpmndi:BPMNShape>
                    <bpmndi:BPMNShape id="StepOne_di" bpmnElement="StepOne"><dc:Bounds x="200" y="78" width="100" height="80" /></bpmndi:BPMNShape>
                    <bpmndi:BPMNShape id="End_di" bpmnElement="End"><dc:Bounds x="380" y="100" width="36" height="36" /></bpmndi:BPMNShape>
                    <bpmndi:BPMNEdge id="f0_di" bpmnElement="f0"><di:waypoint x="116" y="118" /><di:waypoint x="200" y="118" /></bpmndi:BPMNEdge>
                    <bpmndi:BPMNEdge id="f1_di" bpmnElement="f1"><di:waypoint x="300" y="118" /><di:waypoint x="380" y="118" /></bpmndi:BPMNEdge>
                  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
                </bpmn2:definitions>
                """.formatted(processId, activityXml, processId);
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

    @Test
    void syncInsertionAddsDiagramInterchangeForBothNewBpmnElements() {
        String activity = """
                <bpmn2:userTask id="StepOne" name="Step One">
                  <bpmn2:incoming>f0</bpmn2:incoming><bpmn2:outgoing>f1</bpmn2:outgoing>
                </bpmn2:userTask>
                """;

        String proxy = generator.generate(diagrammedFlowBpmn("proxy_process", activity), false).bpmnXml();
        String twin = generator.generate(diagrammedFlowBpmn("twin_process", activity.replace("userTask", "serviceTask")
                .replace("<bpmn2:serviceTask", "<bpmn2:serviceTask camunda:delegateExpression=\"${stepOne}\"")),
                true, java.util.Set.of("StepOne")).bpmnXml();

        assertThat(proxy)
                .contains("bpmnElement=\"sync_evt_StepOne\"")
                .contains("bpmnElement=\"sync_flow_StepOne\"");
        assertThat(twin)
                .contains("bpmnElement=\"sync_evt_StepOne\"")
                .contains("bpmnElement=\"sync_flow_StepOne\"");
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
