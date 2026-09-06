package com.metaml.workbench.codegen;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

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
import org.w3c.dom.NodeList;

// Derives a Target Platform Twin BPMN by mirroring the proxy's graph verbatim, except for two
// things. TwinModelGenerator's receiveTask/serviceTask rewrite is not used here: it waits on
// ${twinAutomationDelegate}, a bean a generated Target Platform does not have.
//
// - process id/name get a "_twin" suffix, so it is a distinct process definition
// - every camunda:topic gets a "Twin" suffix, because external-task topics are global to the engine
//   and proxy and twin would otherwise steal each other's tasks
//
// Signal names and activity ids are deliberately left alone: the shared signal name is how
// SignalBroadcaster/PairRegistry recognise proxy and twin as synchronising on the same point.
@Component
public class TargetPlatformTwinMirrorGenerator {

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String TWIN_ID_SUFFIX = "_twin";
    private static final String TWIN_NAME_SUFFIX = " (twin)";
    private static final String TWIN_TOPIC_SUFFIX = "Twin";

    public String mirror(String proxyBpmnXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(proxyBpmnXml.getBytes(StandardCharsets.UTF_8)));

            retagProcessElement(document);
            suffixExternalTaskTopics(document);

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

    // One worker's topic subscription is global across the whole engine (see ExternalTaskPoller.poll's own fetchAndLock) - without this, a twin external task and its proxy counterpart of the same name would both be served by whichever worker happened to be generated for that topic, silently running the wrong side's logic.
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
