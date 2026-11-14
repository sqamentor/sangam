// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.variable.Variables;

import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.LoggingAuditSink;

/**
 * Decision 223, round 4, over {@link MoneyCalculationDelegate}: the wage rules and the rounding policy are selected by
 * the wage period (fields {@code periodFrom} and {@code periodTo}), not the business date; decision 255: in a period
 * that straddles a rule change the row in force on the first day applies, unless the table's row in force on the last
 * day says {@code periodRuleBasis} {@code LAST_DAY}; decision 256: ESI continuation ({@code continuation}) comes from
 * the rules row's mode and contribution periods plus the register's coverage start, and the low-wage exemption
 * ({@code exemptWhen}, {@code exemptComparison}) compares as the rules row says (at or below in the demo); gross
 * wages are the exact sum of the register (op {@code TOTAL}). Every expected value is worked out by hand in minor
 * units (comments). The rows are test data, not statutory values.
 *
 * <p>ASSUMPTIONS, not statutory expected results (decision 288): the PF and ESI rates, ceilings, limits, continuation,
 * exemption and rounding rows here are test inputs (most mirror the packgen rows, which are UNVERIFIED and
 * SYNTHETIC_DEMO_ONLY); no expected amount states what any law requires.
 */
class MoneyWagePeriodTest {

    private static final String TENANT = "t-period";
    private static final ObjectMapper JSON = new ObjectMapper();
    static ProcessEngine engine;
    static int seq;
    static int ids;

    private static final String NS = "xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\"";
    private static final String DMN = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d_";
    private static final String DATE_INPUT = "<input id=\"i0\"><inputExpression id=\"ie0\" typeRef=\"date\">"
            + "<text>date(asOfDate)</text></inputExpression></input>";

    private static String calc(String id, String... fields) {
        StringBuilder f = new StringBuilder();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            f.append("<operaton:field name=\"").append(fields[i]).append("\"><operaton:string>").append(fields[i + 1])
                    .append("</operaton:string></operaton:field>");
        }
        return "<serviceTask id=\"" + id + "\" operaton:class=\"" + MoneyCalculationDelegate.class.getName()
                + "\"><extensionElements>" + f + "</extensionElements></serviceTask>";
    }

    private static String process(String id, String... tasks) {
        StringBuilder b = new StringBuilder("<definitions " + NS + "><process id=\"" + id
                + "\" isExecutable=\"true\"><startEvent id=\"s\"/>");
        for (String t : tasks) {
            b.append(t);
        }
        b.append("<userTask id=\"hold\"/><endEvent id=\"e\"/>");
        String prev = "s";
        int n = 0;
        for (String t : tasks) {
            String tid = t.substring(t.indexOf("id=\"") + 4, t.indexOf('"', t.indexOf("id=\"") + 4));
            b.append("<sequenceFlow id=\"f").append(++n).append("\" sourceRef=\"").append(prev)
                    .append("\" targetRef=\"").append(tid).append("\"/>");
            prev = tid;
        }
        b.append("<sequenceFlow id=\"f").append(++n).append("\" sourceRef=\"").append(prev)
                .append("\" targetRef=\"hold\"/>");
        b.append("<sequenceFlow id=\"f").append(++n).append("\" sourceRef=\"hold\" targetRef=\"e\"/>");
        return b.append("</process></definitions>").toString();
    }

    private static String range(String from, String to) {
        return "<inputEntry id=\"e" + (++ids) + "\"><text>[date(\"" + from + "\")..date(\"" + to
                + "\")]</text></inputEntry>";
    }

    private static String out(String... values) {
        StringBuilder b = new StringBuilder();
        for (String v : values) {
            b.append("<outputEntry id=\"e").append(++ids).append("\"><text>").append(v).append("</text></outputEntry>");
        }
        return b.toString();
    }

    /** A rounding policy whose mode changes on 29 June 2026: DOWN before, HALF_UP from then. */
    private static String switchingPolicy(String key) {
        String cols = "policyId,policyVersion,evidenceStatus,permittedUse,mode,quantumMinor,negatives,source,provision,effectiveFrom";
        StringBuilder b = new StringBuilder(DMN + key.replace('-', '_') + "\" name=\"p\" namespace=\"t\">"
                + "<decision id=\""
                + key + "\" name=\"p\"><decisionTable id=\"t_" + key.replace('-', '_') + "\" hitPolicy=\"UNIQUE\">"
                + DATE_INPUT);
        for (String c : cols.split(",")) {
            b.append("<output id=\"o").append(++ids).append("\" name=\"").append(c).append("\" typeRef=\"")
                    .append(c.equals("quantumMinor") ? "number" : "string").append("\"/>");
        }
        b.append("<rule id=\"p0\">").append(range("1900-01-01", "2026-06-28"))
                .append(out("\"" + key + "\"", "\"v-old\"", "\"UNVERIFIED\"", "\"SYNTHETIC_DEMO_ONLY\"", "\"DOWN\"", "100", "\"REFUSE\"",
                        "\"none found\"", "\"none\"", "\"1900-01-01\""))
                .append("</rule><rule id=\"p1\">").append(range("2026-06-29", "9999-12-31"))
                .append(out("\"" + key + "\"", "\"v-new\"", "\"VERIFIED\"", "\"PRODUCTION\"", "\"HALF_UP\"", "100", "\"REFUSE\"",
                        CITED, "\"para 1\"", "\"2026-06-29\""))
                .append("</rule>");
        return b.append("</decisionTable></decision></definitions>").toString();
    }

    /** Decision 280: the citation of every VERIFIED test row (a test value, not a statement of any law). */
    private static final String CITED = "\"test source\"";

    private static String openPolicy(String key, String mode) {
        return openPolicy(key, mode, "2000-01-01", "2000-01-01", CITED, "PRODUCTION");
    }

    /**
     * One VERIFIED row for {@code use} (decision 288) matched from {@code entryFrom}, recording {@code effectiveFrom}
     * and {@code source}.
     */
    private static String openPolicy(String key, String mode, String entryFrom, String effectiveFrom, String source,
                                     String use) {
        String cols = "policyId,policyVersion,evidenceStatus,permittedUse,mode,quantumMinor,negatives,source,provision,"
                + "effectiveFrom";
        StringBuilder b = new StringBuilder(DMN + key.replace('-', '_') + "\" name=\"p\" namespace=\"t\">"
                + "<decision id=\""
                + key + "\" name=\"p\"><decisionTable id=\"t_" + key.replace('-', '_') + "\" hitPolicy=\"UNIQUE\">"
                + DATE_INPUT);
        for (String c : cols.split(",")) {
            b.append("<output id=\"o").append(++ids).append("\" name=\"").append(c).append("\" typeRef=\"")
                    .append(c.equals("quantumMinor") ? "number" : "string").append("\"/>");
        }
        b.append("<rule id=\"p0\">").append(range(entryFrom, "9999-12-31"))
                .append(out("\"" + key + "\"", "\"v1\"", "\"VERIFIED\"", "\"" + use + "\"", "\"" + mode + "\"", "100",
                        "\"REFUSE\"", source, "\"para 1\"", "\"" + effectiveFrom + "\""))
                .append("</rule>");
        return b.append("</decisionTable></decision></definitions>").toString();
    }

    /**
     * Wage rules shaped like packgen payroll.py with two rows: r1 up to 16 September 2026 (ceiling 15,000.00, no
     * continuation, basis FIRST_DAY, at or below), r2 from 17 September 2026 (ceiling 25,000.00, continuation, basis
     * and comparison as given); limit 21,000.00, a daily low-wage limit of 176.00 and contribution periods starting
     * 1 April and 1 October in both.
     */
    private static String rules(String key, String r2Continuation) {
        return rules(key, r2Continuation, "FIRST_DAY", "AT_OR_BELOW");
    }

    private static String rules(String key, String r2Continuation, String r2Basis, String r2Comparison) {
        String[] cols = {"ruleId", "ruleVersion", "evidenceStatus", "permittedUse", "pfWageCeiling", "esiWageLimit", "esiContinuation",
                "esiLowWageDailyLimit", "esiLowWageComparison", "esiContributionPeriodStarts", "periodRuleBasis"};
        StringBuilder b = new StringBuilder(DMN + key.replace('-', '_') + "\" name=\"r\" namespace=\"t\">"
                + "<decision id=\""
                + key + "\" name=\"r\"><decisionTable id=\"t_" + key.replace('-', '_') + "\" hitPolicy=\"UNIQUE\">"
                + DATE_INPUT);
        for (String c : cols) {
            b.append("<output id=\"o").append(++ids).append("\" name=\"").append(c).append("\" typeRef=\"")
                    .append(c.startsWith("pf") || c.endsWith("Limit") ? "number" : "string").append("\"/>");
        }
        b.append("<rule id=\"r0\">").append(range("1900-01-01", "2026-09-16"))
                .append(out("\"" + key + "\"", "\"r1\"", "\"UNVERIFIED\"", "\"SYNTHETIC_DEMO_ONLY\"", "1500000", "2100000", "\"NONE\"",
                        "17600", "\"AT_OR_BELOW\"", "\"04-01,10-01\"", "\"FIRST_DAY\""))
                .append("</rule><rule id=\"r1\">").append(range("2026-09-17", "9999-12-31"))
                .append(out("\"" + key + "\"", "\"r2\"", "\"UNVERIFIED\"", "\"SYNTHETIC_DEMO_ONLY\"", "2500000", "2100000",
                        "\"" + r2Continuation + "\"", "17600", "\"" + r2Comparison + "\"", "\"04-01,10-01\"",
                        "\"" + r2Basis + "\""))
                .append("</rule>");
        return b.append("</decisionTable></decision></definitions>").toString();
    }

    private static final String[] PERIOD = {"periodFrom", "from", "periodTo", "to"};

    private static String[] with(String... fields) {
        String[] all = new String[fields.length + PERIOD.length];
        System.arraycopy(fields, 0, all, 0, fields.length);
        System.arraycopy(PERIOD, 0, all, fields.length, PERIOD.length);
        return all;
    }

    private static String payroll(String id, String rulesKey) {
        return process(id,
                calc("gross", "result", "grossWages", "op", "TOTAL", "items", "register", "formula", "grossWages"),
                calc("pfE", with("result", "pfEmployeeAmount", "op", "EACH", "items", "register", "itemResult",
                        "pfEmployee", "formula", "pfWages * pfEmployeePct / 100", "policy", "test-half-up",
                        "cap", "pfWages&lt;=pfWageCeiling", "rules", rulesKey)),
                calc("esiE", with("result", "esiEmployeeAmount", "op", "EACH", "items", "register", "itemResult",
                        "esiEmployee", "formula", "esiWages * esiEmployeePct / 100", "policy", "test-ceiling",
                        "eligibleWhen", "esiWages&lt;=esiWageLimit", "continuation", CONTINUATION,
                        "exemptWhen", "esiWages/esiDays&lt;=esiLowWageDailyLimit", "exemptComparison",
                        "esiLowWageComparison", "rules", rulesKey)),
                calc("esiR", with("result", "esiEmployerAmount", "op", "EACH", "items", "register", "itemResult",
                        "esiEmployer", "formula", "esiWages * esiEmployerPct / 100", "policy", "test-ceiling",
                        "eligibleWhen", "esiWages&lt;=esiWageLimit", "continuation", CONTINUATION,
                        "rules", rulesKey)));
    }

    private static final String CONTINUATION = "esiCoverageStart@esiContinuation@esiContributionPeriodStarts";

    /**
     * Decisions 270 to 272: one rules row in force on every day (limit 21,000.00, continuation, contribution periods
     * from 1 April and 1 October, joiner covered to the period end, daily low-wage limit 176.00 at or below, the
     * average daily wage definition {@code adw} marked UNVERIFIED) and the ESI employee and employer shares with the
     * joiner, the register's esiContinuationEndDate and (employee share only) the exemption by wage basis.
     */
    private static String rules270(String key, String adw) {
        String[] cols = {"ruleId", "ruleVersion", "evidenceStatus", "permittedUse", "esiWageLimit", "esiContinuation",
                "esiContributionPeriodStarts", "esiMidPeriodJoiner", "esiLowWageDailyLimit", "esiLowWageComparison",
                "esiAverageDailyWage", "esiAverageDailyWageStatus"};
        StringBuilder b = new StringBuilder(DMN + key.replace('-', '_') + "\" name=\"r\" namespace=\"t\">"
                + "<decision id=\"" + key + "\" name=\"r\"><decisionTable id=\"t_" + key.replace('-', '_')
                + "\" hitPolicy=\"UNIQUE\">" + DATE_INPUT);
        for (String c : cols) {
            b.append("<output id=\"o").append(++ids).append("\" name=\"").append(c).append("\" typeRef=\"")
                    .append(c.endsWith("Limit") ? "number" : "string").append("\"/>");
        }
        b.append("<rule id=\"r0\">").append(range("1900-01-01", "9999-12-31"))
                .append(out("\"" + key + "\"", "\"r270\"", "\"UNVERIFIED\"", "\"SYNTHETIC_DEMO_ONLY\"", "2100000", "\"CONTRIBUTION_PERIOD\"",
                        "\"04-01,10-01\"", "\"COVERED_TO_PERIOD_END\"", "17600", "\"AT_OR_BELOW\"",
                        "\"" + adw + "\"", "\"UNVERIFIED\""))
                .append("</rule>");
        return b.append("</decisionTable></decision></definitions>").toString();
    }

    private static String payroll270(String id, String rulesKey) {
        String[] coverage = {"continuation", CONTINUATION, "joiner", "esiMidPeriodJoiner", "continuationEnd",
                "esiContinuationEndDate", "eligibleWhen", "esiWages&lt;=esiWageLimit", "policy", "test-ceiling",
                "op", "EACH", "items", "register", "rules", rulesKey};
        return process(id,
                calc("esiE", with(concat(coverage, "result", "esiEmployeeAmount", "itemResult", "esiEmployee",
                        "formula", "esiWages * esiEmployeePct / 100", "exemptWhen",
                        "esiFullPeriodWages/esiRating&lt;=esiLowWageDailyLimit", "exemptComparison",
                        "esiLowWageComparison",
                        "exemptBasis", "esiWageBasis@esiAverageDailyWage@esiAverageDailyWageStatus"))),
                calc("esiR", with(concat(coverage, "result", "esiEmployerAmount", "itemResult", "esiEmployer",
                        "formula", "esiWages * esiEmployerPct / 100"))));
    }

    private static String[] concat(String[] a, String... b) {
        String[] all = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-money-period;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        org.sangam.packs.PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel), as production
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString("payroll.bpmn", payroll("period-payroll", "test-rules"))
                .addString("payroll-none.bpmn", payroll("period-payroll-none", "test-rules-none"))
                .addString("payroll-last.bpmn", payroll("period-payroll-last", "test-rules-last"))
                .addString("payroll-below.bpmn", payroll("period-payroll-below", "test-rules-below"))
                .addString("payroll-bad-basis.bpmn", payroll("period-payroll-bad-basis", "test-rules-bad-basis"))
                .addString("payroll-bad-cmp.bpmn", payroll("period-payroll-bad-cmp", "test-rules-bad-cmp"))
                .addString("rules-last.dmn", rules("test-rules-last", "CONTRIBUTION_PERIOD", "LAST_DAY",
                        "AT_OR_BELOW"))
                .addString("rules-below.dmn", rules("test-rules-below", "CONTRIBUTION_PERIOD", "FIRST_DAY", "BELOW"))
                .addString("rules-bad-basis.dmn", rules("test-rules-bad-basis", "CONTRIBUTION_PERIOD", "MIDDLE",
                        "AT_OR_BELOW"))
                .addString("rules-bad-cmp.dmn", rules("test-rules-bad-cmp", "CONTRIBUTION_PERIOD", "FIRST_DAY",
                        "UNDER"))
                .addString("policy.bpmn", process("period-policy", calc("one", with("result", "share", "formula",
                        "x * 12 / 100", "policy", "test-switch"))))
                .addString("half-up.dmn", openPolicy("test-half-up", "HALF_UP"))
                .addString("ceiling.dmn", openPolicy("test-ceiling", "CEILING"))
                .addString("switch.dmn", switchingPolicy("test-switch"))
                .addString("rules.dmn", rules("test-rules", "CONTRIBUTION_PERIOD"))
                .addString("rules-none.dmn", rules("test-rules-none", "NONE"))
                .addString("payroll-270.bpmn", payroll270("period-payroll-270", "test-rules-270"))
                .addString("rules-270.dmn", rules270("test-rules-270", "TIME_RATED=FULL_PERIOD_WAGE_BY_RATING"))
                // decision 295: the round 7 method (wages per paid day) is not the rule and is refused
                .addString("payroll-270-paid-day.bpmn", payroll270("period-payroll-270-paid-day",
                        "test-rules-270-paid-day"))
                .addString("rules-270-paid-day.dmn", rules270("test-rules-270-paid-day",
                        "TIME_RATED=WAGES_PER_PAID_DAY"))
                .addString("payroll-270-undefined.bpmn", payroll270("period-payroll-270-undefined",
                        "test-rules-270-undefined"))
                .addString("rules-270-undefined.dmn", rules270("test-rules-270-undefined", ""))
                // decision 280: a VERIFIED row matched from 1 June 2026 that records 29 June 2026 as its effective date,
                // one without a citation and one without an explicit effective date
                .addString("policy-late.bpmn", process("period-policy-late", calc("one", with("result", "share",
                        "formula", "x * 12 / 100", "policy", "test-late"))))
                .addString("late.dmn", openPolicy("test-late", "HALF_UP", "2026-06-01", "2026-06-29", CITED,
                        "PRODUCTION"))
                .addString("policy-uncited.bpmn", process("period-policy-uncited", calc("one", with("result", "share",
                        "formula", "x * 12 / 100", "policy", "test-uncited"))))
                .addString("uncited.dmn", openPolicy("test-uncited", "HALF_UP", "2000-01-01", "2000-01-01",
                        "\"none found (demo)\"", "PRODUCTION"))
                .addString("policy-undated.bpmn", process("period-policy-undated", calc("one", with("result", "share",
                        "formula", "x * 12 / 100", "policy", "test-undated"))))
                .addString("undated.dmn", openPolicy("test-undated", "HALF_UP", "1900-01-01", "1900-01-01", CITED,
                        "PRODUCTION"))
                // decision 288: the same uncited, undated row as SYNTHETIC_DEMO_ONLY is not a production rule
                .addString("policy-demo-only.bpmn", process("period-policy-demo-only", calc("one", with("result",
                        "share", "formula", "x * 12 / 100", "policy", "test-demo-only"))))
                .addString("demo-only.dmn", openPolicy("test-demo-only", "HALF_UP", "1900-01-01", "1900-01-01",
                        "\"none found (demo)\"", "SYNTHETIC_DEMO_ONLY"))
                .deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    /**
     * A: ordinary; B: ESI wages above the limit, coverage since 1 April 2026, so covered when every contribution
     * period from then began; C: low wage, 26 paid days; D: ESI wages above the limit, coverage only from 15 October
     * 2026 (after the October contribution period began), so not continuing.
     */
    static final String FOUR = "["
            + "{\"employeeId\":\"A\",\"grossWages\":1400050,\"pfWages\":1250050,\"esiWages\":1250050,\"esiDays\":31,"
            + "\"esiCoverageStart\":\"2025-04-01\"},"
            + "{\"employeeId\":\"B\",\"grossWages\":3200000,\"pfWages\":3000000,\"esiWages\":2500000,\"esiDays\":31,"
            + "\"esiCoverageStart\":\"2026-04-01\"},"
            + "{\"employeeId\":\"C\",\"grossWages\":500000,\"pfWages\":450000,\"esiWages\":450000,\"esiDays\":26,"
            + "\"esiCoverageStart\":\"2026-06-01\"},"
            + "{\"employeeId\":\"D\",\"grossWages\":2600000,\"pfWages\":2200000,\"esiWages\":2200000,\"esiDays\":31,"
            + "\"esiCoverageStart\":\"2026-10-15\"}]";

    private static Map<String, Object> vars(String register, String from, String to) {
        Map<String, Object> v = new HashMap<>();
        if (register != null) {
            v.put("register", Variables.byteArrayValue(register.getBytes(StandardCharsets.UTF_8)));
        }
        v.put("pfEmployeePct", new BigDecimal("12"));
        v.put("esiEmployeePct", new BigDecimal("0.75"));
        v.put("esiEmployerPct", new BigDecimal("3.25"));
        v.put("x", 125450L);
        if (from != null) {
            v.put("from", from);
        }
        if (to != null) {
            v.put("to", to);
        }
        return v;
    }

    private static ProcessInstance start(String key, Map<String, Object> v) {
        return engine.getRuntimeService().createProcessInstanceByKey(key).businessKey("PERIOD-" + (++seq))
                .processDefinitionTenantId(TENANT).setVariables(v).execute();
    }

    private static final java.util.regex.Pattern CODE =
            java.util.regex.Pattern.compile("(REGISTER|ITEM|RULES|PERIOD|CALCULATION|WAGE|CONTINUATION|POLICY)_[A-Z_]+"
                    + "(?=:)");

    private static String refusal(String key, Map<String, Object> v) {
        String m = assertThrows(ProcessEngineException.class, () -> start(key, v)).getMessage();
        java.util.regex.Matcher c = CODE.matcher(m);
        assertTrue(c.find(), "no refusal code in: " + m);
        return c.group();
    }

    private static List<Object> trail(ProcessInstance pi, String result) {
        String bk = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pi.getId())
                .singleResult().getBusinessKey();
        List<PackAuditEvent> audit = ((LoggingAuditSink) PackRuntime.audit()).timeline(bk);
        PackAuditEvent e = audit.stream().filter(a -> result.equals(a.details().get("result"))).findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Object> values = (List<Object>) e.details().get("itemValues");
        return values;
    }

    private static Map<String, Map<String, Object>> register(Map<String, Object> out) throws Exception {
        List<Map<String, Object>> items = JSON.readValue(new String((byte[]) out.get("register"),
                StandardCharsets.UTF_8), new TypeReference<>() { });
        Map<String, Map<String, Object>> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.get("employeeId").toString(), i));
        return byId;
    }

    @Test
    void theRulesOfTheWagePeriodApplyWithContinuationAndTheLowWageExemption() throws Exception {
        ProcessInstance pi = start("period-payroll", vars(FOUR, "2026-10-01", "2026-10-31"));
        Map<String, Object> out = engine.getRuntimeService().getVariables(pi.getId());
        // gross: 1400050 + 3200000 + 500000 + 2600000, exact, from the register
        assertEquals(7700050L, out.get("grossWages"));
        // row r2 (ceiling 2500000). PF 12 % HALF_UP to 100: A 150006 -> 150000; B capped 2500000 -> 300000;
        // C 54000; D 264000
        assertEquals(768000L, out.get("pfEmployeeAmount"));
        assertEquals("test-rules@r2", out.get("pfEmployeeAmountRules"));
        // ESI employee 0.75 % CEILING to 100: A 9375.375 -> 9400 (40324.19 a day, not exempt); B above the limit but
        // continuing: 18750 -> 18800; C 450000 / 26 = 17307.69 a day <= 17600: exempt, 0; D above the limit: 0
        assertEquals(28200L, out.get("esiEmployeeAmount"));
        // ESI employer 3.25 % CEILING: A 40626.625 -> 40700; B 81250 -> 81300; C 14625 -> 14700 (the exemption is
        // the employee share only); D 0
        assertEquals(136700L, out.get("esiEmployerAmount"));
        assertEquals(List.of("A:9400", "B:18800:CONTINUED", "C:0:EXEMPT", "D:0:INELIGIBLE"),
                trail(pi, "esiEmployeeAmount"));
        assertEquals(List.of("A:40700", "B:81300:CONTINUED", "C:14700", "D:0:INELIGIBLE"),
                trail(pi, "esiEmployerAmount"));
        assertEquals(List.of("A:1400050", "B:3200000", "C:500000", "D:2600000"), trail(pi, "grossWages"));
        Map<String, Map<String, Object>> byId = register(out);
        assertEquals(Boolean.TRUE, byId.get("B").get("esiEmployeeContinued"));
        assertEquals(Boolean.TRUE, byId.get("B").get("esiEmployeeEligible"));
        assertEquals(Boolean.TRUE, byId.get("C").get("esiEmployeeExempt"));
        assertEquals(0, ((Number) byId.get("C").get("esiEmployee")).intValue());
        assertEquals(14700, ((Number) byId.get("C").get("esiEmployer")).intValue());
        assertEquals(Boolean.FALSE, byId.get("D").get("esiEmployeeEligible"));
    }

    @Test
    void anEarlierWagePeriodTakesItsOwnRowNotTheBusinessDate() {
        // the business date is on or after 17 September 2026 (row r2); the August 2026 wage period is row r1:
        // ceiling 1500000 and no continuation
        ProcessInstance pi = start("period-payroll", vars(FOUR, "2026-08-01", "2026-08-31"));
        Map<String, Object> out = engine.getRuntimeService().getVariables(pi.getId());
        assertEquals("test-rules@r1", out.get("pfEmployeeAmountRules"));
        // PF: A 150000; B capped 1500000 -> 180000; C 54000; D capped 1500000 -> 180000
        assertEquals(564000L, out.get("pfEmployeeAmount"));
        // ESI employee: A 9400; B not continued under r1 (NONE) although covered since 1 April 2026: 0; C exempt;
        // D 0
        assertEquals(9400L, out.get("esiEmployeeAmount"));
        assertEquals(List.of("A:9400", "B:0:INELIGIBLE", "C:0:EXEMPT", "D:0:INELIGIBLE"),
                trail(pi, "esiEmployeeAmount"));
    }

    @Test
    void continuationSwitchedOffByTheRulesRowLeavesAnEmployeeAboveTheLimitOut() {
        ProcessInstance pi = start("period-payroll-none", vars(FOUR, "2026-10-01", "2026-10-31"));
        // r2 of test-rules-none says NONE: B is not eligible; A 9400 only
        assertEquals(9400L, engine.getRuntimeService().getVariable(pi.getId(), "esiEmployeeAmount"));
    }

    @Test
    void thePolicyIsSelectedByTheWagePeriodToo() {
        // 125450 x 12 % = 15054: DOWN to 100 (v-old, before 29 June 2026) = 15000; HALF_UP (v-new) = 15100
        ProcessInstance may = start("period-policy", vars(null, "2026-05-01", "2026-05-31"));
        assertEquals(15000L, engine.getRuntimeService().getVariable(may.getId(), "share"));
        assertEquals("test-switch@v-old", engine.getRuntimeService().getVariable(may.getId(), "sharePolicy"));
        ProcessInstance oct = start("period-policy", vars(null, "2026-10-01", "2026-10-31"));
        assertEquals(15100L, engine.getRuntimeService().getVariable(oct.getId(), "share"));
        assertEquals("test-switch@v-new", engine.getRuntimeService().getVariable(oct.getId(), "sharePolicy"));
    }

    /**
     * Decision 255 replaces the round 4 refusal (PERIOD_STRADDLES_RULE_CHANGE, its test removed with it): the row in
     * force on the wage period's first day applies.
     */
    @Test
    void aWagePeriodThatStraddlesARuleChangeTakesTheRowOfItsFirstDay() {
        // September 2026: r1 on the 1st, r2 from the 17th; r2 says FIRST_DAY, so r1 for the whole period:
        // PF A 150000; B capped 1500000 -> 180000; C 54000; D capped 1500000 -> 180000
        ProcessInstance sep = start("period-payroll", vars(FOUR, "2026-09-01", "2026-09-30"));
        Map<String, Object> out = engine.getRuntimeService().getVariables(sep.getId());
        assertEquals("test-rules@r1", out.get("pfEmployeeAmountRules"));
        assertEquals(564000L, out.get("pfEmployeeAmount"));
        // ESI under r1 (NONE): B is not continued: A 9400 only
        assertEquals(9400L, out.get("esiEmployeeAmount"));
        String bk = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(sep.getId())
                .singleResult().getBusinessKey();
        PackAuditEvent e = ((LoggingAuditSink) PackRuntime.audit()).timeline(bk).stream()
                .filter(a -> "pfEmployeeAmount".equals(a.details().get("result"))).findFirst().orElseThrow();
        assertEquals("2026-09-01 FIRST_DAY RULE_CHANGED_IN_PERIOD",
                ((Map<?, ?>) e.details().get("rowsAsOf")).get("test-rules"));
        // June 2026 policy: v-old on the 1st (DOWN), v-new from the 29th: 15054 DOWN to 100 = 15000
        ProcessInstance june = start("period-policy", vars(null, "2026-06-01", "2026-06-30"));
        assertEquals(15000L, engine.getRuntimeService().getVariable(june.getId(), "share"));
        assertEquals("test-switch@v-old", engine.getRuntimeService().getVariable(june.getId(), "sharePolicy"));
    }

    @Test
    void aTableWhoseNewRowSaysLastDayAppliesItToTheWholeWagePeriod() {
        // test-rules-last r2 (from 17 September 2026) says LAST_DAY: September 2026 takes r2, ceiling 2500000:
        // PF A 150000; B capped 2500000 -> 300000; C 54000; D 264000
        ProcessInstance pi = start("period-payroll-last", vars(FOUR, "2026-09-01", "2026-09-30"));
        Map<String, Object> out = engine.getRuntimeService().getVariables(pi.getId());
        assertEquals("test-rules-last@r2", out.get("pfEmployeeAmountRules"));
        assertEquals(768000L, out.get("pfEmployeeAmount"));
        // ESI under r2: the contribution period of 1 September 2026 began on 1 April 2026; B covered since then
        // continues (18750 -> 18800); D's coverage starts 15 October 2026: not continued
        assertEquals(List.of("A:9400", "B:18800:CONTINUED", "C:0:EXEMPT", "D:0:INELIGIBLE"),
                trail(pi, "esiEmployeeAmount"));
        // a period inside one row is the same row on both days, LAST_DAY or not
        ProcessInstance oct = start("period-payroll-last", vars(FOUR, "2026-10-01", "2026-10-31"));
        assertEquals("test-rules-last@r2", engine.getRuntimeService().getVariable(oct.getId(),
                "pfEmployeeAmountRules"));
    }

    @Test
    void aPeriodRuleBasisOtherThanFirstOrLastDayIsRefused() {
        assertEquals("RULES_INVALID", refusal("period-payroll-bad-basis", vars(FOUR, "2026-10-01", "2026-10-31")));
    }

    @Test
    void theLowWageExemptionComparesAsTheRulesRowSays() {
        // E: 545600 over 31 days = 17600.00 a day, exactly the limit
        String atLimit = "[{\"employeeId\":\"E\",\"grossWages\":545600,\"pfWages\":545600,\"esiWages\":545600,"
                + "\"esiDays\":31,\"esiCoverageStart\":\"2026-04-01\"}]";
        // AT_OR_BELOW (decision 256): the limit itself is exempt, employee share 0
        ProcessInstance at = start("period-payroll", vars(atLimit, "2026-10-01", "2026-10-31"));
        assertEquals(0L, engine.getRuntimeService().getVariable(at.getId(), "esiEmployeeAmount"));
        assertEquals(List.of("E:0:EXEMPT"), trail(at, "esiEmployeeAmount"));
        // BELOW: not exempt at the limit: 545600 x 0.75 % = 4092 -> CEILING to 100 = 4100
        ProcessInstance below = start("period-payroll-below", vars(atLimit, "2026-10-01", "2026-10-31"));
        assertEquals(4100L, engine.getRuntimeService().getVariable(below.getId(), "esiEmployeeAmount"));
        assertEquals("RULES_INVALID", refusal("period-payroll-bad-cmp", vars(atLimit, "2026-10-01", "2026-10-31")));
    }

    @Test
    void continuationFollowsTheContributionPeriodOfTheRulesAndTheCoverageStart() {
        // B and D above the limit (2500000 > 2100000). October 2026: contribution period from 1 October 2026.
        String two = "[{\"employeeId\":\"B\",\"grossWages\":1,\"pfWages\":1,\"esiWages\":2500000,\"esiDays\":31,"
                + "\"esiCoverageStart\":\"2026-10-01\"},"
                + "{\"employeeId\":\"N\",\"grossWages\":1,\"pfWages\":1,\"esiWages\":2500000,\"esiDays\":31,"
                + "\"esiCoverageStart\":\"NONE\"}]";
        // B's coverage started on the first day of the contribution period: continued; N is not covered
        ProcessInstance oct = start("period-payroll", vars(two, "2026-10-01", "2026-10-31"));
        assertEquals(List.of("B:18800:CONTINUED", "N:0:INELIGIBLE"), trail(oct, "esiEmployeeAmount"));
        // February 2027 is still in the contribution period from 1 October 2026
        ProcessInstance feb = start("period-payroll", vars(two, "2027-02-01", "2027-02-28"));
        assertEquals(List.of("B:18800:CONTINUED", "N:0:INELIGIBLE"), trail(feb, "esiEmployeeAmount"));
        Map<String, Object> row = Map.of("starts", "04-01,10-01");
        assertEquals(java.time.LocalDate.of(2026, 10, 1), MoneyCalculationDelegate.contributionPeriodStart(row,
                "starts", "r", java.time.LocalDate.of(2027, 3, 31)));
        assertEquals(java.time.LocalDate.of(2027, 4, 1), MoneyCalculationDelegate.contributionPeriodStart(row,
                "starts", "r", java.time.LocalDate.of(2027, 4, 1)));
        assertEquals(java.time.LocalDate.of(2026, 4, 1), MoneyCalculationDelegate.contributionPeriodStart(row,
                "starts", "r", java.time.LocalDate.of(2026, 9, 30)));
        for (String bad : List.of("", "4-1", "04-01,04-01", "02-29", "13-01", "04-31", "04-01;10-01")) {
            assertTrue(assertThrows(ProcessEngineException.class,
                    () -> MoneyCalculationDelegate.contributionPeriodStart(Map.of("starts", bad), "starts", "r",
                            java.time.LocalDate.of(2026, 10, 1))).getMessage().startsWith("RULES_INVALID"), bad);
        }
    }

    @Test
    void aMissingOrInvalidWagePeriodIsRefusedNeverTheBusinessDate() {
        assertEquals("PERIOD_MISSING", refusal("period-policy", vars(null, null, "2026-10-31")));
        assertEquals("PERIOD_MISSING", refusal("period-policy", vars(null, "2026-10-01", null)));
        assertEquals("PERIOD_INVALID", refusal("period-policy", vars(null, "2026-02-30", "2026-03-31")));
        assertEquals("PERIOD_INVALID", refusal("period-policy", vars(null, "2026-10-31", "2026-10-01")));
    }

    @Test
    void theRegisterMustStateCoverageStartAndPositiveWholePaidDays() {
        String noStart = "[{\"employeeId\":\"A\",\"grossWages\":1,\"pfWages\":1250050,\"esiWages\":1250050,"
                + "\"esiDays\":31}]";
        assertEquals("ITEM_INVALID", refusal("period-payroll", vars(noStart, "2026-10-01", "2026-10-31")));
        String badStart = "[{\"employeeId\":\"A\",\"grossWages\":1,\"pfWages\":1250050,\"esiWages\":1250050,"
                + "\"esiDays\":31,\"esiCoverageStart\":\"true\"}]";
        assertEquals("ITEM_INVALID", refusal("period-payroll", vars(badStart, "2026-10-01", "2026-10-31")));
        String noDays = "[{\"employeeId\":\"A\",\"grossWages\":1,\"pfWages\":1250050,\"esiWages\":1250050,"
                + "\"esiDays\":0,\"esiCoverageStart\":\"2026-04-01\"}]";
        assertEquals("ITEM_INVALID", refusal("period-payroll", vars(noDays, "2026-10-01", "2026-10-31")));
    }

    @Test
    void grossWagesAreWholeMinorUnitsOfANonEmptyRegister() {
        assertEquals("REGISTER_EMPTY", refusal("period-payroll", vars("[]", "2026-10-01", "2026-10-31")));
        assertEquals("ITEM_INVALID", refusal("period-payroll", vars("[{\"employeeId\":\"A\",\"grossWages\":1.5}]",
                "2026-10-01", "2026-10-31")));
        assertEquals("ITEM_INVALID", refusal("period-payroll", vars("[{\"employeeId\":\"A\"}]",
                "2026-10-01", "2026-10-31")));
        assertEquals("ITEM_DUPLICATE", refusal("period-payroll", vars("[{\"employeeId\":\"A\",\"grossWages\":1},"
                + "{\"employeeId\":\"A\",\"grossWages\":2}]", "2026-10-01", "2026-10-31")));
    }

    @Test
    void theExemptionAndContinuationFieldsParse() {
        MoneyCalculationDelegate.Bound b = MoneyCalculationDelegate.Bound.parseRatio("exemptWhen",
                " esiWages / esiDays <= esiLowWageDailyLimit ");
        assertEquals(new MoneyCalculationDelegate.Bound("esiWages", "esiDays", "esiLowWageDailyLimit"), b);
        assertEquals("esiWages/esiDays<=esiLowWageDailyLimit", b.text());
        assertArrayEquals(new String[] {"esiCoverageStart", "esiContinuation", "esiContributionPeriodStarts"},
                MoneyCalculationDelegate.continuation(" esiCoverageStart @ esiContinuation @ esiContributionPeriodStarts "));
        assertNull(MoneyCalculationDelegate.continuation(" "));
        for (String bad : List.of("a/b/c<=d", "a/<=d", "a/b<d")) {
            assertTrue(assertThrows(ProcessEngineException.class,
                    () -> MoneyCalculationDelegate.Bound.parseRatio("exemptWhen", bad)).getMessage()
                    .startsWith("CALCULATION_INVALID"), bad);
        }
        assertTrue(assertThrows(ProcessEngineException.class,
                () -> MoneyCalculationDelegate.Bound.parse("cap", "a/b<=c")).getMessage()
                .startsWith("CALCULATION_INVALID"), "a ratio is not a cap");
        // decision 256: continuation names three parts (the round 4 two-part form a@b is now refused)
        for (String bad : List.of("a", "a@", "@b", "a@b", "a@b@", "@b@c", "a@b@c@d")) {
            assertTrue(assertThrows(ProcessEngineException.class,
                    () -> MoneyCalculationDelegate.continuation(bad)).getMessage().startsWith("CALCULATION_INVALID"),
                    bad);
        }
    }

    /**
     * A register record of the decision 270 to 272 tests (paid days, wage basis, coverage start, continuation end).
     * Decision 295: monthly rated, with the full-period wage equal to the ESI wages of the period (a test assumption:
     * no leave was taken).
     */
    private static String emp(String id, long esiWages, int paidDays, String basis, String start, String end) {
        return rated(id, esiWages, esiWages, "MONTHLY", paidDays, basis, start, end);
    }

    /** Decision 295: a register record with its own full-period wage and rating (rule 2(1)(h)(i)). */
    private static String rated(String id, long esiWages, Long fullPeriodWages, String rating, int paidDays,
                                String basis, String start, String end) {
        return "{\"employeeId\":\"" + id + "\",\"esiWages\":" + esiWages + ",\"esiDays\":" + paidDays
                + (fullPeriodWages == null ? "" : ",\"esiFullPeriodWages\":" + fullPeriodWages)
                + (rating == null ? "" : ",\"esiRating\":\"" + rating + "\"")
                + (basis == null ? "" : ",\"esiWageBasis\":\"" + basis + "\"") + ",\"esiCoverageStart\":\"" + start
                + "\"" + (end == null ? "" : ",\"esiContinuationEndDate\":\"" + end + "\"") + "}";
    }

    private static String reg(String... records) {
        return "[" + String.join(",", records) + "]";
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rounding(ProcessInstance pi, String result) {
        String bk = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pi.getId())
                .singleResult().getBusinessKey();
        return (List<Object>) ((LoggingAuditSink) PackRuntime.audit()).timeline(bk).stream()
                .filter(a -> result.equals(a.details().get("result"))).findFirst().orElseThrow().details()
                .get("itemRounding");
    }

    /**
     * Decision 271 (4) replaces the round 6 test theDelegatePaysOnlyTheCoveredDaysOfASplitWagePeriod (parts
     * 16-30 September and 1-15 October 2026, wages shared by days, :PARTIAL): a wage period crossing a
     * contribution-period boundary is blocked from final calculation before any item, for every share, whether or
     * not the rules row continues coverage.
     */
    @Test
    void aWagePeriodCrossingAContributionPeriodBoundaryIsBlocked() {
        String one = reg(emp("P", 2500000, 30, "TIME_RATED", "2026-07-01", null));
        assertEquals("WAGE_PERIOD_CROSSES_CONTRIBUTION_PERIOD_UNVERIFIED",
                refusal("period-payroll-270", vars(one, "2026-09-16", "2026-10-15")));
        assertEquals("WAGE_PERIOD_CROSSES_CONTRIBUTION_PERIOD_UNVERIFIED",
                refusal("period-payroll-270", vars(one, "2027-03-31", "2027-04-01")));
        // test-rules row r1 (in force on 16 September 2026) has continuation NONE but states the periods: blocked too
        assertEquals("WAGE_PERIOD_CROSSES_CONTRIBUTION_PERIOD_UNVERIFIED",
                refusal("period-payroll", vars(FOUR, "2026-09-16", "2026-10-15")));
    }

    /**
     * Decision 270 through the delegate, October 2026 (contribution period 1 October 2026 to 31 March 2027).
     * B: above the limit (2,500,000), covered since 1 April 2026, continuation end 31 March 2027: continued;
     * employee 0.75 % = 18,750 -> CEILING 18,800, employer 3.25 % = 81,250 -> 81,300. Q: above the limit, its
     * continuation ended 30 September 2026: ineligible for both shares. L: below the limit (2,000,000) with a stale
     * continuation end of 30 September 2025: covered by the normal rule; 2,000,000 / 31 paid days = 64,516.13 a day,
     * not exempt; employee 15,000, employer 65,000.
     */
    @Test
    void theContinuationEndDateEndsOnlyAContinuationAndNeverShortensIt() {
        String three = reg(emp("B", 2500000, 31, "TIME_RATED", "2026-04-01", "2027-03-31"),
                emp("Q", 2500000, 31, "TIME_RATED", "2026-04-01", "2026-09-30"),
                emp("L", 2000000, 31, "TIME_RATED", "2025-04-01", "2025-09-30"));
        ProcessInstance pi = start("period-payroll-270", vars(three, "2026-10-01", "2026-10-31"));
        Map<String, Object> out = engine.getRuntimeService().getVariables(pi.getId());
        assertEquals(List.of("B:18800:CONTINUED", "Q:0:INELIGIBLE", "L:15000"), trail(pi, "esiEmployeeAmount"));
        assertEquals(List.of("B:81300:CONTINUED", "Q:0:INELIGIBLE", "L:65000"), trail(pi, "esiEmployerAmount"));
        assertEquals(33800L, out.get("esiEmployeeAmount"));
        assertEquals(146300L, out.get("esiEmployerAmount"));
        // the same employee B in January 2027 (a mid-period crossing month) is still continued
        ProcessInstance jan = start("period-payroll-270", vars(three, "2027-01-01", "2027-01-31"));
        assertEquals(List.of("B:18800:CONTINUED", "Q:0:INELIGIBLE", "L:15000"), trail(jan, "esiEmployeeAmount"));
        // April 2027: B's continuation ended on 31 March 2027; a later wage change below the limit is covered again
        String april = reg(emp("B", 2500000, 30, "TIME_RATED", "2026-04-01", "2027-03-31"),
                emp("W", 2000000, 30, "TIME_RATED", "2026-04-01", "2027-03-31"));
        ProcessInstance apr = start("period-payroll-270", vars(april, "2027-04-01", "2027-04-30"));
        assertEquals(List.of("B:0:INELIGIBLE", "W:15000"), trail(apr, "esiEmployeeAmount"));
        // a date that would shorten the mandatory continuation (inside 1 October 2026 to 30 March 2027) is refused
        String shorter = reg(emp("S", 2500000, 31, "TIME_RATED", "2026-04-01", "2026-12-31"));
        assertEquals("CONTINUATION_END_SHORTENS", refusal("period-payroll-270", vars(shorter, "2026-10-01",
                "2026-10-31")));
        String odd = reg(emp("S", 2500000, 31, "TIME_RATED", "2026-04-01", "2027-04-15"));
        assertEquals("CONTINUATION_END_NOT_PERIOD_END", refusal("period-payroll-270", vars(odd, "2026-10-01",
                "2026-10-31")));
        // the same shortening date on an employee below the limit is not read
        String below = reg(emp("S", 2000000, 31, "TIME_RATED", "2026-04-01", "2026-12-31"));
        ProcessInstance ok = start("period-payroll-270", vars(below, "2026-10-01", "2026-10-31"));
        assertEquals(List.of("S:15000"), trail(ok, "esiEmployeeAmount"));
        // an above-limit joiner whose coverage starts inside the wage period: blocked, never prorated
        String joiner = reg(emp("J", 2500000, 17, "TIME_RATED", "2026-10-15", null));
        assertEquals("CONTINUATION_STARTS_INSIDE_WAGE_PERIOD_UNVERIFIED", refusal("period-payroll-270",
                vars(joiner, "2026-10-01", "2026-10-31")));
    }

    /**
     * Decision 295 replaces the decision 271 expectations of wages per paid day (C 450,000 / 26 paid days, E 545,600 /
     * 31, U 360,000 / 20): Social Security (Central) Rules 2026 rule 2(1)(h)(i) for time-rated pay, the wage of the
     * complete wage period had every working day been worked, divided by 26 when monthly rated (evidence file section
     * 3.1). October 2026, limit 17,600 a day at or below, minor units. R: monthly rate 468,000 (Rs 4,680, the evidence
     * example), leave taken so the wages are 396,000 over 26 paid days: 468,000 / 26 = 18,000 (Rs 180) > 17,600, not
     * exempt, whatever the leave (wages per paid day, 15,230.77, would wrongly exempt it); employee 2,970 -> CEILING
     * 3,000, employer 12,870 -> 12,900. C: 450,000 / 26 = 17,307.69 <= 17,600: employee exempt, employer 14,625 ->
     * 14,700 stays. E: 457,600 / 26 = 17,600.00, exactly the limit: exempt (AT_OR_BELOW); employer 14,872 -> 14,900.
     */
    @Test
    void decision295TheLowWageExemptionUsesTheFullPeriodWageOverTheRatedDays() throws Exception {
        String three = reg(rated("R", 396000, 468000L, "MONTHLY", 26, "TIME_RATED", "2026-04-01", null),
                rated("C", 450000, 450000L, "MONTHLY", 26, "TIME_RATED", "2026-06-01", null),
                rated("E", 457600, 457600L, "MONTHLY", 31, "TIME_RATED", "2026-04-01", null));
        ProcessInstance pi = start("period-payroll-270", vars(three, "2026-10-01", "2026-10-31"));
        Map<String, Object> out = engine.getRuntimeService().getVariables(pi.getId());
        assertEquals(List.of("R:3000", "C:0:EXEMPT", "E:0:EXEMPT"), trail(pi, "esiEmployeeAmount"));
        assertEquals(List.of("R:12900", "C:14700", "E:14900"), trail(pi, "esiEmployerAmount"));
        Map<String, Map<String, Object>> byId = register(out);
        assertEquals(Boolean.TRUE, byId.get("C").get("esiEmployeeEligible"), "the exemption keeps coverage");
        assertEquals("18000", new BigDecimal(byId.get("R").get("esiEmployeeAverageDailyWage").toString())
                .stripTrailingZeros().toPlainString(), "Rs 4,680 a month is Rs 180 a day (rule 2(1)(h)(i))");
        assertTrue(byId.get("C").get("esiEmployeeAverageDailyWage").toString().startsWith("17307.6923"));
        assertEquals("17600", new BigDecimal(byId.get("E").get("esiEmployeeAverageDailyWage").toString())
                .stripTrailingZeros().toPlainString());
        // a wage basis the rules do not define (piece-rated, rule 2(1)(h)(ii), not coded) is refused, never assumed;
        // a missing basis, rating or full-period wage, and an unknown rating, are refused too
        assertEquals("RULES_AVERAGE_DAILY_WAGE_UNDEFINED", refusal("period-payroll-270",
                vars(reg(emp("K", 450000, 26, "PIECE_RATED", "2026-04-01", null)), "2026-10-01", "2026-10-31")));
        assertEquals("ITEM_INVALID", refusal("period-payroll-270",
                vars(reg(emp("K", 450000, 26, null, "2026-04-01", null)), "2026-10-01", "2026-10-31")));
        assertEquals("ITEM_INVALID", refusal("period-payroll-270", vars(reg(rated("K", 450000, 450000L, null, 26,
                "TIME_RATED", "2026-04-01", null)), "2026-10-01", "2026-10-31")));
        assertEquals("ITEM_INVALID", refusal("period-payroll-270", vars(reg(rated("K", 450000, 450000L, "HOURLY", 26,
                "TIME_RATED", "2026-04-01", null)), "2026-10-01", "2026-10-31")));
        assertEquals("ITEM_INVALID", refusal("period-payroll-270", vars(reg(rated("K", 450000, null, "MONTHLY", 26,
                "TIME_RATED", "2026-04-01", null)), "2026-10-01", "2026-10-31")));
        // a rules row without the definition is refused; the round 7 method (wages per paid day) is not the rule
        assertEquals("RULES_AVERAGE_DAILY_WAGE_UNDEFINED", refusal("period-payroll-270-undefined",
                vars(three, "2026-10-01", "2026-10-31")));
        assertEquals("RULES_INVALID", refusal("period-payroll-270-paid-day", vars(three, "2026-10-01", "2026-10-31")));
    }

    /**
     * Decision 295, the divisors of rule 2(1)(h)(i) on their own: the full-period wage divided by 26 (monthly), 13
     * (fortnightly), 6 (weekly) or 1 (daily); each example is Rs 180 a day. Which rating fits which wage period is not
     * decided here (round 10 report question).
     */
    @Test
    void decision295TheRatedDaysAre26And13And6And1() {
        Map<String, Object> row = Map.of("adw", "TIME_RATED=FULL_PERIOD_WAGE_BY_RATING", "adwStatus", "UNVERIFIED");
        AverageDailyWage d = AverageDailyWage.of("basis@adw@adwStatus", row, "r");
        for (Object[] c : new Object[][] {{"MONTHLY", 468000}, {"FORTNIGHTLY", 234000}, {"WEEKLY", 108000},
                {"DAILY", 18000}}) {
            Map<String, Object> item = Map.of("basis", "TIME_RATED", "full", c[1], "rating", c[0]);
            assertEquals("18000", d.perDay(item, "x", "full", "rating").stripTrailingZeros().toPlainString(),
                    c[0].toString());
        }
        for (String bad : List.of("PIECE_RATED=FULL_PERIOD_WAGE_BY_RATING", "TIME_RATED=WAGES_PER_PAID_DAY",
                "TIME_RATED=FULL_PERIOD_WAGE_BY_RATING,TIME_RATED=FULL_PERIOD_WAGE_BY_RATING")) {
            assertTrue(assertThrows(ProcessEngineException.class, () -> AverageDailyWage.of("basis@adw@adwStatus",
                    Map.of("adw", bad, "adwStatus", "UNVERIFIED"), "r")).getMessage().startsWith("RULES_INVALID"), bad);
        }
    }

    /**
     * Decision 272, October 2026, CEILING to 100 minor units, once per employee and share. F 1,000,040: employee
     * 7,500.3 -> 7,600 (difference 99.7), employer 32,501.3 -> 32,600 (98.7); rounded separately 40,200, while one
     * combined 4 % (40,001.6 -> 40,100) would differ. X 2,000,000 (exact rupees): 15,000 and 65,000, difference 0.
     * Z 0: employee exempt (0 a day), employer 0, difference 0. M 2,100,000, exactly the limit: eligible; 15,750 ->
     * 15,800 and 68,250 -> 68,300 (difference 50 each). N 2,100,001 not covered: 0.
     */
    @Test
    void eachShareIsRoundedOncePerEmployeeWithTheUnroundedAmountAndDifferenceInTheTrace() throws Exception {
        String five = reg(emp("F", 1000040, 31, "TIME_RATED", "2026-04-01", null),
                emp("X", 2000000, 31, "TIME_RATED", "2026-04-01", null),
                emp("Z", 0, 31, "TIME_RATED", "2026-04-01", null),
                emp("M", 2100000, 31, "TIME_RATED", "2026-04-01", null),
                emp("N", 2100001, 31, "TIME_RATED", "NONE", null));
        ProcessInstance pi = start("period-payroll-270", vars(five, "2026-10-01", "2026-10-31"));
        Map<String, Object> out = engine.getRuntimeService().getVariables(pi.getId());
        assertEquals(List.of("F:7600", "X:15000", "Z:0:EXEMPT", "M:15800", "N:0:INELIGIBLE"),
                trail(pi, "esiEmployeeAmount"));
        assertEquals(List.of("F:32600", "X:65000", "Z:0", "M:68300", "N:0:INELIGIBLE"),
                trail(pi, "esiEmployerAmount"));
        assertEquals(38400L, out.get("esiEmployeeAmount"));
        assertEquals(165900L, out.get("esiEmployerAmount"));
        assertEquals(List.of("F:7500.3:7600:99.7", "X:15000:15000:0", "Z:0:0:0", "M:15750:15800:50", "N:0:0:0"),
                rounding(pi, "esiEmployeeAmount"));
        assertEquals(List.of("F:32501.3:32600:98.7", "X:65000:65000:0", "Z:0:0:0", "M:68250:68300:50", "N:0:0:0"),
                rounding(pi, "esiEmployerAmount"));
        Map<String, Map<String, Object>> byId = register(out);
        assertEquals("7500.3", byId.get("F").get("esiEmployeeExact"));
        assertEquals("99.7", byId.get("F").get("esiEmployeeRoundingDifference"));
        assertEquals("32501.3", byId.get("F").get("esiEmployerExact"));
        assertEquals("98.7", byId.get("F").get("esiEmployerRoundingDifference"));
        assertEquals("0", byId.get("X").get("esiEmployeeRoundingDifference"));
        long separate = ((Number) byId.get("F").get("esiEmployee")).longValue()
                + ((Number) byId.get("F").get("esiEmployer")).longValue();
        assertEquals(40200L, separate, "employee and employer shares are rounded separately, never combined");
    }

    /**
     * Decision 280: a VERIFIED rounding row applies only with its citation and an explicit effective date on record,
     * and only to a wage period starting on or after that date; otherwise that period is refused.
     */
    @Test
    void decision280AVerifiedRowAppliesOnlyFromItsRecordedEffectiveDate() {
        assertEquals("POLICY_NOT_IN_FORCE_FOR_PERIOD",
                refusal("period-policy-late", vars(null, "2026-06-01", "2026-06-30")));
        ProcessInstance july = start("period-policy-late", vars(null, "2026-07-01", "2026-07-31"));
        // 125450 x 12 % = 15054 -> HALF_UP to 100 = 15100
        assertEquals(15100L, engine.getRuntimeService().getVariable(july.getId(), "share"));
        assertEquals("test-late@v1", engine.getRuntimeService().getVariable(july.getId(), "sharePolicy"));
        assertEquals("POLICY_VERIFICATION_INCOMPLETE",
                refusal("period-policy-uncited", vars(null, "2026-10-01", "2026-10-31")));
        assertEquals("POLICY_VERIFICATION_INCOMPLETE",
                refusal("period-policy-undated", vars(null, "2026-10-01", "2026-10-31")));
        // a SYNTHETIC_DEMO_ONLY row is not checked: it blocks go-live as a pack placeholder instead (May 2026, v-old)
        ProcessInstance may = start("period-policy", vars(null, "2026-05-01", "2026-05-31"));
        assertEquals("test-switch@v-old", engine.getRuntimeService().getVariable(may.getId(), "sharePolicy"));
    }

    /**
     * Decision 281: a stated wage period that is not one whole calendar month is refused on the eligibility path with
     * an explicit unverified code; a crossing of a contribution-period boundary keeps its own code (decision 270).
     */
    @Test
    void decision281AWagePeriodThatIsNotAWholeMonthIsRefused() {
        assertEquals("WAGE_PERIOD_NOT_WHOLE_MONTH_UNVERIFIED",
                refusal("period-payroll", vars(FOUR, "2026-10-15", "2026-10-31"))); // a joiner's first partial month
        assertEquals("WAGE_PERIOD_NOT_WHOLE_MONTH_UNVERIFIED",
                refusal("period-payroll", vars(FOUR, "2026-10-01", "2026-10-30")));
        assertEquals("WAGE_PERIOD_NOT_WHOLE_MONTH_UNVERIFIED",
                refusal("period-payroll", vars(FOUR, "2026-10-01", "2026-11-30"))); // two months
        assertEquals("WAGE_PERIOD_NOT_WHOLE_MONTH_UNVERIFIED",
                refusal("period-payroll-270", vars(FOUR, "2026-11-01", "2026-11-01")));
        assertEquals("WAGE_PERIOD_CROSSES_CONTRIBUTION_PERIOD_UNVERIFIED",
                refusal("period-payroll", vars(FOUR, "2026-09-16", "2026-10-15")));
        // February of a leap and a common year is a whole month
        start("period-payroll", vars(FOUR, "2027-02-01", "2027-02-28"));
        start("period-payroll", vars(FOUR, "2028-02-01", "2028-02-29"));
        // a calculation without eligibility is not decided by coverage: unchanged
        ProcessInstance part = start("period-policy", vars(null, "2026-10-15", "2026-10-31"));
        assertEquals("test-switch@v-new", engine.getRuntimeService().getVariable(part.getId(), "sharePolicy"));
    }

    /** Decision 282: above the limit with no live continuation is not covered, traced with the rules row version. */
    @Test
    void decision282AboveTheLimitWithoutALiveContinuationIsNotCoveredAndTraced() throws Exception {
        Map<String, Map<String, Object>> byId = register(engine.getRuntimeService().getVariables(
                start("period-payroll", vars(FOUR, "2026-10-01", "2026-10-31")).getId()));
        assertEquals("ABOVE_LIMIT_NO_LIVE_CONTINUATION", byId.get("D").get("esiEmployeeCoverageReason"));
        assertEquals("test-rules@r2", byId.get("D").get("esiEmployeeCoverageRules"));
        assertEquals("ABOVE_LIMIT_NO_LIVE_CONTINUATION", byId.get("D").get("esiEmployerCoverageReason"));
        assertEquals("test-rules@r2", byId.get("D").get("esiEmployerCoverageRules"));
        assertNull(byId.get("B").get("esiEmployeeCoverageReason"), "B is continued");
        assertNull(byId.get("A").get("esiEmployeeCoverageReason"), "A is within the limit");
        // rules row without continuation: B above the limit is not covered either, traced with that row
        Map<String, Map<String, Object>> none = register(engine.getRuntimeService().getVariables(
                start("period-payroll-none", vars(FOUR, "2026-10-01", "2026-10-31")).getId()));
        assertEquals("ABOVE_LIMIT_NO_LIVE_CONTINUATION", none.get("B").get("esiEmployeeCoverageReason"));
        assertEquals("test-rules-none@r2", none.get("B").get("esiEmployeeCoverageRules"));
    }

    /**
     * Decision 288: evidence and use are separate and recorded. A row for SYNTHETIC_DEMO_ONLY is never checked as a
     * production rule (the pack placeholder blocks go-live); the same row for PRODUCTION without a citation and an
     * explicit effective date is refused; the rules row's evidence and use are recorded with the result.
     */
    @Test
    void decision288EvidenceAndUseAreSeparateAndRecorded() {
        ProcessInstance demo = start("period-policy-demo-only", vars(null, "2026-10-01", "2026-10-31"));
        assertEquals("VERIFIED", engine.getRuntimeService().getVariable(demo.getId(), "sharePolicyEvidence"));
        assertEquals("SYNTHETIC_DEMO_ONLY", engine.getRuntimeService().getVariable(demo.getId(), "sharePolicyUse"));
        assertEquals("POLICY_VERIFICATION_INCOMPLETE",
                refusal("period-policy-undated", vars(null, "2026-10-01", "2026-10-31")));
        ProcessInstance pi = start("period-payroll", vars(FOUR, "2026-10-01", "2026-10-31"));
        assertEquals("UNVERIFIED", engine.getRuntimeService().getVariable(pi.getId(), "esiEmployeeAmountRulesEvidence"));
        assertEquals("SYNTHETIC_DEMO_ONLY", engine.getRuntimeService().getVariable(pi.getId(),
                "esiEmployeeAmountRulesUse"));
        assertEquals("PRODUCTION", engine.getRuntimeService().getVariable(pi.getId(), "esiEmployeeAmountPolicyUse"));
    }
}