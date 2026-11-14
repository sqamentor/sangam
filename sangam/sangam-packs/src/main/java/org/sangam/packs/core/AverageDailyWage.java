// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.sangam.packs.core.MoneyCalculationDelegate.refused;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The average daily wage of the employee low-wage exemption of {@link MoneyCalculationDelegate} op {@code EACH}
 * (decisions 271 and 295): the prescribed definition for the employee's wage basis, a versioned rules field, never
 * wages per paid day or a calendar-day divisor. Field {@code exemptBasis} is
 * {@code itemBasisField@definitionColumn@statusColumn}; field {@code exemptWhen} is
 * {@code wageField/ratingField<=limit}:
 * <ul>
 *   <li>{@code itemBasisField}: the register field with the employee's wage basis (for example {@code TIME_RATED});
 *       missing or blank is refused ({@code ITEM_INVALID}).</li>
 *   <li>{@code definitionColumn}: the rules column defining the average daily wage per wage basis,
 *       {@code BASIS=METHOD} comma separated, each basis once. The only method is {@value #FULL_PERIOD_WAGE_BY_RATING},
 *       for basis {@value #TIME_RATED} only: Social Security (Central) Rules 2026 (G.S.R. 344(E)) rule 2(1)(h)(i), as
 *       read in sangam-handoff evidence/STATUTORY-EVIDENCE-288.md section 3.1 (decision 295): the wage that would have
 *       been payable for the complete wage period had the employee worked on all the working days in it (the item's
 *       {@code wageField}), divided by 26 if monthly rated, 13 if fortnightly rated, 6 if weekly rated and 1 if daily
 *       rated (the item's {@code ratingField}: {@code MONTHLY}, {@code FORTNIGHTLY}, {@code WEEKLY} or
 *       {@code DAILY}; anything else is refused, {@code ITEM_INVALID}). For example a monthly rate of 4,680 rupees
 *       gives 180 rupees a day, whatever leave was taken. The round 7 method {@code WAGES_PER_PAID_DAY} is not the
 *       rule and is refused like any other method ({@code RULES_INVALID}). An absent or blank column, and a basis it
 *       does not define (piece-rated pay: rule 2(1)(h)(ii), not coded), are refused
 *       ({@code RULES_AVERAGE_DAILY_WAGE_UNDEFINED}).</li>
 *   <li>{@code statusColumn}: how far that definition was checked, {@code VERIFIED}, {@code UNVERIFIED} or
 *       {@code SYNTHETIC_DEMO}; anything else is refused ({@code RULES_INVALID}). It is recorded with every
 *       calculation.</li>
 * </ul>
 * The exemption removes only the employee share: never the employer share or coverage. Its threshold (the old
 * Rs 176 a day) was not found in current official text after 8 May 2026, so the shipped rules rows stay UNVERIFIED and
 * SYNTHETIC_DEMO_ONLY (decision 298) and payroll stays blocked.
 */
final class AverageDailyWage {

    static final String FULL_PERIOD_WAGE_BY_RATING = "FULL_PERIOD_WAGE_BY_RATING";
    static final String TIME_RATED = "TIME_RATED";
    /** Rule 2(1)(h)(i): the divisor of each rating. */
    static final Map<String, BigDecimal> RATED_DAYS = Map.of("MONTHLY", BigDecimal.valueOf(26), "FORTNIGHTLY",
            BigDecimal.valueOf(13), "WEEKLY", BigDecimal.valueOf(6), "DAILY", BigDecimal.ONE);
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z_]\\w*");
    private static final java.util.regex.Pattern BASIS = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]*");

    private final String basisField;
    private final String definitionColumn;
    private final Map<String, String> methods;
    private final String status;

    private AverageDailyWage(String basisField, String definitionColumn, Map<String, String> methods, String status) {
        this.basisField = basisField;
        this.definitionColumn = definitionColumn;
        this.methods = methods;
        this.status = status;
    }

    /** The definition of one calculation from field {@code spec} and the rules row; null when {@code spec} is blank. */
    static AverageDailyWage of(String spec, Map<String, Object> ruleRow, String ruleKey) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String[] parts = spec.split("@", -1);
        if (parts.length != 3 || java.util.Arrays.stream(parts).anyMatch(s -> !NAME.matcher(s.trim()).matches())) {
            throw refused("CALCULATION_INVALID", "field exemptBasis is 'itemBasisField@definitionColumn@statusColumn',"
                    + " not '" + spec + "'");
        }
        String column = parts[1].trim();
        Object d = ruleRow == null ? null : ruleRow.get(column);
        String definition = d == null ? "" : d.toString().trim();
        if (definition.isEmpty()) {
            throw refused("RULES_AVERAGE_DAILY_WAGE_UNDEFINED", "rules " + ruleKey + ": column " + column
                    + " does not define the average daily wage of the low-wage exemption; it is never assumed");
        }
        Map<String, String> methods = new LinkedHashMap<>();
        for (String pair : definition.split(",", -1)) {
            String[] kv = pair.split("=", -1);
            String basis = kv.length == 2 ? kv[0].trim() : "";
            String method = kv.length == 2 ? kv[1].trim() : "";
            if (!BASIS.matcher(basis).matches() || !basis.equals(TIME_RATED)
                    || !method.equals(FULL_PERIOD_WAGE_BY_RATING) || methods.putIfAbsent(basis, method) != null) {
                throw refused("RULES_INVALID", "rules " + ruleKey + ": column " + column + " is " + TIME_RATED + "="
                        + FULL_PERIOD_WAGE_BY_RATING + " (rule 2(1)(h)(i), decision 295; no other basis or method is"
                        + " coded), not '" + definition + "'");
            }
        }
        String statusColumn = parts[2].trim();
        Object st = ruleRow.get(statusColumn);
        String status = st == null ? "" : st.toString().trim();
        if (!status.equals("VERIFIED") && !status.equals("UNVERIFIED") && !status.equals("SYNTHETIC_DEMO")) {
            throw refused("RULES_INVALID", "rules " + ruleKey + ": column " + statusColumn + " is VERIFIED, UNVERIFIED"
                    + " or SYNTHETIC_DEMO, not '" + status + "'");
        }
        return new AverageDailyWage(parts[0].trim(), column, java.util.Collections.unmodifiableMap(methods), status);
    }

    /**
     * The average daily wage of item {@code id} under rule 2(1)(h)(i): its full-period wage {@code wageField} divided
     * by the rated days of its rating {@code ratingField}.
     */
    BigDecimal perDay(Map<String, Object> item, String id, String wageField, String ratingField) {
        Object b = item.get(basisField);
        String basis = b == null ? "" : b.toString().trim();
        if (basis.isEmpty()) {
            throw refused("ITEM_INVALID", "op EACH: item " + id + " has no wage basis " + basisField);
        }
        if (!methods.containsKey(basis)) {
            throw refused("RULES_AVERAGE_DAILY_WAGE_UNDEFINED", "op EACH: item " + id + " has wage basis " + basis
                    + ", for which column " + definitionColumn + " defines no average daily wage; it is never"
                    + " assumed (decisions 271, 295)");
        }
        Object r = item.get(ratingField);
        BigDecimal days = r == null ? null : RATED_DAYS.get(r.toString().trim());
        if (days == null) {
            throw refused("ITEM_INVALID", "op EACH: item " + id + " has " + ratingField + " = " + r + "; the rating"
                    + " is MONTHLY, FORTNIGHTLY, WEEKLY or DAILY (rule 2(1)(h)(i))");
        }
        BigDecimal wage = Vars.decimal(item.get(wageField));
        if (wage == null || wage.signum() < 0) {
            throw refused("ITEM_INVALID", "op EACH: item " + id + " has no " + wageField + " (the wage for the"
                    + " complete wage period had every working day been worked) or it is negative");
        }
        return wage.divide(days, DecimalFormula.DIVISION);
    }

    String status() {
        return status;
    }

    String text() {
        return basisField + "@" + definitionColumn + ":" + methods + ":" + status;
    }
}
