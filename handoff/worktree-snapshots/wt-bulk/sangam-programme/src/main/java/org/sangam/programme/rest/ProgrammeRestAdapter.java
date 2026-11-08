// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.rest;

import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityPorts;
import org.sangam.ports.support.Paging;
import org.sangam.programme.ProgrammeException;
import org.sangam.programme.ProgrammeModule;
import org.sangam.programme.config.IndicatorDefinition;
import org.sangam.programme.config.ProgrammeConfig;
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
import java.sql.Connection;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link ProgrammeRestPort} over the programme services. One transaction per call ({@link Transactions}, normally
 * {@code EngineTx.transactions(engine)} so campaign process starts commit with the return rows). VI.4: object checks
 * through {@link SecurityPorts.Authorization#require} inside the transaction; lists through
 * {@link SecurityPorts.Authorization#visible} compiled into the SQL (no post-filtering, A-3).
 *
 * <p>Resource types (register in WS-SECURITY's {@code ResourceRegistry}): {@value #PROGRAMME} (id = programme id, alias
 * {@code p} over {@code PG_PROGRAMME}; {@code *} = module-wide administration) and {@value #RETURN} (id = return id,
 * alias {@code r} over {@code PG_RETURN}). Permissions used: READ, MODIFY, APPROVE, START, WORK, ADMIN.
 */
public final class ProgrammeRestAdapter implements ProgrammeRestPort {

    public static final String PROGRAMME = "PROGRAMME";
    public static final String RETURN = "PROGRAMME_RETURN";
    private static final String ALL = "*";

    private final ProgrammeModule m;
    private final Transactions tx;
    private final SecurityPorts.Authorization auth;

    public ProgrammeRestAdapter(ProgrammeModule module, SecurityPorts.Authorization authorization) {
        this.m = Objects.requireNonNull(module, "module");
        this.tx = module.tx();
        this.auth = Objects.requireNonNull(authorization, "authorization (VI.4) is required");
    }

    private <T> T run(Function<Connection, T> work) {
        return tx.inTransaction(work);
    }

    private void require(Connection c, ApiContext ctx, String type, String permission, String id) {
        auth.require(c, ctx.identity(), type, permission, id);
    }

    private void requireProgramme(Connection c, ApiContext ctx, String permission, String programmeId) {
        if (programmeId == null) {
            require(c, ctx, PROGRAMME, permission, ALL);
        } else {
            m.programmes().get(c, ctx.tenant(), programmeId);
            require(c, ctx, PROGRAMME, permission, programmeId);
        }
    }

    private ReturnService.ReturnRow requireReturn(Connection c, ApiContext ctx, String permission, String returnId) {
        ReturnService.ReturnRow r = m.returns().get(c, ctx.tenant(), returnId);
        require(c, ctx, RETURN, permission, returnId);
        return r;
    }

    // ------------------------------------------------------------------------------------------------ configuration

    @Override
    public IndicatorRegistry.Published publishConfig(ApiContext ctx, Map<String, Object> config) {
        return run(c -> {
            require(c, ctx, PROGRAMME, "ADMIN", ALL);
            return m.registry().publish(c, ctx.tenant(), config, ctx.actor());
        });
    }

    @Override
    public List<Map<String, Object>> configHistory(ApiContext ctx) {
        return run(c -> {
            require(c, ctx, PROGRAMME, "ADMIN", ALL);
            return m.registry().history(c, ctx.tenant());
        });
    }

    // ------------------------------------------------------------------------------------------------ periods

    @Override
    public List<PeriodService.Period> generatePeriods(ApiContext ctx, IndicatorDefinition.Frequency frequency, int fiscalStartYear, int dueOffsetDays) {
        return run(c -> {
            require(c, ctx, PROGRAMME, "ADMIN", ALL);
            Month start = m.registry().config(c, ctx.tenant()).fiscalYearStartMonth();
            return m.periods().generate(c, ctx.tenant(), frequency, fiscalStartYear, start, dueOffsetDays);
        });
    }

    @Override
    public List<PeriodService.Period> listPeriods(ApiContext ctx, IndicatorDefinition.Frequency frequency, String fiscalYear) {
        return run(c -> {
            require(c, ctx, PROGRAMME, "READ", ALL);
            return m.periods().list(c, ctx.tenant(), frequency, fiscalYear);
        });
    }

    @Override
    public PeriodService.Period changePeriodState(ApiContext ctx, String periodId, PeriodService.State state, String reason) {
        return run(c -> {
            require(c, ctx, PROGRAMME, "ADMIN", ALL);
            return m.periods().changeState(c, ctx.tenant(), periodId, state, reason, ctx.actor());
        });
    }

    // ------------------------------------------------------------------------------------------------ programmes

    @Override
    public ProgrammeService.Programme createProgramme(ApiContext ctx, ProgrammeService.ProgrammeInput input) {
        return run(c -> {
            if (input.parentId() == null) {
                require(c, ctx, PROGRAMME, "ADMIN", ALL);
            } else {
                requireProgramme(c, ctx, "MODIFY", input.parentId());
            }
            return m.programmes().create(c, ctx.tenant(), input, ctx.actor());
        });
    }

    @Override
    public ProgrammeService.Programme updateProgramme(ApiContext ctx, String id, int expectedVersion, ProgrammeService.ProgrammeInput input) {
        return run(c -> {
            requireProgramme(c, ctx, "MODIFY", id);
            return m.programmes().update(c, ctx.tenant(), id, expectedVersion, input, ctx.actor());
        });
    }

    @Override
    public ProgrammeService.Programme changeProgrammeStatus(ApiContext ctx, String id, ProgrammeService.Status status) {
        return run(c -> {
            requireProgramme(c, ctx, "APPROVE", id);
            return m.programmes().changeStatus(c, ctx.tenant(), id, status, ctx.actor());
        });
    }

    @Override
    public ProgrammeService.Programme getProgramme(ApiContext ctx, String id) {
        return run(c -> {
            requireProgramme(c, ctx, "READ", id);
            return m.programmes().get(c, ctx.tenant(), id);
        });
    }

    @Override
    public ProgrammeService.Node programmeTree(ApiContext ctx, String id) {
        return run(c -> {
            requireProgramme(c, ctx, "READ", id);
            return filterTree(c, ctx, m.programmes().tree(c, ctx.tenant(), id));
        });
    }

    /** Children the caller may not read are cut from the tree with their subtrees (object check per node). */
    private ProgrammeService.Node filterTree(Connection c, ApiContext ctx, ProgrammeService.Node n) {
        List<ProgrammeService.Node> kids = n.children().stream()
                .filter(k -> auth.allow(c, ctx.identity(), PROGRAMME, "READ", k.programme().id()))
                .map(k -> filterTree(c, ctx, k)).toList();
        return new ProgrammeService.Node(n.programme(), kids);
    }

    @Override
    public Paging.PageResult<ProgrammeService.Programme> listProgrammes(ApiContext ctx, String kind, String parentId, ProgrammeService.Status status,
                                                                       Paging.PageRequest page) {
        return run(c -> {
            SqlPredicate visible = auth.visible(c, ctx.identity(), PROGRAMME, "READ", "p");
            return m.programmes().list(c, ctx.tenant(), kind, parentId, status, visible, page);
        });
    }

    // ------------------------------------------------------------------------------------------------ targets

    @Override
    public TargetService.Target setTarget(ApiContext ctx, String programmeId, String indicatorKey, String periodId, String orgUnit, BigDecimal value) {
        return run(c -> {
            requireProgramme(c, ctx, "MODIFY", programmeId);
            return m.targets().set(c, ctx.tenant(), programmeId, indicatorKey, periodId, orgUnit, value, ctx.actor());
        });
    }

    @Override
    public TargetService.Target proposeTarget(ApiContext ctx, String targetId) {
        return run(c -> {
            TargetService.Target t = m.targets().get(c, ctx.tenant(), targetId);
            requireProgramme(c, ctx, "MODIFY", t.programmeId());
            return m.targets().propose(c, ctx.tenant(), targetId, ctx.actor());
        });
    }

    @Override
    public TargetService.Target approveTarget(ApiContext ctx, String targetId) {
        return run(c -> {
            TargetService.Target t = m.targets().get(c, ctx.tenant(), targetId);
            requireProgramme(c, ctx, "APPROVE", t.programmeId());
            return m.targets().approve(c, ctx.tenant(), targetId, ctx.actor());
        });
    }

    @Override
    public TargetService.Target rejectTarget(ApiContext ctx, String targetId, String reason) {
        return run(c -> {
            TargetService.Target t = m.targets().get(c, ctx.tenant(), targetId);
            requireProgramme(c, ctx, "APPROVE", t.programmeId());
            return m.targets().reject(c, ctx.tenant(), targetId, reason, ctx.actor());
        });
    }

    @Override
    public TargetService.Target reviseTarget(ApiContext ctx, String targetId, BigDecimal value, String reason) {
        return run(c -> {
            TargetService.Target t = m.targets().get(c, ctx.tenant(), targetId);
            requireProgramme(c, ctx, "MODIFY", t.programmeId());
            return m.targets().revise(c, ctx.tenant(), targetId, value, reason, ctx.actor());
        });
    }

    @Override
    public List<TargetService.Target> allocateTarget(ApiContext ctx, String targetId, AllocationRequest request) {
        return run(c -> {
            TargetService.Target t = m.targets().get(c, ctx.tenant(), targetId);
            requireProgramme(c, ctx, "MODIFY", t.programmeId());
            return m.targets().allocate(c, ctx.tenant(), targetId, request.explicit(), request.weights(), request.allowOverAllocation(), ctx.actor());
        });
    }

    @Override
    public List<TargetService.Target> listTargets(ApiContext ctx, String programmeId, String periodId, String orgUnit, boolean approvedOnly) {
        return run(c -> {
            requireProgramme(c, ctx, "READ", programmeId);
            return m.targets().list(c, ctx.tenant(), programmeId, periodId, orgUnit, approvedOnly);
        });
    }

    // ------------------------------------------------------------------------------------------------ milestones

    @Override
    public MilestoneService.Milestone planMilestone(ApiContext ctx, String programmeId, String orgUnit, String name, int seq, LocalDate plannedStart,
                                                    LocalDate plannedEnd, BigDecimal weight, List<String> dependsOn) {
        return run(c -> {
            requireProgramme(c, ctx, "MODIFY", programmeId);
            return m.milestones().plan(c, ctx.tenant(), programmeId, orgUnit, name, seq, plannedStart, plannedEnd, weight, dependsOn, ctx.actor());
        });
    }

    @Override
    public MilestoneService.Milestone updateMilestone(ApiContext ctx, MilestoneService.Progress progress) {
        return run(c -> {
            MilestoneService.Milestone ms = m.milestones().get(c, ctx.tenant(), progress.milestoneId());
            requireProgramme(c, ctx, "MODIFY", ms.programmeId());
            return m.milestones().update(c, ctx.tenant(), progress, ctx.actor());
        });
    }

    @Override
    public List<MilestoneService.Milestone> listMilestones(ApiContext ctx, String programmeId, String orgUnit) {
        return run(c -> {
            requireProgramme(c, ctx, "READ", programmeId);
            return m.milestones().list(c, ctx.tenant(), programmeId, orgUnit);
        });
    }

    // ------------------------------------------------------------------------------------------------ returns

    @Override
    public ReturnService.CampaignResult startCampaign(ApiContext ctx, String returnKey, String periodId, String rootNode, String programmeId) {
        return run(c -> {
            requireProgramme(c, ctx, "START", programmeId);
            return m.returns().startCampaign(c, ctx.tenant(), returnKey, periodId, rootNode, programmeId, ctx.actor());
        });
    }

    @Override
    public Paging.PageResult<ReturnService.ReturnRow> listReturns(ApiContext ctx, String returnKey, String periodId, String orgUnit,
                                                                  ReturnService.State state, Paging.PageRequest page) {
        return run(c -> {
            SqlPredicate visible = auth.visible(c, ctx.identity(), RETURN, "READ", "r");
            return m.returns().list(c, ctx.tenant(), returnKey, periodId, orgUnit, state, visible, page);
        });
    }

    @Override
    public ReturnDetail getReturn(ApiContext ctx, String returnId) {
        return run(c -> {
            ReturnService.ReturnRow r = requireReturn(c, ctx, "READ", returnId);
            return new ReturnDetail(r, m.returns().values(c, ctx.tenant(), returnId), m.returns().findings(c, ctx.tenant(), returnId),
                    m.returns().events(c, ctx.tenant(), returnId));
        });
    }

    @Override
    public ReturnService.ReturnRow saveReturnDraft(ApiContext ctx, String returnId, int expectedVersion, Map<String, Object> values,
                                                   Map<String, String> remarks) {
        return run(c -> {
            requireReturn(c, ctx, "WORK", returnId);
            return m.returns().saveDraft(c, ctx.tenant(), returnId, expectedVersion, values, remarks, ctx.actor());
        });
    }

    @Override
    public ReturnService.Outcome submitReturn(ApiContext ctx, String returnId, int expectedVersion) {
        return run(c -> {
            requireReturn(c, ctx, "WORK", returnId);
            return m.returns().submit(c, ctx.tenant(), returnId, expectedVersion, ctx.actor());
        });
    }

    @Override
    public ReturnService.Outcome validateReturn(ApiContext ctx, String returnId) {
        return run(c -> {
            requireReturn(c, ctx, "APPROVE", returnId);
            return m.returns().validate(c, ctx.tenant(), returnId, ctx.actor());
        });
    }

    @Override
    public ReturnService.ReturnRow acceptReturn(ApiContext ctx, String returnId) {
        return run(c -> {
            requireReturn(c, ctx, "APPROVE", returnId);
            return m.returns().accept(c, ctx.tenant(), returnId, ctx.actor());
        });
    }

    @Override
    public ReturnService.ReturnRow sendBackReturn(ApiContext ctx, String returnId, String reason) {
        return run(c -> {
            requireReturn(c, ctx, "APPROVE", returnId);
            return m.returns().returnForCorrection(c, ctx.tenant(), returnId, reason, ctx.actor());
        });
    }

    @Override
    public ReturnService.ReturnRow freezeReturn(ApiContext ctx, String returnId) {
        return run(c -> {
            requireReturn(c, ctx, "APPROVE", returnId);
            return m.returns().freeze(c, ctx.tenant(), returnId, ctx.actor());
        });
    }

    @Override
    public ReturnService.ReturnRow reopenReturn(ApiContext ctx, String returnId, String reason) {
        return run(c -> {
            requireReturn(c, ctx, "ADMIN", returnId);
            return m.returns().reopen(c, ctx.tenant(), returnId, reason, ctx.actor());
        });
    }

    @Override
    public void acknowledgeFinding(ApiContext ctx, String returnId, int seq) {
        run(c -> {
            requireReturn(c, ctx, "APPROVE", returnId);
            m.returns().acknowledgeFinding(c, ctx.tenant(), returnId, seq, ctx.actor());
            return null;
        });
    }

    @Override
    public List<ReturnService.ReturnRow> defaulters(ApiContext ctx, String returnKey, String periodId, LocalDate asOf, int limit) {
        return run(c -> {
            // module-wide read of pendency (defaulter lists cross offices); no per-row post-filtering (A-3)
            require(c, ctx, RETURN, "READ", ALL);
            LocalDate d = asOf != null ? asOf : org.sangam.programme.support.Db.today(c, m.dialect());
            return m.returns().defaulters(c, ctx.tenant(), returnKey, periodId, d, limit);
        });
    }

    // ------------------------------------------------------------------------------------------------ achievements

    @Override
    public RollupService.Achievement recordManualAchievement(ApiContext ctx, String programmeId, String indicatorKey, String periodId,
                                                             String orgUnit, BigDecimal value) {
        return run(c -> {
            requireProgramme(c, ctx, "MODIFY", programmeId);
            if (value == null) {
                throw ProgrammeException.invalid("value is required");
            }
            return m.rollup().recordLeaf(c, ctx.tenant(), programmeId, indicatorKey, periodId, orgUnit, value, RollupService.Source.MANUAL,
                    "manual:" + ctx.actor());
        });
    }

    @Override
    public RollupService.RollupResult rollup(ApiContext ctx, String programmeId, String periodId, String rootNode) {
        return run(c -> {
            requireProgramme(c, ctx, "MODIFY", programmeId);
            RollupService.RollupResult r = m.rollup().rollup(c, ctx.tenant(), programmeId, periodId, rootNode);
            m.progress().rollupFinancial(c, ctx.tenant(), programmeId, periodId, rootNode);
            return r;
        });
    }

    @Override
    public List<RollupService.Achievement> achievements(ApiContext ctx, String programmeId, String periodId, String orgUnit) {
        return run(c -> {
            requireProgramme(c, ctx, "READ", programmeId);
            return m.rollup().atNode(c, ctx.tenant(), programmeId, periodId, orgUnit);
        });
    }

    @Override
    public List<RollupService.Achievement> achievementChildren(ApiContext ctx, String programmeId, String indicatorKey, String periodId,
                                                               String orgUnit) {
        return run(c -> {
            requireProgramme(c, ctx, "READ", programmeId);
            return m.rollup().children(c, ctx.tenant(), programmeId, indicatorKey, periodId, orgUnit);
        });
    }

    @Override
    public List<RollupService.Achievement> series(ApiContext ctx, String programmeId, String indicatorKey, String orgUnit, String fiscalYear) {
        return run(c -> {
            requireProgramme(c, ctx, "READ", programmeId);
            return m.rollup().series(c, ctx.tenant(), programmeId, indicatorKey, orgUnit, fiscalYear);
        });
    }

    // ------------------------------------------------------------------------------------------------ financial / progress

    @Override
    public ProgressService.Financial recordFinancial(ApiContext ctx, FinancialRequest r) {
        return run(c -> {
            requireProgramme(c, ctx, "MODIFY", r.programmeId());
            // controller boundary: decimal + currency on the wire, exact Money inside (SD-398)
            return m.progress().recordFinancial(c, ctx.tenant(), r.programmeId(), r.periodId(), r.orgUnit(), r.head(),
                    money(r.allocated(), r.currency()), money(r.released(), r.currency()), money(r.committed(), r.currency()),
                    money(r.expended(), r.currency()), r.currency(), ctx.actor());
        });
    }

    @Override
    public int importFinancial(ApiContext ctx, String programmeId, String periodId, String orgUnit) {
        return run(c -> {
            requireProgramme(c, ctx, "MODIFY", programmeId);
            return m.progress().importFinancial(c, ctx.tenant(), programmeId, periodId, orgUnit, ctx.actor());
        });
    }

    @Override
    public ProgressService.Progress progress(ApiContext ctx, String programmeId, String periodId, String orgUnit) {
        return run(c -> {
            requireProgramme(c, ctx, "READ", programmeId);
            return m.progress().progress(c, ctx.tenant(), programmeId, periodId, orgUnit);
        });
    }

    @Override
    public List<ProgressService.Progress> childProgress(ApiContext ctx, String programmeId, String periodId, String orgUnit) {
        return run(c -> {
            requireProgramme(c, ctx, "READ", programmeId);
            return m.progress().childProgress(c, ctx.tenant(), programmeId, periodId, orgUnit);
        });
    }

    // ------------------------------------------------------------------------------------------------ alerts

    @Override
    public List<VarianceService.Alert> checkVariance(ApiContext ctx, String programmeId, String periodId, LocalDate asOf) {
        return run(c -> {
            requireProgramme(c, ctx, "MODIFY", programmeId);
            LocalDate d = asOf != null ? asOf : org.sangam.programme.support.Db.today(c, m.dialect());
            return m.variance().checkVariance(c, ctx.tenant(), programmeId, periodId, d);
        });
    }

    @Override
    public List<VarianceService.Alert> openAlerts(ApiContext ctx, String programmeId, VarianceService.Kind kind, int limit) {
        return run(c -> {
            requireProgramme(c, ctx, "READ", programmeId);
            return m.variance().open(c, ctx.tenant(), programmeId, kind, limit);
        });
    }

    @Override
    public VarianceService.Alert acknowledgeAlert(ApiContext ctx, String alertId) {
        return run(c -> {
            VarianceService.Alert a = m.variance().get(c, ctx.tenant(), alertId);
            requireProgramme(c, ctx, "MODIFY", a.programmeId());
            return m.variance().acknowledge(c, ctx.tenant(), alertId, ctx.actor());
        });
    }

    // ------------------------------------------------------------------------------------------------ dashboards

    @Override
    public int refreshDashboard(ApiContext ctx, String programmeId, String periodId, String rootNode) {
        return run(c -> {
            requireProgramme(c, ctx, "MODIFY", programmeId);
            return m.dashboard().refreshTree(c, ctx.tenant(), programmeId, periodId, rootNode);
        });
    }

    @Override
    public Optional<DashboardFeed.Snapshot> dashboard(ApiContext ctx, String programmeId, String periodId, String orgUnit) {
        return run(c -> {
            requireProgramme(c, ctx, "READ", programmeId);
            return m.dashboard().get(c, ctx.tenant(), programmeId, periodId, orgUnit);
        });
    }

    @Override
    public List<DashboardFeed.Snapshot> dashboardChildren(ApiContext ctx, String programmeId, String periodId, String orgUnit) {
        return run(c -> {
            requireProgramme(c, ctx, "READ", programmeId);
            return m.dashboard().children(c, ctx.tenant(), programmeId, periodId, orgUnit);
        });
    }

    /** Exposed for the integrator's configuration endpoint (read-only view of the parsed configuration). */
    public ProgrammeConfig currentConfig(ApiContext ctx) {
        return run(c -> {
            require(c, ctx, PROGRAMME, "READ", ALL);
            return m.registry().config(c, ctx.tenant());
        });
    }

    private static org.sangam.core.money.Money money(java.math.BigDecimal v, String currency) {
        if (v == null) {
            return null;
        }
        if (currency == null) {
            throw org.sangam.programme.ProgrammeException.invalid("financial amounts need a currency (ISO 4217)");
        }
        try {
            return org.sangam.core.money.Money.ofMajor(v, currency);
        } catch (RuntimeException e) {
            throw org.sangam.programme.ProgrammeException.invalid(e.getMessage());
        }
    }
}
