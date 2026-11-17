// SPDX-License-Identifier: Apache-2.0
package org.sangam.spike.dmn;

import org.junit.jupiter.api.Test;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.feel.FEEL;
import org.kie.dmn.feel.lang.types.impl.ComparablePeriod;
import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnDecisionRequirementsGraph;
import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.dmn.engine.DmnDecisionResultEntries;
import org.operaton.bpm.dmn.engine.DmnEngine;
import org.operaton.bpm.dmn.engine.DmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.impl.DmnDecisionTableImpl;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.model.dmn.HitPolicy;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * DMN TCK (github.com/dmn-tck/tck TestCases) against three DMN paths: plain Operaton 2.2.0, the option A hybrid with
 * boxed-only routing, and the hybrid with all models on Drools. One row per resultNode; a baseline, not a gate.
 *
 * <p>Status: PASS; FAIL (wrong value); ERROR (evaluation or parse failure where a value was expected); STRICT (the
 * TCK expects null or an error result and the path raised an error: Sangam's SG-34 policy, not spec behaviour);
 * NOT_RUN (the harness cannot invoke the target, with the reason).
 */
class TckRunnerTest {

    enum Mode { OPERATON, HYBRID_BOXED, HYBRID_ALL }

    record Row(String suite, String dir, String file, String caseId, String node, String status, String detail) {
    }

    private static final FEEL FEEL_ENGINE = FEEL.newInstance(TckRunnerTest.class.getClassLoader(), List.of());

    @Test
    void runTck() throws Exception {
        Path root = Path.of(System.getProperty("tck.dir"));
        Path out = Path.of(System.getProperty("tck.out"));
        Files.createDirectories(out);
        List<Path> tests;
        try (Stream<Path> s = Files.walk(root)) {
            tests = s.filter(p -> p.getFileName().toString().matches(".*-test-\\d+\\.xml"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
        for (Mode mode : Mode.values()) {
            List<Row> rows = new ArrayList<>();
            for (Path t : tests) {
                rows.addAll(runFile(mode, root, t));
            }
            write(out, mode, rows);
        }
    }

    // ---------------------------------------------------------------- per test file

    private List<Row> runFile(Mode mode, Path root, Path testFile) throws Exception {
        Path dir = testFile.getParent();
        String suite = root.relativize(dir).getName(0).toString();
        String dirName = dir.getFileName().toString();
        Document doc = parse(Files.readAllBytes(testFile));
        Element top = doc.getDocumentElement();
        String modelName = text(child(top, "modelName"));
        List<Row> rows = new ArrayList<>();
        List<Path> models;
        try (Stream<Path> s = Files.list(dir)) {
            models = s.filter(p -> p.toString().endsWith(".dmn")).sorted().toList();
        }
        Path main = dir.resolve(modelName);
        DmnEngine engine;
        DmnDecisionRequirementsGraph drg = null;
        String parseError = null;
        try {
            DmnEngine built = engine(mode, models, main);
            engine = built;
            byte[] bytes = Files.readAllBytes(main);
            drg = timed(() -> built.parseDecisionRequirementsGraph(new ByteArrayInputStream(bytes)));
        } catch (Throwable e) {
            engine = null;
            parseError = "parse: " + oneLine(e);
        }
        for (Element tc : children(top, "testCase")) {
            String id = tc.getAttribute("id");
            String type = tc.getAttribute("type").isEmpty() ? "decision" : tc.getAttribute("type");
            Map<String, Object> inputs = new LinkedHashMap<>();
            String inputError = null;
            for (Element in : children(tc, "inputNode")) {
                try {
                    inputs.put(in.getAttribute("name"), toEngine(mode, value(in)));
                } catch (RuntimeException e) {
                    inputError = "input " + in.getAttribute("name") + ": " + oneLine(e);
                }
            }
            for (Element rn : children(tc, "resultNode")) {
                String node = rn.getAttribute("name");
                boolean errorExpected = "true".equals(rn.getAttribute("errorResult"));
                Element expectedEl = child(rn, "expected");
                Object expected = expectedEl == null ? null : value(expectedEl);
                Row base = new Row(suite, dirName, testFile.getFileName().toString(), id, node, null, null);
                if (parseError != null) {
                    rows.add(with(base, "ERROR", parseError)); // a model that does not deploy fails every case
                    continue;
                }
                if (inputError != null) {
                    rows.add(with(base, "ERROR", inputError));
                    continue;
                }
                if ("bkm".equals(type)) {
                    rows.add(with(base, "NOT_RUN", "BKM invoked directly as a function: no Sangam API invokes a BKM on its own"));
                    continue;
                }
                Object actual;
                try {
                    if ("decisionService".equals(type)) {
                        if (mode == Mode.OPERATON) {
                            rows.add(with(base, "ERROR", "decision service: Operaton has no decision-service evaluation"));
                            continue;
                        }
                        String svc = tc.getAttribute("invocableName");
                        DroolsDecisionLogic logic = anyDroolsLogic(drg);
                        if (logic == null) {
                            rows.add(with(base, "ERROR", "decision service: model not routed to Drools"));
                            continue;
                        }
                        actual = decisionService(logic, svc, node, inputs);
                    } else {
                        DmnDecision d = decision(drg, node);
                        if (d == null) {
                            rows.add(with(base, "ERROR", "no decision named " + node));
                            continue;
                        }
                        DmnEngine e = engine;
                        DmnDecisionResult r = timed(() -> e.evaluateDecision(d, Variables.fromMap(inputs)));
                        actual = resultValue(d, r);
                    }
                } catch (Throwable e) {
                    // errorResult="true": the TCK expects an error, so raising one passes
                    rows.add(with(base, errorExpected ? "PASS" : expected == null ? "STRICT" : "ERROR", "eval: " + oneLine(e)));
                    continue;
                }
                if (errorExpected) {
                    rows.add(with(base, actual == null ? "PASS" : "FAIL", "errorResult expected, got " + show(actual)));
                    continue;
                }
                String why = compare(expected, actual);
                rows.add(with(base, why == null ? "PASS" : "FAIL", why == null ? "" : why));
            }
        }
        return rows;
    }

    private static DmnEngine engine(Mode mode, List<Path> models, Path main) throws IOException {
        if (mode == Mode.OPERATON) {
            return Engines.operatonEngine(); // Operaton 2.2.0 + SangamFeel + PriorityHitPolicies (production configuration)
        }
        HybridDmnEngineConfiguration cfg = new HybridDmnEngineConfiguration(mode == Mode.HYBRID_ALL
                ? HybridDmnEngineConfiguration.Routing.ALL : HybridDmnEngineConfiguration.Routing.BOXED_ONLY);
        for (Path m : models) {
            if (!m.equals(main)) {
                cfg.companionModels().add(Files.readAllBytes(m));
            }
        }
        return cfg.buildEngine();
    }

    private static <T> T timed(java.util.concurrent.Callable<T> c) throws Exception {
        CompletableFuture<T> f = CompletableFuture.supplyAsync(() -> {
            try {
                return c.call();
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        try {
            return f.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof java.util.concurrent.CompletionException ce && ce.getCause() instanceof Exception x ? x
                    : (e.getCause() instanceof Exception x2 ? x2 : e);
        }
    }

    private static DmnDecision decision(DmnDecisionRequirementsGraph drg, String name) {
        for (DmnDecision d : drg.getDecisions()) {
            if (name.equals(d.getName())) {
                return d;
            }
        }
        return drg.getDecision(name);
    }

    private static DroolsDecisionLogic anyDroolsLogic(DmnDecisionRequirementsGraph drg) {
        for (DmnDecision d : drg.getDecisions()) {
            if (d.getDecisionLogic() instanceof DroolsDecisionLogic l) {
                return l;
            }
        }
        return null;
    }

    private static Object decisionService(DroolsDecisionLogic logic, String svc, String node, Map<String, Object> inputs) {
        DMNContext ctx = logic.runtime().newContext();
        inputs.forEach(ctx::set);
        DMNResult r = logic.runtime().evaluateDecisionService(logic.model(), ctx, svc);
        if (r.hasErrors()) {
            throw new IllegalStateException(r.getMessages().toString());
        }
        var dr = r.getDecisionResultByName(node);
        Object v = dr != null ? dr.getResult() : r.getContext().get(node);
        return DroolsEvaluationHandler.toEngineValue(v);
    }

    /** The decision's value in TCK terms (table rows folded per hit policy, single output unwrapped). */
    private static Object resultValue(DmnDecision d, DmnDecisionResult r) {
        if (d.getDecisionLogic() instanceof DmnDecisionTableImpl t) {
            HitPolicy hp = t.getHitPolicyHandler().getHitPolicyEntry().getHitPolicy();
            boolean aggregated = t.getHitPolicyHandler().getHitPolicyEntry().getAggregator() != null;
            boolean multi = !aggregated && (hp == HitPolicy.COLLECT || hp == HitPolicy.RULE_ORDER || hp == HitPolicy.OUTPUT_ORDER);
            List<Object> values = new ArrayList<>();
            for (DmnDecisionResultEntries e : r) {
                values.add(e.size() == 1 && t.getOutputs().size() == 1 ? e.getSingleEntry() : new LinkedHashMap<>(e.getEntryMap()));
            }
            if (multi) {
                return values;
            }
            return values.isEmpty() ? null : values.get(0);
        }
        if (r.isEmpty()) {
            return null;
        }
        DmnDecisionResultEntries e = r.getFirstResult();
        return e.size() == 1 ? e.getSingleEntry() : new LinkedHashMap<>(e.getEntryMap());
    }

    // ---------------------------------------------------------------- TCK values

    private static Object value(Element holder) {
        List<Element> comps = children(holder, "component");
        if (!comps.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Element c : comps) {
                m.put(c.getAttribute("name"), value(c));
            }
            return m;
        }
        Element list = child(holder, "list");
        if (list != null) {
            List<Object> l = new ArrayList<>();
            for (Element item : children(list, "item")) {
                l.add(value(item));
            }
            return l;
        }
        Element v = child(holder, "value");
        if (v == null) {
            return null;
        }
        if ("true".equals(v.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil"))) {
            return null;
        }
        String type = v.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type");
        String text = v.getTextContent();
        String local = type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
        return switch (local) {
            case "decimal", "double", "int", "integer", "long", "float" -> new BigDecimal(text.trim());
            case "boolean" -> Boolean.valueOf(text.trim());
            case "date" -> LocalDate.parse(text.trim());
            case "dateTime" -> feel("date and time(\"" + text.trim() + "\")");
            case "time" -> feel("time(\"" + text.trim() + "\")");
            case "duration" -> feel("duration(\"" + text.trim() + "\")");
            default -> text;
        };
    }

    private static Object feel(String expr) {
        Object o = FEEL_ENGINE.evaluate(expr);
        if (o == null) {
            throw new IllegalArgumentException("invalid literal " + expr);
        }
        return o;
    }

    /** Operaton's FEEL engine gets JDK types; Drools accepts both. */
    private static Object toEngine(Mode mode, Object v) {
        if (v instanceof ComparablePeriod p) {
            return mode == Mode.OPERATON ? p.asPeriod() : p;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> o = new LinkedHashMap<>();
            m.forEach((k, x) -> o.put((String) k, toEngine(mode, x)));
            return o;
        }
        if (v instanceof List<?> l) {
            List<Object> o = new ArrayList<>();
            l.forEach(x -> o.add(toEngine(mode, x)));
            return o;
        }
        return v;
    }

    /** null when equal, else the reason. Leaves compare with FEEL '=' (Drools). */
    private static String compare(Object expected, Object actual) {
        if (expected == null) {
            return actual == null ? null : "expected null, got " + show(actual);
        }
        if (actual == null) {
            return "expected " + show(expected) + ", got null";
        }
        if (expected instanceof Map<?, ?> em) {
            if (!(actual instanceof Map<?, ?> am)) {
                return "expected context, got " + show(actual);
            }
            for (var e : em.entrySet()) {
                if (!am.containsKey(e.getKey())) {
                    return "missing key " + e.getKey();
                }
                String w = compare(e.getValue(), am.get(e.getKey()));
                if (w != null) {
                    return e.getKey() + ": " + w;
                }
            }
            return am.size() == em.size() ? null : "extra keys " + am.keySet();
        }
        if (expected instanceof List<?> el) {
            if (!(actual instanceof List<?> al)) {
                return el.size() == 1 ? compare(el.get(0), actual) : "expected list, got " + show(actual);
            }
            if (al.size() != el.size()) {
                return "list size " + al.size() + " != " + el.size();
            }
            for (int i = 0; i < el.size(); i++) {
                String w = compare(el.get(i), al.get(i));
                if (w != null) {
                    return "[" + i + "] " + w;
                }
            }
            return null;
        }
        Object a = normalise(actual);
        Object eq;
        try {
            eq = FEEL_ENGINE.evaluate("a = b", Map.of("a", a, "b", expected));
        } catch (RuntimeException ex) {
            eq = null;
        }
        return Boolean.TRUE.equals(eq) ? null : "expected " + show(expected) + ", got " + show(actual);
    }

    private static Object normalise(Object v) {
        if (v instanceof Double d) {
            return BigDecimal.valueOf(d);
        }
        if (v instanceof Float f) {
            return new BigDecimal(f.toString());
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof Period p) {
            return new ComparablePeriod(p);
        }
        return v;
    }

    private static String show(Object o) {
        String s = o == null ? "null" : o + " (" + o.getClass().getSimpleName() + ")";
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    // ---------------------------------------------------------------- report

    private static void write(Path out, Mode mode, List<Row> rows) throws IOException {
        StringBuilder csv = new StringBuilder("suite,dir,file,case,resultNode,status,detail\n");
        Map<String, Integer> total = new TreeMap<>();
        Map<String, Map<String, Integer>> bySuite = new TreeMap<>();
        for (Row r : rows) {
            csv.append(String.join(",", q(r.suite()), q(r.dir()), q(r.file()), q(r.caseId()), q(r.node()), r.status(), q(r.detail())))
                    .append('\n');
            total.merge(r.status(), 1, Integer::sum);
            bySuite.computeIfAbsent(r.suite(), k -> new TreeMap<>()).merge(r.status(), 1, Integer::sum);
        }
        Files.writeString(out.resolve("tck-" + mode.name().toLowerCase() + ".csv"), csv, StandardCharsets.UTF_8);
        StringBuilder md = new StringBuilder("mode " + mode + ": " + rows.size() + " result nodes " + total + "\n");
        bySuite.forEach((s, m) -> md.append("  ").append(s).append(' ').append(m).append('\n'));
        Files.writeString(out.resolve("tck-" + mode.name().toLowerCase() + ".txt"), md, StandardCharsets.UTF_8);
        System.out.print("[tck] " + md);
    }

    private static String q(String s) {
        return s == null ? "" : '"' + s.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + '"';
    }

    private static Row with(Row r, String status, String detail) {
        return new Row(r.suite(), r.dir(), r.file(), r.caseId(), r.node(), status, detail);
    }

    private static String oneLine(Throwable e) {
        Throwable c = e;
        String msg = c.getClass().getSimpleName() + ": " + c.getMessage();
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
            msg = c.getClass().getSimpleName() + ": " + c.getMessage();
        }
        msg = msg.replace('\n', ' ');
        return msg.length() > 300 ? msg.substring(0, 300) + "..." : msg;
    }

    // ---------------------------------------------------------------- XML

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static List<Element> children(Element e, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && local.equals(c.getLocalName())) {
                out.add(c);
            }
        }
        return out;
    }

    private static Element child(Element e, String local) {
        List<Element> l = children(e, local);
        return l.isEmpty() ? null : l.get(0);
    }

    private static String text(Element e) {
        return e == null ? null : e.getTextContent().trim();
    }
}
