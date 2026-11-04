// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.rest;

import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.roster.RosterGenerator;
import org.sangam.resources.roster.RosterModel;
import org.sangam.resources.roster.RosterService;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.VisibleQuery;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link RosterRestPort} over {@link RosterService}: one database transaction per operation (endpoint list in the
 * port's javadoc). The REST workstream maps it onto Spring MVC controllers.
 */
public final class RosterRestAdapter implements RosterRestPort {

    private final Transactions tx;
    private final RosterService roster;

    public RosterRestAdapter(Transactions tx, RosterService roster) {
        this.tx = tx;
        this.roster = roster;
    }

    @Override
    public List<RosterModel.ShiftTemplate> listShifts(SecurityIdentity actor, int offset, int limit) {
        return tx.inTransaction(c -> roster.shifts(c, actor, offset, limit));
    }

    @Override
    public RosterModel.ShiftTemplate saveShift(SecurityIdentity actor, ShiftRequest r) {
        RosterModel.ShiftTemplate s;
        try {
            s = new RosterModel.ShiftTemplate(actor.tenantId(), r.shiftId(), r.code(), r.name(),
                    LocalTime.parse(r.fromTime()), r.durationMinutes(), r.night(), r.skills(),
                    r.zoneId() == null ? null : ZoneId.of(r.zoneId()), r.attributes());
        } catch (RuntimeException e) {
            throw ResourcesException.invalid("INVALID_SHIFT", "invalid shift: " + e.getMessage());
        }
        return tx.inTransaction(c -> roster.saveShift(c, actor, s));
    }

    @Override
    public void deleteShift(SecurityIdentity actor, String shiftId) {
        tx.inTransaction(c -> {
            roster.deleteShift(c, actor, shiftId);
            return null;
        });
    }

    @Override
    public List<RosterModel.RotationPattern> listRotations(SecurityIdentity actor, int offset, int limit) {
        return tx.inTransaction(c -> roster.rotations(c, actor, offset, limit));
    }

    @Override
    public RosterModel.RotationPattern saveRotation(SecurityIdentity actor, RotationRequest r) {
        RosterModel.RotationPattern p;
        try {
            p = new RosterModel.RotationPattern(actor.tenantId(), r.rotationId(), r.name(), r.cycle());
        } catch (RuntimeException e) {
            throw ResourcesException.invalid("INVALID_ROTATION", e.getMessage());
        }
        return tx.inTransaction(c -> roster.saveRotation(c, actor, p));
    }

    @Override
    public void deleteRotation(SecurityIdentity actor, String rotationId) {
        tx.inTransaction(c -> {
            roster.deleteRotation(c, actor, rotationId);
            return null;
        });
    }

    @Override
    public RosterModel.RosterPeriod createPeriod(SecurityIdentity actor, PeriodRequest r) {
        return tx.inTransaction(c -> roster.createPeriod(c, actor, r.unitId(), r.name(), r.firstDay(), r.lastDay(),
                r.constraints()));
    }

    @Override
    public List<RosterModel.RosterPeriod> listPeriods(SecurityIdentity actor, String unitId, int offset, int limit) {
        return tx.inTransaction(c -> roster.periods(c, actor, unitId, offset, limit));
    }

    @Override
    public RosterModel.RosterPeriod getPeriod(SecurityIdentity actor, String periodId) {
        return tx.inTransaction(c -> roster.period(c, actor, periodId));
    }

    @Override
    public RosterModel.RosterPeriod updateConstraints(SecurityIdentity actor, String periodId, long version,
                                                      Map<String, Object> constraints) {
        return tx.inTransaction(c -> roster.updateConstraints(c, actor, periodId, version, constraints));
    }

    @Override
    public RosterModel.RosterPeriod closePeriod(SecurityIdentity actor, String periodId, long version) {
        return tx.inTransaction(c -> roster.closePeriod(c, actor, periodId, version));
    }

    @Override
    public List<RosterModel.RosterMember> listMembers(SecurityIdentity actor, String periodId, int offset, int limit) {
        return tx.inTransaction(c -> roster.members(c, actor, periodId, offset, limit));
    }

    @Override
    public RosterModel.RosterMember setMember(SecurityIdentity actor, String periodId, String personId, MemberRequest r) {
        RosterModel.RosterMember m = new RosterModel.RosterMember(periodId, personId, r.seatId(), r.skills(),
                r.rotationId(), r.rotationOffset(), r.attributes());
        return tx.inTransaction(c -> roster.addMember(c, actor, m));
    }

    @Override
    public void removeMember(SecurityIdentity actor, String periodId, String personId) {
        tx.inTransaction(c -> {
            roster.removeMember(c, actor, periodId, personId);
            return null;
        });
    }

    @Override
    public List<RosterModel.CoverageDemand> listDemand(SecurityIdentity actor, String periodId, int offset, int limit) {
        return tx.inTransaction(c -> roster.demands(c, actor, periodId, offset, limit));
    }

    @Override
    public RosterModel.CoverageDemand addDemand(SecurityIdentity actor, String periodId, DemandRequest r) {
        Set<DayOfWeek> days = new LinkedHashSet<>();
        if (r.weekdays() != null) {
            try {
                for (String d : r.weekdays()) {
                    days.add(DayOfWeek.valueOf(d.toUpperCase(Locale.ROOT)));
                }
            } catch (IllegalArgumentException e) {
                throw ResourcesException.invalid("INVALID_WEEKDAY", "unknown weekday");
            }
        }
        RosterModel.CoverageDemand d = new RosterModel.CoverageDemand(null, periodId, r.shiftId(), r.skill(), days,
                r.dutyDay(), r.requiredCount(), r.dutySeatId(), r.locationId());
        return tx.inTransaction(c -> roster.addDemand(c, actor, d));
    }

    @Override
    public void removeDemand(SecurityIdentity actor, String periodId, String demandId) {
        tx.inTransaction(c -> {
            roster.removeDemand(c, actor, periodId, demandId);
            return null;
        });
    }

    @Override
    public RosterModel.GenerationReport generate(SecurityIdentity actor, String periodId, long version, String seed) {
        return tx.inTransaction(c -> roster.generate(c, actor, periodId, version, seed));
    }

    @Override
    public RosterGenerator.RandomisationResult randomise(SecurityIdentity actor, String periodId, RandomiseRequest r) {
        return tx.inTransaction(c -> roster.randomise(c, actor, periodId, r.seed(),
                r.people() == null ? List.of() : r.people(), r.slots() == null ? List.of() : r.slots()));
    }

    @Override
    public ProcessResponse submit(SecurityIdentity actor, String periodId, long version) {
        return new ProcessResponse(tx.inTransaction(c -> roster.submitForApproval(c, actor, periodId, version)));
    }

    @Override
    public RosterModel.RosterPeriod decidePublication(SecurityIdentity actor, String periodId, DecisionRequest r) {
        return tx.inTransaction(c -> roster.decidePublication(c, actor, periodId, r.version(), r.approved(), r.reason()));
    }

    @Override
    public RosterModel.RosterPeriod publish(SecurityIdentity actor, String periodId, long version) {
        return tx.inTransaction(c -> roster.publish(c, actor, periodId, version));
    }

    @Override
    public CountResponse applyLeave(SecurityIdentity actor, String periodId) {
        return new CountResponse(tx.inTransaction(c -> roster.applyLeave(c, actor, periodId)));
    }

    @Override
    public CountResponse reconcileAttendance(SecurityIdentity actor, String periodId) {
        return new CountResponse(tx.inTransaction(c -> roster.reconcileAttendance(c, actor, periodId)));
    }

    @Override
    public List<RosterModel.RosterAssignment> unitRoster(SecurityIdentity actor, String periodId, int offset, int limit) {
        return tx.inTransaction(c -> roster.unitRoster(c, actor, periodId, offset, limit));
    }

    @Override
    public VisibleQuery.CappedCount countAssignments(SecurityIdentity actor, String periodId, int cap) {
        return tx.inTransaction(c -> roster.countAssignments(c, actor, periodId, cap));
    }

    @Override
    public List<RosterModel.RosterAssignment> onDuty(SecurityIdentity actor, String periodId, Instant at, int offset,
                                                     int limit) {
        return tx.inTransaction(c -> roster.onDuty(c, actor, periodId, at == null ? roster.now(c) : at, offset, limit));
    }

    @Override
    public RosterModel.RosterAssignment assign(SecurityIdentity actor, String periodId, AssignRequest r) {
        RosterModel.AssignmentKind kind;
        try {
            kind = r.kind() == null ? RosterModel.AssignmentKind.DUTY
                    : RosterModel.AssignmentKind.valueOf(r.kind().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ResourcesException.invalid("INVALID_KIND", "unknown assignment kind: " + r.kind());
        }
        RosterService.AssignCommand cmd = new RosterService.AssignCommand(periodId, r.personId(), r.shiftId(), r.day(),
                kind, r.demandId(), r.dutySeatId(), r.locationId(), r.override(), r.overrideReason());
        return tx.inTransaction(c -> roster.assign(c, actor, cmd));
    }

    @Override
    public RosterModel.RosterAssignment markOnCall(SecurityIdentity actor, String periodId, String personId,
                                                   String shiftId, LocalDate day) {
        return tx.inTransaction(c -> roster.markOnCall(c, actor, periodId, personId, shiftId, day));
    }

    @Override
    public RosterModel.RosterAssignment getAssignment(SecurityIdentity actor, String assignmentId) {
        return tx.inTransaction(c -> roster.assignment(c, actor, assignmentId));
    }

    @Override
    public void unassign(SecurityIdentity actor, String assignmentId, long version, String reason) {
        tx.inTransaction(c -> {
            roster.unassign(c, actor, assignmentId, version, reason);
            return null;
        });
    }

    @Override
    public List<RosterModel.RosterAssignment> personalRoster(SecurityIdentity actor, String personId, Instant from,
                                                             Instant to, int offset, int limit) {
        String who = personId == null || "me".equals(personId) ? actor.personId() : personId;
        return tx.inTransaction(c -> roster.personalRoster(c, actor, who, from, to, offset, limit));
    }

    @Override
    public RosterModel.SwapRequest requestSwap(SecurityIdentity actor, SwapCreateRequest r) {
        return tx.inTransaction(c -> roster.requestSwap(c, actor, r.assignmentId(), r.counterpartId(),
                r.counterpartAssignmentId(), r.reason()));
    }

    @Override
    public List<RosterModel.SwapRequest> listSwaps(SecurityIdentity actor, String periodId, int offset, int limit) {
        return tx.inTransaction(c -> roster.swaps(c, actor, periodId, offset, limit));
    }

    @Override
    public RosterModel.SwapRequest getSwap(SecurityIdentity actor, String swapId) {
        return tx.inTransaction(c -> roster.swap(c, actor, swapId));
    }

    @Override
    public RosterModel.SwapRequest acceptSwap(SecurityIdentity actor, String swapId, long version) {
        return tx.inTransaction(c -> roster.acceptSwap(c, actor, swapId, version));
    }

    @Override
    public RosterModel.SwapRequest decideSwap(SecurityIdentity actor, String swapId, DecisionRequest r) {
        return tx.inTransaction(c -> roster.decideSwap(c, actor, swapId, r.version(), r.approved(), r.reason()));
    }

    @Override
    public RosterModel.SwapRequest cancelSwap(SecurityIdentity actor, String swapId, long version) {
        return tx.inTransaction(c -> roster.cancelSwap(c, actor, swapId, version));
    }
}
