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

// Generates the Spring beans the fixed com.tp.TargetPlatform template expects. The BPMN is normalised
// to reference each generated bean by BPMN element id, so delegateExpression and class inputs behave
// the same way in the target platform.
//
// Lockstep synchronization for delegate-expression BPMNs: those have no signal catch events, so the
// proxy runs straight through with no wait state and the twin's receiveTask/message subscriptions are
// invisible to SignalBroadcaster, which only queries signals. This generator therefore inserts a
// signal intermediateCatchEvent after each proxy serviceTask, and replaces each twin receiveTask with
// a catch event on the SAME sync_<activityId> name, giving the existing REQUEST/RESPONSE handoff two
// subscription points to pair.
// Load-bearing assumption: SignalBroadcaster only concludes the twin has advanced from state read
// after signalEventReceived() returns, which is a valid proof only because the twin's _automate
// delegate runs synchronously in the same Camunda transaction. An automation delegate that hands work
// to another thread and returns early would silently break that invariant.
@Component
public class TargetPlatformSourceGenerator {
    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    static final String SYNC_SIGNAL_PREFIX = "sync_";

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
                String expression = element.getAttributeNS(CAMUNDA_NS, "delegateExpression");
                String javaClass = element.getAttributeNS(CAMUNDA_NS, "class");
                if ((!activity && !event) || (expression.isBlank() && javaClass.isBlank())) continue;
                String id = element.getAttribute("id");
                if (id == null || id.isBlank()) throw new IllegalArgumentException("Delegated BPMN element has no id");
                // The twin side always gets its own bean name: an authored twin is free to reuse the proxy's activity
                // ids, and Spring's @Component("...") registers by that literal string regardless of package, so two
                // classes claiming one bean name fail startup with ConflictingBeanDefinitionException.
                // Class names are left unqualified - Java tolerates identical simple names across packages.
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
                if (activity && !twin) {
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
                syncSignalNames = replaceTwinReceiveTasksWithSignals(document, syncActivityIdsFromProxy);
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

    // Lockstep, twin side: replace TwinModelGenerator's receiveTasks - whose message subscriptions
    // SignalBroadcaster cannot poll - with signal catch events on the same sync_<activityId> names the
    // proxy waits on. The receiveTask's implicit parallel split is preserved: one branch fires the
    // _automate delegate, the other advances to the next gate.
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

    // Returns candidate unchanged when no element already uses it as an id, otherwise probes candidate_2,
    // candidate_3, ... deterministically - never random, since the same source BPMN must always generate
    // the same output.
    // Covers an activity id that already ends in something like "_evt", and re-running generation on a
    // BPMN that has already been through this transformation once.
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

    // Unlike element ids, a signal NAME cannot be renamed on collision: SignalBroadcaster pairs proxy and
    // twin purely by matching name, and the twin is handed this exact name with no channel back to
    // renegotiate. Failing loudly and telling the caller to rename beats guessing.
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

    // ── Existing helpers (unchanged) ───────────────────────────────────────────

    // camunda:executionListener is a child of extensionElements rather than an attribute, so the activity
    // loop above never sees it - but it names a delegateExpression bean the same way, and the engine
    // throws PropertyNotFoundException the moment the listener's own event fires if that bean is missing.
    // Deduplicated by bean name rather than element id: one listener is typically wired onto many
    // activities, and it has no element of its own to derive an id from.
    private List<GeneratedSource> scanExecutionListeners(Document document, boolean twin) {
        List<GeneratedSource> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        NodeList listeners = document.getElementsByTagNameNS(CAMUNDA_NS, "executionListener");
        for (int i = 0; i < listeners.getLength(); i++) {
            Element listener = (Element) listeners.item(i);
            String originalBeanName = stripExpression(listener.getAttribute("delegateExpression"));
            if (originalBeanName.isBlank()) continue;
            // The twin side always gets its own bean name: proxy and twin can genuinely name the same listener - a
            // structural mirror always does - and Spring refuses to start with two classes under one bean name.
            // The BPMN is rewritten to match, as with the delegateExpressions above.
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

    // camunda:taskListener has the same shape as executionListener but is a different Camunda API: it
    // fires on the user task's own lifecycle events and is invoked through TaskListener.notify(DelegateTask),
    // not ExecutionListener.notify(DelegateExecution). A stub implementing the wrong interface throws
    // ClassCastException the first time the listener event fires.
    // Mirrors scanExecutionListeners otherwise - same twin bean suffixing, same dedup by bean name, same
    // listeners/ output location.
    // Only the delegateExpression form is generated: camunda:class would have to land at the exact package
    // the BPMN names, and a raw UEL expression names something this cannot safely turn into a class.
    // Both are left unrewritten rather than mis-generated.
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
                import org.springframework.stereotype.Component;

                @Component("%s")
                public class %s implements TaskListener {
                    @Override
                    public void notify(DelegateTask delegateTask) {
                        System.out.println("******************** %s - %s ---- Spring Bean invoked");
                    }
                }
                """.formatted(pkg, beanName, className, label, beanName);
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
                import org.springframework.stereotype.Component;

                @Component("%s")
                public class %s implements ExecutionListener {
                    @Override
                    public void notify(DelegateExecution execution) throws Exception {
                        System.out.println("******************** %s - %s ---- Spring Bean invoked");
                    }
                }
                """.formatted(pkg, beanName, className, label, beanName);
    }

    private static String render(String pkg, String className, String beanName, String label) {
        return """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateExecution;
                import org.camunda.bpm.engine.delegate.JavaDelegate;
                import org.springframework.stereotype.Component;

                @Component("%s")
                public class %s implements JavaDelegate {
                    @Override
                    public void execute(DelegateExecution arg0) throws Exception {
                        System.out.println("******************** %s - %s ---- Spring Bean invoked");
                    }
                }
                """.formatted(pkg, beanName, className, label, beanName);
    }

    private static String pascal(String id) {
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

    private static String camel(String id) {
        String value = pascal(id);
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
