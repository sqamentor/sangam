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
import org.sangam.core.money.Money;
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

    /** Amounts are exact {@link Money} in {@code currency} (JSON: MoneyAmount decimal strings). */
    public record Financial(String programmeId, String periodId, String orgUnit, String head, Money allocated, Money released,
                            Money committed, Money expended, String currency, String source, String updatedBy, Instant updatedAt) {
    }

    public record Progress(String programmeId, String periodId, String orgUnit, String physicalBasis, BigDecimal physicalPct,
                           BigDecimal plannedPhysicalPct, BigDecimal financialPct, BigDecimal releasedPct, BigDecimal utilisationOfReleasedPct,
                           BigDecimal gapPct, Money allocated, Money released, Money expended) {
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
                                     Money allocated, Money released, Money committed, Money expended, String currency,
                                     String actor) {
        String t = Db.tenant(tenant);
        programmes.get(c, t, programmeId);
        periods.requireWritable(c, t, periodId, true);
        Db.requireText(orgUnit, "org unit");
        String h = head == null || head.isBlank() ? "TOTAL" : head;
        for (Money v : new Money[]{allocated, released, committed, expended}) {
            if (v != null && v.signum() < 0) {
                throw ProgrammeException.invalid("financial amounts must not be negative");
            }
            if (v != null && !v.currencyCode().equals(currency)) {
                throw ProgrammeException.invalid("financial amount in " + v.currencyCode() + " but the row currency is " + currency);
            }
        }
        write(c, t, programmeId, periodId, orgUnit, h, allocated, released, committed, expended, currency, "MANUAL", actor);
        return financial(c, t, programmeId, periodId, orgUnit).stream().filter(f -> f.head().equals(h)).findFirst().orElseThrow();
    }

    /** Pulls actuals from the bound {@link FinancialSource} for one node (source IMPORT). */
    public int importFinancial(Connection c, String tenant, String programmeId, String periodId, String orgUnit, String actor) {
        String t = Db.tenant(tenant);
        int n = 0;
        String ccy = programmes.get(c, t, programmeId).currency();
        for (Map.Entry<String, Money[]> e : financialSource.actuals(c, t, programmeId, periodId, orgUnit).entrySet()) {
            Money[] v = e.getValue();
            for (Money x : v) {
                if (x != null && !x.currencyCode().equals(ccy)) {
                    throw ProgrammeException.invalid("imported " + x.currencyCode() + " amount for a " + ccy + " programme");
                }
            }
            write(c, t, programmeId, periodId, orgUnit, e.getKey(), at(v, 0), at(v, 1), at(v, 2), at(v, 3), ccy, "IMPORT", actor);
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
        // exact integer sums per head AND currency: amounts in different currencies are never added (the old MAX(currency)
        // labelled a mixed sum with one of them)
        List<Object[]> sums = Jdbc.query(c, "SELECT head, currency, SUM(allocated) AS a, SUM(released) AS r, SUM(committed_amt) AS cm,"
                        + " SUM(expended) AS e, COUNT(allocated) AS na, COUNT(released) AS nr, COUNT(committed_amt) AS ncm,"
                        + " COUNT(expended) AS ne FROM PG_FINANCIAL WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit IN "
                        + Db.in(kids.size()) + " GROUP BY head, currency ORDER BY head",
                rs -> {
                    String cur = rs.getString("currency");
                    return new Object[]{rs.getString("head"), cur, sum(rs, "a", "na", cur), sum(rs, "r", "nr", cur),
                            sum(rs, "cm", "ncm", cur), sum(rs, "e", "ne", cur)};
                }, params.toArray());
        Set<String> heads = new HashSet<>();
        for (Object[] s : sums) {
            if (!heads.add((String) s[0])) {
                throw ProgrammeException.invalid("cannot roll up head " + s[0] + " of " + node + ": children report different currencies");
            }
        }
        for (Object[] s : sums) {
            write(c, t, programmeId, periodId, node, (String) s[0], (Money) s[2], (Money) s[3], (Money) s[4], (Money) s[5],
                    (String) s[1], "ROLLUP", null);
            n[0]++;
        }
    }

    public List<Financial> financial(Connection c, String tenant, String programmeId, String periodId, String orgUnit) {
        return Jdbc.query(c, "SELECT * FROM PG_FINANCIAL WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit=? ORDER BY head",
                ProgressService::mapFinancial, Db.tenant(tenant), programmeId, periodId, orgUnit);
    }

    private static Money sum(ResultSet rs, String col, String count, String currency) throws SQLException {
        return rs.getLong(count) == 0 ? null : Money.ofMinor(rs.getLong(col), currency);
    }

    private static Long minor(Money m) {
        return m == null ? null : m.minor();
    }

    private void write(Connection c, String t, String programmeId, String periodId, String orgUnit, String head, Money allocated,
                       Money released, Money committed, Money expended, String currency, String source, String actor) {
        Jdbc.update(c, "DELETE FROM PG_FINANCIAL WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit=? AND head=?",
                t, programmeId, periodId, orgUnit, head);
        Jdbc.update(c, "INSERT INTO PG_FINANCIAL(tenant_id, programme_id, period_id, org_unit, head, allocated, released, committed_amt, expended,"
                        + " currency, source, updated_by, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                t, programmeId, periodId, orgUnit, head, minor(allocated), minor(released), minor(committed), minor(expended), currency,
                source, actor);
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
        List<Financial> rows = financial(c, t, programmeId, periodId, orgUnit);
        String ccy = rows.isEmpty() ? prog.currency() : rows.get(0).currency();
        Money alloc = ccy == null ? null : Money.zero(ccy);
        Money rel = alloc;
        Money exp = alloc;
        boolean onlyTotal = rows.stream().allMatch(f -> "TOTAL".equals(f.head()));
        for (Financial f : rows) {
            // a TOTAL head next to detailed heads is a control figure, not added again
            if (onlyTotal || !"TOTAL".equals(f.head())) {
                alloc = alloc.plus(nz(f.allocated(), f.currency())); // exact; a mixed currency fails here
                rel = rel.plus(nz(f.released(), f.currency()));
                exp = exp.plus(nz(f.expended(), f.currency()));
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

    /** A percentage of two money amounts (a ratio, not money): computed from exact minor units, shown to 2 places. */
    private static BigDecimal pct(Money part, Money whole) {
        return whole == null || whole.signum() == 0 ? null
                : BigDecimal.valueOf(part.minor()).multiply(HUNDRED).divide(BigDecimal.valueOf(whole.minor()), MC)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static Money nz(Money v, String currency) {
        return v == null ? Money.zero(currency) : v;
    }

    private static Money at(Money[] v, int i) {
        return v != null && v.length > i ? v[i] : null;
    }

    static Financial mapFinancial(ResultSet rs) throws SQLException {
        return new Financial(rs.getString("programme_id"), rs.getString("period_id"), rs.getString("org_unit"), rs.getString("head"),
                Db.money(rs, "allocated"), Db.money(rs, "released"), Db.money(rs, "committed_amt"), Db.money(rs, "expended"),
                rs.getString("currency"), rs.getString("source"), rs.getString("updated_by"), Jdbc.instant(rs, "updated_at"));
    }
}
