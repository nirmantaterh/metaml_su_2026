package com.metaml.workbench.codegen;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

    // Derives a mirrored Twin BPMN model with external task topics and stabilized diagram interchange.
@Component
public class TargetPlatformTwinMirrorGenerator {

    private static final Logger logger = LoggerFactory.getLogger(TargetPlatformTwinMirrorGenerator.class);

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String TWIN_ID_SUFFIX = "_twin";
    private static final String TWIN_NAME_SUFFIX = " (twin)";
    private static final String TWIN_TOPIC_SUFFIX = "Twin";

    // Task types the Twin cannot execute on its own. A userTask is a wait state with no engine-side
    // actor, so a mirrored one parks forever: nothing invokes it, the proxy's matching sync signal
    // never receives its RESPONSE, and the pair deadlocks. manualTask and a bare task are
    // pass-throughs - they do not block, but they invoke nothing either, so the Twin has no delegate
    // to observe or to later bind an evolved component onto. Both are converted to serviceTask so
    // every Twin activity is executable and observable, and no Twin activity is a human task.
    private static final Set<String> HUMAN_OR_INERT_TASKS = Set.of("userTask", "manualTask", "task");

    // camunda attributes that only mean anything on a userTask. Left on a serviceTask they describe an
    // assignment that can never happen, and Camunda's own BPMN parser rejects several of them.
    private static final List<String> USER_TASK_ONLY_ATTRIBUTES = List.of(
            "assignee", "candidateUsers", "candidateGroups", "dueDate", "followUpDate", "priority",
            "formKey", "formRef", "formRefBinding", "formRefVersion");

    // A camunda:taskListener only ever fires on a userTask, so automating the Twin's human tasks would
    // silently stop every Twin task listener the model declared. These two events have exact
    // serviceTask analogues and are retagged as executionListeners instead; the rest (assignment,
    // update, delete, timeout) describe things that only happen to a human task and have none.
    private static final Map<String, String> TASK_EVENT_TO_EXECUTION_EVENT =
            Map.of("create", "start", "complete", "end");

    public String mirror(String proxyBpmnXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(proxyBpmnXml.getBytes(StandardCharsets.UTF_8)));

            retagProcessElement(document);
            suffixExternalTaskTopics(document);
            automateHumanTasks(document);

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter xml = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(xml));
            return xml.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not mirror BPMN into a Twin: " + e.getMessage(), e);
        }
    }

    // Rewrites every human or inert task into an executable serviceTask carrying a delegateExpression.
    // The bean name is derived from the activity id exactly as TargetPlatformSourceGenerator derives
    // it, because that generator is what actually emits the @Component behind this expression: it
    // rescans this mirrored XML, sees the delegateExpression, and writes the class. Anything left as a
    // userTask here is invisible to it and gets no delegate at all.
    private static void automateHumanTasks(Document document) {
        NodeList all = document.getElementsByTagNameNS("*", "*");
        // Snapshot first: renameNode mutates the live NodeList this loop would otherwise be walking.
        Set<Element> targets = new LinkedHashSet<>();
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (element.getLocalName() != null && HUMAN_OR_INERT_TASKS.contains(element.getLocalName())) {
                targets.add(element);
            }
        }
        Set<String> automated = new LinkedHashSet<>();
        for (Element element : targets) {
            String id = element.getAttribute("id");
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Twin cannot automate a " + element.getLocalName()
                        + " with no id - every activity needs one to derive a delegate bean name from");
            }
            String was = element.getLocalName();
            String prefix = element.getPrefix();
            String qualifiedName = (prefix == null || prefix.isBlank())
                    ? "serviceTask" : prefix + ":serviceTask";
            // renameNode keeps the element's id, name, children (incoming/outgoing, extensionElements)
            // and its BPMNDI shape reference, which is by bpmnElement id rather than by element type.
            Element serviceTask = (Element) document.renameNode(element, element.getNamespaceURI(),
                    qualifiedName);
            retagTaskListenersAsExecutionListeners(document, serviceTask, id);
            stripUserTaskOnlyMarkup(serviceTask);
            // An already-delegated or external task keeps whatever producer the model named for it -
            // this only supplies one where the model left the activity with no executable behaviour.
            boolean alreadyExecutable = !serviceTask.getAttributeNS(CAMUNDA_NS, "delegateExpression").isBlank()
                    || !serviceTask.getAttributeNS(CAMUNDA_NS, "class").isBlank()
                    || !serviceTask.getAttributeNS(CAMUNDA_NS, "expression").isBlank()
                    || !serviceTask.getAttributeNS(CAMUNDA_NS, "type").isBlank();
            if (!alreadyExecutable) {
                serviceTask.setAttributeNS(CAMUNDA_NS, "camunda:delegateExpression",
                        "${" + TargetPlatformSourceGenerator.camel(id) + "}");
            }
            automated.add(was + " " + id);
        }
        if (!automated.isEmpty()) {
            logger.info("Twin mirror converted {} non-executable activity/activities into serviceTasks: {}",
                    automated.size(), automated);
        }
    }

    // Rewrites this activity's camunda:taskListener children into camunda:executionListener, mapping
    // create->start and complete->end. The delegateExpression is carried over untouched, so
    // TargetPlatformSourceGenerator generates the same bean under the same name - it just implements
    // ExecutionListener rather than TaskListener, which is the only interface a serviceTask can invoke.
    private static void retagTaskListenersAsExecutionListeners(Document document, Element serviceTask,
            String activityId) {
        for (Element listener : childListeners(serviceTask, "taskListener")) {
            String taskEvent = listener.getAttribute("event");
            String executionEvent = TASK_EVENT_TO_EXECUTION_EVENT.get(taskEvent);
            if (executionEvent == null) {
                // Dropped rather than silently retagged onto a lifecycle point that means something
                // else - a listener on the wrong event is worse than a listener the log says is gone.
                logger.warn("Twin activity {} drops its taskListener on event '{}': an automated Twin "
                        + "activity has no such lifecycle point (delegateExpression was {})",
                        activityId, taskEvent, listener.getAttribute("delegateExpression"));
                listener.getParentNode().removeChild(listener);
                continue;
            }
            String prefix = listener.getPrefix();
            String qualifiedName = (prefix == null || prefix.isBlank())
                    ? "executionListener" : prefix + ":executionListener";
            Element retagged = (Element) document.renameNode(listener, listener.getNamespaceURI(),
                    qualifiedName);
            retagged.setAttribute("event", executionEvent);
        }
    }

    // Direct camunda:<localName> children of the activity's extensionElements block.
    private static List<Element> childListeners(Element serviceTask, String localName) {
        List<Element> found = new java.util.ArrayList<>();
        NodeList children = serviceTask.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element extensions)
                    || !"extensionElements".equals(extensions.getLocalName())) {
                continue;
            }
            NodeList extensionChildren = extensions.getChildNodes();
            for (int j = 0; j < extensionChildren.getLength(); j++) {
                if (extensionChildren.item(j) instanceof Element element
                        && localName.equals(element.getLocalName())
                        && CAMUNDA_NS.equals(element.getNamespaceURI())) {
                    found.add(element);
                }
            }
        }
        return found;
    }

    // Drops userTask-only camunda attributes and any camunda:formData block, then removes the
    // extensionElements wrapper if that emptied it, so the mirrored XML stays schema-clean.
    private static void stripUserTaskOnlyMarkup(Element serviceTask) {
        for (String attribute : USER_TASK_ONLY_ATTRIBUTES) {
            serviceTask.removeAttributeNS(CAMUNDA_NS, attribute);
        }
        NodeList children = serviceTask.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (!(child instanceof Element extensions)
                    || !"extensionElements".equals(extensions.getLocalName())) {
                continue;
            }
            NodeList extensionChildren = extensions.getChildNodes();
            for (int j = extensionChildren.getLength() - 1; j >= 0; j--) {
                Node extension = extensionChildren.item(j);
                if (extension instanceof Element element && "formData".equals(element.getLocalName())
                        && CAMUNDA_NS.equals(element.getNamespaceURI())) {
                    extensions.removeChild(extension);
                }
            }
            if (!hasElementChild(extensions)) {
                serviceTask.removeChild(extensions);
            }
        }
    }

    private static boolean hasElementChild(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                return true;
            }
        }
        return false;
    }

    private static void retagProcessElement(Document document) {
        NodeList processes = document.getElementsByTagNameNS("*", "process");
        for (int i = 0; i < processes.getLength(); i++) {
            Element process = (Element) processes.item(i);
            if (!"true".equals(process.getAttribute("isExecutable"))) continue;
            String id = process.getAttribute("id");
            if (id != null && !id.isBlank()) {
                process.setAttribute("id", id + TWIN_ID_SUFFIX);
            }
            String name = process.getAttribute("name");
            if (name != null && !name.isBlank()) {
                process.setAttribute("name", name + TWIN_NAME_SUFFIX);
            }
        }
    }

    // Suffix external task topics to isolate twin tasks from proxy tasks.
    private static void suffixExternalTaskTopics(Document document) {
        NodeList all = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            String topic = element.getAttributeNS(CAMUNDA_NS, "topic");
            if (topic == null || topic.isBlank()) continue;
            element.setAttributeNS(CAMUNDA_NS, "camunda:topic", topic + TWIN_TOPIC_SUFFIX);
        }
    }
}
