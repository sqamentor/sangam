// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;

import org.sangam.packs.core.MoneyCalculationDelegate.Policy;
import org.sangam.packs.port.PackRuntime;

/**
 * Decision 139: rounding policies applied once, per component, per instalment with an explicit residual, negatives
 * by policy, and refusals for an unverified, conflicting or invalid policy. Every expected value is worked out by hand
 * in minor units (shown in the comments), independently of the code under test.
 *
 * <p>ASSUMPTIONS, not statutory expected results (decision 288): the PF and ESI rates, rounding modes and units below
 * are test inputs that exercise the arithmetic; no expected amount here states what any law requires.
 */
class MoneyCalculationDelegateTest {

    private static final String TENANT = "t-money";
    static ProcessEngine engine;

    /** A policy row with decision 288's evidence and use (test values, not a statement of any rule). */
    private static Map<String, Object> row(String evidence, String use, String mode, Object quantum, String negatives,
                                           String residual) {
        Map<String, Object> r = new HashMap<>();
        r.put("policyId", "test-policy");
        r.put("policyVersion", "v1");
        r.put("evidenceStatus", evidence);
        r.put("permittedUse", use);
        r.put("mode", mode);
        r.put("quantumMinor", quantum);
        r.put("negatives", negatives);
        r.put("residual", residual);
        return r;
    }

    private static Policy policy(String mode, long quantum, String negatives) {
        return Policy.of("test-policy", row("VERIFIED", "PRODUCTION", mode, quantum, negatives, "LAST"));
    }

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static String refusal(Runnable r) {
        String m = assertThrows(ProcessEngineException.class, r::run).getMessage();
        return m.substring(0, m.indexOf(':'));
    }

    // ---- policy application --------------------------------------------------------------------------------------

    @Test
    void ceilingToTheNextHigherMajorUnit() {
        Policy p = policy("CEILING", 100, "REFUSE");
        assertEquals(d("200"), p.apply(d("150.01")));    // 1.5001 major -> 2
        assertEquals(d("15000"), p.apply(d("15000")));   // already whole: unchanged
        assertEquals(d("100"), p.apply(d("0.0001")));    // any fraction goes up
        assertEquals(d("0"), p.apply(d("0")));
    }

    @Test
    void halfUpToTheNearestMajorUnitAtTheBoundary() {
        Policy p = policy("HALF_UP", 100, "REFUSE");
        assertEquals(d("1300"), p.apply(d("1250")));     // 12.50 -> 13 (fifty minor units or more go up)
        assertEquals(d("1200"), p.apply(d("1249.99"))); // 12.4999 -> 12
        assertEquals(d("100"), p.apply(d("50")));
        assertEquals(d("0"), p.apply(d("49.999")));
    }

    @Test
    void wholeMinorUnitQuantum() {
        Policy p = policy("HALF_UP", 1, "REFUSE");
        assertEquals(d("1235"), p.apply(d("1234.5")));
        assertEquals(d("1234"), p.apply(d("1234.4999999")));
    }

    @Test
    void negativesFollowThePolicy() {
        assertEquals("NEGATIVE_REFUSED", refusal(() -> policy("HALF_UP", 100, "REFUSE").apply(d("-1"))));
        assertEquals(d("-1300"), policy("HALF_UP", 100, "MAGNITUDE").apply(d("-1250")));  // |12.50| -> 13, sign kept
        assertEquals(d("-1200"), policy("CEILING", 100, "SIGNED").apply(d("-1250")));    // ceiling of -12.5 is -12
        assertEquals(d("-1300"), policy("CEILING", 100, "MAGNITUDE").apply(d("-1250"))); // ceiling of 12.5 is 13
    }

    @Test
    void unverifiedConflictingAndInvalidPoliciesAreRefused() {
        // decision 288: UNVERIFIED evidence for PRODUCTION use is refused; UNVERIFIED for SYNTHETIC_DEMO_ONLY is read
        assertEquals("POLICY_VERIFICATION_INCOMPLETE", refusal(() -> Policy.of("k", row("UNVERIFIED", "PRODUCTION",
                "HALF_UP", 100L, "REFUSE", ""))));
        assertEquals("POLICY_INVALID", refusal(() -> Policy.of("k", row("CONFLICTING", "PRODUCTION", "HALF_UP", 100L,
                "REFUSE", ""))));
        assertEquals("POLICY_INVALID", refusal(() -> Policy.of("k", row("DRAFT", "PRODUCTION", "HALF_UP", 100L, "REFUSE",
                ""))));
        assertEquals("POLICY_INVALID", refusal(() -> Policy.of("k", row("VERIFIED", "LIVE", "HALF_UP", 100L, "REFUSE",
                ""))));
        Map<String, Object> old = row("VERIFIED", "PRODUCTION", "HALF_UP", 100L, "REFUSE", "");
        old.remove("evidenceStatus");
        old.remove("permittedUse");
        old.put("status", "VERIFIED");
        assertEquals("POLICY_INVALID", refusal(() -> Policy.of("k", old)), "the old single status column is refused");
        assertEquals("POLICY_INVALID", refusal(() -> Policy.of("k", row("VERIFIED", "PRODUCTION", "NEAREST", 100L, "REFUSE", ""))));
        assertEquals("POLICY_INVALID", refusal(() -> Policy.of("k", row("VERIFIED", "PRODUCTION", "UNNECESSARY", 100L, "REFUSE", ""))));
        assertEquals("POLICY_INVALID", refusal(() -> Policy.of("k", row("VERIFIED", "PRODUCTION", "HALF_UP", 0L, "REFUSE", ""))));
        assertEquals("POLICY_INVALID", refusal(() -> Policy.of("k", row("VERIFIED", "PRODUCTION", "HALF_UP", d("2.5"), "REFUSE", ""))));
        assertEquals("POLICY_INVALID", refusal(() -> Policy.of("k", row("VERIFIED", "PRODUCTION", "HALF_UP", 100L, "ROUND", ""))));
        assertEquals("POLICY_MISSING", refusal(() -> Policy.of("k", Map.of())));
        Policy demo = Policy.of("k", row("UNVERIFIED", "SYNTHETIC_DEMO_ONLY", "DOWN", 100L, "REFUSE", "LAST"));
        assertEquals("UNVERIFIED", demo.evidence());
        assertEquals("SYNTHETIC_DEMO_ONLY", demo.use());
    }

    // ---- instalments ---------------------------------------------------------------------------------------------

    @Test
    void instalmentsConserveTheTotalWithTheRemainderOnTheLast() {
        Policy p = Policy.of("k", row("VERIFIED", "PRODUCTION", "DOWN", 100L, "REFUSE", "LAST"));
        BigDecimal total = d("100001");  // 1000.01 major units in 3 instalments: 333.33.. down to 333 each
        assertEquals(d("33300"), MoneyCalculationDelegate.instalment(p, total, d("3"), d("1")));
        assertEquals(d("33300"), MoneyCalculationDelegate.instalment(p, total, d("3"), d("2")));
        assertEquals(d("33401"), MoneyCalculationDelegate.instalment(p, total, d("3"), d("3"))); // 100001 - 2 x 33300
        BigDecimal sum = BigDecimal.ZERO;
        for (int k = 1; k <= 7; k++) {
            sum = sum.add(MoneyCalculationDelegate.instalment(p, d("1000003"), d("7"), BigDecimal.valueOf(k)));
        }
        assertEquals(d("1000003"), sum);
        assertEquals(d("50"), MoneyCalculationDelegate.instalment(p, d("50"), d("3"), d("3"))); // shares 0, rest 50
        assertEquals(d("777"), MoneyCalculationDelegate.instalment(p, d("777"), d("1"), d("1")));
    }

    @Test
    void residualFirstPutsTheRemainderOnTheFirstInstalment() {
        Policy p = Policy.of("k", row("VERIFIED", "PRODUCTION", "DOWN", 100L, "REFUSE", "FIRST"));
        assertEquals(d("33401"), MoneyCalculationDelegate.instalment(p, d("100001"), d("3"), d("1")));
        assertEquals(d("33300"), MoneyCalculationDelegate.instalment(p, d("100001"), d("3"), d("3")));
    }

    @Test
    void instalmentRefusals() {
        Policy last = Policy.of("k", row("VERIFIED", "PRODUCTION", "DOWN", 100L, "REFUSE", "LAST"));
        assertEquals("CALCULATION_INVALID", refusal(() -> MoneyCalculationDelegate.instalment(last, d("100"), d("3"),
                d("4"))));
        assertEquals("CALCULATION_INVALID", refusal(() -> MoneyCalculationDelegate.instalment(last, d("100"), d("0"),
                d("1"))));
        assertEquals("CALCULATION_INVALID", refusal(() -> MoneyCalculationDelegate.instalment(last, d("100"), d("2.5"),
                d("1"))));
        Policy none = Policy.of("k", row("VERIFIED", "PRODUCTION", "DOWN", 100L, "REFUSE", ""));
        assertEquals("POLICY_INVALID", refusal(() -> MoneyCalculationDelegate.instalment(none, d("100"), d("3"),
                d("1"))));
        // UP to whole major units: shares of 101 / 3 = 33.67 -> 100 each, the last would be 101 - 200 = -99
        Policy up = Policy.of("k", row("VERIFIED", "PRODUCTION", "UP", 100L, "REFUSE", "LAST"));
        assertEquals("RESIDUAL_NEGATIVE", refusal(() -> MoneyCalculationDelegate.instalment(up, d("101"), d("3"),
                d("3"))));
    }

    // ---- engine run ----------------------------------------------------------------------------------------------

    private static final String NS = "xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\"";

    private static String calc(String id, String... fields) {
        StringBuilder f = new StringBuilder();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            f.append("<operaton:field name=\"").append(fields[i]).append("\"><operaton:string>").append(fields[i + 1])
                    .append("</operaton:string></operaton:field>");
        }
        return "<serviceTask id=\"" + id + "\" operaton:class=\"" + MoneyCalculationDelegate.class.getName()
                + "\"><extensionElements>" + f + "</extensionElements></serviceTask>";
    }

    /** A policy DMN shaped like packgen rounding.py: one row from 2026-01-01, another before. */
    private static String policyDmn(String key, String mode, String quantum) {
        String out = "<output id=\"o0\" name=\"policyId\" typeRef=\"string\"/><output id=\"o1\" name=\"policyVersion\""
                + " typeRef=\"string\"/><output id=\"o2\" name=\"evidenceStatus\" typeRef=\"string\"/><output id=\"o3\""
                + " name=\"mode\" typeRef=\"string\"/><output id=\"o4\" name=\"quantumMinor\" typeRef=\"number\"/>"
                + "<output id=\"o5\" name=\"negatives\" typeRef=\"string\"/><output id=\"o6\" name=\"residual\""
                + " typeRef=\"string\"/><output id=\"o7\" name=\"source\" typeRef=\"string\"/>"
                + "<output id=\"o8\" name=\"provision\" typeRef=\"string\"/>"
                + "<output id=\"o9\" name=\"effectiveFrom\" typeRef=\"string\"/>"
                + "<output id=\"o10\" name=\"permittedUse\" typeRef=\"string\"/>";
        String rule = "<rule id=\"%s\"><inputEntry id=\"%s_i\"><text>%s</text></inputEntry>"
                + "<outputEntry id=\"%s_0\"><text>\"" + key + "\"</text></outputEntry>"
                + "<outputEntry id=\"%s_1\"><text>\"%s\"</text></outputEntry>"
                + "<outputEntry id=\"%s_2\"><text>\"%s\"</text></outputEntry>"
                + "<outputEntry id=\"%s_3\"><text>\"" + mode + "\"</text></outputEntry>"
                + "<outputEntry id=\"%s_4\"><text>" + quantum + "</text></outputEntry>"
                + "<outputEntry id=\"%s_5\"><text>\"REFUSE\"</text></outputEntry>"
                + "<outputEntry id=\"%s_6\"><text>\"LAST\"</text></outputEntry>"
                + "<outputEntry id=\"%s_7\"><text>\"%s\"</text></outputEntry>"
                + "<outputEntry id=\"%s_8\"><text>\"%s\"</text></outputEntry>"
                + "<outputEntry id=\"%s_9\"><text>\"%s\"</text></outputEntry>"
                + "<outputEntry id=\"%s_10\"><text>\"%s\"</text></outputEntry></rule>";
        // decisions 280 and 288: the VERIFIED PRODUCTION row records its citation and explicit effective date (test
        // values, not a statement of any rule)
        String now = String.format(rule, "r0", "r0", "[date(\"2026-01-01\")..date(\"9999-12-31\")]", "r0", "r0", "v2",
                "r0", "VERIFIED", "r0", "r0", "r0", "r0", "r0", "test source", "r0", "para 1", "r0", "2026-01-01",
                "r0", "PRODUCTION");
        String before = String.format(rule, "r1", "r1", "[date(\"1900-01-01\")..date(\"2025-12-31\")]", "r1", "r1",
                "v1", "r1", "UNVERIFIED", "r1", "r1", "r1", "r1", "r1", "none found", "r1", "none", "r1",
                "1900-01-01", "r1", "SYNTHETIC_DEMO_ONLY");
        return "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"d_" + key.replace('-', '_')
                + "\" name=\"p\" namespace=\"t\"><decision id=\"" + key + "\" name=\"p\"><decisionTable id=\"t_"
                + key.replace('-', '_') + "\" hitPolicy=\"UNIQUE\"><input id=\"i0\"><inputExpression id=\"ie0\""
                + " typeRef=\"date\"><text>date(asOfDate)</text></inputExpression></input>" + out + now + before
                + "</decisionTable></decision></definitions>";
    }

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-money-calculation;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        org.sangam.packs.PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel), as production
        engine = cfg.buildProcessEngine();
        String process = "<definitions " + NS + "><process id=\"money-calc\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                + calc("pf", "result", "pfAmount", "formula",
                        "round(pfWages * pfEmployeePct / 100) + round(pfWages * pfEmployerPct / 100)",
                        "policy", "test-half-up-rounding")
                + calc("esiE", "result", "esiEmployeeAmount", "formula", "esiWages * esiEmployeePct / 100",
                        "policy", "test-ceiling-rounding")
                + calc("esiR", "result", "esiEmployerAmount", "formula", "esiWages * esiEmployerPct / 100",
                        "policy", "test-ceiling-rounding")
                + calc("esi", "result", "esiAmount", "formula", "esiEmployeeAmount + esiEmployerAmount", "op", "SUM")
                + calc("inst", "result", "instalmentAmount", "op", "INSTALMENT", "total", "amount", "count",
                        "instalments", "number", "instalmentNo", "policy", "test-down-rounding")
                + calc("ratio", "result", "foirPct", "formula", "emi * 100 / income", "ratio", "true")
                + "<userTask id=\"hold\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"pf\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"pf\" targetRef=\"esiE\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"esiE\" targetRef=\"esiR\"/>"
                + "<sequenceFlow id=\"f4\" sourceRef=\"esiR\" targetRef=\"esi\"/>"
                + "<sequenceFlow id=\"f5\" sourceRef=\"esi\" targetRef=\"inst\"/>"
                + "<sequenceFlow id=\"f6\" sourceRef=\"inst\" targetRef=\"ratio\"/>"
                + "<sequenceFlow id=\"f7\" sourceRef=\"ratio\" targetRef=\"hold\"/>"
                + "<sequenceFlow id=\"f8\" sourceRef=\"hold\" targetRef=\"e\"/></process></definitions>";
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString("money-calc.bpmn", process)
                .addString("half-up.dmn", policyDmn("test-half-up-rounding", "HALF_UP", "100"))
                .addString("ceiling.dmn", policyDmn("test-ceiling-rounding", "CEILING", "100"))
                .addString("down.dmn", policyDmn("test-down-rounding", "DOWN", "100")).deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    @Test
    void aCaseComputesEveryAmountByItsPolicyAndKeepsThePolicyVersion() {
        Map<String, Object> v = new HashMap<>();
        v.put("pfWages", 1254L);          // 12.54 major units
        v.put("pfEmployeePct", d("12"));
        v.put("pfEmployerPct", d("12"));
        v.put("esiWages", 2000001L);      // 20000.01 major units
        v.put("esiEmployeePct", d("0.75"));
        v.put("esiEmployerPct", d("3.25"));
        v.put("amount", 100001L);
        v.put("instalments", 3);
        v.put("instalmentNo", 3);
        v.put("emi", 25000L);
        v.put("income", 60000L);
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceByKey("money-calc")
                .processDefinitionTenantId(TENANT).setVariables(v).execute();
        Map<String, Object> out = engine.getRuntimeService().getVariables(pi.getId());
        // PF per share: 1254 x 12 % = 150.48 minor units, HALF_UP to a quantum of 100 = 200; two shares = 400.
        // Rounding the total instead (1254 x 24 % = 300.96 -> 300) would give 300: the policy is per component.
        assertEquals(400L, out.get("pfAmount"));
        assertEquals("test-half-up-rounding@v2", out.get("pfAmountPolicy"));
        assertEquals("VERIFIED", out.get("pfAmountPolicyEvidence"));
        assertEquals("PRODUCTION", out.get("pfAmountPolicyUse"));
        // ESI employee 2000001 x 0.75 % = 15000.0075 -> ceiling to 100 = 15100; employer x 3.25 % = 65000.0325 -> 65100
        assertEquals(15100L, out.get("esiEmployeeAmount"));
        assertEquals(65100L, out.get("esiEmployerAmount"));
        assertEquals(80200L, out.get("esiAmount"));
        // instalment 3 of 3 of 100001, shares 33333.67 down to 33300: last = 100001 - 66600 = 33401
        assertEquals(33401L, out.get("instalmentAmount"));
        assertEquals("test-down-rounding@v2", out.get("instalmentAmountPolicy"));
        // ratio: 25000 x 100 / 60000 = 41.666... exact at 34 digits, not rounded
        assertEquals(d("41.66666666666666666666666666666667"), out.get("foirPct"));
        assertTrue(out.get("test_half_up_rounding_version") instanceof Integer);
    }
}
