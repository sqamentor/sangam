// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.rollup;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.programme.ProgrammeException;
import org.sangam.programme.config.IndicatorDefinition;
import org.sangam.programme.config.IndicatorDefinition.Aggregation;
import org.sangam.programme.config.ProgrammeConfig;
import org.sangam.programme.indicator.FormulaEvaluator;
import org.sangam.programme.indicator.IndicatorRegistry;
import org.sangam.programme.period.PeriodService;
import org.sangam.programme.ports.ProgrammePorts.HierarchyPort;
import org.sangam.programme.programme.ProgrammeService;
import org.sangam.programme.support.Db;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Consolidation up the hierarchy (N15 "consolidation up the hierarchy"). Accepted returns and manual entries write
 * leaf rows ({@code RETURN}/{@code MANUAL}); {@link #rollup} walks the {@link HierarchyPort} bottom-up and writes
 * {@code ROLLUP} rows with each indicator's aggregation rule, then recomputes {@code FORMULA} indicators per node
 * ({@code DERIVED}). A node that reported its own value keeps it (reported data wins over consolidation).
 *
 * <p>Temporal rule for the cumulative-to-date column: indicators reported cumulatively keep the reported value (the
 * period value is the difference to the previous period); otherwise SUM accumulates, LAST and WEIGHTED_AVG keep the
 * latest value, AVG/MIN/MAX are taken over the year-to-date period values.
 */
public final class RollupService {

    public enum Source { RETURN, MANUAL, ROLLUP, DERIVED }

    public record Achievement(String programmeId, String indicatorKey, String periodId, String orgUnit, BigDecimal periodValue,
                              BigDecimal cumulativeValue, BigDecimal weightValue, Source source, String sourceRef, int contributors,
                              Instant computedAt) {
    }

    public record RollupResult(int nodes, int rows) {
    }

    private static final int MAX_NODES = 20_000;
    private static final MathContext MC = MathContext.DECIMAL64;

    private final Dialect dialect;
    private final IndicatorRegistry registry;
    private final PeriodService periods;
    private final ProgrammeService programmes;
    private final HierarchyPort hierarchy;

    public RollupService(Dialect dialect, IndicatorRegistry registry, PeriodService periods, ProgrammeService programmes, HierarchyPort hierarchy) {
        this.dialect = dialect;
        this.registry = registry;
        this.periods = periods;
        this.programmes = programmes;
        this.hierarchy = hierarchy;
    }

    /** Records a reported (leaf) value and derives its period and cumulative columns. */
    public Achievement recordLeaf(Connection c, String tenant, String programmeId, String indicatorKey, String periodId, String orgUnit,
                                  BigDecimal value, Source source, String sourceRef) {
        if (source != Source.RETURN && source != Source.MANUAL) {
            throw ProgrammeException.invalid("leaf source must be RETURN or MANUAL");
        }
        String t = Db.tenant(tenant);
        IndicatorDefinition ind = registry.indicator(c, t, indicatorKey);
        if (ind.aggregation() == Aggregation.FORMULA) {
            throw ProgrammeException.invalid("derived indicator " + indicatorKey + " cannot be reported");
        }
        periods.requireWritable(c, t, periodId, true);
        PeriodService.Period p = periods.get(c, t, periodId);
        BigDecimal periodValue;
        BigDecimal cumulative;
        Optional<Achievement> prev = periods.previous(c, t, p).filter(pp -> pp.fiscalYear().equals(p.fiscalYear()))
                .flatMap(pp -> find(c, t, programmeId, indicatorKey, pp.id(), orgUnit));
        if (ind.cumulative()) {
            cumulative = value;
            periodValue = prev.map(a -> a.cumulativeValue() == null ? value : value.subtract(a.cumulativeValue())).orElse(value);
        } else {
            periodValue = value;
            cumulative = temporal(c, t, ind, programmeId, orgUnit, p, value, prev);
        }
        write(c, t, new Achievement(programmeId, indicatorKey, periodId, orgUnit, periodValue, cumulative, null, source, sourceRef, 1, null));
        return find(c, t, programmeId, indicatorKey, periodId, orgUnit).orElseThrow();
    }

    private BigDecimal temporal(Connection c, String t, IndicatorDefinition ind, String programmeId, String orgUnit, PeriodService.Period p,
                                BigDecimal value, Optional<Achievement> prev) {
        return switch (ind.aggregation()) {
            case SUM -> prev.map(a -> a.cumulativeValue() == null ? value : a.cumulativeValue().add(value)).orElse(value);
            case LAST, WEIGHTED_AVG, FORMULA -> value;
            case AVG, MIN, MAX -> {
                List<BigDecimal> ytd = new ArrayList<>();
                for (PeriodService.Period q : periods.yearToDate(c, t, p)) {
                    if (q.id().equals(p.id())) {
                        continue;
                    }
                    find(c, t, programmeId, ind.key(), q.id(), orgUnit).map(Achievement::periodValue).ifPresent(ytd::add);
                }
                ytd.add(value);
                yield aggregate(ind.aggregation(), ytd, null);
            }
        };
    }

    /** Removes leaf rows written from {@code sourceRef} (a re-opened return); the next rollup recomputes parents. */
    public int removeSource(Connection c, String tenant, String sourceRef) {
        return Jdbc.update(c, "DELETE FROM PG_ACHIEVEMENT WHERE tenant_id=? AND source_ref=? AND source IN ('RETURN','MANUAL')",
                Db.tenant(tenant), sourceRef);
    }

    /** Rolls up one programme and period from {@code rootNode} down (post-order). */
    public RollupResult rollup(Connection c, String tenant, String programmeId, String periodId, String rootNode) {
        String t = Db.tenant(tenant);
        ProgrammeConfig cfg = registry.config(c, t);
        ProgrammeService.Programme prog = programmes.get(c, t, programmeId);
        periods.requireWritable(c, t, periodId, true);
        List<IndicatorDefinition> base = new ArrayList<>();
        Set<String> tracked = new HashSet<>(prog.indicators().isEmpty() ? cfg.indicators().keySet() : prog.indicators());
        // formula inputs and weight indicators must roll up too
        for (IndicatorDefinition d : cfg.formulaOrder()) {
            if (tracked.contains(d.key())) {
                tracked.addAll(FormulaEvaluator.references(d.formula()));
            }
        }
        for (IndicatorDefinition d : cfg.indicators().values()) {
            if (tracked.contains(d.key()) && d.weightIndicator() != null) {
                tracked.add(d.weightIndicator());
            }
        }
        for (IndicatorDefinition d : cfg.indicators().values()) {
            if (tracked.contains(d.key()) && d.aggregation() != Aggregation.FORMULA) {
                base.add(d);
            }
        }
        List<IndicatorDefinition> formulas = cfg.formulaOrder().stream().filter(d -> tracked.contains(d.key())).toList();
        int[] counters = new int[2];
        walk(c, t, programmeId, periodId, rootNode, base, formulas, counters, new HashSet<>(), 0);
        return new RollupResult(counters[0], counters[1]);
    }

    private void walk(Connection c, String t, String programmeId, String periodId, String node, List<IndicatorDefinition> base,
                      List<IndicatorDefinition> formulas, int[] counters, Set<String> seen, int depth) {
        if (!seen.add(node)) {
            return;
        }
        if (++counters[0] > MAX_NODES || depth > 32) {
            throw ProgrammeException.invalid("hierarchy too large or too deep for one rollup");
        }
        List<String> kids = hierarchy.children(c, t, node);
        for (String k : kids) {
            walk(c, t, programmeId, periodId, k, base, formulas, counters, seen, depth + 1);
        }
        if (!kids.isEmpty()) {
            for (IndicatorDefinition ind : base) {
                Optional<Achievement> own = find(c, t, programmeId, ind.key(), periodId, node);
                if (own.isPresent() && (own.get().source() == Source.RETURN || own.get().source() == Source.MANUAL)) {
                    continue;
                }
                List<BigDecimal> pv = new ArrayList<>();
                List<BigDecimal> cv = new ArrayList<>();
                List<BigDecimal> wv = new ArrayList<>();
                for (String k : kids) {
                    Optional<Achievement> a = find(c, t, programmeId, ind.key(), periodId, k);
                    if (a.isEmpty()) {
                        continue;
                    }
                    pv.add(a.get().periodValue());
                    cv.add(a.get().cumulativeValue());
                    if (ind.aggregation() == Aggregation.WEIGHTED_AVG) {
                        wv.add(find(c, t, programmeId, ind.weightIndicator(), periodId, k).map(Achievement::periodValue).orElse(BigDecimal.ZERO));
                    }
                }
                if (pv.isEmpty()) {
                    Jdbc.update(c, "DELETE FROM PG_ACHIEVEMENT WHERE tenant_id=? AND programme_id=? AND indicator_key=? AND period_id=? AND org_unit=?"
                            + " AND source='ROLLUP'", t, programmeId, ind.key(), periodId, node);
                    continue;
                }
                Aggregation across = ind.aggregation() == Aggregation.LAST ? Aggregation.SUM : ind.aggregation();
                BigDecimal p = round(aggregate(across, pv, wv), ind);
                BigDecimal cu = round(aggregate(across, cv, wv), ind);
                BigDecimal w = ind.aggregation() == Aggregation.WEIGHTED_AVG ? wv.stream().reduce(BigDecimal.ZERO, BigDecimal::add) : null;
                write(c, t, new Achievement(programmeId, ind.key(), periodId, node, p, cu, w, Source.ROLLUP, null, pv.size(), null));
                counters[1]++;
            }
        }
        for (IndicatorDefinition f : formulas) {
            Map<String, BigDecimal> pScope = new LinkedHashMap<>();
            Map<String, BigDecimal> cScope = new LinkedHashMap<>();
            for (String ref : FormulaEvaluator.references(f.formula())) {
                find(c, t, programmeId, ref, periodId, node).ifPresent(a -> {
                    pScope.put(ref, a.periodValue());
                    cScope.put(ref, a.cumulativeValue());
                });
            }
            BigDecimal p = FormulaEvaluator.evaluate(f.formula(), pScope::get);
            BigDecimal cu = FormulaEvaluator.evaluate(f.formula(), cScope::get);
            if (p == null && cu == null) {
                continue;
            }
            write(c, t, new Achievement(programmeId, f.key(), periodId, node, round(p, f), round(cu, f), null, Source.DERIVED, null,
                    kids.size(), null));
            counters[1]++;
        }
    }

    static BigDecimal aggregate(Aggregation agg, List<BigDecimal> values, List<BigDecimal> weights) {
        List<BigDecimal> v = values.stream().filter(java.util.Objects::nonNull).toList();
        if (v.isEmpty()) {
            return null;
        }
        return switch (agg) {
            case SUM, LAST, FORMULA -> v.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case AVG -> v.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(v.size()), MC);
            case MIN -> v.stream().min(BigDecimal::compareTo).orElseThrow();
            case MAX -> v.stream().max(BigDecimal::compareTo).orElseThrow();
            case WEIGHTED_AVG -> {
                BigDecimal num = BigDecimal.ZERO;
                BigDecimal den = BigDecimal.ZERO;
                for (int i = 0; i < values.size(); i++) {
                    if (values.get(i) == null) {
                        continue;
                    }
                    BigDecimal w = weights == null || i >= weights.size() ? BigDecimal.ONE : weights.get(i);
                    num = num.add(values.get(i).multiply(w));
                    den = den.add(w);
                }
                yield den.signum() == 0 ? null : num.divide(den, MC);
            }
        };
    }

    private static BigDecimal round(BigDecimal v, IndicatorDefinition ind) {
        return v == null ? null : v.setScale(Math.max(ind.decimals(), 0), RoundingMode.HALF_UP);
    }

    private void write(Connection c, String t, Achievement a) {
        Jdbc.update(c, "DELETE FROM PG_ACHIEVEMENT WHERE tenant_id=? AND programme_id=? AND indicator_key=? AND period_id=? AND org_unit=?",
                t, a.programmeId(), a.indicatorKey(), a.periodId(), a.orgUnit());
        Jdbc.update(c, "INSERT INTO PG_ACHIEVEMENT(tenant_id, programme_id, indicator_key, period_id, org_unit, period_value, cumulative_value,"
                        + " weight_value, source, source_ref, contributors, computed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                t, a.programmeId(), a.indicatorKey(), a.periodId(), a.orgUnit(), a.periodValue(), a.cumulativeValue(), a.weightValue(),
                a.source().name(), a.sourceRef(), a.contributors());
    }

    public Optional<Achievement> find(Connection c, String tenant, String programmeId, String indicatorKey, String periodId, String orgUnit) {
        return Jdbc.one(c, "SELECT * FROM PG_ACHIEVEMENT WHERE tenant_id=? AND programme_id=? AND indicator_key=? AND period_id=? AND org_unit=?",
                RollupService::map, Db.tenant(tenant), programmeId, indicatorKey, periodId, orgUnit);
    }

    /** Every indicator of a programme at one node and period. */
    public List<Achievement> atNode(Connection c, String tenant, String programmeId, String periodId, String orgUnit) {
        return Jdbc.query(c, "SELECT * FROM PG_ACHIEVEMENT WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit=? ORDER BY indicator_key",
                RollupService::map, Db.tenant(tenant), programmeId, periodId, orgUnit);
    }

    /** Values of the children of {@code node} (drill-down). */
    public List<Achievement> children(Connection c, String tenant, String programmeId, String indicatorKey, String periodId, String node) {
        List<Achievement> out = new ArrayList<>();
        for (String k : hierarchy.children(c, Db.tenant(tenant), node)) {
            find(c, tenant, programmeId, indicatorKey, periodId, k).ifPresent(out::add);
        }
        return out;
    }

    /** Time series of one indicator at one node over the periods of a fiscal year. */
    public List<Achievement> series(Connection c, String tenant, String programmeId, String indicatorKey, String orgUnit, String fiscalYear) {
        return Jdbc.query(c, "SELECT a.* FROM PG_ACHIEVEMENT a JOIN PG_PERIOD p ON p.tenant_id=a.tenant_id AND p.period_id=a.period_id"
                        + " WHERE a.tenant_id=? AND a.programme_id=? AND a.indicator_key=? AND a.org_unit=? AND p.fiscal_year=? ORDER BY p.starts_on",
                RollupService::map, Db.tenant(tenant), programmeId, indicatorKey, orgUnit, fiscalYear);
    }

    static Achievement map(ResultSet rs) throws SQLException {
        return new Achievement(rs.getString("programme_id"), rs.getString("indicator_key"), rs.getString("period_id"), rs.getString("org_unit"),
                rs.getBigDecimal("period_value"), rs.getBigDecimal("cumulative_value"), rs.getBigDecimal("weight_value"),
                Source.valueOf(rs.getString("source")), rs.getString("source_ref"), rs.getInt("contributors"), Jdbc.instant(rs, "computed_at"));
    }
}
