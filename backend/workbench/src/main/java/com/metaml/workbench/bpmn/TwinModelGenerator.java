package com.metaml.workbench.bpmn;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.builder.AbstractFlowNodeBuilder;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent;
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask;
import org.camunda.bpm.model.bpmn.instance.CallActivity;
import org.camunda.bpm.model.bpmn.instance.ConditionExpression;
import org.camunda.bpm.model.bpmn.instance.EndEvent;
import org.camunda.bpm.model.bpmn.instance.EventBasedGateway;
import org.camunda.bpm.model.bpmn.instance.EventDefinition;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.camunda.bpm.model.bpmn.instance.IntermediateThrowEvent;
import org.camunda.bpm.model.bpmn.instance.LoopCardinality;
import org.camunda.bpm.model.bpmn.instance.ManualTask;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.camunda.bpm.model.bpmn.instance.ParallelGateway;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.ReceiveTask;
import org.camunda.bpm.model.bpmn.instance.ScriptTask;
import org.camunda.bpm.model.bpmn.instance.SendTask;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.SubProcess;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnEdge;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnPlane;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnShape;
import org.camunda.bpm.model.xml.instance.DomDocument;
import org.camunda.bpm.model.xml.instance.DomElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Keeps twin advancement synchronized by pairing each user task with a receive and service task.
@Component
public class TwinModelGenerator {

    private static final Logger logger = LoggerFactory.getLogger(TwinModelGenerator.class);

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String BPMN_PREFIX = "bpmn";
    private static final String METAML_NAMESPACE = "http://metaml.com/schema/bpmn/metaml";
    private static final String METAML_PREFIX = "metaml";
    private static final String EXTENSION_ELEMENTS_NAME = "extensionElements";
    private static final String ID_ATTRIBUTE = "id";

    private static final String TWIN_PROCESS_ID_SUFFIX = "_twin";
    private static final String TWIN_PROCESS_NAME_SUFFIX = " (twin)";
    private static final String TWIN_DELEGATE_EXPRESSION = "${twinAutomationDelegate}";
    private static final String TWIN_MESSAGE_PREFIX = "TwinAdvance_";

    private static final String AUTOMATION_TASK_ID_SUFFIX = "_automate";
    private static final String WRAPPER_ID_SUFFIX = "_sync";
    private static final String WRAPPER_START_ID_SUFFIX = "_sync_start";
    private static final String WRAPPER_END_ID_SUFFIX = "_sync_end";
    private static final String FLOW_TO_AUTOMATION_SUFFIX = "_to_automate";
    private static final String WRAPPER_START_FLOW_SUFFIX = "_sync_start_flow";
    private static final String WRAPPER_END_FLOW_SUFFIX = "_sync_end_flow";

    private static final String DEFINITIONS_ID_PREFIX = "Definitions_";
    private static final String CONDITION_EXPRESSION_ID_PREFIX = "ConditionExpression_";
    private static final String MESSAGE_ID_PREFIX = "Message_";
    private static final String MI_LOOP_CHARACTERISTICS_ID_PREFIX = "MultiInstance_";
    private static final String MI_CARDINALITY_ID_PREFIX = "LoopCardinality_";
    private static final String DI_SHAPE_ID_PREFIX = "BPMNShape_";
    private static final String DI_EDGE_ID_PREFIX = "BPMNEdge_";
    private static final String DI_PLANE_ID_PREFIX = "BPMNPlane_";
    private static final String DI_DIAGRAM_ID_PREFIX = "BPMNDiagram_";

    // original activity ids ending in any of these would collide with their own derived twin ids
    private static final List<String> RESERVED_ID_SUFFIXES = List.of(AUTOMATION_TASK_ID_SUFFIX,
            WRAPPER_ID_SUFFIX, WRAPPER_START_ID_SUFFIX, WRAPPER_END_ID_SUFFIX, FLOW_TO_AUTOMATION_SUFFIX,
            WRAPPER_START_FLOW_SUFFIX, WRAPPER_END_FLOW_SUFFIX);

    public static String twinMessageName(String twinActivityId) {
        return TWIN_MESSAGE_PREFIX + twinActivityId;
    }

    // Derived from the synchronization point's id so TwinAutomationDelegate can recover it.
    public static String automationTaskId(String twinActivityId) {
        return twinActivityId + AUTOMATION_TASK_ID_SUFFIX;
    }

    // getCurrentActivityId() returns the automation task's id; recovers the receive task's id that variables key off.
    public static String synchronizationActivityIdOf(String automationTaskId) {
        return automationTaskId.endsWith(AUTOMATION_TASK_ID_SUFFIX)
                ? automationTaskId.substring(0, automationTaskId.length() - AUTOMATION_TASK_ID_SUFFIX.length())
                : automationTaskId;
    }

    // moveToNode() returns raw type; type parameters are lost after any non-forward step
    @SuppressWarnings("rawtypes")
    public BpmnModelInstance generate(BpmnModelInstance original) {
        Process process = executableProcessOf(original);
        StartEvent start = plainStartEventOf(process);
        rejectReservedIdSuffixes(process);

        List<SequenceFlow> flows = copyableFlows(process);
        Set<String> copied = new LinkedHashSet<>();
        copied.add(start.getId());

        AbstractFlowNodeBuilder cursor = Bpmn.createExecutableProcess(twinProcessId(process))
                .name(twinProcessName(process))
                .startEvent(start.getId());
        Set<String> subProcessWrapped = subProcessWrappedActivityIds(process);
        cursor = copyGraph(cursor, flows, copied, process.getId(), subProcessWrapped,
                syncThenAutomateActivityIds(process, subProcessWrapped));

        BpmnModelInstance twin = cursor.done();
        twin.getDocument().registerNamespace(
                BPMN_PREFIX,
                org.camunda.bpm.model.bpmn.impl.BpmnModelConstants.BPMN20_NS);
        twin.getDocument().registerNamespace(METAML_PREFIX, METAML_NAMESPACE);
        twin.getDefinitions().setId(DEFINITIONS_ID_PREFIX + twinProcessId(process));
        stabilizeMessageIds(twin);
        stabilizeMultiInstanceIds(twin);

        // names, conditions and defaults applied after construction; builder never exposes the flow mid-chain
        copyNodeNames(original, twin, copied);
        copyFlowDetails(twin, flows);
        copyDefaultFlows(process, twin);
        copyConstructDetails(original, twin, copied);
        copyTopLevelDefinitions(original, twin);
        copyMetamlExtensions(original, twin, process, copied);
        stabilizeDiagramInterchange(twin);

        return twin;
    }

    private static void copyTopLevelDefinitions(BpmnModelInstance original, BpmnModelInstance twin) {
        Document sourceDoc = (Document) original.getDocument().getDomSource().getNode();
        Document twinDoc = (Document) twin.getDocument().getDomSource().getNode();
        Element twinRoot = twinDoc.getDocumentElement();

        // Locate first process or diagram element to insert before, ensuring schema validity
        Node refNode = null;
        NodeList twinChildren = twinRoot.getChildNodes();
        for (int i = 0; i < twinChildren.getLength(); i++) {
            Node child = twinChildren.item(i);
            if (child instanceof Element el) {
                String local = el.getLocalName();
                if ("process".equals(local) || "BPMNDiagram".equals(local)) {
                    refNode = child;
                    break;
                }
            }
        }

        NodeList rootChildren = sourceDoc.getDocumentElement().getChildNodes();
        for (int i = 0; i < rootChildren.getLength(); i++) {
            if (rootChildren.item(i) instanceof Element childEl) {
                String localName = childEl.getLocalName();
                if ("signal".equals(localName) || "message".equals(localName)
                        || "error".equals(localName) || "escalation".equals(localName)) {
                    String id = childEl.getAttribute("id");
                    if (!isBlank(id) && twin.getModelElementById(id) == null) {
                        Node imported = twinDoc.importNode(childEl, true);
                        if (refNode != null) {
                            twinRoot.insertBefore(imported, refNode);
                        } else {
                            twinRoot.appendChild(imported);
                        }
                    }
                }
            }
        }
    }

    private static void stabilizeMessageIds(BpmnModelInstance twin) {
        for (org.camunda.bpm.model.bpmn.instance.Message message
                : twin.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Message.class)) {
            String oldId = message.getId();
            String newId = MESSAGE_ID_PREFIX + message.getName();
            if (oldId != null && !oldId.equals(newId)) {
                message.setId(newId);
                for (org.camunda.bpm.model.bpmn.instance.MessageEventDefinition def
                        : twin.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.MessageEventDefinition.class)) {
                    if (def.getMessage() != null && oldId.equals(def.getMessage().getId())) {
                        def.setMessage(message);
                    }
                }
            }
        }
    }

    // Same non-determinism as messages; defeats enableDuplicateFiltering on multi-instance models.
    private static void stabilizeMultiInstanceIds(BpmnModelInstance twin) {
        for (MultiInstanceLoopCharacteristics loop : new ArrayList<>(
                twin.getModelElementsByType(MultiInstanceLoopCharacteristics.class))) {
            if (!(loop.getParentElement() instanceof BaseElement owner)) {
                continue;
            }
            loop.setId(MI_LOOP_CHARACTERISTICS_ID_PREFIX + owner.getId());
            LoopCardinality cardinality = loop.getLoopCardinality();
            if (cardinality != null) {
                cardinality.setId(MI_CARDINALITY_ID_PREFIX + owner.getId());
            }
        }
    }

    // Reserved suffixes collide with derived twin ids; reject up front to avoid an opaque deploy failure.
    private static void rejectReservedIdSuffixes(Process process) {
        for (UserTask task : process.getChildElementsByType(UserTask.class)) {
            for (String suffix : RESERVED_ID_SUFFIXES) {
                if (task.getId().endsWith(suffix)) {
                    throw new IllegalArgumentException("Activity id '" + task.getId() + "' ends with '"
                            + suffix + "', which the twin generator reserves for its own derived ids; "
                            + "rename the activity in the original model to build a twin from it");
                }
            }
        }
    }

    // Derives stable BPMNDI element identifiers for Cockpit visualization.
    private static void stabilizeDiagramInterchange(BpmnModelInstance twin) {
        for (BpmnShape shape : twin.getModelElementsByType(BpmnShape.class)) {
            BaseElement depicted = shape.getBpmnElement();
            if (depicted != null) {
                shape.setId(DI_SHAPE_ID_PREFIX + depicted.getId());
            }
        }
        for (BpmnEdge edge : twin.getModelElementsByType(BpmnEdge.class)) {
            BaseElement depicted = edge.getBpmnElement();
            if (depicted != null) {
                edge.setId(DI_EDGE_ID_PREFIX + depicted.getId());
            }
        }
        for (BpmnPlane plane : twin.getModelElementsByType(BpmnPlane.class)) {
            BaseElement depicted = plane.getBpmnElement();
            plane.setId(DI_PLANE_ID_PREFIX + (depicted == null ? "" : depicted.getId()));
        }
        for (BpmnDiagram diagram : twin.getModelElementsByType(BpmnDiagram.class)) {
            BpmnPlane plane = diagram.getBpmnPlane();
            BaseElement depicted = plane == null ? null : plane.getBpmnElement();
            diagram.setId(DI_DIAGRAM_ID_PREFIX + (depicted == null ? "" : depicted.getId()));
        }
    }

    private static Process executableProcessOf(BpmnModelInstance model) {
        List<Process> executable = model.getModelElementsByType(Process.class).stream()
                .filter(Process::isExecutable)
                .toList();
        if (executable.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one executable bpmn:process to build a twin "
                    + "from, found " + executable.size());
        }
        return executable.get(0);
    }

    // message/timer start events would require triggering the twin the same way, not implemented
    private static StartEvent plainStartEventOf(Process process) {
        List<StartEvent> starts = process.getChildElementsByType(StartEvent.class).stream()
                .filter(event -> event.getEventDefinitions().isEmpty())
                .toList();
        if (starts.isEmpty()) {
            throw new IllegalArgumentException("Cannot build a twin for process " + process.getId()
                    + ": it has no plain start event");
        }
        if (starts.size() > 1) {
            logger.warn("Process {} has {} plain start events; the twin is built from {} and the rest are "
                    + "left out", process.getId(), starts.size(), starts.get(0).getId());
        }
        return starts.get(0);
    }

    // Boundary timers on the twin fire on their own clock; dropped here with their outgoing flows.
    private static List<SequenceFlow> copyableFlows(Process process) {
        List<SequenceFlow> flows = new ArrayList<>();
        for (SequenceFlow flow : process.getChildElementsByType(SequenceFlow.class)) {
            if (flow.getSource() instanceof BoundaryEvent boundary) {
                logger.info("Twin of process {} leaves out boundary event {} and its flow {}: the twin's "
                        + "own copy would fire on its own clock instead of following the original, "
                        + "which is the twin diverging from the thing it is supposed to mirror",
                        process.getId(), boundary.getId(), flow.getId());
                continue;
            }
            flows.add(flow);
        }
        return flows;
    }

    // Repeated passes because document order puts a join's second input before its branch exists.
    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder copyGraph(AbstractFlowNodeBuilder cursor,
            List<SequenceFlow> flows, Set<String> copied, String processId, Set<String> subProcessWrapped,
            Set<String> syncThenAutomateIds) {
        List<SequenceFlow> pending = new ArrayList<>(flows);
        boolean madeProgress = true;
        while (madeProgress && !pending.isEmpty()) {
            madeProgress = false;
            for (Iterator<SequenceFlow> pass = pending.iterator(); pass.hasNext();) {
                SequenceFlow flow = pass.next();
                FlowNode source = flow.getSource();
                FlowNode target = flow.getTarget();
                if (source == null || target == null || !copied.contains(source.getId())) {
                    continue;
                }
                String moveToId = exitNodeId(source.getId(), subProcessWrapped, syncThenAutomateIds);
                if (copied.contains(target.getId())) {
                    pass.remove();
                    madeProgress = true;
                    cursor = cursor.moveToNode(moveToId)
                            .sequenceFlowId(flow.getId())
                            .connectTo(target.getId());
                    continue;
                }
                // Hard failure, not silent drop: a twin with a hole is worse than one that fails to generate.
                if (!isSupported(target)) {
                    throw new IllegalArgumentException("Cannot build a twin for process " + processId
                            + ": activity " + target.getId() + " is a " + target.getElementType().getTypeName()
                            + ", which the twin generator does not support. Remove it from the original "
                            + "model, or extend the generator to handle it, before building a twin.");
                }
                pass.remove();
                madeProgress = true;
                cursor = append(cursor.moveToNode(moveToId).sequenceFlowId(flow.getId()), target);
                copied.add(target.getId());
            }
        }
        for (SequenceFlow unreachable : pending) {
            logger.info("Twin leaves out flow {}: nothing reachable from the start event leads into it",
                    unreachable.getId());
        }
        return cursor;
    }

    // moveToNode() is scope-blind; later flows must exit from the wrapper, not the nested receive
    // task - and, for an activity converted into a plain (non-wrapped) receive/automation pair, from
    // the automation task, not the receive task. A flow leaving from the receive task would become
    // enabled as soon as the receive task itself completes, concurrently with (and possibly before)
    // the automation task actually running and publishing the activity's declared output - so a
    // downstream gateway whose condition depends on that output could evaluate it before it exists.
    // Exiting from the automation task instead means the flow is only enabled once that task - and
    // therefore the capability output boundary that runs inside it (see TwinAutomationDelegate /
    // CapabilityOutputPropagator) - has already completed.
    private static String exitNodeId(String activityId, Set<String> subProcessWrapped,
            Set<String> syncThenAutomateIds) {
        if (subProcessWrapped.contains(activityId)) {
            return wrapperId(activityId);
        }
        if (syncThenAutomateIds.contains(activityId)) {
            return automationTaskId(activityId);
        }
        return activityId;
    }

    // Every activity id the twin represents as a receive-task/automation-task pair (see
    // append()/appendSynchronizedActivity()/appendSyncThenAutomate() above) without a multi-instance
    // subprocess wrapper - i.e. every activity a later flow must exit from the automation task for,
    // per exitNodeId's javadoc above. Mirrors that dispatch logic's own criteria exactly: a
    // non-wrapped UserTask, any ReceiveTask or ServiceTask on the original model (both always become
    // receive/automation pairs - see append()), or a bare BusinessRuleTask (isBareBusinessRuleTask).
    // subProcessWrapped is subtracted first so a multi-instance-wrapped UserTask keeps exiting from
    // its wrapper, exactly as it already did.
    private static Set<String> syncThenAutomateActivityIds(Process process, Set<String> subProcessWrapped) {
        Set<String> ids = new HashSet<>();
        for (UserTask task : process.getChildElementsByType(UserTask.class)) {
            if (!subProcessWrapped.contains(task.getId())) {
                ids.add(task.getId());
            }
        }
        for (ReceiveTask task : process.getChildElementsByType(ReceiveTask.class)) {
            ids.add(task.getId());
        }
        for (ServiceTask task : process.getChildElementsByType(ServiceTask.class)) {
            ids.add(task.getId());
        }
        for (BusinessRuleTask rule : process.getChildElementsByType(BusinessRuleTask.class)) {
            if (isBareBusinessRuleTask(rule)) {
                ids.add(rule.getId());
            }
        }
        return ids;
    }

    private static Set<String> subProcessWrappedActivityIds(Process process) {
        Set<String> wrapped = new HashSet<>();
        for (UserTask task : process.getChildElementsByType(UserTask.class)) {
            if (!(task.getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics loop)) {
                continue;
            }
            LoopCardinality cardinality = loop.getLoopCardinality();
            String cardinalityText = cardinality == null ? null : cardinality.getTextContent();
            if (!isBlank(cardinalityText) && LITERAL_CARDINALITY.matcher(cardinalityText.trim()).matches()) {
                wrapped.add(task.getId());
            }
        }
        return wrapped;
    }

    // Supported flow node types for Twin process transformation (Tasks, CallActivities, Gateways).
    private static boolean isSupported(FlowNode node) {
        if (node instanceof UserTask || node instanceof ReceiveTask || node instanceof ServiceTask
                || node instanceof SendTask || node instanceof ManualTask || node instanceof ScriptTask
                || node instanceof BusinessRuleTask || node instanceof CallActivity || node instanceof SubProcess
                || node instanceof ExclusiveGateway || node instanceof ParallelGateway
                || node instanceof InclusiveGateway || node instanceof EventBasedGateway
                || node instanceof IntermediateCatchEvent || node instanceof IntermediateThrowEvent
                || node instanceof BoundaryEvent) {
            return true;
        }
        return node instanceof EndEvent;
    }

    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder append(AbstractFlowNodeBuilder at, FlowNode node) {
        String id = node.getId();
        if (node instanceof UserTask task) {
            return appendSynchronizedActivity(at, task);
        }
        // Transformed into twin receive-automation pairs to maintain lockstep without re-executing delegates.
        if (node instanceof ReceiveTask || node instanceof ServiceTask) {
            return appendSyncThenAutomate(at, id);
        }
        if (node instanceof ExclusiveGateway) {
            return at.exclusiveGateway(id);
        }
        if (node instanceof ParallelGateway) {
            return at.parallelGateway(id);
        }
        if (node instanceof InclusiveGateway) {
            return at.inclusiveGateway(id);
        }
        // Sets stable ID immediately after creating EventBasedGateway element.
        if (node instanceof EventBasedGateway) {
            return at.eventBasedGateway().id(id);
        }
        if (node instanceof IntermediateCatchEvent) {
            return at.intermediateCatchEvent(id);
        }
        if (node instanceof IntermediateThrowEvent) {
            return at.intermediateThrowEvent(id);
        }
        if (node instanceof SendTask) {
            return at.sendTask(id);
        }
        if (node instanceof ManualTask) {
            return at.manualTask(id);
        }
        if (node instanceof ScriptTask) {
            return at.scriptTask(id);
        }
        if (node instanceof BusinessRuleTask rule) {
            if (isBareBusinessRuleTask(rule)) {
                return appendSyncThenAutomate(at, id);
            }
            return at.businessRuleTask(id);
        }
        if (node instanceof CallActivity) {
            return at.callActivity(id);
        }
        if (node instanceof SubProcess) {
            return at.subProcess(id);
        }
        return at.endEvent(id);
    }

    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder appendSynchronizedActivity(AbstractFlowNodeBuilder at, UserTask task) {
        String id = task.getId();
        if (task.getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics loop) {
            return appendMultiInstanceSynchronizedActivity(at, id, loop);
        }
        if (task.getLoopCharacteristics() != null) {
            logger.warn("Twin activity {} runs once: the generator only carries multi-instance "
                    + "characteristics over, not a standard loop", id);
        }
        return appendSyncThenAutomate(at, id);
    }

    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder appendSyncThenAutomate(AbstractFlowNodeBuilder at, String id) {
        return at.receiveTask(id)
                .message(twinMessageName(id))
                .sequenceFlowId(flowToAutomationId(id))
                .serviceTask(automationTaskId(id))
                .camundaDelegateExpression(TWIN_DELEGATE_EXPRESSION);
    }

    private static boolean isBareBusinessRuleTask(BusinessRuleTask rule) {
        String decisionRef = rule.getCamundaDecisionRef();
        if (isBlank(decisionRef)) {
            decisionRef = rule.getAttributeValue("decisionRef");
        }
        String delegateExpr = rule.getCamundaDelegateExpression();
        if (isBlank(delegateExpr)) {
            delegateExpr = rule.getAttributeValue("delegateExpression");
        }
        String cls = rule.getCamundaClass();
        if (isBlank(cls)) {
            cls = rule.getAttributeValue("class");
        }
        String expr = rule.getCamundaExpression();
        if (isBlank(expr)) {
            expr = rule.getAttributeValue("expression");
        }
        String type = rule.getCamundaType();
        if (isBlank(type)) {
            type = rule.getAttributeValue("type");
        }
        String topic = rule.getCamundaTopic();
        if (isBlank(topic)) {
            topic = rule.getAttributeValue("topic");
        }
        return isBlank(decisionRef) && isBlank(delegateExpr) && isBlank(cls) && isBlank(expr) && isBlank(type) && isBlank(topic);
    }

    private static final java.util.regex.Pattern LITERAL_CARDINALITY = java.util.regex.Pattern.compile("\\d+");
    private static final java.util.regex.Pattern LITERAL_BOOLEAN =
            java.util.regex.Pattern.compile("(true|false)|\\$\\{\\s*(true|false)\\s*\\}");

    @SuppressWarnings("rawtypes")
    private static AbstractFlowNodeBuilder appendMultiInstanceSynchronizedActivity(AbstractFlowNodeBuilder at,
            String id, MultiInstanceLoopCharacteristics loop) {
        LoopCardinality cardinality = loop.getLoopCardinality();
        String cardinalityText = cardinality == null ? null : cardinality.getTextContent();
        String collectionText = loop.getCamundaCollection();

        org.camunda.bpm.model.bpmn.builder.MultiInstanceLoopCharacteristicsBuilder multiInstance =
                at.subProcess(wrapperId(id))
                        .embeddedSubProcess()
                        .startEvent(wrapperStartId(id))
                        .sequenceFlowId(wrapperStartFlowId(id))
                        .receiveTask(id).message(twinMessageName(id))
                        .sequenceFlowId(flowToAutomationId(id))
                        .serviceTask(automationTaskId(id)).camundaDelegateExpression(TWIN_DELEGATE_EXPRESSION)
                        .sequenceFlowId(wrapperEndFlowId(id))
                        .endEvent(wrapperEndId(id))
                    .subProcessDone()
                    .multiInstance();
        org.camunda.bpm.model.bpmn.builder.MultiInstanceLoopCharacteristicsBuilder sequenced =
                loop.isSequential() ? multiInstance.sequential() : multiInstance.parallel();

        if (!isBlank(cardinalityText)) {
            sequenced = sequenced.cardinality(cardinalityText);
        } else if (!isBlank(collectionText)) {
            sequenced = sequenced.camundaCollection(collectionText);
        }

        org.camunda.bpm.model.bpmn.instance.CompletionCondition completionCondition = loop.getCompletionCondition();
        String completionConditionText = completionCondition == null ? null : completionCondition.getTextContent();
        if (!isBlank(completionConditionText)) {
            sequenced = sequenced.completionCondition(completionConditionText);
        }
        return sequenced.multiInstanceDone();
    }

    private static String wrapperId(String twinActivityId) {
        return twinActivityId + WRAPPER_ID_SUFFIX;
    }

    private static String wrapperStartId(String twinActivityId) {
        return twinActivityId + WRAPPER_START_ID_SUFFIX;
    }

    private static String wrapperEndId(String twinActivityId) {
        return twinActivityId + WRAPPER_END_ID_SUFFIX;
    }

    private static String flowToAutomationId(String twinActivityId) {
        return twinActivityId + FLOW_TO_AUTOMATION_SUFFIX;
    }

    private static String wrapperStartFlowId(String twinActivityId) {
        return twinActivityId + WRAPPER_START_FLOW_SUFFIX;
    }

    private static String wrapperEndFlowId(String twinActivityId) {
        return twinActivityId + WRAPPER_END_FLOW_SUFFIX;
    }

    private static void copyNodeNames(BpmnModelInstance original, BpmnModelInstance twin,
            Collection<String> copied) {
        for (String id : copied) {
            FlowNode source = original.getModelElementById(id);
            FlowNode target = twin.getModelElementById(id);
            if (source == null || target == null || isBlank(source.getName())) {
                continue;
            }
            target.setName(source.getName());
        }
    }

    private static void copyFlowDetails(BpmnModelInstance twin, List<SequenceFlow> flows) {
        for (SequenceFlow source : flows) {
            SequenceFlow target = twin.getModelElementById(source.getId());
            if (target == null) {
                continue;
            }
            if (!isBlank(source.getName())) {
                target.setName(source.getName());
            }
            ConditionExpression condition = source.getConditionExpression();
            if (condition == null) {
                continue;
            }
            ConditionExpression copy = twin.newInstance(ConditionExpression.class);
            copy.setId(CONDITION_EXPRESSION_ID_PREFIX + source.getId());
            copy.setTextContent(condition.getTextContent());
            target.setConditionExpression(copy);
        }
    }

    private static void copyDefaultFlows(Process process, BpmnModelInstance twin) {
        for (ExclusiveGateway gateway : process.getChildElementsByType(ExclusiveGateway.class)) {
            applyDefault(twin, gateway.getId(), gateway.getDefault());
        }
        for (InclusiveGateway gateway : process.getChildElementsByType(InclusiveGateway.class)) {
            applyDefault(twin, gateway.getId(), gateway.getDefault());
        }
    }

    private static void applyDefault(BpmnModelInstance twin, String gatewayId, SequenceFlow defaultFlow) {
        if (defaultFlow == null) {
            return;
        }
        Object gateway = twin.getModelElementById(gatewayId);
        SequenceFlow flow = twin.getModelElementById(defaultFlow.getId());
        if (flow == null) {
            logger.warn("Twin gateway {} has no default flow: {} was not copied over",
                    gatewayId, defaultFlow.getId());
            return;
        }
        if (gateway instanceof ExclusiveGateway exclusive) {
            exclusive.setDefault(flow);
        } else if (gateway instanceof InclusiveGateway inclusive) {
            inclusive.setDefault(flow);
        }
    }

    // Copies construct-specific BPMN details via DOM element adoption.
    private static void copyConstructDetails(BpmnModelInstance original, BpmnModelInstance twin,
            Set<String> copied) {
        Document sourceDoc = (Document) original.getDocument().getDomSource().getNode();
        Document twinDoc = (Document) twin.getDocument().getDomSource().getNode();
        for (String id : copied) {
            FlowNode source = original.getModelElementById(id);
            FlowNode target = twin.getModelElementById(id);
            if (source == null || target == null) {
                continue;
            }
            Element sourceEl = findW3cElementById(sourceDoc, id);
            if (sourceEl == null) {
                continue;
            }

            // Copy EventDefinitions for CatchEvents, ThrowEvents, EndEvents, BoundaryEvents (unless target was converted to receiveTask)
            if ((source instanceof IntermediateCatchEvent || source instanceof IntermediateThrowEvent
                    || source instanceof EndEvent || source instanceof BoundaryEvent)
                    && !(target instanceof ReceiveTask)) {
                Element targetEl = findW3cElementById(twinDoc, id);
                if (targetEl != null) {
                    if (source instanceof BoundaryEvent) {
                        String attachedToRef = sourceEl.getAttribute("attachedToRef");
                        if (!isBlank(attachedToRef)) {
                            targetEl.setAttribute("attachedToRef", attachedToRef);
                        }
                        String cancelActivity = sourceEl.getAttribute("cancelActivity");
                        if (!isBlank(cancelActivity)) {
                            targetEl.setAttribute("cancelActivity", cancelActivity);
                        }
                    }
                    NodeList sourceChildren = sourceEl.getChildNodes();
                    for (int i = 0; i < sourceChildren.getLength(); i++) {
                        if (sourceChildren.item(i) instanceof Element childEl
                                && childEl.getLocalName() != null
                                && childEl.getLocalName().endsWith("EventDefinition")) {
                            Node copyNode = twinDoc.importNode(childEl, true);
                            targetEl.appendChild(copyNode);
                        }
                    }
                }
            }

            // Copy Script Task format and script content
            if (source instanceof ScriptTask && target instanceof ScriptTask targetScript) {
                String format = sourceEl.getAttribute("scriptFormat");
                if (!isBlank(format)) {
                    targetScript.setScriptFormat(format);
                }
                NodeList children = sourceEl.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element childEl && "script".equals(childEl.getLocalName())) {
                        DomElement copy = copyElement(twin.getDocument(), childEl);
                        target.getDomElement().appendChild(copy);
                    }
                }
            }

            // Copy Business Rule Task attributes (Case A: DMN, Case B: explicit Camunda implementation)
            if (source instanceof BusinessRuleTask && target instanceof BusinessRuleTask targetRule) {
                // Case A: DMN configuration
                String decisionRef = sourceEl.getAttributeNS(CAMUNDA_NS, "decisionRef");
                if (isBlank(decisionRef)) {
                    decisionRef = sourceEl.getAttribute("decisionRef");
                }
                if (!isBlank(decisionRef)) {
                    targetRule.setCamundaDecisionRef(decisionRef);
                }
                String binding = sourceEl.getAttributeNS(CAMUNDA_NS, "decisionRefBinding");
                if (isBlank(binding)) {
                    binding = sourceEl.getAttributeNS(CAMUNDA_NS, "decisionBinding");
                }
                if (isBlank(binding)) {
                    binding = sourceEl.getAttribute("decisionRefBinding");
                }
                if (isBlank(binding)) {
                    binding = sourceEl.getAttribute("decisionBinding");
                }
                if (!isBlank(binding)) {
                    targetRule.setCamundaDecisionRefBinding(binding);
                }
                String resultVar = sourceEl.getAttributeNS(CAMUNDA_NS, "resultVariable");
                if (!isBlank(resultVar)) {
                    targetRule.setCamundaResultVariable(resultVar);
                }
                String mapDecisionResult = sourceEl.getAttributeNS(CAMUNDA_NS, "mapDecisionResult");
                if (!isBlank(mapDecisionResult)) {
                    targetRule.setCamundaMapDecisionResult(mapDecisionResult);
                }

                // Case B: Explicit Camunda implementation
                String delegateExpr = sourceEl.getAttributeNS(CAMUNDA_NS, "delegateExpression");
                if (isBlank(delegateExpr)) {
                    delegateExpr = sourceEl.getAttribute("delegateExpression");
                }
                if (!isBlank(delegateExpr)) {
                    targetRule.setCamundaDelegateExpression(delegateExpr);
                }
                String cls = sourceEl.getAttributeNS(CAMUNDA_NS, "class");
                if (isBlank(cls)) {
                    cls = sourceEl.getAttribute("class");
                }
                if (!isBlank(cls)) {
                    targetRule.setCamundaClass(cls);
                }
                String expr = sourceEl.getAttributeNS(CAMUNDA_NS, "expression");
                if (isBlank(expr)) {
                    expr = sourceEl.getAttribute("expression");
                }
                if (!isBlank(expr)) {
                    targetRule.setCamundaExpression(expr);
                }
                String type = sourceEl.getAttributeNS(CAMUNDA_NS, "type");
                if (isBlank(type)) {
                    type = sourceEl.getAttribute("type");
                }
                if (!isBlank(type)) {
                    targetRule.setCamundaType(type);
                }
                String topic = sourceEl.getAttributeNS(CAMUNDA_NS, "topic");
                if (isBlank(topic)) {
                    topic = sourceEl.getAttribute("topic");
                }
                if (!isBlank(topic)) {
                    targetRule.setCamundaTopic(topic);
                }
            }

            // Copy Call Activity calledElement and calledElementBinding
            if (source instanceof CallActivity && target instanceof CallActivity targetCall) {
                String calledElement = sourceEl.getAttribute("calledElement");
                if (!isBlank(calledElement)) {
                    targetCall.setCalledElement(calledElement);
                }
                String binding = sourceEl.getAttributeNS(CAMUNDA_NS, "calledElementBinding");
                if (!isBlank(binding)) {
                    targetCall.setCamundaCalledElementBinding(binding);
                }
            }

            // Copy SubProcess nested child elements if target is empty (skipping incoming/outgoing)
            if (source instanceof SubProcess && target instanceof SubProcess) {
                NodeList children = sourceEl.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element childEl) {
                        String local = childEl.getLocalName();
                        if ("incoming".equals(local) || "outgoing".equals(local)) {
                            continue;
                        }
                        DomElement copy = copyElement(twin.getDocument(), childEl);
                        target.getDomElement().appendChild(copy);
                    }
                }
            }
        }
    }

    private static Element findW3cElementById(Node node, String id) {
        if (node instanceof Element el && id.equals(el.getAttribute("id"))) {
            return el;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Element found = findW3cElementById(children.item(i), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // Raw DOM copy preserves metaml attributes without needing to register the extension schema.
    private static void copyMetamlExtensions(BpmnModelInstance original, BpmnModelInstance twin,
            Process process, Set<String> copied) {
        Set<String> owners = new HashSet<>(copied);
        owners.add(process.getId());

        Document source = (Document) original.getDocument().getDomSource().getNode();
        NodeList declarations = source.getElementsByTagNameNS(METAML_NAMESPACE, "*");
        for (int i = 0; i < declarations.getLength(); i++) {
            if (!(declarations.item(i) instanceof Element declaration)) {
                continue;
            }
            if (!(declaration.getParentNode() instanceof Element holder)
                    || !EXTENSION_ELEMENTS_NAME.equals(holder.getLocalName())
                    || !(holder.getParentNode() instanceof Element owner)) {
                continue;
            }
            String ownerId = owner.getAttribute(ID_ATTRIBUTE);
            String twinOwnerId = process.getId().equals(ownerId) ? twinProcessId(process) : ownerId;
            if (!owners.contains(ownerId) || !(twin.getModelElementById(twinOwnerId) instanceof BaseElement target)) {
                continue;
            }
            attach(twin, target, declaration);
        }
    }

    private static void attach(BpmnModelInstance twin, BaseElement target, Element declaration) {
        ExtensionElements extensions = target.getExtensionElements();
        if (extensions == null) {
            extensions = twin.newInstance(ExtensionElements.class);
            target.setExtensionElements(extensions);
        }
        extensions.getDomElement().appendChild(copyElement(twin.getDocument(), declaration));
    }

    private static DomElement copyElement(DomDocument document, Element source) {
        DomElement copy = document.createElement(source.getNamespaceURI(), source.getLocalName());

        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            if (!(attributes.item(i) instanceof Attr attribute)
                    || XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                continue;
            }
            if (attribute.getNamespaceURI() == null) {
                copy.setAttribute(attribute.getName(), attribute.getValue());
            } else {
                copy.setAttribute(attribute.getNamespaceURI(), attribute.getLocalName(), attribute.getValue());
            }
        }

        boolean nested = false;
        NodeList children = source.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                copy.appendChild(copyElement(document, element));
                nested = true;
            }
        }
        if (!nested && !isBlank(source.getTextContent())) {
            copy.setTextContent(source.getTextContent());
        }
        return copy;
    }

    // distinct from original — same id registers the twin as a new version of the same process
    private static String twinProcessId(Process process) {
        return process.getId() + TWIN_PROCESS_ID_SUFFIX;
    }

    private static String twinProcessName(Process process) {
        return isBlank(process.getName())
                ? twinProcessId(process)
                : process.getName() + TWIN_PROCESS_NAME_SUFFIX;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
