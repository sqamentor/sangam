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
 * <p>Findings pinned here (see TESTING-GAP-REGISTER.md SG-27): arithmetic and comparisons inside FEEL are exact
 * decimals, but a non-whole FEEL number leaves the engine as {@code java.lang.Double}, and a non-whole decision-table
 * input reaches its input entries as a double (with or without {@code typeRef="double"}). Money must cross the DMN
 * boundary as integer minor units (long) or as a string. A throwing plugin function fails the evaluation. Null/missing/mistyped operands evaluate to null (no exception) in FEEL; in a decision
 * table they match no comparison rule but DO match a catch-all "-" rule. A declared typeRef rejects a mistyped value.
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
        dmn.append(BAND_PAISE).append(bandRupees("bandExact", null)).append(bandRupees("bandDouble", "double"))
                .append(NULL_STRICT).append(CATCH_ALL).append("</definitions>");
        engine.deploy("t1", "sg27-feel", Map.of("sg27.dmn", dmn.toString().getBytes(StandardCharsets.UTF_8),
                "feelRule.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
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
    void nonWholeFeelNumbersLeaveTheEngineAsDouble() {
        // FINDING: the Java value mapper returns Double for a non-whole number and Long for a whole one
        assertEquals(0.3d, lit("sum01_02"));
        Object big = lit("bigPlus");
        assertInstanceOf(Double.class, big);
        assertFalse(new BigDecimal("123456789012345678.92").equals(BigDecimal.valueOf((Double) big)),
                "precision is lost on the way out: money must leave DMN as long minor units or a string");
        assertInstanceOf(Double.class, lit("third"));
        assertEquals(9999999L, lit("rupeesToPaise"), "whole results come out as Long (exact)");
        assertEquals(Boolean.TRUE, lit("doubleIn"), "a Java double input 0.1 is read as decimal 0.1");
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
    void bandBoundariesInRupeesPassThroughDouble() {
        for (String id : new String[] {"bandExact", "bandDouble"}) {
            assertEquals("LOW", band(id, "amount", new BigDecimal("99999")), id);
            assertEquals("MID", band(id, "amount", new BigDecimal("99999.01")), id);
            assertEquals("MID", band(id, "amount", new BigDecimal("100000")), id);
            assertEquals("HIGH", band(id, "amount", new BigDecimal("100000.01")), id);
            assertEquals("HIGH", band(id, "amount", new BigDecimal("100001")), id);
            // FINDING: a non-whole decision-table input reaches the input entries as a double, with or without
            // typeRef="double" (205 corpus clauses use it): 100000.0000000000000001 becomes 100000.0, the wrong band.
            // Two-decimal rupee amounts below ~10^13 survive; anything finer or larger needs paise (long).
            assertEquals("MID", band(id, "amount", new BigDecimal("100000.0000000000000001")), id);
        }
    }

    // ------------------------------------------------------------------ null and error behaviour

    @Test
    void nullMissingAndMistypedOperandsEvaluateToNullNotAnException() {
        assertNull(lit("missingPlus"));
        assertNull(lit("nullVar"));
        assertEquals(Boolean.TRUE, lit("missingIsNull"));
        assertNull(lit("mismatch"));
        assertNull(lit("strPlusNum"));
        assertNull(lit("divZero"));
        assertNull(lit("unknownFn"));
        assertNull(lit("badDate"));
        assertEquals("low", lit("ifNull"), "FINDING: a null condition silently takes the else branch");
    }

    @Test
    void nullInputMatchesNoComparisonRuleButMatchesCatchAll() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", null);
        DmnDecisionResult strict = table("nullStrict", vars);
        assertTrue(strict.isEmpty(), "null input: no rule, empty result (not the wrong rule)");
        vars.clear();
        vars.put("delayDays", null);
        // FINDING: the corpus "<= 0" then "-" pattern sends a missing value to the catch-all rule
        assertEquals("penalty", table("catchAll", vars).getSingleEntry());
        assertEquals("none", table("catchAll", Map.of("delayDays", 0)).getSingleEntry());
    }

    @Test
    void declaredTypeRefRejectsMistypedInput() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> table("nullStrict", Map.of("amount", "abc")));
        StringBuilder chain = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append(" | ");
        }
        assertTrue(chain.toString().contains("abc"), chain.toString());
    }

    @Test
    void businessRuleTaskNullGivesNullResultAndMistypeFailsTheCommand() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", null);
        String pi = engine.startProcess("t1", "feelRule", "SG27-NULL", vars);
        var tasks = engine.queryTasks(new SangamEngineAdapter.TaskQuery("t1", null, null, pi, null, 0, 10));
        assertEquals(1, tasks.size());
        assertTrue(engine.processEngine().getRuntimeService().getVariables(pi).containsKey("ok"));
        assertNull(engine.processEngine().getRuntimeService().getVariable(pi, "ok"));
        assertThrows(RuntimeException.class,
                () -> engine.startProcess("t1", "feelRule", "SG27-BAD", Map.of("amount", "abc")),
                "a mistyped input fails the transaction (incident if async), never a silent rule");
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
                "FINDING: '=' on date and time also compares the zone; compare instants with < > or subtraction");
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
        assertNull(lit("customNull"));
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
