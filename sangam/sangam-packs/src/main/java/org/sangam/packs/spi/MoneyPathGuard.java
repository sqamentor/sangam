// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Owner decision A1 (I1/I2): deploy-time refusal of any path on which money can leave ({@link MoneyMovement#OUT})
 * without a recorded approval. A DMN result only recommends; it never stands in for the approval.
 *
 * <p>For every executable process the guard walks the sequence flows from each start event, carrying the value of
 * {@code executionConnector} last set by a {@code SetVariablesDelegate} on that path. A node moves money out when it is
 * <ul>
 *   <li>a {@code ConnectorCallDelegate} in {@code submit} mode on an OUT connector;</li>
 *   <li>an {@code ExternalOperationDelegate} {@code PREPARE} on an OUT connector (its field, or the carried
 *       {@code executionConnector});</li>
 *   <li>a call activity to {@code sangam-core-execution} while the carried {@code executionConnector} is OUT;</li>
 *   <li>an external task whose topic the pack serves with an OUT connector.</li>
 * </ul>
 * An approval is a user task with {@code ApprovalBindingListener} or a call activity to {@code sangam-core-sanction}.
 * Reaching a money-out node before any approval on some path is a violation. Undeclared connectors count as OUT.
 * The guard reads plain XML, so it does not depend on the workflow engine's model API.
 */
public final class MoneyPathGuard {

    static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    static final String OPERATON = "http://operaton.org/schema/1.0/bpmn";
    static final String CORE_EXECUTION = "sangam-core-execution";
    static final String CORE_SANCTION = "sangam-core-sanction";

    private MoneyPathGuard() {
    }

    /**
     * @param movement   direction of a connector id (undeclared must answer OUT)
     * @param topicToConnector the pack's external-task topics and the connector serving each
     */
    public static List<String> check(String resourceName, byte[] bpmn, Function<String, MoneyMovement> movement,
                                     Map<String, String> topicToConnector) {
        List<String> out = new ArrayList<>();
        Document doc = parse(bpmn);
        NodeList procs = doc.getElementsByTagNameNS(BPMN, "process");
        for (int i = 0; i < procs.getLength(); i++) {
            Element p = (Element) procs.item(i);
            if (!"false".equals(p.getAttribute("isExecutable"))) {
                out.addAll(checkProcess(resourceName, p, movement, topicToConnector));
            }
        }
        return out;
    }

    private record State(String node, String connector) {
    }

    private static List<String> checkProcess(String res, Element proc, Function<String, MoneyMovement> mm,
                                             Map<String, String> topics) {
        Map<String, Element> nodes = new HashMap<>();
        Map<String, List<String>> succ = new HashMap<>();
        List<String> starts = new ArrayList<>();
        collect(proc, nodes, succ, starts, true);
        Set<String> violations = new LinkedHashSet<>();
        boolean movesMoney = false;
        Set<State> seen = new HashSet<>();
        Deque<State> todo = new ArrayDeque<>();
        starts.forEach(s -> todo.push(new State(s, null)));
        while (!todo.isEmpty()) {
            State st = todo.pop();
            if (!seen.add(st)) {
                continue;
            }
            Element el = nodes.get(st.node());
            String carried = st.connector();
            if (el != null) {
                if (isApproval(el)) {
                    continue; // every path past an approval is bound (the runtime re-verifies the payload hash)
                }
                String set = setsExecutionConnector(el);
                if (set != null) {
                    carried = set;
                }
                String moving = movesMoneyOut(el, carried, mm, topics);
                if (moving != null) {
                    movesMoney = true;
                }
                if (moving != null) {
                    violations.add(res + ": process " + proc.getAttribute("id") + " reaches " + st.node() + " ("
                            + moving + ", moneyMovement OUT) on a path without a recorded approval (owner decision A1)");
                }
            }
            for (String n : succ.getOrDefault(st.node(), List.of())) {
                todo.push(new State(n, carried));
            }
        }
        if (movesMoney || anyMoneyOut(nodes, mm, topics)) {
            List<String> declared = materialFields(proc);
            List<String> missing = new ArrayList<>();
            for (String f : MONEY_FIELDS) {
                if (!declared.contains(f)) {
                    missing.add(f);
                }
            }
            if (!missing.isEmpty()) {
                violations.add(res + ": process " + proc.getAttribute("id") + " moves money out but its materialFields "
                        + declared + " lack " + missing + " (owner decision E3)");
            }
        }
        return new ArrayList<>(violations);
    }

    /** E3: amount, payee or beneficiary account, currency and purpose (MoneyOutApproval.MONEY_FIELDS). */
    static final List<String> MONEY_FIELDS = List.of("amount", "payeeAccount", "currency", "purpose");

    private static boolean anyMoneyOut(Map<String, Element> nodes, Function<String, MoneyMovement> mm,
                                       Map<String, String> topics) {
        for (Element e : nodes.values()) {
            if (movesMoneyOut(e, null, mm, topics) != null) {
                return true;
            }
            // any executionConnector this process assigns, tried on every core-execution call / PREPARE of it
            String set = setsExecutionConnector(e);
            if (set != null) {
                for (Element x : nodes.values()) {
                    if (movesMoneyOut(x, set, mm, topics) != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The materialFields the process declares on its PackStartListener (empty when none). */
    private static List<String> materialFields(Element proc) {
        NodeList ls = proc.getElementsByTagNameNS(OPERATON, "executionListener");
        for (int i = 0; i < ls.getLength(); i++) {
            Element l = (Element) ls.item(i);
            if (l.getParentNode().getParentNode() == proc && l.getAttribute("class").endsWith(".PackStartListener")) {
                String v = fields(l).get("materialFields");
                if (v != null) {
                    List<String> out = new ArrayList<>();
                    for (String s : v.split(",")) {
                        if (!s.isBlank()) {
                            out.add(s.trim());
                        }
                    }
                    return out;
                }
            }
        }
        return List.of();
    }

    /**
     * Flow nodes and sequence flows of a scope. A sub-process is entered at its start events, and each of its end
     * events continues with the sub-process's own outgoing flows; event sub-process starts and boundary events are
     * entry points (an event can fire at any time).
     */
    private static void collect(Element scope, Map<String, Element> nodes, Map<String, List<String>> succ,
                                List<String> starts, boolean top) {
        Map<String, List<String>> flows = new HashMap<>();
        Map<String, String> endOwner = new HashMap<>();
        walk(scope, nodes, flows, succ, starts, endOwner, top);
        flows.forEach((k, v) -> succ.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
        endOwner.forEach((end, sub) -> succ.computeIfAbsent(end, x -> new ArrayList<>())
                .addAll(flows.getOrDefault(sub, List.of())));
    }

    private static void walk(Element scope, Map<String, Element> nodes, Map<String, List<String>> flows,
                             Map<String, List<String>> succ, List<String> starts, Map<String, String> endOwner,
                             boolean top) {
        boolean eventSub = "true".equals(scope.getAttribute("triggeredByEvent"));
        for (Node c = scope.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!(c instanceof Element e) || !BPMN.equals(e.getNamespaceURI())) {
                continue;
            }
            String id = e.getAttribute("id");
            switch (e.getLocalName()) {
                case "sequenceFlow" -> flows.computeIfAbsent(e.getAttribute("sourceRef"), k -> new ArrayList<>())
                        .add(e.getAttribute("targetRef"));
                case "startEvent" -> {
                    nodes.put(id, e);
                    if (top || eventSub) {
                        starts.add(id);
                    }
                }
                case "endEvent" -> {
                    nodes.put(id, e);
                    if (!top && !eventSub) {
                        endOwner.put(id, scope.getAttribute("id"));
                    }
                }
                case "subProcess", "transaction", "adHocSubProcess" -> {
                    nodes.put(id, e);
                    for (String s : children(e, "startEvent")) {
                        succ.computeIfAbsent(id, k -> new ArrayList<>()).add(s);
                    }
                    walk(e, nodes, flows, succ, starts, endOwner, false);
                }
                case "boundaryEvent" -> {
                    nodes.put(id, e);
                    succ.computeIfAbsent(e.getAttribute("attachedToRef"), k -> new ArrayList<>()).add(id);
                }
                default -> nodes.put(id, e);
            }
        }
    }

    private static List<String> children(Element sub, String local) {
        List<String> out = new ArrayList<>();
        for (Node c = sub.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e && BPMN.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) {
                out.add(e.getAttribute("id"));
            }
        }
        return out;
    }

    private static String cls(Element e) {
        return e.getAttributeNS(OPERATON, "class");
    }

    private static boolean isApproval(Element e) {
        if ("callActivity".equals(e.getLocalName()) && CORE_SANCTION.equals(e.getAttribute("calledElement"))) {
            return true;
        }
        if (cls(e).endsWith(".BatchItemReleaseDelegate")) {
            return true; // E1: the batch parent's sanction, released for this unchanged item
        }
        if (!"userTask".equals(e.getLocalName())) {
            return false;
        }
        NodeList ls = e.getElementsByTagNameNS(OPERATON, "taskListener");
        for (int i = 0; i < ls.getLength(); i++) {
            if (((Element) ls.item(i)).getAttribute("class").endsWith(".ApprovalBindingListener")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> fields(Element e) {
        Map<String, String> f = new HashMap<>();
        NodeList fs = e.getElementsByTagNameNS(OPERATON, "field");
        for (int i = 0; i < fs.getLength(); i++) {
            Element x = (Element) fs.item(i);
            f.put(x.getAttribute("name"), x.getTextContent().trim());
        }
        return f;
    }

    /** The {@code executionConnector} a SetVariablesDelegate assigns, or null. */
    private static String setsExecutionConnector(Element e) {
        if (!cls(e).endsWith(".SetVariablesDelegate")) {
            return null;
        }
        String found = null;
        for (String v : fields(e).values()) {
            for (String kv : v.split(";")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && "executionConnector".equals(kv.substring(0, eq).trim())) {
                    found = kv.substring(eq + 1).trim();
                }
            }
        }
        return found;
    }

    private static boolean out(Function<String, MoneyMovement> mm, String connector) {
        // a connector chosen by an expression is only known at run time; MoneyOutApproval enforces it there
        return connector != null && !connector.isBlank() && !connector.contains("{")
                && mm.apply(connector) == MoneyMovement.OUT;
    }

    private static String movesMoneyOut(Element e, String carried, Function<String, MoneyMovement> mm,
                                        Map<String, String> topics) {
        String c = cls(e);
        Map<String, String> f = fields(e);
        if (c.endsWith(".ConnectorCallDelegate") && "submit".equalsIgnoreCase(f.getOrDefault("mode", "query"))
                && out(mm, f.get("connector"))) {
            return f.get("connector");
        }
        if (c.endsWith(".ExternalOperationDelegate") && "PREPARE".equals(f.get("op"))) {
            String conn = f.get("connector");
            String eff = conn == null || conn.isBlank() || conn.startsWith("$") ? carried : conn;
            if (out(mm, eff)) {
                return eff;
            }
        }
        if ("callActivity".equals(e.getLocalName()) && CORE_EXECUTION.equals(e.getAttribute("calledElement"))
                && out(mm, carried)) {
            return carried;
        }
        if ("external".equals(e.getAttributeNS(OPERATON, "type"))) {
            String conn = topics.get(e.getAttributeNS(OPERATON, "topic"));
            if (out(mm, conn)) {
                return conn;
            }
        }
        return null;
    }

    private static Document parse(byte[] bpmn) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new ByteArrayInputStream(bpmn));
        } catch (Exception e) {
            throw new IllegalArgumentException("unreadable BPMN: " + e.getMessage(), e);
        }
    }
}
