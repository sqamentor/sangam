// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * SL-55: every path of every generated pack into a rejection carries a coded reason. The core rejection
 * ({@code sangam-core-rejection}, step {@code rj_record}: CaseRecordDelegate {@code op=REJECT}) throws
 * {@code 422 REASONS_REQUIRED} when {@code rejectionReasonCodes} is empty and the decision is not deemed
 * (CaseRecordDelegate.reject). On a path no person decides (an operation result from the inbox, a timer) that refusal
 * repeats on every retry: a FAILED execution in five gov.ifms packs routed to the rejection without a code, and the
 * inbox retried the result message every 5 s for ever (IfmsPacksIT reappropriation).
 *
 * <p>The check is a forward walk over each process (and, through call activities, the processes it calls) that tracks
 * whether a reason is established on the way to a rejection:
 * <ul>
 *   <li><b>coded</b>: a SetVariablesDelegate step sets {@code rejectionReasonCodes} (values or defaults) or
 *       {@code deemedDecision=true}, or the path took an SLA breach flow {@code slaAction == 'DEEMED'} (SlaDelegate sets
 *       {@code deemedDecision=true} there); this holds for the rest of the path;</li>
 *   <li><b>human</b>: a user task whose form has a {@code rejectionReasonCodes} field: the person who decides gives the
 *       reasons, and the 422 reaches that person synchronously when they are missing. It holds only until the next wait
 *       state (catch event, event-based gateway, receive task, boundary event) or asynchronous continuation, after
 *       which the refusal would be raised by a job or an inbox message instead;</li>
 *   <li>otherwise <b>none</b>: arriving at a rejection with none is a violation.</li>
 * </ul>
 * Outcome variables set by literal assignments ({@code executionOutcome=FAILED}) are tracked through called processes,
 * so a gateway {@code executionOutcome == 'DONE'} with a rejection as default is followed only for the outcomes that
 * really reach it. A call activity runs the called process with the caller's state ({@code operaton:in variables="all"}).
 */
class RejectionReasonCoverageTest {

    static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    static final String OP = "http://operaton.org/schema/1.0/bpmn";
    static final String REJECTION = "sangam-core-rejection";
    static final Pattern EQ = Pattern.compile("^\\$\\{\\s*(\\w+)\\s*==\\s*'([^']*)'\\s*}$");
    static final Set<String> FLOW_NODES = Set.of("startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent",
            "boundaryEvent", "serviceTask", "userTask", "callActivity", "businessRuleTask", "scriptTask", "sendTask",
            "receiveTask", "task", "manualTask", "exclusiveGateway", "inclusiveGateway", "parallelGateway",
            "eventBasedGateway", "complexGateway", "subProcess", "transaction");
    static final Set<String> WAITS = Set.of("intermediateCatchEvent", "eventBasedGateway", "receiveTask", "boundaryEvent");
    static final int NONE = 0;
    static final int HUMAN = 1;
    static final int CODED = 2;

    // ---- the real packs ----------------------------------------------------------------------------------------

    @Test
    void everyPathOfEveryGeneratedPackIntoARejectionCarriesACodedReason() throws Exception {
        Path root = Path.of("src/main/resources/packs");
        Map<String, String> bpmn = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".bpmn")).sorted().toList()) {
                bpmn.put(root.relativize(f).toString().replace('\\', '/'), Files.readString(f));
            }
        }
        Map<String, Boolean> forms = new HashMap<>();
        Predicate<String> formHasReasons = key -> forms.computeIfAbsent(key, k -> {
            if (k == null || !k.startsWith("sangam:") || !k.contains("/")) {
                return false;
            }
            String ref = k.substring("sangam:".length());
            Path f = root.resolve(ref.substring(0, ref.indexOf('/'))).resolve("forms")
                    .resolve(ref.substring(ref.indexOf('/') + 1) + ".form.json");
            try {
                return Files.exists(f) && Files.readString(f).contains("rejectionReasonCodes");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        Analyzer a = new Analyzer(bpmn, formHasReasons);
        assertTrue(a.rejectionTargets() > 50, "the packs use the core rejection: " + a.rejectionTargets());
        assertEquals(List.of(), a.violations(), "paths into a rejection without a coded reason");
    }

    // ---- the check itself, on small models -----------------------------------------------------------------------

    /** The SL-55 shape: an inbox result sets executionOutcome FAILED; the caller's fallback is the rejection. */
    static String execution(String id) {
        return process(id, """
                <startEvent id="x_start"/>
                <intermediateCatchEvent id="x_result"><messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                <exclusiveGateway id="x_gw" default="x_f4"/>
                %s
                %s
                <endEvent id="x_endDone"/><endEvent id="x_endFailed"/>
                <sequenceFlow id="x_f1" sourceRef="x_start" targetRef="x_result"/>
                <sequenceFlow id="x_f2" sourceRef="x_result" targetRef="x_gw"/>
                <sequenceFlow id="x_f3" sourceRef="x_gw" targetRef="x_done"><conditionExpression>${opStatus == 'COMPLETED'}</conditionExpression></sequenceFlow>
                <sequenceFlow id="x_f4" sourceRef="x_gw" targetRef="x_failed"/>
                <sequenceFlow id="x_f5" sourceRef="x_done" targetRef="x_endDone"/>
                <sequenceFlow id="x_f6" sourceRef="x_failed" targetRef="x_endFailed"/>
                """.formatted(setv("x_done", "executionOutcome=DONE", null), setv("x_failed", "executionOutcome=FAILED", null)));
    }

    static String rejectionCore() {
        return process(REJECTION, """
                <startEvent id="rj_start"/>
                <serviceTask id="rj_record" operaton:class="org.sangam.packs.core.CaseRecordDelegate"><extensionElements><operaton:field name="op"><operaton:string>REJECT</operaton:string></operaton:field></extensionElements></serviceTask>
                <endEvent id="rj_end"/>
                <sequenceFlow id="rj_f1" sourceRef="rj_start" targetRef="rj_record"/>
                <sequenceFlow id="rj_f2" sourceRef="rj_record" targetRef="rj_end"/>
                """);
    }

    /** start - first - call execution - gateway (DONE: end; FAILED: failedTarget, if given; otherwise: rejection). */
    static String caller(String first, String failedStep) {
        String failed = failedStep == null ? "" : failedStep
                + "<sequenceFlow id=\"c_f7\" sourceRef=\"c_gw\" targetRef=\"c_code\"><conditionExpression>${executionOutcome == 'FAILED'}</conditionExpression></sequenceFlow>"
                + "<sequenceFlow id=\"c_f8\" sourceRef=\"c_code\" targetRef=\"c_rej\"/>";
        return process("caller", """
                <startEvent id="c_start"/>
                %s
                <callActivity id="c_exec" calledElement="exec"/>
                <exclusiveGateway id="c_gw" default="c_f5"/>
                <callActivity id="c_rej" calledElement="sangam-core-rejection"/>
                <endEvent id="c_end"/><endEvent id="c_endRejected"/>
                <sequenceFlow id="c_f1" sourceRef="c_start" targetRef="c_first"/>
                <sequenceFlow id="c_f2" sourceRef="c_first" targetRef="c_exec"/>
                <sequenceFlow id="c_f3" sourceRef="c_exec" targetRef="c_gw"/>
                <sequenceFlow id="c_f4" sourceRef="c_gw" targetRef="c_end"><conditionExpression>${executionOutcome == 'DONE'}</conditionExpression></sequenceFlow>
                <sequenceFlow id="c_f5" sourceRef="c_gw" targetRef="c_rej"/>
                <sequenceFlow id="c_f6" sourceRef="c_rej" targetRef="c_endRejected"/>
                %s
                """.formatted(first, failed));
    }

    static List<String> check(Predicate<String> forms, String... processes) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < processes.length; i++) {
            m.put("m" + i + ".bpmn", processes[i]);
        }
        return new Analyzer(m, forms).violations();
    }

    @Test
    void aFailedExecutionRoutedToTheRejectionWithoutACodeIsReported() {
        List<String> v = check(k -> false, execution("exec"), rejectionCore(),
                caller("<task id=\"c_first\"/>", null));
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("[caller] -> c_rej"), v.get(0));
    }

    @Test
    void aCodedFailureRouteIsAccepted() {
        assertEquals(List.of(), check(k -> false, execution("exec"), rejectionCore(),
                caller("<task id=\"c_first\"/>", setv("c_code", "rejectionReasonCodes=EXECUTION_FAILED", null))));
        // a pack default (only if unset) also establishes the reason
        assertEquals(List.of(), check(k -> false, execution("exec"), rejectionCore(),
                caller("<task id=\"c_first\"/>", setv("c_code", null, "rejectionReasonCodes=EXECUTION_FAILED"))));
    }

    @Test
    void aHumanDecisionBeforeAWaitStateDoesNotCoverTheAutomaticFailure() {
        // the sanction form asks for reasons, but the FAILED result arrives later from the inbox: not covered
        String human = "<userTask id=\"c_first\" operaton:formKey=\"sangam:core/sanction\"/>";
        assertEquals(1, check("sangam:core/sanction"::equals, execution("exec"), rejectionCore(),
                caller(human, null)).size());
    }

    @Test
    void aHumanDecisionWithAReasonFieldRightBeforeTheRejectionIsCovered() {
        String p = process("human", """
                <startEvent id="h_start"/>
                <userTask id="h_decide" operaton:formKey="sangam:p/decide"/>
                <exclusiveGateway id="h_gw" default="h_f4"/>
                <callActivity id="h_rej" calledElement="sangam-core-rejection"/>
                <endEvent id="h_end"/><endEvent id="h_endRejected"/>
                <sequenceFlow id="h_f1" sourceRef="h_start" targetRef="h_decide"/>
                <sequenceFlow id="h_f2" sourceRef="h_decide" targetRef="h_gw"/>
                <sequenceFlow id="h_f3" sourceRef="h_gw" targetRef="h_end"><conditionExpression>${decision == 'approve'}</conditionExpression></sequenceFlow>
                <sequenceFlow id="h_f4" sourceRef="h_gw" targetRef="h_rej"/>
                <sequenceFlow id="h_f5" sourceRef="h_rej" targetRef="h_endRejected"/>
                """);
        assertEquals(List.of(), check("sangam:p/decide"::equals, rejectionCore(), p));
        // the same model whose form has no reason field: the decider cannot give one
        assertEquals(1, check(k -> false, rejectionCore(), p).size());
    }

    @Test
    void aDeemedRefusalAfterAnSlaBreachIsCovered() {
        String p = process("deemed", """
                <startEvent id="d_start"/>
                <serviceTask id="d_breach" operaton:class="org.sangam.packs.core.SlaDelegate"/>
                <exclusiveGateway id="d_gw" default="d_f4"/>
                <callActivity id="d_rej" calledElement="sangam-core-rejection"/>
                <endEvent id="d_end"/><endEvent id="d_endRejected"/>
                <sequenceFlow id="d_f1" sourceRef="d_start" targetRef="d_breach"/>
                <sequenceFlow id="d_f2" sourceRef="d_breach" targetRef="d_gw"/>
                <sequenceFlow id="d_f3" sourceRef="d_gw" targetRef="d_rej"><conditionExpression>${slaAction == 'DEEMED'}</conditionExpression></sequenceFlow>
                <sequenceFlow id="d_f4" sourceRef="d_gw" targetRef="d_end"/>
                <sequenceFlow id="d_f5" sourceRef="d_rej" targetRef="d_endRejected"/>
                """);
        assertEquals(List.of(), check(k -> false, rejectionCore(), p));
    }

    static String setv(String id, String values, String defaults) {
        StringBuilder b = new StringBuilder("<serviceTask id=\"" + id + "\" operaton:class=\"org.sangam.packs.core.SetVariablesDelegate\"><extensionElements>");
        if (values != null) {
            b.append("<operaton:field name=\"values\"><operaton:string>").append(values).append("</operaton:string></operaton:field>");
        }
        if (defaults != null) {
            b.append("<operaton:field name=\"defaults\"><operaton:string>").append(defaults).append("</operaton:string></operaton:field>");
        }
        return b.append("</extensionElements></serviceTask>").toString();
    }

    static String process(String id, String body) {
        return "<definitions xmlns=\"" + BPMN + "\" xmlns:operaton=\"" + OP + "\" targetNamespace=\"t\"><process id=\"" + id
                + "\" isExecutable=\"true\">" + body + "</process></definitions>";
    }

    // ---- the analysis --------------------------------------------------------------------------------------------

    static final class Proc {
        final String id;
        final String file;
        final Map<String, Element> nodes = new LinkedHashMap<>();
        final Map<String, String> parent = new HashMap<>();
        final Map<String, List<Element>> out = new HashMap<>();
        final Map<String, List<String>> boundary = new HashMap<>();
        final List<String> starts = new ArrayList<>();
        final Set<String> tested = new HashSet<>();
        final Set<String> sideEnds = new HashSet<>();

        Proc(Element process, String file) {
            this.id = process.getAttribute("id");
            this.file = file;
            walk(process, null);
            Map<String, List<String>> in = new HashMap<>();
            for (Element sf : descendants(process, BPMN, "sequenceFlow")) {
                out.computeIfAbsent(sf.getAttribute("sourceRef"), k -> new ArrayList<>()).add(sf);
                in.computeIfAbsent(sf.getAttribute("targetRef"), k -> new ArrayList<>()).add(sf.getAttribute("sourceRef"));
            }
            for (Element ce : descendants(process, BPMN, "conditionExpression")) {
                Matcher m = EQ.matcher(ce.getTextContent().trim());
                if (m.matches()) {
                    tested.add(m.group(1));
                }
            }
            // an end event reached only from a non-interrupting boundary event does not complete the process
            for (Map.Entry<String, Element> e : nodes.entrySet()) {
                if (!"endEvent".equals(e.getValue().getLocalName())) {
                    continue;
                }
                Deque<String> todo = new ArrayDeque<>(List.of(e.getKey()));
                Set<String> seen = new HashSet<>();
                boolean side = true;
                while (!todo.isEmpty() && side) {
                    String x = todo.pop();
                    if (!seen.add(x)) {
                        continue;
                    }
                    Element xn = nodes.get(x);
                    if (xn != null && "boundaryEvent".equals(xn.getLocalName()) && "false".equals(xn.getAttribute("cancelActivity"))) {
                        continue;
                    }
                    if (!in.containsKey(x)) {
                        side = false;
                    } else {
                        todo.addAll(in.get(x));
                    }
                }
                if (side) {
                    sideEnds.add(e.getKey());
                }
            }
        }

        private void walk(Element el, String sub) {
            for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element c) || !BPMN.equals(c.getNamespaceURI()) || !FLOW_NODES.contains(c.getLocalName())
                        || c.getAttribute("id").isEmpty()) {
                    continue;
                }
                String cid = c.getAttribute("id");
                nodes.put(cid, c);
                parent.put(cid, sub);
                if ("boundaryEvent".equals(c.getLocalName())) {
                    boundary.computeIfAbsent(c.getAttribute("attachedToRef"), k -> new ArrayList<>()).add(cid);
                }
                if ("startEvent".equals(c.getLocalName()) && (sub == null || eventSub(nodes.get(sub)))) {
                    starts.add(cid);
                }
                if ("subProcess".equals(c.getLocalName()) || "transaction".equals(c.getLocalName())) {
                    walk(c, cid);
                }
            }
        }
    }

    static boolean eventSub(Element e) {
        return e != null && "true".equals(e.getAttribute("triggeredByEvent"));
    }

    static List<Element> descendants(Element e, String ns, String local) {
        NodeList l = e.getElementsByTagNameNS(ns, local);
        List<Element> r = new ArrayList<>(l.getLength());
        for (int i = 0; i < l.getLength(); i++) {
            r.add((Element) l.item(i));
        }
        return r;
    }

    /** The element's own {@code operaton:field}s (not those of its listeners): name to string value. */
    static Map<String, String> fields(Element e) {
        Map<String, String> r = new HashMap<>();
        for (Node x = e.getFirstChild(); x != null; x = x.getNextSibling()) {
            if (!(x instanceof Element ext) || !"extensionElements".equals(ext.getLocalName())) {
                continue;
            }
            for (Node f = ext.getFirstChild(); f != null; f = f.getNextSibling()) {
                if (f instanceof Element fe && OP.equals(fe.getNamespaceURI()) && "field".equals(fe.getLocalName())) {
                    List<Element> s = descendants(fe, OP, "string");
                    r.put(fe.getAttribute("name"), s.isEmpty() ? fe.getAttribute("stringValue") : s.get(0).getTextContent());
                }
            }
        }
        return r;
    }

    static String cls(Element e) {
        return e.getAttributeNS(OP, "class");
    }

    static Map<String, String> assignments(String spec) {
        Map<String, String> r = new LinkedHashMap<>();
        if (spec != null) {
            for (String pair : spec.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    r.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
        return r;
    }

    static String cond(Element sf) {
        List<Element> c = descendants(sf, BPMN, "conditionExpression");
        return c.isEmpty() ? null : c.get(0).getTextContent().trim();
    }

    record State(String node, int cov, TreeMap<String, String> bind) {
        String key() {
            return node + "|" + cov + "|" + bind;
        }
    }

    record End(int cov, TreeMap<String, String> bind) {
    }

    static final class Analyzer {
        final Map<String, Proc> procs = new LinkedHashMap<>();
        final Predicate<String> formHasReasons;
        final Map<String, Set<End>> summaries = new HashMap<>();

        Analyzer(Map<String, String> bpmnByFile, Predicate<String> formHasReasons) {
            this.formHasReasons = formHasReasons;
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                for (Map.Entry<String, String> e : bpmnByFile.entrySet()) {
                    Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue().getBytes(StandardCharsets.UTF_8)));
                    for (Element p : descendants(d.getDocumentElement(), BPMN, "process")) {
                        procs.put(p.getAttribute("id"), new Proc(p, e.getKey()));
                    }
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        int rejectionTargets() {
            int n = 0;
            for (Proc p : procs.values()) {
                for (Element e : p.nodes.values()) {
                    if (isTarget(p, e)) {
                        n++;
                    }
                }
            }
            return n;
        }

        List<String> violations() {
            Map<String, String> found = new TreeMap<>();
            for (Proc p : procs.values()) {
                List<State> starts = new ArrayList<>();
                for (String s : p.starts) {
                    starts.add(new State(s, NONE, new TreeMap<>()));
                }
                run(p, starts, found, 0, Set.of());
            }
            List<String> r = new ArrayList<>();
            found.forEach((k, v) -> r.add(k + ": " + v));
            return r;
        }

        boolean isTarget(Proc p, Element e) {
            String t = e.getLocalName();
            if ("callActivity".equals(t) && REJECTION.equals(e.getAttribute("calledElement"))) {
                return true;
            }
            return "serviceTask".equals(t) && cls(e).endsWith("CaseRecordDelegate") && "REJECT".equals(fields(e).get("op"))
                    && !REJECTION.equals(p.id);
        }

        Set<End> summary(Proc p, int cov, int depth, Set<String> export) {
            String key = p.id + "|" + cov + "|" + new TreeSet<>(export);
            Set<End> s = summaries.get(key);
            if (s == null) {
                summaries.put(key, Collections.emptySet());   // recursion guard
                List<State> starts = new ArrayList<>();
                for (String st : p.starts) {
                    starts.add(new State(st, cov, new TreeMap<>()));
                }
                s = run(p, starts, null, depth, export);
                summaries.put(key, s);
            }
            return s;
        }

        /** Forward walk; reports violations when {@code found} is given; returns the states at the completing ends. */
        Set<End> run(Proc p, List<State> startStates, Map<String, String> found, int depth, Set<String> export) {
            Set<String> track = new HashSet<>(p.tested);
            track.addAll(export);
            Set<End> ends = new LinkedHashSet<>();
            Set<String> seen = new HashSet<>();
            Map<String, String> pred = new HashMap<>();
            Deque<State> stack = new ArrayDeque<>();
            Deque<String> from = new ArrayDeque<>();
            for (State s : startStates) {
                stack.push(s);
                from.push("");
            }
            while (!stack.isEmpty()) {
                State st = stack.pop();
                String via = from.pop();
                String key = st.key();
                if (!seen.add(key)) {
                    continue;
                }
                pred.put(key, via);
                Element el = p.nodes.get(st.node());
                if (el == null) {
                    continue;
                }
                String t = el.getLocalName();
                int cov = st.cov();
                if (found != null && cov == NONE && isTarget(p, el)) {
                    found.putIfAbsent(p.file + " [" + p.id + "] -> " + st.node(), trace(pred, key));
                }
                // boundary events leave the activity before it completes: the state on arrival, a human cover lost
                for (String b : p.boundary.getOrDefault(st.node(), List.of())) {
                    stack.push(new State(b, cov == CODED ? CODED : NONE, st.bind()));
                    from.push(key);
                }
                if (WAITS.contains(t) || "true".equals(el.getAttributeNS(OP, "asyncBefore"))
                        || "true".equals(el.getAttributeNS(OP, "asyncAfter"))) {
                    cov = cov == CODED ? CODED : NONE;
                }
                List<End> results = new ArrayList<>();
                TreeMap<String, String> b = new TreeMap<>(st.bind());
                if ("userTask".equals(t)) {
                    results.add(new End(cov == CODED ? CODED : formHasReasons.test(el.getAttributeNS(OP, "formKey")) ? HUMAN : NONE, b));
                } else if ("serviceTask".equals(t) && cls(el).endsWith("SetVariablesDelegate")) {
                    Map<String, String> f = fields(el);
                    Map<String, String> values = assignments(f.get("values"));
                    Map<String, String> defaults = assignments(f.get("defaults"));
                    int nc = cov;
                    if (values.containsKey("rejectionReasonCodes") || defaults.containsKey("rejectionReasonCodes")
                            || "true".equals(values.get("deemedDecision"))) {
                        nc = CODED;
                    }
                    for (Map.Entry<String, String> v : values.entrySet()) {
                        if (!track.contains(v.getKey())) {
                            continue;
                        }
                        if (!"null".equals(v.getValue()) && !v.getValue().startsWith("@")) {
                            b.put(v.getKey(), v.getValue());
                        } else {
                            b.remove(v.getKey());
                        }
                    }
                    results.add(new End(nc, b));
                } else if ("callActivity".equals(t) && procs.containsKey(el.getAttribute("calledElement")) && depth < 6) {
                    for (End e : summary(procs.get(el.getAttribute("calledElement")), cov, depth + 1, track)) {
                        TreeMap<String, String> nb = new TreeMap<>(b);
                        nb.putAll(e.bind());
                        results.add(new End(e.cov(), nb));
                    }
                } else {
                    results.add(new End(cov, b));
                }
                if (("subProcess".equals(t) || "transaction".equals(t)) && !eventSub(el)) {
                    for (End r : results) {
                        for (Map.Entry<String, String> c : p.parent.entrySet()) {
                            if (st.node().equals(c.getValue()) && "startEvent".equals(p.nodes.get(c.getKey()).getLocalName())) {
                                stack.push(new State(c.getKey(), r.cov(), r.bind()));
                                from.push(key);
                            }
                        }
                    }
                    continue;
                }
                List<Element> outs = p.out.getOrDefault(st.node(), List.of());
                if (outs.isEmpty()) {
                    String par = p.parent.get(st.node());
                    if ("endEvent".equals(t) && par != null && !eventSub(p.nodes.get(par))) {
                        for (End r : results) {
                            for (Element sf : p.out.getOrDefault(par, List.of())) {
                                stack.push(new State(sf.getAttribute("targetRef"), r.cov(), r.bind()));
                                from.push(key);
                            }
                        }
                    } else if ("endEvent".equals(t) && par == null && !p.sideEnds.contains(st.node())) {
                        ends.addAll(results);
                    }
                    continue;
                }
                for (End r : results) {
                    for (Element sf : outs) {
                        if (passes(el, sf, outs, r.bind())) {
                            String c = cond(sf);
                            int c3 = c != null && c.replace(" ", "").equals("${slaAction=='DEEMED'}") ? CODED : r.cov();
                            stack.push(new State(sf.getAttribute("targetRef"), c3, r.bind()));
                            from.push(key);
                        }
                    }
                }
            }
            return ends;
        }

        static boolean passes(Element gw, Element sf, List<Element> outs, Map<String, String> b) {
            String t = gw.getLocalName();
            if (!"exclusiveGateway".equals(t) && !"inclusiveGateway".equals(t)) {
                return true;
            }
            String c = cond(sf);
            if (c != null) {
                Matcher m = EQ.matcher(c);
                return !(m.matches() && b.containsKey(m.group(1)) && !b.get(m.group(1)).equals(m.group(2)));
            }
            if (sf.getAttribute("id").equals(gw.getAttribute("default"))) {
                for (Element o : outs) {
                    String oc = cond(o);
                    Matcher m = oc == null ? null : EQ.matcher(oc);
                    if (m != null && m.matches() && m.group(2).equals(b.get(m.group(1)))) {
                        return false;
                    }
                }
            }
            return true;
        }

        static String trace(Map<String, String> pred, String key) {
            List<String> out = new ArrayList<>();
            for (String k = key; k != null && !k.isEmpty() && out.size() < 25; k = pred.get(k)) {
                out.add(k.substring(0, k.indexOf('|')) + (k.contains("|0|") ? "*" : ""));
            }
            Collections.reverse(out);
            return String.join(" > ", out);
        }
    }
}
