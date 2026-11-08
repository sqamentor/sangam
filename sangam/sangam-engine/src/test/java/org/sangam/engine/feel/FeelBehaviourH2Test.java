// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.dmn.feel.impl.scala.function.CustomFunction;
import org.operaton.bpm.dmn.feel.impl.scala.function.FeelCustomFunctionProvider;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.sangam.core.engine.SangamEngineAdapter;
import org.sangam.core.plugin.DmnFunctionProvider;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * SG-27: what the kept FEEL engine (org.camunda.feel:feel-engine 1.22.1 behind operaton-engine-dmn 2.2.0) actually
 * does when decisions run through Sangam's engine (SangamEngineFactory, H2). These tests pin the behaviour the
 * owner exception was granted on; a FEEL upgrade that changes any of it fails here and must be re-verified.
 *
 * <p>Findings pinned here (see TESTING-GAP-REGISTER.md SG-27), now under the Sangam FEEL policy ADR-D-26
 * ({@link SangamFeel}): arithmetic and comparisons inside FEEL are exact decimals, and since SG-33 they stay exact
 * across the DMN boundary: a non-whole FEEL number leaves the engine as {@code BigDecimal} (whole: {@code Long}),
 * decision-table inputs are compared exactly, {@code typeRef="number"} is exact and {@code typeRef="double"} on money
 * is refused at deploy time. Since SG-34 evaluation is strict: a missing variable, a null or mistyped operand,
 * division by zero, an unknown function or an invalid date fails the decision instead of yielding null, and a null
 * decision-table input is an error unless a rule tests it with {@code null} (a catch-all "-" no longer catches it).
 * A genuinely null value ({@code x = null}, an explicit null rule) still works. SG-35: {@code =} on zoned date-times
 * still compares the zone (pinned engine; spec says instants), flagged by a deploy-time lint.
 */
class FeelBehaviourH2Test {

    private static OperatonEngineAdapter engine;

    /** Literal-expression decisions, one per FEEL expression under test (deployed once). */
    private static final Map<String, String> LITERALS = new LinkedHashMap<>();

    static {
        LITERALS.put("sum01_02", "0.1 + 0.2");
        LITERALS.put("eq01_02", "0.1 + 0.2 = 0.3");
        LITERALS.put("sumList", "string(sum([0.1, 0.2, 0.3]))");
        LITERALS.put("bigPlus", "big + 0.01");
        LITERALS.put("bigPlusStr", "string(big + 0.01)");
        LITERALS.put("bigGt", "big > 100000");
        LITERALS.put("third", "10 / 3");
        LITERALS.put("thirdDec", "string(decimal(10 / 3, 2))");
        LITERALS.put("roundHalfUp", "string(round half up(2.345, 2))");
        LITERALS.put("rupeesToPaise", "rupees * 100");
        LITERALS.put("doubleIn", "dbl + 0.2 = 0.3");
        LITERALS.put("missingPlus", "missing + 1");
        LITERALS.put("nullVar", "nul");
        LITERALS.put("missingIsNull", "missing = null");
        LITERALS.put("nulIsNull", "nul = null");
        LITERALS.put("nulPlus", "nul + 1");
        LITERALS.put("ifNulCond", "if nul > 5 then \"high\" else \"low\"");
        LITERALS.put("customNullArg", "toPaise(nul)");
        LITERALS.put("sumOutputs", "0.1 + 0.2 + 100000.0000000000000001");
        LITERALS.put("mismatch", "\"abc\" < 100");
        LITERALS.put("strPlusNum", "\"5\" + 1");
        LITERALS.put("divZero", "1 / 0");
        LITERALS.put("unknownFn", "noSuchFunction(1)");
        LITERALS.put("badDate", "date(\"2026-02-30\")");
        LITERALS.put("ifNull", "if missing > 5 then \"high\" else \"low\"");
        LITERALS.put("datePlus", "date(\"2026-03-08\") + duration(\"P1D\")");
        LITERALS.put("kolkata", "date and time(\"2026-11-08T10:00:00@Asia/Kolkata\")");
        LITERALS.put("kolkataMinusUtc",
                "date and time(\"2026-11-08T10:00:00@Asia/Kolkata\") - date and time(\"2026-11-08T04:30:00@UTC\")");
        LITERALS.put("kolkataEqUtc",
                "date and time(\"2026-11-08T10:00:00@Asia/Kolkata\") = date and time(\"2026-11-08T04:30:00@UTC\")");
        LITERALS.put("kolkataBeforeUtc",
                "date and time(\"2026-11-08T10:00:00@Asia/Kolkata\") < date and time(\"2026-11-08T04:30:01@UTC\")");
        LITERALS.put("kolkataDay", "date and time(\"2026-11-08T23:30:00@Asia/Kolkata\").day");
        LITERALS.put("kolkataPlusDayOverUsDst",
                "date and time(\"2026-03-07T12:00:00@Asia/Kolkata\") + duration(\"P1D\")");
        LITERALS.put("nyFallBack", "date and time(\"2026-11-01T01:30:00@America/New_York\") + duration(\"PT1H\")");
        LITERALS.put("nySpringGap", "date and time(\"2026-03-08T02:30:00@America/New_York\")");
        LITERALS.put("nySpringEdge", "date and time(\"2026-03-08T01:59:59@America/New_York\") + duration(\"PT1S\")");
        LITERALS.put("dur", "duration(\"P1DT2H\")");
        LITERALS.put("ymDur", "years and months duration(date(\"2026-01-31\"), date(\"2026-03-01\"))");
        // built-in functions used by the pack corpus (min, max, floor, abs, not) and common string/list functions
        LITERALS.put("corpusFns", "min([3, 1]) = 1 and max([3, 1]) = 3 and floor(12.5) = 12 and abs(-2) = 2 and not(false)");
        LITERALS.put("ldCap", "string(min(400 * 0.0005, 0.1) * 1234567.89)");
        LITERALS.put("strFns", "upper case(\"abc\") + substring(\"kolkata\", 1, 3)");
        LITERALS.put("listFns", "list contains([1, 2, 3], 2) and count([1, 2]) = 2");
        // Sangam plugin FEEL functions (DmnFunctionProvider SPI, adapted like the server's PluginFeelEnginePlugin)
        LITERALS.put("customPaise", "toPaise(rupees)");
        LITERALS.put("customNull", "toPaise(missing)");
        LITERALS.put("customThrows", "boom(1)");
    }

    /** Band table modelled on the pack corpus (hitPolicy FIRST); amounts in paise (long, no typeRef). */
    private static final String BAND_PAISE = """
            <decision id="bandPaise" name="bandPaise"><decisionTable id="bp" hitPolicy="FIRST">
              <input id="bpi"><inputExpression id="bpie"><text>amountPaise</text></inputExpression></input>
              <output id="bpo" name="band" typeRef="string"/>
              <rule id="bp1"><inputEntry id="bp1i"><text>&lt; 9999900</text></inputEntry><outputEntry id="bp1o"><text>"A"</text></outputEntry></rule>
              <rule id="bp2"><inputEntry id="bp2i"><text>[9999900..10000000)</text></inputEntry><outputEntry id="bp2o"><text>"B"</text></outputEntry></rule>
              <rule id="bp3"><inputEntry id="bp3i"><text>10000000</text></inputEntry><outputEntry id="bp3o"><text>"C"</text></outputEntry></rule>
              <rule id="bp4"><inputEntry id="bp4i"><text>&gt; 10000000</text></inputEntry><outputEntry id="bp4o"><text>"D"</text></outputEntry></rule>
            </decisionTable></decision>
            """;

    /** Same bands in rupees, exact decimal input (no typeRef) vs typeRef="double" (as 205 corpus clauses use). */
    private static String bandRupees(String id, String typeRef) {
        String t = typeRef == null ? "" : " typeRef=\"" + typeRef + "\"";
        return """
            <decision id="%1$s" name="%1$s"><decisionTable id="%1$s_t" hitPolicy="FIRST">
              <input id="%1$s_i"><inputExpression id="%1$s_ie"%2$s><text>amount</text></inputExpression></input>
              <output id="%1$s_o" name="band" typeRef="string"/>
              <rule id="%1$s_1"><inputEntry id="%1$s_1i"><text>&lt;= 99999</text></inputEntry><outputEntry id="%1$s_1o"><text>"LOW"</text></outputEntry></rule>
              <rule id="%1$s_2"><inputEntry id="%1$s_2i"><text>(99999..100000]</text></inputEntry><outputEntry id="%1$s_2o"><text>"MID"</text></outputEntry></rule>
              <rule id="%1$s_3"><inputEntry id="%1$s_3i"><text>&gt; 100000</text></inputEntry><outputEntry id="%1$s_3o"><text>"HIGH"</text></outputEntry></rule>
            </decisionTable></decision>
            """.formatted(id, t);
    }

    /** No catch-all: a null input must match nothing. */
    private static final String NULL_STRICT = """
            <decision id="nullStrict" name="nullStrict"><decisionTable id="ns" hitPolicy="FIRST">
              <input id="nsi"><inputExpression id="nsie" typeRef="integer"><text>amount</text></inputExpression></input>
              <output id="nso" name="ok" typeRef="boolean"/>
              <rule id="ns1"><inputEntry id="ns1i"><text>&lt; 100</text></inputEntry><outputEntry id="ns1o"><text>true</text></outputEntry></rule>
              <rule id="ns2"><inputEntry id="ns2i"><text>&gt;= 100</text></inputEntry><outputEntry id="ns2o"><text>false</text></outputEntry></rule>
            </decisionTable></decision>
            """;

    /** Corpus pattern: comparison rule then a catch-all "-" rule. */
    private static final String CATCH_ALL = """
            <decision id="catchAll" name="catchAll"><decisionTable id="ca" hitPolicy="FIRST">
              <input id="cai"><inputExpression id="caie" typeRef="integer"><text>delayDays</text></inputExpression></input>
              <output id="cao" name="rule" typeRef="string"/>
              <rule id="ca1"><inputEntry id="ca1i"><text>&lt;= 0</text></inputEntry><outputEntry id="ca1o"><text>"none"</text></outputEntry></rule>
              <rule id="ca2"><inputEntry id="ca2i"><text>-</text></inputEntry><outputEntry id="ca2o"><text>"penalty"</text></outputEntry></rule>
            </decisionTable></decision>
            """;

    /** Corpus pattern with an explicit null rule: the author decided what a missing value means. */
    private static final String NULL_RULE = """
            <decision id="nullRule" name="nullRule"><decisionTable id="nr" hitPolicy="FIRST">
              <input id="nri"><inputExpression id="nrie" typeRef="integer"><text>delayDays</text></inputExpression></input>
              <output id="nro" name="rule" typeRef="string"/>
              <rule id="nr0"><inputEntry id="nr0i"><text>null</text></inputEntry><outputEntry id="nr0o"><text>"unknown"</text></outputEntry></rule>
              <rule id="nr1"><inputEntry id="nr1i"><text>&lt;= 0</text></inputEntry><outputEntry id="nr1o"><text>"none"</text></outputEntry></rule>
              <rule id="nr2"><inputEntry id="nr2i"><text>-</text></inputEntry><outputEntry id="nr2o"><text>"penalty"</text></outputEntry></rule>
            </decisionTable></decision>
            """;

    /** COLLECT table with exact number outputs: their sum must be exact. */
    private static final String FEES = """
            <decision id="fees" name="fees"><decisionTable id="fe" hitPolicy="COLLECT">
              <input id="fei"><inputExpression id="feie" typeRef="number"><text>amount</text></inputExpression></input>
              <output id="feo" name="fee" typeRef="number"/>
              <rule id="fe1"><inputEntry id="fe1i"><text>&gt;= 0</text></inputEntry><outputEntry id="fe1o"><text>0.1</text></outputEntry></rule>
              <rule id="fe2"><inputEntry id="fe2i"><text>&gt;= 0</text></inputEntry><outputEntry id="fe2o"><text>0.2</text></outputEntry></rule>
              <rule id="fe3"><inputEntry id="fe3i"><text>&gt;= 0</text></inputEntry><outputEntry id="fe3o"><text>amount * 0.0000000000000001</text></outputEntry></rule>
            </decisionTable></decision>
            """;

    /** Async business rule task over nullStrict: a failure becomes an incident on the task. */
    private static final String BPMN_ASYNC = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="feelRuleAsync" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="brt"/>
                <businessRuleTask id="brt" operaton:asyncBefore="true" operaton:decisionRef="nullStrict"
                                  operaton:decisionRefBinding="deployment"
                                  operaton:mapDecisionResult="singleEntry" operaton:resultVariable="ok"/>
                <sequenceFlow id="f2" sourceRef="brt" targetRef="ut"/>
                <userTask id="ut"/>
                <sequenceFlow id="f3" sourceRef="ut" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    /** Business rule task over nullStrict, then a user task so the mapped result can be read. */
    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="feelRule" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="brt"/>
                <businessRuleTask id="brt" operaton:decisionRef="nullStrict" operaton:decisionRefBinding="deployment"
                                  operaton:mapDecisionResult="singleEntry" operaton:resultVariable="ok"/>
                <sequenceFlow id="f2" sourceRef="brt" targetRef="ut"/>
                <userTask id="ut"/>
                <sequenceFlow id="f3" sourceRef="ut" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    @BeforeAll
    static void start() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:feelsg27;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setAllowMultipleEnginesPerJvm(true);
        settings.setEngineSchemaUpdate("true"); // empty in-memory database owned by this test
        Map<String, DmnFunctionProvider.DmnFunction> fns = new LinkedHashMap<>();
        fns.put("toPaise", DmnFunctionProvider.DmnFunction.of(args -> args.get(0) == null ? null
                : new BigDecimal(args.get(0).toString()).movePointRight(2).longValueExact(), "rupees"));
        fns.put("boom", DmnFunctionProvider.DmnFunction.of(args -> {
            throw new IllegalStateException("plugin failure");
        }, "x"));
        engine = SangamEngineFactory.build(ds, settings, "h2", List.of(new TestFunctionPlugin(fns)));

        StringBuilder dmn = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="sg27" name="sg27" namespace="t">
                """);
        LITERALS.forEach((id, expr) -> dmn.append("<decision id=\"").append(id).append("\" name=\"").append(id)
                .append("\"><variable name=\"r\"/><literalExpression><text>").append(xml(expr))
                .append("</text></literalExpression></decision>\n"));
        dmn.append(BAND_PAISE).append(bandRupees("bandExact", null)).append(bandRupees("bandNumber", "number"))
                .append(NULL_STRICT).append(CATCH_ALL).append(NULL_RULE).append(FEES).append("</definitions>");
        engine.deploy("t1", "sg27-feel", Map.of("sg27.dmn", dmn.toString().getBytes(StandardCharsets.UTF_8),
                "feelRule.bpmn", BPMN.getBytes(StandardCharsets.UTF_8),
                "feelRuleAsync.bpmn", BPMN_ASYNC.getBytes(StandardCharsets.UTF_8)));
    }

    @AfterAll
    static void stop() {
        if (engine != null) {
            engine.close();
        }
    }

    // ------------------------------------------------------------------ decimal precision

    @Test
    void decimalArithmeticIsExactInsideFeel() {
        assertEquals(Boolean.TRUE, lit("eq01_02"));
        assertEquals("0.6", lit("sumList"));
        assertEquals("123456789012345678.92", lit("bigPlusStr"), "18-digit amount + 0.01 is exact inside FEEL");
        assertEquals(Boolean.TRUE, lit("bigGt"));
        assertEquals("3.33", lit("thirdDec"));
        assertEquals("2.35", lit("roundHalfUp"));
        assertEquals("123456.789", lit("ldCap"), "corpus LD cap formula: min(400 * 0.0005, 0.1) * 1234567.89");
    }

    @Test
    void nonWholeFeelNumbersLeaveTheEngineAsExactBigDecimal() {
        // SG-33 (was: Double). SangamValueMapper: non-whole -> BigDecimal, whole -> Long, never Double
        Object sum = lit("sum01_02");
        assertInstanceOf(BigDecimal.class, sum);
        assertEquals(0, new BigDecimal("0.3").compareTo((BigDecimal) sum));
        Object big = lit("bigPlus");
        assertInstanceOf(BigDecimal.class, big);
        assertEquals(0, new BigDecimal("123456789012345678.92").compareTo((BigDecimal) big),
                "18-digit amount + 0.01 leaves the engine exactly");
        assertInstanceOf(BigDecimal.class, lit("third"));
        assertEquals(9999999L, lit("rupeesToPaise"), "whole results come out as Long (exact)");
        assertEquals(Boolean.TRUE, lit("doubleIn"), "a Java double input 0.1 is read as decimal 0.1");
        Object sumOut = lit("sumOutputs");
        assertEquals(0, new BigDecimal("100000.3000000000000001").compareTo((BigDecimal) sumOut));
    }

    @Test
    void decisionTableOutputsAreExactAndSumExactly() {
        List<Map<String, Object>> rows = table("fees", Map.of("amount", new BigDecimal("1"))).getResultList();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            Object fee = row.get("fee");
            assertFalse(fee instanceof Double, "no Double output: " + fee);
            total = total.add(fee instanceof BigDecimal bd ? bd : BigDecimal.valueOf(((Number) fee).longValue()));
        }
        assertInstanceOf(BigDecimal.class, rows.get(0).get("fee"));
        assertEquals(0, new BigDecimal("0.3000000000000001").compareTo(total), "0.1 + 0.2 + 1E-16 exactly");
    }

    @Test
    void bandBoundariesInMinorUnitsAreExact() {
        assertEquals("A", band("bandPaise", "amountPaise", 9999899L));
        assertEquals("B", band("bandPaise", "amountPaise", 9999900L));   // 99,999.00
        assertEquals("B", band("bandPaise", "amountPaise", 9999999L));   // 99,999.99
        assertEquals("C", band("bandPaise", "amountPaise", 10000000L));  // 1,00,000.00
        assertEquals("D", band("bandPaise", "amountPaise", 10000001L));  // 1,00,000.01
        assertEquals("D", band("bandPaise", "amountPaise", 10000100L));  // 1,00,001.00
    }

    @Test
    void bandBoundariesInRupeesAreExact() {
        // SG-33 (was: the input passed through double, so 100000.0000000000000001 landed in MID). No typeRef and
        // typeRef="number" (the migration target of the 176 pack files that used "double") are both exact.
        for (String id : new String[] {"bandExact", "bandNumber"}) {
            assertEquals("LOW", band(id, "amount", new BigDecimal("99999")), id);
            assertEquals("MID", band(id, "amount", new BigDecimal("99999.01")), id);
            assertEquals("MID", band(id, "amount", new BigDecimal("99999.99")), id);
            assertEquals("MID", band(id, "amount", new BigDecimal("100000")), id);
            assertEquals("HIGH", band(id, "amount", new BigDecimal("100000.01")), id);
            assertEquals("HIGH", band(id, "amount", new BigDecimal("100001")), id);
            assertEquals("HIGH", band(id, "amount", new BigDecimal("100000.0000000000000001")), id);
            assertEquals("MID", band(id, "amount", 100000L), id);
        }
        assertEquals("HIGH", band("bandNumber", "amount", "100000.0000000000000001"), "number accepts a decimal string exactly");
        assertEquals("MID", band("bandNumber", "amount", 100000.0d), "a double input is read by its decimal form");
    }

    @Test
    void moneyDoubleTypeRefIsRefusedAtDeployTime() {
        String dmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="bad" name="bad" namespace="t">
                """ + bandRupees("bandDouble", "double") + "</definitions>";
        RuntimeException e = assertThrows(RuntimeException.class, () -> engine.deploy("t1", "sg33-bad",
                Map.of("bad.dmn", dmn.getBytes(StandardCharsets.UTF_8))));
        assertTrue(chain(e).contains("SANGAM-DMN-MONEY-DOUBLE"), chain(e));
        assertEquals(0, engine.processEngine().getRepositoryService().createDecisionDefinitionQuery()
                .decisionDefinitionKey("bandDouble").count(), "nothing deployed");
    }

    // ------------------------------------------------------------------ null and error behaviour

    @Test
    void evaluationFailuresFailTheDecisionInsteadOfNull() {
        // SG-34 (was: each of these evaluated to null; ifNull silently took the else branch)
        for (String id : new String[] {"missingPlus", "missingIsNull", "nulPlus", "mismatch", "strPlusNum", "divZero",
                "unknownFn", "badDate", "ifNull", "ifNulCond", "customNull"}) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> lit(id), id);
            assertTrue(chain(e).contains("SANGAM-FEEL-STRICT"), id + ": " + chain(e));
        }
    }

    @Test
    void genuineNullValuesStillWork() {
        assertNull(lit("nullVar"), "a present variable holding null is a value, not a failure");
        assertEquals(Boolean.TRUE, lit("nulIsNull"));
        assertNull(lit("customNullArg"), "a plugin function may return null for a null argument");
    }

    @Test
    void nullInputIsAnErrorUnlessARuleTestsNull() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", null);
        // SG-34 (was: empty result)
        RuntimeException strict = assertThrows(RuntimeException.class, () -> table("nullStrict", vars));
        assertTrue(chain(strict).contains("SANGAM-DMN-NULL-INPUT"), chain(strict));
        Map<String, Object> delay = new HashMap<>();
        delay.put("delayDays", null);
        // SG-34 (was: "penalty" from the catch-all "-" rule)
        RuntimeException catchAll = assertThrows(RuntimeException.class, () -> table("catchAll", delay));
        assertTrue(chain(catchAll).contains("SANGAM-DMN-NULL-INPUT"), chain(catchAll));
        assertEquals("none", table("catchAll", Map.of("delayDays", 0)).getSingleEntry());
        assertEquals("penalty", table("catchAll", Map.of("delayDays", 3)).getSingleEntry());
        // an explicit null rule still works, and a null input is not compared by the other rules
        assertEquals("unknown", table("nullRule", delay).getSingleEntry());
        assertEquals("none", table("nullRule", Map.of("delayDays", 0)).getSingleEntry());
        assertEquals("penalty", table("nullRule", Map.of("delayDays", 3)).getSingleEntry());
    }

    @Test
    void declaredTypeRefRejectsMistypedInput() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> table("nullStrict", Map.of("amount", "abc")));
        assertTrue(chain(e).contains("abc"), chain(e));
    }

    @Test
    void businessRuleTaskNullInputFailsTheCommandWithNoDownstreamEffect() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", null);
        // SG-34 (was: the task completed with ok = null)
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> engine.startProcess("t1", "feelRule", "SG27-NULL", vars));
        assertTrue(chain(e).contains("SANGAM-DMN-NULL-INPUT"), chain(e));
        assertEquals(0, engine.processEngine().getRuntimeService().createProcessInstanceQuery()
                .processInstanceBusinessKey("SG27-NULL").count(), "rolled back: no instance, no task");
        assertThrows(RuntimeException.class,
                () -> engine.startProcess("t1", "feelRule", "SG27-BAD", Map.of("amount", "abc")),
                "a mistyped input fails the transaction (incident if async), never a silent rule");
    }

    @Test
    void asyncBusinessRuleTaskFailureIsAnIncidentAndCanBeRetriedAfterCorrection() {
        var rt = engine.processEngine().getRuntimeService();
        var mgmt = engine.processEngine().getManagementService();
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", null);
        String pi = engine.startProcess("t1", "feelRuleAsync", "SG34-INC", vars);
        var job = mgmt.createJobQuery().processInstanceId(pi).singleResult();
        for (int i = 0; i < 10 && mgmt.createJobQuery().jobId(job.getId()).singleResult().getRetries() > 0; i++) {
            assertThrows(RuntimeException.class, () -> mgmt.executeJob(job.getId()));
        }
        var incidents = rt.createIncidentQuery().processInstanceId(pi).list();
        assertEquals(1, incidents.size(), "incident on the business rule task");
        assertEquals("brt", incidents.get(0).getActivityId());
        assertTrue(incidents.get(0).getIncidentMessage().contains("SANGAM-DMN-NULL-INPUT"),
                incidents.get(0).getIncidentMessage());
        assertFalse(rt.getVariables(pi).containsKey("ok"), "no rule fired: no result variable");
        assertEquals(0, engine.processEngine().getTaskService().createTaskQuery().processInstanceId(pi).count(),
                "no downstream effect");
        // correction, then retry
        rt.setVariable(pi, "amount", 50);
        mgmt.setJobRetries(job.getId(), 1);
        mgmt.executeJob(job.getId());
        assertEquals(Boolean.TRUE, rt.getVariable(pi, "ok"));
        assertEquals(1, engine.processEngine().getTaskService().createTaskQuery().processInstanceId(pi).count());
        assertEquals(0, rt.createIncidentQuery().processInstanceId(pi).count(), "incident resolved");
    }

    // ------------------------------------------------------------------ date and time

    @Test
    void datesDurationsAndZones() {
        assertEquals(LocalDate.of(2026, 3, 9), lit("datePlus"));
        ZonedDateTime k = (ZonedDateTime) lit("kolkata");
        assertEquals(ZoneId.of("Asia/Kolkata"), k.getZone());
        assertEquals(java.time.Instant.parse("2026-11-08T04:30:00Z"), k.toInstant());
        assertEquals(Duration.ZERO, lit("kolkataMinusUtc"));
        assertEquals(Boolean.FALSE, lit("kolkataEqUtc"),
                "SG-35 (kept, deviation from DMN 1.3 time-line equality, DMN13-35): '=' also compares the zone in the pinned engine; deploy-time lint SANGAM-DMN-DATETIME-EQ; compare instants with < > or subtraction");
        assertEquals(Boolean.TRUE, lit("kolkataBeforeUtc"));
        assertEquals(8L, lit("kolkataDay"));
        assertEquals(Duration.ofHours(26), lit("dur"));
        assertEquals(java.time.Period.ofMonths(1), lit("ymDur"));
    }

    @Test
    void dstAdjacentTimes() {
        ZonedDateTime k = (ZonedDateTime) lit("kolkataPlusDayOverUsDst");
        assertEquals(ZonedDateTime.of(2026, 3, 8, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")), k, "IST has no DST");
        ZonedDateTime fall = (ZonedDateTime) lit("nyFallBack");
        assertEquals("2026-11-01T01:30-05:00[America/New_York]", fall.toString(), "PT1H is one real hour in overlap");
        ZonedDateTime gap = (ZonedDateTime) lit("nySpringGap");
        assertEquals("2026-03-08T03:30-04:00[America/New_York]", gap.toString(), "a time in the gap is moved forward");
        ZonedDateTime edge = (ZonedDateTime) lit("nySpringEdge");
        assertEquals("2026-03-08T03:00-04:00[America/New_York]", edge.toString());
    }

    // ------------------------------------------------------------------ functions

    @Test
    void builtInFunctionsUsedByTheCorpus() {
        assertEquals(Boolean.TRUE, lit("corpusFns"));
        assertEquals("ABCkol", lit("strFns"));
        assertEquals(Boolean.TRUE, lit("listFns"));
    }

    @Test
    void sangamPluginFunctions() {
        assertEquals(9999999L, lit("customPaise"));
        assertThrows(RuntimeException.class, () -> lit("customThrows"),
                "a throwing plugin function fails the evaluation (defined error, not a silent null)");
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> vars() {
        Map<String, Object> v = new HashMap<>();
        v.put("big", new BigDecimal("123456789012345678.91"));
        v.put("rupees", new BigDecimal("99999.99"));
        v.put("dbl", 0.1d);
        v.put("nul", null);
        return v;
    }

    private static Object lit(String id) {
        DmnDecisionResult r = engine.processEngine().getDecisionService().evaluateDecisionByKey(id)
                .decisionDefinitionTenantId("t1").variables(vars()).evaluate();
        return r.isEmpty() ? null : r.getSingleEntry();
    }

    private static DmnDecisionResult table(String id, Map<String, Object> vars) {
        return engine.processEngine().getDecisionService().evaluateDecisionByKey(id).decisionDefinitionTenantId("t1")
                .variables(vars).evaluate();
    }

    private static Object band(String id, String var, Object value) {
        return table(id, Map.of(var, value)).getSingleEntry();
    }

    private static String chain(Throwable e) {
        StringBuilder b = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            b.append(t.getMessage()).append(" | ");
        }
        return b.toString();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Mirrors sangam-server's PluginFeelEnginePlugin (DmnFunctionProvider SPI to FeelCustomFunctionProvider). */
    static final class TestFunctionPlugin extends AbstractProcessEnginePlugin {
        private final Map<String, DmnFunctionProvider.DmnFunction> functions;

        TestFunctionPlugin(Map<String, DmnFunctionProvider.DmnFunction> functions) {
            this.functions = functions;
        }

        @Override
        public void preInit(ProcessEngineConfigurationImpl cfg) {
            List<FeelCustomFunctionProvider> all = new ArrayList<>();
            if (cfg.getDmnFeelCustomFunctionProviders() != null) {
                all.addAll(cfg.getDmnFeelCustomFunctionProviders());
            }
            all.add(new FeelCustomFunctionProvider() {
                @Override
                public Optional<CustomFunction> resolveFunction(String name) {
                    DmnFunctionProvider.DmnFunction f = functions.get(name);
                    return f == null ? Optional.empty() : Optional.of(CustomFunction.create()
                            .setParams(f.params().toArray(new String[0])).setFunction(f.body()).build());
                }

                @Override
                public Collection<String> getFunctionNames() {
                    return functions.keySet();
                }
            });
            cfg.setDmnFeelCustomFunctionProviders(all);
        }
    }
}
