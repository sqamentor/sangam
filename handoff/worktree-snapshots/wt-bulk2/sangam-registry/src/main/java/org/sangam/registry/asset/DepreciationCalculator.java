// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.asset;

import org.sangam.core.money.Money;
import org.sangam.registry.support.Maps;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Monthly depreciation schedules and book values in integer minor units ({@link Money}, SD-398 / SG-20).
 *
 * <p><b>Rounding rule.</b> The schedule is one document. Every period charge is first computed at full precision
 * (34 significant digits; the last period takes the exact remainder), so the exact charges sum to the depreciable
 * amount {@code cost - residual}. That amount, already whole minor units, is then allocated onto the periods by the
 * largest-remainder method ({@link Money#allocate}; ties go to the earlier period). Hence
 * {@code Σ charges == cost - residual} exactly and the closing value of the last period equals the residual value.
 * UNITS_OF_PRODUCTION is open-ended (future readings are unknown), so it rounds the cumulative exact charge once per
 * period ({@code charge_i = round(cum_i) - round(cum_{i-1})}), which keeps {@code Σ charges == round(Σ exact)} and
 * reaches the depreciable amount exactly when the units are used up.
 *
 * <p>Methods: STRAIGHT_LINE, DECLINING_BALANCE, SUM_OF_YEARS, UNITS_OF_PRODUCTION (units per month from meter
 * readings), NONE. Conventions prorate the first period: FULL_MONTH, HALF_MONTH (half first, extra half at the end),
 * DAILY (remaining days of the first month, the rest at the end).
 */
public final class DepreciationCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;

    private DepreciationCalculator() {
    }

    /** Inputs of one asset; {@code cost} and {@code residual} are money in the same currency. */
    public record Inputs(String method, Money cost, Money residual, int lifeMonths, BigDecimal ratePercent,
                         BigDecimal unitsTotal, String convention, LocalDate inService, Map<YearMonth, BigDecimal> units) {
    }

    /** One period line. */
    public record Line(YearMonth period, Money opening, Money charge, Money accumulated, Money closing) {
        public Map<String, Object> toMap() {
            return Maps.of("period", period.toString(), "opening", opening.toPlainString(), "charge", charge.toPlainString(),
                    "accumulated", accumulated.toPlainString(), "closing", closing.toPlainString(),
                    "currency", charge.currencyCode());
        }
    }

    public static List<Line> schedule(Inputs in) {
        if (in.cost() == null || in.inService() == null) {
            return new ArrayList<>();
        }
        Money cost = in.cost();
        Money residual = in.residual() == null ? Money.zero(cost.currencyCode()) : in.residual().min(cost);
        Money base = cost.minus(residual);
        String method = in.method() == null ? "NONE" : in.method();
        if ("NONE".equals(method) || base.signum() <= 0) {
            return new ArrayList<>();
        }
        YearMonth start = YearMonth.from(in.inService());
        if ("UNITS_OF_PRODUCTION".equals(method)) {
            return unitsOfProduction(in, cost, base, start);
        }
        if (in.lifeMonths() <= 0) {
            return new ArrayList<>();
        }
        // 1) exact charges in minor units (BigDecimal at 34 digits); the last period takes the exact remainder
        List<BigDecimal> fr = fractions(in.convention(), in.inService(), in.lifeMonths());
        int n = (int) Math.ceil(in.lifeMonths() / 12.0);
        BigDecimal sydSum = BigDecimal.valueOf((long) n * (n + 1) / 2);
        BigDecimal rate = in.ratePercent() != null && in.ratePercent().signum() > 0 ? in.ratePercent()
                : BigDecimal.valueOf(200).divide(BigDecimal.valueOf(in.lifeMonths()).divide(BigDecimal.valueOf(12), MC), MC);
        BigDecimal monthlyRate = rate.divide(BigDecimal.valueOf(1200), MC);
        BigDecimal exactBase = BigDecimal.valueOf(base.minor());
        BigDecimal perMonth = exactBase.divide(BigDecimal.valueOf(in.lifeMonths()), MC);
        BigDecimal remaining = exactBase;          // exact depreciable amount still to charge
        BigDecimal elapsed = BigDecimal.ZERO;
        List<BigDecimal> exact = new ArrayList<>();
        for (int i = 0; i < fr.size() && remaining.signum() > 0; i++) {
            BigDecimal f = fr.get(i);
            BigDecimal charge;
            if (i == fr.size() - 1) {
                charge = remaining;
            } else {
                BigDecimal openingExact = remaining.add(BigDecimal.valueOf(residual.minor()));
                charge = switch (method) {
                    case "DECLINING_BALANCE" -> openingExact.multiply(monthlyRate, MC).multiply(f, MC);
                    case "SUM_OF_YEARS" -> {
                        int k = Math.min(n, elapsed.divide(BigDecimal.valueOf(12), 0, java.math.RoundingMode.FLOOR).intValue() + 1);
                        yield exactBase.multiply(BigDecimal.valueOf(n - k + 1L), MC).divide(sydSum, MC)
                                .divide(BigDecimal.valueOf(12), MC).multiply(f, MC);
                    }
                    default -> perMonth.multiply(f, MC);
                };
                charge = charge.min(remaining);
            }
            exact.add(charge);
            remaining = remaining.subtract(charge);
            elapsed = elapsed.add(f);
        }
        // 2) one allocation of the whole depreciable amount: Σ charges == base exactly
        long[] charges = Money.allocate(base.minor(), exact.toArray(new BigDecimal[0]));
        return lines(cost, start, charges, null);
    }

    private static List<Line> unitsOfProduction(Inputs in, Money cost, Money base, YearMonth start) {
        if (in.unitsTotal() == null || in.unitsTotal().signum() <= 0 || in.units() == null) {
            return new ArrayList<>();
        }
        List<YearMonth> periods = new ArrayList<>();
        List<Long> charges = new ArrayList<>();
        BigDecimal cumUnits = BigDecimal.ZERO;
        long chargedSoFar = 0;
        for (Map.Entry<YearMonth, BigDecimal> e : new TreeMap<>(in.units()).entrySet()) {
            if (e.getKey().isBefore(start) || e.getValue() == null || e.getValue().signum() <= 0) {
                continue;
            }
            if (chargedSoFar >= base.minor()) {
                break;
            }
            cumUnits = cumUnits.add(e.getValue()).min(in.unitsTotal());
            BigDecimal cumExact = BigDecimal.valueOf(base.minor()).multiply(cumUnits, MC).divide(in.unitsTotal(), MC);
            long cum = Math.min(base.minor(), cumExact.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact());
            periods.add(e.getKey());
            charges.add(cum - chargedSoFar);
            chargedSoFar = cum;
        }
        long[] c = charges.stream().mapToLong(Long::longValue).toArray();
        return lines(cost, start, c, periods);
    }

    private static List<Line> lines(Money cost, YearMonth start, long[] charges, List<YearMonth> periods) {
        List<Line> out = new ArrayList<>();
        String ccy = cost.currencyCode();
        Money opening = cost;
        Money accumulated = Money.zero(ccy);
        for (int i = 0; i < charges.length; i++) {
            Money charge = Money.ofMinor(charges[i], ccy);
            accumulated = accumulated.plus(charge);
            Money closing = opening.minus(charge);
            out.add(new Line(periods == null ? start.plusMonths(i) : periods.get(i), opening, charge, accumulated, closing));
            opening = closing;
        }
        return out;
    }

    /** Period fractions for the convention: life months in total, prorated at the start and end. */
    static List<BigDecimal> fractions(String convention, LocalDate inService, int life) {
        List<BigDecimal> fr = new ArrayList<>();
        String conv = convention == null ? "FULL_MONTH" : convention;
        switch (conv) {
            case "HALF_MONTH" -> {
                fr.add(new BigDecimal("0.5"));
                for (int i = 1; i < life; i++) {
                    fr.add(BigDecimal.ONE);
                }
                fr.add(new BigDecimal("0.5"));
            }
            case "DAILY" -> {
                int len = inService.lengthOfMonth();
                BigDecimal f = BigDecimal.valueOf(len - inService.getDayOfMonth() + 1L)
                        .divide(BigDecimal.valueOf(len), MC);
                fr.add(f);
                for (int i = 1; i < life; i++) {
                    fr.add(BigDecimal.ONE);
                }
                BigDecimal rest = BigDecimal.ONE.subtract(f);
                if (rest.signum() > 0) {
                    fr.add(rest);
                }
            }
            default -> {
                for (int i = 0; i < life; i++) {
                    fr.add(BigDecimal.ONE);
                }
            }
        }
        return fr;
    }

    /** Book value at the end of the month of {@code asOf} (cost before the in-service month). */
    public static Money bookValue(Inputs in, LocalDate asOf) {
        if (in.cost() == null) {
            return null;
        }
        Money value = in.cost();
        YearMonth until = YearMonth.from(asOf);
        for (Line l : schedule(in)) {
            if (l.period().isAfter(until)) {
                break;
            }
            value = l.closing();
        }
        return value;
    }

    /** Accumulated depreciation up to the month of {@code asOf}. */
    public static Money accumulated(Inputs in, LocalDate asOf) {
        Money bv = bookValue(in, asOf);
        return bv == null ? null : in.cost().minus(bv);
    }
}
