// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

/**
 * Decision 139: a money calculation with a calculation-specific, versioned rounding policy. The amount is computed in
 * exact decimal arithmetic ({@link DecimalFormula}: division at the explicit intermediate precision
 * {@link DecimalFormula#DIVISION}) and rounded <b>once</b> by the policy, or per component where the formula says
 * {@code round(...)} (for example each contribution separately, then summed). No binary floating point (decision 138).
 *
 * <p><b>Policy.</b> Field {@code policy} names a DMN decision evaluated with input {@code asOfDate} (the tenant's
 * business date, ISO text), so a policy has effective dates; the decision deployed with the calling process version
 * is used (GP-19 binding), so a later policy change never silently recalculates a case that already ran, and a
 * tenant's own table wins (GP-37). The single matching row gives {@code policyId}, {@code policyVersion},
 * {@code evidenceStatus} ({@code VERIFIED} or {@code UNVERIFIED}) and {@code permittedUse} ({@code PRODUCTION} or
 * {@code SYNTHETIC_DEMO_ONLY}) kept apart (decision 288: a real but unproven rule is {@code UNVERIFIED} +
 * {@code SYNTHETIC_DEMO_ONLY}, never fictitious; a row in the old single-{@code status} shape is refused);
 * {@code PRODUCTION} only with {@code VERIFIED} evidence, a citation and an explicit effective date on or before the
 * day the row was selected on (decisions 280, 288, 297, {@link RowEvidence}); in production mode only a
 * {@code PRODUCTION} row applies (decision 292), {@code mode} (a {@link RoundingMode} name),
 * {@code quantumMinor} (the
 * rounding unit in minor units: for a currency with two decimals 100 is one major unit, 1 is one minor unit) and {@code negatives} ({@code REFUSE},
 * {@code SIGNED}: the mode applies to the signed value, {@code MAGNITUDE}: to the absolute value, sign kept); for
 * {@code op=INSTALMENT} also {@code residual} ({@code LAST} or {@code FIRST}: the instalment that takes the
 * remainder). Every other output (jurisdiction, scheme, purpose, source, provision, stage, application, effective
 * dates) is copied into the audit record.
 *
 * <p><b>Operations</b> (field {@code op}):
 * <ul>
 *   <li>{@code FORMULA} (default): {@code result = policy(formula)}.</li>
 *   <li>{@code INSTALMENT}: fields {@code total}, {@code count} and {@code number} name the variables of the total, the
 *       number of instalments and this instalment's 1-based number; every instalment is
 *       {@code policy(total / count)} except the residual one, which is the total minus the others, so the instalments
 *       always add up to the total exactly.</li>
 *   <li>{@code SUM}: variables joined by {@code +} or {@code -}, each already whole minor units (rounded components
 *       reconcile exactly with their total); nothing is rounded, so no policy.</li>
 *   <li>{@code EACH} (decision 223): field {@code items} names a variable holding a JSON array of records (a bytes
 *       JSON document, {@link Vars#setJsonDocument}, or JSON text), for example the frozen wage register; the formula
 *       is evaluated per item, a name resolving to the item's own field first and to the case variable otherwise,
 *       and rounded by the policy per item ({@code round(...)} components per item too). Each item gets the result
 *       under field {@code itemResult} (whole minor units); the case variable {@code result} is the exact sum of
 *       the per-item results, so the total reconciles with the items. Optional {@code cap}
 *       ({@code field<=limit}): the item field is used up to the limit (the base used is written as
 *       {@code <itemResult>Base}); optional {@code eligibleWhen} ({@code field<=limit}): an item whose field is
 *       above the limit is not eligible ({@code <itemResult>Eligible=false}, result 0). A limit is read from the row
 *       of the versioned rules DMN named by field {@code rules} (evaluated with {@code asOfDate} like the policy,
 *       evidence and use read and checked as for a policy row; recorded as {@code <result>Rules},
 *       {@code <result>RulesEvidence} and {@code <result>RulesUse}) and otherwise from the case variable. Field {@code itemKey} (default
 *       {@code employeeId}) identifies an item: a missing or repeated key, a negative capped or eligibility field
 *       and an empty or missing list are refused. One audit event lists every item compactly
 *       ({@code key:value}, with {@code :CAPPED} or {@code :INELIGIBLE}). Optional {@code continuation}
 *       ({@code itemDateField@modeColumn@periodStartsColumn}, with {@code eligibleWhen} and {@code rules}; decision
 *       256): continuation of coverage is decided by the versioned rules row and the register, never by a caller
 *       flag. The row's {@code modeColumn} is {@code CONTRIBUTION_PERIOD} or {@code NONE} (off); its
 *       {@code periodStartsColumn} lists the first days of the contribution periods as {@code MM-dd} (for example
 *       {@code 04-01,10-01}: April to September, October to March). The contribution period is the one containing
 *       the wage period's first day (the business date without a wage period). Each item's {@code itemDateField} is
 *       the start of the employee's current coverage as the register states it (an ISO date, or {@code NONE}: not
 *       covered); an item above the eligibility limit whose coverage started on or before the first day of that
 *       contribution period stays eligible ({@code <itemResult>Continued=true}, {@code :CONTINUED}). Under
 *       {@code CONTRIBUTION_PERIOD} every item must state its coverage start, and a wage period crossing a
 *       contribution-period boundary is refused ({@code WAGE_PERIOD_CROSSES_CONTRIBUTION_PERIOD_UNVERIFIED},
 *       decision 271: blocked from final calculation, never split). Optional with {@code continuation}:
 *       {@code joiner} (rules column, decision 264, {@code COVERED_TO_PERIOD_END}: a joiner covered on joining stays
 *       covered to the end of that contribution period) and {@code continuationEnd} (item field, decision 270: the
 *       end of the continuation after the wages crossed the limit, read only for an item above the limit; a date that
 *       would shorten the mandatory continuation is refused); rules in {@link ContributionCoverage}. Optional
 *       {@code exemptWhen} ({@code field/perField<=limit}): an eligible item whose field divided exactly by its
 *       positive whole {@code perField} (for example wages per paid day) meets the limit is exempt from this share,
 *       result 0 ({@code <itemResult>Exempt=true}, {@code :EXEMPT}); with optional {@code exemptComparison} (a rules
 *       column, decision 256) the rules row decides the comparison: {@code AT_OR_BELOW} (the limit itself is exempt)
 *       or {@code BELOW}, anything else refused; without it the comparison is at or below. With optional
 *       {@code exemptBasis} (decision 271) the average daily wage is the rules row's definition for the item's wage
 *       basis ({@link AverageDailyWage}, written as {@code <itemResult>AverageDailyWage}), refused when undefined.
 *       Coverage, the exemption and the contribution are separate rules; the exemption is per share, so the
 *       employer share and coverage stay. Decision 272: each item keeps its unrounded amount
 *       ({@code <itemResult>Exact}) and the rounding difference ({@code <itemResult>RoundingDifference}, final minus
 *       unrounded), both exact decimal text, also listed in the audit event ({@code itemRounding}); the policy
 *       rounds once per item and share.</li>
 *   <li>{@code TOTAL} (decision 223): field {@code formula} names one item field of the register in {@code items};
 *       {@code result} is the exact sum of that field over the items (each whole minor units, not negative, keys
 *       unique, the register not empty); nothing is rounded, so no policy. For example the gross wages of a payroll
 *       taken from the frozen register, never one case figure.</li>
 *   <li>{@code EXACT} (ruling C): never rounds; fields {@code min}/{@code max}; see {@link MoneyExactCalculation}.</li>
 * </ul>
 * <b>Wage period (decision 223).</b> Optional fields {@code periodFrom} and {@code periodTo} name the variables
 * holding the first and last day (ISO dates) of the period the amounts are for. When set, the policy DMN and the
 * rules DMN are evaluated with {@code asOfDate} equal to each of the two days instead of the business date, and
 * (decision 255) the row in force on the wage period's <b>first day</b> applies. A table overrides that only where
 * a notification says otherwise: the row in force on the period's last day (the row that came into force during
 * the period, when they differ) may carry output {@code periodRuleBasis}, {@code FIRST_DAY} (the default, also
 * when the column is absent or blank) or {@code LAST_DAY} (that row applies to the whole wage period); any other
 * value is refused ({@code RULES_INVALID}). A missing or invalid date is refused ({@code PERIOD_MISSING},
 * {@code PERIOD_INVALID}). The dates evaluated are recorded in the audit event ({@code asOf}), and the day and
 * basis of each table's row in {@code rowsAsOf}. Optional field {@code periodUnit} {@code CALENDAR_MONTH} (decision
 * 293, the PF shares: EPF Scheme 2026 para 18(4) works on the wages of the month) refuses a calculation whose stated
 * wage period is not one whole calendar month, or that states none ({@code WAGE_PERIOD_NOT_CALENDAR_MONTH}), until
 * monthly aggregation across wage periods is built and verified.
 * A result that is a ratio, not money ({@code ratio=true}), takes no policy and is stored exact at the intermediate
 * precision (for example a percentage compared with a threshold: rounding it would move the boundary).
 *
 * <p><b>Recorded on the case:</b> {@code <result>} (whole minor units as a {@code Long}, otherwise exact
 * {@code BigDecimal}), {@code <result>Policy} ({@code policyId@policyVersion}), {@code <result>PolicyEvidence} and
 * {@code <result>PolicyUse}; audit
 * event {@code MONEY_CALCULATED} with the formula, the inputs, the exact unrounded value, the result and the policy.
 * Missing inputs, a missing or invalid policy, a negative result under {@code REFUSE}, division by zero and a
 * residual instalment below zero are refused, never defaulted.
 */
public class MoneyCalculationDelegate implements JavaDelegate {

    public static final String AUDIT_TYPE = "MONEY_CALCULATED";

    private Expression result;
    private Expression formula;
    private Expression policy;
    private Expression op;
    private Expression total;
    private Expression count;
    private Expression number;
    private Expression ratio;
    private Expression items;
    private Expression itemKey;
    private Expression itemResult;
    private Expression cap;
    private Expression eligibleWhen;
    private Expression rules;
    private Expression continuation;
    private Expression exemptWhen;
    private Expression exemptComparison;
    private Expression periodFrom;
    private Expression periodTo;
    private Expression continuationEnd;
    private Expression joiner;
    private Expression exemptBasis;
    private Expression periodUnit;
    private Expression min;
    private Expression max;

    /** The computational part of a policy row. */
    public record Policy(String id, String version, String evidence, String use, RoundingMode mode,
                         BigDecimal quantumMinor, String negatives, String residual, Map<String, Object> row) {

        /** Reads and checks a policy row; refuses anything it cannot apply exactly. */
        public static Policy of(String key, Map<String, Object> row) {
            if (row == null || row.isEmpty()) {
                throw refused("POLICY_MISSING", "rounding policy " + key + " matched no row");
            }
            String id = text(row, "policyId", key);
            String version = text(row, "policyVersion", key);
            String[] eu = RowEvidence.evidenceAndUse(row, "POLICY", "rounding policy " + id + "@" + version);
            RoundingMode mode;
            try {
                mode = RoundingMode.valueOf(text(row, "mode", key));
            } catch (IllegalArgumentException e) {
                throw refused("POLICY_INVALID", "rounding policy " + id + " has an unknown mode " + row.get("mode"));
            }
            if (mode == RoundingMode.UNNECESSARY) {
                throw refused("POLICY_INVALID", "rounding policy " + id + ": mode UNNECESSARY is not a policy");
            }
            BigDecimal quantum = Vars.decimal(row.get("quantumMinor"));
            if (quantum == null || quantum.signum() <= 0 || quantum.stripTrailingZeros().scale() > 0) {
                throw refused("POLICY_INVALID", "rounding policy " + id + ": quantumMinor must be a positive whole"
                        + " number of minor units");
            }
            String negatives = text(row, "negatives", key);
            if (!negatives.equals("REFUSE") && !negatives.equals("SIGNED") && !negatives.equals("MAGNITUDE")) {
                throw refused("POLICY_INVALID", "rounding policy " + id + ": negatives must be REFUSE, SIGNED or"
                        + " MAGNITUDE");
            }
            Object r = row.get("residual");
            String residual = r == null ? null : r.toString().trim();
            return new Policy(id, version, eu[0], eu[1], mode, quantum, negatives, residual, row);
        }

        /** Applies the policy to one value. */
        public BigDecimal apply(BigDecimal v) {
            if (v.signum() < 0) {
                if (negatives.equals("REFUSE")) {
                    throw refused("NEGATIVE_REFUSED", "policy " + ref() + " refuses a negative amount " + v);
                }
                if (negatives.equals("MAGNITUDE")) {
                    return round(v.negate()).negate();
                }
            }
            return round(v);
        }

        private BigDecimal round(BigDecimal v) {
            return v.divide(quantumMinor, 0, mode).multiply(quantumMinor);
        }

        public String ref() {
            return id + "@" + version;
        }

        private static String text(Map<String, Object> row, String name, String key) {
            Object v = row.get(name);
            if (v == null || v.toString().isBlank()) {
                throw refused("POLICY_INVALID", "rounding policy " + key + " has no " + name);
            }
            return v.toString().trim();
        }
    }

    /** Decisions 280, 288 and 297 for a policy row: {@link RowEvidence#inForce} on the day it was selected on. */
    static void inForce(Policy p, String checkDay) {
        RowEvidence.inForce("POLICY", "rounding policy " + p.ref(), p.use(), p.row(), checkDay);
    }

    /** Decision 297: the day the row of table {@code key} was selected on, as {@link #row} recorded it. */
    static String checkDay(Map<String, Object> rowsAsOf, String key) {
        return String.valueOf(rowsAsOf.get(key)).split(" ", 2)[0];
    }

    static ProcessEngineException refused(String code, String message) {
        return new ProcessEngineException(code + ": " + message);
    }

    @Override
    public void execute(DelegateExecution ex) {
        String target = Vars.field(result, ex);
        if (target == null || target.isBlank()) {
            throw refused("CALCULATION_INVALID", "field result is required");
        }
        String operation = Vars.field(op, ex, "FORMULA");
        Map<String, Object> inputs = new LinkedHashMap<>();
        if ("SUM".equals(operation)) {
            MoneyExactCalculation.sum(ex, target, Vars.field(formula, ex), inputs);
            return;
        }
        if ("TOTAL".equals(operation)) {
            total(ex, target);
            return;
        }
        if ("EXACT".equals(operation)) {
            MoneyExactCalculation.exact(ex, target, Vars.field(formula, ex), Vars.field(min, ex), Vars.field(max, ex),
                    inputs);
            return;
        }
        if (Vars.fieldBool(ratio, ex, false)) {
            DecimalFormula f = DecimalFormula.parse(Vars.field(formula, ex));
            if (f.roundsComponents()) {
                throw refused("CALCULATION_INVALID", "a ratio has no rounding policy, so round(...) is not allowed");
            }
            BigDecimal exact = f.evaluate(name -> read(ex, name, inputs), v -> v);
            ex.setVariable(target, Vars.engineValue(exact));
            Vars.audit(ex, AUDIT_TYPE, Vars.map("result", target, "formula", f.text(), "inputs", inputs,
                    "exact", exact.toString(), "value", exact.toString(), "policy", "RATIO_EXACT"));
            return;
        }
        String key = Vars.field(policy, ex);
        if (key == null || key.isBlank()) {
            throw refused("POLICY_MISSING", "a money calculation needs field policy (decision 139)");
        }
        List<String> asOf = asOf(ex);
        calendarMonth(Vars.field(periodUnit, ex), Vars.field(periodFrom, ex), asOf);
        Map<String, Object> rowsAsOf = new LinkedHashMap<>();
        Policy p = Policy.of(key, row(ex, key, asOf, rowsAsOf));
        inForce(p, checkDay(rowsAsOf, key));
        if ("EACH".equals(operation)) {
            each(ex, target, p, asOf, rowsAsOf);
            return;
        }
        BigDecimal exact;
        BigDecimal value;
        String text;
        if ("INSTALMENT".equals(operation)) {
            BigDecimal t = need(ex, Vars.field(total, ex), inputs);
            BigDecimal n = need(ex, Vars.field(count, ex), inputs);
            BigDecimal k = need(ex, Vars.field(number, ex), inputs);
            text = "instalment " + k + " of " + n + " of " + t;
            exact = t.divide(n, DecimalFormula.DIVISION);
            value = instalment(p, t, n, k);
        } else if ("FORMULA".equals(operation)) {
            DecimalFormula f = DecimalFormula.parse(Vars.field(formula, ex));
            text = f.text();
            exact = f.evaluate(name -> read(ex, name, inputs), v -> v);
            value = p.apply(f.roundsComponents() ? f.evaluate(name -> read(ex, name, inputs), p::apply) : exact);
        } else {
            throw refused("CALCULATION_INVALID", "unknown op " + operation);
        }
        ex.setVariable(target, Vars.engineValue(value));
        ex.setVariable(target + "Policy", p.ref());
        ex.setVariable(target + "PolicyEvidence", p.evidence());
        ex.setVariable(target + "PolicyUse", p.use());
        Map<String, Object> details = Vars.map("result", target, "formula", text, "inputs", inputs,
                "exact", exact.toString(), "value", value.toPlainString(), "policy", p.ref(), "evidence", p.evidence(),
                "use", p.use(),
                "mode", p.mode().name(), "quantumMinor", p.quantumMinor().toPlainString(), "negatives", p.negatives(),
                "asOf", asOf, "rowsAsOf", rowsAsOf);
        for (Map.Entry<String, Object> e : p.row().entrySet()) {
            details.putIfAbsent("policy." + e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        Vars.audit(ex, AUDIT_TYPE, details);
    }

    /** Instalment {@code k} (1-based) of {@code n} of {@code t}: each the policy share, the residual one the rest. */
    static BigDecimal instalment(Policy p, BigDecimal t, BigDecimal n, BigDecimal k) {
        int count;
        int no;
        try {
            count = n.intValueExact();
            no = k.intValueExact();
        } catch (ArithmeticException e) {
            throw refused("CALCULATION_INVALID", "instalment count and number must be whole numbers");
        }
        if (count < 1 || no < 1 || no > count) {
            throw refused("CALCULATION_INVALID", "instalment " + no + " of " + count + " is out of range");
        }
        String residual = p.residual() == null ? "" : p.residual();
        if (!residual.equals("LAST") && !residual.equals("FIRST")) {
            throw refused("POLICY_INVALID", "rounding policy " + p.ref() + ": an instalment policy needs residual"
                    + " LAST or FIRST");
        }
        BigDecimal share = p.apply(t.divide(n, DecimalFormula.DIVISION));
        int residualNo = residual.equals("LAST") ? count : 1;
        if (no != residualNo) {
            return share;
        }
        BigDecimal rest = t.subtract(share.multiply(BigDecimal.valueOf(count - 1L)));
        if (rest.signum() * t.signum() < 0) {
            throw refused("RESIDUAL_NEGATIVE", "policy " + p.ref() + " leaves a residual instalment of " + rest
                    + " for a total of " + t);
        }
        return rest;
    }

    /** {@code EACH} (decision 223): the formula per item, rounded per item, results written back, exact sum. */
    private void each(DelegateExecution ex, String target, Policy p, List<String> asOf,
                      Map<String, Object> rowsAsOf) {
        String listVar = Vars.field(items, ex);
        String out = Vars.field(itemResult, ex);
        String keyField = Vars.field(itemKey, ex, "employeeId");
        if (listVar == null || out == null || !NAME.matcher(out.trim()).matches()) {
            throw refused("CALCULATION_INVALID", "op EACH needs field items and a field name itemResult");
        }
        out = out.trim();
        DecimalFormula f = DecimalFormula.parse(Vars.field(formula, ex));
        Bound capBound = Bound.parse("cap", Vars.field(cap, ex));
        Bound eligible = Bound.parse("eligibleWhen", Vars.field(eligibleWhen, ex));
        Bound exempt = Bound.parseRatio("exemptWhen", Vars.field(exemptWhen, ex));
        String[] cont = continuation(Vars.field(continuation, ex));
        String comparisonColumn = Vars.field(exemptComparison, ex);
        List<Map<String, Object>> list = register(ex, listVar, "EACH");
        Map<String, Object> inputs = new LinkedHashMap<>();
        Map<String, Object> ruleRow = null;
        String ruleKey = Vars.field(rules, ex);
        if (cont != null && (eligible == null || ruleKey == null)) {
            throw refused("CALCULATION_INVALID", "field continuation needs eligibleWhen and the versioned rules");
        }
        if (comparisonColumn != null && (exempt == null || ruleKey == null)) {
            throw refused("CALCULATION_INVALID", "field exemptComparison needs exemptWhen and the versioned rules");
        }
        if (ruleKey != null) {
            ruleRow = row(ex, ruleKey.trim(), asOf, rowsAsOf);
            if (ruleRow.isEmpty()) {
                throw refused("RULES_MISSING", "rules " + ruleKey + " matched no row");
            }
            String ref = ruleRow.get("ruleId") + "@" + ruleRow.get("ruleVersion");
            String[] eu = RowEvidence.evidenceAndUse(ruleRow, "RULES", "rules " + ruleKey + " row " + ref);
            RowEvidence.inForce("RULES", "rules " + ruleKey + " row " + ref, eu[1], ruleRow,
                    checkDay(rowsAsOf, ruleKey.trim()));
            ex.setVariable(target + "Rules", ref);
            ex.setVariable(target + "RulesEvidence", eu[0]);
            ex.setVariable(target + "RulesUse", eu[1]);
        }
        BigDecimal capLimit = capBound == null ? null : limit(ex, capBound.limit(), ruleRow, inputs);
        BigDecimal eligibleLimit = eligible == null ? null : limit(ex, eligible.limit(), ruleRow, inputs);
        BigDecimal exemptLimit = exempt == null ? null : limit(ex, exempt.limit(), ruleRow, inputs);
        if (cont == null && (Vars.field(continuationEnd, ex) != null || Vars.field(joiner, ex) != null)) {
            throw refused("CALCULATION_INVALID", "fields continuationEnd and joiner need continuation");
        }
        String basisSpec = Vars.field(exemptBasis, ex);
        if (basisSpec != null && (exempt == null || exempt.per() == null || ruleKey == null)) {
            throw refused("CALCULATION_INVALID", "field exemptBasis needs exemptWhen 'wageField/ratingField<=limit'"
                    + " and the versioned rules");
        }
        AverageDailyWage daily = AverageDailyWage.of(basisSpec, ruleRow, ruleKey);
        ContributionCoverage cov = cont == null ? null : ContributionCoverage.of(ruleRow, ruleKey, cont,
                Vars.field(continuationEnd, ex), Vars.field(joiner, ex), LocalDate.parse(asOf.get(0)),
                LocalDate.parse(asOf.get(asOf.size() - 1)));
        String stated = Vars.field(periodFrom, ex);
        if (eligible != null && stated != null && !stated.isBlank()) {
            wholeMonth(LocalDate.parse(asOf.get(0)), LocalDate.parse(asOf.get(asOf.size() - 1)));
        }
        boolean continues = cov != null && cov.continues();
        LocalDate periodStart = continues ? cov.firstPeriodStart() : null;
        boolean strictlyBelow = comparisonColumn != null && strictlyBelow(ruleRow, comparisonColumn.trim(), ruleKey);
        Set<String> seen = new HashSet<>();
        List<String> trail = new ArrayList<>(list.size());
        List<String> rounding = new ArrayList<>(list.size());
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal exactSum = BigDecimal.ZERO;
        for (Map<String, Object> item : list) {
            String id = key(item, keyField, listVar, seen);
            String note = "";
            Map<String, BigDecimal> local = new HashMap<>();
            if (eligible != null) {
                BigDecimal wages = itemAmount(item, eligible.field(), id);
                boolean ok = wages.compareTo(eligibleLimit) <= 0;
                boolean covered = continues && cov.continued(item, id, !ok);
                if (covered) {
                    item.put(out + "Continued", true);
                    note = ":CONTINUED";
                }
                item.put(out + "Eligible", ok || covered);
                if (!ok && !covered) {
                    // decision 282: above the limit with no live continuation: not covered for this period, traced
                    // with the rules row (ruleId@ruleVersion) that decided it
                    item.put(out + "CoverageReason", "ABOVE_LIMIT_NO_LIVE_CONTINUATION");
                    item.put(out + "CoverageRules", ex.getVariable(target + "Rules"));
                    zero(item, out, id, rounding);
                    trail.add(id + ":0:INELIGIBLE");
                    continue;
                }
            }
            if (exempt != null) {
                BigDecimal perDay;
                if (daily != null) {
                    perDay = daily.perDay(item, id, exempt.field(), exempt.per(), stated == null ? null : asOf);
                    item.put(out + "AverageDailyWage", perDay.toPlainString());
                } else {
                    BigDecimal per = itemAmount(item, exempt.per(), id);
                    if (per.signum() <= 0 || per.stripTrailingZeros().scale() > 0) {
                        throw refused("ITEM_INVALID", "op EACH: item " + id + " has " + exempt.per() + " = " + per
                                + "; it must be a positive whole number");
                    }
                    perDay = itemAmount(item, exempt.field(), id).divide(per, DecimalFormula.DIVISION);
                }
                int cmp = perDay.compareTo(exemptLimit);
                boolean free = strictlyBelow ? cmp < 0 : cmp <= 0;
                item.put(out + "Exempt", free);
                if (free) {
                    zero(item, out, id, rounding);
                    trail.add(id + ":0:EXEMPT" + note);
                    continue;
                }
            }
            if (capBound != null) {
                BigDecimal w = itemAmount(item, capBound.field(), id);
                boolean capped = w.compareTo(capLimit) > 0;
                BigDecimal base = capped ? capLimit : w;
                local.put(capBound.field(), base);
                item.put(out + "Base", Vars.engineValue(base));
                note += capped ? ":CAPPED" : "";
            }
            java.util.function.Function<String, BigDecimal> values = name -> local.containsKey(name) ? local.get(name)
                    : item.containsKey(name) ? Vars.decimal(item.get(name)) : read(ex, name, inputs);
            BigDecimal exact = f.evaluate(values, v -> v);
            BigDecimal value = p.apply(f.roundsComponents() ? f.evaluate(values, p::apply) : exact);
            item.put(out, Vars.engineValue(value));
            trace(item, out, id, exact, value, rounding);
            sum = sum.add(value);
            exactSum = exactSum.add(exact);
            trail.add(id + ":" + value.toPlainString() + note);
        }
        Vars.setJsonDocument(ex, listVar, list);
        ex.setVariable(target, Vars.engineValue(sum));
        ex.setVariable(target + "Policy", p.ref());
        ex.setVariable(target + "PolicyEvidence", p.evidence());
        ex.setVariable(target + "PolicyUse", p.use());
        Map<String, Object> details = Vars.map("result", target, "op", "EACH", "items", listVar, "itemResult", out,
                "formula", f.text(), "inputs", inputs, "cap", capBound == null ? null : capBound.text(),
                "eligibleWhen", eligible == null ? null : eligible.text(), "rules", ex.getVariable(target + "Rules"),
                "rulesEvidence", ex.getVariable(target + "RulesEvidence"), "rulesUse",
                ex.getVariable(target + "RulesUse"), "count", list.size(),
                "continuation", cont == null ? null : String.join("@", cont) + (continues ? "" : ":OFF"),
                "coverage", cov == null ? null : cov.text(),
                "contributionPeriodFrom", periodStart == null ? null : periodStart.toString(),
                "contributionPeriodTo", periodStart == null ? null : cov.firstPeriodEnd().toString(),
                "exemptWhen", exempt == null ? null : exempt.text(), "exemptComparison", comparisonColumn == null
                        ? null : (strictlyBelow ? "BELOW" : "AT_OR_BELOW"), "averageDailyWage", daily == null ? null
                        : daily.text(), "asOf", asOf, "rowsAsOf", rowsAsOf,
                "exact", exactSum.toString(), "value", sum.toPlainString(), "policy", p.ref(), "evidence",
                p.evidence(), "use", p.use(), "mode", p.mode().name(), "quantumMinor",
                p.quantumMinor().toPlainString(), "itemValues", trail,
                "itemRounding", rounding);
        for (Map.Entry<String, Object> e : p.row().entrySet()) {
            details.putIfAbsent("policy." + e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        Vars.audit(ex, AUDIT_TYPE, details);
    }

    /**
     * Decision 272: the item's unrounded amount, its final amount and the rounding difference (final minus
     * unrounded), as exact decimal text on the item and as {@code id:exact:value:difference} in the audit trace.
     */
    static void trace(Map<String, Object> item, String out, String id, BigDecimal exact, BigDecimal value,
                      List<String> rounding) {
        String difference = value.subtract(exact).stripTrailingZeros().toPlainString();
        String unrounded = exact.stripTrailingZeros().toPlainString();
        item.put(out + "Exact", unrounded);
        item.put(out + "RoundingDifference", difference);
        rounding.add(id + ":" + unrounded + ":" + value.toPlainString() + ":" + difference);
    }

    /** An item that bears no amount for this share (ineligible or exempt): result, unrounded amount and difference 0. */
    private static void zero(Map<String, Object> item, String out, String id, List<String> rounding) {
        item.put(out, 0L);
        trace(item, out, id, BigDecimal.ZERO, BigDecimal.ZERO, rounding);
    }

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z_]\\w*");

    /**
     * {@code field<=limit} of op EACH: the item field and the name of its limit; for {@code exemptWhen}
     * {@code field/per<=limit}, the item field divided by the item field {@code per}.
     */
    record Bound(String field, String per, String limit) {
        Bound(String field, String limit) {
            this(field, null, limit);
        }

        static Bound parse(String name, String text) {
            Bound b = parseRatio(name, text);
            if (b != null && b.per() != null) {
                throw refused("CALCULATION_INVALID", "field " + name + " is 'field<=limit', not '" + text + "'");
            }
            return b;
        }

        static Bound parseRatio(String name, String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String[] parts = text.split("<=", -1);
            String[] left = parts[0].split("/", -1);
            if (parts.length != 2 || left.length > 2 || !NAME.matcher(parts[1].trim()).matches()
                    || java.util.Arrays.stream(left).anyMatch(s -> !NAME.matcher(s.trim()).matches())) {
                throw refused("CALCULATION_INVALID", "field " + name + " is 'field<=limit' or 'field/per<=limit',"
                        + " not '" + text + "'");
            }
            return new Bound(left[0].trim(), left.length == 2 ? left[1].trim() : null, parts[1].trim());
        }

        String text() {
            return field + (per == null ? "" : "/" + per) + "<=" + limit;
        }
    }

    /** {@code continuation} of op EACH (decision 256), see {@link ContributionCoverage#continuation}. */
    static String[] continuation(String text) {
        return ContributionCoverage.continuation(text);
    }

    /** The first day of the contribution period containing {@code day} (decision 256), from the rules column. */
    static LocalDate contributionPeriodStart(Map<String, Object> ruleRow, String column, String ruleKey,
                                             LocalDate day) {
        return ContributionCoverage.periodStart(ContributionCoverage.periodStarts(ruleRow, column, ruleKey), day);
    }

    /** Whether the rules row's comparison column says {@code BELOW}; {@code AT_OR_BELOW} is false; else refused. */
    private static boolean strictlyBelow(Map<String, Object> ruleRow, String column, String ruleKey) {
        Object v = ruleRow.get(column);
        String s = v == null ? "" : v.toString().trim();
        if (!s.equals("AT_OR_BELOW") && !s.equals("BELOW")) {
            throw refused("RULES_INVALID", "rules " + ruleKey + ": column " + column + " must be AT_OR_BELOW or"
                    + " BELOW, not '" + s + "'");
        }
        return s.equals("BELOW");
    }

    /** The register of op EACH or TOTAL: a JSON list of records, refused when missing or empty. */
    private static List<Map<String, Object>> register(DelegateExecution ex, String listVar, String op) {
        if (listVar == null || listVar.isBlank()) {
            throw refused("CALCULATION_INVALID", "op " + op + " needs field items");
        }
        List<Map<String, Object>> list = ex.getVariable(listVar) == null ? List.of() : Vars.jsonList(ex, listVar);
        if (list.isEmpty()) {
            throw refused("REGISTER_EMPTY", "op " + op + ": " + listVar + " has no items; an empty register is"
                    + " refused, never computed as a zero total");
        }
        return list;
    }

    /** The key of one item: present and unique in the register. */
    private static String key(Map<String, Object> item, String keyField, String listVar, Set<String> seen) {
        Object k = item == null ? null : item.get(keyField);
        if (k == null || k.toString().isBlank()) {
            throw refused("ITEM_INVALID", "an item of " + listVar + " has no " + keyField);
        }
        String id = k.toString().trim();
        if (!seen.add(id)) {
            throw refused("ITEM_DUPLICATE", keyField + " " + id + " appears twice in " + listVar);
        }
        return id;
    }

    /** {@code TOTAL}: the exact sum of one item field over the register; nothing is rounded. */
    private void total(DelegateExecution ex, String target) {
        String listVar = Vars.field(items, ex);
        String field = Vars.field(formula, ex);
        String keyField = Vars.field(itemKey, ex, "employeeId");
        if (field == null || !NAME.matcher(field.trim()).matches()) {
            throw refused("CALCULATION_INVALID", "op TOTAL needs field formula naming one item field");
        }
        field = field.trim();
        List<Map<String, Object>> list = register(ex, listVar, "TOTAL");
        Set<String> seen = new HashSet<>();
        List<String> trail = new ArrayList<>(list.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> item : list) {
            String id = key(item, keyField, listVar, seen);
            BigDecimal v = itemAmount(item, field, id);
            if (v.signum() != 0 && v.stripTrailingZeros().scale() > 0) {
                throw refused("ITEM_INVALID", "op TOTAL: item " + id + " " + field + " = " + v
                        + " is not whole minor units");
            }
            sum = sum.add(v);
            trail.add(id + ":" + v.toPlainString());
        }
        ex.setVariable(target, Vars.engineValue(sum));
        Vars.audit(ex, AUDIT_TYPE, Vars.map("result", target, "op", "TOTAL", "items", listVar, "field", field,
                "count", list.size(), "exact", sum.toString(), "value", sum.toPlainString(), "policy",
                "SUM_OF_ITEMS", "itemValues", trail));
    }

    /** A limit: the rules row's output of that name when a rules DMN is used, otherwise the case variable. */
    private static BigDecimal limit(DelegateExecution ex, String name, Map<String, Object> ruleRow,
                                    Map<String, Object> inputs) {
        BigDecimal v;
        if (ruleRow != null && ruleRow.containsKey(name)) {
            v = Vars.decimal(ruleRow.get(name));
            inputs.put("rules." + name, v == null ? null : v.toString());
        } else {
            v = read(ex, name, inputs);
        }
        if (v == null || v.signum() < 0) {
            throw refused("FORMULA_MISSING", "limit " + name + " has no value or is negative");
        }
        return v;
    }

    private static BigDecimal itemAmount(Map<String, Object> item, String field, String id) {
        BigDecimal v = Vars.decimal(item.get(field));
        if (v == null || v.signum() < 0) {
            throw refused("ITEM_INVALID", "op EACH: item " + id + " has no " + field + " or it is negative");
        }
        return v;
    }

    /**
     * The dates that select the policy and rules rows: the first and last day of the wage period when fields
     * {@code periodFrom} and {@code periodTo} are set (decision 223), otherwise the tenant's business date.
     */
    private List<String> asOf(DelegateExecution ex) {
        String fromVar = Vars.field(periodFrom, ex);
        String toVar = Vars.field(periodTo, ex);
        boolean hasFrom = fromVar != null && !fromVar.isBlank();
        boolean hasTo = toVar != null && !toVar.isBlank();
        if (!hasFrom && !hasTo) {
            return List.of(Vars.today(Vars.tenant(ex)).toString());
        }
        if (hasFrom != hasTo) {
            throw refused("CALCULATION_INVALID", "fields periodFrom and periodTo are set together or not at all");
        }
        LocalDate from = periodDate(ex, fromVar.trim());
        LocalDate to = periodDate(ex, toVar.trim());
        if (to.isBefore(from)) {
            throw refused("PERIOD_INVALID", "wage period " + from + " to " + to + " ends before it begins");
        }
        return from.equals(to) ? List.of(from.toString()) : List.of(from.toString(), to.toString());
    }

    /**
     * Decision 281: coverage by eligibility is decided only for a wage period that is one whole calendar month (first
     * to last day of the same month); any other stated period (a joiner's first partial month, a fortnight, two
     * months) is refused until the official basis for such periods is read and recorded. Checked after the
     * contribution-period crossing, which keeps its own code (decision 270).
     */
    static void wholeMonth(LocalDate from, LocalDate to) {
        if (!isCalendarMonth(from, to)) {
            throw refused("WAGE_PERIOD_NOT_WHOLE_MONTH_UNVERIFIED", "wage period " + from + " to " + to + " is not one"
                    + " whole calendar month; the official basis for such a period is not recorded (decision 281)");
        }
    }

    /** First to last day of the same month. */
    static boolean isCalendarMonth(LocalDate from, LocalDate to) {
        return from.getDayOfMonth() == 1 && to.equals(from.withDayOfMonth(from.lengthOfMonth()));
    }

    /**
     * Decision 293: field {@code periodUnit} {@code CALENDAR_MONTH} (any other value refused) needs a stated wage
     * period that is one whole calendar month; anything else is {@code WAGE_PERIOD_NOT_CALENDAR_MONTH}.
     */
    static void calendarMonth(String unit, String periodFromField, List<String> asOf) {
        if (unit == null || unit.isBlank()) {
            return;
        }
        if (!unit.trim().equals("CALENDAR_MONTH")) {
            throw refused("CALCULATION_INVALID", "field periodUnit is CALENDAR_MONTH, not '" + unit + "'");
        }
        LocalDate from = LocalDate.parse(asOf.get(0));
        LocalDate to = LocalDate.parse(asOf.get(asOf.size() - 1));
        if (periodFromField == null || periodFromField.isBlank() || !isCalendarMonth(from, to)) {
            throw refused("WAGE_PERIOD_NOT_CALENDAR_MONTH", "this calculation works on the wages of a calendar month"
                    + " (decision 293; for PF, EPF Scheme 2026 para 18(4)); the wage period "
                    + (periodFromField == null || periodFromField.isBlank() ? "is not stated" : from + " to " + to
                    + " is not one") + "; monthly aggregation across wage periods is not built");
        }
    }

    static LocalDate periodDate(DelegateExecution ex, String name) {
        Object v = ex.getVariable(name);
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v == null || v.toString().isBlank()) {
            throw refused("PERIOD_MISSING", "wage period date " + name + " has no value; the rules of the wage period"
                    + " cannot be selected");
        }
        try {
            return LocalDate.parse(v.toString().trim());
        } catch (DateTimeParseException e) {
            throw refused("PERIOD_INVALID", "wage period date " + name + " = '" + v + "' is not an ISO date");
        }
    }

    public static final String PERIOD_RULE_BASIS = "periodRuleBasis";

    /**
     * The row of DMN {@code key} that applies to {@code asOf} (the wage period's first and last day, or the business
     * date), from the bound deployment (decision 255): the row in force on the first day, unless the row in force on
     * the last day declares {@value #PERIOD_RULE_BASIS} {@code LAST_DAY}. The day and basis used are put in
     * {@code rowsAsOf} under the key.
     */
    private static Map<String, Object> row(DelegateExecution ex, String key, List<String> asOf,
                                           Map<String, Object> rowsAsOf) {
        String deploymentId = ex.getProcessEngineServices().getRepositoryService()
                .getProcessDefinition(ex.getProcessDefinitionId()).getDeploymentId();
        List<Decisions.Evaluation> evs = new ArrayList<>(asOf.size());
        for (String date : asOf) {
            evs.add(Decisions.evaluate(ex.getProcessEngineServices(), Vars.tenant(ex), key,
                    Map.of("asOfDate", date), deploymentId));
        }
        Map<String, Object> last = evs.get(evs.size() - 1).single();
        Object b = last == null ? null : last.get(PERIOD_RULE_BASIS);
        String basis = b == null || b.toString().isBlank() ? "FIRST_DAY" : b.toString().trim();
        if (!basis.equals("FIRST_DAY") && !basis.equals("LAST_DAY")) {
            throw refused("RULES_INVALID", key + ": " + PERIOD_RULE_BASIS + " is FIRST_DAY or LAST_DAY, not '"
                    + basis + "'");
        }
        int chosen = basis.equals("LAST_DAY") ? evs.size() - 1 : 0;
        Decisions.Evaluation ev = evs.get(chosen);
        ex.setVariable(key.replace('-', '_') + "_version", ev.version());
        Map<String, Object> r = ev.single();
        boolean same = evs.size() == 1 || Objects.equals(evs.get(0).single(), last);
        rowsAsOf.put(key, asOf.get(chosen) + " " + basis + (same ? "" : " RULE_CHANGED_IN_PERIOD"));
        return r == null ? Map.of() : r;
    }

    static BigDecimal read(DelegateExecution ex, String name, Map<String, Object> inputs) {
        BigDecimal v = Vars.decimal(ex.getVariable(name));
        inputs.put(name, v == null ? null : v.toString());
        return v;
    }

    private static BigDecimal need(DelegateExecution ex, String name, Map<String, Object> inputs) {
        if (name == null || name.isBlank()) {
            throw refused("CALCULATION_INVALID", "an instalment needs fields total, count and number");
        }
        BigDecimal v = read(ex, name, inputs);
        if (v == null) {
            throw refused("FORMULA_MISSING", "variable " + name + " has no value");
        }
        return v;
    }
}
