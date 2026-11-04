// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.rest;

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
import org.sangam.programme.returns.ReturnService;
import org.sangam.programme.rollup.RollupService;
import org.sangam.programme.target.TargetService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST port of the programme module (N15). WS-REST mounts it under {@code /api/v1} (route table in the module
 * README) with the ARCHITECTURE §4 conventions: JSON, ISO-8601, {@code Idempotency-Key} on mutations (R41, handled by
 * the REST layer's idempotency store), {@code page/size} with {@code X-Total-Count} / {@code X-Total-Count-Capped}
 * (A-16), {@code ProgrammeException.status()} → RFC 9457 problem+json. Every call runs the VI.4 check for the caller
 * ({@link ApiContext#identity()}); the tenant always comes from the identity (VI.4 rule 6).
 */
public interface ProgrammeRestPort {

    /** The authenticated caller. */
    record ApiContext(SecurityIdentity identity) {
        public String tenant() {
            return identity.tenantId();
        }

        public String actor() {
            return identity.personId();
        }
    }

    record ReturnDetail(ReturnService.ReturnRow returnRow, List<ReturnService.Value> values, List<ReturnService.StoredFinding> findings,
                        List<ReturnService.Event> events) {
    }

    record AllocationRequest(Map<String, BigDecimal> explicit, Map<String, BigDecimal> weights, boolean allowOverAllocation) {
    }

    record FinancialRequest(String programmeId, String periodId, String orgUnit, String head, BigDecimal allocated, BigDecimal released,
                            BigDecimal committed, BigDecimal expended, String currency) {
    }

    // configuration                      PUT /programme-config, GET /programme-config/history
    IndicatorRegistry.Published publishConfig(ApiContext ctx, Map<String, Object> config);

    List<Map<String, Object>> configHistory(ApiContext ctx);

    // periods                            POST /periods/generate, GET /periods, POST /periods/{id}/state
    List<PeriodService.Period> generatePeriods(ApiContext ctx, IndicatorDefinition.Frequency frequency, int fiscalStartYear, int dueOffsetDays);

    List<PeriodService.Period> listPeriods(ApiContext ctx, IndicatorDefinition.Frequency frequency, String fiscalYear);

    PeriodService.Period changePeriodState(ApiContext ctx, String periodId, PeriodService.State state, String reason);

    // programmes                         /programmes/**
    ProgrammeService.Programme createProgramme(ApiContext ctx, ProgrammeService.ProgrammeInput input);

    ProgrammeService.Programme updateProgramme(ApiContext ctx, String id, int expectedVersion, ProgrammeService.ProgrammeInput input);

    ProgrammeService.Programme changeProgrammeStatus(ApiContext ctx, String id, ProgrammeService.Status status);

    ProgrammeService.Programme getProgramme(ApiContext ctx, String id);

    ProgrammeService.Node programmeTree(ApiContext ctx, String id);

    Paging.PageResult<ProgrammeService.Programme> listProgrammes(ApiContext ctx, String kind, String parentId, ProgrammeService.Status status,
                                                                Paging.PageRequest page);

    // targets                            /programmes/{id}/targets/**, /targets/{id}/**
    TargetService.Target setTarget(ApiContext ctx, String programmeId, String indicatorKey, String periodId, String orgUnit, BigDecimal value);

    TargetService.Target proposeTarget(ApiContext ctx, String targetId);

    TargetService.Target approveTarget(ApiContext ctx, String targetId);

    TargetService.Target rejectTarget(ApiContext ctx, String targetId, String reason);

    TargetService.Target reviseTarget(ApiContext ctx, String targetId, BigDecimal value, String reason);

    List<TargetService.Target> allocateTarget(ApiContext ctx, String targetId, AllocationRequest request);

    List<TargetService.Target> listTargets(ApiContext ctx, String programmeId, String periodId, String orgUnit, boolean approvedOnly);

    // milestones                         /programmes/{id}/milestones/**
    MilestoneService.Milestone planMilestone(ApiContext ctx, String programmeId, String orgUnit, String name, int seq, LocalDate plannedStart,
                                             LocalDate plannedEnd, BigDecimal weight, List<String> dependsOn);

    MilestoneService.Milestone updateMilestone(ApiContext ctx, MilestoneService.Progress progress);

    List<MilestoneService.Milestone> listMilestones(ApiContext ctx, String programmeId, String orgUnit);

    // returns                            /returns/**
    ReturnService.CampaignResult startCampaign(ApiContext ctx, String returnKey, String periodId, String rootNode, String programmeId);

    Paging.PageResult<ReturnService.ReturnRow> listReturns(ApiContext ctx, String returnKey, String periodId, String orgUnit,
                                                           ReturnService.State state, Paging.PageRequest page);

    ReturnDetail getReturn(ApiContext ctx, String returnId);

    ReturnService.ReturnRow saveReturnDraft(ApiContext ctx, String returnId, int expectedVersion, Map<String, Object> values,
                                            Map<String, String> remarks);

    ReturnService.Outcome submitReturn(ApiContext ctx, String returnId, int expectedVersion);

    ReturnService.Outcome validateReturn(ApiContext ctx, String returnId);

    ReturnService.ReturnRow acceptReturn(ApiContext ctx, String returnId);

    ReturnService.ReturnRow sendBackReturn(ApiContext ctx, String returnId, String reason);

    ReturnService.ReturnRow freezeReturn(ApiContext ctx, String returnId);

    ReturnService.ReturnRow reopenReturn(ApiContext ctx, String returnId, String reason);

    void acknowledgeFinding(ApiContext ctx, String returnId, int seq);

    List<ReturnService.ReturnRow> defaulters(ApiContext ctx, String returnKey, String periodId, LocalDate asOf, int limit);

    // achievements and roll-up           /programmes/{id}/achievements/**, POST /programmes/{id}/rollup
    RollupService.Achievement recordManualAchievement(ApiContext ctx, String programmeId, String indicatorKey, String periodId, String orgUnit,
                                                      BigDecimal value);

    RollupService.RollupResult rollup(ApiContext ctx, String programmeId, String periodId, String rootNode);

    List<RollupService.Achievement> achievements(ApiContext ctx, String programmeId, String periodId, String orgUnit);

    List<RollupService.Achievement> achievementChildren(ApiContext ctx, String programmeId, String indicatorKey, String periodId, String orgUnit);

    List<RollupService.Achievement> series(ApiContext ctx, String programmeId, String indicatorKey, String orgUnit, String fiscalYear);

    // financial and progress             /programmes/{id}/financial/**, /programmes/{id}/progress
    ProgressService.Financial recordFinancial(ApiContext ctx, FinancialRequest request);

    int importFinancial(ApiContext ctx, String programmeId, String periodId, String orgUnit);

    ProgressService.Progress progress(ApiContext ctx, String programmeId, String periodId, String orgUnit);

    List<ProgressService.Progress> childProgress(ApiContext ctx, String programmeId, String periodId, String orgUnit);

    // alerts                             /programme-alerts/**
    List<VarianceService.Alert> checkVariance(ApiContext ctx, String programmeId, String periodId, LocalDate asOf);

    List<VarianceService.Alert> openAlerts(ApiContext ctx, String programmeId, VarianceService.Kind kind, int limit);

    VarianceService.Alert acknowledgeAlert(ApiContext ctx, String alertId);

    // dashboards (C14 feed)              /programmes/{id}/dashboard/**
    int refreshDashboard(ApiContext ctx, String programmeId, String periodId, String rootNode);

    Optional<DashboardFeed.Snapshot> dashboard(ApiContext ctx, String programmeId, String periodId, String orgUnit);

    List<DashboardFeed.Snapshot> dashboardChildren(ApiContext ctx, String programmeId, String periodId, String orgUnit);
}
