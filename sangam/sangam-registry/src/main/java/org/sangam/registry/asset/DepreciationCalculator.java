// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.asset;

import org.sangam.registry.support.Maps;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Monthly depreciation schedules and book values. All amounts are scale 2, HALF_UP; the last period absorbs rounding
 * so the closing value equals the residual value exactly. Methods: STRAIGHT_LINE, DECLINING_BALANCE, SUM_OF_YEARS,
 * UNITS_OF_PRODUCTION (units per month from meter readings), NONE. Conventions prorate the first period:
 * FULL_MONTH (full first month), HALF_MONTH (half in the first and an extra half at the end), DAILY (remaining
 * days of the first month, the rest at the end).
 */
public final class DepreciationCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;

    private DepreciationCalculator() {
    }

    /** Inputs of one asset. */
    public record Inputs(String method, BigDecimal cost, BigDecimal residual, int lifeMonths, BigDecimal ratePercent,
                         BigDecimal unitsTotal, String convention, LocalDate inService, Map<YearMonth, BigDecimal> units) {
    }

    /** One period line. */
    public record Line(YearMonth period, BigDecimal opening, BigDecimal charge, BigDecimal accumulated,
                       BigDecimal closing) {
        public Map<String, Object> toMap() {
            return Maps.of("period", period.toString(), "opening", opening, "charge", charge, "accumulated", accumulated,
                    "closing", closing);
        }
    }

    public static List<Line> schedule(Inputs in) {
        List<Line> out = new ArrayList<>();
        if (in.cost() == null || in.inService() == null) {
            return out;
        }
        BigDecimal cost = in.cost().setScale(2, RoundingMode.HALF_UP);
        BigDecimal residual = (in.residual() == null ? BigDecimal.ZERO : in.residual()).setScale(2, RoundingMode.HALF_UP);
        if (residual.compareTo(cost) > 0) {
            residual = cost;
        }
        BigDecimal base = cost.subtract(residual);
        String method = in.method() == null ? "NONE" : in.method();
        if ("NONE".equals(method) || base.signum() <= 0) {
            return out;
        }
        YearMonth start = YearMonth.from(in.inService());
        if ("UNITS_OF_PRODUCTION".equals(method)) {
            return unitsOfProduction(in, cost, residual, base, start);
        }
        if (in.lifeMonths() <= 0) {
            return out;
        }
        List<BigDecimal> fr = fractions(in.convention(), in.inService(), in.lifeMonths());
        int n = (int) Math.ceil(in.lifeMonths() / 12.0);
        BigDecimal sydSum = BigDecimal.valueOf((long) n * (n + 1) / 2);
        BigDecimal rate = in.ratePercent() != null && in.ratePercent().signum() > 0 ? in.ratePercent()
                : BigDecimal.valueOf(200).divide(BigDecimal.valueOf(in.lifeMonths()).divide(BigDecimal.valueOf(12), MC), MC);
        BigDecimal monthlyRate = rate.divide(BigDecimal.valueOf(1200), MC);
        BigDecimal perMonth = base.divide(BigDecimal.valueOf(in.lifeMonths()), MC);
        BigDecimal opening = cost;
        BigDecimal accumulated = BigDecimal.ZERO;
        BigDecimal elapsed = BigDecimal.ZERO;
        for (int i = 0; i < fr.size(); i++) {
            BigDecimal f = fr.get(i);
            BigDecimal remaining = opening.subtract(residual);
            BigDecimal charge;
            boolean last = i == fr.size() - 1;
            if (last) {
                charge = remaining;
            } else {
                charge = switch (method) {
                    case "DECLINING_BALANCE" -> opening.multiply(monthlyRate, MC).multiply(f, MC);
                    case "SUM_OF_YEARS" -> {
                        int k = Math.min(n, elapsed.divide(BigDecimal.valueOf(12), 0, RoundingMode.FLOOR).intValue() + 1);
                        yield base.multiply(BigDecimal.valueOf(n - k + 1L), MC).divide(sydSum, MC)
                                .divide(BigDecimal.valueOf(12), MC).multiply(f, MC);
                    }
                    default -> perMonth.multiply(f, MC);
                };
                charge = charge.setScale(2, RoundingMode.HALF_UP);
                if (charge.compareTo(remaining) > 0) {
                    charge = remaining;
                }
            }
            accumulated = accumulated.add(charge);
            BigDecimal closing = opening.subtract(charge);
            out.add(new Line(start.plusMonths(i), opening, charge, accumulated, closing));
            opening = closing;
            elapsed = elapsed.add(f);
            if (opening.compareTo(residual) <= 0) {
                break;
            }
        }
        return out;
    }

    private static List<Line> unitsOfProduction(Inputs in, BigDecimal cost, BigDecimal residual, BigDecimal base,
                                                YearMonth start) {
        List<Line> out = new ArrayList<>();
        if (in.unitsTotal() == null || in.unitsTotal().signum() <= 0 || in.units() == null) {
            return out;
        }
        BigDecimal opening = cost;
        BigDecimal accumulated = BigDecimal.ZERO;
        for (Map.Entry<YearMonth, BigDecimal> e : new TreeMap<>(in.units()).entrySet()) {
            if (e.getKey().isBefore(start) || e.getValue() == null || e.getValue().signum() <= 0) {
                continue;
            }
            BigDecimal remaining = opening.subtract(residual);
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal charge = base.multiply(e.getValue(), MC).divide(in.unitsTotal(), MC).setScale(2, RoundingMode.HALF_UP);
            if (charge.compareTo(remaining) > 0) {
                charge = remaining;
            }
            accumulated = accumulated.add(charge);
            BigDecimal closing = opening.subtract(charge);
            out.add(new Line(e.getKey(), opening, charge, accumulated, closing));
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
    public static BigDecimal bookValue(Inputs in, LocalDate asOf) {
        if (in.cost() == null) {
            return null;
        }
        BigDecimal value = in.cost().setScale(2, RoundingMode.HALF_UP);
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
    public static BigDecimal accumulated(Inputs in, LocalDate asOf) {
        BigDecimal bv = bookValue(in, asOf);
        return bv == null ? null : in.cost().setScale(2, RoundingMode.HALF_UP).subtract(bv);
    }
}
