// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;

/**
 * Ops {@code SUM} and {@code EXACT} of {@link MoneyCalculationDelegate}.
 *
 * <p>{@code EXACT} (integrator ruling C, owner rules E1-E3, C4): no rounding policy, nothing is ever rounded. The
 * formula is evaluated exactly; optional {@code min} and {@code max} (a variable name or a number, in minor units)
 * bound it. A missing input or bound is held ({@code MONEY_OUT_HELD}), never defaulted. A whole minor-unit result is
 * written to {@code result} with {@code <result>Held=false}; otherwise {@code result} is removed and
 * {@code <result>Held=true}, {@code <result>Exact} and {@code <result>HeldReason} are set (audit {@code MONEY_OUT_HELD}),
 * so the process routes to a reviewer who enters {@code <result>Entered}, approved under the same sanction. On the next
 * run the entered amount is accepted only when it is whole minor units, within the bounds and one of the two whole
 * amounts next to the exact value; anything else is held again.
 */
final class MoneyExactCalculation {

    private MoneyExactCalculation() {
    }

    /** {@code EXACT} (ruling C): exact value, bounded, whole minor units or held for the reviewer's amount. */
    static void exact(DelegateExecution ex, String target, String formulaText, String minSpec, String maxSpec,
                      Map<String, Object> inputs) {
        DecimalFormula f = DecimalFormula.parse(formulaText);
        if (f.roundsComponents()) {
            throw MoneyCalculationDelegate.refused("CALCULATION_INVALID", "op EXACT never rounds, so round(...) is not allowed");
        }
        for (String name : f.variables()) {
            if (MoneyCalculationDelegate.read(ex, name, inputs) == null) {
                throw held(ex, target, "input '" + name + "' of " + target + " is missing");
            }
        }
        BigDecimal value = f.evaluate(name -> MoneyCalculationDelegate.read(ex, name, inputs), v -> v);
        BigDecimal lo = bound(ex, target, "min", minSpec, inputs);
        BigDecimal hi = bound(ex, target, "max", maxSpec, inputs);
        if (lo != null && hi != null && lo.compareTo(hi) > 0) {
            throw held(ex, target, "min " + lo.toPlainString() + " is above max " + hi.toPlainString());
        }
        BigDecimal bounded = value;
        if (lo != null && bounded.compareTo(lo) < 0) {
            bounded = lo;
        }
        if (hi != null && bounded.compareTo(hi) > 0) {
            bounded = hi;
        }
        String exactText = bounded.stripTrailingZeros().toPlainString();
        Map<String, Object> details = Vars.map("result", target, "formula", f.text(), "inputs", inputs,
                "exact", value.toString(), "bounded", exactText, "min", lo == null ? null : lo.toPlainString(),
                "max", hi == null ? null : hi.toPlainString(), "policy", "EXACT_NO_ROUNDING");
        if (whole(bounded)) {
            accept(ex, target, bounded, exactText, details, null);
            return;
        }
        Object enteredRaw = ex.getVariable(target + "Entered");
        if (enteredRaw != null) {
            BigDecimal entered;
            try {
                entered = Vars.decimal(enteredRaw);
            } catch (RuntimeException e) {
                entered = null;
            }
            BigDecimal down = bounded.setScale(0, java.math.RoundingMode.FLOOR);
            BigDecimal up = bounded.setScale(0, java.math.RoundingMode.CEILING);
            boolean adjacent = entered != null && whole(entered)
                    && (entered.compareTo(down) == 0 || entered.compareTo(up) == 0);
            boolean inBounds = entered != null && (lo == null || entered.compareTo(lo) >= 0)
                    && (hi == null || entered.compareTo(hi) <= 0);
            if (adjacent && inBounds) {
                accept(ex, target, entered.setScale(0), exactText, details, "REVIEWER_ENTERED");
                return;
            }
            ex.removeVariable(target + "Entered");
            hold(ex, target, exactText, details, "entered " + target + " " + enteredRaw + " is not one of the whole"
                    + " minor-unit amounts next to the exact " + exactText + " within the bounds");
            return;
        }
        hold(ex, target, exactText, details, "exact " + target + " " + exactText + " has more decimals than the"
                + " currency's minor unit; the reviewer enters the payable amount (never rounded silently)");
    }

    private static boolean whole(BigDecimal v) {
        return v.signum() == 0 || v.stripTrailingZeros().scale() <= 0;
    }

    private static void accept(DelegateExecution ex, String target, BigDecimal value, String exactText,
                               Map<String, Object> details, String source) {
        ex.setVariable(target, Vars.engineValue(value.setScale(0)));
        ex.setVariable(target + "Held", Boolean.FALSE);
        ex.setVariable(target + "Exact", exactText);
        ex.removeVariable(target + "HeldReason");
        details.put("value", value.setScale(0).toPlainString());
        details.put("source", source == null ? "EXACT" : source);
        Vars.audit(ex, MoneyCalculationDelegate.AUDIT_TYPE, details);
    }

    private static void hold(DelegateExecution ex, String target, String exactText, Map<String, Object> details,
                             String reason) {
        ex.removeVariable(target);
        ex.setVariable(target + "Held", Boolean.TRUE);
        ex.setVariable(target + "Exact", exactText);
        ex.setVariable(target + "HeldReason", reason);
        details.put("reason", reason);
        Vars.audit(ex, MoneyOutApproval.HELD, details);
    }

    private static ProcessEngineException held(DelegateExecution ex, String target, String why) {
        Vars.audit(ex, MoneyOutApproval.HELD, Vars.map("result", target, "reason", why));
        return BatchSeal.held(why);
    }

    private static BigDecimal bound(DelegateExecution ex, String target, String which, String spec,
                                    Map<String, Object> inputs) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String s = spec.trim();
        if (s.matches("-?\\d+")) {
            return new BigDecimal(s);
        }
        BigDecimal v = MoneyCalculationDelegate.read(ex, s, inputs);
        if (v == null) {
            throw held(ex, target, which + " '" + s + "' of " + target + " is missing");
        }
        return v;
    }

    /** {@code SUM}: variables added or subtracted, each already whole minor units; nothing is rounded. */
    static void sum(DelegateExecution ex, String target, String text, Map<String, Object> inputs) {
        if (text == null || !SUM_FORMULA.matcher(text).matches()) {
            throw MoneyCalculationDelegate.refused("CALCULATION_INVALID", "op SUM takes variables joined by + or - only, not '" + text + "'");
        }
        DecimalFormula f = DecimalFormula.parse(text);
        BigDecimal value = f.evaluate(name -> {
            BigDecimal v = MoneyCalculationDelegate.read(ex, name, inputs);
            if (v != null && v.signum() != 0 && v.stripTrailingZeros().scale() > 0) {
                throw MoneyCalculationDelegate.refused("CALCULATION_INVALID", "op SUM: " + name + " = " + v + " is not whole minor units");
            }
            return v;
        }, v -> v);
        ex.setVariable(target, Vars.engineValue(value));
        Vars.audit(ex, MoneyCalculationDelegate.AUDIT_TYPE, Vars.map("result", target, "formula", text, "inputs", inputs,
                "exact", value.toString(), "value", value.toPlainString(), "policy", "SUM_OF_ROUNDED"));
    }

    private static final java.util.regex.Pattern SUM_FORMULA =
            java.util.regex.Pattern.compile("[A-Za-z_]\\w*( *[+-] *[A-Za-z_]\\w*)*");

}
