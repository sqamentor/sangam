// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * The one money type of Sangam (SD-398, SG-20): an exact count of ISO 4217 <b>minor units</b> ({@code long}) plus the
 * currency. The exponent comes from {@link Currency#getDefaultFractionDigits()} (INR/USD 2, JPY 0, KWD/BHD 3), so the
 * type knows no country.
 *
 * <ul>
 *   <li>No floating point anywhere; every operation is exact and overflow-checked ({@code Math.*Exact}).</li>
 *   <li>{@link #parse}/{@link #ofMajor} are the API boundary: a value with more fraction digits than the currency
 *       allows is <b>rejected</b>, never silently rounded.</li>
 *   <li>The only place a non-representable amount (a tariff line, interest, an FX conversion computed at full
 *       precision) becomes money is {@link #round}, called once per document total; the total is spread back onto
 *       the lines with {@link #allocate} (largest remainder), so the lines always sum to the total exactly.</li>
 * </ul>
 */
public final class Money implements Comparable<Money> {

    private final long minor;
    private final Currency currency;

    private Money(long minor, Currency currency) {
        this.minor = minor;
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    // ------------------------------------------------------------------ construction

    public static Money ofMinor(long minor, String currency) {
        return new Money(minor, currency(currency));
    }

    public static Money zero(String currency) {
        return ofMinor(0, currency);
    }

    /** Exact major-unit amount (e.g. {@code 12.34} INR); rejects excess fraction digits and overflow. */
    public static Money ofMajor(BigDecimal major, String currency) {
        Currency c = currency(currency);
        return new Money(toMinorExact(major, c.getDefaultFractionDigits()), c);
    }

    /** Parses the API representation (a plain decimal string in major units, e.g. {@code "100000.00"}); strict. */
    public static Money parse(String decimal, String currency) {
        Objects.requireNonNull(decimal, "amount");
        String s = decimal.strip();
        if (!s.matches("[+-]?\\d+(\\.\\d+)?")) {
            throw new IllegalArgumentException("amount must be a plain decimal string, got '" + decimal + "'");
        }
        return ofMajor(new BigDecimal(s), currency);
    }

    /**
     * Rounds a full-precision major-unit amount to whole minor units with the given mode. This is the single sanctioned
     * rounding step; use it once per document total, not per line.
     */
    public static Money round(BigDecimal major, String currency, RoundingMode mode) {
        return round(major, currency, mode, -1);
    }

    /**
     * As {@link #round(BigDecimal, String, RoundingMode)} but to {@code scale} major-unit decimals (e.g. 0 = whole
     * rupees); {@code scale < 0} or above the currency exponent means "the currency exponent".
     */
    public static Money round(BigDecimal major, String currency, RoundingMode mode, int scale) {
        Currency c = currency(currency);
        int exp = c.getDefaultFractionDigits();
        int s = scale < 0 || scale > exp ? exp : scale;
        BigDecimal r = Objects.requireNonNull(major, "amount").setScale(s, Objects.requireNonNull(mode, "mode"));
        return new Money(toMinorExact(r, exp), c);
    }

    /** Minor units per {@code scale} step (e.g. INR scale 0 = 100 paise); the allocation granule. */
    public static long unit(String currency, int scale) {
        int exp = currency(currency).getDefaultFractionDigits();
        int s = scale < 0 || scale > exp ? exp : scale;
        return pow10(exp - s);
    }

    /** {@code major * 10^exponent} as a long, exactly: throws on excess fraction digits or overflow. */
    public static long toMinorExact(BigDecimal major, int exponent) {
        Objects.requireNonNull(major, "amount");
        BigDecimal m = major.movePointRight(exponent);
        try {
            return m.stripTrailingZeros().scale() > 0 ? fail(major, exponent) : m.longValueExact();
        } catch (ArithmeticException e) {
            throw new ArithmeticException("amount " + major.toPlainString() + " does not fit in minor units: " + e.getMessage());
        }
    }

    private static long fail(BigDecimal major, int exponent) {
        throw new IllegalArgumentException("amount " + major.toPlainString() + " has more than " + exponent
                + " fraction digit(s) allowed by its currency");
    }

    public static Currency currency(String code) {
        Objects.requireNonNull(code, "currency");
        Currency c = Currency.getInstance(code); // validates ISO 4217
        if (c.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("currency " + code + " has no minor unit (pseudo-currency)");
        }
        return c;
    }

    // ------------------------------------------------------------------ access

    public long minor() {
        return minor;
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    public int exponent() {
        return currency.getDefaultFractionDigits();
    }

    /** Exact major-unit value with the currency scale (for display / the API). */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minor, exponent());
    }

    /**
     * The API representation (OpenAPI {@code MoneyAmount}): plain decimal string with exactly the currency's fraction
     * digits. It is also the JSON form of a {@code Money} field; the DTO carries the currency in its own field.
     */
    @com.fasterxml.jackson.annotation.JsonValue
    public String toPlainString() {
        return toMajor().toPlainString();
    }

    public int signum() {
        return Long.signum(minor);
    }

    public boolean isZero() {
        return minor == 0;
    }

    // ------------------------------------------------------------------ exact arithmetic

    public Money plus(Money o) {
        same(o);
        return new Money(Math.addExact(minor, o.minor), currency);
    }

    public Money minus(Money o) {
        same(o);
        return new Money(Math.subtractExact(minor, o.minor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    public Money times(long n) {
        return new Money(Math.multiplyExact(minor, n), currency);
    }

    public Money min(Money o) {
        return compareTo(o) <= 0 ? this : o;
    }

    public Money max(Money o) {
        return compareTo(o) >= 0 ? this : o;
    }

    public static Money sum(List<Money> xs, String currency) {
        Money s = zero(currency);
        for (Money m : xs) {
            s = s.plus(m);
        }
        return s;
    }

    /** Splits this amount into {@code weights.length} parts proportional to the weights; Σ parts == this exactly. */
    public List<Money> split(long... weights) {
        BigDecimal[] exact = new BigDecimal[weights.length];
        BigInteger w = BigInteger.ZERO;
        for (long x : weights) {
            if (x < 0) {
                throw new IllegalArgumentException("negative weight");
            }
            w = w.add(BigInteger.valueOf(x));
        }
        if (w.signum() == 0) {
            throw new IllegalArgumentException("weights sum to zero");
        }
        for (int i = 0; i < weights.length; i++) {
            // exact rational -> enough digits to rank remainders; the floor/remainder below is integer-exact
            exact[i] = new BigDecimal(BigInteger.valueOf(minor).multiply(BigInteger.valueOf(weights[i])))
                    .divide(new BigDecimal(w), 30, RoundingMode.FLOOR);
        }
        long[] parts = allocate(minor, exact);
        List<Money> out = new ArrayList<>(parts.length);
        for (long p : parts) {
            out.add(new Money(p, currency));
        }
        return out;
    }

    // ------------------------------------------------------------------ largest-remainder allocation

    /**
     * Largest-remainder (Hamilton) allocation: integer parts {@code p[i]} with {@code Σ p == total} exactly, each
     * {@code p[i]} = floor(exact[i]) plus at most a few units, extra units going to the largest fractional remainders.
     * Deterministic tie-break: equal remainders go to the lower index first. Works for negative values and for any
     * target (if the target is far from Σ exact, the difference is spread round-robin in the same order).
     *
     * @param total  the target sum in integer units
     * @param exact  full-precision values in the same units
     */
    public static long[] allocate(long total, BigDecimal[] exact) {
        int n = exact.length;
        if (n == 0) {
            if (total != 0) {
                throw new IllegalArgumentException("cannot allocate " + total + " onto no lines");
            }
            return new long[0];
        }
        long[] p = new long[n];
        BigDecimal[] frac = new BigDecimal[n];
        long floorSum = 0;
        for (int i = 0; i < n; i++) {
            BigDecimal f = exact[i].setScale(0, RoundingMode.FLOOR);
            p[i] = f.longValueExact();
            frac[i] = exact[i].subtract(f);
            floorSum = Math.addExact(floorSum, p[i]);
        }
        long rest = Math.subtractExact(total, floorSum);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        if (rest >= 0) {
            // largest remainder first; ties: lower index first
            Arrays.sort(order, (a, b) -> {
                int c = frac[b].compareTo(frac[a]);
                return c != 0 ? c : Integer.compare(a, b);
            });
            for (long k = 0; k < rest; k++) {
                int i = order[(int) (k % n)];
                p[i] = Math.addExact(p[i], 1);
            }
        } else {
            // remove units from the smallest remainders first; ties: higher index first
            Arrays.sort(order, (a, b) -> {
                int c = frac[a].compareTo(frac[b]);
                return c != 0 ? c : Integer.compare(b, a);
            });
            for (long k = 0; k < -rest; k++) {
                int i = order[(int) (k % n)];
                p[i] = Math.subtractExact(p[i], 1);
            }
        }
        return p;
    }

    /**
     * Rounds the exact sum of {@code lines} (major units, full precision) ONCE to {@code scale} with {@code mode} and
     * spreads the rounded total back onto the lines (largest remainder, in steps of the scale unit). The result has
     * one entry per line and sums to the returned total exactly.
     */
    public static Rounded roundDocument(List<BigDecimal> lines, String currency, RoundingMode mode, int scale) {
        BigDecimal exactTotal = lines.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Money total = round(exactTotal, currency, mode, scale);
        int exp = currency(currency).getDefaultFractionDigits();
        long unit = unit(currency, scale);
        BigDecimal[] inUnits = new BigDecimal[lines.size()];
        for (int i = 0; i < inUnits.length; i++) {
            inUnits[i] = lines.get(i).movePointRight(exp).divide(BigDecimal.valueOf(unit), 30, RoundingMode.FLOOR);
        }
        long[] parts = allocate(total.minor / unit, inUnits);
        List<Money> out = new ArrayList<>(parts.length);
        for (long u : parts) {
            out.add(ofMinor(Math.multiplyExact(u, unit), currency));
        }
        return new Rounded(total, Collections.unmodifiableList(out), exactTotal);
    }

    /** A document rounded once: {@code Σ lines == total} always holds. */
    public record Rounded(Money total, List<Money> lines, BigDecimal exactTotal) {
    }

    // ------------------------------------------------------------------ misc

    private static long pow10(int e) {
        long r = 1;
        for (int i = 0; i < e; i++) {
            r = Math.multiplyExact(r, 10);
        }
        return r;
    }

    private void same(Money o) {
        if (!currency.equals(o.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currencyCode() + " vs " + o.currencyCode());
        }
    }

    @Override
    public int compareTo(Money o) {
        same(o);
        return Long.compare(minor, o.minor);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money m && m.minor == minor && m.currency.equals(currency);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(minor) * 31 + currency.hashCode();
    }

    @Override
    public String toString() {
        return toPlainString() + " " + currencyCode();
    }
}
