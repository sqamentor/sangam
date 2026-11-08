// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.money;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    // ------------------------------------------------------------------ exponent per currency (0, 2, 3 digits)

    @ParameterizedTest
    @CsvSource({"INR,2", "USD,2", "EUR,2", "JPY,0", "KRW,0", "KWD,3", "BHD,3", "OMR,3"})
    void exponentComesFromIso4217(String ccy, int exp) {
        assertEquals(exp, Money.zero(ccy).exponent());
    }

    @Test
    void pseudoAndUnknownCurrenciesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Money.zero("XXX")); // no minor unit
        assertThrows(IllegalArgumentException.class, () -> Money.zero("ABC"));
    }

    // ------------------------------------------------------------------ API boundary: strict decimal strings

    @ParameterizedTest
    @CsvSource({"0.01,INR,1", "100000.00,INR,10000000", "100000,INR,10000000", "-5.5,INR,-550", "123,JPY,123",
            "1.234,KWD,1234", "1.230,KWD,1230", "99999.99,INR,9999999", "100000.01,INR,10000001"})
    void parsesExactly(String s, String ccy, long minor) {
        Money m = Money.parse(s, ccy);
        assertEquals(minor, m.minor());
        assertEquals(0, new BigDecimal(s).compareTo(m.toMajor()));
    }

    @ParameterizedTest
    @CsvSource({"0.001,INR", "100000.005,INR", "1.5,JPY", "1.2345,KWD", "1e3,INR", "12.,INR", "abc,INR", "' ',INR",
            "1.0E+2,INR", "0x10,INR", "NaN,INR", "Infinity,INR"})
    void rejectsExcessFractionDigitsAndNonPlainForms(String s, String ccy) {
        assertThrows(IllegalArgumentException.class, () -> Money.parse(s, ccy));
    }

    @Test
    void roundTripsTheApiString() {
        assertEquals("100000.00", Money.parse("100000", "INR").toPlainString());
        assertEquals("1.230", Money.parse("1.23", "KWD").toPlainString());
        assertEquals("7", Money.parse("7", "JPY").toPlainString());
        assertEquals("-0.01", Money.ofMinor(-1, "INR").toPlainString());
        Random r = new Random(398);
        for (int i = 0; i < 10_000; i++) {
            long minor = r.nextLong() / 4;
            Money m = Money.ofMinor(minor, "INR");
            assertEquals(m, Money.parse(m.toPlainString(), "INR"));
        }
    }

    // ------------------------------------------------------------------ overflow

    @Test
    void arithmeticIsOverflowChecked() {
        Money max = Money.ofMinor(Long.MAX_VALUE, "INR");
        assertThrows(ArithmeticException.class, () -> max.plus(Money.ofMinor(1, "INR")));
        assertThrows(ArithmeticException.class, () -> Money.ofMinor(Long.MIN_VALUE, "INR").minus(Money.ofMinor(1, "INR")));
        assertThrows(ArithmeticException.class, () -> Money.ofMinor(Long.MIN_VALUE, "INR").negate());
        assertThrows(ArithmeticException.class, () -> max.times(2));
        assertThrows(ArithmeticException.class, () -> Money.parse("92233720368547758.08", "INR")); // MAX + 1 paisa
        assertEquals(Long.MAX_VALUE, Money.parse("92233720368547758.07", "INR").minor());
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinor(1, "INR").plus(Money.ofMinor(1, "USD")));
    }

    // ------------------------------------------------------------------ band boundaries (major and minor units)

    @Test
    void bandBoundariesInMajorUnits() {
        Money band = Money.parse("100000", "INR");                 // 1,00,000
        assertTrue(Money.parse("99999", "INR").compareTo(band) < 0);
        assertEquals(0, Money.parse("100000.00", "INR").compareTo(band));
        assertTrue(Money.parse("100001", "INR").compareTo(band) > 0);
        assertTrue(Money.parse("99999.99", "INR").compareTo(band) < 0); // one paisa below
        assertTrue(Money.parse("100000.01", "INR").compareTo(band) > 0); // one paisa above
    }

    @Test
    void bandBoundariesInMinorUnits() {
        Money band = Money.ofMinor(100_000, "INR");                 // 1,00,000 paise = 1,000.00
        assertEquals(99_999, Money.ofMinor(99_999, "INR").minor());
        assertTrue(Money.ofMinor(99_999, "INR").compareTo(band) < 0);
        assertEquals(0, Money.parse("1000.00", "INR").compareTo(band));
        assertTrue(Money.ofMinor(100_001, "INR").compareTo(band) > 0);
        assertEquals("999.99", Money.ofMinor(99_999, "INR").toPlainString());
        assertEquals("1000.01", Money.ofMinor(100_001, "INR").toPlainString());
        assertEquals("100.001", Money.ofMinor(100_001, "KWD").toPlainString());
        assertEquals("100001", Money.ofMinor(100_001, "JPY").toPlainString());
    }

    // ------------------------------------------------------------------ rounding once

    @ParameterizedTest
    @CsvSource({"0.005,HALF_UP,1", "0.005,HALF_EVEN,0", "0.015,HALF_EVEN,2", "0.0049999,HALF_UP,0", "-0.005,HALF_UP,-1",
            "-0.005,HALF_EVEN,0", "99999.995,HALF_UP,10000000", "99999.994999,HALF_UP,9999999"})
    void roundsToMinorUnitsWithTheMode(String exact, RoundingMode mode, long minor) {
        assertEquals(minor, Money.round(new BigDecimal(exact), "INR", mode).minor());
    }

    @Test
    void roundsToACoarserScale() {
        assertEquals(15_700, Money.round(new BigDecimal("156.6"), "INR", RoundingMode.HALF_UP, 0).minor());
        assertEquals(100, Money.unit("INR", 0));
        assertEquals(1, Money.unit("INR", 2));
        assertEquals(1, Money.unit("INR", 5)); // never finer than the currency
        assertEquals(1, Money.unit("JPY", 2));
        assertEquals(10, Money.unit("KWD", 2));
    }

    // ------------------------------------------------------------------ largest remainder

    @Test
    void largestRemainderGoesToTheBiggestFractionWithLowerIndexTieBreak() {
        // 3 x 10.5 = 31.5 -> 32: floors 10,10,10 = 30, two extra units, all remainders equal -> indexes 0 and 1
        long[] p = Money.allocate(32, new BigDecimal[]{bd("10.5"), bd("10.5"), bd("10.5")});
        assertArrayEquals(new long[]{11, 11, 10}, p);
        // biggest remainder wins regardless of position
        assertArrayEquals(new long[]{1, 2, 1}, Money.allocate(4, new BigDecimal[]{bd("1.2"), bd("1.7"), bd("1.1")}));
        // deterministic: same input, same output
        assertArrayEquals(p, Money.allocate(32, new BigDecimal[]{bd("10.5"), bd("10.5"), bd("10.5")}));
    }

    @Test
    void allocationHandlesNegativesAndTargetsBelowTheFloorSum() {
        long[] p = Money.allocate(-3, new BigDecimal[]{bd("-1.5"), bd("-1.5")});
        assertEquals(-3, Arrays.stream(p).sum());
        long[] q = Money.allocate(5, new BigDecimal[]{bd("3.9"), bd("3.1")}); // floors 3+3 = 6 > 5: take from smallest remainder
        assertArrayEquals(new long[]{3, 2}, q);
        assertThrows(IllegalArgumentException.class, () -> Money.allocate(1, new BigDecimal[0]));
    }

    /** Property: for many random line sets, Σ allocated == rounded total, each line within one unit of its exact value. */
    @Test
    void propertySumOfAllocatedLinesEqualsTheTotal() {
        Random r = new Random(20261108);
        String[] ccys = {"INR", "JPY", "KWD", "USD"};
        RoundingMode[] modes = {RoundingMode.HALF_UP, RoundingMode.HALF_EVEN, RoundingMode.DOWN, RoundingMode.CEILING};
        for (int run = 0; run < 20_000; run++) {
            String ccy = ccys[r.nextInt(ccys.length)];
            int n = 1 + r.nextInt(12);
            List<BigDecimal> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                // full precision: up to 7 decimals, values from -1,00,000 to 10,00,00,000
                long unscaled = (long) (r.nextInt(1_000_000_000)) * (r.nextInt(100) + 1) - 1_000_000_000L * (r.nextInt(4) == 0 ? 1 : 0);
                lines.add(BigDecimal.valueOf(unscaled, r.nextInt(8)));
            }
            int scale = r.nextInt(4) - 1; // -1 = currency exponent, 0, 1, 2
            RoundingMode mode = modes[r.nextInt(modes.length)];
            Money.Rounded doc = Money.roundDocument(lines, ccy, mode, scale);
            assertEquals(n, doc.lines().size());
            assertEquals(doc.total(), Money.sum(doc.lines(), ccy), "sum of lines == total, run " + run);
            assertEquals(Money.round(doc.exactTotal(), ccy, mode, scale), doc.total(), "total rounded once, run " + run);
            long unit = Money.unit(ccy, scale);
            for (int i = 0; i < n; i++) {
                long minor = doc.lines().get(i).minor();
                assertEquals(0, minor % unit, "line on the rounding grid");
                BigDecimal diff = doc.lines().get(i).toMajor().subtract(lines.get(i)).abs();
                assertTrue(diff.compareTo(BigDecimal.valueOf(unit, doc.total().exponent()).multiply(BigDecimal.valueOf(2))) < 0,
                        "line " + i + " within two units of its exact value, run " + run);
            }
        }
    }

    @Test
    void splitIsExactForAnyWeights() {
        Random r = new Random(7);
        for (int run = 0; run < 5_000; run++) {
            Money total = Money.ofMinor(r.nextLong() / 1_000, "INR");
            long[] w = new long[1 + r.nextInt(9)];
            for (int i = 0; i < w.length; i++) {
                w[i] = r.nextInt(1_000);
            }
            w[0] += 1;
            assertEquals(total, Money.sum(total.split(w), "INR"));
        }
        assertEquals(List.of(Money.ofMinor(34, "INR"), Money.ofMinor(33, "INR"), Money.ofMinor(33, "INR")),
                Money.ofMinor(100, "INR").split(1, 1, 1));
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
