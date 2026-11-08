// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.programme.config.ProgrammeConfig;
import org.sangam.programme.indicator.FormulaEvaluator;
import org.sangam.programme.returns.ValidationEngine;
import org.sangam.programme.returns.ValidationEngine.Finding;

/** SL-40: exact arithmetic and strict (ADR-D-26) null and error behaviour of FormulaEvaluator and ValidationEngine. */
class FormulaAndValidationEngineTest {

    private static BigDecimal eval(String expr, Map<String, String> vals) {
        return FormulaEvaluator.evaluate(expr, k -> vals.containsKey(k) ? new BigDecimal(vals.get(k)) : null);
    }

    @Test
    void additionSubtractionAndMultiplicationAreExact() {
        // 17+ significant digits: DECIMAL64 used to round these.
        assertEquals(new BigDecimal("12345678901234567.90"), eval("a + 0.01", Map.of("a", "12345678901234567.89")));
        assertEquals(new BigDecimal("0.3"), eval("0.1 + 0.2", Map.of()));
        assertEquals(new BigDecimal("99999999999999999.99"), eval("a - 0.01", Map.of("a", "100000000000000000.00")));
        assertEquals(new BigDecimal("152415787532388367504942236884722755800955129"),
                eval("a * a", Map.of("a", "12345678901234567890123")));
    }

    @Test
    void divisionIsExactWhenItTerminatesAndOtherwise34Digits() {
        assertEquals(new BigDecimal("2.5"), eval("10 / 4", Map.of()));
        assertEquals(new BigDecimal("0.3333333333333333333333333333333333"), eval("1 / 3", Map.of()));
        assertEquals(34, eval("2 / 3", Map.of()).precision());
    }

    @Test
    void roundIsHalfUpAndRefusesFractionalOrMissingDecimals() {
        assertEquals(new BigDecimal("2.35"), eval("round(2.345, 2)", Map.of()));
        assertEquals(new BigDecimal("-3"), eval("round(-2.5)", Map.of()));
        assertNull(eval("round(1.25, d)", Map.of()), "missing decimals is unknown, not 0 decimals");
        assertThrows(ProgrammeException.class, () -> eval("round(1.25, 1.5)", Map.of()));
        assertThrows(ProgrammeException.class, () -> eval("round(1.25, 99)", Map.of()));
    }

    @Test
    void missingValuesAndDivisionByZeroAreUnknownExceptInCoalesceAndDiv() {
        assertNull(eval("a + 1", Map.of()));
        assertNull(eval("a / b", Map.of("a", "1", "b", "0")));
        assertEquals(new BigDecimal("7"), eval("div(a, b, 7)", Map.of("a", "1", "b", "0")));
        assertEquals(new BigDecimal("5"), eval("coalesce(a, 5)", Map.of()));
        assertNull(FormulaEvaluator.test("a > 1", k -> null));
        // three-valued logic: a known false side decides AND, a known true side decides OR
        assertEquals(BigDecimal.ZERO, eval("a > 1 && 0", Map.of()));
        assertEquals(BigDecimal.ONE, eval("a > 1 || 1", Map.of()));
        assertNull(eval("a > 1 && 1", Map.of()));
    }

    @Test
    void syntaxErrorsAndLimitsAreRefusedNotIgnored() {
        for (String bad : List.of("", "1 +", "(1", "foo(1)", "1 2", "1..2", "a $ b")) {
            assertThrows(ProgrammeException.class, () -> eval(bad, Map.of()), bad);
        }
        assertThrows(ProgrammeException.class, () -> eval("(".repeat(70) + "1" + ")".repeat(70), Map.of()));
        assertThrows(ProgrammeException.class, () -> eval("1+".repeat(1000) + "1", Map.of()));
        assertEquals(Set.of("a", "prev.a", "target.b"), FormulaEvaluator.references("a - prev.a + max(target.b, 1)"));
    }

    // ---------------------------------------------------------------- ValidationEngine

    private static final String CONFIG = """
            {"indicators":[
               {"key":"spend","name":"Spend","unit":"INR","decimals":2,"cumulative":true,
                "validation":{"min":0,"max":"1000000000000000.00","maxOfTarget":"1.5"}},
               {"key":"count","name":"Count","unit":"NO","validation":{"integer":true}},
               {"key":"opt","name":"Optional","unit":"NO"}],
             "returns":[{"key":"monthly","name":"Monthly","indicators":["spend","count",{"key":"opt","required":false}],
               "rules":[
                 {"expr":"spend >= count","code":"SPEND_LT_COUNT","indicator":"spend"},
                 {"expr":"spend >= prev.spend","code":"HIST","severity":"WARNING"},
                 {"expr":"opt <= count","code":"OPT_LE_COUNT"},
                 {"expr":"spend / count > 0","code":"PER_UNIT"},
                 {"expr":"opt >= 0","code":"OPT_SKIP","whenMissing":"skip"}]}]}
            """;

    private static List<Finding> validate(Map<String, String> vals, Map<String, String> prev, Map<String, String> targets) {
        ProgrammeConfig cfg = ProgrammeConfig.parse(CONFIG);
        return new ValidationEngine(null).validate(null, "t1", cfg, cfg.returns().get("monthly"), dec(vals), Map.of(),
                dec(prev), dec(targets), Map.of(), false);
    }

    private static Map<String, BigDecimal> dec(Map<String, String> m) {
        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, new BigDecimal(v)));
        return out;
    }

    private static List<String> codes(List<Finding> f) {
        return f.stream().map(Finding::code).toList();
    }

    @Test
    void builtInRulesUseExactComparison() {
        List<Finding> f = validate(Map.of("spend", "1000000000000000.01", "count", "2.0", "opt", "1"), Map.of(), Map.of());
        assertEquals(List.of("MAX"), codes(f), "0.01 above the maximum is caught, 2.0 is a whole number: " + f);
        assertEquals(List.of("DECIMALS"), codes(validate(Map.of("spend", "10.001", "count", "2", "opt", "1"), Map.of(), Map.of())));
        assertEquals(List.of("INTEGER"), codes(validate(Map.of("spend", "10", "count", "2.5", "opt", "1"), Map.of(), Map.of())));
        assertEquals(List.of("MIN", "SPEND_LT_COUNT", "RULE_UNDETERMINED"),
                codes(validate(Map.of("spend", "-0.01", "count", "0", "opt", "0"), Map.of(), Map.of())),
                "spend / 0 is undetermined, not a pass");
    }

    @Test
    void historyAndTargetRulesWarn() {
        List<Finding> f = validate(Map.of("spend", "150.01", "count", "1", "opt", "1"), Map.of("spend", "151"),
                Map.of("spend", "100"));
        assertEquals(List.of("DECREASED", "ABOVE_TARGET", "HIST"), codes(f));
        assertEquals(ValidationEngine.Severity.ERROR, f.get(0).severity(), "cumulative indicators may not decrease");
        assertEquals(ValidationEngine.Severity.WARNING, f.get(1).severity());
        assertEquals(ValidationEngine.Severity.WARNING, f.get(2).severity());
        assertFalse(ValidationEngine.hasErrors(validate(Map.of("spend", "150", "count", "1", "opt", "1"),
                Map.of("spend", "149.99"), Map.of("spend", "100"))), "150 = 1.5 x target is allowed");
    }

    @Test
    void missingCurrentValueMakesARuleFailInsteadOfPassing() {
        List<Finding> f = validate(Map.of("spend", "10", "count", "1"), Map.of(), Map.of());
        assertEquals(List.of("RULE_UNDETERMINED"), codes(f), "opt <= count cannot be decided; whenMissing=skip rule passes");
        assertTrue(f.get(0).message().contains("missing opt"), f.get(0).message());
        assertTrue(ValidationEngine.hasErrors(f));
        // first period: prev.spend is missing, which is legitimate history, so HIST is skipped by default
        assertFalse(codes(validate(Map.of("spend", "10", "count", "1", "opt", "1"), Map.of(), Map.of())).contains("HIST"));
        // a required value that is missing is reported as REQUIRED as well
        assertTrue(codes(validate(Map.of("count", "1", "opt", "1"), Map.of(), Map.of())).contains("REQUIRED"));
    }

    @Test
    void ruleOptionsAreCheckedAtConfigurationTime() {
        assertThrows(ProgrammeException.class, () -> ProgrammeConfig.parse(CONFIG.replace("\"severity\":\"WARNING\"",
                "\"severity\":\"INFO\"")));
        assertThrows(ProgrammeException.class, () -> ProgrammeConfig.parse(CONFIG.replace("\"whenMissing\":\"skip\"",
                "\"whenMissing\":\"ignore\"")));
        assertThrows(ProgrammeException.class, () -> ProgrammeConfig.parse(CONFIG.replace("spend >= count",
                "spend >= unknown_key")));
    }
}
