// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
 * Decision 223: op {@code EACH} of {@link MoneyCalculationDelegate} computes a share per item of a register, rounds
 * it per item by the decision 139 policy, applies a cap and an eligibility limit from a versioned rules DMN, writes the
 * per-item results back and sums them exactly. Every expected value is worked out by hand in minor units (comments).
 *
 * <p>ASSUMPTIONS, not statutory expected results (decision 288): the PF and ESI rates, the ceiling, the limit and the
 * rounding policies here are test inputs that exercise the arithmetic; no expected amount states what any law requires.
 */
class MoneyEachOperationTest {

    private static final String TENANT = "t-each";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static ProcessEngine engine;
    static int seq;

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

    /** A rounding policy DMN shaped like packgen rounding.py, one open-ended VERIFIED row from 1 January 2000. */
    private static String policyDmn(String key, String mode) {
        return "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" xmlns:operaton=\"http://operaton.org/schema/1.0/dmn\" id=\"d_" + key.replace('-', '_')
                + "\" name=\"p\" namespace=\"t\"><decision operaton:historyTimeToLive=\"30\" id=\"" + key + "\" name=\"p\"><decisionTable id=\"t_"
                + key.replace('-', '_') + "\" hitPolicy=\"UNIQUE\"><input id=\"i0\"><inputExpression id=\"ie0\""
                + " typeRef=\"date\"><text>date(asOfDate)</text></inputExpression></input>"
                + "<output id=\"o0\" name=\"policyId\" typeRef=\"string\"/>"
                + "<output id=\"o1\" name=\"policyVersion\" typeRef=\"string\"/>"
                + "<output id=\"o2\" name=\"evidenceStatus\" typeRef=\"string\"/>"
                + "<output id=\"o2u\" name=\"permittedUse\" typeRef=\"string\"/>"
                + "<output id=\"o3\" name=\"mode\" typeRef=\"string\"/>"
                + "<output id=\"o4\" name=\"quantumMinor\" typeRef=\"number\"/>"
                + "<output id=\"o5\" name=\"negatives\" typeRef=\"string\"/>"
                + "<output id=\"o6\" name=\"source\" typeRef=\"string\"/>"
                + "<output id=\"o7\" name=\"provision\" typeRef=\"string\"/>"
                + "<output id=\"o8\" name=\"effectiveFrom\" typeRef=\"string\"/>"
                + "<rule id=\"r0\"><inputEntry id=\"r0i\"><text>[date(\"2000-01-01\")..date(\"9999-12-31\")]</text>"
                + "</inputEntry><outputEntry id=\"r0o0\"><text>\"" + key + "\"</text></outputEntry>"
                + "<outputEntry id=\"r0o1\"><text>\"v1\"</text></outputEntry>"
                + "<outputEntry id=\"r0o2\"><text>\"VERIFIED\"</text></outputEntry>"
                + "<outputEntry id=\"r0o2u\"><text>\"PRODUCTION\"</text></outputEntry>"
                + "<outputEntry id=\"r0o3\"><text>\"" + mode + "\"</text></outputEntry>"
                + "<outputEntry id=\"r0o4\"><text>100</text></outputEntry>"
                + "<outputEntry id=\"r0o5\"><text>\"REFUSE\"</text></outputEntry>"
                // decision 280: a VERIFIED row records its citation and explicit effective date (test values)
                + "<outputEntry id=\"r0o6\"><text>\"test source\"</text></outputEntry>"
                + "<outputEntry id=\"r0o7\"><text>\"para 1\"</text></outputEntry>"
                + "<outputEntry id=\"r0o8\"><text>\"2000-01-01\"</text></outputEntry></rule>"
                + "</decisionTable></decision></definitions>";
    }

    /** Versioned wage rules shaped like packgen payroll.py: ceiling 25,000.00 and limit 21,000.00 in minor units. */
    private static String rulesDmn(String key, String evidence, String use) {
        return "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" xmlns:operaton=\"http://operaton.org/schema/1.0/dmn\" id=\"d_" + key.replace('-', '_')
                + "\" name=\"r\" namespace=\"t\"><decision operaton:historyTimeToLive=\"30\" id=\"" + key + "\" name=\"r\"><decisionTable id=\"t_"
                + key.replace('-', '_') + "\" hitPolicy=\"UNIQUE\"><input id=\"i0\"><inputExpression id=\"ie0\""
                + " typeRef=\"date\"><text>date(asOfDate)</text></inputExpression></input>"
                + "<output id=\"o0\" name=\"ruleId\" typeRef=\"string\"/>"
                + "<output id=\"o1\" name=\"ruleVersion\" typeRef=\"string\"/>"
                + "<output id=\"o2\" name=\"evidenceStatus\" typeRef=\"string\"/>"
                + "<output id=\"o2u\" name=\"permittedUse\" typeRef=\"string\"/>"
                + "<output id=\"o3\" name=\"pfWageCeiling\" typeRef=\"number\"/>"
                + "<output id=\"o4\" name=\"esiWageLimit\" typeRef=\"number\"/>"
                + "<rule id=\"r0\"><inputEntry id=\"r0i\"><text>[date(\"1900-01-01\")..date(\"9999-12-31\")]</text>"
                + "</inputEntry><outputEntry id=\"r0o0\"><text>\"" + key + "\"</text></outputEntry>"
                + "<outputEntry id=\"r0o1\"><text>\"demo-1\"</text></outputEntry>"
                + "<outputEntry id=\"r0o2\"><text>\"" + evidence + "\"</text></outputEntry>"
                + "<outputEntry id=\"r0o2u\"><text>\"" + use + "\"</text></outputEntry>"
                + "<outputEntry id=\"r0o3\"><text>2500000</text></outputEntry>"
                + "<outputEntry id=\"r0o4\"><text>2100000</text></outputEntry></rule>"
                + "</decisionTable></decision></definitions>";
    }

    private static String process(String id, String rulesKey) {
        return "<definitions " + NS + "><process id=\"" + id + "\" isExecutable=\"true\" operaton:historyTimeToLive=\"30\"><startEvent id=\"s\"/>"
                + calc("pfE", "result", "pfEmployeeAmount", "op", "EACH", "items", "register", "itemResult",
                        "pfEmployee", "formula", "pfWages * pfEmployeePct / 100", "policy", "test-half-up",
                        "cap", "pfWages&lt;=pfWageCeiling", "rules", rulesKey)
                + calc("esiE", "result", "esiEmployeeAmount", "op", "EACH", "items", "register", "itemResult",
                        "esiEmployee", "formula", "esiWages * esiEmployeePct / 100", "policy", "test-ceiling",
                        "eligibleWhen", "esiWages&lt;=esiWageLimit", "rules", rulesKey)
                + calc("agg", "result", "esiAggregate", "formula", "eligibleEsiWages * esiEmployeePct / 100",
                        "policy", "test-ceiling")
                + "<userTask id=\"hold\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"pfE\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"pfE\" targetRef=\"esiE\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"esiE\" targetRef=\"agg\"/>"
                + "<sequenceFlow id=\"f4\" sourceRef=\"agg\" targetRef=\"hold\"/>"
                + "<sequenceFlow id=\"f5\" sourceRef=\"hold\" targetRef=\"e\"/></process></definitions>";
    }

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-money-each;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        org.sangam.packs.PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel), as production
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString("each.bpmn", process("money-each", "test-wage-rules"))
                .addString("each-unverified.bpmn", process("money-each-unverified", "test-wage-rules-unverified"))
                .addString("half-up.dmn", policyDmn("test-half-up", "HALF_UP"))
                .addString("ceiling.dmn", policyDmn("test-ceiling", "CEILING"))
                .addString("rules.dmn", rulesDmn("test-wage-rules", "UNVERIFIED", "SYNTHETIC_DEMO_ONLY"))
                // decision 288: UNVERIFIED evidence claimed for PRODUCTION use
                .addString("rules-unverified.dmn", rulesDmn("test-wage-rules-unverified", "UNVERIFIED", "PRODUCTION"))
                .deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    /** A register as the bytes JSON document the HRIS call stores. */
    private static String register(String json) {
        return json;
    }

    private static Map<String, Object> vars(Object register) {
        Map<String, Object> v = new HashMap<>();
        if (register != null) {
            v.put("register", register instanceof String s
                    ? Variables.byteArrayValue(s.getBytes(StandardCharsets.UTF_8)) : register);
        }
        v.put("pfEmployeePct", new java.math.BigDecimal("12"));
        v.put("esiEmployeePct", new java.math.BigDecimal("0.75"));
        v.put("eligibleEsiWages", 225075L); // employees A and C together, for the aggregate comparison
        return v;
    }

    private static ProcessInstance start(String key, Map<String, Object> v) {
        return engine.getRuntimeService().createProcessInstanceByKey(key).businessKey("EACH-" + (++seq))
                .processDefinitionTenantId(TENANT).setVariables(v).execute();
    }

    private static final java.util.regex.Pattern CODE =
            java.util.regex.Pattern.compile("(REGISTER|ITEM|RULES)_[A-Z_]+(?=:)"); // a code has several words

    /** The refusal code ({@code CODE: message}) of a start that must fail. */
    private static String refusal(String key, Map<String, Object> v) {
        String m = assertThrows(ProcessEngineException.class, () -> start(key, v)).getMessage();
        java.util.regex.Matcher c = CODE.matcher(m);
        assertTrue(c.find(), "no refusal code in: " + m);
        return c.group();
    }

    static final String THREE = "[{\"employeeId\":\"A\",\"pfWages\":125050,\"esiWages\":125050},"
            + "{\"employeeId\":\"B\",\"pfWages\":3000000,\"esiWages\":2500000},"
            + "{\"employeeId\":\"C\",\"pfWages\":100025,\"esiWages\":100025}]";

    @Test
    void eachShareIsRoundedPerEmployeeCappedOrExcludedAndSummedExactly() throws Exception {
        ProcessInstance pi = start("money-each", vars(register(THREE)));
        Map<String, Object> out = engine.getRuntimeService().getVariables(pi.getId());
        // PF 12 %, HALF_UP to 100: A 125050 x 12 % = 15006 -> 15000; B capped at 2500000 -> 300000;
        // C 100025 x 12 % = 12003 -> 12000; total 327000
        assertEquals(327000L, out.get("pfEmployeeAmount"));
        // ESI 0.75 %, CEILING to 100: A 937.875 -> 1000; B 2500000 > 2100000 is not eligible -> 0;
        // C 750.1875 -> 800; total 1800
        assertEquals(1800L, out.get("esiEmployeeAmount"));
        // the same wages rounded once on the aggregate: (125050 + 100025) x 0.75 % = 1688.0625 -> 1700
        assertEquals(1700L, out.get("esiAggregate"));
        assertNotEquals(out.get("esiAggregate"), out.get("esiEmployeeAmount"),
                "rounding the aggregate is not the per-employee policy");
        assertEquals("test-half-up@v1", out.get("pfEmployeeAmountPolicy"));
        assertEquals("test-wage-rules@demo-1", out.get("pfEmployeeAmountRules"));
        assertEquals("UNVERIFIED", out.get("pfEmployeeAmountRulesEvidence"));
        assertEquals("SYNTHETIC_DEMO_ONLY", out.get("pfEmployeeAmountRulesUse"));

        assertTrue(out.get("register") instanceof byte[], "the register stays a bytes JSON document");
        List<Map<String, Object>> items = JSON.readValue(new String((byte[]) out.get("register"),
                StandardCharsets.UTF_8), new TypeReference<>() { });
        Map<String, Map<String, Object>> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.get("employeeId").toString(), i));
        assertEquals(15000, ((Number) byId.get("A").get("pfEmployee")).intValue());
        assertEquals(125050, ((Number) byId.get("A").get("pfEmployeeBase")).intValue());
        assertEquals(300000, ((Number) byId.get("B").get("pfEmployee")).intValue());
        assertEquals(2500000, ((Number) byId.get("B").get("pfEmployeeBase")).intValue(), "capped at the ceiling");
        assertEquals(3000000, ((Number) byId.get("B").get("pfWages")).intValue(), "the HRIS wages are kept");
        assertEquals(Boolean.FALSE, byId.get("B").get("esiEmployeeEligible"));
        assertEquals(0, ((Number) byId.get("B").get("esiEmployee")).intValue());
        assertEquals(Boolean.TRUE, byId.get("A").get("esiEmployeeEligible"));
        assertEquals(1000, ((Number) byId.get("A").get("esiEmployee")).intValue());
        assertEquals(800, ((Number) byId.get("C").get("esiEmployee")).intValue());
        // exact sums: the items add up to the totals
        long pf = items.stream().mapToLong(i -> ((Number) i.get("pfEmployee")).longValue()).sum();
        long esi = items.stream().mapToLong(i -> ((Number) i.get("esiEmployee")).longValue()).sum();
        assertEquals(out.get("pfEmployeeAmount"), pf);
        assertEquals(out.get("esiEmployeeAmount"), esi);

        List<PackAuditEvent> audit = ((LoggingAuditSink) PackRuntime.audit()).timeline(
                engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pi.getId()).singleResult()
                        .getBusinessKey());
        PackAuditEvent pfEvent = audit.stream().filter(e -> "pfEmployeeAmount".equals(e.details().get("result")))
                .findFirst().orElseThrow();
        assertEquals("EACH", pfEvent.details().get("op"));
        assertEquals(List.of("A:15000", "B:300000:CAPPED", "C:12000"), pfEvent.details().get("itemValues"));
        PackAuditEvent esiEvent = audit.stream().filter(e -> "esiEmployeeAmount".equals(e.details().get("result")))
                .findFirst().orElseThrow();
        assertEquals(List.of("A:1000", "B:0:INELIGIBLE", "C:800"), esiEvent.details().get("itemValues"));
    }

    @Test
    void aRegisterGivenAsJsonTextIsReadTheSame() {
        Map<String, Object> v = vars(null);
        v.put("register", THREE);
        ProcessInstance pi = start("money-each", v);
        assertEquals(327000L, engine.getRuntimeService().getVariable(pi.getId(), "pfEmployeeAmount"));
        assertTrue(engine.getRuntimeService().getVariable(pi.getId(), "register") instanceof byte[]);
    }

    @Test
    void anEmptyOrMissingRegisterIsRefusedNeverAZeroTotal() {
        assertEquals("REGISTER_EMPTY", refusal("money-each", vars(register("[]"))));
        assertEquals("REGISTER_EMPTY", refusal("money-each", vars(null)));
    }

    @Test
    void aRepeatedOrMissingEmployeeAndNegativeWagesAreRefused() {
        assertEquals("ITEM_DUPLICATE", refusal("money-each", vars(register(
                "[{\"employeeId\":\"A\",\"pfWages\":1,\"esiWages\":1},{\"employeeId\":\"A\",\"pfWages\":2,"
                        + "\"esiWages\":2}]"))));
        assertEquals("ITEM_INVALID", refusal("money-each", vars(register(
                "[{\"pfWages\":1,\"esiWages\":1}]"))));
        assertEquals("ITEM_INVALID", refusal("money-each", vars(register(
                "[{\"employeeId\":\"A\",\"pfWages\":-1,\"esiWages\":1}]"))));
    }

    @Test
    void anUnverifiedRulesRowIsRefusedForProduction() {
        // decision 288: UNVERIFIED evidence may not be used for PRODUCTION (UNVERIFIED + SYNTHETIC_DEMO_ONLY computes)
        assertEquals("RULES_VERIFICATION_INCOMPLETE", refusal("money-each-unverified", vars(register(THREE))));
    }

    @Test
    void theBoundIsFieldLessOrEqualLimit() {
        assertEquals(new MoneyCalculationDelegate.Bound("pfWages", "pfWageCeiling"),
                MoneyCalculationDelegate.Bound.parse("cap", " pfWages <= pfWageCeiling "));
        assertEquals(null, MoneyCalculationDelegate.Bound.parse("cap", " "));
        for (String bad : List.of("pfWages<pfWageCeiling", "pfWages<=", "<=x", "a<=b<=c", "1a<=b")) {
            String m = assertThrows(ProcessEngineException.class,
                    () -> MoneyCalculationDelegate.Bound.parse("cap", bad)).getMessage();
            assertTrue(m.startsWith("CALCULATION_INVALID"), bad + ": " + m);
        }
    }
}
