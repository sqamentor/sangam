// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.rest;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.roster.RosterGenerator;
import org.sangam.resources.roster.RosterModel;
import org.sangam.resources.support.VisibleQuery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rostering port for the REST workstream (no Spring here). Each method is one HTTP operation; the caller's
 * {@link SecurityIdentity} comes first and carries the tenant. Mutations of versioned objects take the expected
 * {@code version} (from the {@code If-Match} header or the body); a mismatch fails with 409 {@code STALE_VERSION}.
 * Errors are {@link org.sangam.resources.support.ResourcesException} (map {@code httpStatus()} / {@code reason()} to
 * problem+json).
 *
 * <p>Every list, count and get-by-id runs through {@link VisibleQuery}: tenant bound and the VI.4 visibility
 * predicate in the SQL, so hidden rows never appear and are reported as 404 on get-by-id. Lists take
 * {@code offset}/{@code limit} ({@code ?page=&size=}; limit capped at {@link VisibleQuery#MAX_PAGE_SIZE}).
 *
 * <p>Intended endpoints (base {@code /api/v1/resources/rosters}):
 * <pre>
 * GET    /shifts                                   listShifts
 * PUT    /shifts/{shiftId}                         saveShift          (POST /shifts for a new id)
 * DELETE /shifts/{shiftId}                         deleteShift
 * GET    /rotations                                listRotations
 * PUT    /rotations/{rotationId}                   saveRotation       (POST /rotations for a new id)
 * DELETE /rotations/{rotationId}                   deleteRotation
 * POST   /periods                                  createPeriod
 * GET    /periods?unitId=                          listPeriods
 * GET    /periods/{periodId}                       getPeriod
 * PUT    /periods/{periodId}/constraints           updateConstraints
 * POST   /periods/{periodId}/close                 closePeriod
 * GET    /periods/{periodId}/members               listMembers
 * PUT    /periods/{periodId}/members/{personId}    setMember
 * DELETE /periods/{periodId}/members/{personId}    removeMember
 * GET    /periods/{periodId}/demand                listDemand
 * POST   /periods/{periodId}/demand                addDemand
 * DELETE /periods/{periodId}/demand/{demandId}     removeDemand
 * POST   /periods/{periodId}/generate              generate
 * POST   /periods/{periodId}/randomise             randomise
 * POST   /periods/{periodId}/submit                submit
 * POST   /periods/{periodId}/decision              decidePublication
 * POST   /periods/{periodId}/publish               publish
 * POST   /periods/{periodId}/apply-leave           applyLeave
 * POST   /periods/{periodId}/reconcile-attendance  reconcileAttendance
 * GET    /periods/{periodId}/assignments           unitRoster
 * GET    /periods/{periodId}/assignments/count     countAssignments  (?cap=; header X-Total-Count-Capped)
 * GET    /periods/{periodId}/on-duty?at=           onDuty
 * POST   /periods/{periodId}/assignments           assign
 * POST   /periods/{periodId}/on-call               markOnCall
 * GET    /assignments/{assignmentId}               getAssignment
 * DELETE /assignments/{assignmentId}               unassign
 * GET    /people/{personId}/assignments?from=&to=  personalRoster    (/people/me/... for the caller)
 * POST   /swaps                                    requestSwap
 * GET    /periods/{periodId}/swaps                 listSwaps
 * GET    /swaps/{swapId}                           getSwap
 * POST   /swaps/{swapId}/accept                    acceptSwap
 * POST   /swaps/{swapId}/decision                  decideSwap
 * POST   /swaps/{swapId}/cancel                    cancelSwap
 * </pre>
 */
public interface RosterRestPort {

    // ------------------------------------------------------------------ request records

    record ShiftRequest(String shiftId, String code, String name, String fromTime, int durationMinutes, boolean night,
                        Set<String> skills, String zoneId, Map<String, Object> attributes) {
    }

    record RotationRequest(String rotationId, String name, List<String> cycle) {
    }

    record PeriodRequest(String unitId, String name, LocalDate firstDay, LocalDate lastDay,
                         Map<String, Object> constraints) {
    }

    record MemberRequest(String seatId, Set<String> skills, String rotationId, int rotationOffset,
                         Map<String, Object> attributes) {
    }

    record DemandRequest(String shiftId, String skill, Set<String> weekdays, LocalDate dutyDay, int requiredCount,
                         String dutySeatId, String locationId) {
    }

    record AssignRequest(String personId, String shiftId, LocalDate day, String kind, String demandId,
                         String dutySeatId, String locationId, boolean override, String overrideReason) {
    }

    record RandomiseRequest(String seed, List<String> people, List<String> slots) {
    }

    record DecisionRequest(Long version, boolean approved, String reason) {
    }

    record SwapCreateRequest(String assignmentId, String counterpartId, String counterpartAssignmentId, String reason) {
    }

    record CountResponse(int count) {
    }

    record ProcessResponse(String processRef) {
    }

    // ------------------------------------------------------------------ operations

    List<RosterModel.ShiftTemplate> listShifts(SecurityIdentity actor, int offset, int limit);

    RosterModel.ShiftTemplate saveShift(SecurityIdentity actor, ShiftRequest request);

    void deleteShift(SecurityIdentity actor, String shiftId);

    List<RosterModel.RotationPattern> listRotations(SecurityIdentity actor, int offset, int limit);

    RosterModel.RotationPattern saveRotation(SecurityIdentity actor, RotationRequest request);

    void deleteRotation(SecurityIdentity actor, String rotationId);

    RosterModel.RosterPeriod createPeriod(SecurityIdentity actor, PeriodRequest request);

    List<RosterModel.RosterPeriod> listPeriods(SecurityIdentity actor, String unitId, int offset, int limit);

    RosterModel.RosterPeriod getPeriod(SecurityIdentity actor, String periodId);

    RosterModel.RosterPeriod updateConstraints(SecurityIdentity actor, String periodId, long version,
                                               Map<String, Object> constraints);

    RosterModel.RosterPeriod closePeriod(SecurityIdentity actor, String periodId, long version);

    List<RosterModel.RosterMember> listMembers(SecurityIdentity actor, String periodId, int offset, int limit);

    RosterModel.RosterMember setMember(SecurityIdentity actor, String periodId, String personId, MemberRequest request);

    void removeMember(SecurityIdentity actor, String periodId, String personId);

    List<RosterModel.CoverageDemand> listDemand(SecurityIdentity actor, String periodId, int offset, int limit);

    RosterModel.CoverageDemand addDemand(SecurityIdentity actor, String periodId, DemandRequest request);

    void removeDemand(SecurityIdentity actor, String periodId, String demandId);

    RosterModel.GenerationReport generate(SecurityIdentity actor, String periodId, long version, String seed);

    RosterGenerator.RandomisationResult randomise(SecurityIdentity actor, String periodId, RandomiseRequest request);

    ProcessResponse submit(SecurityIdentity actor, String periodId, long version);

    RosterModel.RosterPeriod decidePublication(SecurityIdentity actor, String periodId, DecisionRequest request);

    RosterModel.RosterPeriod publish(SecurityIdentity actor, String periodId, long version);

    CountResponse applyLeave(SecurityIdentity actor, String periodId);

    CountResponse reconcileAttendance(SecurityIdentity actor, String periodId);

    List<RosterModel.RosterAssignment> unitRoster(SecurityIdentity actor, String periodId, int offset, int limit);

    VisibleQuery.CappedCount countAssignments(SecurityIdentity actor, String periodId, int cap);

    List<RosterModel.RosterAssignment> onDuty(SecurityIdentity actor, String periodId, Instant at, int offset,
                                              int limit);

    RosterModel.RosterAssignment assign(SecurityIdentity actor, String periodId, AssignRequest request);

    RosterModel.RosterAssignment markOnCall(SecurityIdentity actor, String periodId, String personId, String shiftId,
                                            LocalDate day);

    RosterModel.RosterAssignment getAssignment(SecurityIdentity actor, String assignmentId);

    void unassign(SecurityIdentity actor, String assignmentId, long version, String reason);

    List<RosterModel.RosterAssignment> personalRoster(SecurityIdentity actor, String personId, Instant from, Instant to,
                                                      int offset, int limit);

    RosterModel.SwapRequest requestSwap(SecurityIdentity actor, SwapCreateRequest request);

    List<RosterModel.SwapRequest> listSwaps(SecurityIdentity actor, String periodId, int offset, int limit);

    RosterModel.SwapRequest getSwap(SecurityIdentity actor, String swapId);

    RosterModel.SwapRequest acceptSwap(SecurityIdentity actor, String swapId, long version);

    RosterModel.SwapRequest decideSwap(SecurityIdentity actor, String swapId, DecisionRequest request);

    RosterModel.SwapRequest cancelSwap(SecurityIdentity actor, String swapId, long version);
}
