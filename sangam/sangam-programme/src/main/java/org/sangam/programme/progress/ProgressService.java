// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.progress;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.programme.ProgrammeException;
import org.sangam.programme.config.IndicatorDefinition;
import org.sangam.programme.config.ProgrammeConfig;
import org.sangam.programme.indicator.IndicatorRegistry;
import org.sangam.programme.milestone.MilestoneService;
import org.sangam.programme.period.PeriodService;
import org.sangam.programme.ports.ProgrammePorts.FinancialSource;
import org.sangam.programme.ports.ProgrammePorts.HierarchyPort;
import org.sangam.programme.programme.ProgrammeService;
import org.sangam.programme.rollup.RollupService;
import org.sangam.programme.support.Db;
import org.sangam.programme.target.TargetService;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Physical versus financial progress (N15 monthly progress reports, physical and financial). Physical progress comes
 * from weighted milestones or from weighted achievement-to-target of PHYSICAL indicators (configuration
 * {@code physicalProgress}); financial progress is expenditure over allocation from {@code PG_FINANCIAL} (entered,
 * imported from a {@link FinancialSource} such as the generic ledger, and rolled up along the hierarchy). The gap is
 * physical minus financial (percentage points).
 */
public final class ProgressService {

    public record Financial(String programmeId, String periodId, String orgUnit, String head, BigDecimal allocated, BigDecimal released,
                            BigDecimal committed, BigDecimal expended, String currency, String source, String updatedBy, Instant updatedAt) {
    }

    public record Progress(String programmeId, String periodId, String orgUnit, String physicalBasis, BigDecimal physicalPct,
                           BigDecimal plannedPhysicalPct, BigDecimal financialPct, BigDecimal releasedPct, BigDecimal utilisationOfReleasedPct,
                           BigDecimal gapPct, BigDecimal allocated, BigDecimal released, BigDecimal expended) {
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext MC = MathContext.DECIMAL64;

    private final Dialect dialect;
    private final IndicatorRegistry registry;
    private final ProgrammeService programmes;
    private final PeriodService periods;
    private final TargetService targets;
    private final MilestoneService milestones;
    private final RollupService rollup;
    private final HierarchyPort hierarchy;
    private final FinancialSource financialSource;

    public ProgressService(Dialect dialect, IndicatorRegistry registry, ProgrammeService programmes, PeriodService periods,
                           TargetService targets, MilestoneService milestones, RollupService rollup, HierarchyPort hierarchy,
                           FinancialSource financialSource) {
        this.dialect = dialect;
        this.registry = registry;
        this.programmes = programmes;
        this.periods = periods;
        this.targets = targets;
        this.milestones = milestones;
        this.rollup = rollup;
        this.hierarchy = hierarchy;
        this.financialSource = financialSource == null ? FinancialSource.NONE : financialSource;
    }

    // ------------------------------------------------------------------------------------------------ financial

    public Financial recordFinancial(Connection c, String tenant, String programmeId, String periodId, String orgUnit, String head,
                                     BigDecimal allocated, BigDecimal released, BigDecimal committed, BigDecimal expended, String currency,
                                     String actor) {
        String t = Db.tenant(tenant);
        programmes.get(c, t, programmeId);
        periods.requireWritable(c, t, periodId, true);
        Db.requireText(orgUnit, "org unit");
        String h = head == null || head.isBlank() ? "TOTAL" : head;
        for (BigDecimal v : new BigDecimal[]{allocated, released, committed, expended}) {
            if (v != null && v.signum() < 0) {
                throw ProgrammeException.invalid("financial amounts must not be negative");
            }
        }
        write(c, t, programmeId, periodId, orgUnit, h, allocated, released, committed, expended, currency, "MANUAL", actor);
        return financial(c, t, programmeId, periodId, orgUnit).stream().filter(f -> f.head().equals(h)).findFirst().orElseThrow();
    }

    /** Pulls actuals from the bound {@link FinancialSource} for one node (source IMPORT). */
    public int importFinancial(Connection c, String tenant, String programmeId, String periodId, String orgUnit, String actor) {
        String t = Db.tenant(tenant);
        int n = 0;
        for (Map.Entry<String, BigDecimal[]> e : financialSource.actuals(c, t, programmeId, periodId, orgUnit).entrySet()) {
            BigDecimal[] v = e.getValue();
            write(c, t, programmeId, periodId, orgUnit, e.getKey(), at(v, 0), at(v, 1), at(v, 2), at(v, 3),
                    programmes.get(c, t, programmeId).currency(), "IMPORT", actor);
            n++;
        }
        return n;
    }

    /** Sums financial rows bottom-up along the hierarchy (nodes with own MANUAL/IMPORT rows keep them). */
    public int rollupFinancial(Connection c, String tenant, String programmeId, String periodId, String rootNode) {
        String t = Db.tenant(tenant);
        int[] n = {0};
        walkFinancial(c, t, programmeId, periodId, rootNode, n, new HashSet<>(), 0);
        return n[0];
    }

    private void walkFinancial(Connection c, String t, String programmeId, String periodId, String node, int[] n, Set<String> seen, int depth) {
        if (!seen.add(node) || depth > 32 || seen.size() > 20_000) {
            return;
        }
        List<String> kids = hierarchy.children(c, t, node);
        for (String k : kids) {
            walkFinancial(c, t, programmeId, periodId, k, n, seen, depth + 1);
        }
        if (kids.isEmpty()) {
            return;
        }
        boolean own = financial(c, t, programmeId, periodId, node).stream().anyMatch(f -> !"ROLLUP".equals(f.source()));
        if (own) {
            return;
        }
        Jdbc.update(c, "DELETE FROM PG_FINANCIAL WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit=? AND source='ROLLUP'",
                t, programmeId, periodId, node);
        List<Object> params = new ArrayList<>(List.of(t, programmeId, periodId));
        params.addAll(kids);
        List<Object[]> sums = Jdbc.query(c, "SELECT head, SUM(allocated) AS a, SUM(released) AS r, SUM(committed_amt) AS cm, SUM(expended) AS e,"
                        + " MAX(currency) AS cur FROM PG_FINANCIAL WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit IN "
                        + Db.in(kids.size()) + " GROUP BY head",
                rs -> new Object[]{rs.getString("head"), rs.getBigDecimal("a"), rs.getBigDecimal("r"), rs.getBigDecimal("cm"),
                        rs.getBigDecimal("e"), rs.getString("cur")}, params.toArray());
        for (Object[] s : sums) {
            write(c, t, programmeId, periodId, node, (String) s[0], (BigDecimal) s[1], (BigDecimal) s[2], (BigDecimal) s[3], (BigDecimal) s[4],
                    (String) s[5], "ROLLUP", null);
            n[0]++;
        }
    }

    public List<Financial> financial(Connection c, String tenant, String programmeId, String periodId, String orgUnit) {
        return Jdbc.query(c, "SELECT * FROM PG_FINANCIAL WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit=? ORDER BY head",
                ProgressService::mapFinancial, Db.tenant(tenant), programmeId, periodId, orgUnit);
    }

    private void write(Connection c, String t, String programmeId, String periodId, String orgUnit, String head, BigDecimal allocated,
                       BigDecimal released, BigDecimal committed, BigDecimal expended, String currency, String source, String actor) {
        Jdbc.update(c, "DELETE FROM PG_FINANCIAL WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit=? AND head=?",
                t, programmeId, periodId, orgUnit, head);
        Jdbc.update(c, "INSERT INTO PG_FINANCIAL(tenant_id, programme_id, period_id, org_unit, head, allocated, released, committed_amt, expended,"
                        + " currency, source, updated_by, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                t, programmeId, periodId, orgUnit, head, allocated, released, committed, expended, currency, source, actor);
    }

    // ------------------------------------------------------------------------------------------------ progress

    public Progress progress(Connection c, String tenant, String programmeId, String periodId, String orgUnit) {
        String t = Db.tenant(tenant);
        ProgrammeConfig cfg = registry.config(c, t);
        ProgrammeService.Programme prog = programmes.get(c, t, programmeId);
        PeriodService.Period p = periods.get(c, t, periodId);
        BigDecimal physical;
        BigDecimal planned;
        if ("MILESTONES".equals(cfg.physicalProgressBasis())) {
            String scope = milestones.list(c, t, programmeId, orgUnit).isEmpty() ? null : orgUnit;
            physical = milestones.physicalProgress(c, t, programmeId, scope);
            planned = milestones.plannedProgress(c, t, programmeId, scope, p.endsOn());
        } else {
            physical = indicatorPhysical(c, t, cfg, prog, periodId, orgUnit);
            planned = HUNDRED;
        }
        BigDecimal alloc = BigDecimal.ZERO;
        BigDecimal rel = BigDecimal.ZERO;
        BigDecimal exp = BigDecimal.ZERO;
        List<Financial> rows = financial(c, t, programmeId, periodId, orgUnit);
        boolean onlyTotal = rows.stream().allMatch(f -> "TOTAL".equals(f.head()));
        for (Financial f : rows) {
            // a TOTAL head next to detailed heads is a control figure, not added again
            if (onlyTotal || !"TOTAL".equals(f.head())) {
                alloc = alloc.add(nz(f.allocated()));
                rel = rel.add(nz(f.released()));
                exp = exp.add(nz(f.expended()));
            }
        }
        BigDecimal finPct = pct(exp, alloc);
        BigDecimal relPct = pct(rel, alloc);
        BigDecimal util = pct(exp, rel);
        BigDecimal gap = physical == null || finPct == null ? null : physical.subtract(finPct).setScale(2, RoundingMode.HALF_UP);
        return new Progress(programmeId, periodId, orgUnit, cfg.physicalProgressBasis(), physical, planned, finPct, relPct, util, gap, alloc, rel, exp);
    }

    /** Weighted min(100, cumulative achievement / approved target) over the programme's PHYSICAL indicators. */
    private BigDecimal indicatorPhysical(Connection c, String t, ProgrammeConfig cfg, ProgrammeService.Programme prog, String periodId, String orgUnit) {
        BigDecimal num = BigDecimal.ZERO;
        BigDecimal den = BigDecimal.ZERO;
        for (IndicatorDefinition ind : cfg.indicators().values()) {
            if (!IndicatorDefinition.PHYSICAL.equals(ind.kind()) || (!prog.indicators().isEmpty() && !prog.indicators().contains(ind.key()))) {
                continue;
            }
            BigDecimal target = targets.approvedValue(c, t, prog.id(), ind.key(), periodId, orgUnit).orElse(null);
            if (target == null || target.signum() == 0) {
                continue;
            }
            BigDecimal ach = rollup.find(c, t, prog.id(), ind.key(), periodId, orgUnit)
                    .map(a -> a.cumulativeValue() != null ? a.cumulativeValue() : a.periodValue()).orElse(BigDecimal.ZERO);
            BigDecimal ratio = ach.multiply(HUNDRED).divide(target, MC);
            if (ind.lowerIsBetter()) {
                ratio = ach.signum() == 0 ? HUNDRED : target.multiply(HUNDRED).divide(ach, MC);
            }
            BigDecimal w = ind.weight() == null ? BigDecimal.ONE : ind.weight();
            num = num.add(ratio.min(HUNDRED).multiply(w));
            den = den.add(w);
        }
        return den.signum() == 0 ? null : num.divide(den, MC).setScale(2, RoundingMode.HALF_UP);
    }

    /** Progress of the children of {@code node} (drill-down table). */
    public List<Progress> childProgress(Connection c, String tenant, String programmeId, String periodId, String node) {
        List<Progress> out = new ArrayList<>();
        for (String k : hierarchy.children(c, Db.tenant(tenant), node)) {
            out.add(progress(c, tenant, programmeId, periodId, k));
        }
        return out;
    }

    /** Pro-rata expected achievement for a target at {@code asOf} inside the period. */
    public static BigDecimal proRata(BigDecimal target, PeriodService.Period p, LocalDate asOf) {
        return target.multiply(BigDecimal.valueOf(PeriodService.elapsedShare(p, asOf)), MC);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? null : part.multiply(HUNDRED).divide(whole, MC).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal at(BigDecimal[] v, int i) {
        return v != null && v.length > i ? v[i] : null;
    }

    static Financial mapFinancial(ResultSet rs) throws SQLException {
        return new Financial(rs.getString("programme_id"), rs.getString("period_id"), rs.getString("org_unit"), rs.getString("head"),
                rs.getBigDecimal("allocated"), rs.getBigDecimal("released"), rs.getBigDecimal("committed_amt"), rs.getBigDecimal("expended"),
                rs.getString("currency"), rs.getString("source"), rs.getString("updated_by"), Jdbc.instant(rs, "updated_at"));
    }
}
