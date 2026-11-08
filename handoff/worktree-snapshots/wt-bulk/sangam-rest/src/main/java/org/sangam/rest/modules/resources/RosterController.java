// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.resources;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.roster.RosterGenerator;
import org.sangam.resources.roster.RosterModel;
import org.sangam.resources.rest.RosterRestPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rostering, shifts and duty deployment (sangam-resources N08) at {@code /api/v1/rosters}: shift templates,
 * rotations, roster periods with members and coverage demand, generation and randomisation, submission for approval,
 * publication, leave and attendance reconciliation, assignments, personal rosters and swaps.
 */
@RestController
@RequestMapping("/api/v1/rosters")
public class RosterController {

    private static final int COUNT_CAP = 1000;

    private final RosterRestPort port;
    private final ResourcesIdentity identities;
    private final PermissionPort permissions;

    public RosterController(RosterRestPort port, ResourcesIdentity identities, PermissionPort permissions) {
        this.port = port;
        this.identities = identities;
        this.permissions = permissions;
    }

    public record OnCallRequest(String personId, String shiftId, LocalDate day) {
    }

    // ---------------------------------------------------------------- shifts and rotations

    @GetMapping("/shifts")
    public ResponseEntity<List<RosterModel.ShiftTemplate>> listShifts(RequestContext ctx,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page.PageRequest pr = Lists.page(page, size);
        return Lists.ok(port.listShifts(read(ctx), pr.firstResult(), pr.size()));
    }

    @PutMapping("/shifts")
    public ResponseEntity<RosterModel.ShiftTemplate> saveShift(RequestContext ctx,
                                                               @RequestBody RosterRestPort.ShiftRequest body) {
        RosterModel.ShiftTemplate s = port.saveShift(admin(ctx), body);
        return Lists.okRef(body.shiftId(), s);
    }

    @DeleteMapping("/shifts/{shiftId}")
    public ResponseEntity<Void> deleteShift(RequestContext ctx, @PathVariable String shiftId) {
        port.deleteShift(admin(ctx), shiftId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rotations")
    public ResponseEntity<List<RosterModel.RotationPattern>> listRotations(RequestContext ctx,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page.PageRequest pr = Lists.page(page, size);
        return Lists.ok(port.listRotations(read(ctx), pr.firstResult(), pr.size()));
    }

    @PutMapping("/rotations")
    public ResponseEntity<RosterModel.RotationPattern> saveRotation(RequestContext ctx,
                                                                    @RequestBody RosterRestPort.RotationRequest body) {
        return Lists.okRef(body.rotationId(), port.saveRotation(admin(ctx), body));
    }

    @DeleteMapping("/rotations/{rotationId}")
    public ResponseEntity<Void> deleteRotation(RequestContext ctx, @PathVariable String rotationId) {
        port.deleteRotation(admin(ctx), rotationId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- periods, members, demand

    @PostMapping("/periods")
    public ResponseEntity<RosterModel.RosterPeriod> createPeriod(RequestContext ctx,
                                                                 @RequestBody RosterRestPort.PeriodRequest body) {
        RosterModel.RosterPeriod p = port.createPeriod(admin(ctx), body);
        return Lists.created("/api/v1/rosters/periods/" + p.periodId(), p.periodId(), p);
    }

    @GetMapping("/periods")
    public ResponseEntity<List<RosterModel.RosterPeriod>> listPeriods(RequestContext ctx,
            @RequestParam(required = false) String unitId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page.PageRequest pr = Lists.page(page, size);
        return Lists.ok(port.listPeriods(read(ctx), unitId, pr.firstResult(), pr.size()));
    }

    @GetMapping("/periods/{periodId}")
    public RosterModel.RosterPeriod getPeriod(RequestContext ctx, @PathVariable String periodId) {
        return port.getPeriod(read(ctx), periodId);
    }

    @PutMapping("/periods/{periodId}/constraints")
    public ResponseEntity<RosterModel.RosterPeriod> updateConstraints(RequestContext ctx, @PathVariable String periodId,
            @RequestParam long version, @RequestBody Map<String, Object> constraints) {
        return Lists.okRef(periodId, port.updateConstraints(admin(ctx), periodId, version, constraints));
    }

    @PostMapping("/periods/{periodId}/close")
    public ResponseEntity<RosterModel.RosterPeriod> closePeriod(RequestContext ctx, @PathVariable String periodId,
                                                                @RequestParam long version) {
        return Lists.okRef(periodId, port.closePeriod(admin(ctx), periodId, version));
    }

    @GetMapping("/periods/{periodId}/members")
    public ResponseEntity<List<RosterModel.RosterMember>> listMembers(RequestContext ctx, @PathVariable String periodId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page.PageRequest pr = Lists.page(page, size);
        return Lists.ok(port.listMembers(read(ctx), periodId, pr.firstResult(), pr.size()));
    }

    @PutMapping("/periods/{periodId}/members/{personId}")
    public ResponseEntity<RosterModel.RosterMember> setMember(RequestContext ctx, @PathVariable String periodId,
            @PathVariable String personId, @RequestBody RosterRestPort.MemberRequest body) {
        return Lists.okRef(personId, port.setMember(admin(ctx), periodId, personId, body));
    }

    @DeleteMapping("/periods/{periodId}/members/{personId}")
    public ResponseEntity<Void> removeMember(RequestContext ctx, @PathVariable String periodId,
                                             @PathVariable String personId) {
        port.removeMember(admin(ctx), periodId, personId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/periods/{periodId}/demand")
    public ResponseEntity<List<RosterModel.CoverageDemand>> listDemand(RequestContext ctx, @PathVariable String periodId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page.PageRequest pr = Lists.page(page, size);
        return Lists.ok(port.listDemand(read(ctx), periodId, pr.firstResult(), pr.size()));
    }

    @PostMapping("/periods/{periodId}/demand")
    public ResponseEntity<RosterModel.CoverageDemand> addDemand(RequestContext ctx, @PathVariable String periodId,
                                                                @RequestBody RosterRestPort.DemandRequest body) {
        RosterModel.CoverageDemand d = port.addDemand(admin(ctx), periodId, body);
        return ResponseEntity.status(201).header(Lists.RESOURCE_REF, d.demandId()).body(d);
    }

    @DeleteMapping("/periods/{periodId}/demand/{demandId}")
    public ResponseEntity<Void> removeDemand(RequestContext ctx, @PathVariable String periodId,
                                             @PathVariable String demandId) {
        port.removeDemand(admin(ctx), periodId, demandId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- generation, approval, publication

    @PostMapping("/periods/{periodId}/generate")
    public ResponseEntity<RosterModel.GenerationReport> generate(RequestContext ctx, @PathVariable String periodId,
            @RequestParam long version, @RequestParam(required = false) String seed) {
        return Lists.okRef(periodId, port.generate(admin(ctx), periodId, version, seed));
    }

    @PostMapping("/periods/{periodId}/randomise")
    public ResponseEntity<RosterGenerator.RandomisationResult> randomise(RequestContext ctx,
            @PathVariable String periodId, @RequestBody RosterRestPort.RandomiseRequest body) {
        return Lists.okRef(periodId, port.randomise(admin(ctx), periodId, body));
    }

    @PostMapping("/periods/{periodId}/submit")
    public ResponseEntity<RosterRestPort.ProcessResponse> submit(RequestContext ctx, @PathVariable String periodId,
                                                                 @RequestParam long version) {
        return Lists.okRef(periodId, port.submit(admin(ctx), periodId, version));
    }

    @PostMapping("/periods/{periodId}/publication-decision")
    public ResponseEntity<RosterModel.RosterPeriod> decidePublication(RequestContext ctx, @PathVariable String periodId,
                                                                      @RequestBody RosterRestPort.DecisionRequest body) {
        return Lists.okRef(periodId, port.decidePublication(admin(ctx), periodId, body));
    }

    @PostMapping("/periods/{periodId}/publish")
    public ResponseEntity<RosterModel.RosterPeriod> publish(RequestContext ctx, @PathVariable String periodId,
                                                            @RequestParam long version) {
        return Lists.okRef(periodId, port.publish(admin(ctx), periodId, version));
    }

    @PostMapping("/periods/{periodId}/apply-leave")
    public RosterRestPort.CountResponse applyLeave(RequestContext ctx, @PathVariable String periodId) {
        return port.applyLeave(admin(ctx), periodId);
    }

    @PostMapping("/periods/{periodId}/reconcile-attendance")
    public RosterRestPort.CountResponse reconcileAttendance(RequestContext ctx, @PathVariable String periodId) {
        return port.reconcileAttendance(admin(ctx), periodId);
    }

    // ---------------------------------------------------------------- assignments and duties

    @GetMapping("/periods/{periodId}/assignments")
    public ResponseEntity<List<RosterModel.RosterAssignment>> unitRoster(RequestContext ctx,
            @PathVariable String periodId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        SecurityIdentity id = read(ctx);
        Page.PageRequest pr = Lists.page(page, size);
        return ResourcesController.listed(port.unitRoster(id, periodId, pr.firstResult(), pr.size()),
                port.countAssignments(id, periodId, COUNT_CAP));
    }

    @GetMapping("/periods/{periodId}/on-duty")
    public ResponseEntity<List<RosterModel.RosterAssignment>> onDuty(RequestContext ctx, @PathVariable String periodId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page.PageRequest pr = Lists.page(page, size);
        return Lists.ok(port.onDuty(read(ctx), periodId, at == null ? Instant.now() : at, pr.firstResult(), pr.size()));
    }

    @PostMapping("/periods/{periodId}/assignments")
    public ResponseEntity<RosterModel.RosterAssignment> assign(RequestContext ctx, @PathVariable String periodId,
                                                               @RequestBody RosterRestPort.AssignRequest body) {
        RosterModel.RosterAssignment a = port.assign(admin(ctx), periodId, body);
        return Lists.created("/api/v1/rosters/assignments/" + a.assignmentId(), a.assignmentId(), a);
    }

    @PostMapping("/periods/{periodId}/on-call")
    public ResponseEntity<RosterModel.RosterAssignment> markOnCall(RequestContext ctx, @PathVariable String periodId,
                                                                   @RequestBody OnCallRequest body) {
        RosterModel.RosterAssignment a = port.markOnCall(admin(ctx), periodId, body.personId(), body.shiftId(), body.day());
        return Lists.created("/api/v1/rosters/assignments/" + a.assignmentId(), a.assignmentId(), a);
    }

    @GetMapping("/assignments/{assignmentId}")
    public RosterModel.RosterAssignment getAssignment(RequestContext ctx, @PathVariable String assignmentId) {
        return port.getAssignment(read(ctx), assignmentId);
    }

    @DeleteMapping("/assignments/{assignmentId}")
    public ResponseEntity<Void> unassign(RequestContext ctx, @PathVariable String assignmentId,
            @RequestParam long version, @RequestParam(required = false) String reason) {
        port.unassign(admin(ctx), assignmentId, version, reason);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/people/{personId}/duties")
    public ResponseEntity<List<RosterModel.RosterAssignment>> personalRoster(RequestContext ctx,
            @PathVariable String personId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page.PageRequest pr = Lists.page(page, size);
        return Lists.ok(port.personalRoster(read(ctx), personId, from, to, pr.firstResult(), pr.size()));
    }

    // ---------------------------------------------------------------- swaps

    @PostMapping("/swaps")
    public ResponseEntity<RosterModel.SwapRequest> requestSwap(RequestContext ctx,
                                                               @RequestBody RosterRestPort.SwapCreateRequest body) {
        RosterModel.SwapRequest s = port.requestSwap(write(ctx), body);
        return Lists.created("/api/v1/rosters/swaps/" + s.swapId(), s.swapId(), s);
    }

    @GetMapping("/swaps")
    public ResponseEntity<List<RosterModel.SwapRequest>> listSwaps(RequestContext ctx,
            @RequestParam(required = false) String periodId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Page.PageRequest pr = Lists.page(page, size);
        return Lists.ok(port.listSwaps(read(ctx), periodId, pr.firstResult(), pr.size()));
    }

    @GetMapping("/swaps/{swapId}")
    public RosterModel.SwapRequest getSwap(RequestContext ctx, @PathVariable String swapId) {
        return port.getSwap(read(ctx), swapId);
    }

    @PostMapping("/swaps/{swapId}/accept")
    public ResponseEntity<RosterModel.SwapRequest> acceptSwap(RequestContext ctx, @PathVariable String swapId,
                                                              @RequestParam long version) {
        return Lists.okRef(swapId, port.acceptSwap(write(ctx), swapId, version));
    }

    @PostMapping("/swaps/{swapId}/decision")
    public ResponseEntity<RosterModel.SwapRequest> decideSwap(RequestContext ctx, @PathVariable String swapId,
                                                              @RequestBody RosterRestPort.DecisionRequest body) {
        return Lists.okRef(swapId, port.decideSwap(admin(ctx), swapId, body));
    }

    @PostMapping("/swaps/{swapId}/cancel")
    public ResponseEntity<RosterModel.SwapRequest> cancelSwap(RequestContext ctx, @PathVariable String swapId,
                                                              @RequestParam long version) {
        return Lists.okRef(swapId, port.cancelSwap(write(ctx), swapId, version));
    }

    private SecurityIdentity read(RequestContext ctx) {
        permissions.require(ctx, ResourcesPermissions.READ);
        return identities.of(ctx);
    }

    private SecurityIdentity write(RequestContext ctx) {
        permissions.require(ctx, ResourcesPermissions.WRITE);
        return identities.of(ctx);
    }

    private SecurityIdentity admin(RequestContext ctx) {
        permissions.require(ctx, ResourcesPermissions.ADMIN);
        return identities.of(ctx);
    }
}
