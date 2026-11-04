// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.roster;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.port.ResourcesPorts;
import org.sangam.resources.roster.RosterConstraintChecker.Duty;
import org.sangam.resources.roster.RosterModel.AssignmentKind;
import org.sangam.resources.roster.RosterModel.AssignmentState;
import org.sangam.resources.roster.RosterModel.Attendance;
import org.sangam.resources.roster.RosterModel.CoverageDemand;
import org.sangam.resources.roster.RosterModel.GenerationReport;
import org.sangam.resources.roster.RosterModel.PeriodState;
import org.sangam.resources.roster.RosterModel.RosterAssignment;
import org.sangam.resources.roster.RosterModel.RosterConstraints;
import org.sangam.resources.roster.RosterModel.RosterMember;
import org.sangam.resources.roster.RosterModel.RosterPeriod;
import org.sangam.resources.roster.RosterModel.RotationPattern;
import org.sangam.resources.roster.RosterModel.ShiftTemplate;
import org.sangam.resources.roster.RosterModel.SwapKind;
import org.sangam.resources.roster.RosterModel.SwapRequest;
import org.sangam.resources.roster.RosterModel.SwapState;
import org.sangam.resources.roster.RosterModel.Violation;
import org.sangam.resources.rules.RuleEvaluator;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sql;
import org.sangam.resources.support.VisibleQuery;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rostering service (N08): shift templates, rotations, roster periods, members, coverage demand, draft generation,
 * manual assignment, approval and publication, duty-to-seat link, swaps and covers, on-call, leave integration and
 * attendance reconciliation.
 *
 * <p>Every method takes the caller's connection and never commits it. The tenant is the actor's tenant. Times come
 * from the database clock. State changes use optimistic {@code row_version} checks.
 *
 * <h2>No double rostering</h2>
 * Each DUTY assignment claims {@code RES_PERSON_CLAIM} rows keyed (tenant, person, hour_key) with
 * {@code hour_key = epochSecond / 3600} for every whole clock hour inside the duty (a duty shorter than one whole hour
 * claims the hour it starts in). The primary key makes two concurrent transactions that roster the same person into
 * overlapping hours collide in the database, across periods and units; partial-hour overlaps between non-aligned
 * shifts are caught by {@link RosterConstraintChecker} ({@code OVERLAP}).
 */
public final class RosterService {

    public static final String ET_PERIOD = "ROSTER_PERIOD";
    public static final String ET_ASSIGNMENT = "ROSTER_ASSIGNMENT";
    public static final String ET_SWAP = "ROSTER_SWAP";

    /** A manual assignment command. */
    public record AssignCommand(String periodId, String personId, String shiftId, LocalDate day, AssignmentKind kind,
                                String demandId, String dutySeatId, String locationId, boolean override,
                                String overrideReason) {
    }

    private static final String A_COLS = "a.tenant_id, a.assignment_id, a.period_id, a.person_id, a.seat_id, a.shift_id,"
            + " a.duty_day, a.starts_at, a.ends_at, a.kind, a.demand_id, a.duty_seat_id, a.location_id, a.state,"
            + " a.posting_ref, a.attendance, a.swapped_from, a.row_version";

    private final ResourcesContext ctx;

    public RosterService(ResourcesContext ctx) {
        this.ctx = ctx;
    }

    /** Database time (A-1). */
    public Instant now(Connection c) {
        return ctx.now(c);
    }

    // ================================================================== shift templates

    public ShiftTemplate saveShift(Connection c, SecurityIdentity actor, ShiftTemplate s) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, "*");
        String t = actor.tenantId();
        String id = s.shiftId() == null ? Sql.newId() : s.shiftId();
        int n = Jdbc.update(c, "UPDATE RES_SHIFT_TEMPLATE SET code=?, name=?, from_time=?, duration_minutes=?, night_flag=?,"
                        + " skills=?, zone_id=?, attributes_json=? WHERE tenant_id=? AND shift_id=?",
                s.code(), s.name(), s.fromTime().toString(), s.durationMinutes(), s.night(), RosterModel.csv(s.skills()),
                s.zone().getId(), Sql.toJson(s.attributes()), t, id);
        if (n == 0) {
            boolean ok = ctx.dialect().insertIfAbsent(c, "INSERT INTO RES_SHIFT_TEMPLATE(tenant_id, shift_id, code, name,"
                            + " from_time, duration_minutes, night_flag, skills, zone_id, attributes_json)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    t, id, s.code(), s.name(), s.fromTime().toString(), s.durationMinutes(), s.night(),
                    RosterModel.csv(s.skills()), s.zone().getId(), Sql.toJson(s.attributes()));
            if (!ok) {
                throw ResourcesException.conflict("SHIFT_CODE_TAKEN", "shift code already exists: " + s.code());
            }
        }
        ctx.event(c, actor, "ROSTER_SHIFT", id, "SHIFT_SAVED", null, null, Map.of("code", s.code()));
        return requireShift(c, t, id);
    }

    /** Visible shift by id (empty when missing or hidden). */
    public Optional<ShiftTemplate> shift(Connection c, SecurityIdentity actor, String shiftId) {
        return vq(actor, ResourcesContext.RT_SHIFT, "RES_SHIFT_TEMPLATE", "s").byId(c, "shift_id", shiftId,
                RosterService::shiftRow);
    }

    public List<ShiftTemplate> shifts(Connection c, SecurityIdentity actor, int offset, int limit) {
        return vq(actor, ResourcesContext.RT_SHIFT, "RES_SHIFT_TEMPLATE", "s").orderBy("s.code")
                .list(c, RosterService::shiftRow, offset, limit);
    }

    public void deleteShift(Connection c, SecurityIdentity actor, String shiftId) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, "*");
        long used = Jdbc.one(c, "SELECT COUNT(*) FROM RES_ROSTER_ASSIGNMENT WHERE tenant_id=? AND shift_id=?",
                rs -> rs.getLong(1), actor.tenantId(), shiftId).orElse(0L)
                + Jdbc.one(c, "SELECT COUNT(*) FROM RES_COVERAGE_DEMAND WHERE tenant_id=? AND shift_id=?",
                rs -> rs.getLong(1), actor.tenantId(), shiftId).orElse(0L);
        if (used > 0) {
            throw ResourcesException.conflict("SHIFT_IN_USE", "the shift is used by assignments or demand");
        }
        Jdbc.update(c, "DELETE FROM RES_SHIFT_TEMPLATE WHERE tenant_id=? AND shift_id=?", actor.tenantId(), shiftId);
        ctx.event(c, actor, "ROSTER_SHIFT", shiftId, "SHIFT_DELETED", null, null, null);
    }

    // ================================================================== rotations

    public RotationPattern saveRotation(Connection c, SecurityIdentity actor, RotationPattern r) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, "*");
        String t = actor.tenantId();
        String id = r.rotationId() == null ? Sql.newId() : r.rotationId();
        int n = Jdbc.update(c, "UPDATE RES_ROTATION SET name=?, cycle_json=? WHERE tenant_id=? AND rotation_id=?",
                r.name(), Sql.toJson(r.cycle()), t, id);
        if (n == 0) {
            Jdbc.update(c, "INSERT INTO RES_ROTATION(tenant_id, rotation_id, name, cycle_json) VALUES (?,?,?,?)",
                    t, id, r.name(), Sql.toJson(r.cycle()));
        }
        ctx.event(c, actor, "ROSTER_ROTATION", id, "ROTATION_SAVED", null, null, Map.of("cycle", r.cycle()));
        return new RotationPattern(t, id, r.name(), r.cycle());
    }

    public Optional<RotationPattern> rotation(Connection c, SecurityIdentity actor, String rotationId) {
        return vq(actor, ResourcesContext.RT_ROTATION, "RES_ROTATION", "r").byId(c, "rotation_id", rotationId,
                RosterService::rotationRow);
    }

    public List<RotationPattern> rotations(Connection c, SecurityIdentity actor, int offset, int limit) {
        return vq(actor, ResourcesContext.RT_ROTATION, "RES_ROTATION", "r").orderBy("r.name, r.rotation_id")
                .list(c, RosterService::rotationRow, offset, limit);
    }

    public void deleteRotation(Connection c, SecurityIdentity actor, String rotationId) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, "*");
        Jdbc.update(c, "DELETE FROM RES_ROTATION WHERE tenant_id=? AND rotation_id=?", actor.tenantId(), rotationId);
        ctx.event(c, actor, "ROSTER_ROTATION", rotationId, "ROTATION_DELETED", null, null, null);
    }

    // ================================================================== periods, members, demand

    public RosterPeriod createPeriod(Connection c, SecurityIdentity actor, String unitId, String name, LocalDate firstDay,
                                     LocalDate lastDay, Map<String, Object> constraints) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, unitId);
        if (lastDay.isBefore(firstDay)) {
            throw ResourcesException.invalid("INVALID_RANGE", "lastDay is before firstDay");
        }
        String id = Sql.newId();
        RosterConstraints k = RosterConstraints.from(constraints);
        Jdbc.update(c, "INSERT INTO RES_ROSTER_PERIOD(tenant_id, period_id, unit_id, name, first_day, last_day, state,"
                        + " constraints_json, row_version, created_at) VALUES (?,?,?,?,?,?,?,?,0,?)",
                actor.tenantId(), id, unitId, name, firstDay.toString(), lastDay.toString(), PeriodState.DRAFT,
                Sql.toJson(k.toMap()), ctx.now(c));
        ctx.event(c, actor, ET_PERIOD, id, "PERIOD_CREATED", null, null,
                Map.of("unitId", unitId, "firstDay", firstDay.toString(), "lastDay", lastDay.toString()));
        return requirePeriod(c, actor.tenantId(), id);
    }

    /** Visible period or NOT_FOUND (a hidden period is reported like a missing one). */
    public RosterPeriod period(Connection c, SecurityIdentity actor, String periodId) {
        return vq(actor, ResourcesContext.RT_ROSTER, "RES_ROSTER_PERIOD", "p")
                .require(c, "period_id", periodId, RosterService::periodRow, "roster period");
    }

    /** Visible periods of a unit (all units when {@code unitId} is null), newest first. */
    public List<RosterPeriod> periods(Connection c, SecurityIdentity actor, String unitId, int offset, int limit) {
        VisibleQuery q = vq(actor, ResourcesContext.RT_ROSTER, "RES_ROSTER_PERIOD", "p");
        if (unitId != null) {
            q.where("p.unit_id=?", unitId);
        }
        return q.orderBy("p.first_day DESC, p.period_id").list(c, RosterService::periodRow, offset, limit);
    }

    public RosterPeriod updateConstraints(Connection c, SecurityIdentity actor, String periodId, long version,
                                          Map<String, Object> constraints) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, periodId);
        RosterPeriod p = requirePeriod(c, actor.tenantId(), periodId);
        requireState(p, PeriodState.DRAFT, PeriodState.GENERATED);
        bumpPeriod(c, actor.tenantId(), periodId, version, "constraints_json=?", Sql.toJson(RosterConstraints.from(constraints).toMap()));
        ctx.event(c, actor, ET_PERIOD, periodId, "CONSTRAINTS_CHANGED", null, null, constraints);
        return requirePeriod(c, actor.tenantId(), periodId);
    }

    public RosterPeriod closePeriod(Connection c, SecurityIdentity actor, String periodId, long version) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, periodId);
        RosterPeriod p = requirePeriod(c, actor.tenantId(), periodId);
        requireState(p, PeriodState.PUBLISHED);
        bumpPeriod(c, actor.tenantId(), periodId, version, "state=?", PeriodState.CLOSED);
        ctx.event(c, actor, ET_PERIOD, periodId, "PERIOD_CLOSED", null, null, null);
        return requirePeriod(c, actor.tenantId(), periodId);
    }

    public RosterMember addMember(Connection c, SecurityIdentity actor, RosterMember m) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, m.periodId());
        RosterPeriod p = requirePeriod(c, actor.tenantId(), m.periodId());
        requireOpen(p);
        String t = actor.tenantId();
        int n = Jdbc.update(c, "UPDATE RES_ROSTER_MEMBER SET seat_id=?, skills=?, rotation_id=?, rotation_offset=?,"
                        + " attributes_json=? WHERE tenant_id=? AND period_id=? AND person_id=?",
                m.seatId(), RosterModel.csv(m.skills()), m.rotationId(), m.rotationOffset(), Sql.toJson(m.attributes()),
                t, m.periodId(), m.personId());
        if (n == 0) {
            Jdbc.update(c, "INSERT INTO RES_ROSTER_MEMBER(tenant_id, period_id, person_id, seat_id, skills, rotation_id,"
                            + " rotation_offset, attributes_json) VALUES (?,?,?,?,?,?,?,?)",
                    t, m.periodId(), m.personId(), m.seatId(), RosterModel.csv(m.skills()), m.rotationId(),
                    m.rotationOffset(), Sql.toJson(m.attributes()));
        }
        ctx.event(c, actor, ET_PERIOD, m.periodId(), "MEMBER_SET", null, null, Map.of("personId", m.personId()));
        return m;
    }

    public void removeMember(Connection c, SecurityIdentity actor, String periodId, String personId) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, periodId);
        requireOpen(requirePeriod(c, actor.tenantId(), periodId));
        Jdbc.update(c, "DELETE FROM RES_ROSTER_MEMBER WHERE tenant_id=? AND period_id=? AND person_id=?",
                actor.tenantId(), periodId, personId);
        ctx.event(c, actor, ET_PERIOD, periodId, "MEMBER_REMOVED", null, null, Map.of("personId", personId));
    }

    /** Visible members of a visible period. */
    public List<RosterMember> members(Connection c, SecurityIdentity actor, String periodId, int offset, int limit) {
        period(c, actor, periodId);
        return vq(actor, ResourcesContext.RT_ROSTER_MEMBER, "RES_ROSTER_MEMBER", "m").where("m.period_id=?", periodId)
                .orderBy("m.person_id").list(c, RosterService::memberRow, offset, limit);
    }

    public CoverageDemand addDemand(Connection c, SecurityIdentity actor, CoverageDemand d) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, d.periodId());
        requireOpen(requirePeriod(c, actor.tenantId(), d.periodId()));
        if (d.requiredCount() <= 0) {
            throw ResourcesException.invalid("INVALID_COUNT", "requiredCount must be positive");
        }
        requireShift(c, actor.tenantId(), d.shiftId());
        String id = d.demandId() == null ? Sql.newId() : d.demandId();
        Jdbc.update(c, "INSERT INTO RES_COVERAGE_DEMAND(tenant_id, demand_id, period_id, shift_id, skill, weekdays,"
                        + " duty_day, required_count, duty_seat_id, location_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
                actor.tenantId(), id, d.periodId(), d.shiftId(), d.skill(), RosterModel.weekdaysCsv(d.weekdays()),
                d.dutyDay() == null ? null : d.dutyDay().toString(), d.requiredCount(), d.dutySeatId(), d.locationId());
        ctx.event(c, actor, ET_PERIOD, d.periodId(), "DEMAND_ADDED", null, null,
                Map.of("demandId", id, "shiftId", d.shiftId(), "requiredCount", d.requiredCount()));
        return new CoverageDemand(id, d.periodId(), d.shiftId(), d.skill(), d.weekdays(), d.dutyDay(), d.requiredCount(),
                d.dutySeatId(), d.locationId());
    }

    public void removeDemand(Connection c, SecurityIdentity actor, String periodId, String demandId) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, periodId);
        requireOpen(requirePeriod(c, actor.tenantId(), periodId));
        Jdbc.update(c, "DELETE FROM RES_COVERAGE_DEMAND WHERE tenant_id=? AND period_id=? AND demand_id=?",
                actor.tenantId(), periodId, demandId);
        ctx.event(c, actor, ET_PERIOD, periodId, "DEMAND_REMOVED", null, null, Map.of("demandId", demandId));
    }

    /** Visible coverage demand of a visible period. */
    public List<CoverageDemand> demands(Connection c, SecurityIdentity actor, String periodId, int offset, int limit) {
        period(c, actor, periodId);
        return vq(actor, ResourcesContext.RT_COVERAGE, "RES_COVERAGE_DEMAND", "d").where("d.period_id=?", periodId)
                .orderBy("d.demand_id").list(c, RosterService::demandRow, offset, limit);
    }

    // ================================================================== generation

    /**
     * Generates (or regenerates) the draft roster. Existing DRAFT rows of the period are replaced; published rows of
     * other periods count as existing duty for the constraints and the person claims.
     *
     * @param seed audited random seed; null draws a fresh one (stored on the period and in the event)
     */
    public GenerationReport generate(Connection c, SecurityIdentity actor, String periodId, long version, String seed) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, periodId);
        String t = actor.tenantId();
        RosterPeriod p = requirePeriod(c, t, periodId);
        requireState(p, PeriodState.DRAFT, PeriodState.GENERATED);
        String useSeed = seed == null || seed.isBlank() ? Sql.newId() : seed;

        // drop the previous draft
        Jdbc.update(c, "DELETE FROM RES_PERSON_CLAIM WHERE tenant_id=? AND assignment_id IN (SELECT assignment_id FROM"
                + " RES_ROSTER_ASSIGNMENT WHERE tenant_id=? AND period_id=? AND state=?)", t, t, periodId, AssignmentState.DRAFT);
        Jdbc.update(c, "DELETE FROM RES_ROSTER_ASSIGNMENT WHERE tenant_id=? AND period_id=? AND state=?",
                t, periodId, AssignmentState.DRAFT);

        Map<String, ShiftTemplate> shifts = new HashMap<>();
        for (ShiftTemplate s : Jdbc.query(c, "SELECT * FROM RES_SHIFT_TEMPLATE WHERE tenant_id=?", RosterService::shiftRow, t)) {
            shifts.put(s.shiftId(), s);
        }
        Map<String, RotationPattern> rotations = new HashMap<>();
        for (RotationPattern r : Jdbc.query(c, "SELECT * FROM RES_ROTATION WHERE tenant_id=?", RosterService::rotationRow, t)) {
            rotations.put(r.rotationId(), r);
        }
        List<RosterMember> members = loadMembers(c, t, periodId);
        List<CoverageDemand> demands = loadDemands(c, t, periodId);
        Instant from = p.firstDay().minusDays(8).atStartOfDay(ZoneId.of("UTC")).toInstant();
        Instant to = p.lastDay().plusDays(9).atStartOfDay(ZoneId.of("UTC")).toInstant();
        Map<String, List<Duty>> existing = new HashMap<>();
        Map<String, List<ResourcesPorts.LeaveInterval>> leave = new HashMap<>();
        Map<String, RosterConstraints> constraints = new HashMap<>();
        for (RosterMember m : members) {
            existing.put(m.personId(), loadDuties(c, t, m.personId(), from, to, Set.of()));
            leave.put(m.personId(), ctx.leave().leaveOf(c, t, m.personId(), from, to));
            constraints.put(m.personId(), resolveConstraints(t, p, m));
        }
        RosterGenerator.Result r = RosterGenerator.generate(new RosterGenerator.Input(p.firstDay(), p.lastDay(), shifts,
                rotations, members, demands, existing, leave, m -> constraints.get(m.personId()), useSeed));

        List<Violation> rejected = new ArrayList<>(r.rejected());
        int assigned = 0;
        int leaveMarked = 0;
        for (RosterGenerator.Planned pl : r.planned()) {
            Optional<String> id = insertAssignment(c, t, periodId, pl.personId(), pl.seatId(), pl.shiftId(), pl.day(),
                    pl.start(), pl.end(), pl.kind(), pl.demandId(), pl.dutySeatId(), pl.locationId(), AssignmentState.DRAFT, null);
            if (id.isEmpty()) {
                rejected.add(new Violation("DOUBLE_ROSTERED", pl.personId(), pl.day(), "already rostered in these hours"));
            } else if (pl.kind() == AssignmentKind.LEAVE) {
                leaveMarked++;
            } else {
                assigned++;
            }
        }
        bumpPeriod(c, t, periodId, version, "state=?, seed=?, generated_at=?", PeriodState.GENERATED, useSeed, ctx.now(c));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("seed", useSeed);
        ev.put("algorithm", RosterGenerator.ALGORITHM);
        ev.put("entrantsHash", r.entrantsHash());
        ev.put("assigned", assigned);
        ev.put("leaveMarked", leaveMarked);
        ev.put("unfilled", r.unfilled().size());
        ev.put("rejected", rejected.size());
        ctx.event(c, actor, ET_PERIOD, periodId, "ROSTER_GENERATED", null, null, ev);
        return new GenerationReport(periodId, useSeed, r.entrantsHash(), assigned, leaveMarked, r.unfilled(), rejected);
    }

    /** Audited random deployment of people to named slots (recorded with seed and entrants hash). */
    public RosterGenerator.RandomisationResult randomise(Connection c, SecurityIdentity actor, String periodId, String seed,
                                                         List<String> people, List<String> slots) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, periodId);
        requirePeriod(c, actor.tenantId(), periodId);
        String useSeed = seed == null || seed.isBlank() ? Sql.newId() : seed;
        RosterGenerator.RandomisationResult r = RosterGenerator.auditedRandomisation(useSeed, people, slots);
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (RosterGenerator.Pairing pr : r.pairings()) {
            pairs.add(Map.of("slot", pr.slot(), "person", pr.person()));
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("seed", useSeed);
        ev.put("algorithm", r.algorithm());
        ev.put("entrantsHash", r.entrantsHash());
        ev.put("pairings", pairs);
        ev.put("unfilledSlots", r.unfilledSlots());
        ctx.event(c, actor, ET_PERIOD, periodId, "ROSTER_RANDOMISED", null, null, ev);
        return r;
    }

    // ================================================================== manual assignment

    public RosterAssignment assign(Connection c, SecurityIdentity actor, AssignCommand cmd) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, cmd.periodId());
        String t = actor.tenantId();
        RosterPeriod p = requirePeriod(c, t, cmd.periodId());
        requireOpen(p);
        if (cmd.day().isBefore(p.firstDay()) || cmd.day().isAfter(p.lastDay())) {
            throw ResourcesException.invalid("OUT_OF_PERIOD", "the day is outside the roster period");
        }
        ShiftTemplate s = requireShift(c, t, cmd.shiftId());
        AssignmentKind kind = cmd.kind() == null ? AssignmentKind.DUTY : cmd.kind();
        RosterMember member = loadMembers(c, t, cmd.periodId()).stream()
                .filter(m -> m.personId().equals(cmd.personId())).findFirst()
                .orElse(new RosterMember(cmd.periodId(), cmd.personId(), null, Set.of(), null, 0, Map.of()));
        Instant start = s.startOn(cmd.day());
        Instant end = s.endOn(cmd.day());
        if (kind == AssignmentKind.DUTY || kind == AssignmentKind.ON_CALL) {
            String skill = cmd.demandId() == null ? null : demandSkill(c, t, cmd.demandId());
            List<Violation> v = checkFor(c, t, p, member, null, cmd.day(), start, end, s.night(), skill, Set.of());
            if (kind == AssignmentKind.ON_CALL) {
                v = v.stream().filter(x -> x.code().equals("ON_LEAVE") || x.code().equals("OVERLAP")).toList();
            }
            if (!v.isEmpty()) {
                if (!cmd.override()) {
                    throw violations(v);
                }
                if (cmd.overrideReason() == null || cmd.overrideReason().isBlank()) {
                    throw ResourcesException.invalid("REASON_REQUIRED", "an override needs a reason");
                }
            }
        }
        AssignmentState st = p.state() == PeriodState.PUBLISHED ? AssignmentState.PUBLISHED : AssignmentState.DRAFT;
        String id = insertAssignment(c, t, cmd.periodId(), cmd.personId(), member.seatId(), s.shiftId(), cmd.day(), start,
                end, kind, cmd.demandId(), cmd.dutySeatId(), cmd.locationId(), st, null)
                .orElseThrow(() -> ResourcesException.conflict("DOUBLE_ROSTERED", "the person is already rostered then"));
        if (st == AssignmentState.PUBLISHED && kind == AssignmentKind.DUTY && cmd.dutySeatId() != null) {
            linkSeat(c, actor, requireAssignment(c, t, id));
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("personId", cmd.personId());
        ev.put("shiftId", s.shiftId());
        ev.put("day", cmd.day().toString());
        ev.put("kind", kind.name());
        ev.put("override", cmd.override());
        ctx.event(c, actor, ET_ASSIGNMENT, id, cmd.override() ? "ASSIGNED_WITH_OVERRIDE" : "ASSIGNED",
                cmd.overrideReason(), null, ev);
        if (st == AssignmentState.PUBLISHED) {
            ctx.notify(c, t, cmd.personId(), "roster.assigned", Map.of("assignmentId", id,
                    "day", cmd.day().toString(), "shiftId", s.shiftId()));
        }
        return requireAssignment(c, t, id);
    }

    /** Marks a person on call for a shift (no hour claim; only leave and overlap are checked). */
    public RosterAssignment markOnCall(Connection c, SecurityIdentity actor, String periodId, String personId,
                                       String shiftId, LocalDate day) {
        return assign(c, actor, new AssignCommand(periodId, personId, shiftId, day, AssignmentKind.ON_CALL, null, null,
                null, false, null));
    }

    /** Removes an assignment (cancelling it): releases the person claims and ends the duty-seat link. */
    public void unassign(Connection c, SecurityIdentity actor, String assignmentId, long version, String reason) {
        String t = actor.tenantId();
        RosterAssignment a = requireAssignment(c, t, assignmentId);
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, a.periodId());
        if (a.version() != version) {
            throw ResourcesException.stale("assignment changed: " + assignmentId);
        }
        requireOpen(requirePeriod(c, t, a.periodId()));
        unlinkSeat(c, actor, a);
        releaseClaims(c, t, assignmentId);
        Jdbc.update(c, "DELETE FROM RES_ROSTER_ASSIGNMENT WHERE tenant_id=? AND assignment_id=? AND row_version=?",
                t, assignmentId, version);
        ctx.event(c, actor, ET_ASSIGNMENT, assignmentId, "UNASSIGNED", reason, null,
                Map.of("personId", a.personId(), "day", a.dutyDay().toString(), "shiftId", a.shiftId()));
        if (a.state() == AssignmentState.PUBLISHED) {
            ctx.notify(c, t, a.personId(), "roster.unassigned",
                    Map.of("assignmentId", assignmentId, "day", a.dutyDay().toString()));
        }
    }

    // ================================================================== approval and publication

    /** Sends the generated roster for approval (C02); returns the process reference. */
    public String submitForApproval(Connection c, SecurityIdentity actor, String periodId, long version) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, periodId);
        String t = actor.tenantId();
        RosterPeriod p = requirePeriod(c, t, periodId);
        requireState(p, PeriodState.GENERATED);
        long count = Jdbc.one(c, "SELECT COUNT(*) FROM RES_ROSTER_ASSIGNMENT WHERE tenant_id=? AND period_id=?",
                rs -> rs.getLong(1), t, periodId).orElse(0L);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("periodId", periodId);
        vars.put("unitId", p.unitId());
        vars.put("rosterName", p.name());
        vars.put("firstDay", p.firstDay().toString());
        vars.put("lastDay", p.lastDay().toString());
        vars.put("assignmentCount", count);
        vars.put("tenantId", t);
        // candidate seat from configuration: per unit first, then the module default (default BPMN sangam-roster-publish)
        Object seat = ctx.settings().getOrDefault("rosterApproverSeat." + p.unitId(), ctx.settings().get("rosterApproverSeat"));
        if (seat != null) {
            vars.put("approverSeatId", seat.toString());
        }
        vars.put("approvalTimeout", String.valueOf(ctx.settings().getOrDefault("rosterApprovalTimeout", "P7D")));
        String key = p.constraints().approvalProcessKey() == null
                ? RosterConstraints.DEFAULT_APPROVAL_PROCESS : p.constraints().approvalProcessKey();
        String ref = ctx.approvals().startApproval(c, actor, key, ET_PERIOD, periodId, "roster:" + periodId, vars);
        bumpPeriod(c, t, periodId, version, "state=?, process_ref=?", PeriodState.SUBMITTED, ref);
        ctx.event(c, actor, ET_PERIOD, periodId, "SUBMITTED_FOR_APPROVAL", null, ref, Map.of("processKey", key));
        return ref;
    }

    /**
     * Records the approval outcome: approved publishes, rejected returns the roster to GENERATED with the reason.
     *
     * @param version expected period version, or null to act on the current one (engine callbacks)
     */
    public RosterPeriod decidePublication(Connection c, SecurityIdentity actor, String periodId, Long version,
                                          boolean approved, String reason) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_APPROVE, periodId);
        String t = actor.tenantId();
        RosterPeriod p = requirePeriod(c, t, periodId);
        requireState(p, PeriodState.SUBMITTED);
        long v = version == null ? p.version() : version;
        if (approved) {
            return doPublish(c, actor, p, v, reason);
        }
        bumpPeriod(c, t, periodId, v, "state=?", PeriodState.GENERATED);
        ctx.event(c, actor, ET_PERIOD, periodId, "PUBLICATION_REJECTED", reason, p.processRef(), null);
        return requirePeriod(c, t, periodId);
    }

    /** Publishes directly (units whose configuration needs no approval process). */
    public RosterPeriod publish(Connection c, SecurityIdentity actor, String periodId, long version) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_APPROVE, periodId);
        RosterPeriod p = requirePeriod(c, actor.tenantId(), periodId);
        requireState(p, PeriodState.GENERATED, PeriodState.SUBMITTED);
        return doPublish(c, actor, p, version, null);
    }

    private RosterPeriod doPublish(Connection c, SecurityIdentity actor, RosterPeriod p, long version, String reason) {
        String t = actor.tenantId();
        Instant now = ctx.now(c);
        bumpPeriod(c, t, p.periodId(), version, "state=?, published_at=?, published_by=?",
                PeriodState.PUBLISHED, now, actor.personId());
        Jdbc.update(c, "UPDATE RES_ROSTER_ASSIGNMENT SET state=?, row_version=row_version+1, updated_at=? WHERE tenant_id=?"
                + " AND period_id=? AND state=?", AssignmentState.PUBLISHED, now, t, p.periodId(), AssignmentState.DRAFT);
        List<RosterAssignment> all = unitAssignments(c, t, p.periodId());
        Map<String, Integer> perPerson = new LinkedHashMap<>();
        int linked = 0;
        for (RosterAssignment a : all) {
            perPerson.merge(a.personId(), 1, Integer::sum);
            if (a.kind() == AssignmentKind.DUTY && a.dutySeatId() != null && a.postingRef() == null) {
                if (linkSeat(c, actor, a) != null) {
                    linked++;
                }
            }
        }
        for (Map.Entry<String, Integer> e : perPerson.entrySet()) {
            ctx.notify(c, t, e.getKey(), "roster.published", Map.of("periodId", p.periodId(),
                    "rosterName", p.name(), "assignments", e.getValue()));
        }
        ctx.event(c, actor, ET_PERIOD, p.periodId(), "ROSTER_PUBLISHED", reason, p.processRef(),
                Map.of("assignments", all.size(), "seatLinks", linked));
        return requirePeriod(c, t, p.periodId());
    }

    // ================================================================== swaps and covers

    /**
     * Requests a swap (with {@code counterpartAssignmentId}) or a cover (without). The requester is the holder of the
     * assignment; an administrator may request on their behalf.
     */
    public SwapRequest requestSwap(Connection c, SecurityIdentity actor, String assignmentId, String counterpartId,
                                   String counterpartAssignmentId, String reason) {
        String t = actor.tenantId();
        RosterAssignment a = requireAssignment(c, t, assignmentId);
        if (!a.personId().equals(actor.personId())) {
            ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, a.periodId());
        } else {
            ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_MODIFY, a.periodId());
        }
        requireOpen(requirePeriod(c, t, a.periodId()));
        if (a.kind() != AssignmentKind.DUTY && a.kind() != AssignmentKind.ON_CALL) {
            throw ResourcesException.invalid("NOT_SWAPPABLE", "only duty and on-call assignments can be swapped");
        }
        if (counterpartId.equals(a.personId())) {
            throw ResourcesException.invalid("SAME_PERSON", "cannot swap with oneself");
        }
        if (counterpartAssignmentId != null) {
            RosterAssignment b = requireAssignment(c, t, counterpartAssignmentId);
            if (!b.personId().equals(counterpartId)) {
                throw ResourcesException.invalid("COUNTERPART_MISMATCH", "the counterpart does not hold that assignment");
            }
        }
        return insertSwap(c, actor, counterpartAssignmentId == null ? SwapKind.COVER : SwapKind.SWAP, a.assignmentId(),
                a.personId(), counterpartId, counterpartAssignmentId, reason);
    }

    /** The counterpart accepts; if the configuration names a swap approval process, it is started. */
    public SwapRequest acceptSwap(Connection c, SecurityIdentity actor, String swapId, long version) {
        String t = actor.tenantId();
        SwapRequest s = requireSwap(c, t, swapId);
        if (!actor.system() && !actor.personId().equals(s.counterpartId())) {
            throw ResourcesException.invalid("NOT_COUNTERPART", "only the counterpart can accept");
        }
        requireSwapState(s, SwapState.REQUESTED);
        RosterAssignment a = requireAssignment(c, t, s.assignmentId());
        RosterPeriod p = requirePeriod(c, t, a.periodId());
        String ref = null;
        if (p.constraints().swapApprovalProcessKey() != null) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("swapId", swapId);
            vars.put("periodId", p.periodId());
            vars.put("requesterId", s.requesterId());
            vars.put("counterpartId", s.counterpartId());
            vars.put("kind", s.kind().name());
            vars.put("tenantId", t);
            ref = ctx.approvals().startApproval(c, actor, p.constraints().swapApprovalProcessKey(), ET_SWAP, swapId,
                    "roster-swap:" + swapId, vars);
        }
        bumpSwap(c, t, swapId, version, "state=?, process_ref=?", SwapState.ACCEPTED, ref);
        ctx.event(c, actor, ET_SWAP, swapId, "SWAP_ACCEPTED", null, ref, null);
        return requireSwap(c, t, swapId);
    }

    /**
     * Approves (re-checking every constraint for both people, then moving the assignments and their claims) or rejects.
     *
     * @param version expected swap version, or null for the current one (engine callbacks)
     */
    public SwapRequest decideSwap(Connection c, SecurityIdentity actor, String swapId, Long version, boolean approve,
                                  String reason) {
        String t = actor.tenantId();
        SwapRequest s = requireSwap(c, t, swapId);
        RosterAssignment a = requireAssignment(c, t, s.assignmentId());
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_APPROVE, a.periodId());
        requireSwapState(s, SwapState.ACCEPTED);
        if (!actor.system() && (actor.personId().equals(s.requesterId()) || actor.personId().equals(s.counterpartId()))) {
            throw ResourcesException.invalid("SOD_VIOLATION", "a party to the swap cannot decide it");
        }
        long v = version == null ? s.version() : version;
        Instant now = ctx.now(c);
        if (!approve) {
            bumpSwap(c, t, swapId, v, "state=?, decided_by=?, decided_at=?", SwapState.REJECTED, actor.personId(), now);
            ctx.event(c, actor, ET_SWAP, swapId, "SWAP_REJECTED", reason, s.processRef(), null);
            notifySwap(c, t, s, "roster.swap.rejected");
            return requireSwap(c, t, swapId);
        }
        RosterPeriod p = requirePeriod(c, t, a.periodId());
        RosterAssignment b = s.counterpartAssignmentId() == null ? null : requireAssignment(c, t, s.counterpartAssignmentId());
        Set<String> exclude = b == null ? Set.of(a.assignmentId()) : Set.of(a.assignmentId(), b.assignmentId());
        List<Violation> v1 = checkAssignmentFor(c, t, p, s.counterpartId(), a, exclude);
        List<Violation> all = new ArrayList<>(v1);
        if (b != null) {
            RosterPeriod pb = requirePeriod(c, t, b.periodId());
            all.addAll(checkAssignmentFor(c, t, pb, s.requesterId(), b, exclude));
        }
        if (!all.isEmpty()) {
            throw violations(all);
        }
        moveAssignment(c, actor, a, s.counterpartId(), now);
        if (b != null) {
            moveAssignment(c, actor, b, s.requesterId(), now);
        }
        bumpSwap(c, t, swapId, v, "state=?, decided_by=?, decided_at=?", SwapState.APPROVED, actor.personId(), now);
        ctx.event(c, actor, ET_SWAP, swapId, "SWAP_APPROVED", reason, s.processRef(),
                Map.of("kind", s.kind().name(), "assignmentId", a.assignmentId()));
        notifySwap(c, t, s, "roster.swap.approved");
        return requireSwap(c, t, swapId);
    }

    public SwapRequest cancelSwap(Connection c, SecurityIdentity actor, String swapId, long version) {
        String t = actor.tenantId();
        SwapRequest s = requireSwap(c, t, swapId);
        if (!actor.personId().equals(s.requesterId())) {
            RosterAssignment a = requireAssignment(c, t, s.assignmentId());
            ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, a.periodId());
        }
        requireSwapState(s, SwapState.REQUESTED, SwapState.ACCEPTED);
        bumpSwap(c, t, swapId, version, "state=?, decided_at=?", SwapState.CANCELLED, ctx.now(c));
        ctx.event(c, actor, ET_SWAP, swapId, "SWAP_CANCELLED", null, null, null);
        return requireSwap(c, t, swapId);
    }

    /** Visible swap request or NOT_FOUND. */
    public SwapRequest swap(Connection c, SecurityIdentity actor, String swapId) {
        return vq(actor, ResourcesContext.RT_SWAP, "RES_SWAP_REQUEST", "s")
                .require(c, "swap_id", swapId, RosterService::swapRow, "swap request");
    }

    /** Visible swap requests on assignments of a visible period, oldest first. */
    public List<SwapRequest> swaps(Connection c, SecurityIdentity actor, String periodId, int offset, int limit) {
        period(c, actor, periodId);
        return vq(actor, ResourcesContext.RT_SWAP, "RES_SWAP_REQUEST", "s")
                .where("EXISTS (SELECT 1 FROM RES_ROSTER_ASSIGNMENT x WHERE x.tenant_id=s.tenant_id"
                        + " AND x.assignment_id=s.assignment_id AND x.period_id=?)", periodId)
                .orderBy("s.created_at, s.swap_id").list(c, RosterService::swapRow, offset, limit);
    }

    // ================================================================== leave and attendance

    /**
     * Leave integration: duty assignments of the period that overlap an approved leave become LEAVE (claims released,
     * seat link ended); when the leave names a substitute, a COVER request is offered to the substitute.
     *
     * @return number of assignments changed to LEAVE
     */
    public int applyLeave(Connection c, SecurityIdentity actor, String periodId) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, periodId);
        String t = actor.tenantId();
        requireOpen(requirePeriod(c, t, periodId));
        int changed = 0;
        for (RosterAssignment a : unitAssignments(c, t, periodId)) {
            if (a.kind() != AssignmentKind.DUTY && a.kind() != AssignmentKind.ON_CALL) {
                continue;
            }
            for (ResourcesPorts.LeaveInterval l : ctx.leave().leaveOf(c, t, a.personId(), a.startsAt(), a.endsAt())) {
                if (!(a.startsAt().isBefore(l.to()) && l.from().isBefore(a.endsAt()))) {
                    continue;
                }
                unlinkSeat(c, actor, a);
                releaseClaims(c, t, a.assignmentId());
                int n = Jdbc.update(c, "UPDATE RES_ROSTER_ASSIGNMENT SET kind=?, posting_ref=NULL, row_version=row_version+1,"
                                + " updated_at=? WHERE tenant_id=? AND assignment_id=? AND row_version=?",
                        AssignmentKind.LEAVE, ctx.now(c), t, a.assignmentId(), a.version());
                if (n == 0) {
                    throw ResourcesException.stale("assignment changed: " + a.assignmentId());
                }
                changed++;
                ctx.event(c, actor, ET_ASSIGNMENT, a.assignmentId(), "MARKED_LEAVE", null, l.ref(),
                        Map.of("personId", a.personId()));
                if (l.substitutePersonId() != null && !l.substitutePersonId().equals(a.personId())) {
                    SwapRequest offer = insertSwap(c, actor, SwapKind.COVER, a.assignmentId(), a.personId(),
                            l.substitutePersonId(), null, "leave " + l.ref());
                    ctx.notify(c, t, l.substitutePersonId(), "roster.cover.offered",
                            Map.of("swapId", offer.swapId(), "assignmentId", a.assignmentId(), "day", a.dutyDay().toString()));
                }
                break;
            }
        }
        return changed;
    }

    /**
     * Attendance reconciliation: for published duty that has ended, compares the duty window with the
     * time-and-attendance records. PRESENT when the uncovered time is within {@code attendanceGraceMinutes}
     * (setting, default 15), ABSENT when nothing overlaps, otherwise PARTIAL.
     *
     * @return number of assignments updated
     */
    public int reconcileAttendance(Connection c, SecurityIdentity actor, String periodId) {
        ctx.guard().require(c, actor, ResourcesContext.RT_ROSTER, ResourcesContext.P_ADMIN, periodId);
        String t = actor.tenantId();
        Instant now = ctx.now(c);
        long grace = ctx.setting("attendanceGraceMinutes", 15);
        int updated = 0;
        for (RosterAssignment a : unitAssignments(c, t, periodId)) {
            if (a.kind() != AssignmentKind.DUTY || a.state() != AssignmentState.PUBLISHED || a.endsAt().isAfter(now)) {
                continue;
            }
            long covered = 0;
            for (ResourcesPorts.AttendanceRecord r : ctx.attendance().attendance(c, t, a.personId(), a.startsAt(), a.endsAt())) {
                Instant in = r.in().isAfter(a.startsAt()) ? r.in() : a.startsAt();
                Instant out = r.out() == null || r.out().isAfter(a.endsAt()) ? a.endsAt() : r.out();
                if (out.isAfter(in)) {
                    covered += ChronoUnit.MINUTES.between(in, out);
                }
            }
            Attendance att = covered == 0 ? Attendance.ABSENT
                    : (a.minutes() - covered <= grace ? Attendance.PRESENT : Attendance.PARTIAL);
            if (att != a.attendance()) {
                Jdbc.update(c, "UPDATE RES_ROSTER_ASSIGNMENT SET attendance=?, row_version=row_version+1, updated_at=?"
                        + " WHERE tenant_id=? AND assignment_id=?", att, now, t, a.assignmentId());
                ctx.event(c, actor, ET_ASSIGNMENT, a.assignmentId(), "ATTENDANCE_" + att.name(), null, null,
                        Map.of("coveredMinutes", covered, "dutyMinutes", a.minutes()));
                updated++;
            }
        }
        return updated;
    }

    // ================================================================== queries

    /**
     * Visible assignment or NOT_FOUND. Whether a person sees their own assignments is a policy matter (a VI.4 grant
     * conditioned on {@code person_id = personId}), not a code bypass.
     */
    public RosterAssignment assignment(Connection c, SecurityIdentity actor, String assignmentId) {
        return assignmentsQuery(actor).require(c, "assignment_id", assignmentId, RosterService::assignmentRow, "assignment");
    }

    /** A person's visible duties across all periods overlapping [from, to), by start time. */
    public List<RosterAssignment> personalRoster(Connection c, SecurityIdentity actor, String personId, Instant from,
                                                 Instant to, int offset, int limit) {
        return assignmentsQuery(actor).where("a.person_id=? AND a.starts_at < ? AND a.ends_at > ?", personId, to, from)
                .orderBy("a.starts_at, a.assignment_id").list(c, RosterService::assignmentRow, offset, limit);
    }

    /** The visible roster of a visible period, ordered by day and start time. */
    public List<RosterAssignment> unitRoster(Connection c, SecurityIdentity actor, String periodId, int offset, int limit) {
        period(c, actor, periodId);
        return assignmentsQuery(actor).where("a.period_id=?", periodId)
                .orderBy("a.duty_day, a.starts_at, a.person_id, a.assignment_id")
                .list(c, RosterService::assignmentRow, offset, limit);
    }

    /** Capped count of the visible assignments of a period ("more than {@code cap}" when capped, A-16). */
    public VisibleQuery.CappedCount countAssignments(Connection c, SecurityIdentity actor, String periodId, int cap) {
        period(c, actor, periodId);
        return assignmentsQuery(actor).where("a.period_id=?", periodId).count(c, cap);
    }

    /** Visible duty or on-call assignments of the period that cover the instant. */
    public List<RosterAssignment> onDuty(Connection c, SecurityIdentity actor, String periodId, Instant at, int offset,
                                         int limit) {
        period(c, actor, periodId);
        return assignmentsQuery(actor)
                .where("a.period_id=? AND a.kind IN ('DUTY','ON_CALL') AND a.starts_at <= ? AND a.ends_at > ?",
                        periodId, at, at)
                .orderBy("a.starts_at, a.assignment_id").list(c, RosterService::assignmentRow, offset, limit);
    }

    private VisibleQuery assignmentsQuery(SecurityIdentity actor) {
        return vq(actor, ResourcesContext.RT_ASSIGNMENT, "RES_ROSTER_ASSIGNMENT", "a").select(A_COLS);
    }

    private VisibleQuery vq(SecurityIdentity actor, String resourceType, String table, String alias) {
        return VisibleQuery.on(ctx, actor, resourceType, ResourcesContext.P_READ, table, alias);
    }

    // ================================================================== internals

    private RosterConstraints resolveConstraints(String tenant, RosterPeriod p, RosterMember m) {
        RosterConstraints base = p.constraints();
        if (base.constraintsDecision() == null) {
            return base;
        }
        Map<String, Object> in = new LinkedHashMap<>(m.attributes());
        in.put("unitId", p.unitId());
        in.put("periodId", p.periodId());
        in.put("personId", m.personId());
        in.put("seatId", m.seatId());
        in.put("skills", RosterModel.csv(m.skills()));
        Optional<RuleEvaluator.Outcome> o = ctx.rules().evaluate(tenant, base.constraintsDecision(), in);
        return o.flatMap(RuleEvaluator.Outcome::first).map(base::override).orElse(base);
    }

    private List<Violation> checkFor(Connection c, String t, RosterPeriod p, RosterMember m, String assignmentId,
                                     LocalDate day, Instant start, Instant end, boolean night, String skill,
                                     Set<String> exclude) {
        Instant from = start.minus(8, ChronoUnit.DAYS);
        Instant to = end.plus(8, ChronoUnit.DAYS);
        List<Duty> existing = loadDuties(c, t, m.personId(), from, to, exclude);
        List<ResourcesPorts.LeaveInterval> leave = ctx.leave().leaveOf(c, t, m.personId(), from, to);
        return RosterConstraintChecker.check(m.personId(), new Duty(assignmentId, day, start, end, night), skill,
                m.skills(), existing, leave, resolveConstraints(t, p, m));
    }

    /** Checks whether {@code personId} may take over assignment {@code a}. */
    private List<Violation> checkAssignmentFor(Connection c, String t, RosterPeriod p, String personId, RosterAssignment a,
                                               Set<String> exclude) {
        ShiftTemplate s = requireShift(c, t, a.shiftId());
        RosterMember m = loadMembers(c, t, a.periodId()).stream().filter(x -> x.personId().equals(personId)).findFirst()
                .orElse(new RosterMember(a.periodId(), personId, null, Set.of(), null, 0, Map.of()));
        String skill = a.demandId() == null ? null : demandSkill(c, t, a.demandId());
        return checkFor(c, t, p, m, null, a.dutyDay(), a.startsAt(), a.endsAt(), s.night(), skill, exclude);
    }

    private void moveAssignment(Connection c, SecurityIdentity actor, RosterAssignment a, String toPerson, Instant now) {
        String t = actor.tenantId();
        unlinkSeat(c, actor, a);
        releaseClaims(c, t, a.assignmentId());
        String seat = loadMembers(c, t, a.periodId()).stream().filter(x -> x.personId().equals(toPerson))
                .map(RosterMember::seatId).findFirst().orElse(null);
        int n = Jdbc.update(c, "UPDATE RES_ROSTER_ASSIGNMENT SET person_id=?, seat_id=?, swapped_from=?, posting_ref=NULL,"
                        + " row_version=row_version+1, updated_at=? WHERE tenant_id=? AND assignment_id=? AND row_version=?",
                toPerson, seat, a.personId(), now, t, a.assignmentId(), a.version());
        if (n == 0) {
            throw ResourcesException.stale("assignment changed: " + a.assignmentId());
        }
        if (a.kind() == AssignmentKind.DUTY && !claim(c, t, toPerson, a.assignmentId(), a.startsAt(), a.endsAt())) {
            throw ResourcesException.constraint("DOUBLE_ROSTERED", "the person is already rostered in those hours");
        }
        RosterAssignment moved = requireAssignment(c, t, a.assignmentId());
        if (moved.state() == AssignmentState.PUBLISHED && moved.kind() == AssignmentKind.DUTY && moved.dutySeatId() != null) {
            linkSeat(c, actor, moved);
        }
    }

    private SwapRequest insertSwap(Connection c, SecurityIdentity actor, SwapKind kind, String assignmentId,
                                   String requesterId, String counterpartId, String counterpartAssignmentId, String reason) {
        String t = actor.tenantId();
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO RES_SWAP_REQUEST(tenant_id, swap_id, kind, assignment_id, requester_id, counterpart_id,"
                        + " counterpart_assignment_id, state, reason, row_version, created_at) VALUES (?,?,?,?,?,?,?,?,?,0,?)",
                t, id, kind, assignmentId, requesterId, counterpartId, counterpartAssignmentId, SwapState.REQUESTED, reason,
                ctx.now(c));
        ctx.event(c, actor, ET_SWAP, id, kind == SwapKind.SWAP ? "SWAP_REQUESTED" : "COVER_REQUESTED", reason, null,
                Map.of("assignmentId", assignmentId, "counterpartId", counterpartId));
        ctx.notify(c, t, counterpartId, "roster.swap.requested",
                Map.of("swapId", id, "kind", kind.name(), "requesterId", requesterId));
        return requireSwap(c, t, id);
    }

    private void notifySwap(Connection c, String t, SwapRequest s, String template) {
        Map<String, Object> d = Map.of("swapId", s.swapId(), "kind", s.kind().name());
        ctx.notify(c, t, s.requesterId(), template, d);
        ctx.notify(c, t, s.counterpartId(), template, d);
    }

    private String linkSeat(Connection c, SecurityIdentity actor, RosterAssignment a) {
        if (a.dutySeatId() == null) {
            return null;
        }
        String ref = ctx.seatLink().link(c, actor, a.dutySeatId(), a.personId(), a.startsAt(), a.endsAt(),
                "roster:" + a.periodId() + ":" + a.assignmentId());
        if (ref != null) {
            Jdbc.update(c, "UPDATE RES_ROSTER_ASSIGNMENT SET posting_ref=?, row_version=row_version+1 WHERE tenant_id=?"
                    + " AND assignment_id=?", ref, actor.tenantId(), a.assignmentId());
            ctx.event(c, actor, ET_ASSIGNMENT, a.assignmentId(), "DUTY_SEAT_LINKED", null, ref,
                    Map.of("dutySeatId", a.dutySeatId(), "personId", a.personId()));
        }
        return ref;
    }

    private void unlinkSeat(Connection c, SecurityIdentity actor, RosterAssignment a) {
        if (a.postingRef() == null) {
            return;
        }
        Instant now = ctx.now(c);
        ctx.seatLink().unlink(c, actor, a.postingRef(), now.isAfter(a.startsAt()) ? now : a.startsAt());
        Jdbc.update(c, "UPDATE RES_ROSTER_ASSIGNMENT SET posting_ref=NULL WHERE tenant_id=? AND assignment_id=?",
                actor.tenantId(), a.assignmentId());
        ctx.event(c, actor, ET_ASSIGNMENT, a.assignmentId(), "DUTY_SEAT_UNLINKED", null, a.postingRef(), null);
    }

    /** Inserts an assignment row and, for DUTY, its person claims; empty when the person is already rostered. */
    private Optional<String> insertAssignment(Connection c, String t, String periodId, String personId, String seatId,
                                              String shiftId, LocalDate day, Instant start, Instant end,
                                              AssignmentKind kind, String demandId, String dutySeatId, String locationId,
                                              AssignmentState state, String swappedFrom) {
        String id = Sql.newId();
        boolean ok = ctx.dialect().insertIfAbsent(c, "INSERT INTO RES_ROSTER_ASSIGNMENT(tenant_id, assignment_id, period_id,"
                        + " person_id, seat_id, shift_id, duty_day, starts_at, ends_at, kind, demand_id, duty_seat_id,"
                        + " location_id, state, swapped_from, row_version, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0," + ctx.dialect().now() + ")",
                t, id, periodId, personId, seatId, shiftId, day.toString(), start, end, kind, demandId, dutySeatId,
                locationId, state, swappedFrom);
        if (!ok) {
            return Optional.empty();
        }
        if (kind == AssignmentKind.DUTY && !claim(c, t, personId, id, start, end)) {
            Jdbc.update(c, "DELETE FROM RES_ROSTER_ASSIGNMENT WHERE tenant_id=? AND assignment_id=?", t, id);
            return Optional.empty();
        }
        return Optional.of(id);
    }

    /** Claims the person-hours of a duty; on any collision releases what it claimed and returns false. */
    private boolean claim(Connection c, String t, String personId, String assignmentId, Instant start, Instant end) {
        for (long k : hourKeys(start, end)) {
            boolean ok = ctx.dialect().insertIfAbsent(c, "INSERT INTO RES_PERSON_CLAIM(tenant_id, person_id, hour_key,"
                    + " assignment_id) VALUES (?,?,?,?)", t, personId, k, assignmentId);
            if (!ok) {
                releaseClaims(c, t, assignmentId);
                return false;
            }
        }
        return true;
    }

    static List<Long> hourKeys(Instant start, Instant end) {
        long s = Math.floorDiv(start.getEpochSecond() + 3599, 3600L);
        long e = Math.floorDiv(end.getEpochSecond(), 3600L);
        List<Long> keys = new ArrayList<>();
        if (e <= s) {
            keys.add(Math.floorDiv(start.getEpochSecond(), 3600L));
        } else {
            for (long k = s; k < e; k++) {
                keys.add(k);
            }
        }
        return keys;
    }

    private void releaseClaims(Connection c, String t, String assignmentId) {
        Jdbc.update(c, "DELETE FROM RES_PERSON_CLAIM WHERE tenant_id=? AND assignment_id=?", t, assignmentId);
    }

    private List<Duty> loadDuties(Connection c, String t, String personId, Instant from, Instant to, Set<String> exclude) {
        List<Duty> out = new ArrayList<>();
        for (Duty d : Jdbc.query(c, "SELECT a.assignment_id, a.duty_day, a.starts_at, a.ends_at, s.night_flag"
                        + " FROM RES_ROSTER_ASSIGNMENT a JOIN RES_SHIFT_TEMPLATE s ON s.tenant_id=a.tenant_id"
                        + " AND s.shift_id=a.shift_id WHERE a.tenant_id=? AND a.person_id=? AND a.kind='DUTY'"
                        + " AND a.starts_at < ? AND a.ends_at > ?",
                rs -> new Duty(rs.getString("assignment_id"), LocalDate.parse(rs.getString("duty_day")),
                        Jdbc.instant(rs, "starts_at"), Jdbc.instant(rs, "ends_at"), rs.getInt("night_flag") == 1),
                t, personId, to, from)) {
            if (!exclude.contains(d.assignmentId())) {
                out.add(d);
            }
        }
        return out;
    }

    private String demandSkill(Connection c, String t, String demandId) {
        return Jdbc.one(c, "SELECT skill FROM RES_COVERAGE_DEMAND WHERE tenant_id=? AND demand_id=?",
                rs -> rs.getString(1), t, demandId).orElse(null);
    }

    private List<RosterMember> loadMembers(Connection c, String t, String periodId) {
        return Jdbc.query(c, "SELECT * FROM RES_ROSTER_MEMBER WHERE tenant_id=? AND period_id=? ORDER BY person_id",
                RosterService::memberRow, t, periodId);
    }

    private List<CoverageDemand> loadDemands(Connection c, String t, String periodId) {
        return Jdbc.query(c, "SELECT * FROM RES_COVERAGE_DEMAND WHERE tenant_id=? AND period_id=? ORDER BY demand_id",
                RosterService::demandRow, t, periodId);
    }

    private List<RosterAssignment> unitAssignments(Connection c, String t, String periodId) {
        return Jdbc.query(c, "SELECT " + A_COLS + " FROM RES_ROSTER_ASSIGNMENT a WHERE a.tenant_id=? AND a.period_id=?"
                + " ORDER BY a.duty_day, a.starts_at, a.person_id", RosterService::assignmentRow, t, periodId);
    }

    private ShiftTemplate requireShift(Connection c, String t, String shiftId) {
        return Jdbc.one(c, "SELECT * FROM RES_SHIFT_TEMPLATE WHERE tenant_id=? AND shift_id=?", RosterService::shiftRow,
                t, shiftId).orElseThrow(() -> ResourcesException.notFound("shift", shiftId));
    }

    private RosterPeriod requirePeriod(Connection c, String t, String periodId) {
        return Jdbc.one(c, "SELECT * FROM RES_ROSTER_PERIOD WHERE tenant_id=? AND period_id=?", RosterService::periodRow,
                t, periodId).orElseThrow(() -> ResourcesException.notFound("roster period", periodId));
    }

    private RosterAssignment requireAssignment(Connection c, String t, String id) {
        return Jdbc.one(c, "SELECT " + A_COLS + " FROM RES_ROSTER_ASSIGNMENT a WHERE a.tenant_id=? AND a.assignment_id=?",
                RosterService::assignmentRow, t, id).orElseThrow(() -> ResourcesException.notFound("assignment", id));
    }

    private SwapRequest requireSwap(Connection c, String t, String id) {
        return Jdbc.one(c, "SELECT * FROM RES_SWAP_REQUEST WHERE tenant_id=? AND swap_id=?", RosterService::swapRow, t, id)
                .orElseThrow(() -> ResourcesException.notFound("swap request", id));
    }

    private void bumpPeriod(Connection c, String t, String periodId, long version, String set, Object... values) {
        Object[] params = new Object[values.length + 3];
        System.arraycopy(values, 0, params, 0, values.length);
        params[values.length] = t;
        params[values.length + 1] = periodId;
        params[values.length + 2] = version;
        int n = Jdbc.update(c, "UPDATE RES_ROSTER_PERIOD SET " + set + ", row_version=row_version+1 WHERE tenant_id=?"
                + " AND period_id=? AND row_version=?", params);
        if (n == 0) {
            throw ResourcesException.stale("roster period changed: " + periodId);
        }
    }

    private void bumpSwap(Connection c, String t, String swapId, long version, String set, Object... values) {
        Object[] params = new Object[values.length + 3];
        System.arraycopy(values, 0, params, 0, values.length);
        params[values.length] = t;
        params[values.length + 1] = swapId;
        params[values.length + 2] = version;
        int n = Jdbc.update(c, "UPDATE RES_SWAP_REQUEST SET " + set + ", row_version=row_version+1 WHERE tenant_id=?"
                + " AND swap_id=? AND row_version=?", params);
        if (n == 0) {
            throw ResourcesException.stale("swap request changed: " + swapId);
        }
    }

    private static void requireState(RosterPeriod p, PeriodState... allowed) {
        for (PeriodState s : allowed) {
            if (p.state() == s) {
                return;
            }
        }
        throw ResourcesException.conflict("INVALID_STATE", "roster period is " + p.state());
    }

    private static void requireOpen(RosterPeriod p) {
        if (p.state() == PeriodState.CLOSED) {
            throw ResourcesException.conflict("PERIOD_CLOSED", "roster period is closed");
        }
    }

    private static void requireSwapState(SwapRequest s, SwapState... allowed) {
        for (SwapState st : allowed) {
            if (s.state() == st) {
                return;
            }
        }
        throw ResourcesException.conflict("INVALID_STATE", "swap request is " + s.state());
    }

    private static ResourcesException violations(List<Violation> v) {
        StringBuilder sb = new StringBuilder();
        for (Violation x : v) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(x.code()).append(": ").append(x.message());
        }
        return ResourcesException.constraint(v.get(0).code(), sb.toString());
    }

    // ------------------------------------------------------------------ row mappers

    static ShiftTemplate shiftRow(ResultSet rs) throws SQLException {
        return new ShiftTemplate(rs.getString("tenant_id"), rs.getString("shift_id"), rs.getString("code"),
                rs.getString("name"), LocalTime.parse(rs.getString("from_time")), rs.getInt("duration_minutes"),
                rs.getInt("night_flag") == 1, RosterModel.skills(rs.getString("skills")), ZoneId.of(rs.getString("zone_id")),
                Sql.mapJson(Sql.text(rs, "attributes_json")));
    }

    static RosterMember memberRow(ResultSet rs) throws SQLException {
        return new RosterMember(rs.getString("period_id"), rs.getString("person_id"), rs.getString("seat_id"),
                RosterModel.skills(rs.getString("skills")), rs.getString("rotation_id"), rs.getInt("rotation_offset"),
                Sql.mapJson(Sql.text(rs, "attributes_json")));
    }

    static CoverageDemand demandRow(ResultSet rs) throws SQLException {
        String day = rs.getString("duty_day");
        return new CoverageDemand(rs.getString("demand_id"), rs.getString("period_id"), rs.getString("shift_id"),
                rs.getString("skill"), RosterModel.weekdays(rs.getString("weekdays")),
                day == null ? null : LocalDate.parse(day), rs.getInt("required_count"), rs.getString("duty_seat_id"),
                rs.getString("location_id"));
    }

    static RotationPattern rotationRow(ResultSet rs) throws SQLException {
        List<String> cycle = new ArrayList<>();
        for (Object o : Sql.listJson(Sql.text(rs, "cycle_json"))) {
            cycle.add(String.valueOf(o));
        }
        return new RotationPattern(rs.getString("tenant_id"), rs.getString("rotation_id"), rs.getString("name"), cycle);
    }

    static RosterPeriod periodRow(ResultSet rs) throws SQLException {
        return new RosterPeriod(rs.getString("tenant_id"), rs.getString("period_id"), rs.getString("unit_id"),
                rs.getString("name"), LocalDate.parse(rs.getString("first_day")), LocalDate.parse(rs.getString("last_day")),
                PeriodState.valueOf(rs.getString("state")), rs.getString("seed"),
                RosterConstraints.from(Sql.mapJson(Sql.text(rs, "constraints_json"))), rs.getString("process_ref"),
                Jdbc.instant(rs, "generated_at"), Jdbc.instant(rs, "published_at"), rs.getString("published_by"),
                rs.getLong("row_version"));
    }

    static RosterAssignment assignmentRow(ResultSet rs) throws SQLException {
        String att = rs.getString("attendance");
        return new RosterAssignment(rs.getString("tenant_id"), rs.getString("assignment_id"), rs.getString("period_id"),
                rs.getString("person_id"), rs.getString("seat_id"), rs.getString("shift_id"),
                LocalDate.parse(rs.getString("duty_day")), Jdbc.instant(rs, "starts_at"), Jdbc.instant(rs, "ends_at"),
                AssignmentKind.valueOf(rs.getString("kind")), rs.getString("demand_id"), rs.getString("duty_seat_id"),
                rs.getString("location_id"), AssignmentState.valueOf(rs.getString("state")), rs.getString("posting_ref"),
                att == null ? null : Attendance.valueOf(att), rs.getString("swapped_from"), rs.getLong("row_version"));
    }

    static SwapRequest swapRow(ResultSet rs) throws SQLException {
        return new SwapRequest(rs.getString("tenant_id"), rs.getString("swap_id"), SwapKind.valueOf(rs.getString("kind")),
                rs.getString("assignment_id"), rs.getString("requester_id"), rs.getString("counterpart_id"),
                rs.getString("counterpart_assignment_id"), SwapState.valueOf(rs.getString("state")), rs.getString("reason"),
                rs.getString("decided_by"), rs.getString("process_ref"), rs.getLong("row_version"),
                Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "decided_at"));
    }
}
