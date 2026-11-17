// SPDX-License-Identifier: Apache-2.0
package org.sangam.spike.dmn;

import org.junit.jupiter.api.Test;
import org.sangam.spike.dmn.Engines.Kind;
import org.sangam.spike.dmn.Engines.Outcome;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mandatory correctness and security gates for the DMN engine choice (owner directive 2026-11-17 section 2).
 * Every expected result is written by hand from the cited rule (DMN 1.5 spec formal-24-01-01 / SG-33/34/35 in
 * STANDARDS-PROFILE "FEEL policy" / OWASP XXE), never from either engine's output. Where Sangam's rule is stricter
 * than the spec (SG-34: the spec yields null, Sangam fails the decision) the gate expects Sangam's rule and says so.
 */
class GateSuiteTest {

    static final Object ERR = new Object() {
        @Override
        public String toString() {
            return "ERROR";
        }
    };

    record Gate(String id, String criterion, boolean mandatory, String source, String xml, String decision,
                Map<String, Object> inputs, Object expected) {
    }

    static final String NS = "https://www.omg.org/spec/DMN/20230324/MODEL/";

    /** A literal-expression decision "d" over the named inputs ("name" or "name:typeRef"). */
    static String lit(String expr, String... inputs) {
        StringBuilder in = new StringBuilder();
        StringBuilder req = new StringBuilder();
        for (String i : inputs) {
            String[] p = i.split(":");
            in.append("<inputData id=\"i_").append(p[0]).append("\" name=\"").append(p[0]).append("\"><variable name=\"")
                    .append(p[0]).append('"').append(p.length > 1 ? " typeRef=\"" + p[1] + "\"" : "").append("/></inputData>");
            req.append("<informationRequirement><requiredInput href=\"#i_").append(p[0]).append("\"/></informationRequirement>");
        }
        return defs(in + "<decision id=\"d\" name=\"d\"><variable name=\"d\"/>" + req
                + "<literalExpression><text>" + esc(expr) + "</text></literalExpression></decision>");
    }

    /**
     * A one-input one-output decision table "d". rules: "inputEntry|outputEntry". outputValues: comma list or null.
     * aggregation: SUM/COUNT/... or null. defaultOut: default output entry or null.
     */
    static String table(String hitPolicy, String aggregation, String inType, String outType, String outputValues,
                        String defaultOut, String... rules) {
        StringBuilder r = new StringBuilder();
        int n = 0;
        for (String rule : rules) {
            String[] p = rule.split("\\|");
            r.append("<rule id=\"r").append(n++).append("\"><inputEntry><text>").append(esc(p[0]))
                    .append("</text></inputEntry><outputEntry><text>").append(esc(p[1])).append("</text></outputEntry></rule>");
        }
        String ov = outputValues == null ? "" : "<outputValues><text>" + esc(outputValues) + "</text></outputValues>";
        String dflt = defaultOut == null ? "" : "<defaultOutputEntry><text>" + esc(defaultOut) + "</text></defaultOutputEntry>";
        return defs("<inputData id=\"i_x\" name=\"x\"><variable name=\"x\" typeRef=\"" + inType + "\"/></inputData>"
                + "<decision id=\"d\" name=\"d\"><variable name=\"d\"/><informationRequirement><requiredInput href=\"#i_x\"/></informationRequirement>"
                + "<decisionTable id=\"t\" hitPolicy=\"" + hitPolicy + "\"" + (aggregation == null ? "" : " aggregation=\"" + aggregation + "\"") + ">"
                + "<input id=\"in\" label=\"x\"><inputExpression id=\"ie\" typeRef=\"" + inType + "\"><text>x</text></inputExpression></input>"
                + "<output id=\"out\" name=\"o\" typeRef=\"" + outType + "\">" + ov + dflt + "</output>" + r + "</decisionTable></decision>");
    }

    static String defs(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"" + NS + "\" id=\"gate\" name=\"gate\" "
                + "namespace=\"https://sangam.org/gate\">" + body + "</definitions>";
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static Map<String, Object> in(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    static final String XXE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE definitions [<!ENTITY x SYSTEM \"file:///C:/Windows/win.ini\">]>"
            + "<definitions xmlns=\"" + NS + "\" id=\"gate\" name=\"gate\" namespace=\"https://sangam.org/gate\">"
            + "<decision id=\"d\" name=\"d\"><variable name=\"d\"/><literalExpression><text>\"&x;\"</text></literalExpression></decision></definitions>";

    static List<Gate> gates() {
        String spec = "DMN 1.5 (formal/24-01-01) ";
        List<Gate> g = new ArrayList<>();
        // --- exact decimals (SG-33; FEEL number = IEEE 754-2008 decimal128, 34 digits)
        g.add(new Gate("DEC-1", "exact decimals", true, spec + "10.3.2.3.1 decimal128; SG-33", lit("0.1 + 0.2"), "d", in(), n("0.3")));
        g.add(new Gate("DEC-2", "exact decimals", true, spec + "10.3.2.3.1 (34 significant digits)", lit("1 / 3"), "d", in(),
                n("0.3333333333333333333333333333333333")));
        g.add(new Gate("DEC-3", "exact decimals", true, "SG-33 BigDecimal input unchanged", lit("a * 3", "a:number"), "d",
                in("a", n("1000.10")), n("3000.30")));
        g.add(new Gate("DEC-4", "exact decimals", true, spec + "10.3.2.3.1 (19 digits exact)", lit("12345678901234567.89 * 100"), "d", in(),
                n("1234567890123456789")));
        g.add(new Gate("DEC-5", "exact decimals", true, spec + "10.3.4.5 decimal(n, scale) rounds half even",
                lit("[decimal(2.5, 0), decimal(3.5, 0), decimal(1/3, 2)]"), "d", in(), List.of(n("2"), n("4"), n("0.33"))));
        g.add(new Gate("DEC-6", "exact decimals", true, "SG-33 never Double", lit("a / 4", "a:number"), "d", in("a", 10L), n("2.5")));
        // --- dates and times
        g.add(new Gate("DAT-1", "dates", true, "SG-35 / OMG DMN13-35: '=' on date-time is the same instant",
                lit("date and time(\"2024-01-01T10:00:00+01:00\") = date and time(\"2024-01-01T09:00:00Z\")"), "d", in(), true));
        g.add(new Gate("DAT-2", "dates", true, spec + "10.3.2.3.5 date - date is a days-time duration",
                lit("date(\"2024-03-31\") - date(\"2024-03-01\") = duration(\"P30D\")"), "d", in(), true));
        g.add(new Gate("DAT-3", "dates", true, spec + "10.3.4.4 years and months duration example",
                lit("years and months duration(date(\"2011-12-22\"), date(\"2013-08-24\")) = duration(\"P1Y8M\")"), "d", in(), true));
        g.add(new Gate("DAT-4", "dates", true, "SG-34 invalid literal is refused (spec: null)", lit("date(\"2024-02-30\")"), "d", in(), ERR));
        g.add(new Gate("DAT-5", "dates", true, "SG-34 requirement: invalid string into a built-in fails (Operaton residual documented)",
                lit("date(s)", "s:string"), "d", in("s", "2024-13-45"), ERR));
        g.add(new Gate("DAT-6", "dates", true, spec + "10.3.2.3.4 IANA zone id; 10:00 Asia/Kolkata = 04:30Z",
                lit("date and time(\"2024-01-01T10:00:00@Asia/Kolkata\") < date and time(\"2024-01-01T05:00:00Z\")"), "d", in(), true));
        // --- missing values and errors (SG-34 strict; the spec returns null in each case)
        g.add(new Gate("NUL-1", "null and errors", true, "SG-34 missing variable fails", lit("x + 1", "x:number"), "d", in(), ERR));
        g.add(new Gate("NUL-2", "null and errors", true, "SG-34 null operand fails", lit("x + 1", "x:number"), "d", in("x", null), ERR));
        g.add(new Gate("NUL-3", "null and errors", true, "SG-34 a genuine null is a value; spec 10.3.2.2", lit("x = null", "x:number"), "d",
                in("x", null), true));
        g.add(new Gate("NUL-4", "null and errors", true, "SG-34 mistyped operand fails", lit("s * 2", "s:string"), "d", in("s", "a"), ERR));
        g.add(new Gate("NUL-5", "null and errors", true, "SG-34 division by zero fails", lit("1 / 0"), "d", in(), ERR));
        g.add(new Gate("NUL-6", "null and errors", true, "SG-34 unknown function fails (deploy or evaluation)", lit("foo(1)"), "d", in(), ERR));
        g.add(new Gate("NUL-7", "null and errors", true, "SG-34 SANGAM-DMN-NULL-INPUT: '-' never catches null",
                table("UNIQUE", null, "number", "string", null, null, "-|\"a\""), "d", in("x", null), ERR));
        g.add(new Gate("NUL-8", "null and errors", true, "SG-34 explicit null rule matches null input",
                table("UNIQUE", null, "number", "string", null, null, "null|\"n\"", ">0|\"p\""), "d", in("x", null), "n"));
        // --- hit policies, gaps and overlaps (spec 8.2.11)
        g.add(new Gate("HIT-1", "hit policies", true, spec + "8.2.11 UNIQUE overlap (spec: null) + Sangam fail-on-error policy SG-34 (STANDARDS-PROFILE Strict evaluation)",
                table("UNIQUE", null, "number", "string", null, null, ">0|\"a\"", ">5|\"b\""), "d", in("x", 10), ERR));
        g.add(new Gate("HIT-2", "hit policies", true, spec + "8.2.11 ANY different outputs (spec: null) + Sangam fail-on-error policy SG-34",
                table("ANY", null, "number", "string", null, null, ">0|\"a\"", ">5|\"b\""), "d", in("x", 10), ERR));
        g.add(new Gate("HIT-3", "hit policies", true, spec + "8.2.11 ANY: equal outputs",
                table("ANY", null, "number", "string", null, null, ">0|\"a\"", ">5|\"a\""), "d", in("x", 10), "a"));
        g.add(new Gate("HIT-4", "hit policies", true, spec + "8.2.11 FIRST", table("FIRST", null, "number", "string", null, null,
                ">0|\"a\"", ">5|\"b\""), "d", in("x", 10), "a"));
        g.add(new Gate("HIT-5", "hit policies", true, spec + "8.2.11 PRIORITY by output values order",
                table("PRIORITY", null, "number", "string", "\"high\",\"low\"", null, ">0|\"low\"", ">5|\"high\""), "d", in("x", 10), "high"));
        g.add(new Gate("HIT-6", "hit policies", true, spec + "8.2.11 OUTPUT ORDER",
                table("OUTPUT ORDER", null, "number", "string", "\"high\",\"low\"", null, ">0|\"low\"", ">5|\"high\""), "d", in("x", 10),
                List.of("high", "low")));
        g.add(new Gate("HIT-7", "hit policies", true, spec + "8.2.11 RULE ORDER",
                table("RULE ORDER", null, "number", "string", null, null, ">0|\"low\"", ">5|\"high\""), "d", in("x", 10), List.of("low", "high")));
        g.add(new Gate("HIT-8", "hit policies", true, spec + "8.2.11 COLLECT SUM, exact",
                table("COLLECT", "SUM", "number", "number", null, null, ">0|0.1", ">5|0.2"), "d", in("x", 10), n("0.3")));
        g.add(new Gate("HIT-9", "hit policies", true, spec + "8.2.11 COLLECT COUNT",
                table("COLLECT", "COUNT", "number", "number", null, null, ">0|1", ">5|7"), "d", in("x", 10), n("2")));
        g.add(new Gate("HIT-9b", "hit policies", false, spec + "COLLECT COUNT with duplicate outputs; expected 'distinct outputs' "
                + "is pending a check against the DMN 1.5 PDF (informational until then)",
                table("COLLECT", "COUNT", "number", "number", null, null, ">0|1", ">5|1"), "d", in("x", 10), n("1")));
        g.add(new Gate("GAP-1", "gaps", true, spec + "8.2.11 no matching rule, single hit: null",
                table("UNIQUE", null, "number", "string", null, null, ">0|\"a\""), "d", in("x", -1), null));
        g.add(new Gate("GAP-2", "gaps", true, spec + "decision table default output entry (clause number pending a check against the DMN 1.5 PDF, not downloaded)",
                table("UNIQUE", null, "number", "string", null, "\"z\"", ">0|\"a\""), "d", in("x", -1), "z"));
        // --- security
        g.add(new Gate("SEC-1", "security", true, "OWASP XXE: a DOCTYPE / external entity is refused, no file content",
                XXE, "d", in(), ERR));
        g.add(new Gate("SEC-2", "security", true, "Sangam SangamFeelEngine.java:68 withEnabledExternalFunctions(false); FeelClockTest: external Java function refused",
                lit("(function(k) external {java: {class: \"java.lang.System\", method signature: \"getProperty(java.lang.String)\"}})(\"user.home\")"),
                "d", in(), ERR));
        // --- boxed expressions and decision services (capabilities, not correctness gates)
        g.add(new Gate("BOX-1", "DMN features", false, spec + "BKM and invocation (clause numbers pending a check against the DMN 1.5 PDF, not downloaded)",
                defs("<businessKnowledgeModel id=\"b\" name=\"fee\"><variable name=\"fee\"/><encapsulatedLogic><formalParameter name=\"y\" typeRef=\"number\"/>"
                        + "<literalExpression><text>y * 2</text></literalExpression></encapsulatedLogic></businessKnowledgeModel>"
                        + "<decision id=\"d\" name=\"d\"><variable name=\"d\"/><knowledgeRequirement><requiredKnowledge href=\"#b\"/></knowledgeRequirement>"
                        + "<literalExpression><text>fee(21)</text></literalExpression></decision>"), "d", in(), n("42")));
        g.add(new Gate("BOX-2", "DMN features", false, spec + "10.2.1.4 boxed context",
                defs("<decision id=\"d\" name=\"d\"><variable name=\"d\"/><context><contextEntry><variable name=\"a\"/><literalExpression><text>2</text></literalExpression></contextEntry>"
                        + "<contextEntry><literalExpression><text>a * 3</text></literalExpression></contextEntry></context></decision>"), "d", in(), n("6")));
        g.add(new Gate("BOX-3", "DMN features", false, spec + "10.2.1.6 boxed relation",
                defs("<decision id=\"d\" name=\"d\"><variable name=\"d\"/><relation><column name=\"k\"/><row><literalExpression><text>1</text></literalExpression></row>"
                        + "<row><literalExpression><text>2</text></literalExpression></row></relation></decision>"), "d", in(),
                List.of(Map.of("k", n("1")), Map.of("k", n("2")))));
        g.add(new Gate("BOX-4", "DMN features", false, spec + "FEEL filter expression (clause number pending a check against the DMN 1.5 PDF, not downloaded)", lit("[1,2,3][item > 1]"), "d", in(),
                List.of(n("2"), n("3"))));
        return g;
    }

    @Test
    void gateTable() throws Exception {
        StringBuilder md = new StringBuilder("| Gate | Criterion | Mandatory | Source of the expected result | Expected | Operaton 2.2.0 + SangamFeel | Drools 10.1.0 |\n|---|---|---|---|---|---|---|\n");
        StringBuilder detail = new StringBuilder();
        int[][] count = new int[2][2];
        for (Gate gate : gates()) {
            String[] cells = new String[2];
            for (Kind k : Kind.values()) {
                Outcome o = Engines.evaluate(k, gate.xml().getBytes(StandardCharsets.UTF_8), gate.decision(), gate.inputs());
                boolean pass;
                if (gate.expected() == ERR) {
                    pass = o.error() && !(gate.id().equals("SEC-1") && String.valueOf(o.message()).contains("fonts"));
                } else {
                    pass = !o.error() && Engines.same(gate.expected(), o.value()) == null;
                }
                cells[k.ordinal()] = pass ? "PASS" : "FAIL";
                count[k.ordinal()][pass ? 0 : 1]++;
                detail.append(gate.id()).append(' ').append(k).append(' ').append(pass ? "PASS" : "FAIL").append(": ").append(o).append('\n');
            }
            md.append("| ").append(gate.id()).append(" | ").append(gate.criterion()).append(" | ").append(gate.mandatory() ? "yes" : "no (capability)")
                    .append(" | ").append(gate.source()).append(" | ").append(gate.expected() == null ? "null" : gate.expected())
                    .append(" | ").append(cells[0]).append(" | ").append(cells[1]).append(" |\n");
        }
        Path out = Path.of(System.getProperty("tck.out")).getParent().resolve("gates");
        Files.createDirectories(out);
        Files.writeString(out.resolve("gate-table.md"), md);
        Files.writeString(out.resolve("gate-detail.txt"), detail);
        System.out.println(md);
        System.out.println(detail);
        System.out.println("[gates] OPERATON pass/fail " + Arrays.toString(count[0]) + " DROOLS pass/fail " + Arrays.toString(count[1]));
    }
}
