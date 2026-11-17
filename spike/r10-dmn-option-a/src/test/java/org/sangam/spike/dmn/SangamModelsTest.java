// SPDX-License-Identifier: Apache-2.0
package org.sangam.spike.dmn;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnDecisionRequirementsGraph;
import org.sangam.spike.dmn.Engines.Outcome;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sangam's shipped .dmn files, unchanged, on both engines: deploy/compile result, then every decision table evaluated
 * with input vectors derived from its own rules (one vector per rule whose entries are simple literals or
 * comparisons). Agreement is only reported as agreement, not correctness. Also a simple timing and allocation probe.
 */
class SangamModelsTest {

    private static final Pattern CMP = Pattern.compile("^\\s*(>=|<=|>|<|=)?\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");
    private static final Pattern RANGE = Pattern.compile("^\\s*[\\[(\\]]\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\.\\.\\s*(-?\\d+(?:\\.\\d+)?)\\s*[\\])\\[]\\s*$");
    private static final Pattern STR = Pattern.compile("^\\s*\"([^\"]*)\"\\s*$");
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]*$");

    @Test
    void sangamModelsOnBothEngines() throws Exception {
        Path repo = Path.of(System.getProperty("user.dir")).resolve("../../sangam").normalize();
        List<Path> files;
        try (Stream<Path> s = Files.walk(repo)) {
            files = s.filter(p -> p.toString().endsWith(".dmn") && !p.toString().contains("node_modules")
                    && !p.toString().contains(java.io.File.separator + "target" + java.io.File.separator)).sorted().toList();
        }
        Map<String, Integer> stats = new TreeMap<>();
        StringBuilder diffs = new StringBuilder();
        StringBuilder deploy = new StringBuilder();
        long opNanos = 0, drNanos = 0, opBytes = 0, drBytes = 0, evals = 0;
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (Path f : files) {
            byte[] xml = Files.readAllBytes(f);
            String rel = repo.relativize(f).toString().replace('\\', '/');
            stats.merge("files", 1, Integer::sum);
            DmnDecisionRequirementsGraph drg = null;
            Engines.DroolsModel dm = null;
            boolean rewritten = false;
            String opErr = null, drErr = null;
            try {
                drg = Engines.operatonParse(xml);
            } catch (Throwable e) {
                opErr = Engines.root(e);
            }
            try {
                dm = Engines.droolsCompile(xml);
            } catch (Throwable e) {
                drErr = Engines.root(e);
            }
            stats.merge("deploy operaton " + (opErr == null ? "ok" : "refused"), 1, Integer::sum);
            stats.merge("deploy drools unchanged " + (drErr == null ? "ok" : "refused"), 1, Integer::sum);
            if (dm == null) {
                // second pass, clearly separated: the one mechanical edit Drools demands (a <variable> per decision,
                // named after the decision); the shipped files are not changed
                try {
                    dm = Engines.droolsCompile(withVariables(xml));
                    rewritten = true;
                    drErr = null;
                    stats.merge("deploy drools after adding decision variables ok", 1, Integer::sum);
                } catch (Throwable e) {
                    drErr = Engines.root(e);
                    stats.merge("deploy drools after adding decision variables refused", 1, Integer::sum);
                }
            }
            if (opErr != null || drErr != null) {
                deploy.append(rel).append(" | operaton: ").append(opErr == null ? "ok" : opErr).append(" | drools: ")
                        .append(drErr == null ? "ok" : drErr).append('\n');
            }
            if (drg == null || dm == null) {
                continue;
            }
            Document doc = parse(xml);
            for (Element dec : elements(doc.getDocumentElement(), "decision")) {
                Element table = first(dec, "decisionTable");
                if (table == null) {
                    continue;
                }
                String name = dec.getAttribute("name");
                DmnDecision od = drg.getDecisions().stream().filter(x -> name.equals(x.getName())).findFirst().orElse(null);
                if (od == null) {
                    continue;
                }
                stats.merge("tables", 1, Integer::sum);
                List<String> inputNames = new ArrayList<>();
                boolean simple = true;
                for (Element in : elements(table, "input")) {
                    String expr = first(in, "inputExpression") == null ? "" : first(in, "inputExpression").getTextContent().trim();
                    simple &= IDENT.matcher(expr).matches();
                    inputNames.add(expr);
                }
                if (!simple) {
                    stats.merge("tables skipped (computed input expression)", 1, Integer::sum);
                    continue;
                }
                List<Element> rules = elements(table, "rule");
                // per input, a fallback literal for '-' entries
                Map<Integer, Object> fallback = new LinkedHashMap<>();
                List<List<Object>> perRule = new ArrayList<>();
                for (Element rule : rules) {
                    List<Element> entries = elements(rule, "inputEntry");
                    List<Object> vec = new ArrayList<>();
                    for (int i = 0; i < entries.size(); i++) {
                        Object v = derive(entries.get(i).getTextContent());
                        vec.add(v);
                        if (v != null && v != SKIP && v != ANY) {
                            fallback.putIfAbsent(i, v);
                        }
                    }
                    perRule.add(vec);
                }
                for (List<Object> vec : perRule) {
                    Map<String, Object> inputs = new LinkedHashMap<>();
                    boolean ok = vec.size() == inputNames.size();
                    for (int i = 0; ok && i < vec.size(); i++) {
                        Object v = vec.get(i) == ANY ? fallback.get(i) : vec.get(i);
                        if (v == null || v == SKIP) {
                            ok = false;
                        } else {
                            inputs.put(inputNames.get(i), v);
                        }
                    }
                    if (!ok) {
                        stats.merge("vectors skipped (entry not a simple literal)", 1, Integer::sum);
                        continue;
                    }
                    long b0 = mx.getCurrentThreadAllocatedBytes(), t0 = System.nanoTime();
                    Outcome o = Engines.operatonEval(od, inputs);
                    long t1 = System.nanoTime(), b1 = mx.getCurrentThreadAllocatedBytes();
                    Outcome d = Engines.droolsEval(dm, rewritten ? feelName(name) : name, inputs);
                    long t2 = System.nanoTime(), b2 = mx.getCurrentThreadAllocatedBytes();
                    opNanos += t1 - t0;
                    drNanos += t2 - t1;
                    opBytes += b1 - b0;
                    drBytes += b2 - b1;
                    evals++;
                    String key;
                    if (o.error() && d.error()) {
                        key = "both error";
                    } else if (o.error() != d.error()) {
                        key = o.error() ? "only operaton error" : "only drools error";
                    } else {
                        key = Engines.same(normal(o.value()), d.value()) == null ? "same value" : "different value";
                    }
                    stats.merge("vectors " + key, 1, Integer::sum);
                    stats.merge("vectors total", 1, Integer::sum);
                    if (!"same value".equals(key) && !"both error".equals(key)) {
                        diffs.append(rel).append(" :: ").append(name).append(' ').append(inputs).append(" | operaton ").append(o)
                                .append(" | drools ").append(d).append('\n');
                    }
                }
            }
        }
        StringBuilder sum = new StringBuilder();
        stats.forEach((k, v) -> sum.append(k).append(": ").append(v).append('\n'));
        sum.append(String.format("evaluations timed: %d; mean per evaluation: operaton %.1f us / %d bytes, drools %.1f us / %d bytes "
                        + "(single thread, cold-to-warm mix, not a benchmark)%n", evals, opNanos / 1e3 / Math.max(1, evals),
                opBytes / Math.max(1, evals), drNanos / 1e3 / Math.max(1, evals), drBytes / Math.max(1, evals)));
        Path out = Path.of(System.getProperty("tck.out")).getParent().resolve("models");
        Files.createDirectories(out);
        Files.writeString(out.resolve("summary.txt"), sum);
        Files.writeString(out.resolve("differences.txt"), diffs);
        Files.writeString(out.resolve("deploy-differences.txt"), deploy);
        System.out.println("[models]\n" + sum);
    }

    /** Operaton-side value in Drools terms for comparison only (Long/Integer stay numbers; Scala seq is a List). */
    private static Object normal(Object v) {
        if (v instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        return v;
    }

    private static final Object SKIP = new Object();
    private static final Object ANY = new Object();

    private static Object derive(String entry) {
        String t = entry.trim();
        if (t.equals("-") || t.isEmpty()) {
            return ANY;
        }
        Matcher m = CMP.matcher(t);
        if (m.matches()) {
            BigDecimal n = new BigDecimal(m.group(2));
            String op = m.group(1) == null ? "=" : m.group(1);
            return switch (op) {
                case ">" -> n.add(BigDecimal.ONE);
                case "<" -> n.subtract(BigDecimal.ONE);
                default -> n;
            };
        }
        m = RANGE.matcher(t);
        if (m.matches()) {
            return new BigDecimal(m.group(1)).add(new BigDecimal(m.group(2))).divide(BigDecimal.valueOf(2));
        }
        m = STR.matcher(t);
        if (m.matches()) {
            return m.group(1);
        }
        if (t.equals("true") || t.equals("false")) {
            return Boolean.valueOf(t);
        }
        Matcher list = Pattern.compile("^\\s*\"([^\"]*)\"\\s*,").matcher(t);
        if (list.find()) {
            return list.group(1);
        }
        return SKIP;
    }

    /** Adds {@code <variable name="<decision name>"/>} as the first child of each decision that has none. */
    static byte[] withVariables(byte[] xml) throws Exception {
        Document doc = parse(xml);
        for (Element dec : elements(doc.getDocumentElement(), "decision")) {
            if (first(dec, "variable") != null && first(dec, "variable").getParentNode() == dec) {
                continue;
            }
            // Drools requires a FEEL-identifier decision name equal to its variable: rename (labels keep the text)
            String safe = feelName(dec.getAttribute("name"));
            dec.setAttribute("label", dec.getAttribute("name"));
            dec.setAttribute("name", safe);
            Element v = doc.createElementNS(dec.getNamespaceURI(), dec.getPrefix() == null ? "variable" : dec.getPrefix() + ":variable");
            v.setAttribute("name", safe);
            // and every free variable of an input expression declared as inputData + required by the decision
            for (Element ie : elements(dec, "inputExpression")) {
                String id = ie.getTextContent().trim();
                if (!IDENT.matcher(id).matches() || id.contains(".")) {
                    continue;
                }
                String inId = "in_" + id;
                if (doc.getElementById(inId) == null && !hasInputData(doc, id)) {
                    Element in = doc.createElementNS(dec.getNamespaceURI(), dec.getPrefix() == null ? "inputData" : dec.getPrefix() + ":inputData");
                    in.setAttribute("id", inId);
                    in.setAttribute("name", id);
                    in.setIdAttribute("id", true);
                    Element iv = doc.createElementNS(dec.getNamespaceURI(), dec.getPrefix() == null ? "variable" : dec.getPrefix() + ":variable");
                    iv.setAttribute("name", id);
                    in.appendChild(iv);
                    doc.getDocumentElement().appendChild(in);
                }
                Element req = doc.createElementNS(dec.getNamespaceURI(), dec.getPrefix() == null ? "informationRequirement" : dec.getPrefix() + ":informationRequirement");
                Element ri = doc.createElementNS(dec.getNamespaceURI(), dec.getPrefix() == null ? "requiredInput" : dec.getPrefix() + ":requiredInput");
                ri.setAttribute("href", "#" + inId);
                req.appendChild(ri);
                Element logic = first(dec, "decisionTable") != null ? first(dec, "decisionTable") : first(dec, "literalExpression");
                if (logic != null && logic.getParentNode() == dec) {
                    dec.insertBefore(req, logic);
                }
            }
            Node after = dec.getFirstChild();
            while (after != null && after instanceof Element e && ("description".equals(e.getLocalName()) || "extensionElements".equals(e.getLocalName()))) {
                after = after.getNextSibling();
            }
            while (after != null && !(after instanceof Element)) {
                after = after.getNextSibling();
            }
            // description / extensionElements come first in the schema order
            Node n = dec.getFirstChild();
            Node insertBefore = null;
            for (; n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && !"description".equals(e.getLocalName()) && !"extensionElements".equals(e.getLocalName())) {
                    insertBefore = e;
                    break;
                }
            }
            dec.insertBefore(v, insertBefore);
        }
        var t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        var out = new java.io.ByteArrayOutputStream();
        t.transform(new javax.xml.transform.dom.DOMSource(doc), new javax.xml.transform.stream.StreamResult(out));
        return out.toByteArray();
    }

    static String feelName(String s) {
        String t = s.replaceAll("[^A-Za-z0-9_ ]", "_").trim();
        return t.isEmpty() || !Character.isLetter(t.charAt(0)) ? "d_" + t : t;
    }

    private static boolean hasInputData(Document doc, String name) {
        for (Element e : elements(doc.getDocumentElement(), "inputData")) {
            if (name.equals(e.getAttribute("name"))) {
                return true;
            }
        }
        return false;
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static List<Element> elements(Element e, String local) {
        List<Element> out = new ArrayList<>();
        NodeList all = e.getElementsByTagNameNS("*", local);
        for (int i = 0; i < all.getLength(); i++) {
            out.add((Element) all.item(i));
        }
        return out;
    }

    private static Element first(Element e, String local) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && local.equals(c.getLocalName())) {
                return c;
            }
        }
        List<Element> l = elements(e, local);
        return l.isEmpty() ? null : l.get(0);
    }
}
