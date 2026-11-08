// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.modelimport;

import org.sangam.ports.bpmn.SangamBpmn;
import org.sangam.ports.support.SecureXml;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flowable → Sangam/Operaton model import and incompatibility analysis (FLOWABLE-INTO-OPERATON FC-05 import mapping; EC-21 LEAP-style
 * analyser; R44/R48). Converts {@code flowable:*} BPMN extensions to {@code operaton:*} or {@code sangam:*} and
 * classifies every Flowable construct as {@code MAPPED}, {@code PORTED} (needs a Sangam port that exists) or
 * {@code UNSUPPORTED} (with the reason and gap-list id). Input is parsed with the hardened parser (FLOWABLE-INTO-OPERATON D-51).
 *
 * <p>Notable mappings: {@code variableListenerEventDefinition} → {@code conditionalEventDefinition} with
 * {@code operaton:variableName}/{@code operaton:variableEvents} (FLOWABLE-INTO-OPERATON FC-05, Operaton evaluates it automatically, B-2);
 * {@code flowable:triggerable} / {@code flowable:skipExpression} / {@code terminateAll} → Sangam ports (FLOWABLE-INTO-OPERATON FC-12, FLOWABLE-INTO-OPERATON FC-13,
 * FLOWABLE-INTO-OPERATON FC-22); {@code flowable:eventType} → {@code sangam:eventType} (FLOWABLE-INTO-OPERATON FC-02); {@code flowable:type="external-worker"} →
 * {@code operaton:type="external"} + topic; {@code flowable:async} → {@code operaton:asyncBefore}.
 */
public final class FlowableModelImporter {

    /** WARNING: imports, but its meaning may differ on Sangam and the author must decide (e.g. ADR-D-25 first-match). */
    public enum Classification { MAPPED, PORTED, WARNING, UNSUPPORTED }

    public record Finding(Classification classification, String elementId, String construct, String reference, String note) {
    }

    public record Result(byte[] converted, List<Finding> findings) {
        public boolean importable() {
            return findings.stream().noneMatch(f -> f.classification() == Classification.UNSUPPORTED);
        }
    }

    public static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    /** Flowable still reads Activiti 5 models; the importer treats the Activiti namespace as an alias (FW-27). */
    public static final String ACTIVITI_NS = "http://activiti.org/bpmn";
    public static final String OPERATON_NS = "http://operaton.org/schema/1.0/bpmn";
    private static final String XMLNS = "http://www.w3.org/2000/xmlns/";
    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** Flowable attribute → Operaton attribute (same semantics). */
    private static final Map<String, String> ATTR = Map.ofEntries(
            Map.entry("class", "class"), Map.entry("expression", "expression"), Map.entry("delegateExpression", "delegateExpression"),
            Map.entry("resultVariableName", "resultVariable"), Map.entry("resultVariable", "resultVariable"),
            Map.entry("assignee", "assignee"), Map.entry("candidateUsers", "candidateUsers"), Map.entry("candidateGroups", "candidateGroups"),
            Map.entry("formKey", "formKey"), Map.entry("dueDate", "dueDate"), Map.entry("priority", "priority"),
            Map.entry("category", "category"), Map.entry("exclusive", "exclusive"), Map.entry("initiator", "initiator"),
            Map.entry("calledElementType", "calledElementBinding"), Map.entry("processInstanceName", "processInstanceName"),
            Map.entry("collection", "collection"), Map.entry("elementVariable", "elementVariable"),
            Map.entry("decisionTableReferenceKey", "decisionRef"), Map.entry("historyTimeToLive", "historyTimeToLive"),
            Map.entry("asyncAfter", "asyncAfter"));

    /** Flowable extension elements with an Operaton equivalent of the same local name. */
    private static final Set<String> ELEMENTS = Set.of("field", "string", "expression", "executionListener", "taskListener", "in", "out",
            "formProperty", "properties", "property", "value");

    private FlowableModelImporter() {
    }

    /**
     * Converts one Flowable BPMN model. Input goes through {@link SecureXml#parse} (FW-01: DOCTYPE/entities rejected,
     * {@link SecureXml#MAX_MODEL_BYTES} size limit, depth limit); the importer never uses Flowable's own parsers.
     */
    public static Result convert(byte[] flowableBpmn) {
        Document doc = SecureXml.parse(flowableBpmn);
        List<Finding> findings = new ArrayList<>();
        Element root = doc.getDocumentElement();
        root.setAttributeNS(XMLNS, "xmlns:operaton", OPERATON_NS);
        root.setAttributeNS(XMLNS, "xmlns:sangam", SangamBpmn.NS_URI);
        walk(doc, root, findings);
        // ADR-D-25: Flowable takes the first true flow in document order; Sangam routes strictly. Never silently
        // change the meaning: every gateway that could depend on first-match is reported, and an existing
        // sangam:routingPolicy / sangam:priority is kept as is (attributes in the Sangam namespace are not touched).
        for (org.sangam.ports.bpmn.XorRoutingDom.Gateway g : org.sangam.ports.bpmn.XorRoutingDom.gateways(root)) {
            if (g.nativeFirstMatchSensitive()) {
                findings.add(new Finding(Classification.WARNING, g.id(), "exclusiveGateway first-match", "ADR-D-25",
                        "Flowable picks the first true of " + g.conditionalFlows() + " conditions in document order; "
                                + "Sangam raises AMBIGUOUS_ROUTE when more than one is true. Make the conditions "
                                + "mutually exclusive, or keep first-match explicitly with sangam:routingPolicy=\"priority\" "
                                + "and sangam:priority on every conditional flow"));
            }
            for (String p : g.problems()) {
                findings.add(new Finding(Classification.UNSUPPORTED, g.id(), "exclusiveGateway routing", "ADR-D-25", p));
            }
        }
        return new Result(SecureXml.write(doc), findings);
    }

    private static void walk(Document doc, Element el, List<Finding> out) {
        String id = el.getAttribute("id");
        String local = el.getLocalName();
        if (isVendorNs(el.getNamespaceURI())) {
            if (ELEMENTS.contains(local)) {
                Node renamed = doc.renameNode(el, OPERATON_NS, "operaton:" + local);
                out.add(new Finding(Classification.MAPPED, parentId(el), "flowable:" + local, "A-18", "operaton:" + local));
                el = (Element) renamed;
            } else if ("eventCorrelationParameter".equals(local) || "eventOutParameter".equals(local) || "eventInParameter".equals(local)) {
                out.add(new Finding(Classification.PORTED, parentId(el), "flowable:" + local, "FLOWABLE-INTO-OPERATON FC-02/FLOWABLE-INTO-OPERATON FC-04",
                        "declare correlation parameters in the .event definition (EventDefinition.correlationParameters)"));
            } else if ("eventType".equals(local)) {
                if (el.getParentNode() != null && el.getParentNode().getParentNode() instanceof Element owner) {
                    owner.setAttributeNS(SangamBpmn.NS_URI, "sangam:eventType", el.getTextContent().trim());
                    out.add(new Finding(Classification.PORTED, owner.getAttribute("id"), "flowable:eventType", "FC-02", "sangam:eventType"));
                } else {
                    out.add(new Finding(Classification.UNSUPPORTED, parentId(el), "flowable:eventType", "FC-02",
                            "eventType outside an element's extensionElements"));
                }
            } else if ("variableAggregation".equals(local)) {
                out.add(new Finding(Classification.PORTED, parentId(el), "flowable:variableAggregation", "FC-21",
                        "use sangam:aggregateTo / sangam:aggregateVariables"));
            } else {
                out.add(new Finding(Classification.UNSUPPORTED, parentId(el), "flowable:" + local, "-", "no Operaton/Sangam equivalent"));
            }
        }
        if (BPMN_NS.equals(el.getNamespaceURI())) {
            switch (local) {
                case "adHocSubProcess" -> out.add(new Finding(Classification.PORTED, id, "adHocSubProcess", "FC-01", "patch P-1"));
                case "complexGateway" -> out.add(new Finding(Classification.UNSUPPORTED, id, "complexGateway", "D-52", "not executable on Operaton"));
                case "scriptTask" -> out.add(new Finding(Classification.UNSUPPORTED, id, "scriptTask", "D-46",
                        "in-engine scripts are rejected at deploy (R6); move the script to an external worker"));
                case "eventBasedGateway", "userTask", "serviceTask" -> {
                }
                case "extensionElements" -> mapVariableListener(doc, el, out);
                default -> {
                }
            }
        }
        convertAttributes(el, out);
        NodeList kids = el.getChildNodes();
        List<Element> children = new ArrayList<>();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element c) {
                children.add(c);
            }
        }
        for (Element c : children) {
            walk(doc, c, out);
        }
    }

    private static void convertAttributes(Element el, List<Finding> out) {
        NamedNodeMap attrs = el.getAttributes();
        List<Attr> flowable = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            if (isVendorNs(a.getNamespaceURI())) {
                flowable.add(a);
            }
        }
        String id = el.getAttribute("id");
        for (Attr a : flowable) {
            String name = a.getLocalName();
            String value = a.getValue();
            el.removeAttributeNode(a);
            switch (name) {
                case "async" -> {
                    el.setAttributeNS(OPERATON_NS, "operaton:asyncBefore", value);
                    out.add(new Finding(Classification.MAPPED, id, "flowable:async", "A-10", "operaton:asyncBefore"));
                }
                case "triggerable" -> {
                    el.setAttributeNS(SangamBpmn.NS_URI, "sangam:triggerable", value);
                    out.add(new Finding(Classification.PORTED, id, "flowable:triggerable", "FC-12", "sangam:triggerable"));
                }
                case "skipExpression" -> {
                    el.setAttributeNS(SangamBpmn.NS_URI, "sangam:skipExpression", value);
                    out.add(new Finding(Classification.PORTED, id, "flowable:skipExpression", "FC-13", "sangam:skipExpression"));
                }
                case "terminateAll" -> {
                    if ("true".equals(value)) {
                        ((Element) el.getParentNode()).setAttributeNS(SangamBpmn.NS_URI, "sangam:terminate", "all");
                    }
                    out.add(new Finding(Classification.PORTED, parentId(el), "flowable:terminateAll", "FC-22", "sangam:terminate=all"));
                }
                case "terminateMultiInstance" -> {
                    if ("true".equals(value)) {
                        ((Element) el.getParentNode()).setAttributeNS(SangamBpmn.NS_URI, "sangam:terminate", "multiInstance");
                    }
                    out.add(new Finding(Classification.PORTED, parentId(el), "flowable:terminateMultiInstance", "FC-22", "sangam:terminate=multiInstance"));
                }
                case "type" -> mapTaskType(el, value, out);
                case "decisionTableReferenceKey" -> {
                    el.setAttributeNS(OPERATON_NS, "operaton:decisionRef", value);
                    out.add(new Finding(Classification.MAPPED, id, "flowable:decisionTableReferenceKey", "A-3", "operaton:decisionRef"));
                }
                case "topic" -> el.setAttributeNS(OPERATON_NS, "operaton:topic", value);
                case "businessCalendarName" -> out.add(new Finding(Classification.PORTED, id, "flowable:businessCalendarName", "FC-23",
                        "prefix the timer text with cal:" + value + ": (WS-ENGINE tagged calendar)"));
                case "sameDeployment" -> {
                    if ("true".equals(value)) {
                        el.setAttributeNS(OPERATON_NS, "operaton:calledElementBinding", "deployment");
                    }
                    out.add(new Finding(Classification.MAPPED, id, "flowable:sameDeployment", "D-12", "calledElementBinding=deployment"));
                }
                case "fallbackToDefaultTenant" -> out.add(new Finding(Classification.PORTED, id, "flowable:fallbackToDefaultTenant", "FC-33",
                        "use TenantDefinitionResolver in operaton:calledElementTenantId"));
                default -> {
                    String target = ATTR.get(name);
                    if (target != null) {
                        el.setAttributeNS(OPERATON_NS, "operaton:" + target, value);
                        out.add(new Finding(Classification.MAPPED, id, "flowable:" + name, "A-1", "operaton:" + target));
                    } else {
                        out.add(new Finding(Classification.UNSUPPORTED, id, "flowable:" + name, "-", "attribute dropped"));
                    }
                }
            }
        }
    }

    private static void mapTaskType(Element el, String type, List<Finding> out) {
        String id = el.getAttribute("id");
        switch (type) {
            case "external-worker" -> {
                el.setAttributeNS(OPERATON_NS, "operaton:type", "external");
                out.add(new Finding(Classification.MAPPED, id, "flowable:type=external-worker", "FC-36", "operaton external task"));
            }
            case "http" -> {
                el.setAttributeNS(OPERATON_NS, "operaton:type", "external");
                el.setAttributeNS(OPERATON_NS, "operaton:topic", "sangam.http");
                out.add(new Finding(Classification.PORTED, id, "flowable:type=http", "FC-06", "WS-INTEGRATION HTTP connector worker"));
            }
            case "mail" -> {
                el.setAttributeNS(OPERATON_NS, "operaton:type", "external");
                el.setAttributeNS(OPERATON_NS, "operaton:topic", "sangam.mail");
                out.add(new Finding(Classification.PORTED, id, "flowable:type=mail", "FC-07", "WS-INTEGRATION mail connector worker"));
            }
            case "shell" -> out.add(new Finding(Classification.UNSUPPORTED, id, "flowable:type=shell", "FC-10",
                    "in-engine shell is not ported (R6); use an out-of-process command worker"));
            case "camel" -> out.add(new Finding(Classification.UNSUPPORTED, id, "flowable:type=camel", "FC-08", "stage E optional connector"));
            case "mule" -> out.add(new Finding(Classification.UNSUPPORTED, id, "flowable:type=mule", "FC-09", "not present in Flowable 8"));
            case "dmn" -> mapDmnTask(el, out);
            case "send-event" -> {
                el.setAttributeNS(OPERATON_NS, "operaton:class", "org.sangam.ports.events.SendEventDelegate");
                out.add(new Finding(Classification.PORTED, id, "flowable:type=send-event", "FC-02", "SendEventDelegate"));
            }
            default -> out.add(new Finding(Classification.UNSUPPORTED, id, "flowable:type=" + type, "-", "unknown task type"));
        }
    }

    /**
     * FW-27: Flowable's {@code serviceTask flowable:type="dmn"} becomes an Operaton {@code businessRuleTask} with
     * {@code operaton:decisionRef} (from the {@code decisionTableReferenceKey} field) and deployment binding (FLOWABLE-INTO-OPERATON D-12). Without
     * a decision key the task is UNSUPPORTED: left as a plain service task it would deploy and do nothing.
     */
    private static void mapDmnTask(Element el, List<Finding> out) {
        String id = el.getAttribute("id");
        String key = el.getAttributeNS(OPERATON_NS, "decisionRef");
        for (String ns : new String[]{FLOWABLE_NS, ACTIVITI_NS}) {
            if (key.isEmpty()) {
                key = el.getAttributeNS(ns, "decisionTableReferenceKey");
            }
        }
        Element keyField = null;
        NodeList fields = el.getElementsByTagNameNS("*", "field");
        for (int i = 0; i < fields.getLength(); i++) {
            Element f = (Element) fields.item(i);
            if (isVendorNs(f.getNamespaceURI()) && "decisionTableReferenceKey".equals(f.getAttribute("name"))) {
                keyField = f;
                String v = f.getAttribute("stringValue");
                if (v.isEmpty()) {
                    v = f.getTextContent().trim();
                }
                if (!v.isEmpty()) {
                    key = v;
                }
            }
        }
        if (key == null || key.isEmpty()) {
            out.add(new Finding(Classification.UNSUPPORTED, id, "flowable:type=dmn", "A-3", "no decisionTableReferenceKey"));
            return;
        }
        if (keyField != null) {
            keyField.getParentNode().removeChild(keyField);
        }
        el.getOwnerDocument().renameNode(el, BPMN_NS, prefix(el) + "businessRuleTask");
        el.setAttributeNS(OPERATON_NS, "operaton:decisionRef", key);
        el.setAttributeNS(OPERATON_NS, "operaton:decisionRefBinding", "deployment");
        if (el.getAttributeNS(OPERATON_NS, "resultVariable").isEmpty()) {
            el.setAttributeNS(OPERATON_NS, "operaton:mapDecisionResult", "singleResult");
            el.setAttributeNS(OPERATON_NS, "operaton:resultVariable", "decisionResult");
        }
        out.add(new Finding(Classification.MAPPED, id, "flowable:type=dmn", "A-3",
                "businessRuleTask decisionRef=" + key + "; check the result mapping (Flowable writes outputs as variables)"));
    }

    static boolean isVendorNs(String ns) {
        return FLOWABLE_NS.equals(ns) || ACTIVITI_NS.equals(ns);
    }

    /** FLOWABLE-INTO-OPERATON FC-05: {@code flowable:variableListenerEventDefinition} becomes an Operaton conditional event definition. */
    private static void mapVariableListener(Document doc, Element ext, List<Finding> out) {
        NodeList l = ext.getElementsByTagNameNS("*", "variableListenerEventDefinition");
        List<Element> defs = new ArrayList<>();
        for (int i = 0; i < l.getLength(); i++) {
            defs.add((Element) l.item(i));
        }
        for (Element vl : defs) {
            if (!isVendorNs(vl.getNamespaceURI()) || !(ext.getParentNode() instanceof Element event)) {
                continue;
            }
            Element cond = doc.createElementNS(BPMN_NS, prefix(event) + "conditionalEventDefinition");
            cond.setAttributeNS(OPERATON_NS, "operaton:variableName", vl.getAttribute("variableName"));
            String types = vl.getAttribute("variableChangeType");
            cond.setAttributeNS(OPERATON_NS, "operaton:variableEvents", types.isEmpty() || "all".equals(types) ? "create, update" : types);
            Element c = doc.createElementNS(BPMN_NS, prefix(event) + "condition");
            c.setAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "xsi:type", prefix(event) + "tFormalExpression");
            c.setTextContent("${true}");
            cond.appendChild(c);
            event.appendChild(cond);
            ext.removeChild(vl);
            out.add(new Finding(Classification.MAPPED, event.getAttribute("id"), "flowable:variableListenerEventDefinition", "FC-05",
                    "conditionalEventDefinition(variableName=" + vl.getAttribute("variableName") + ")"));
        }
    }

    private static String prefix(Element e) {
        return e.getPrefix() == null ? "" : e.getPrefix() + ":";
    }

    private static String parentId(Element el) {
        Node n = el.getParentNode();
        while (n instanceof Element e) {
            if (!e.getAttribute("id").isEmpty()) {
                return e.getAttribute("id");
            }
            n = n.getParentNode();
        }
        return null;
    }
}
