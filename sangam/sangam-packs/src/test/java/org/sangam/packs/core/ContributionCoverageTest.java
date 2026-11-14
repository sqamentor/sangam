// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;

/**
 * Decisions 264, 270 and 271 over {@link ContributionCoverage}. Contribution periods start 1 April and 1 October, so
 * a wage period in October 2026 to March 2027 lies in the period P = 2026-10-01 to E = 2027-03-31. A joiner covered on
 * joining stays covered to the end of that contribution period (rules column {@code esiMidPeriodJoiner}); the
 * register's optional {@code esiContinuationEndDate} is strictly the end of the continuation after the wages crossed
 * the limit, read only for an employee above the limit, never allowed to shorten the continuation derived from the
 * contribution periods; a wage period crossing a contribution-period boundary is blocked (the round 6 split by days
 * is removed). The rows are test data, not statutory values.
 */
class ContributionCoverageTest {

    private static final String[] CONT = {"esiCoverageStart", "esiContinuation", "esiContributionPeriodStarts"};
    private static final String END = "esiContinuationEndDate";

    static Map<String, Object> row(String joiner) {
        Map<String, Object> r = new HashMap<>();
        r.put("esiContinuation", "CONTRIBUTION_PERIOD");
        r.put("esiContributionPeriodStarts", "04-01,10-01");
        r.put("esiMidPeriodJoiner", joiner);
        return r;
    }

    static ContributionCoverage cov(String joiner, String from, String to) {
        return ContributionCoverage.of(row(joiner), "r", CONT, END, "esiMidPeriodJoiner", LocalDate.parse(from),
                LocalDate.parse(to));
    }

    static Map<String, Object> item(String start, String end) {
        Map<String, Object> i = new HashMap<>();
        i.put("esiCoverageStart", start);
        if (end != null) {
            i.put(END, end);
        }
        return i;
    }

    /** Whether an item above the limit ({@code above}) stays covered by continuation. */
    static boolean continued(ContributionCoverage c, String start, String end, boolean above) {
        return c.continued(item(start, end), "E1", above);
    }

    static void assertRefused(String code, Runnable r) {
        String m = assertThrows(ProcessEngineException.class, r::run).getMessage();
        assertTrue(m.startsWith(code + ":"), m);
    }

    static final String JOINER = "COVERED_TO_PERIOD_END";

    @Test
    void aJoinerCoveredOnJoiningStaysCoveredToTheEndOfThatContributionPeriod() {
        // joined 15 October 2026 (inside P): March 2027 is a later wage period of the same contribution period
        assertTrue(continued(cov(JOINER, "2027-03-01", "2027-03-31"), "2026-10-15", null, true));
        // a wage period that starts on the coverage start is covered whole
        assertTrue(continued(cov(JOINER, "2026-10-15", "2026-11-14"), "2026-10-15", null, true));
        // from the next contribution period on, by continuation as before
        assertTrue(continued(cov("NONE", "2027-04-01", "2027-04-30"), "2026-10-15", null, true));
        // coverage that starts after the wage period: not covered in it
        assertFalse(continued(cov(JOINER, "2026-10-01", "2026-10-31"), "2026-11-05", null, true));
    }

    /**
     * Replaces the round 6 parts (1-14 October uncovered, 15-31 October covered, wages shared by days): the covered
     * days' wages are not in the register and are never prorated (decision 271 (3)).
     */
    @Test
    void anAboveLimitJoinerWhoseCoverageStartsInsideTheWagePeriodIsBlocked() {
        assertRefused("CONTINUATION_STARTS_INSIDE_WAGE_PERIOD_UNVERIFIED",
                () -> continued(cov(JOINER, "2026-10-01", "2026-10-31"), "2026-10-15", null, true));
        // the same item at or below the limit is decided by the normal rule, not by continuation
        assertFalse(continued(cov(JOINER, "2026-10-01", "2026-10-31"), "2026-10-15", null, false));
    }

    @Test
    void withoutTheJoinerRuleOrWithoutCoverageOnJoiningAJoinerIsNotCovered() {
        // joiner rule NONE: coverage that started inside the contribution period does not continue in it
        assertFalse(continued(cov("NONE", "2026-10-01", "2026-10-31"), "2026-10-15", null, true));
        assertFalse(continued(cov("NONE", "2027-03-01", "2027-03-31"), "2026-10-15", null, true));
        // a joiner above the limit on joining: the register states NONE, never covered by the joiner rule
        assertFalse(continued(cov(JOINER, "2026-10-01", "2026-10-31"), "NONE", null, true));
    }

    /** Replaces the round 6 split-by-days tests (exact parts and their sum): such a wage period is blocked. */
    @Test
    void aWagePeriodCrossingAContributionPeriodBoundaryIsBlocked() {
        for (String[] p : new String[][] {{"2026-09-16", "2026-10-15"}, {"2026-09-30", "2026-10-01"},
                {"2027-03-31", "2027-04-01"}, {"2026-09-01", "2026-10-31"}}) {
            assertRefused("WAGE_PERIOD_CROSSES_CONTRIBUTION_PERIOD_UNVERIFIED", () -> cov("NONE", p[0], p[1]));
            assertRefused("WAGE_PERIOD_CROSSES_CONTRIBUTION_PERIOD_UNVERIFIED", () -> cov(JOINER, p[0], p[1]));
        }
        // exact boundaries inside one contribution period are not crossings
        ContributionCoverage sep = cov("NONE", "2026-09-01", "2026-09-30");
        assertEquals(LocalDate.of(2026, 4, 1), sep.firstPeriodStart());
        assertEquals(LocalDate.of(2026, 9, 30), sep.firstPeriodEnd());
        ContributionCoverage oct = cov("NONE", "2026-10-01", "2026-10-31");
        assertEquals(LocalDate.of(2026, 10, 1), oct.firstPeriodStart());
        assertEquals(LocalDate.of(2027, 3, 31), oct.firstPeriodEnd());
        assertEquals(LocalDate.of(2027, 3, 31), cov("NONE", "2027-03-31", "2027-03-31").firstPeriodEnd());
        // the continuation mode NONE does not lift the block when the row states the contribution periods
        Map<String, Object> off = row("NONE");
        off.put("esiContinuation", "NONE");
        assertRefused("WAGE_PERIOD_CROSSES_CONTRIBUTION_PERIOD_UNVERIFIED", () -> ContributionCoverage.of(off, "r",
                CONT, END, null, LocalDate.parse("2026-09-16"), LocalDate.parse("2026-10-15")));
    }

    /** Decision 270 (2): a below-limit employee is never decided by the continuation end, a stale one included. */
    @Test
    void aBelowLimitEmployeeWithAStaleContinuationEndIsNotDecidedByIt() {
        ContributionCoverage oct = cov(JOINER, "2026-10-01", "2026-10-31");
        // stale: the end of an earlier continuation, even one before the current coverage start; no refusal
        assertFalse(continued(oct, "2025-04-01", "2025-09-30", false));
        assertFalse(continued(oct, "2026-04-01", "2025-09-30", false));
        // a date that would shorten the continuation of an employee above the limit means nothing below it
        assertFalse(continued(oct, "2026-04-01", "2026-10-31", false));
    }

    /** Decision 270 (3): the continuation is derived from the contribution periods; an entered end never shortens it. */
    @Test
    void theContinuationEndIsCheckedAgainstTheDerivedContinuation() {
        ContributionCoverage oct = cov("NONE", "2026-10-01", "2026-10-31"); // P 2026-10-01, E 2027-03-31
        String start = "2026-04-01";
        assertTrue(continued(oct, start, null, true), "no end: the derived continuation to E");
        assertTrue(continued(oct, start, "", true), "an empty end is no end");
        assertTrue(continued(oct, start, "2027-03-31", true), "the end is E exactly");
        assertTrue(continued(oct, start, "2027-09-30", true), "a later period end does not shorten it");
        // the continuation ended at the end of the previous contribution period: not covered in this one
        assertFalse(continued(oct, start, "2026-09-30", true));
        // inside P..E-1: it would shorten the mandatory continuation
        for (String shorter : new String[] {"2026-10-01", "2026-10-31", "2027-03-30"}) {
            assertRefused("CONTINUATION_END_SHORTENS", () -> continued(oct, start, shorter, true));
        }
        // a continuation ends only on the last day of a contribution period
        for (String odd : new String[] {"2027-04-15", "2026-08-31"}) {
            assertRefused("CONTINUATION_END_NOT_PERIOD_END", () -> continued(oct, start, odd, true));
        }
        // before the coverage start it cannot end this coverage
        assertRefused("ITEM_INVALID", () -> continued(oct, start, "2026-03-31", true));
        // a joiner's continuation runs to E too
        assertRefused("CONTINUATION_END_SHORTENS",
                () -> continued(cov(JOINER, "2027-03-01", "2027-03-31"), "2026-10-15", "2027-01-31", true));
    }

    /** Wages that cross the limit in the middle of a contribution period, then a later wage change. */
    @Test
    void aMidPeriodCrossingContinuesToThePeriodEndThenEnds() {
        String start = "2026-04-01";
        // January 2027: above the limit for the first time in P (October to March); covered since before P
        assertTrue(continued(cov("NONE", "2027-01-01", "2027-01-31"), start, null, true));
        assertTrue(continued(cov("NONE", "2027-01-01", "2027-01-31"), start, "2027-03-31", true));
        // March 2027, the last wage period of P
        assertTrue(continued(cov("NONE", "2027-03-01", "2027-03-31"), start, "2027-03-31", true));
        // April 2027, the next contribution period: the continuation ended on 31 March 2027
        assertFalse(continued(cov("NONE", "2027-04-01", "2027-04-30"), start, "2027-03-31", true));
        // a later wage change below the limit: decided by the normal rule, never by the old continuation end
        assertFalse(continued(cov("NONE", "2027-04-01", "2027-04-30"), start, "2027-03-31", false));
    }

    @Test
    void anInvalidContinuationEndOrRuleValueIsRefused() {
        ContributionCoverage c = cov("NONE", "2026-10-01", "2026-10-31");
        assertRefused("ITEM_INVALID", () -> continued(c, "2026-04-01", "2026-13-01", true));
        assertRefused("ITEM_INVALID", () -> continued(c, "2026-04-01", "soon", true));
        assertRefused("ITEM_INVALID", () -> continued(c, "2026-04-01", "soon", false)); // checked for every item
        assertRefused("ITEM_INVALID", () -> continued(c, "yes", null, false)); // the start is stated, never assumed
        assertRefused("RULES_INVALID", () -> cov("SOMETIMES", "2026-10-01", "2026-10-31"));
        assertRefused("RULES_INVALID", () -> cov("", "2026-10-01", "2026-10-31"));
        assertRefused("CALCULATION_INVALID", () -> ContributionCoverage.of(row("NONE"), "r", CONT, "a b", null,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")));
    }

    @Test
    void withoutTheOptionalFieldsTheRound5RuleIsUnchanged() {
        ContributionCoverage c = ContributionCoverage.of(row(null), "r", CONT, null, null,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));
        assertTrue(c.continues());
        assertEquals(LocalDate.of(2026, 10, 1), c.firstPeriodStart());
        assertTrue(c.continued(item("2026-10-01", "2020-01-01"), "E1", true), "no end field configured");
        assertFalse(c.continued(item("2026-10-02", null), "E1", true));
        assertEquals("CONTRIBUTION_PERIOD", c.text());
        assertEquals("CONTRIBUTION_PERIOD+COVERED_TO_PERIOD_END+CONTINUATION_END:" + END,
                cov(JOINER, "2026-10-01", "2026-10-31").text());
        assertNull(ContributionCoverage.continuationEnd(item("2026-04-01", null), END, "E1"));
    }
}
