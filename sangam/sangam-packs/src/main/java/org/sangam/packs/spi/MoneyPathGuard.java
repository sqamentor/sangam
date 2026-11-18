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
        return check(resourceName, bpmn, movement, topicToConnector, Models.NONE);
    }

    /**
     * The processes and decisions a pack can reach (its own and those of the packs it depends on). The guard reads a
     * called process to see whether every path through it records an approval (an approval bound inside a called
     * process, e.g. the PAO pass of the IFMS PAO chain), and a decision table to see which {@code executionConnector}
     * it can choose.
     */
    public interface Models {
        /** The BPMN that defines the executable process {@code processId}, or null when unknown. */
        byte[] process(String processId);

        /** The literal values the decision {@code decisionKey} can give its output {@code output} (empty: unknown). */
        List<String> decisionOutputs(String decisionKey, String output);

        Models NONE = new Models() {
            @Override
            public byte[] process(String processId) {
                return null;
            }

            @Override
            public List<String> decisionOutputs(String decisionKey, String output) {
                return List.of();
            }
        };

        /** Index of every BPMN process and DMN decision of the given packs. */
        static Models of(java.util.Collection<SangamPack> packs) {
            Map<String, byte[]> procs = new HashMap<>();
            Map<String, Element> decisions = new HashMap<>();
            for (SangamPack p : packs) {
                for (PackDescriptor.PackResource r : p.descriptor().resources()) {
                    if (r.kind() != PackDescriptor.PackResource.Kind.BPMN
                            && r.kind() != PackDescriptor.PackResource.Kind.DMN) {
                        continue;
                    }
                    byte[] bytes;
                    try (java.io.InputStream in = p.open(r.path())) {
                        bytes = in.readAllBytes();
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    Document d = parse(bytes);
                    NodeList ps = d.getElementsByTagNameNS(BPMN, "process");
                    for (int i = 0; i < ps.getLength(); i++) {
                        procs.putIfAbsent(((Element) ps.item(i)).getAttribute("id"), bytes);
                    }
                    NodeList ds = d.getElementsByTagNameNS("*", "decision");
                    for (int i = 0; i < ds.getLength(); i++) {
                        decisions.putIfAbsent(((Element) ds.item(i)).getAttribute("id"), (Element) ds.item(i));
                    }
                }
            }
            return new Models() {
                @Override
                public byte[] process(String processId) {
                    return procs.get(processId);
                }

                @Override
                public List<String> decisionOutputs(String decisionKey, String output) {
                    Element dec = decisions.get(decisionKey);
                    if (dec == null) {
                        return List.of();
                    }
                    NodeList outs = dec.getElementsByTagNameNS("*", "output");
                    int col = -1;
                    for (int i = 0; i < outs.getLength(); i++) {
                        if (output.equals(((Element) outs.item(i)).getAttribute("name"))) {
                            col = i;
                        }
                    }
                    if (col < 0) {
                        return List.of();
                    }
                    List<String> values = new ArrayList<>();
                    NodeList rules = dec.getElementsByTagNameNS("*", "rule");
                    for (int i = 0; i < rules.getLength(); i++) {
                        NodeList entries = ((Element) rules.item(i)).getElementsByTagNameNS("*", "outputEntry");
                        if (col < entries.getLength()) {
                            String t = entries.item(col).getTextContent().trim();
                            if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
                                values.add(t.substring(1, t.length() - 1));
                            } else {
                                values.add(t); // not a literal: the run-time check (MoneyOutApproval) decides
                            }
                        }
                    }
                    return values;
                }
            };
        }
    }

    /**
     * @param models processes and decisions reachable from this pack ({@link Models#NONE}: only this resource)
     */
    public static List<String> check(String resourceName, byte[] bpmn, Function<String, MoneyMovement> movement,
                                     Map<String, String> topicToConnector, Models models) {
        List<String> out = new ArrayList<>();
        Document doc = parse(bpmn);
        NodeList procs = doc.getElementsByTagNameNS(BPMN, "process");
        Ctx ctx = new Ctx(movement, topicToConnector, models);
        for (int i = 0; i < procs.getLength(); i++) {
            Element p = (Element) procs.item(i);
            if (!"false".equals(p.getAttribute("isExecutable"))) {
                out.addAll(checkProcess(resourceName, p, ctx));
            }
        }
        return out;
    }

    private record Ctx(Function<String, MoneyMovement> mm, Map<String, String> topics, Models models) {
    }

    /**
     * {@code approved}: an approval lies on this path since the last money-out step (E1: one sanction, one payment);
     * {@code approvedIn}: the called process whose approval it is (null: this process).
     */
    private record State(String node, String connector, boolean approved, String approvedIn) {
    }

    private static List<String> checkProcess(String res, Element proc, Ctx ctx) {
        Function<String, MoneyMovement> mm = ctx.mm();
        Map<String, String> topics = ctx.topics();
        Map<String, Element> nodes = new HashMap<>();
        Map<String, List<String>> succ = new HashMap<>();
        List<String> starts = new ArrayList<>();
        collect(proc, nodes, succ, starts, true);
        Set<String> violations = new LinkedHashSet<>();
        boolean movesMoney = false;
        Set<State> seen = new HashSet<>();
        Deque<State> todo = new ArrayDeque<>();
        starts.forEach(s -> todo.push(new State(s, null, false, null)));
        while (!todo.isEmpty()) {
            State st = todo.pop();
            if (!seen.add(st)) {
                continue;
            }
            Element el = nodes.get(st.node());
            String carried = st.connector();
            boolean approved = st.approved();
            String approvedIn = st.approvedIn();
            if (el != null) {
                if (isApproval(el)) {
                    approved = true; // bound until the next money-out step (the runtime re-verifies the payload hash)
                    approvedIn = null;
                } else {
                    String callee = calledPackProcess(el, ctx);
                    if (callee != null) {
                        Boolean through = approvesOnEveryPath(callee, ctx, new HashSet<>());
                        if (Boolean.TRUE.equals(through)) {
                            approved = true; // bound inside the called process (its PackStartListener's fields)
                            approvedIn = callee;
                        }
                        if (calleeMovesMoney(callee, ctx, new HashSet<>())) {
                            movesMoney = true; // the caller's materialFields are the ones in force (first one set)
                            approved = false;
                        }
                    }
                }
                String set = setsExecutionConnector(el, ctx);
                if (set != null) {
                    carried = set;
                }
                String moving = movesMoneyOut(el, carried, mm, topics);
                if (moving != null) {
                    movesMoney = true;
                }
                if (moving != null && !approved) {
                    violations.add(res + ": process " + proc.getAttribute("id") + " reaches " + st.node() + " ("
                            + moving + ", moneyMovement OUT) on a path without a recorded approval (owner decision A1)");
                }
                if (moving != null && approved && approvedIn != null) {
                    List<String> missing = missingMoneyFields(processElement(approvedIn, ctx));
                    if (!missing.isEmpty()) {
                        violations.add(res + ": process " + proc.getAttribute("id") + " reaches " + st.node() + " ("
                                + moving + ", moneyMovement OUT) on an approval bound in the called process "
                                + approvedIn + " whose materialFields lack " + missing + " (owner decision E3)");
                    }
                }
                if (moving != null) {
                    approved = false; // the sanction is spent: a further payment (loop, retry, reissue) needs its own
                    approvedIn = null;
                }
            }
            for (String n : succ.getOrDefault(st.node(), List.of())) {
                todo.push(new State(n, carried, approved, approvedIn));
            }
        }
        if (movesMoney || anyMoneyOut(nodes, ctx)) {
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

    private static List<String> missingMoneyFields(Element proc) {
        List<String> declared = proc == null ? List.of() : materialFields(proc);
        List<String> missing = new ArrayList<>();
        for (String f : MONEY_FIELDS) {
            if (!declared.contains(f)) {
                missing.add(f);
            }
        }
        return missing;
    }

    /** The id of the process a call activity starts, when it is a pack process the guard can read (not core). */
    private static String calledPackProcess(Element e, Ctx ctx) {
        if (!"callActivity".equals(e.getLocalName())) {
            return null;
        }
        String id = e.getAttribute("calledElement");
        if (id.isBlank() || id.contains("{") || CORE_SANCTION.equals(id) || CORE_EXECUTION.equals(id)) {
            return null;
        }
        return ctx.models().process(id) == null ? null : id;
    }

    private static Element processElement(String id, Ctx ctx) {
        byte[] b = ctx.models().process(id);
        if (b == null) {
            return null;
        }
        NodeList ps = parse(b).getElementsByTagNameNS(BPMN, "process");
        for (int i = 0; i < ps.getLength(); i++) {
            if (id.equals(((Element) ps.item(i)).getAttribute("id"))) {
                return (Element) ps.item(i);
            }
        }
        return null;
    }

    /**
     * True when every way through the called process to an end records an approval that no money-out step in it has
     * spent. Ends reached only from a non-interrupting boundary event (an SLA warning) do not complete the call and
     * are ignored. Null when the process cannot be read or calls itself.
     */
    private static Boolean approvesOnEveryPath(String id, Ctx ctx, Set<String> visiting) {
        Element proc = processElement(id, ctx);
        if (proc == null || !visiting.add(id)) {
            return null;
        }
        Map<String, Element> nodes = new HashMap<>();
        Map<String, List<String>> succ = new HashMap<>();
        List<String> starts = new ArrayList<>();
        collect(proc, nodes, succ, starts, true);
        Set<String> seen = new HashSet<>();
        Deque<String[]> todo = new ArrayDeque<>();
        boolean any = false;
        for (String s : starts) {
            todo.push(new String[] {s, "0"});
        }
        while (!todo.isEmpty()) {
            String[] st = todo.pop();
            if (!seen.add(st[0] + "|" + st[1])) {
                continue;
            }
            Element el = nodes.get(st[0]);
            boolean approved = "1".equals(st[1]);
            if (el != null) {
                if (isApproval(el)) {
                    approved = true;
                } else {
                    String callee = calledPackProcess(el, ctx);
                    if (callee != null && Boolean.TRUE.equals(approvesOnEveryPath(callee, ctx, visiting))) {
                        approved = true;
                    }
                }
                if (movesMoneyOut(el, null, ctx.mm(), ctx.topics()) != null) {
                    approved = false;
                }
                if ("endEvent".equals(el.getLocalName()) && succ.getOrDefault(st[0], List.of()).isEmpty()) {
                    any = true;
                    if (!approved) {
                        visiting.remove(id);
                        return false;
                    }
                }
            }
            for (String n : succ.getOrDefault(st[0], List.of())) {
                Element ne = nodes.get(n);
                if (ne != null && "boundaryEvent".equals(ne.getLocalName())
                        && "false".equals(ne.getAttribute("cancelActivity"))) {
                    continue;
                }
                todo.push(new String[] {n, approved ? "1" : "0"});
            }
        }
        visiting.remove(id);
        return any;
    }

    /** True when the called pack process (or one it calls) moves money out. */
    private static boolean calleeMovesMoney(String id, Ctx ctx, Set<String> visiting) {
        Element proc = processElement(id, ctx);
        if (proc == null || !visiting.add(id)) {
            return false;
        }
        Map<String, Element> nodes = new HashMap<>();
        collect(proc, nodes, new HashMap<>(), new ArrayList<>(), true);
        if (anyMoneyOut(nodes, ctx)) {
            return true;
        }
        for (Element e : nodes.values()) {
            String callee = calledPackProcess(e, ctx);
            if (callee != null && calleeMovesMoney(callee, ctx, visiting)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyMoneyOut(Map<String, Element> nodes, Ctx ctx) {
        Function<String, MoneyMovement> mm = ctx.mm();
        Map<String, String> topics = ctx.topics();
        for (Element e : nodes.values()) {
            if (movesMoneyOut(e, null, mm, topics) != null) {
                return true;
            }
            // any executionConnector this process assigns, tried on every core-execution call / PREPARE of it
            String set = setsExecutionConnector(e, ctx);
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
                default -> {
                    nodes.put(id, e);
                    // a compensation handler is reached through an association, not a sequence flow; it can run
                    // whenever its activity is compensated, so it is an entry point of its own
                    if ("true".equals(e.getAttribute("isForCompensation"))) {
                        starts.add(id);
                    }
                }
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
        NodeList el = e.getElementsByTagNameNS(OPERATON, "executionListener");
        for (int i = 0; i < el.getLength(); i++) {
            Element l = (Element) el.item(i);
            if ("start".equals(l.getAttribute("event")) && l.getAttribute("class").endsWith(".CaptureRefunds$Release")) {
                return true; // M5: a refund released by its capture record (at most the capture, same source, once)
            }
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

    /**
     * The {@code executionConnector} a SetVariablesDelegate assigns, or that a decision (DecisionDelegate without a
     * result prefix) can give: an OUT choice when the table has one, so the worst case is checked. Null when none.
     */
    private static String setsExecutionConnector(Element e, Ctx ctx) {
        if (cls(e).endsWith(".DecisionDelegate")) {
            Map<String, String> f = fields(e);
            String key = f.get("decisionKey");
            if (key == null || key.isBlank() || !f.getOrDefault("resultPrefix", "").isBlank()) {
                return null;
            }
            List<String> values = ctx.models().decisionOutputs(key, "executionConnector");
            for (String v : values) {
                if (out(ctx.mm(), v)) {
                    return v;
                }
            }
            return values.isEmpty() ? null : values.get(0);
        }
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
