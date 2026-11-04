// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.roster;

import org.sangam.resources.port.ResourcesPorts;
import org.sangam.resources.roster.RosterModel.RosterConstraints;
import org.sangam.resources.roster.RosterModel.Violation;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure checks of one candidate duty against a person's other duties, leave and the working-time constraints. No I/O,
 * so the generator, manual assignment and swap approval all apply exactly the same rules.
 */
public final class RosterConstraintChecker {

    /** A duty window of a person (existing or candidate). */
    public record Duty(String assignmentId, LocalDate day, Instant start, Instant end, boolean night) {
        long minutes() {
            return (end.getEpochSecond() - start.getEpochSecond()) / 60L;
        }

        boolean overlaps(Instant s, Instant e) {
            return start.isBefore(e) && s.isBefore(end);
        }
    }

    private RosterConstraintChecker() {
    }

    /**
     * @param personId       the person
     * @param candidate      the duty to add
     * @param requiredSkill  skill the duty needs (null = none)
     * @param personSkills   the person's skills
     * @param existing       the person's other duties (the candidate itself must not be included)
     * @param leave          the person's leave intervals
     * @param k              the constraints
     */
    public static List<Violation> check(String personId, Duty candidate, String requiredSkill, Set<String> personSkills,
                                        Collection<Duty> existing, Collection<ResourcesPorts.LeaveInterval> leave,
                                        RosterConstraints k) {
        List<Violation> out = new ArrayList<>();
        LocalDate day = candidate.day();
        if (requiredSkill != null && k.requireSkill() && (personSkills == null || !personSkills.contains(requiredSkill))) {
            out.add(new Violation("SKILL", personId, day, "lacks skill " + requiredSkill));
        }
        for (ResourcesPorts.LeaveInterval l : leave) {
            if (candidate.overlaps(l.from(), l.to())) {
                out.add(new Violation("ON_LEAVE", personId, day, "on leave " + l.ref()));
                break;
            }
        }
        long dayMinutes = candidate.minutes();
        long weekMinutes = candidate.minutes();
        LocalDate weekStart = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        Set<LocalDate> workedDays = new HashSet<>();
        Set<LocalDate> nightDays = new HashSet<>();
        Set<LocalDate> weekDays = new HashSet<>();
        weekDays.add(day);
        for (Duty d : existing) {
            if (candidate.assignmentId() != null && candidate.assignmentId().equals(d.assignmentId())) {
                continue;
            }
            if (d.overlaps(candidate.start(), candidate.end())) {
                out.add(new Violation("OVERLAP", personId, day, "overlaps another duty on " + d.day()));
            } else if (k.minRestMinutes() > 0) {
                long gap = d.end().isAfter(candidate.start())
                        ? (d.start().getEpochSecond() - candidate.end().getEpochSecond()) / 60L
                        : (candidate.start().getEpochSecond() - d.end().getEpochSecond()) / 60L;
                if (gap >= 0 && gap < k.minRestMinutes()) {
                    out.add(new Violation("REST_HOURS", personId, day,
                            "rest of " + gap + " min is below " + k.minRestMinutes()));
                }
            }
            if (d.day().equals(day)) {
                dayMinutes += d.minutes();
            }
            if (!d.day().isBefore(weekStart) && !d.day().isAfter(weekEnd)) {
                weekMinutes += d.minutes();
                weekDays.add(d.day());
            }
            workedDays.add(d.day());
            if (d.night()) {
                nightDays.add(d.day());
            }
        }
        if (k.maxMinutesPerDay() > 0 && dayMinutes > k.maxMinutesPerDay()) {
            out.add(new Violation("MAX_DAY", personId, day, dayMinutes + " min exceeds " + k.maxMinutesPerDay()));
        }
        if (k.maxMinutesPerWeek() > 0 && weekMinutes > k.maxMinutesPerWeek()) {
            out.add(new Violation("MAX_WEEK", personId, day, weekMinutes + " min exceeds " + k.maxMinutesPerWeek()));
        }
        if (k.weeklyOffDays() > 0 && weekDays.size() > 7 - k.weeklyOffDays()) {
            out.add(new Violation("WEEKLY_OFF", personId, day, "fewer than " + k.weeklyOffDays() + " days off this week"));
        }
        workedDays.add(day);
        if (k.maxConsecutiveDays() > 0 && run(workedDays, day) > k.maxConsecutiveDays()) {
            out.add(new Violation("CONSECUTIVE_DAYS", personId, day, "more than " + k.maxConsecutiveDays() + " days in a row"));
        }
        if (candidate.night()) {
            nightDays.add(day);
            if (k.maxConsecutiveNights() > 0 && run(nightDays, day) > k.maxConsecutiveNights()) {
                out.add(new Violation("CONSECUTIVE_NIGHTS", personId, day,
                        "more than " + k.maxConsecutiveNights() + " nights in a row"));
            }
        }
        return out;
    }

    /** Length of the run of consecutive days in {@code days} that contains {@code day}. */
    static int run(Set<LocalDate> days, LocalDate day) {
        int n = 1;
        for (LocalDate d = day.minusDays(1); days.contains(d); d = d.minusDays(1)) {
            n++;
        }
        for (LocalDate d = day.plusDays(1); days.contains(d); d = d.plusDays(1)) {
            n++;
        }
        return n;
    }
}
