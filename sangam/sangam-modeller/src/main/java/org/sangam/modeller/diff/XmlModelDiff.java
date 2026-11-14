// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.diff;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.sangam.modeller.diff.ModelDiff.Change;
import org.sangam.modeller.diff.ModelDiff.Kind;
import org.sangam.modeller.diff.ModelDiff.Level;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * The XML side of {@link ModelDiff}: BPMN 2.0, DMN 1.1-1.5 and CMMN 1.1. Every element with an {@code id} is one
 * comparable unit; its attributes, its text and its child elements without an id are flattened into named properties
 * ({@code @name}, {@code conditionExpression[1]/#text}, {@code extensionElements[1]/operaton:inputOutput[1]/...}).
 * Diagram interchange and modeller-tool attributes are not part of a unit (see {@link ModelDiff}).
 */
final class XmlModelDiff {

    static final String ROOT_KEY = "(root)";

    /** Element types whose addition or removal is a flow change. */
    static final Set<String> FLOW_TYPES = Set.of("sequenceFlow", "messageFlow", "association", "dataInputAssociation",
            "dataOutputAssociation", "informationRequirement", "knowledgeRequirement", "authorityRequirement");
    /** Attributes that wire elements together. */
    static final Set<String> FLOW_ATTRIBUTES = Set.of("sourceRef", "targetRef", "default", "attachedToRef", "href",
            "sentryRef", "processRef", "decisionRef", "caseRef");
    /** Property path segments that are wiring (DMN requirements, CMMN sentries and on-parts). */
    static final Set<String> FLOW_SEGMENTS = Set.of("informationRequirement", "knowledgeRequirement",
            "authorityRequirement", "requiredDecision", "requiredInput", "requiredKnowledge", "requiredAuthority",
            "entryCriterion", "exitCriterion", "planItemOnPart", "caseFileItemOnPart", "incoming", "outgoing");
    /** Child elements that hold an expression (BPMN conditions, timers, scripts, mappings; DMN FEEL text). */
    static final Set<String> EXPRESSION_ELEMENTS = Set.of("conditionExpression", "completionCondition",
            "loopCardinality", "script", "timeDate", "timeDuration", "timeCycle", "condition", "text", "inputValues",
            "outputValues", "allowedValues", "defaultOutputEntry", "inputParameter", "outputParameter", "expression",
            "string", "in", "out", "ifPart", "body", "messagePayload");
    /** Attributes that hold an expression. */
    static final Set<String> EXPRESSION_ATTRIBUTES = Set.of("expression", "delegateExpression", "sourceExpression",
            "collection", "conditionExpression", "variableMappingDelegateExpression");
    /** DMN parents whose child order is semantic (rule order for FIRST/RULE ORDER, output order for PRIORITY). */
    static final Set<String> ORDERED_PARENTS = Set.of("decisionTable", "relation", "list", "context");
    /** Modeller-tool attributes that never change behaviour. */
    static final Set<String> TOOL_ATTRIBUTES = Set.of("exporter", "exporterVersion");
    static final Set<String> TOOL_NAMESPACES = Set.of("http://camunda.org/schema/modeler/1.0",
            "http://operaton.org/schema/modeler/1.0");

    private static final Map<String, String> PREFIXES = Map.of(
            "http://operaton.org/schema/1.0/bpmn", "operaton:",
            "http://operaton.org/schema/1.0/dmn", "operaton:",
            "http://operaton.org/schema/1.0/cmmn", "operaton:",
            "http://camunda.org/schema/1.0/bpmn", "camunda:",
            "http://camunda.org/schema/1.0/dmn", "camunda:",
            "http://camunda.org/schema/1.0/cmmn", "camunda:",
            "http://sangam.org/schema/1.0/bpmn", "sangam:",
            XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:");

    private XmlModelDiff() {
    }

    /** One comparable unit: an element with an id. */
    record Unit(String key, String type, String name, String parentKey, Map<String, String> properties,
                List<String> orderedChildren) {
    }

    static List<Change> compare(String resource, byte[] before, byte[] after) {
        Map<String, Unit> a = ModelDiff.nonEmpty(before) ? units(resource, before) : Map.of();
        Map<String, Unit> b = ModelDiff.nonEmpty(after) ? units(resource, after) : Map.of();
        List<Change> out = new ArrayList<>();
        // an element inside a removed (added) element is covered by its parent's line
        for (Unit u : a.values()) {
            if (!b.containsKey(u.key()) && (u.parentKey() == null || b.containsKey(u.parentKey()))) {
                out.add(whole(Kind.REMOVED, u));
            }
        }
        for (Unit u : b.values()) {
            if (!a.containsKey(u.key()) && (u.parentKey() == null || a.containsKey(u.parentKey()))) {
                out.add(whole(Kind.ADDED, u));
            }
        }
        for (Unit old : a.values()) {
            Unit now = b.get(old.key());
            if (now != null) {
                compareUnit(old, now, out);
            }
        }
        return out;
    }

    private static Change whole(Kind kind, Unit u) {
        Level level = FLOW_TYPES.contains(local(u.type())) ? Level.FLOW : Level.ELEMENT;
        return new Change(level, kind, u.key(), u.type(), u.name(), "", null, null);
    }

    private static void compareUnit(Unit old, Unit now, List<Change> out) {
        String name = now.name().isEmpty() ? old.name() : now.name();
        if (!old.type().equals(now.type())) {
            out.add(new Change(Level.ELEMENT, Kind.CHANGED, now.key(), now.type(), name, "#type", old.type(), now.type()));
        }
        if (!Objects.equals(old.parentKey(), now.parentKey())) {
            out.add(new Change(Level.ELEMENT, Kind.CHANGED, now.key(), now.type(), name, "#parent", old.parentKey(),
                    now.parentKey()));
        }
        Set<String> paths = new TreeSet<>(old.properties().keySet());
        paths.addAll(now.properties().keySet());
        for (String p : paths) {
            String x = old.properties().get(p);
            String y = now.properties().get(p);
            if (Objects.equals(x, y)) {
                continue;
            }
            Kind kind = x == null ? Kind.ADDED : y == null ? Kind.REMOVED : Kind.CHANGED;
            out.add(new Change(level(p), kind, now.key(), now.type(), name, p, x, y));
        }
        if (ORDERED_PARENTS.contains(local(now.type()))) {
            Set<String> common = new LinkedHashSet<>(old.orderedChildren());
            common.retainAll(now.orderedChildren());
            List<String> x = old.orderedChildren().stream().filter(common::contains).toList();
            List<String> y = now.orderedChildren().stream().filter(common::contains).toList();
            if (!x.equals(y)) {
                out.add(new Change(Level.PROPERTY, Kind.CHANGED, now.key(), now.type(), name, "#order",
                        String.join(",", x), String.join(",", y)));
            }
        }
    }

    /** The level of a property change, from the last attribute and the element segments of its path. */
    static Level level(String path) {
        String[] segments = path.split("/");
        String last = segments[segments.length - 1];
        if (last.startsWith("@") && FLOW_ATTRIBUTES.contains(local(last.substring(1)))) {
            return Level.FLOW;
        }
        for (String s : segments) {
            if (FLOW_SEGMENTS.contains(local(stripIndex(s)))) {
                return Level.FLOW;
            }
        }
        if (last.startsWith("@") && EXPRESSION_ATTRIBUTES.contains(local(last.substring(1)))) {
            return Level.EXPRESSION;
        }
        for (String s : segments) {
            if (!s.startsWith("@") && !s.startsWith("#") && EXPRESSION_ELEMENTS.contains(local(stripIndex(s)))) {
                return Level.EXPRESSION;
            }
        }
        return Level.PROPERTY;
    }

    private static String stripIndex(String segment) {
        int i = segment.indexOf('[');
        return i < 0 ? segment : segment.substring(0, i);
    }

    private static String local(String qualified) {
        int i = qualified.lastIndexOf(':');
        int j = qualified.lastIndexOf('}');
        return qualified.substring(Math.max(i, j) + 1);
    }

    // ------------------------------------------------------------------ reading one version

    static Map<String, Unit> units(String resource, byte[] xml) {
        Document doc = parse(resource, xml);
        Element root = doc.getDocumentElement();
        String modelNs = root.getNamespaceURI();
        Map<String, Unit> out = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        collect(root, null, modelNs, out, seen);
        return out;
    }

    private static void collect(Element e, String parentKey, String modelNs, Map<String, Unit> out,
                                Map<String, Integer> seen) {
        String key = keyOf(e, parentKey == null, seen);
        Map<String, String> props = new TreeMap<>();
        List<String> ordered = new ArrayList<>();
        List<Element> nested = new ArrayList<>();
        flatten(e, "", modelNs, props, ordered, nested, seen);
        String name = e.getAttribute("name");
        if (name.isEmpty()) {
            name = e.getAttribute("label");
        }
        out.put(key, new Unit(key, type(e, modelNs), name, parentKey, props, ordered));
        for (Element child : nested) {
            collect(child, key, modelNs, out, seen);
        }
    }

    /** The unit key of an element with an id (or the root); null when the element has no id. */
    private static String keyOf(Element e, boolean root, Map<String, Integer> seen) {
        String id = e.getAttribute("id").strip();
        if (id.isEmpty()) {
            return root ? ROOT_KEY : null;
        }
        int n = seen.merge(id, 1, Integer::sum);
        return n == 1 ? id : id + "#" + n;
    }

    /**
     * Adds the attributes and text of {@code e} under {@code prefix}, recursing into child elements without an id.
     * Child elements with an id are returned in {@code nested} (their own units); their keys are listed in
     * {@code ordered}.
     */
    private static void flatten(Element e, String prefix, String modelNs, Map<String, String> props,
                                List<String> ordered, List<Element> nested, Map<String, Integer> seen) {
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String ns = a.getNamespaceURI();
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(ns) || "xmlns".equals(a.getName())
                    || a.getName().startsWith("xmlns:")) {
                continue;
            }
            String local = a.getLocalName() == null ? a.getName() : a.getLocalName();
            if (prefix.isEmpty() && "id".equals(local) && ns == null) {
                continue;
            }
            if (ns != null && TOOL_NAMESPACES.contains(ns) || ns == null && TOOL_ATTRIBUTES.contains(local)) {
                continue;
            }
            props.put(prefix + "@" + prefix(ns, modelNs) + local, a.getValue());
        }
        StringBuilder text = new StringBuilder();
        Map<String, Integer> index = new HashMap<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(n.getNodeValue());
            } else if (n instanceof Element c) {
                if (isDiagram(c)) {
                    continue;
                }
                if (!c.getAttribute("id").strip().isEmpty()) {
                    // its own unit; the key it will get (keyOf counts occurrences in the same document order, so a
                    // duplicate id, which a valid model never has, gets the same #n suffix here and there)
                    nested.add(c);
                    String id = c.getAttribute("id").strip();
                    int already = seen.getOrDefault(id, 0);
                    ordered.add(already == 0 ? id : id + "#" + (already + 1));
                    continue;
                }
                String seg = type(c, modelNs);
                int i = index.merge(seg, 1, Integer::sum);
                flatten(c, prefix + seg + "[" + i + "]/", modelNs, props, ordered, nested, seen);
            }
        }
        String t = text.toString().replace("\r\n", "\n").strip();
        if (!t.isEmpty()) {
            props.put(prefix + "#text", t);
        }
    }

    /** The element type: its local name, prefixed for extension namespaces ({@code operaton:inputParameter}). */
    private static String type(Element e, String modelNs) {
        String local = e.getLocalName() == null ? e.getTagName() : e.getLocalName();
        return prefix(e.getNamespaceURI(), modelNs) + local;
    }

    private static String prefix(String ns, String modelNs) {
        if (ns == null || ns.equals(modelNs) || isModelNamespace(ns)) {
            return "";
        }
        String p = PREFIXES.get(ns);
        return p != null ? p : "{" + ns + "}";
    }

    private static boolean isModelNamespace(String ns) {
        return (ns.contains("/spec/BPMN/") || ns.contains("/spec/DMN/") || ns.contains("/spec/CMMN/"))
                && (ns.endsWith("MODEL") || ns.endsWith("MODEL/"));
    }

    /** BPMNDI, DMNDI, CMMNDI and their DC/DI elements: layout, never semantics. */
    static boolean isDiagram(Element e) {
        String ns = e.getNamespaceURI();
        if (ns == null) {
            return false;
        }
        String n = ns.endsWith("/") ? ns.substring(0, ns.length() - 1) : ns;
        return n.endsWith("/DI") || n.endsWith("/DC") || n.endsWith("/DMNDI") || n.endsWith("/CMMNDI")
                || n.contains("/DD/");
    }

    static Document parse(String resource, byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot read " + resource + " as XML: " + e.getMessage(), e);
        }
    }
}
