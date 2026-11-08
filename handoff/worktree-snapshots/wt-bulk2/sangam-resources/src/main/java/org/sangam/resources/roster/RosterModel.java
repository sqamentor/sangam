// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.roster;

import org.sangam.resources.support.Sql;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rostering model (capability N08): shift templates, rotation patterns, roster periods, members, coverage demand,
 * assignments, swap/cover requests and constraints. Everything domain-specific (shift names, skills, rest rules from
 * labour codes or service rules) is data; this file only defines shapes.
 */
public final class RosterModel {

    private RosterModel() {
    }

    /** Value used in a rotation cycle for a day off. */
    public static final String OFF = "OFF";

    /** A shift definition; times are local to {@code zone}. */
    public record ShiftTemplate(String tenantId, String shiftId, String code, String name, LocalTime fromTime,
                                int durationMinutes, boolean night, Set<String> skills, ZoneId zone,
                                Map<String, Object> attributes) {
        public ShiftTemplate {
            skills = skills == null ? Set.of() : Set.copyOf(skills);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            zone = zone == null ? ZoneId.of("UTC") : zone;
            if (durationMinutes <= 0) {
                throw new IllegalArgumentException("durationMinutes must be positive");
            }
        }

        public Instant startOn(LocalDate day) {
            return ZonedDateTime.of(day, fromTime, zone).toInstant();
        }

        public Instant endOn(LocalDate day) {
            return startOn(day).plusSeconds(durationMinutes * 60L);
        }
    }

    /** A repeating cycle of shift codes (or {@link #OFF}), one entry per day. */
    public record RotationPattern(String tenantId, String rotationId, String name, List<String> cycle) {
        public RotationPattern {
            cycle = cycle == null ? List.of() : List.copyOf(cycle);
            if (cycle.isEmpty()) {
                throw new IllegalArgumentException("a rotation needs at least one day");
            }
        }

        public String codeFor(int dayIndex, int offset) {
            int n = cycle.size();
            return cycle.get(Math.floorMod(dayIndex + offset, n));
        }
    }

    public enum PeriodState { DRAFT, GENERATED, SUBMITTED, PUBLISHED, CLOSED }

    public record RosterPeriod(String tenantId, String periodId, String unitId, String name, LocalDate firstDay,
                               LocalDate lastDay, PeriodState state, String seed, RosterConstraints constraints,
                               String processRef, Instant generatedAt, Instant publishedAt, String publishedBy,
                               long version) {
    }

    public record RosterMember(String periodId, String personId, String seatId, Set<String> skills, String rotationId,
                               int rotationOffset, Map<String, Object> attributes) {
        public RosterMember {
            skills = skills == null ? Set.of() : Set.copyOf(skills);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    /** Required head-count for a shift, on given weekdays or one specific day, optionally needing a skill. */
    public record CoverageDemand(String demandId, String periodId, String shiftId, String skill, Set<DayOfWeek> weekdays,
                                 LocalDate dutyDay, int requiredCount, String dutySeatId, String locationId) {
        public CoverageDemand {
            weekdays = weekdays == null ? Set.of() : Set.copyOf(weekdays);
        }

        public boolean appliesOn(LocalDate day) {
            if (dutyDay != null) {
                return dutyDay.equals(day);
            }
            return weekdays.isEmpty() || weekdays.contains(day.getDayOfWeek());
        }
    }

    public enum AssignmentKind { DUTY, ON_CALL, OFF, LEAVE }

    public enum AssignmentState { DRAFT, PUBLISHED }

    public enum Attendance { PRESENT, ABSENT, PARTIAL }

    public record RosterAssignment(String tenantId, String assignmentId, String periodId, String personId, String seatId,
                                   String shiftId, LocalDate dutyDay, Instant startsAt, Instant endsAt,
                                   AssignmentKind kind, String demandId, String dutySeatId, String locationId,
                                   AssignmentState state, String postingRef, Attendance attendance,
                                   String swappedFrom, long version) {
        public long minutes() {
            return (endsAt.getEpochSecond() - startsAt.getEpochSecond()) / 60L;
        }
    }

    public enum SwapKind { SWAP, COVER }

    public enum SwapState { REQUESTED, ACCEPTED, APPROVED, REJECTED, CANCELLED }

    public record SwapRequest(String tenantId, String swapId, SwapKind kind, String assignmentId, String requesterId,
                              String counterpartId, String counterpartAssignmentId, SwapState state, String reason,
                              String decidedBy, String processRef, long version, Instant createdAt, Instant decidedAt) {
    }

    /**
     * Working-time constraints; 0 means "no limit". Held as profile data (labour codes, service rules) and optionally
     * refined per member by the DMN table {@code constraintsDecision} (outputs with the same names override).
     */
    public record RosterConstraints(int minRestMinutes, int maxMinutesPerWeek, int maxMinutesPerDay, int weeklyOffDays,
                                    int maxConsecutiveNights, int maxConsecutiveDays, boolean requireSkill,
                                    String constraintsDecision, String approvalProcessKey,
                                    String swapApprovalProcessKey) {

        public static final String DEFAULT_APPROVAL_PROCESS = "sangam-roster-publish";

        public static RosterConstraints none() {
            return new RosterConstraints(0, 0, 0, 0, 0, 0, true, null, DEFAULT_APPROVAL_PROCESS, null);
        }

        public static RosterConstraints from(Map<String, Object> m) {
            if (m == null) {
                return none();
            }
            return new RosterConstraints(
                    Sql.intOr(m.get("minRestMinutes"), 0),
                    Sql.intOr(m.get("maxMinutesPerWeek"), 0),
                    Sql.intOr(m.get("maxMinutesPerDay"), 0),
                    Sql.intOr(m.get("weeklyOffDays"), 0),
                    Sql.intOr(m.get("maxConsecutiveNights"), 0),
                    Sql.intOr(m.get("maxConsecutiveDays"), 0),
                    Sql.bool(m.get("requireSkill"), true),
                    Sql.str(m.get("constraintsDecision")),
                    m.get("approvalProcessKey") == null ? DEFAULT_APPROVAL_PROCESS : m.get("approvalProcessKey").toString(),
                    Sql.str(m.get("swapApprovalProcessKey")));
        }

        /** Overrides fields with the outputs of a DMN row (same names). */
        public RosterConstraints override(Map<String, Object> row) {
            if (row == null || row.isEmpty()) {
                return this;
            }
            Map<String, Object> m = toMap();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getValue() != null && m.containsKey(e.getKey())) {
                    m.put(e.getKey(), e.getValue());
                }
            }
            return from(m);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("minRestMinutes", minRestMinutes);
            m.put("maxMinutesPerWeek", maxMinutesPerWeek);
            m.put("maxMinutesPerDay", maxMinutesPerDay);
            m.put("weeklyOffDays", weeklyOffDays);
            m.put("maxConsecutiveNights", maxConsecutiveNights);
            m.put("maxConsecutiveDays", maxConsecutiveDays);
            m.put("requireSkill", requireSkill);
            m.put("constraintsDecision", constraintsDecision);
            m.put("approvalProcessKey", approvalProcessKey);
            m.put("swapApprovalProcessKey", swapApprovalProcessKey);
            return m;
        }
    }

    /** One broken rule. Codes: OVERLAP, ON_LEAVE, REST_HOURS, MAX_DAY, MAX_WEEK, WEEKLY_OFF, CONSECUTIVE_DAYS,
     *  CONSECUTIVE_NIGHTS, SKILL, DOUBLE_ROSTERED. */
    public record Violation(String code, String personId, LocalDate day, String message) {
    }

    /** Demand that the generator could not fill. */
    public record Unfilled(String demandId, String shiftId, LocalDate day, String skill, int missing) {
    }

    public record GenerationReport(String periodId, String seed, String entrantsHash, int assigned, int leaveMarked,
                                   List<Unfilled> unfilled, List<Violation> rejected) {
        public GenerationReport {
            unfilled = List.copyOf(unfilled);
            rejected = List.copyOf(rejected);
        }
    }

    // ------------------------------------------------------------------ helpers

    static Set<String> skills(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null) {
            for (String s : csv.split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    static String csv(Set<String> skills) {
        return skills == null || skills.isEmpty() ? null : String.join(",", skills);
    }

    static Set<DayOfWeek> weekdays(String csv) {
        Set<DayOfWeek> out = new LinkedHashSet<>();
        for (String s : skills(csv)) {
            out.add(DayOfWeek.valueOf(s.toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    static String weekdaysCsv(Set<DayOfWeek> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (DayOfWeek d : days) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(d.name());
        }
        return sb.toString();
    }
}
