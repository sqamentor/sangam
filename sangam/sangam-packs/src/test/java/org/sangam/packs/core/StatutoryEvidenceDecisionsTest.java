// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;

import org.sangam.packs.port.PackRuntime;

/**
 * Owner decisions 292, 293, 296 and 297 over {@link MoneyCalculationDelegate} and {@link RowEvidence}, from the
 * statutory evidence of lane W4-V (sangam-handoff evidence/STATUTORY-EVIDENCE-288.md):
 * <ul>
 *   <li>292: in production mode ({@link PackRuntime#production()}) a row whose permitted use is not PRODUCTION is
 *       refused, policy and rules rows alike; non-production (the default) keeps them usable;</li>
 *   <li>293: field {@code periodUnit CALENDAR_MONTH} (the PF shares: EPF Scheme 2026 para 18(4)) refuses a wage period
 *       that is not one calendar month, or none;</li>
 *   <li>296: the shipped ESI rounding rows (rule 19(1)(a) and (b), VERIFIED, SYNTHETIC_DEMO_ONLY, from 8 May 2026)
 *       round each share separately to the next higher rupee; a period before 8 May 2026 is refused;</li>
 *   <li>297: a PRODUCTION row selected on the period's last day ({@code periodRuleBasis LAST_DAY}) is checked against
 *       the last day, any other row against the first day.</li>
 * </ul>
 * Amounts are minor units. The evidence-file worked examples are the statutory ones; the other rows are test data, not
 * statements of any law. Written for the test phase (not run here).
 */
class StatutoryEvidenceDecisionsTest {

    private static final String TENANT = "t-evidence";
    private static final Path PAYROLL = Path.of("src", "main", "resources", "packs", "sec-hr-payroll");
    static ProcessEngine engine;
    static int seq;
    static int ids;

    private static final String NS = "xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\"";

    /** One MoneyCalculationDelegate task {@code one} followed by a wait state. */
    private static String process(String id, String... fields) {
        StringBuilder f = new StringBuilder();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            f.append("<operaton:field name=\"").append(fields[i]).append("\"><operaton:string>").append(fields[i + 1])
                    .append("</operaton:string></operaton:field>");
        }
        return "<definitions " + NS + "><process id=\"" + id + "\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                + "<serviceTask id=\"one\" operaton:class=\"" + MoneyCalculationDelegate.class.getName()
                + "\"><extensionElements>" + f + "</extensionElements></serviceTask><userTask id=\"hold\"/>"
                + "<endEvent id=\"e\"/><sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"one\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"one\" targetRef=\"hold\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"hold\" targetRef=\"e\"/></process></definitions>";
    }

    private static final String[] PERIOD = {"periodFrom", "from", "periodTo", "to"};

    private static String[] withPeriod(String... fields) {
        String[] all = java.util.Arrays.copyOf(fields, fields.length + PERIOD.length);
        System.arraycopy(PERIOD, 0, all, fields.length, PERIOD.length);
        return all;
    }

    /**
     * A policy DMN with the given rows; each row is {from, to, values of COLUMNS...} with string values already
     * quoted and quantumMinor a number.
     */
    private static String policy(String key, String[]... rows) {
        String cols = "policyId,policyVersion,evidenceStatus,permittedUse,mode,quantumMinor,negatives,source,provision,"
                + "effectiveFrom,periodRuleBasis";
        StringBuilder b = new StringBuilder("<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d_"
                + key.replace('-', '_') + "\" name=\"p\" namespace=\"t\"><decision id=\"" + key + "\" name=\"p\">"
                + "<decisionTable id=\"t_" + key.replace('-', '_') + "\" hitPolicy=\"UNIQUE\"><input id=\"i0\">"
                + "<inputExpression id=\"ie0\" typeRef=\"date\"><text>date(asOfDate)</text></inputExpression></input>");
        for (String c : cols.split(",")) {
            b.append("<output id=\"o").append(++ids).append("\" name=\"").append(c).append("\" typeRef=\"")
                    .append(c.equals("quantumMinor") ? "number" : "string").append("\"/>");
        }
        for (String[] r : rows) {
            b.append("<rule id=\"r").append(++ids).append("\"><inputEntry id=\"e").append(++ids)
                    .append("\"><text>[date(\"").append(r[0]).append("\")..date(\"").append(r[1])
                    .append("\")]</text></inputEntry>");
            for (int i = 2; i < r.length; i++) {
                b.append("<outputEntry id=\"e").append(++ids).append("\"><text>").append(r[i])
                        .append("</text></outputEntry>");
            }
            b.append("</rule>");
        }
        return b.append("</decisionTable></decision></definitions>").toString();
    }

    private static String[] policyRow(String from, String to, String key, String version, String evidence, String use,
                                      String mode, String effectiveFrom, String basis) {
        return new String[] {from, to, q(key), q(version), q(evidence), q(use), q(mode), "100", q("REFUSE"),
                q("test source"), q("para 1"), q(effectiveFrom), q(basis)};
    }

    private static String q(String s) {
        return "\"" + s + "\"";
    }

    private static String shipped(String file) throws IOException {
        return Files.readString(PAYROLL.resolve(file), StandardCharsets.UTF_8);
    }

    @BeforeAll
    static void setUp() throws IOException {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-statutory-evidence;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        org.sangam.packs.PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel), as production
        engine = cfg.buildProcessEngine();
        String share = "x * 12 / 100";
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                // decision 296: the shipped ESI rounding policies, each share on its own
                .addString("sec-hr-esi-employee-rounding.dmn", shipped("sec-hr-esi-employee-rounding.dmn"))
                .addString("sec-hr-esi-employer-rounding.dmn", shipped("sec-hr-esi-employer-rounding.dmn"))
                .addString("esi-employee.bpmn", process("esi-employee", withPeriod("result", "share", "formula",
                        "w * 0.75 / 100", "policy", "sec-hr-esi-employee-rounding")))
                .addString("esi-employer.bpmn", process("esi-employer", withPeriod("result", "share", "formula",
                        "w * 3.25 / 100", "policy", "sec-hr-esi-employer-rounding")))
                // decision 293: a PF-like share that works on the wages of a calendar month
                .addString("monthly.dmn", policy("test-monthly", policyRow("1900-01-01", "9999-12-31",
                        "test-monthly", "m1", "UNVERIFIED", "SYNTHETIC_DEMO_ONLY", "HALF_UP", "1900-01-01",
                        "FIRST_DAY")))
                .addString("monthly.bpmn", process("monthly", withPeriod("result", "share", "formula", share,
                        "policy", "test-monthly", "periodUnit", "CALENDAR_MONTH")))
                .addString("monthly-unstated.bpmn", process("monthly-unstated", "result", "share", "formula", share,
                        "policy", "test-monthly", "periodUnit", "CALENDAR_MONTH"))
                .addString("monthly-bad-unit.bpmn", process("monthly-bad-unit", withPeriod("result", "share",
                        "formula", share, "policy", "test-monthly", "periodUnit", "WEEK")))
                .addString("no-unit.bpmn", process("no-unit", withPeriod("result", "share", "formula", share,
                        "policy", "test-monthly")))
                // decision 297: a PRODUCTION row from 17 June 2026 that says LAST_DAY, after a demo row
                .addString("last-day.dmn", policy("test-last-day",
                        policyRow("1900-01-01", "2026-06-16", "test-last-day", "old", "UNVERIFIED",
                                "SYNTHETIC_DEMO_ONLY", "DOWN", "1900-01-01", "FIRST_DAY"),
                        policyRow("2026-06-17", "9999-12-31", "test-last-day", "new", "VERIFIED", "PRODUCTION",
                                "HALF_UP", "2026-06-17", "LAST_DAY")))
                .addString("last-day.bpmn", process("last-day", withPeriod("result", "share", "formula", share,
                        "policy", "test-last-day")))
                // decision 297: the same PRODUCTION row matched from 1 June 2026 with FIRST_DAY, and with LAST_DAY
                .addString("first-day-late.dmn", policy("test-first-day-late", policyRow("2026-06-01", "9999-12-31",
                        "test-first-day-late", "v1", "VERIFIED", "PRODUCTION", "HALF_UP", "2026-06-17", "FIRST_DAY")))
                .addString("first-day-late.bpmn", process("first-day-late", withPeriod("result", "share", "formula",
                        share, "policy", "test-first-day-late")))
                .addString("last-day-late.dmn", policy("test-last-day-late", policyRow("2026-06-01", "9999-12-31",
                        "test-last-day-late", "v1", "VERIFIED", "PRODUCTION", "HALF_UP", "2026-06-17", "LAST_DAY")))
                .addString("last-day-late.bpmn", process("last-day-late", withPeriod("result", "share", "formula",
                        share, "policy", "test-last-day-late")))
                .deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    @AfterEach
    void nonProduction() {
        PackRuntime.reset();
    }

    private static Map<String, Object> vars(Object w, String from, String to) {
        Map<String, Object> v = new HashMap<>();
        v.put("x", 125450L);
        if (w != null) {
            v.put("w", w);
        }
        if (from != null) {
            v.put("from", from);
            v.put("to", to);
        }
        return v;
    }

    private static ProcessInstance start(String key, Map<String, Object> v) {
        return engine.getRuntimeService().createProcessInstanceByKey(key).businessKey("EVIDENCE-" + (++seq))
                .processDefinitionTenantId(TENANT).setVariables(v).execute();
    }

    private static Object var(ProcessInstance pi, String name) {
        return engine.getRuntimeService().getVariable(pi.getId(), name);
    }

    private static final java.util.regex.Pattern CODE =
            java.util.regex.Pattern.compile("(WAGE|POLICY|RULES|CALCULATION|PERIOD)_[A-Z_]+(?=:)");

    private static String refusal(String key, Map<String, Object> v) {
        String m = assertThrows(ProcessEngineException.class, () -> start(key, v)).getMessage();
        java.util.regex.Matcher c = CODE.matcher(m);
        assertTrue(c.find(), "no refusal code in: " + m);
        return c.group();
    }

    // ---------------------------------------------------------------- 292

    @Test
    void decision292ProductionModeRefusesEveryRowNotForProduction() {
        Map<String, Object> demo = Map.of("evidenceStatus", "UNVERIFIED", "permittedUse", "SYNTHETIC_DEMO_ONLY");
        Map<String, Object> verifiedDemo = Map.of("evidenceStatus", "VERIFIED", "permittedUse", "SYNTHETIC_DEMO_ONLY");
        Map<String, Object> live = Map.of("evidenceStatus", "VERIFIED", "permittedUse", "PRODUCTION");
        assertTrue(!PackRuntime.production(), "non-production is the default");
        assertArrayEquals(new String[] {"UNVERIFIED", "SYNTHETIC_DEMO_ONLY"}, RowEvidence.evidenceAndUse(demo, "RULES",
                "r"));
        PackRuntime.install(PackRuntime.ports().withProduction(true));
        assertTrue(PackRuntime.production());
        for (String prefix : List.of("RULES", "POLICY")) {
            for (Map<String, Object> row : List.of(demo, verifiedDemo)) {
                String m = assertThrows(ProcessEngineException.class,
                        () -> RowEvidence.evidenceAndUse(row, prefix, "row")).getMessage();
                assertTrue(m.startsWith(prefix + "_NOT_PERMITTED_IN_PRODUCTION: "), m);
            }
            assertArrayEquals(new String[] {"VERIFIED", "PRODUCTION"}, RowEvidence.evidenceAndUse(live, prefix, "row"));
        }
        // the earlier checks keep their codes in production mode
        assertTrue(assertThrows(ProcessEngineException.class, () -> RowEvidence.evidenceAndUse(Map.of("status",
                "VERIFIED"), "RULES", "row")).getMessage().startsWith("RULES_INVALID: "));
        assertTrue(assertThrows(ProcessEngineException.class, () -> RowEvidence.evidenceAndUse(Map.of(
                "evidenceStatus", "UNVERIFIED", "permittedUse", "PRODUCTION"), "RULES", "row")).getMessage()
                .startsWith("RULES_VERIFICATION_INCOMPLETE: "));
        // through the delegate: the shipped ESI row (VERIFIED, SYNTHETIC_DEMO_ONLY) is refused in production mode
        assertEquals("POLICY_NOT_PERMITTED_IN_PRODUCTION", refusal("esi-employee", vars(1000100L, "2026-10-01",
                "2026-10-31")));
        // every other port is kept by withProduction, and withProduction(false) turns it off again
        PackRuntime.Ports p = PackRuntime.ports();
        assertEquals(p.gateway(), p.withProduction(false).gateway());
        assertTrue(!p.withProduction(false).production());
        assertTrue(p.withClock(java.time.Clock.systemUTC()).production(), "the other builders keep the mode");
    }

    // ---------------------------------------------------------------- 293

    @Test
    void decision293AShareOfTheMonthRefusesAWagePeriodThatIsNotACalendarMonth() {
        ProcessInstance oct = start("monthly", vars(null, "2026-10-01", "2026-10-31"));
        // 125450 x 12 % = 15054 -> HALF_UP to 100 = 15100
        assertEquals(15100L, var(oct, "share"));
        start("monthly", vars(null, "2028-02-01", "2028-02-29"));
        assertEquals("WAGE_PERIOD_NOT_CALENDAR_MONTH", refusal("monthly", vars(null, "2026-10-01", "2026-10-15")));
        assertEquals("WAGE_PERIOD_NOT_CALENDAR_MONTH", refusal("monthly", vars(null, "2026-10-05", "2026-11-04")));
        assertEquals("WAGE_PERIOD_NOT_CALENDAR_MONTH", refusal("monthly", vars(null, "2026-10-01", "2026-11-30")));
        assertEquals("WAGE_PERIOD_NOT_CALENDAR_MONTH", refusal("monthly-unstated", vars(null, null, null)));
        assertEquals("CALCULATION_INVALID", refusal("monthly-bad-unit", vars(null, "2026-10-01", "2026-10-31")));
        // without periodUnit a fortnight is not refused by this rule
        assertEquals(15100L, var(start("no-unit", vars(null, "2026-10-01", "2026-10-15")), "share"));
    }

    // ---------------------------------------------------------------- 296

    @Test
    void decision296TheEsiSharesAreRoundedSeparatelyToTheNextHigherRupeeFrom8May2026() {
        // Rs 10,001: employee 0.75 % = 75.0075 -> 76, employer 3.25 % = 325.0325 -> 326; 326 + 76 = 402 (combined at
        // 4 % it would be 400.04 -> 401)
        long employee = (Long) var(start("esi-employee", vars(1000100L, "2026-10-01", "2026-10-31")), "share");
        long employer = (Long) var(start("esi-employer", vars(1000100L, "2026-10-01", "2026-10-31")), "share");
        assertEquals(7600L, employee);
        assertEquals(32600L, employer);
        assertEquals(40200L, employee + employer, "Rs 402, the shares rounded separately");
        // Rs 10,000: an exact rupee stays (325 and 75)
        assertEquals(7500L, var(start("esi-employee", vars(1000000L, "2026-10-01", "2026-10-31")), "share"));
        assertEquals(32500L, var(start("esi-employer", vars(1000000L, "2026-10-01", "2026-10-31")), "share"));
        // Rs 0: 0
        assertEquals(0L, var(start("esi-employee", vars(0L, "2026-10-01", "2026-10-31")), "share"));
        assertEquals(0L, var(start("esi-employer", vars(0L, "2026-10-01", "2026-10-31")), "share"));
        // the rows: VERIFIED evidence, SYNTHETIC_DEMO_ONLY use, rule 19(1)(b) and (a)
        ProcessInstance pi = start("esi-employee", vars(1000100L, "2026-10-01", "2026-10-31"));
        assertEquals("sec-hr-esi-employee-rounding@ss-2026-r19-1-b", var(pi, "sharePolicy"));
        assertEquals("VERIFIED", var(pi, "sharePolicyEvidence"));
        assertEquals("SYNTHETIC_DEMO_ONLY", var(pi, "sharePolicyUse"));
        assertEquals("sec-hr-esi-employer-rounding@ss-2026-r19-1-a",
                var(start("esi-employer", vars(1000100L, "2026-10-01", "2026-10-31")), "sharePolicy"));
        // in force from 8 May 2026: May 2026 starts before it, April 2026 too; both refused (old rule 51 not found)
        assertEquals("POLICY_MISSING", refusal("esi-employee", vars(1000100L, "2026-05-01", "2026-05-31")));
        assertEquals("POLICY_MISSING", refusal("esi-employer", vars(1000100L, "2026-04-01", "2026-04-30")));
        assertEquals(7600L, var(start("esi-employee", vars(1000100L, "2026-06-01", "2026-06-30")), "share"));
    }

    // ---------------------------------------------------------------- 297

    @Test
    void decision297ALastDayRowIsCheckedOnTheLastDayAndAnyOtherRowOnTheFirstDay() {
        // June 2026: the old demo row on the 1st, the PRODUCTION row from the 17th says LAST_DAY: it applies to the
        // whole period and is checked on 30 June (on or after 17 June): 15054 HALF_UP -> 15100
        ProcessInstance june = start("last-day", vars(null, "2026-06-01", "2026-06-30"));
        assertEquals("test-last-day@new", var(june, "sharePolicy"));
        assertEquals(15100L, var(june, "share"));
        // a FIRST_DAY PRODUCTION row matched from 1 June but in force from 17 June is checked on 1 June: refused
        assertEquals("POLICY_NOT_IN_FORCE_FOR_PERIOD", refusal("first-day-late", vars(null, "2026-06-01",
                "2026-06-30")));
        // the same row with LAST_DAY is checked on 30 June: applies
        assertEquals("test-last-day-late@v1", var(start("last-day-late", vars(null, "2026-06-01", "2026-06-30")),
                "sharePolicy"));
        // a period ending before 17 June is refused under either basis
        assertEquals("POLICY_NOT_IN_FORCE_FOR_PERIOD", refusal("last-day-late", vars(null, "2026-06-01",
                "2026-06-16")));
        assertEquals("2026-06-30", MoneyCalculationDelegate.checkDay(Map.of("k",
                "2026-06-30 LAST_DAY RULE_CHANGED_IN_PERIOD"), "k"));
        assertEquals("2026-06-01", MoneyCalculationDelegate.checkDay(Map.of("k", "2026-06-01 FIRST_DAY"), "k"));
    }
}
