// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.programme;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.ports.support.Paging;
import org.sangam.programme.config.IndicatorDefinition;
import org.sangam.programme.dashboard.DashboardFeed;
import org.sangam.programme.indicator.IndicatorRegistry;
import org.sangam.programme.milestone.MilestoneService;
import org.sangam.programme.period.PeriodService;
import org.sangam.programme.programme.ProgrammeService;
import org.sangam.programme.progress.ProgressService;
import org.sangam.programme.progress.VarianceService;
import org.sangam.programme.rest.ProgrammeRestPort;
import org.sangam.programme.returns.ReturnService;
import org.sangam.programme.rollup.RollupService;
import org.sangam.programme.target.TargetService;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Programme monitoring (N15). Routes follow the sangam-programme README, except the generic {@code /periods},
 * {@code /targets/{id}}, {@code /milestones/{id}} and {@code /returns} → {@code /programme-periods},
 * {@code /programme-targets/{id}}, {@code /programme-milestones/{id}} and {@code /programme-returns}
 * (those names belong to other modules). The VI.4 object checks run inside the module's REST adapter.
 */
@RestController
@RequestMapping("/api/v1")
public class ProgrammeController {

    public static final String READ = "programme.read";
    public static final String WRITE = "programme.write";
    public static final String APPROVE = "programme.approve";
    public static final String ADMIN = "programme.admin";

    private final ProgrammeRestPort port;
    private final PermissionPort permissions;

    public ProgrammeController(ProgrammeRestPort port, PermissionPort permissions) {
        this.port = port;
        this.permissions = permissions;
    }

    public record GeneratePeriodsRequest(String frequency, int fiscalStartYear, int dueOffsetDays) {
    }

    public record PeriodStateRequest(String state, String reason) {
    }

    public record UpdateProgrammeRequest(int expectedVersion, ProgrammeService.ProgrammeInput programme) {
    }

    public record StatusRequest(String status) {
    }

    public record SetTargetRequest(String indicatorKey, String periodId, String orgUnit, BigDecimal value) {
    }

    public record ReasonBody(String reason) {
    }

    public record ReviseTargetRequest(BigDecimal value, String reason) {
    }

    public record MilestoneRequest(String orgUnit, String name, int seq, LocalDate plannedStart, LocalDate plannedEnd,
                                   BigDecimal weight, List<String> dependsOn) {
    }

    public record CampaignRequest(String returnKey, String periodId, String rootNode, String programmeId) {
    }

    public record ReturnValuesRequest(int expectedVersion, Map<String, Object> values, Map<String, String> remarks) {
    }

    public record VersionBody(int expectedVersion) {
    }

    public record AchievementRequest(String indicatorKey, String periodId, String orgUnit, BigDecimal value) {
    }

    public record RollupRequest(String periodId, String rootNode) {
    }

    public record ImportRequest(String periodId, String orgUnit) {
    }

    public record ImportResult(int imported) {
    }

    public record VarianceRequest(String periodId, LocalDate asOf) {
    }

    public record RefreshResult(int snapshots) {
    }

    // ---------------------------------------------------------------- configuration and periods

    /** Publish the programme configuration (indicators, returns, kinds). */
    @PutMapping("/programme-config")
    public IndicatorRegistry.Published publishConfig(RequestContext ctx, @RequestBody Map<String, Object> body) {
        return port.publishConfig(api(ctx, ADMIN), body);
    }

    /** Published configuration versions. */
    @GetMapping("/programme-config/history")
    public ResponseEntity<List<Map<String, Object>>> configHistory(RequestContext ctx) {
        return Lists.ok(port.configHistory(api(ctx, READ)));
    }

    /** Generate the reporting periods of a fiscal year. */
    @PostMapping("/programme-periods/generate")
    public ResponseEntity<List<PeriodService.Period>> generatePeriods(RequestContext ctx, @RequestBody GeneratePeriodsRequest body) {
        return Lists.ok(port.generatePeriods(api(ctx, ADMIN), enumOf(IndicatorDefinition.Frequency.class, body.frequency(),
                "frequency"), body.fiscalStartYear(), body.dueOffsetDays()));
    }

    /** Reporting periods. */
    @GetMapping("/programme-periods")
    public ResponseEntity<List<PeriodService.Period>> listPeriods(RequestContext ctx,
            @RequestParam(required = false) String frequency, @RequestParam(required = false) String fiscalYear) {
        return Lists.ok(port.listPeriods(api(ctx, READ), enumOf(IndicatorDefinition.Frequency.class, frequency, "frequency"),
                fiscalYear));
    }

    /** Open, close or lock a reporting period. */
    @PostMapping("/programme-periods/{id}/state")
    public PeriodService.Period changePeriodState(RequestContext ctx, @PathVariable String id,
                                                  @RequestBody PeriodStateRequest body) {
        return port.changePeriodState(api(ctx, ADMIN), id, enumOf(PeriodService.State.class, body.state(), "state"),
                body.reason());
    }

    // ---------------------------------------------------------------- programmes

    /** Create a programme (or a component under a parent). */
    @PostMapping("/programmes")
    public ResponseEntity<ProgrammeService.Programme> createProgramme(RequestContext ctx,
                                                                      @RequestBody ProgrammeService.ProgrammeInput body) {
        ProgrammeService.Programme p = port.createProgramme(api(ctx, WRITE), body);
        return Lists.created("/api/v1/programmes/" + p.id(), p.id(), p);
    }

    /** Programmes visible to the caller. */
    @GetMapping("/programmes")
    public ResponseEntity<List<ProgrammeService.Programme>> listProgrammes(RequestContext ctx,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String parentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Lists.ok(page(port.listProgrammes(api(ctx, READ), kind, parentId,
                enumOf(ProgrammeService.Status.class, status, "status"), page(page, size))));
    }

    /** A programme. */
    @GetMapping("/programmes/{id}")
    public ProgrammeService.Programme getProgramme(RequestContext ctx, @PathVariable String id) {
        return port.getProgramme(api(ctx, READ), id);
    }

    /** Update a programme (optimistic version). */
    @PatchMapping("/programmes/{id}")
    public ProgrammeService.Programme updateProgramme(RequestContext ctx, @PathVariable String id,
                                                      @RequestBody UpdateProgrammeRequest body) {
        return port.updateProgramme(api(ctx, WRITE), id, body.expectedVersion(), body.programme());
    }

    /** Change the programme status. */
    @PostMapping("/programmes/{id}/status")
    public ProgrammeService.Programme changeStatus(RequestContext ctx, @PathVariable String id, @RequestBody StatusRequest body) {
        return port.changeProgrammeStatus(api(ctx, APPROVE), id, enumOf(ProgrammeService.Status.class, body.status(), "status"));
    }

    /** The programme tree (components). */
    @GetMapping("/programmes/{id}/tree")
    public ProgrammeService.Node tree(RequestContext ctx, @PathVariable String id) {
        return port.programmeTree(api(ctx, READ), id);
    }

    // ---------------------------------------------------------------- targets

    /** Set a target for an indicator, period and org unit. */
    @PutMapping("/programmes/{id}/targets")
    public TargetService.Target setTarget(RequestContext ctx, @PathVariable String id, @RequestBody SetTargetRequest body) {
        return port.setTarget(api(ctx, WRITE), id, body.indicatorKey(), body.periodId(), body.orgUnit(), body.value());
    }

    /** Targets of a programme. */
    @GetMapping("/programmes/{id}/targets")
    public ResponseEntity<List<TargetService.Target>> listTargets(RequestContext ctx, @PathVariable String id,
            @RequestParam(required = false) String periodId, @RequestParam(required = false) String orgUnit,
            @RequestParam(required = false, defaultValue = "false") boolean approvedOnly) {
        return Lists.ok(port.listTargets(api(ctx, READ), id, periodId, orgUnit, approvedOnly));
    }

    /** Propose a target for approval. */
    @PostMapping("/programme-targets/{id}/propose")
    public TargetService.Target proposeTarget(RequestContext ctx, @PathVariable String id) {
        return port.proposeTarget(api(ctx, WRITE), id);
    }

    /** Approve a target. */
    @PostMapping("/programme-targets/{id}/approve")
    public TargetService.Target approveTarget(RequestContext ctx, @PathVariable String id) {
        return port.approveTarget(api(ctx, APPROVE), id);
    }

    /** Reject a target with a reason. */
    @PostMapping("/programme-targets/{id}/reject")
    public TargetService.Target rejectTarget(RequestContext ctx, @PathVariable String id, @RequestBody ReasonBody body) {
        return port.rejectTarget(api(ctx, APPROVE), id, requireReason(body.reason()));
    }

    /** Revise an approved target with a reason. */
    @PostMapping("/programme-targets/{id}/revise")
    public TargetService.Target reviseTarget(RequestContext ctx, @PathVariable String id, @RequestBody ReviseTargetRequest body) {
        return port.reviseTarget(api(ctx, WRITE), id, body.value(), requireReason(body.reason()));
    }

    /** Allocate a target to child org units (explicit amounts or weights). */
    @PostMapping("/programme-targets/{id}/allocate")
    public ResponseEntity<List<TargetService.Target>> allocateTarget(RequestContext ctx, @PathVariable String id,
                                                                     @RequestBody ProgrammeRestPort.AllocationRequest body) {
        return Lists.ok(port.allocateTarget(api(ctx, WRITE), id, body));
    }

    // ---------------------------------------------------------------- milestones

    /** Plan a milestone. */
    @PostMapping("/programmes/{id}/milestones")
    public MilestoneService.Milestone planMilestone(RequestContext ctx, @PathVariable String id,
                                                    @RequestBody MilestoneRequest body) {
        return port.planMilestone(api(ctx, WRITE), id, body.orgUnit(), body.name(), body.seq(), body.plannedStart(),
                body.plannedEnd(), body.weight(), body.dependsOn());
    }

    /** Record milestone progress (optimistic version; the body's milestoneId must equal the path id). */
    @PatchMapping("/programme-milestones/{id}")
    public MilestoneService.Milestone updateMilestone(RequestContext ctx, @PathVariable String id,
                                                      @RequestBody MilestoneService.Progress body) {
        if (body.milestoneId() == null || !body.milestoneId().equals(id)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "milestoneId in the body must equal the path id");
        }
        return port.updateMilestone(api(ctx, WRITE), body);
    }

    /** Milestones of a programme. */
    @GetMapping("/programmes/{id}/milestones")
    public ResponseEntity<List<MilestoneService.Milestone>> listMilestones(RequestContext ctx, @PathVariable String id,
                                                                          @RequestParam(required = false) String orgUnit) {
        return Lists.ok(port.listMilestones(api(ctx, READ), id, orgUnit));
    }

    // ---------------------------------------------------------------- periodic returns

    /** Start a return campaign: one return (and process) per office under the root node. */
    @PostMapping("/programme-returns/campaigns")
    public ReturnService.CampaignResult startCampaign(RequestContext ctx, @RequestBody CampaignRequest body) {
        return port.startCampaign(api(ctx, WRITE), body.returnKey(), body.periodId(), body.rootNode(), body.programmeId());
    }

    /** Returns visible to the caller. */
    @GetMapping("/programme-returns")
    public ResponseEntity<List<ReturnService.ReturnRow>> listReturns(RequestContext ctx,
            @RequestParam(required = false) String returnKey, @RequestParam(required = false) String periodId,
            @RequestParam(required = false) String orgUnit, @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Lists.ok(page(port.listReturns(api(ctx, READ), returnKey, periodId, orgUnit,
                enumOf(ReturnService.State.class, state, "state"), page(page, size))));
    }

    /** Offices that have not submitted a return. */
    @GetMapping("/programme-returns/defaulters")
    public ResponseEntity<List<ReturnService.ReturnRow>> defaulters(RequestContext ctx, @RequestParam String returnKey,
            @RequestParam String periodId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false, defaultValue = "500") int limit) {
        return Lists.ok(port.defaulters(api(ctx, READ), returnKey, periodId, asOf == null ? LocalDate.now() : asOf,
                Math.max(1, Math.min(limit, 5000))));
    }

    /** A return with values, findings and events. */
    @GetMapping("/programme-returns/{id}")
    public ProgrammeRestPort.ReturnDetail getReturn(RequestContext ctx, @PathVariable String id) {
        return port.getReturn(api(ctx, READ), id);
    }

    /** Save the return draft values (optimistic version). */
    @PutMapping("/programme-returns/{id}/values")
    public ReturnService.ReturnRow saveReturnDraft(RequestContext ctx, @PathVariable String id,
                                                   @RequestBody ReturnValuesRequest body) {
        return port.saveReturnDraft(api(ctx, WRITE), id, body.expectedVersion(), body.values(), body.remarks());
    }

    /** Submit the return. */
    @PostMapping("/programme-returns/{id}/submit")
    public ReturnService.Outcome submitReturn(RequestContext ctx, @PathVariable String id, @RequestBody VersionBody body) {
        return port.submitReturn(api(ctx, WRITE), id, body.expectedVersion());
    }

    /** Validate the return (rules and DMN). */
    @PostMapping("/programme-returns/{id}/validate")
    public ReturnService.Outcome validateReturn(RequestContext ctx, @PathVariable String id) {
        return port.validateReturn(api(ctx, APPROVE), id);
    }

    /** Accept the return. */
    @PostMapping("/programme-returns/{id}/accept")
    public ReturnService.ReturnRow acceptReturn(RequestContext ctx, @PathVariable String id) {
        return port.acceptReturn(api(ctx, APPROVE), id);
    }

    /** Send the return back with a reason. */
    @PostMapping("/programme-returns/{id}/send-back")
    public ReturnService.ReturnRow sendBackReturn(RequestContext ctx, @PathVariable String id, @RequestBody ReasonBody body) {
        return port.sendBackReturn(api(ctx, APPROVE), id, requireReason(body.reason()));
    }

    /** Freeze the return. */
    @PostMapping("/programme-returns/{id}/freeze")
    public ReturnService.ReturnRow freezeReturn(RequestContext ctx, @PathVariable String id) {
        return port.freezeReturn(api(ctx, APPROVE), id);
    }

    /** Reopen a frozen return with a reason. */
    @PostMapping("/programme-returns/{id}/reopen")
    public ReturnService.ReturnRow reopenReturn(RequestContext ctx, @PathVariable String id, @RequestBody ReasonBody body) {
        return port.reopenReturn(api(ctx, ADMIN), id, requireReason(body.reason()));
    }

    /** Acknowledge a validation finding. */
    @PostMapping("/programme-returns/{id}/findings/{seq}/ack")
    public ResponseEntity<Void> acknowledgeFinding(RequestContext ctx, @PathVariable String id, @PathVariable int seq) {
        port.acknowledgeFinding(api(ctx, APPROVE), id, seq);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- achievements, roll-up, progress

    /** Record a manual achievement. */
    @PostMapping("/programmes/{id}/achievements")
    public RollupService.Achievement recordAchievement(RequestContext ctx, @PathVariable String id,
                                                       @RequestBody AchievementRequest body) {
        return port.recordManualAchievement(api(ctx, WRITE), id, body.indicatorKey(), body.periodId(), body.orgUnit(),
                body.value());
    }

    /** Roll achievements up the hierarchy. */
    @PostMapping("/programmes/{id}/rollup")
    public RollupService.RollupResult rollup(RequestContext ctx, @PathVariable String id, @RequestBody RollupRequest body) {
        return port.rollup(api(ctx, WRITE), id, body.periodId(), body.rootNode());
    }

    /** Achievements of a programme. */
    @GetMapping("/programmes/{id}/achievements")
    public ResponseEntity<List<RollupService.Achievement>> achievements(RequestContext ctx, @PathVariable String id,
            @RequestParam(required = false) String periodId, @RequestParam(required = false) String orgUnit) {
        return Lists.ok(port.achievements(api(ctx, READ), id, periodId, orgUnit));
    }

    /** Achievements of the child org units. */
    @GetMapping("/programmes/{id}/achievements/children")
    public ResponseEntity<List<RollupService.Achievement>> achievementChildren(RequestContext ctx, @PathVariable String id,
            @RequestParam String indicatorKey, @RequestParam String periodId, @RequestParam String orgUnit) {
        return Lists.ok(port.achievementChildren(api(ctx, READ), id, indicatorKey, periodId, orgUnit));
    }

    /** Achievement series over a fiscal year. */
    @GetMapping("/programmes/{id}/achievements/series")
    public ResponseEntity<List<RollupService.Achievement>> series(RequestContext ctx, @PathVariable String id,
            @RequestParam String indicatorKey, @RequestParam String orgUnit, @RequestParam String fiscalYear) {
        return Lists.ok(port.series(api(ctx, READ), id, indicatorKey, orgUnit, fiscalYear));
    }

    /** Record financial progress for a head. */
    @PutMapping("/programmes/{id}/financial")
    public ProgressService.Financial recordFinancial(RequestContext ctx, @PathVariable String id,
                                                     @RequestBody ProgrammeRestPort.FinancialRequest body) {
        if (body.programmeId() != null && !body.programmeId().equals(id)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "programmeId in the body must equal the path id");
        }
        var request = new ProgrammeRestPort.FinancialRequest(id, body.periodId(), body.orgUnit(), body.head(),
                body.allocated(), body.released(), body.committed(), body.expended(), body.currency());
        return port.recordFinancial(api(ctx, WRITE), request);
    }

    /** Import financial actuals from the ledger. */
    @PostMapping("/programmes/{id}/financial/import")
    public ImportResult importFinancial(RequestContext ctx, @PathVariable String id, @RequestBody ImportRequest body) {
        return new ImportResult(port.importFinancial(api(ctx, WRITE), id, body.periodId(), body.orgUnit()));
    }

    /** Physical and financial progress. */
    @GetMapping("/programmes/{id}/progress")
    public ProgressService.Progress progress(RequestContext ctx, @PathVariable String id, @RequestParam String periodId,
                                             @RequestParam String orgUnit) {
        return port.progress(api(ctx, READ), id, periodId, orgUnit);
    }

    /** Progress of the child org units. */
    @GetMapping("/programmes/{id}/progress/children")
    public ResponseEntity<List<ProgressService.Progress>> childProgress(RequestContext ctx, @PathVariable String id,
            @RequestParam String periodId, @RequestParam String orgUnit) {
        return Lists.ok(port.childProgress(api(ctx, READ), id, periodId, orgUnit));
    }

    // ---------------------------------------------------------------- alerts and dashboards

    /** Run the variance check (behind-target and delay alerts). */
    @PostMapping("/programmes/{id}/variance-check")
    public ResponseEntity<List<VarianceService.Alert>> checkVariance(RequestContext ctx, @PathVariable String id,
                                                                     @RequestBody VarianceRequest body) {
        return Lists.ok(port.checkVariance(api(ctx, WRITE), id, body.periodId(), body.asOf() == null ? LocalDate.now()
                : body.asOf()));
    }

    /** Open variance alerts. */
    @GetMapping("/programme-alerts")
    public ResponseEntity<List<VarianceService.Alert>> openAlerts(RequestContext ctx,
            @RequestParam(required = false) String programmeId, @RequestParam(required = false) String kind,
            @RequestParam(required = false, defaultValue = "200") int limit) {
        return Lists.ok(port.openAlerts(api(ctx, READ), programmeId, enumOf(VarianceService.Kind.class, kind, "kind"),
                Math.max(1, Math.min(limit, 1000))));
    }

    /** Acknowledge an alert. */
    @PostMapping("/programme-alerts/{id}/ack")
    public VarianceService.Alert acknowledgeAlert(RequestContext ctx, @PathVariable String id) {
        return port.acknowledgeAlert(api(ctx, WRITE), id);
    }

    /** Refresh the dashboard snapshots (C14 feed). */
    @PostMapping("/programmes/{id}/dashboard/refresh")
    public RefreshResult refreshDashboard(RequestContext ctx, @PathVariable String id, @RequestBody RollupRequest body) {
        return new RefreshResult(port.refreshDashboard(api(ctx, WRITE), id, body.periodId(), body.rootNode()));
    }

    /** The dashboard snapshot of an org unit. */
    @GetMapping("/programmes/{id}/dashboard")
    public DashboardFeed.Snapshot dashboard(RequestContext ctx, @PathVariable String id, @RequestParam String periodId,
                                            @RequestParam String orgUnit) {
        return port.dashboard(api(ctx, READ), id, periodId, orgUnit)
                .orElseThrow(() -> ApiProblem.notFound("dashboard snapshot", id + "/" + periodId + "/" + orgUnit));
    }

    /** Dashboard snapshots of the child org units. */
    @GetMapping("/programmes/{id}/dashboard/children")
    public ResponseEntity<List<DashboardFeed.Snapshot>> dashboardChildren(RequestContext ctx, @PathVariable String id,
            @RequestParam String periodId, @RequestParam String orgUnit) {
        return Lists.ok(port.dashboardChildren(api(ctx, READ), id, periodId, orgUnit));
    }

    // ---------------------------------------------------------------- support

    private ProgrammeRestPort.ApiContext api(RequestContext ctx, String permission) {
        permissions.require(ctx, permission);
        return new ProgrammeRestPort.ApiContext(identity(ctx));
    }

    /** The sangam-security resolved identity when present, else one built from the request context (tenant from ctx). */
    static SecurityIdentity identity(RequestContext ctx) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getAttribute(SecurityIdentity.class.getName(), RequestAttributes.SCOPE_REQUEST)
                instanceof SecurityIdentity id && ctx.tenantId().equals(id.tenantId())) {
            return id;
        }
        return new SecurityIdentity(ctx.tenantId(), ctx.principalId(), ctx.principalId(), ctx.groups(), Set.of(),
                ctx.seatId() == null ? Set.of() : Set.of(ctx.seatId()),
                ctx.actingForSeatId() == null ? Set.of() : Set.of(ctx.actingForSeatId()), ctx.authMethod(),
                ctx.clientIp(), null, false, Map.of());
    }

    private static Paging.PageRequest page(Integer page, Integer size) {
        Page.PageRequest p = Page.PageRequest.of(page, size);
        return new Paging.PageRequest(p.page(), Math.min(p.size(), Paging.MAX_PAGE));
    }

    private static <T> Page<T> page(Paging.PageResult<T> r) {
        return Page.of(r.items(), r.total(), r.capped());
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String param) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", param + " must be one of "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "This action requires a reason (R42)");
        }
        return reason;
    }
}
