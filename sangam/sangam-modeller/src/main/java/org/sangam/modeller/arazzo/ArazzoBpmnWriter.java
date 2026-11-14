// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.arazzo;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Writes the BPMN draft of an analysed Arazzo document ({@link ArazzoImporter}): one executable process per workflow,
 * a start event, the steps in document order and an end event. Every value comes from the analysis; nothing is
 * evaluated. The Arazzo request, success criteria and outputs go into static {@code operaton:properties} (never read as
 * expressions by the engine), so a {@code ${...}} inside a payload stays text.
 */
final class ArazzoBpmnWriter {

    static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    static final String OPERATON_NS = "http://operaton.org/schema/1.0/bpmn";
    /** The Sangam BPMN extension namespace ({@code org.sangam.core.engine.RoutingPolicy.NS_URI}). */
    static final String SANGAM_NS = "http://sangam.org/schema/1.0/bpmn";

    /** What follows a step on success. */
    sealed interface Next permits NextStep, NextEnd {
    }

    /** The step that runs next (by its BPMN id). */
    record NextStep(String activityId) implements Next {
    }

    /** The workflow ends. */
    record NextEnd() implements Next {
    }

    /** An operation step: a service task bound to an API contract operation. */
    record OperationTask(String id, String name, String operationRef, Map<String, String> properties, Next next) {
    }

    /** A step that runs another workflow of the same document. */
    record WorkflowCall(String id, String name, String calledProcessId, Next next) {
    }

    /** One workflow as a process: its id, name, history time to live (may be null) and steps in order. */
    record Process(String id, String name, String historyTimeToLive, List<Object> steps) {
    }

    private ArazzoBpmnWriter() {
    }

    /** The BPMN document of {@code processes}; {@code definitionsId} is unique in it. */
    static byte[] write(String definitionsId, String targetNamespace, List<Process> processes) {
        Document doc = newDocument();
        Element defs = doc.createElementNS(BPMN_NS, "bpmn:definitions");
        defs.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:bpmn", BPMN_NS);
        defs.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:operaton", OPERATON_NS);
        defs.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:sangam", SANGAM_NS);
        defs.setAttribute("id", definitionsId);
        defs.setAttribute("targetNamespace", targetNamespace);
        doc.appendChild(defs);
        for (Process p : processes) {
            defs.appendChild(process(doc, p));
        }
        return serialize(doc);
    }

    private static Element process(Document doc, Process p) {
        Element proc = doc.createElementNS(BPMN_NS, "bpmn:process");
        proc.setAttribute("id", p.id());
        if (p.name() != null && !p.name().isBlank()) {
            proc.setAttribute("name", p.name());
        }
        proc.setAttribute("isExecutable", "true");
        if (p.historyTimeToLive() != null) {
            proc.setAttributeNS(OPERATON_NS, "operaton:historyTimeToLive", p.historyTimeToLive());
        }
        // task ids are <processId>.<stepId> with exactly one '.' (Arazzo ids have none), so '..' never collides
        String start = p.id() + "..start";
        String end = p.id() + "..end";
        Element se = doc.createElementNS(BPMN_NS, "bpmn:startEvent");
        se.setAttribute("id", start);
        proc.appendChild(se);
        List<String[]> flows = new ArrayList<>();
        List<Object> steps = p.steps();
        flows.add(new String[] {start, steps.isEmpty() ? end : id(steps.get(0))});
        for (int i = 0; i < steps.size(); i++) {
            Object s = steps.get(i);
            proc.appendChild(s instanceof OperationTask t ? serviceTask(doc, t) : callActivity(doc, (WorkflowCall) s));
            Next next = s instanceof OperationTask t ? t.next() : ((WorkflowCall) s).next();
            String target = switch (next) {
                case NextStep n -> n.activityId();
                case NextEnd e -> end;
                case null -> i + 1 < steps.size() ? id(steps.get(i + 1)) : end;
            };
            flows.add(new String[] {id(s), target});
        }
        Element ee = doc.createElementNS(BPMN_NS, "bpmn:endEvent");
        ee.setAttribute("id", end);
        proc.appendChild(ee);
        int n = 0;
        for (String[] f : flows) {
            Element sf = doc.createElementNS(BPMN_NS, "bpmn:sequenceFlow");
            sf.setAttribute("id", p.id() + "..flow" + (++n));
            sf.setAttribute("sourceRef", f[0]);
            sf.setAttribute("targetRef", f[1]);
            proc.appendChild(sf);
        }
        return proc;
    }

    private static String id(Object step) {
        return step instanceof OperationTask t ? t.id() : ((WorkflowCall) step).id();
    }

    private static Element serviceTask(Document doc, OperationTask t) {
        Element e = doc.createElementNS(BPMN_NS, "bpmn:serviceTask");
        e.setAttribute("id", t.id());
        e.setAttribute("name", t.name());
        e.setAttributeNS(OPERATON_NS, "operaton:type", "external");
        e.setAttributeNS(OPERATON_NS, "operaton:topic", ArazzoImporter.TOPIC);
        e.setAttributeNS(SANGAM_NS, "sangam:operationRef", t.operationRef());
        if (!t.properties().isEmpty()) {
            Element ext = doc.createElementNS(BPMN_NS, "bpmn:extensionElements");
            Element props = doc.createElementNS(OPERATON_NS, "operaton:properties");
            for (Map.Entry<String, String> p : new LinkedHashMap<>(t.properties()).entrySet()) {
                Element prop = doc.createElementNS(OPERATON_NS, "operaton:property");
                prop.setAttribute("name", p.getKey());
                prop.setAttribute("value", p.getValue());
                props.appendChild(prop);
            }
            ext.appendChild(props);
            e.appendChild(ext);
        }
        return e;
    }

    private static Element callActivity(Document doc, WorkflowCall c) {
        Element e = doc.createElementNS(BPMN_NS, "bpmn:callActivity");
        e.setAttribute("id", c.id());
        e.setAttribute("name", c.name());
        e.setAttribute("calledElement", c.calledProcessId());
        e.setAttributeNS(OPERATON_NS, "operaton:calledElementBinding", "deployment");
        Element ext = doc.createElementNS(BPMN_NS, "bpmn:extensionElements");
        Element in = doc.createElementNS(OPERATON_NS, "operaton:in");
        in.setAttribute("variables", "all");
        Element out = doc.createElementNS(OPERATON_NS, "operaton:out");
        out.setAttribute("variables", "all");
        ext.appendChild(in);
        ext.appendChild(out);
        e.appendChild(ext);
        return e;
    }

    private static Document newDocument() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            return f.newDocumentBuilder().newDocument();
        } catch (Exception e) {
            throw new IllegalStateException("no XML document builder", e);
        }
    }

    private static byte[] serialize(Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(b));
            return b.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("cannot write the BPMN draft", e);
        }
    }
}
