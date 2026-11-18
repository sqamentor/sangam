// SPDX-License-Identifier: Apache-2.0
package org.sangam.feel;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.dmn.engine.DmnEngine;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DMN engine gates of ADR-DMN-ENGINE (owner decision D1, 2026-11-18), on Sangam's production DMN configuration:
 * <ul>
 *   <li>HIT-8: COLLECT SUM / MIN / MAX are exact (SG-33). Operaton's own handlers sum through {@code double}.</li>
 *   <li>GAP-2: a decision table's {@code defaultOutputEntry} is the result when no rule matches (DMN 1.5, 8.2.11 /
 *       8.3.2). Operaton ignores it and returns no result.</li>
 *   <li>DAT-5: a built-in conversion of a variable holding an invalid string fails the decision (SG-34).</li>
 * </ul>
 */
class DmnGateRulesTest {

    private static final String NS = "https://www.omg.org/spec/DMN/20191111/MODEL/";

    private static String table(String hitPolicy, String aggregation, String outType, String defaultOut, String... rules) {
        StringBuilder r = new StringBuilder();
        int n = 0;
        for (String rule : rules) {
            String[] p = rule.split("\\|");
            r.append("<rule id=\"r").append(n).append("\"><inputEntry id=\"r").append(n).append("i\"><text>")
                    .append(p[0].replace(">", "&gt;").replace("<", "&lt;")).append("</text></inputEntry><outputEntry id=\"r")
                    .append(n).append("o\"><text>").append(p[1]).append("</text></outputEntry></rule>");
            n++;
        }
        String dflt = defaultOut == null ? "" : "<defaultOutputEntry id=\"dflt\"><text>" + defaultOut + "</text></defaultOutputEntry>";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"" + NS + "\" id=\"g\" name=\"g\" namespace=\"https://sangam.org/gate\">"
                + "<decision id=\"d\" name=\"d\"><decisionTable id=\"t\" hitPolicy=\"" + hitPolicy + "\""
                + (aggregation == null ? "" : " aggregation=\"" + aggregation + "\"") + ">"
                + "<input id=\"in\"><inputExpression id=\"ie\" typeRef=\"number\"><text>x</text></inputExpression></input>"
                + "<output id=\"out\" name=\"o\" typeRef=\"" + outType + "\">" + dflt + "</output>" + r
                + "</decisionTable></decision></definitions>";
    }

    private static DmnDecisionResult eval(String xml, Object x) {
        DmnEngine e = SangamFeel.dmnEngineConfiguration().buildEngine();
        DmnDecision d = e.parseDecisions(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).get(0);
        Map<String, Object> vars = new HashMap<>();
        vars.put("x", x);
        return e.evaluateDecision(d, vars);
    }

    private static Object single(String xml, Object x) {
        DmnDecisionResult r = eval(xml, x);
        return r.isEmpty() ? null : r.getSingleResult().getSingleEntry();
    }

    // ---------------------------------------------------------------- HIT-8

    @Test
    void collectSumIsExact() {
        Object sum = single(table("COLLECT", "SUM", "number", null, ">0|0.1", ">5|0.2"), 10);
        assertInstanceOf(BigDecimal.class, sum, "never a binary double (SG-33)");
        assertEquals(0, new BigDecimal("0.3").compareTo((BigDecimal) sum));
    }

    @Test
    void collectSumOfMoneyKeepsEveryDigit() {
        Object sum = single(table("COLLECT", "SUM", "number", null, ">0|12345678901234567.89", ">5|0.01"), 10);
        assertEquals(0, new BigDecimal("12345678901234567.90").compareTo((BigDecimal) sum));
    }

    @Test
    void wholeCollectResultsAreLongLikeEveryOtherFeelNumber() {
        assertEquals(30L, single(table("COLLECT", "SUM", "number", null, ">0|10", ">5|20"), 10));
        assertEquals(10L, single(table("COLLECT", "MIN", "number", null, ">0|10", ">5|20"), 10));
    }

    @Test
    void collectMinAndMaxAreExact() {
        Object min = single(table("COLLECT", "MIN", "number", null, ">0|0.30000000000000001", ">5|0.3"), 10);
        Object max = single(table("COLLECT", "MAX", "number", null, ">0|0.30000000000000001", ">5|0.3"), 10);
        assertEquals(0, new BigDecimal("0.3").compareTo((BigDecimal) min));
        assertEquals(0, new BigDecimal("0.30000000000000001").compareTo((BigDecimal) max));
    }

    @Test
    void collectAggregationWithoutMatchGivesNoResult() {
        assertTrue(eval(table("COLLECT", "SUM", "number", null, ">0|0.1"), -1).isEmpty());
    }

    @Test
    void collectAggregationOfANonNumberFails() {
        assertThrows(RuntimeException.class, () -> eval(table("COLLECT", "SUM", "string", null, ">0|\"a\"", ">5|\"b\""), 10));
    }

    @Test
    void collectCountStaysAWholeNumber() {
        assertEquals(2L, ((Number) single(table("COLLECT", "COUNT", "number", null, ">0|1", ">5|1"), 10)).longValue());
    }

    // ---------------------------------------------------------------- GAP-2

    @Test
    void defaultOutputEntryIsTheResultWhenNoRuleMatches() {
        assertEquals("z", single(table("UNIQUE", null, "string", "\"z\"", ">0|\"a\""), -1));
    }

    @Test
    void defaultOutputEntryIsNotUsedWhenARuleMatches() {
        assertEquals("a", single(table("UNIQUE", null, "string", "\"z\"", ">0|\"a\""), 1));
    }

    @Test
    void defaultOutputEntryIsTypedByItsOutput() {
        Object v = single(table("FIRST", null, "number", "0.5", ">0|1"), -1);
        assertEquals(0, new BigDecimal("0.5").compareTo((BigDecimal) v));
    }

    @Test
    void defaultOutputEntryFeedsACollectAggregation() {
        Object v = single(table("COLLECT", "SUM", "number", "0", ">0|1"), -1);
        assertEquals(0L, v);
    }

    @Test
    void defaultOutputForACollectListIsAOneElementList() {
        DmnDecisionResult r = eval(table("COLLECT", null, "string", "\"none\"", ">0|\"a\""), -1);
        assertEquals(List.of("none"), r.collectEntries("o"));
    }

    @Test
    void aDefaultOutputEntryThatReadsAVariableIsRefusedAtParse() {
        assertThrows(RuntimeException.class, () -> eval(table("UNIQUE", null, "number", "x + 1", ">0|1"), -1),
                "only constant default output entries are supported");
    }

    @Test
    void withoutADefaultOutputEntryNoRuleMeansNoResult() {
        assertNull(single(table("UNIQUE", null, "string", null, ">0|\"a\""), -1));
    }

    // ---------------------------------------------------------------- DAT-5 (regression for STANDARDS-PROFILE)

    @Test
    void aBuiltInConversionOfAnInvalidVariableStringFails() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"" + NS + "\" id=\"g\" name=\"g\" namespace=\"https://sangam.org/gate\">"
                + "<decision id=\"d\" name=\"d\"><variable name=\"d\"/><literalExpression id=\"l\"><text>date(s)</text></literalExpression></decision></definitions>";
        DmnEngine e = SangamFeel.dmnEngineConfiguration().buildEngine();
        DmnDecision d = e.parseDecisions(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).get(0);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> e.evaluateDecision(d, Map.of("s", "2024-13-45")));
        assertTrue(String.valueOf(ex.getMessage()).contains("SANGAM-FEEL-STRICT"), ex.getMessage());
        assertEquals(java.time.LocalDate.of(2024, 1, 31),
                e.evaluateDecision(d, Map.of("s", "2024-01-31")).getSingleResult().getSingleEntry());
    }
}
