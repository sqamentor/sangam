// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import org.sangam.packs.core.DecimalFormula.FormulaException;

/**
 * Decision 139: the exact formula evaluator. Expected values are derived by hand from the decimal definitions, never
 * from a double.
 */
class DecimalFormulaTest {

    private static final UnaryOperator<BigDecimal> NONE = v -> v;

    private static Function<String, BigDecimal> vars(Object... kv) {
        Map<String, BigDecimal> m = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], new BigDecimal(kv[i + 1].toString()));
        }
        return m::get;
    }

    private static String code(Runnable r) {
        return assertThrows(FormulaException.class, r::run).code();
    }

    @Test
    void additionSubtractionAndMultiplicationAreExact() {
        DecimalFormula f = DecimalFormula.parse("a + b");
        assertEquals(new BigDecimal("0.3"), f.evaluate(vars("a", "0.1", "b", "0.2"), NONE));
        // exact product: the scale of a product is the sum of the scales (1 + 4), so 12.34560, numerically 12.3456
        assertEquals(new BigDecimal("12.34560"), DecimalFormula.parse("q * p").evaluate(vars("q", "1.5", "p", "8.2304"),
                NONE));
        assertEquals(new BigDecimal("-0.5"), DecimalFormula.parse("a - b").evaluate(vars("a", "1", "b", "1.5"), NONE));
    }

    @Test
    void precedenceParenthesesAndUnaryMinus() {
        assertEquals(new BigDecimal("7"), DecimalFormula.parse("1 + 2 * 3").evaluate(vars(), NONE));
        assertEquals(new BigDecimal("9"), DecimalFormula.parse("(1 + 2) * 3").evaluate(vars(), NONE));
        assertEquals(new BigDecimal("-6"), DecimalFormula.parse("-(1 + 2) * 2").evaluate(vars(), NONE));
        assertEquals(new BigDecimal("4"), DecimalFormula.parse("8 / 4 * 2").evaluate(vars(), NONE).stripTrailingZeros());
    }

    @Test
    void divisionUsesTheExplicitIntermediatePrecision() {
        BigDecimal third = DecimalFormula.parse("a / b").evaluate(vars("a", "1", "b", "3"), NONE);
        assertEquals(34, third.precision());
        assertEquals(new BigDecimal("0.3333333333333333333333333333333333"), third);
        // 100 / 3 * 3 at 34 digits is 99.99...99 (34 nines), not 100: rounding belongs to the policy, once, at the end
        BigDecimal back = DecimalFormula.parse("a / b * b").evaluate(vars("a", "100", "b", "3"), NONE);
        assertEquals(new BigDecimal("99.99999999999999999999999999999999"), back);
        assertEquals(new BigDecimal("100"), back.setScale(0, RoundingMode.HALF_UP));
    }

    @Test
    void roundAppliesThePolicyPerComponent() {
        // 15001 minor units x 12 % = 1800.12 each: rounded per component to 1800 + 1800 = 3600;
        // rounding the total instead (3600.24) also gives 3600, so use a case where they differ:
        // 1250 x 12 % = 150 exactly; 1254 x 12 % = 150.48 -> 150 per share, 300 in total; total first: 300.96 -> 301
        UnaryOperator<BigDecimal> halfUpWhole = v -> v.setScale(0, RoundingMode.HALF_UP);
        DecimalFormula perShare = DecimalFormula.parse("round(w * e / 100) + round(w * r / 100)");
        assertTrue(perShare.roundsComponents());
        assertEquals(new BigDecimal("300"), perShare.evaluate(vars("w", "1254", "e", "12", "r", "12"), halfUpWhole));
        DecimalFormula total = DecimalFormula.parse("w * (e + r) / 100");
        assertFalse(total.roundsComponents());
        assertEquals(new BigDecimal("301"), halfUpWhole.apply(total.evaluate(vars("w", "1254", "e", "12", "r", "12"),
                NONE)));
    }

    @Test
    void variablesAreListedInOrder() {
        assertEquals(List.of("refundAmount", "acceptedQty", "returnQty", "restockingFeePct"), List.copyOf(
                DecimalFormula.parse("refundAmount * acceptedQty / returnQty * (1 - restockingFeePct / 100)")
                        .variables()));
    }

    @Test
    void refusalsHaveCodes() {
        assertEquals("FORMULA_MISSING", code(() -> DecimalFormula.parse("a + b").evaluate(vars("a", "1"), NONE)));
        assertEquals("FORMULA_DIVISION_BY_ZERO", code(() -> DecimalFormula.parse("a / b")
                .evaluate(vars("a", "1", "b", "0.00"), NONE)));
        for (String bad : new String[] {"", " ", "a +", "(a", "a)", "1..2", "1.", ".5", "a ^ b", "round a", "a b",
                "${a}", "a,b", "1e3"}) {
            assertEquals("FORMULA_INVALID", code(() -> DecimalFormula.parse(bad)), bad);
        }
        assertEquals("FORMULA_INVALID", code(() -> DecimalFormula.parse("a".repeat(501))));
        assertEquals("FORMULA_INVALID", code(() -> DecimalFormula.parse("(".repeat(60) + "1" + ")".repeat(60))));
    }
}
