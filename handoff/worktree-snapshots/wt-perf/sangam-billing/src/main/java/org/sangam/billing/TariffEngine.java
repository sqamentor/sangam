// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.sangam.config.common.EffectiveDated;
import org.sangam.config.common.Tree;
import org.sangam.core.money.Money;
import org.sangam.ledger.LedgerPorts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes invoice lines from tariffs held in configuration (profile section {@code tariffs}), with an optional DMN
 * hook for anything the generic components cannot express.
 *
 * <pre>
 * tariffs:
 *   - id: WATER_DOMESTIC
 *     effectiveFrom: 2026-04-01
 *     currency: INR
 *     components:
 *       - {id: FIXED, kind: FIXED, amount: 50, head: "0215-01-102"}
 *       - {id: USAGE, kind: SLAB, measure: units, head: "0215-01-103",
 *          slabs: [{upTo: 10, rate: 0}, {upTo: 20, rate: 5.5}, {rate: 9}]}
 *       - {id: METER_RENT, kind: PER_UNIT, measure: meters, rate: 10}
 *       - {id: SEWER, kind: PERCENT, of: USAGE, percent: 30}
 *       - {id: SPECIAL, kind: DECISION, decisionKey: water-special-charge}
 *     minimum: 75                  # minimum bill
 *     rounding: {mode: HALF_UP, scale: 0}
 *     penalty: {graceDays: 15, kind: PERCENT_PER_MONTH, rate: 1.5, decisionKey: null, head: "0215-01-800"}
 * </pre>
 *
 * <p><b>Rounding rule (SD-398, SG-20).</b> Every line (slab, percentage, decision, minimum) is computed at full
 * precision. The document is rounded ONCE: the exact sum of the lines is rounded to {@code rounding.scale} decimals of
 * the tariff {@code currency} (default: the currency's minor unit) with {@code rounding.mode} (tenant configuration,
 * default HALF_UP), and that total is allocated back onto the lines by the largest-remainder method
 * ({@link Money#roundDocument}; ties go to the earlier line). So {@code sum of line amounts == invoice total} exactly,
 * and the total never drifts by per-line rounding. Penalty / interest is likewise computed exactly and rounded once.
 */
public final class TariffEngine {

    /**
     * One computed line.
     *
     * @param exact  the full-precision amount (major units), before the document rounding
     * @param money  the line's share of the rounded document total (integer minor units)
     */
    public record Line(String component, String head, BigDecimal quantity, BigDecimal exact, Money money, String basis) {

        /** The rounded amount in major units (exact representation of {@link #money}). */
        public BigDecimal amount() {
            return money.toMajor();
        }
    }

    /** Currency of a tariff (required: a money amount without a currency is not money). */
    public static String currency(Map<String, Object> tariff) {
        String c = Tree.str(tariff.get("currency"), null);
        if (c == null) {
            throw new IllegalArgumentException("tariff '" + tariff.get("id") + "' has no currency (ISO 4217)");
        }
        return c;
    }

    /** {@code rounding.mode} of the tariff (tenant configuration), default HALF_UP. */
    public static RoundingMode roundingMode(Map<String, Object> tariff) {
        return RoundingMode.valueOf(Tree.str(Tree.map(tariff.get("rounding")).get("mode"), "HALF_UP").toUpperCase());
    }

    private final LedgerPorts.DecisionPort decisions;

    public TariffEngine(LedgerPorts.DecisionPort decisions) {
        this.decisions = decisions;
    }

    public static Optional<Map<String, Object>> tariff(List<Map<String, Object>> tariffs, String id, LocalDate asOf) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> t : tariffs) {
            if (id.equals(t.get("id"))) {
                rows.add(t);
            }
        }
        return EffectiveDated.select(rows, asOf);
    }

    /** Computes the lines for the given measures (e.g. {@code {units: 23, meters: 1}}) and attributes. */
    public List<Line> compute(String tenant, Map<String, Object> tariff, Map<String, BigDecimal> measures,
                              Map<String, Object> attrs) {
        String currency = currency(tariff);
        List<Raw> lines = new ArrayList<>();
        Map<String, BigDecimal> byComponent = new LinkedHashMap<>();
        for (Map<String, Object> c : Tree.maps(tariff.get("components"))) {
            String id = Tree.str(c.get("id"), "C");
            String head = Tree.str(c.get("head"), null);
            String kind = Tree.str(c.get("kind"), "FIXED").toUpperCase();
            BigDecimal q = measures.getOrDefault(Tree.str(c.get("measure"), "units"), BigDecimal.ZERO);
            BigDecimal amt;
            String basis;
            switch (kind) {
                case "FIXED" -> {
                    amt = Tree.decimal(c.get("amount"), BigDecimal.ZERO);
                    basis = "fixed";
                }
                case "PER_UNIT" -> {
                    amt = q.multiply(Tree.decimal(c.get("rate"), BigDecimal.ZERO));
                    basis = q + " x " + c.get("rate");
                }
                case "SLAB" -> {
                    amt = slab(Tree.maps(c.get("slabs")), q, Tree.bool(c.get("telescopic"), true));
                    basis = "slab on " + q;
                }
                case "PERCENT" -> {
                    BigDecimal of = byComponent.getOrDefault(Tree.str(c.get("of"), ""), BigDecimal.ZERO);
                    amt = of.multiply(Tree.decimal(c.get("percent"), BigDecimal.ZERO)).movePointLeft(2); // exact
                    basis = c.get("percent") + "% of " + c.get("of");
                }
                case "DECISION" -> {
                    amt = BigDecimal.ZERO;
                    basis = "decision " + c.get("decisionKey");
                    if (decisions != null) {
                        Map<String, Object> vars = new LinkedHashMap<>(attrs);
                        measures.forEach(vars::put);
                        for (Map<String, Object> row : decisions.evaluate(tenant, Tree.str(c.get("decisionKey"), ""), vars)) {
                            amt = amt.add(Tree.decimal(row.get("amount"), BigDecimal.ZERO));
                        }
                    }
                }
                default -> throw new IllegalArgumentException("unknown tariff component kind " + kind);
            }
            byComponent.put(id, amt);
            lines.add(new Raw(id, head, q, amt, basis));
        }
        BigDecimal total = lines.stream().map(Raw::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal min = Tree.decimal(tariff.get("minimum"), null);
        if (min != null && total.compareTo(min) < 0) {
            lines.add(new Raw("MINIMUM", Tree.str(tariff.get("minimumHead"), null), BigDecimal.ONE, min.subtract(total),
                    "minimum charge " + min));
        }
        // the ONE rounding step of the document: total rounded once, then allocated back (largest remainder)
        Map<String, Object> rnd = Tree.map(tariff.get("rounding"));
        int scale = Tree.integer(rnd.get("scale"), -1);
        Money.Rounded doc = Money.roundDocument(lines.stream().map(Raw::amount).toList(), currency, roundingMode(tariff), scale);
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Raw l = lines.get(i);
            out.add(new Line(l.component(), l.head(), l.quantity(), l.amount(), doc.lines().get(i), l.basis()));
        }
        return out;
    }

    private record Raw(String component, String head, BigDecimal quantity, BigDecimal amount, String basis) {
    }

    static BigDecimal slab(List<Map<String, Object>> slabs, BigDecimal q, boolean telescopic) {
        BigDecimal amt = BigDecimal.ZERO;
        BigDecimal lower = BigDecimal.ZERO;
        for (Map<String, Object> s : slabs) {
            BigDecimal upTo = Tree.decimal(s.get("upTo"), null);
            BigDecimal rate = Tree.decimal(s.get("rate"), BigDecimal.ZERO);
            if (!telescopic) {
                if (upTo == null || q.compareTo(upTo) <= 0) {
                    return q.multiply(rate);
                }
                continue;
            }
            BigDecimal top = upTo == null ? q : upTo.min(q);
            if (top.compareTo(lower) > 0) {
                amt = amt.add(top.subtract(lower).multiply(rate));
            }
            if (upTo == null || q.compareTo(upTo) <= 0) {
                break;
            }
            lower = upTo;
        }
        return amt;
    }

    /**
     * Penalty / interest on an overdue amount, from the tariff's {@code penalty} block (or its decision): computed at
     * full precision and rounded ONCE to the currency minor unit with the tariff rounding mode.
     */
    public Money penalty(String tenant, Map<String, Object> tariff, BigDecimal overdue, LocalDate dueDate, LocalDate asOf) {
        return Money.round(penaltyExact(tenant, tariff, overdue, dueDate, asOf), currency(tariff), roundingMode(tariff));
    }

    /** Full-precision penalty (major units), before rounding. */
    BigDecimal penaltyExact(String tenant, Map<String, Object> tariff, BigDecimal overdue, LocalDate dueDate, LocalDate asOf) {
        Map<String, Object> p = Tree.map(tariff.get("penalty"));
        if (p.isEmpty() || overdue.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(dueDate, asOf) - Tree.integer(p.get("graceDays"), 0);
        if (days <= 0) {
            return BigDecimal.ZERO;
        }
        String key = Tree.str(p.get("decisionKey"), null);
        if (key != null && decisions != null) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> row : decisions.evaluate(tenant, key, Map.of("overdue", overdue, "daysLate", days))) {
                sum = sum.add(Tree.decimal(row.get("amount"), BigDecimal.ZERO));
            }
            return sum;
        }
        BigDecimal rate = Tree.decimal(p.get("rate"), BigDecimal.ZERO);
        return switch (Tree.str(p.get("kind"), "FLAT").toUpperCase()) {
            // /3000 and /36500 need not terminate: 34 significant digits (DECIMAL128) is "full precision" here; the
            // single rounding to minor units happens in penalty()
            case "PERCENT_PER_MONTH" -> overdue.multiply(rate).multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(3000), java.math.MathContext.DECIMAL128);
            case "PERCENT_PER_ANNUM" -> overdue.multiply(rate).multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(36500), java.math.MathContext.DECIMAL128);
            case "PERCENT_ONCE" -> overdue.multiply(rate).movePointLeft(2);
            default -> rate;
        };
    }
}
