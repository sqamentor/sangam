// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sangam.core.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SG-20: a depreciation schedule is one document; Σ period charges == cost - residual exactly, in minor units. */
class DepreciationMoneyTest {

    static DepreciationCalculator.Inputs in(String method, String cost, String residual, int life, String conv) {
        return new DepreciationCalculator.Inputs(method, Money.parse(cost, "INR"), Money.parse(residual, "INR"), life, null,
                null, conv, LocalDate.of(2026, 4, 17), null);
    }

    static Money charged(List<DepreciationCalculator.Line> s) {
        return Money.sum(s.stream().map(DepreciationCalculator.Line::charge).toList(), "INR");
    }

    /**
     * 1,00,000.00 over 7 months straight-line = 14,285.714285...: per-period rounding gives 14,285.71 x 6 and the last
     * period absorbs the drift (14,285.74). The schedule now spreads the paise by largest remainder.
     */
    @Test
    void straightLineSumsExactlyAndSpreadsThePaise() {
        List<DepreciationCalculator.Line> s = DepreciationCalculator.schedule(in("STRAIGHT_LINE", "100000", "0", 7, null));
        assertEquals(7, s.size());
        assertEquals(Money.parse("100000", "INR"), charged(s));
        for (DepreciationCalculator.Line l : s) {
            long c = l.charge().minor();
            assertTrue(c == 1_428_571 || c == 1_428_572, l.toString());
        }
        assertEquals(Money.zero("INR"), s.get(s.size() - 1).closing());
    }

    @ParameterizedTest
    @CsvSource({"STRAIGHT_LINE,FULL_MONTH", "STRAIGHT_LINE,HALF_MONTH", "STRAIGHT_LINE,DAILY", "DECLINING_BALANCE,FULL_MONTH",
            "SUM_OF_YEARS,FULL_MONTH", "SUM_OF_YEARS,DAILY", "DECLINING_BALANCE,HALF_MONTH"})
    void propertyScheduleSumsToTheDepreciableAmount(String method, String conv) {
        Random r = new Random(method.hashCode() * 31L + conv.hashCode());
        for (int run = 0; run < 500; run++) {
            long cost = 1 + Math.abs(r.nextLong() % 10_000_000_000L);
            long residual = Math.abs(r.nextLong() % (cost + 1));
            int life = 1 + r.nextInt(240);
            DepreciationCalculator.Inputs i = new DepreciationCalculator.Inputs(method, Money.ofMinor(cost, "INR"),
                    Money.ofMinor(residual, "INR"), life, null, null, conv, LocalDate.of(2026, 1 + r.nextInt(12), 1 + r.nextInt(28)), null);
            List<DepreciationCalculator.Line> s = DepreciationCalculator.schedule(i);
            if (cost == residual) {
                assertTrue(s.isEmpty());
                continue;
            }
            assertEquals(Money.ofMinor(cost - residual, "INR"), charged(s), method + " run " + run);
            assertEquals(Money.ofMinor(residual, "INR"), s.get(s.size() - 1).closing(), "closes at the residual value");
            for (DepreciationCalculator.Line l : s) {
                assertTrue(l.charge().signum() >= 0, "no negative charge");
            }
        }
    }

    @Test
    void unitsOfProductionReachesTheBaseExactly() {
        Map<YearMonth, BigDecimal> units = new TreeMap<>();
        units.put(YearMonth.of(2026, 4), new BigDecimal("1"));
        units.put(YearMonth.of(2026, 5), new BigDecimal("1"));
        units.put(YearMonth.of(2026, 6), new BigDecimal("1"));
        DepreciationCalculator.Inputs i = new DepreciationCalculator.Inputs("UNITS_OF_PRODUCTION", Money.parse("100", "INR"),
                Money.zero("INR"), 0, null, new BigDecimal("3"), null, LocalDate.of(2026, 4, 1), units);
        List<DepreciationCalculator.Line> s = DepreciationCalculator.schedule(i);
        assertEquals(Money.parse("100", "INR"), charged(s));
        assertEquals(List.of(Money.parse("33.33", "INR"), Money.parse("33.34", "INR"), Money.parse("33.33", "INR")),
                s.stream().map(DepreciationCalculator.Line::charge).toList());
    }

    @Test
    void bookValueAndThreeDigitCurrency() {
        DepreciationCalculator.Inputs i = new DepreciationCalculator.Inputs("STRAIGHT_LINE", Money.parse("1.000", "KWD"),
                Money.zero("KWD"), 3, null, null, null, LocalDate.of(2026, 1, 1), null);
        assertEquals(Money.parse("0.667", "KWD"), DepreciationCalculator.bookValue(i, LocalDate.of(2026, 1, 31)));
        assertEquals(Money.parse("0.333", "KWD"), DepreciationCalculator.accumulated(i, LocalDate.of(2026, 1, 31)));
        assertEquals(Money.zero("KWD"), DepreciationCalculator.bookValue(i, LocalDate.of(2026, 3, 31)));
        assertEquals(Money.parse("0.33", "INR").toMajor().setScale(2, RoundingMode.UNNECESSARY), Money.parse("0.33", "INR").toMajor());
    }
}
