// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.returns;

import org.sangam.programme.config.IndicatorDefinition;
import org.sangam.programme.config.ProgrammeConfig;
import org.sangam.programme.config.ReturnDefinition;
import org.sangam.programme.indicator.FormulaEvaluator;
import org.sangam.programme.ports.ProgrammePorts.ExternalFinding;
import org.sangam.programme.ports.ProgrammePorts.ReturnValidator;
import org.sangam.programme.support.Db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validation of a return (N15 "validation DMN" plus built-in rules). Built-in rules come from each indicator's
 * {@code validation} map and the return definition:
 * <ul>
 *   <li>{@code required} (return item) — a value must be present;</li>
 *   <li>{@code min}, {@code max} — numeric bounds;</li>
 *   <li>{@code integer} — whole numbers only; the indicator's {@code decimals} bounds the scale;</li>
 *   <li>{@code nonDecreasing} (default for cumulative indicators) — not below the previous period's value;</li>
 *   <li>{@code maxOfTarget} — not above ratio × approved target (e.g. 1.5), severity {@code targetSeverity};</li>
 *   <li>definition {@code rules} — comparisons over {@code key}, {@code prev.key} and {@code target.key}.</li>
 * </ul>
 * An external {@link ReturnValidator} (DMN, bound by the integrator) runs when the definition names a decision key.
 * ERROR findings block submission/validation; WARNING findings are recorded for the checker.
 */
public final class ValidationEngine {

    public enum Severity { ERROR, WARNING }

    public record Finding(String indicatorKey, Severity severity, String code, String message) {
    }

    private final ReturnValidator external;

    public ValidationEngine(ReturnValidator external) {
        this.external = external == null ? ReturnValidator.NONE : external;
    }

    /**
     * @param values   reported numeric values (indicator key → value)
     * @param texts    reported text values (for non-numeric items)
     * @param previous previous-period values
     * @param targets  approved targets for the period
     * @param withExternal whether to run the external (DMN) validator too
     */
    public List<Finding> validate(Connection c, String tenant, ProgrammeConfig cfg, ReturnDefinition def, Map<String, BigDecimal> values,
                                  Map<String, String> texts, Map<String, BigDecimal> previous, Map<String, BigDecimal> targets,
                                  Map<String, Object> context, boolean withExternal) {
        List<Finding> out = new ArrayList<>();
        for (ReturnDefinition.Item item : def.items()) {
            String k = item.indicatorKey();
            IndicatorDefinition ind = cfg.indicators().get(k);
            BigDecimal v = values.get(k);
            boolean hasText = texts.get(k) != null && !texts.get(k).isBlank();
            if (v == null) {
                if (item.required() && !hasText) {
                    out.add(new Finding(k, Severity.ERROR, "REQUIRED", label(item, ind) + " is required"));
                }
                continue;
            }
            if (ind == null) {
                continue;
            }
            Map<String, Object> rule = ind.validation();
            BigDecimal min = Db.dec(rule.get("min"));
            BigDecimal max = Db.dec(rule.get("max"));
            if (min != null && v.compareTo(min) < 0) {
                out.add(new Finding(k, Severity.ERROR, "MIN", label(item, ind) + " is below " + min.toPlainString()));
            }
            if (max != null && v.compareTo(max) > 0) {
                out.add(new Finding(k, Severity.ERROR, "MAX", label(item, ind) + " is above " + max.toPlainString()));
            }
            if (Db.boolVal(rule, "integer", false) && v.stripTrailingZeros().scale() > 0) {
                out.add(new Finding(k, Severity.ERROR, "INTEGER", label(item, ind) + " must be a whole number"));
            } else if (v.stripTrailingZeros().scale() > ind.decimals()) {
                out.add(new Finding(k, Severity.ERROR, "DECIMALS", label(item, ind) + " allows " + ind.decimals() + " decimals"));
            }
            boolean nonDecreasing = Db.boolVal(rule, "nonDecreasing", ind.cumulative());
            BigDecimal prev = previous.get(k);
            if (nonDecreasing && prev != null && v.compareTo(prev) < 0) {
                out.add(new Finding(k, Severity.valueOf(Db.str(rule, "nonDecreasingSeverity", "ERROR")), "DECREASED",
                        label(item, ind) + " is below the previous period (" + prev.toPlainString() + ")"));
            }
            BigDecimal ratio = Db.dec(rule.get("maxOfTarget"));
            BigDecimal target = targets.get(k);
            if (ratio != null && target != null && target.signum() > 0 && v.compareTo(target.multiply(ratio)) > 0) {
                out.add(new Finding(k, Severity.valueOf(Db.str(rule, "targetSeverity", "WARNING")), "ABOVE_TARGET",
                        label(item, ind) + " exceeds " + ratio.toPlainString() + " x target"));
            }
        }
        Map<String, BigDecimal> scope = new LinkedHashMap<>(values);
        previous.forEach((k, v) -> scope.put("prev." + k, v));
        targets.forEach((k, v) -> scope.put("target." + k, v));
        for (Map<String, Object> rule : def.rules()) {
            String expr = Db.str(rule.get("expr"));
            Boolean ok = FormulaEvaluator.test(expr, scope::get);
            Severity severity = Severity.valueOf(Db.str(rule, "severity", "ERROR"));
            if (Boolean.FALSE.equals(ok)) {
                out.add(new Finding(Db.str(rule.get("indicator")), severity,
                        Db.str(rule, "code", "RULE"), Db.str(rule, "message", "rule failed: " + expr)));
            } else if (ok == null) {
                // ADR-D-26 strictness: an undetermined rule (missing value, division by zero) is not a pass.
                List<String> missing = FormulaEvaluator.references(expr).stream().filter(r -> scope.get(r) == null).toList();
                if (!skipWhenMissing(rule, missing)) {
                    out.add(new Finding(Db.str(rule.get("indicator")), severity, "RULE_UNDETERMINED",
                            "rule could not be evaluated" + (missing.isEmpty() ? " (division by zero)"
                                    : "; missing " + String.join(", ", missing)) + ": " + expr));
                }
            }
        }
        if (withExternal && def.validationDmnKey() != null) {
            Map<String, Object> inputs = new LinkedHashMap<>(context == null ? Map.of() : context);
            values.forEach((k, v) -> inputs.put(k, v));
            texts.forEach(inputs::putIfAbsent);
            previous.forEach((k, v) -> inputs.put("prev_" + k, v));
            targets.forEach((k, v) -> inputs.put("target_" + k, v));
            for (ExternalFinding f : external.validate(c, tenant, def.validationDmnKey(), inputs)) {
                Severity s = "ERROR".equalsIgnoreCase(f.severity()) ? Severity.ERROR : Severity.WARNING;
                out.add(new Finding(f.indicatorKey(), s, f.code() == null ? "DMN" : f.code(), f.message()));
            }
        }
        return out;
    }

    /**
     * Rule option {@code whenMissing}: {@code fail} reports {@code RULE_UNDETERMINED}, {@code skip} passes. The default
     * is {@code skip} only when every missing reference is history or plan ({@code prev.*}, {@code target.*}: a first
     * period or an unapproved target is legitimate), and {@code fail} when a reported value of this period is missing
     * or the rule divides by zero.
     */
    static boolean skipWhenMissing(Map<String, Object> rule, List<String> missing) {
        String mode = Db.str(rule, "whenMissing", null);
        if (mode != null) {
            return "skip".equals(mode);
        }
        return !missing.isEmpty() && missing.stream().allMatch(r -> r.startsWith("prev.") || r.startsWith("target."));
    }

    public static boolean hasErrors(List<Finding> findings) {
        return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
    }

    private static String label(ReturnDefinition.Item item, IndicatorDefinition ind) {
        return item.label() != null ? item.label() : ind != null ? ind.name() : item.indicatorKey();
    }
}
