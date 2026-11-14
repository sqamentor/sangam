// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.sangam.packs.core.MoneyCalculationDelegate.refused;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coverage eligibility by continuation for {@link MoneyCalculationDelegate} op {@code EACH} (decisions 256, 264, 270
 * and 271), decided by the versioned rules row and the register, never by a caller flag. Coverage eligibility is one
 * rule; the employee low-wage exemption ({@link AverageDailyWage}) and the contribution-bearing wages (the formula of
 * the share) are separate rules (decision 271).
 *
 * <ul>
 *   <li>{@code continuation} ({@code itemDateField@modeColumn@periodStartsColumn}): the register's coverage start (an
 *       ISO date, or {@code NONE}: not covered), the rules column with the mode ({@code CONTRIBUTION_PERIOD} or
 *       {@code NONE}) and the column listing the first days of the contribution periods ({@code MM-dd}). The wage
 *       period lies in one contribution period {@code P..E}, the one containing its first day; a wage period that
 *       crosses a contribution-period boundary is refused whenever the row states the periods, in either mode
 *       ({@code WAGE_PERIOD_CROSSES_CONTRIBUTION_PERIOD_UNVERIFIED}, decision 271 (4)): its treatment is not verified
 *       from the official rules, so it is blocked from final calculation, never split or prorated. An item above the
 *       eligibility limit stays covered when its coverage started on or before {@code P}.</li>
 *   <li>Optional {@code joiner} (a rules column, decision 264): {@code COVERED_TO_PERIOD_END} also covers an item
 *       whose coverage started inside {@code P..E} on or before the wage period's first day (a joiner covered on
 *       joining, in a later wage period of the same contribution period); an item above the limit whose coverage
 *       starts after the wage period's first day and on or before its last day is refused
 *       ({@code CONTINUATION_STARTS_INSIDE_WAGE_PERIOD_UNVERIFIED}): the contribution-bearing wages of the covered
 *       days are not in the register and are never prorated (decision 271 (3)). {@code NONE} does not cover a
 *       joiner.</li>
 *   <li>Optional {@code continuationEnd} (an item field, decision 270): the register's {@code esiContinuationEndDate},
 *       strictly the end of the continuation after the wages crossed the limit, never a general end of coverage. It
 *       is empty or absent (the continuation derived from the contribution-period rules applies), or an ISO date that
 *       is the last day of a contribution period. It is read only for an item above the limit: an item at or below
 *       the limit is covered by the normal rule whatever the date says (decision 270 (2)), a stale date included.
 *       For an item above the limit with a coverage start: a date before the coverage start is refused
 *       ({@code ITEM_INVALID}); a date before {@code P} means the continuation ended before this contribution period
 *       (not covered); a date on or after {@code P} and before {@code E} while the continuation derived from the
 *       rules runs to {@code E} would shorten the mandatory continuation and is refused
 *       ({@code CONTINUATION_END_SHORTENS}); a date that is not the last day of a contribution period is refused
 *       ({@code CONTINUATION_END_NOT_PERIOD_END}). A date never extends coverage beyond the derived continuation.
 *       Employment cessation (effective date, reason, evidence) is not modelled here: it is a listed gap.</li>
 * </ul>
 * Any other column value is refused ({@code RULES_INVALID}).
 */
final class ContributionCoverage {

    static final String JOINER_COVERED = "COVERED_TO_PERIOD_END";
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z_]\\w*");
    private static final java.util.regex.Pattern MONTH_DAY = java.util.regex.Pattern.compile("\\d\\d-\\d\\d");

    private final String startField;
    private final String endField;
    private final boolean continues;
    private final List<MonthDay> starts;
    private final boolean joiner;
    private final LocalDate from;
    private final LocalDate to;
    private final LocalDate periodStart;
    private final LocalDate periodEnd;

    private ContributionCoverage(String startField, String endField, boolean continues, List<MonthDay> starts,
                                 boolean joiner, LocalDate from, LocalDate to) {
        this.startField = startField;
        this.endField = endField;
        this.continues = continues;
        this.starts = starts;
        this.joiner = joiner;
        this.from = from;
        this.to = to;
        this.periodStart = starts.isEmpty() ? null : periodStart(starts, from);
        this.periodEnd = starts.isEmpty() ? null : periodEnd(starts, periodStart);
    }

    /**
     * The coverage rules of one calculation: {@code cont} from {@link #continuation}, the optional item field
     * {@code endField} (the continuation end) and rules column {@code joinerColumn}, over the wage period
     * {@code from..to} (one day without a wage period). A wage period crossing a contribution-period boundary is
     * refused here, before any item.
     */
    static ContributionCoverage of(Map<String, Object> ruleRow, String ruleKey, String[] cont, String endField,
                                   String joinerColumn, LocalDate from, LocalDate to) {
        boolean on = mode(ruleRow, cont[1], ruleKey, "CONTRIBUTION_PERIOD", "NONE");
        Object stated = ruleRow == null ? null : ruleRow.get(cont[2]);
        // the contribution periods are read whenever the row states them, so a crossing wage period is blocked even
        // without continuation; under CONTRIBUTION_PERIOD they are required
        List<MonthDay> s = on || (stated != null && !stated.toString().isBlank())
                ? periodStarts(ruleRow, cont[2], ruleKey) : List.of();
        boolean j = joinerColumn != null && !joinerColumn.isBlank()
                && mode(ruleRow, joinerColumn.trim(), ruleKey, JOINER_COVERED, "NONE");
        if (endField != null && !endField.isBlank() && !NAME.matcher(endField.trim()).matches()) {
            throw refused("CALCULATION_INVALID", "field continuationEnd names one item field, not '" + endField + "'");
        }
        ContributionCoverage c = new ContributionCoverage(cont[0], endField == null || endField.isBlank() ? null
                : endField.trim(), on, s, j, from, to);
        if (c.periodEnd != null && to.isAfter(c.periodEnd)) {
            throw refused("WAGE_PERIOD_CROSSES_CONTRIBUTION_PERIOD_UNVERIFIED", "wage period " + from + " to " + to
                    + " crosses the contribution-period boundary after " + c.periodEnd + " (rules " + ruleKey
                    + "); the treatment of such a wage period is not verified from the official rules, so it is"
                    + " blocked from final calculation, never split or prorated (decision 271)");
        }
        return c;
    }

    boolean continues() {
        return continues;
    }

    /** The first day of the contribution period containing the wage period's first day. */
    LocalDate firstPeriodStart() {
        return periodStart;
    }

    /** The last day of that contribution period. */
    LocalDate firstPeriodEnd() {
        return periodEnd;
    }

    String text() {
        return (continues ? "CONTRIBUTION_PERIOD" : "NONE") + (joiner ? "+" + JOINER_COVERED : "")
                + (endField == null ? "" : "+CONTINUATION_END:" + endField);
    }

    /**
     * Whether item {@code id} stays covered for the wage period by continuation. The coverage start and the format of
     * the continuation end are checked for every item, so the register always states them; the continuation is
     * decided only for an item above the eligibility limit ({@code aboveLimit}), false otherwise (an item at or below
     * the limit is covered by the normal rule, decision 270 (2)).
     */
    boolean continued(Map<String, Object> item, String id, boolean aboveLimit) {
        LocalDate start = coverageStart(item, startField, id);
        LocalDate end = continuationEnd(item, endField, id);
        if (!aboveLimit || !continues || start == null) {
            return false;
        }
        if (end != null && end.isBefore(start)) {
            throw refused("ITEM_INVALID", "op EACH: item " + id + " continuation end " + end + " is before its"
                    + " coverage start " + start);
        }
        boolean derived;
        if (!start.isAfter(periodStart)) {
            derived = true;
        } else if (joiner && !start.isAfter(to)) {
            if (start.isAfter(from)) {
                throw refused("CONTINUATION_STARTS_INSIDE_WAGE_PERIOD_UNVERIFIED", "op EACH: item " + id
                        + " is above the limit and its coverage starts on " + start + ", inside the wage period " + from
                        + " to " + to + "; the wages of the covered days are not in the register and are never"
                        + " prorated (decision 271)");
            }
            derived = true;
        } else {
            derived = false;
        }
        if (end == null) {
            return derived;
        }
        if (derived && !end.isBefore(periodStart) && end.isBefore(periodEnd)) {
            throw refused("CONTINUATION_END_SHORTENS", "op EACH: item " + id + " " + endField + " " + end
                    + " would shorten the mandatory continuation, which the contribution-period rules derive to "
                    + periodEnd + " (decision 270)");
        }
        if (!isPeriodEnd(end)) {
            throw refused("CONTINUATION_END_NOT_PERIOD_END", "op EACH: item " + id + " " + endField + " " + end
                    + " is not the last day of a contribution period; a continuation ends only there (decision 270)");
        }
        return derived && !end.isBefore(periodStart);
    }

    private boolean isPeriodEnd(LocalDate day) {
        LocalDate next = day.plusDays(1);
        return next.equals(periodStart(starts, next));
    }

    /** The register's coverage start: an ISO date, or null for {@code NONE}; anything else is refused. */
    static LocalDate coverageStart(Map<String, Object> item, String field, String id) {
        Object v = item.get(field);
        String s = v == null ? "" : v.toString().trim();
        if (s.equals("NONE")) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw refused("ITEM_INVALID", "op EACH: item " + id + " has no coverage start " + field + " (an ISO date"
                    + " or NONE, not '" + s + "'): continuation of coverage is stated by the register, never assumed");
        }
    }

    /** The register's optional continuation end: empty or absent is none; anything but an ISO date is refused. */
    static LocalDate continuationEnd(Map<String, Object> item, String field, String id) {
        Object v = field == null ? null : item.get(field);
        String s = v == null ? "" : v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw refused("ITEM_INVALID", "op EACH: item " + id + " continuation end " + field + " is an ISO date or"
                    + " empty, not '" + s + "'");
        }
    }

    /** {@code itemDateField@modeColumn@periodStartsColumn}, or null when blank; anything else refused. */
    static String[] continuation(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = text.split("@", -1);
        if (parts.length != 3 || java.util.Arrays.stream(parts).anyMatch(s -> !NAME.matcher(s.trim()).matches())) {
            throw refused("CALCULATION_INVALID", "field continuation is 'itemDateField@modeColumn@periodStartsColumn',"
                    + " not '" + text + "'");
        }
        return new String[] {parts[0].trim(), parts[1].trim(), parts[2].trim()};
    }

    /** The first days of the contribution periods: {@code MM-dd}, comma separated, at least one, each once, no 02-29. */
    static List<MonthDay> periodStarts(Map<String, Object> ruleRow, String column, String ruleKey) {
        Object v = ruleRow == null ? null : ruleRow.get(column);
        String s = v == null ? "" : v.toString().trim();
        Set<MonthDay> out = new LinkedHashSet<>();
        for (String part : s.split(",", -1)) {
            String md = part.trim();
            MonthDay m = null;
            if (MONTH_DAY.matcher(md).matches()) {
                try {
                    m = MonthDay.parse("--" + md);
                } catch (DateTimeParseException e) {
                    m = null;
                }
            }
            if (m == null || m.equals(MonthDay.of(2, 29)) || !out.add(m)) {
                throw refused("RULES_INVALID", "rules " + ruleKey + ": column " + column + " lists the first days of"
                        + " the contribution periods as MM-dd, comma separated, each once and never 02-29, not '"
                        + s + "'");
            }
        }
        return List.copyOf(out);
    }

    /** The latest contribution-period start on or before {@code day}, in its year or the year before. */
    static LocalDate periodStart(List<MonthDay> starts, LocalDate day) {
        LocalDate best = null;
        for (MonthDay m : starts) {
            for (int year : new int[] {day.getYear(), day.getYear() - 1}) {
                LocalDate d = m.atYear(year);
                if (!d.isAfter(day) && (best == null || d.isAfter(best))) {
                    best = d;
                }
            }
        }
        return best;
    }

    /** The last day of the contribution period starting {@code periodStart}: the day before the next start. */
    static LocalDate periodEnd(List<MonthDay> starts, LocalDate periodStart) {
        LocalDate next = null;
        for (MonthDay m : starts) {
            for (int year : new int[] {periodStart.getYear(), periodStart.getYear() + 1}) {
                LocalDate d = m.atYear(year);
                if (d.isAfter(periodStart) && (next == null || d.isBefore(next))) {
                    next = d;
                }
            }
        }
        return next.minusDays(1);
    }

    /** A two-valued rules column: {@code yes} true, {@code no} false, anything else refused. */
    static boolean mode(Map<String, Object> ruleRow, String column, String ruleKey, String yes, String no) {
        Object v = ruleRow == null ? null : ruleRow.get(column);
        String s = v == null ? "" : v.toString().trim();
        if (!s.equals(yes) && !s.equals(no)) {
            throw refused("RULES_INVALID", "rules " + ruleKey + ": column " + column + " must be " + yes + " or " + no
                    + ", not '" + s + "'");
        }
        return s.equals(yes);
    }
}
