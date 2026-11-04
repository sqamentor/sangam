// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.roster;

import org.sangam.core.layer.Hashes;
import org.sangam.resources.port.ResourcesPorts;
import org.sangam.resources.roster.RosterConstraintChecker.Duty;
import org.sangam.resources.roster.RosterModel.AssignmentKind;
import org.sangam.resources.roster.RosterModel.CoverageDemand;
import org.sangam.resources.roster.RosterModel.RosterConstraints;
import org.sangam.resources.roster.RosterModel.RosterMember;
import org.sangam.resources.roster.RosterModel.RotationPattern;
import org.sangam.resources.roster.RosterModel.ShiftTemplate;
import org.sangam.resources.roster.RosterModel.Unfilled;
import org.sangam.resources.roster.RosterModel.Violation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Rule-based draft roster generation (N08 default; an optional solver worker can replace it).
 *
 * <ol>
 *   <li><b>Rotations first</b>: every member with a rotation pattern gets the shift of the cycle position
 *       {@code (dayIndex + offset) mod cycleLength}; {@code OFF} entries are days off. A rotation day that falls on
 *       leave becomes a {@code LEAVE} entry; one that breaks a constraint is rejected and reported.</li>
 *   <li><b>Greedy demand fill</b>: per day and coverage demand, the remaining head-count is filled from eligible
 *       members (skill, constraints, leave), fewest assigned minutes first (fairness), ties broken by an audited
 *       seeded random draw.</li>
 * </ol>
 * The random source is {@link SplittableRandom} seeded with the first 8 bytes of SHA-256(seed); the draw order is
 * deterministic (candidates sorted by person id), so anyone holding the seed and the inputs (whose hash is stored as
 * {@code entrantsHash}) can reproduce the roster.
 */
public final class RosterGenerator {

    public static final String ALGORITHM = "SANGAM-ROSTER-GREEDY-1/SPLITTABLE-SHA256";
    public static final String RANDOMISATION_ALGORITHM = "FISHER-YATES/SPLITTABLE-SHA256";

    /** One planned row before persistence. */
    public record Planned(String personId, String seatId, String shiftId, LocalDate day, Instant start, Instant end,
                          boolean night, AssignmentKind kind, String demandId, String dutySeatId, String locationId) {
    }

    public record Result(String seed, String entrantsHash, List<Planned> planned, List<Unfilled> unfilled,
                         List<Violation> rejected) {
    }

    /** One pairing of an audited randomisation. */
    public record Pairing(String slot, String person) {
    }

    public record RandomisationResult(String seed, String algorithm, String entrantsHash, List<Pairing> pairings,
                                      List<String> unfilledSlots, List<String> unassignedPeople) {
    }

    /** Inputs of one generation run. */
    public record Input(LocalDate firstDay, LocalDate lastDay, Map<String, ShiftTemplate> shiftsById,
                        Map<String, RotationPattern> rotations, List<RosterMember> members, List<CoverageDemand> demands,
                        Map<String, List<Duty>> existingByPerson,
                        Map<String, List<ResourcesPorts.LeaveInterval>> leaveByPerson,
                        Function<RosterMember, RosterConstraints> constraintsFor, String seed) {
    }

    private RosterGenerator() {
    }

    public static Result generate(Input in) {
        Map<String, ShiftTemplate> byCode = new HashMap<>();
        for (ShiftTemplate s : in.shiftsById().values()) {
            byCode.put(s.code(), s);
        }
        Map<String, List<Duty>> duties = new HashMap<>();
        Map<String, Long> minutes = new HashMap<>();
        Map<String, RosterConstraints> constraints = new HashMap<>();
        for (RosterMember m : in.members()) {
            duties.put(m.personId(), new ArrayList<>(in.existingByPerson().getOrDefault(m.personId(), List.of())));
            minutes.put(m.personId(), 0L);
            constraints.put(m.personId(), in.constraintsFor().apply(m));
        }
        List<Planned> planned = new ArrayList<>();
        List<Violation> rejected = new ArrayList<>();
        // covered[day|demandId] = head-count already placed
        Map<String, Integer> covered = new HashMap<>();
        Set<String> taken = new HashSet<>();   // person|day|shift

        // 1. rotations
        List<RosterMember> sortedMembers = new ArrayList<>(in.members());
        sortedMembers.sort(Comparator.comparing(RosterMember::personId));
        for (RosterMember m : sortedMembers) {
            if (m.rotationId() == null) {
                continue;
            }
            RotationPattern rot = in.rotations().get(m.rotationId());
            if (rot == null) {
                rejected.add(new Violation("ROTATION_MISSING", m.personId(), in.firstDay(), "rotation " + m.rotationId()));
                continue;
            }
            int idx = 0;
            for (LocalDate day = in.firstDay(); !day.isAfter(in.lastDay()); day = day.plusDays(1), idx++) {
                String code = rot.codeFor(idx, m.rotationOffset());
                if (RosterModel.OFF.equalsIgnoreCase(code)) {
                    continue;
                }
                ShiftTemplate s = byCode.get(code);
                if (s == null) {
                    s = in.shiftsById().get(code);
                }
                if (s == null) {
                    rejected.add(new Violation("SHIFT_MISSING", m.personId(), day, "shift " + code));
                    continue;
                }
                CoverageDemand d = matchingDemand(in.demands(), s.shiftId(), day, m.skills(), covered);
                Duty cand = new Duty(null, day, s.startOn(day), s.endOn(day), s.night());
                List<ResourcesPorts.LeaveInterval> lv = in.leaveByPerson().getOrDefault(m.personId(), List.of());
                if (onLeave(cand, lv)) {
                    planned.add(plan(m, s, day, AssignmentKind.LEAVE, null));
                    continue;
                }
                List<Violation> v = RosterConstraintChecker.check(m.personId(), cand, d == null ? null : d.skill(),
                        m.skills(), duties.get(m.personId()), lv, constraints.get(m.personId()));
                if (!v.isEmpty()) {
                    rejected.addAll(v);
                    continue;
                }
                planned.add(plan(m, s, day, AssignmentKind.DUTY, d));
                duties.get(m.personId()).add(cand);
                minutes.merge(m.personId(), (long) s.durationMinutes(), Long::sum);
                taken.add(m.personId() + "|" + day + "|" + s.shiftId());
                if (d != null) {
                    covered.merge(day + "|" + d.demandId(), 1, Integer::sum);
                }
            }
        }

        // 2. greedy demand fill
        SplittableRandom rnd = new SplittableRandom(seedLong(in.seed()));
        List<CoverageDemand> demands = new ArrayList<>(in.demands());
        demands.sort(Comparator.comparing(CoverageDemand::demandId));
        List<Unfilled> unfilled = new ArrayList<>();
        for (LocalDate day = in.firstDay(); !day.isAfter(in.lastDay()); day = day.plusDays(1)) {
            for (CoverageDemand d : demands) {
                if (!d.appliesOn(day)) {
                    continue;
                }
                ShiftTemplate s = in.shiftsById().get(d.shiftId());
                if (s == null) {
                    unfilled.add(new Unfilled(d.demandId(), d.shiftId(), day, d.skill(), d.requiredCount()));
                    continue;
                }
                String key = day + "|" + d.demandId();
                int missing = d.requiredCount() - covered.getOrDefault(key, 0);
                if (missing <= 0) {
                    continue;
                }
                Map<String, Long> tie = new HashMap<>();
                for (RosterMember m : sortedMembers) {
                    tie.put(m.personId(), rnd.nextLong());
                }
                List<RosterMember> cands = new ArrayList<>(sortedMembers);
                cands.sort(Comparator.<RosterMember>comparingLong(m -> minutes.get(m.personId()))
                        .thenComparingLong(m -> tie.get(m.personId())));
                Duty cand = new Duty(null, day, s.startOn(day), s.endOn(day), s.night());
                for (RosterMember m : cands) {
                    if (missing == 0) {
                        break;
                    }
                    if (taken.contains(m.personId() + "|" + day + "|" + s.shiftId())) {
                        continue;
                    }
                    List<ResourcesPorts.LeaveInterval> lv = in.leaveByPerson().getOrDefault(m.personId(), List.of());
                    List<Violation> v = RosterConstraintChecker.check(m.personId(), cand, d.skill(), m.skills(),
                            duties.get(m.personId()), lv, constraints.get(m.personId()));
                    if (!v.isEmpty()) {
                        continue;
                    }
                    planned.add(plan(m, s, day, AssignmentKind.DUTY, d));
                    duties.get(m.personId()).add(cand);
                    minutes.merge(m.personId(), (long) s.durationMinutes(), Long::sum);
                    taken.add(m.personId() + "|" + day + "|" + s.shiftId());
                    covered.merge(key, 1, Integer::sum);
                    missing--;
                }
                if (missing > 0) {
                    unfilled.add(new Unfilled(d.demandId(), d.shiftId(), day, d.skill(), missing));
                }
            }
        }
        return new Result(in.seed(), entrantsHash(in.members(), in.demands()), planned, unfilled, rejected);
    }

    /**
     * Audited random deployment (independent random posting of staff to duty slots): shuffles the people with Fisher-Yates driven by
     * the seeded generator and pairs them with the slots in the given order. People and slots are sorted first, so the
     * result depends only on the seed and the sets.
     */
    public static RandomisationResult auditedRandomisation(String seed, Collection<String> people, Collection<String> slots) {
        List<String> p = new ArrayList<>(new TreeSet<>(people));
        List<String> s = new ArrayList<>(new TreeSet<>(slots));
        SplittableRandom rnd = new SplittableRandom(seedLong(seed));
        for (int i = p.size() - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            String t = p.get(i);
            p.set(i, p.get(j));
            p.set(j, t);
        }
        List<Pairing> pairs = new ArrayList<>();
        int n = Math.min(p.size(), s.size());
        for (int i = 0; i < n; i++) {
            pairs.add(new Pairing(s.get(i), p.get(i)));
        }
        String hash = Hashes.hex(Hashes.sha256(("people:" + String.join(",", new TreeSet<>(people))
                + "|slots:" + String.join(",", new TreeSet<>(slots))).getBytes(StandardCharsets.UTF_8)));
        return new RandomisationResult(seed, RANDOMISATION_ALGORITHM, hash, pairs,
                List.copyOf(s.subList(n, s.size())), List.copyOf(p.subList(n, p.size())));
    }

    public static long seedLong(String seed) {
        byte[] h = Hashes.sha256((seed == null ? "" : seed).getBytes(StandardCharsets.UTF_8));
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (h[i] & 0xffL);
        }
        return v;
    }

    public static String entrantsHash(List<RosterMember> members, List<CoverageDemand> demands) {
        StringBuilder sb = new StringBuilder("members:");
        members.stream().sorted(Comparator.comparing(RosterMember::personId)).forEach(m -> sb.append(m.personId())
                .append('/').append(String.join("+", new TreeSet<>(m.skills()))).append('/')
                .append(m.rotationId()).append('/').append(m.rotationOffset()).append(','));
        sb.append("|demands:");
        demands.stream().sorted(Comparator.comparing(CoverageDemand::demandId)).forEach(d -> sb.append(d.demandId())
                .append('/').append(d.shiftId()).append('/').append(d.skill()).append('/').append(d.requiredCount())
                .append(','));
        return Hashes.hex(Hashes.sha256(sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean onLeave(Duty cand, List<ResourcesPorts.LeaveInterval> leave) {
        for (ResourcesPorts.LeaveInterval l : leave) {
            if (cand.start().isBefore(l.to()) && l.from().isBefore(cand.end())) {
                return true;
            }
        }
        return false;
    }

    private static CoverageDemand matchingDemand(List<CoverageDemand> demands, String shiftId, LocalDate day,
                                                 Set<String> skills, Map<String, Integer> covered) {
        for (CoverageDemand d : demands) {
            if (!d.shiftId().equals(shiftId) || !d.appliesOn(day)) {
                continue;
            }
            if (d.skill() != null && !skills.contains(d.skill())) {
                continue;
            }
            if (covered.getOrDefault(day + "|" + d.demandId(), 0) < d.requiredCount()) {
                return d;
            }
        }
        return null;
    }

    private static Planned plan(RosterMember m, ShiftTemplate s, LocalDate day, AssignmentKind kind, CoverageDemand d) {
        return new Planned(m.personId(), m.seatId(), s.shiftId(), day, s.startOn(day), s.endOn(day), s.night(), kind,
                d == null ? null : d.demandId(), d == null ? null : d.dutySeatId(), d == null ? null : d.locationId());
    }
}
