package com.metaml.workbench.codegen;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

    // Generates the Java source tree (delegates, listeners, broadcasters, status controller) for a target platform.
@Component
public class TargetPlatformSourceGenerator {
    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    static final String SYNC_SIGNAL_PREFIX = "sync_";

    // The exact set TargetPlatformTwinMirrorGenerator turns into an executable Twin serviceTask. On the
    // Proxy side these carry no camunda:delegateExpression and no camunda:class, so the delegate scan
    // below skips them entirely - which is why, before this, a Proxy made of human work produced no
    // sync signal at all and its Twin ran the whole process through while the human was still on the
    // first task. Keyed on BPMN element type only: no process, activity or domain name appears here.
    private static final Set<String> MIRRORED_AUTOMATED_TASKS = Set.of("userTask", "manualTask", "task");

    public record GeneratedSource(String relativeDirectory, String className, String source) { }
    public record Result(String bpmnXml, List<GeneratedSource> sources,
                         Set<String> syncSignalNames, Set<String> syncActivityIds) { }

    public Result generate(String bpmnXml, boolean twin) {
        return generate(bpmnXml, twin, null);
    }

    public Result generate(String bpmnXml, boolean twin, Set<String> syncActivityIdsFromProxy) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
            List<GeneratedSource> sources = new ArrayList<>();
            List<String> proxyServiceTaskIds = new ArrayList<>();
            NodeList all = document.getElementsByTagNameNS("*", "*");
            for (int i = 0; i < all.getLength(); i++) {
                Element element = (Element) all.item(i);
                String localName = element.getLocalName();
                if (localName == null) continue;
                boolean activity = localName != null && localName.endsWith("Task");
                boolean event = localName.endsWith("Event");
                // Gate the Proxy's human/inert activities too, before the delegate filter below drops
                // them: the Twin mirrors each of these as an automated task, so each needs the same
                // rendezvous point the delegated ones already get. The Twin half is inserted by
                // insertTwinSyncSignalsBefore, in front of the mirrored activity rather than after it,
                // which is what makes "Twin task N cannot start until the human finished Proxy task N".
                if (!twin && MIRRORED_AUTOMATED_TASKS.contains(localName)) {
                    String mirroredId = element.getAttribute("id");
                    if (mirroredId != null && !mirroredId.isBlank() && !proxyServiceTaskIds.contains(mirroredId)) {
                        proxyServiceTaskIds.add(mirroredId);
                    }
                }
                String expression = element.getAttributeNS(CAMUNDA_NS, "delegateExpression");
                String javaClass = element.getAttributeNS(CAMUNDA_NS, "class");
                if ((!activity && !event) || (expression.isBlank() && javaClass.isBlank())) continue;
                String id = element.getAttribute("id");
                if (id == null || id.isBlank()) throw new IllegalArgumentException("Delegated BPMN element has no id");
                // Twin delegates receive distinct bean names to avoid Spring registration collisions.
                String beanName = twin ? camel(id) + "Twin" : camel(id);
                String className = pascal(id);
                // A fixed template needs a deterministic, component-scanned bean name. Normalising also makes a camunda:class task usable without requiring an arbitrary FQCN.
                element.removeAttributeNS(CAMUNDA_NS, "class");
                element.setAttributeNS(CAMUNDA_NS, "camunda:delegateExpression", "${" + beanName + "}");
                String side = twin ? "TWIN" : "PROXY";
                String label = event ? side + " (MSG)" : side;
                String directory = (twin ? "twin" : "proxy") + "/" + (event ? "events" : "delegates");
                String packageName = "com.tp.TargetPlatform." + directory.replace('/', '.');
                sources.add(new GeneratedSource(directory, className, render(packageName, className, beanName, label)));

                // Track proxy service task IDs for lockstep sync (not events, not twin _automate tasks)
                if (activity && !twin && !proxyServiceTaskIds.contains(id)) {
                    proxyServiceTaskIds.add(id);
                }
            }
            sources.addAll(scanExecutionListeners(document, twin));
            sources.addAll(scanTaskListeners(document, twin));

            // Apply bidirectional lockstep synchronization: insert signal catch events so both proxy and twin wait at the same signals after each activity. SignalBroadcaster's existing REQUEST/RESPONSE protocol handles the rest (see its own comment).
            Set<String> syncSignalNames = new LinkedHashSet<>();
            Set<String> syncActivityIds = new LinkedHashSet<>();
            if (!twin && !proxyServiceTaskIds.isEmpty()) {
                syncSignalNames = insertProxySyncSignals(document, proxyServiceTaskIds);
                // Only report IDs where signals were actually created (requires outgoing flows)
                for (String sn : syncSignalNames) {
                    syncActivityIds.add(sn.substring(SYNC_SIGNAL_PREFIX.length()));
                }
            } else if (twin && syncActivityIdsFromProxy != null && !syncActivityIdsFromProxy.isEmpty()) {
                // An authored Twin models its own wait state as a receiveTask, so that one is rewritten
                // in place. A mirrored Twin has no receiveTask at all - the mirror produced a plain
                // serviceTask carrying the same activity id - so the rendezvous has to be inserted in
                // front of it instead. Whichever produced the Twin, both halves end up subscribed to the
                // same sync_<activityId>, which is all SignalBroadcaster's existing protocol needs.
                Set<String> fromReceiveTasks = replaceTwinReceiveTasksWithSignals(document,
                        syncActivityIdsFromProxy);
                Set<String> notCoveredByReceiveTask = new LinkedHashSet<>(syncActivityIdsFromProxy);
                notCoveredByReceiveTask.removeIf(id -> fromReceiveTasks.contains(SYNC_SIGNAL_PREFIX + id));
                syncSignalNames = new LinkedHashSet<>(fromReceiveTasks);
                syncSignalNames.addAll(insertTwinSyncSignalsBefore(document, notCoveredByReceiveTask));
                syncActivityIds.addAll(syncActivityIdsFromProxy);
            }

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter xml = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(xml));
            return new Result(xml.toString(), sources, syncSignalNames, syncActivityIds);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not scan BPMN for TargetPlatform delegates: " + e.getMessage(), e);
        }
    }

    // Lockstep, proxy side: insert an intermediateCatchEvent with a signalEventDefinition after each
    // serviceTask. The sync_<activityId> name matches the one inserted into the twin BPMN, giving
    // SignalBroadcaster's REQUEST/RESPONSE rendezvous two subscription points to pair.
    private Set<String> insertProxySyncSignals(Document document, List<String> activityIds) {
        Set<String> signalNames = new LinkedHashSet<>();
        Element definitions = document.getDocumentElement();
        String bpmnNs = definitions.getNamespaceURI();
        String prefix = definitions.getPrefix();

        NodeList processes = document.getElementsByTagNameNS(bpmnNs, "process");
        if (processes.getLength() == 0) return signalNames;
        Element process = (Element) processes.item(0);

        for (String activityId : activityIds) {
            String signalName = SYNC_SIGNAL_PREFIX + activityId;
            ensureSignalNameAvailable(document, bpmnNs, signalName, activityId);
            String catchEventId = uniqueId(document, "sync_evt_" + activityId);
            String newFlowId = uniqueId(document, "sync_flow_" + activityId);
            String signalId = uniqueId(document, "Signal_sync_" + activityId);
            String signalEventDefId = uniqueId(document, "SED_sync_" + activityId);

            signalNames.add(signalName);

            Element serviceTask = findElementById(document, activityId);
            if (serviceTask == null) continue;

            // Find all outgoing sequence flows from this serviceTask
            List<Element> outgoingFlows = findFlowsBySourceRef(document, bpmnNs, activityId);
            if (outgoingFlows.isEmpty()) continue;

            // Create the intermediate catch event. BPMN 2.0 XSD element order: incoming/outgoing (from tFlowNode) BEFORE signalEventDefinition (from tCatchEvent).
            Element catchEvent = document.createElementNS(bpmnNs, qname(prefix, "intermediateCatchEvent"));
            catchEvent.setAttribute("id", catchEventId);

            // Add incoming reference (the new bridge flow from serviceTask) - must be first
            Element incomingElem = document.createElementNS(bpmnNs, qname(prefix, "incoming"));
            incomingElem.setTextContent(newFlowId);
            catchEvent.appendChild(incomingElem);

            // Redirect each outgoing flow: change sourceRef from serviceTask to catch event
            for (Element flow : outgoingFlows) {
                flow.setAttribute("sourceRef", catchEventId);
                Element outgoingElem = document.createElementNS(bpmnNs, qname(prefix, "outgoing"));
                outgoingElem.setTextContent(flow.getAttribute("id"));
                catchEvent.appendChild(outgoingElem);
            }

            // Signal event definition comes AFTER incoming/outgoing per BPMN XSD
            Element signalEventDef = document.createElementNS(bpmnNs, qname(prefix, "signalEventDefinition"));
            signalEventDef.setAttribute("id", signalEventDefId);
            signalEventDef.setAttribute("signalRef", signalId);
            catchEvent.appendChild(signalEventDef);

            // Update the serviceTask's <outgoing> children to point to the new bridge flow
            removeChildElementsByLocalName(serviceTask, "outgoing");
            Element newOutgoing = document.createElementNS(bpmnNs, qname(prefix, "outgoing"));
            newOutgoing.setTextContent(newFlowId);
            serviceTask.appendChild(newOutgoing);

            // Create the bridge sequence flow: serviceTask → catch event
            Element bridgeFlow = document.createElementNS(bpmnNs, qname(prefix, "sequenceFlow"));
            bridgeFlow.setAttribute("id", newFlowId);
            bridgeFlow.setAttribute("sourceRef", activityId);
            bridgeFlow.setAttribute("targetRef", catchEventId);

            process.appendChild(catchEvent);
            process.appendChild(bridgeFlow);

            // Signal declaration on the definitions element - must appear BEFORE BPMNDiagram per XSD
            Element signal = document.createElementNS(bpmnNs, qname(prefix, "signal"));
            signal.setAttribute("id", signalId);
            signal.setAttribute("name", signalName);
            insertBeforeDiagram(definitions, signal);
        }
        return signalNames;
    }

    // Replaces twin receive tasks with sync signal catch events matching proxy wait states.
    private Set<String> replaceTwinReceiveTasksWithSignals(Document document, Set<String> activityIds) {
        Set<String> signalNames = new LinkedHashSet<>();
        Element definitions = document.getDocumentElement();
        String bpmnNs = definitions.getNamespaceURI();
        String prefix = definitions.getPrefix();

        // Collect matching receiveTask elements (can't modify DOM while iterating getElementsBy*)
        List<Element> receiveTasks = new ArrayList<>();
        NodeList rtNodes = document.getElementsByTagNameNS(bpmnNs, "receiveTask");
        for (int i = 0; i < rtNodes.getLength(); i++) {
            Element rt = (Element) rtNodes.item(i);
            if (activityIds.contains(rt.getAttribute("id"))) {
                receiveTasks.add(rt);
            }
        }

        for (Element receiveTask : receiveTasks) {
            String activityId = receiveTask.getAttribute("id");
            String signalName = SYNC_SIGNAL_PREFIX + activityId;
            ensureSignalNameAvailable(document, bpmnNs, signalName, activityId);
            String signalId = uniqueId(document, "Signal_sync_" + activityId);
            String signalEventDefId = uniqueId(document, "SED_sync_" + activityId);

            signalNames.add(signalName);

            // Create replacement intermediateCatchEvent keeping the same id so all existing sequence flow sourceRef/targetRef references stay valid. BPMN 2.0 XSD element order: incoming/outgoing BEFORE signalEventDefinition.
            Element catchEvent = document.createElementNS(bpmnNs, qname(prefix, "intermediateCatchEvent"));
            catchEvent.setAttribute("id", activityId);
            if (receiveTask.hasAttribute("name")) {
                catchEvent.setAttribute("name", receiveTask.getAttribute("name"));
            }

            // Carry over incoming/outgoing children from the receiveTask FIRST (XSD order)
            NodeList children = receiveTask.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element el) {
                    String ln = el.getLocalName();
                    if ("incoming".equals(ln) || "outgoing".equals(ln)) {
                        catchEvent.appendChild(child.cloneNode(true));
                    }
                }
            }

            // Signal event definition comes AFTER incoming/outgoing per BPMN XSD
            Element signalEventDef = document.createElementNS(bpmnNs, qname(prefix, "signalEventDefinition"));
            signalEventDef.setAttribute("id", signalEventDefId);
            signalEventDef.setAttribute("signalRef", signalId);
            catchEvent.appendChild(signalEventDef);

            receiveTask.getParentNode().replaceChild(catchEvent, receiveTask);

            // Signal declaration - must appear BEFORE BPMNDiagram per XSD
            Element signal = document.createElementNS(bpmnNs, qname(prefix, "signal"));
            signal.setAttribute("id", signalId);
            signal.setAttribute("name", signalName);
            insertBeforeDiagram(definitions, signal);
        }

        // Remove TwinAdvance_ message declarations for the replaced activities
        removeTwinAdvanceMessages(document, bpmnNs, activityIds);
        return signalNames;
    }

    // Lockstep, mirrored-Twin side: put an intermediateCatchEvent on sync_<activityId> immediately
    // BEFORE the Twin's automated activity, so the activity is unreachable until the signal arrives.
    // Deliberately the mirror image of insertProxySyncSignals, which puts its catch event AFTER the
    // Proxy activity: the Proxy therefore parks only once the human has genuinely completed task N,
    // and the Twin parks before it has executed anything of task N. SignalBroadcaster then pairs the
    // two subscriptions, releases the Twin (REQUEST), watches it advance, and only then releases the
    // Proxy (RESPONSE) - which is what makes the Twin structurally incapable of reaching task N+1
    // while the Proxy is still waiting at task N.
    //
    // Skips silently where the Twin has no element with that id: an authored Twin is free to mirror
    // only part of the Proxy, and a Proxy signal with no Twin subscriber is a case SignalBroadcaster
    // already handles (partnerNotComing -> plain delivery) rather than a generation failure.
    private Set<String> insertTwinSyncSignalsBefore(Document document, Set<String> activityIds) {
        Set<String> signalNames = new LinkedHashSet<>();
        if (activityIds.isEmpty()) {
            return signalNames;
        }
        Element definitions = document.getDocumentElement();
        String bpmnNs = definitions.getNamespaceURI();
        String prefix = definitions.getPrefix();

        NodeList processes = document.getElementsByTagNameNS(bpmnNs, "process");
        if (processes.getLength() == 0) return signalNames;
        Element process = (Element) processes.item(0);

        for (String activityId : activityIds) {
            Element activity = findElementById(document, activityId);
            if (activity == null) continue;
            // Incoming flows are what the catch event is spliced into; with none there is nothing to
            // gate (a start-event-less fragment), and inserting an unreachable catch event would only
            // add a signal nobody can ever be waiting at.
            List<Element> incomingFlows = findFlowsByTargetRef(document, bpmnNs, activityId);
            if (incomingFlows.isEmpty()) continue;

            String signalName = SYNC_SIGNAL_PREFIX + activityId;
            ensureSignalNameAvailable(document, bpmnNs, signalName, activityId);
            String catchEventId = uniqueId(document, "sync_evt_" + activityId);
            String bridgeFlowId = uniqueId(document, "sync_flow_" + activityId);
            String signalId = uniqueId(document, "Signal_sync_" + activityId);
            String signalEventDefId = uniqueId(document, "SED_sync_" + activityId);

            signalNames.add(signalName);

            // BPMN 2.0 XSD element order on a catch event: incoming/outgoing first, then the event
            // definition - the same ordering insertProxySyncSignals observes.
            Element catchEvent = document.createElementNS(bpmnNs, qname(prefix, "intermediateCatchEvent"));
            catchEvent.setAttribute("id", catchEventId);

            // Every flow that used to arrive at the activity now arrives at the catch event instead.
            for (Element flow : incomingFlows) {
                flow.setAttribute("targetRef", catchEventId);
                Element incomingElem = document.createElementNS(bpmnNs, qname(prefix, "incoming"));
                incomingElem.setTextContent(flow.getAttribute("id"));
                catchEvent.appendChild(incomingElem);
            }
            Element outgoingElem = document.createElementNS(bpmnNs, qname(prefix, "outgoing"));
            outgoingElem.setTextContent(bridgeFlowId);
            catchEvent.appendChild(outgoingElem);

            Element signalEventDef = document.createElementNS(bpmnNs, qname(prefix, "signalEventDefinition"));
            signalEventDef.setAttribute("id", signalEventDefId);
            signalEventDef.setAttribute("signalRef", signalId);
            catchEvent.appendChild(signalEventDef);

            // The activity's own <incoming> now names only the bridge flow from the catch event.
            removeChildElementsByLocalName(activity, "incoming");
            Element newIncoming = document.createElementNS(bpmnNs, qname(prefix, "incoming"));
            newIncoming.setTextContent(bridgeFlowId);
            insertIncomingInSchemaOrder(activity, newIncoming);

            Element bridgeFlow = document.createElementNS(bpmnNs, qname(prefix, "sequenceFlow"));
            bridgeFlow.setAttribute("id", bridgeFlowId);
            bridgeFlow.setAttribute("sourceRef", catchEventId);
            bridgeFlow.setAttribute("targetRef", activityId);

            process.appendChild(catchEvent);
            process.appendChild(bridgeFlow);

            Element signal = document.createElementNS(bpmnNs, qname(prefix, "signal"));
            signal.setAttribute("id", signalId);
            signal.setAttribute("name", signalName);
            insertBeforeDiagram(definitions, signal);
        }
        return signalNames;
    }

    // tFlowNode orders its children extensionElements, then incoming, then outgoing. Appending would
    // put the rewritten <incoming> after <outgoing>, so it goes in front of the first <outgoing>
    // instead (and at the end when the activity has none).
    private static void insertIncomingInSchemaOrder(Element activity, Element incoming) {
        NodeList children = activity.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && "outgoing".equals(el.getLocalName())) {
                activity.insertBefore(incoming, el);
                return;
            }
        }
        activity.appendChild(incoming);
    }

    private static List<Element> findFlowsByTargetRef(Document document, String bpmnNs, String targetRef) {
        NodeList flows = document.getElementsByTagNameNS(bpmnNs, "sequenceFlow");
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < flows.getLength(); i++) {
            Element flow = (Element) flows.item(i);
            if (targetRef.equals(flow.getAttribute("targetRef"))) {
                result.add(flow);
            }
        }
        return result;
    }

    private void removeTwinAdvanceMessages(Document document, String bpmnNs, Set<String> activityIds) {
        NodeList messages = document.getElementsByTagNameNS(bpmnNs, "message");
        List<Element> toRemove = new ArrayList<>();
        for (int i = 0; i < messages.getLength(); i++) {
            Element msg = (Element) messages.item(i);
            String name = msg.getAttribute("name");
            if (name != null && name.startsWith("TwinAdvance_")) {
                String actId = name.substring("TwinAdvance_".length());
                if (activityIds.contains(actId)) {
                    toRemove.add(msg);
                }
            }
        }
        for (Element msg : toRemove) {
            msg.getParentNode().removeChild(msg);
        }
    }

    // ── DOM helpers ────────────────────────────────────────────────────────────

    // Inserts a child element into definitions BEFORE the first BPMNDiagram element (or any DI namespace element). BPMN 2.0 XSD requires rootElements (signal, message, process, etc.) before BPMNDiagram elements. Falls back to appendChild if no diagram is found.
    private static void insertBeforeDiagram(Element definitions, Element child) {
        NodeList children = definitions.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element el && "BPMNDiagram".equals(el.getLocalName())) {
                definitions.insertBefore(child, el);
                return;
            }
        }
        definitions.appendChild(child);
    }

    private static String qname(String prefix, String localName) {
        return prefix != null ? prefix + ":" + localName : localName;
    }

    // Generates unique element IDs by deterministically incrementing numeric suffixes.
    private static String uniqueId(Document document, String candidate) {
        if (findElementById(document, candidate) == null) {
            return candidate;
        }
        for (int suffix = 2; ; suffix++) {
            String probe = candidate + "_" + suffix;
            if (findElementById(document, probe) == null) {
                return probe;
            }
        }
    }

    // Fails if a sync signal name collides with a pre-existing signal in the BPMN.
    private static void ensureSignalNameAvailable(Document document, String bpmnNs, String signalName,
            String activityId) {
        NodeList signals = document.getElementsByTagNameNS(bpmnNs, "signal");
        for (int i = 0; i < signals.getLength(); i++) {
            Element existing = (Element) signals.item(i);
            if (signalName.equals(existing.getAttribute("name"))) {
                throw new IllegalStateException("Cannot insert a lockstep sync signal named '" + signalName
                        + "' for activity '" + activityId + "': a signal with that exact name already exists "
                        + "in this BPMN (id=" + existing.getAttribute("id") + "). Rename the pre-existing "
                        + "signal or the activity to resolve the clash before regenerating.");
            }
        }
    }

    private static Element findElementById(Document document, String id) {
        NodeList all = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element el && id.equals(el.getAttribute("id"))) {
                return el;
            }
        }
        return null;
    }

    private static List<Element> findFlowsBySourceRef(Document document, String bpmnNs, String sourceRef) {
        NodeList flows = document.getElementsByTagNameNS(bpmnNs, "sequenceFlow");
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < flows.getLength(); i++) {
            Element flow = (Element) flows.item(i);
            if (sourceRef.equals(flow.getAttribute("sourceRef"))) {
                result.add(flow);
            }
        }
        return result;
    }

    private static void removeChildElementsByLocalName(Element parent, String childLocalName) {
        List<Node> toRemove = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el && childLocalName.equals(el.getLocalName())) {
                toRemove.add(child);
            }
        }
        for (Node n : toRemove) {
            parent.removeChild(n);
        }
    }

    // Scans execution listeners and registers generated delegate beans.
    private List<GeneratedSource> scanExecutionListeners(Document document, boolean twin) {
        List<GeneratedSource> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        NodeList listeners = document.getElementsByTagNameNS(CAMUNDA_NS, "executionListener");
        for (int i = 0; i < listeners.getLength(); i++) {
            Element listener = (Element) listeners.item(i);
            String originalBeanName = stripExpression(listener.getAttribute("delegateExpression"));
            if (originalBeanName.isBlank()) continue;
            // Suffix twin listener beans to avoid bean definition collisions.
            String beanName = twin ? originalBeanName + "Twin" : originalBeanName;
            listener.setAttribute("delegateExpression", "${" + beanName + "}");
            if (!seen.add(beanName)) continue;
            String className = pascal(beanName);
            String directory = (twin ? "twin" : "proxy") + "/listeners";
            String packageName = "com.tp.TargetPlatform." + directory.replace('/', '.');
            String label = (twin ? "TWIN" : "PROXY") + " (LISTENER)";
            sources.add(new GeneratedSource(directory, className, renderListener(packageName, className, beanName, label)));
        }
        return sources;
    }

    // Scans user-task task listeners and generates TaskListener delegate beans.
    private List<GeneratedSource> scanTaskListeners(Document document, boolean twin) {
        List<GeneratedSource> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        NodeList listeners = document.getElementsByTagNameNS(CAMUNDA_NS, "taskListener");
        for (int i = 0; i < listeners.getLength(); i++) {
            Element listener = (Element) listeners.item(i);
            String originalBeanName = stripExpression(listener.getAttribute("delegateExpression"));
            if (originalBeanName.isBlank()) continue;
            String beanName = twin ? originalBeanName + "Twin" : originalBeanName;
            listener.setAttribute("delegateExpression", "${" + beanName + "}");
            if (!seen.add(beanName)) continue;
            String className = pascal(beanName);
            String directory = (twin ? "twin" : "proxy") + "/listeners";
            String packageName = "com.tp.TargetPlatform." + directory.replace('/', '.');
            String label = (twin ? "TWIN" : "PROXY") + " (TASK LISTENER)";
            sources.add(new GeneratedSource(directory, className,
                    renderTaskListener(packageName, className, beanName, label)));
        }
        return sources;
    }

    private static String renderTaskListener(String pkg, String className, String beanName, String label) {
        return """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateTask;
                import org.camunda.bpm.engine.delegate.TaskListener;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component("%s")
                public class %s implements TaskListener {

                    private static final Logger logger = LoggerFactory.getLogger(%s.class);

                    @Override
                    public void notify(DelegateTask delegateTask) {
                        logger.info("%s INVOKED: listener={} bean={} event={} processDefinitionId={} "
                                + "processInstanceId={} activityId={} activityName={} taskId={} "
                                + "businessKey={}",
                                "%s", "%s", delegateTask.getEventName(),
                                delegateTask.getProcessDefinitionId(), delegateTask.getProcessInstanceId(),
                                delegateTask.getTaskDefinitionKey(), delegateTask.getName(),
                                delegateTask.getId(), delegateTask.getExecution().getProcessBusinessKey());
                    }
                }
                """.formatted(pkg, beanName, className, className, label, className, beanName);
    }

    private static String stripExpression(String expression) {
        if (expression == null) return "";
        String trimmed = expression.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            return trimmed.substring(2, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static String renderListener(String pkg, String className, String beanName, String label) {
        return """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateExecution;
                import org.camunda.bpm.engine.delegate.ExecutionListener;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component("%s")
                public class %s implements ExecutionListener {

                    private static final Logger logger = LoggerFactory.getLogger(%s.class);

                    @Override
                    public void notify(DelegateExecution execution) throws Exception {
                        logger.info("%s INVOKED: listener={} bean={} event={} processDefinitionId={} "
                                + "processInstanceId={} activityId={} activityName={} activityInstanceId={} "
                                + "businessKey={}",
                                "%s", "%s", execution.getEventName(),
                                execution.getProcessDefinitionId(), execution.getProcessInstanceId(),
                                execution.getCurrentActivityId(), execution.getCurrentActivityName(),
                                execution.getActivityInstanceId(), execution.getProcessBusinessKey());
                    }
                }
                """.formatted(pkg, beanName, className, className, label, className, beanName);
    }

    // The generated delegate is what proves an activity actually executed inside the Target Platform,
    // so it logs through SLF4J rather than System.out: an unqualified println carries no timestamp, no
    // level and no process context, which makes it useless as the runtime evidence the Target
    // Platform's own log is supposed to provide. Every field here is generic BPMN/engine state - no
    // process, activity or variable name is baked in.
    private static String render(String pkg, String className, String beanName, String label) {
        return """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateExecution;
                import org.camunda.bpm.engine.delegate.JavaDelegate;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                @Component("%s")
                public class %s implements JavaDelegate {

                    private static final Logger logger = LoggerFactory.getLogger(%s.class);

                    @Override
                    public void execute(DelegateExecution execution) throws Exception {
                        logger.info("%s DELEGATE INVOKED: delegate={} bean={} processDefinitionId={} "
                                + "processInstanceId={} activityId={} activityName={} activityInstanceId={} "
                                + "businessKey={}",
                                "%s", "%s", execution.getProcessDefinitionId(),
                                execution.getProcessInstanceId(), execution.getCurrentActivityId(),
                                execution.getCurrentActivityName(), execution.getActivityInstanceId(),
                                execution.getProcessBusinessKey());
                        try {
                            // Generated stub: this activity's behaviour is supplied by binding a real
                            // component to it, not by anything invented here.
                            logger.info("%s DELEGATE COMPLETED: delegate={} activityId={} "
                                    + "processInstanceId={} result=OK",
                                    "%s", execution.getCurrentActivityId(),
                                    execution.getProcessInstanceId());
                        } catch (RuntimeException e) {
                            logger.error("%s DELEGATE FAILED: delegate={} activityId={} "
                                    + "processInstanceId={} result=ERROR reason={}",
                                    "%s", execution.getCurrentActivityId(),
                                    execution.getProcessInstanceId(), e.toString(), e);
                            throw e;
                        }
                    }
                }
                """.formatted(pkg, beanName, className, className,
                        label, className, beanName,
                        label, className,
                        label, className);
    }

    // package-private: TargetPlatformTwinMirrorGenerator derives the same bean names when it
    // automates a Twin human task, and the two must not drift.
    static String pascal(String id) {
        StringBuilder out = new StringBuilder();
        boolean uppercase = true;
        for (char c : id.toCharArray()) {
            if (!Character.isJavaIdentifierPart(c)) { uppercase = true; continue; }
            out.append(uppercase ? Character.toUpperCase(c) : c);
            uppercase = false;
        }
        if (out.isEmpty() || !Character.isJavaIdentifierStart(out.charAt(0))) out.insert(0, 'X');
        return out.toString();
    }

    static String camel(String id) {
        String value = pascal(id);
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
