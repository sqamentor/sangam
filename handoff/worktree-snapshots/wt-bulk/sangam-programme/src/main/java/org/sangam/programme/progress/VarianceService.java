// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.progress;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.programme.config.IndicatorDefinition;
import org.sangam.programme.config.ProgrammeConfig;
import org.sangam.programme.indicator.IndicatorRegistry;
import org.sangam.programme.milestone.MilestoneService;
import org.sangam.programme.period.PeriodService;
import org.sangam.programme.programme.ProgrammeService;
import org.sangam.programme.returns.ReturnService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Variance alerts (N15): achievement against the pro-rata approved target, beyond the configured warn / critical
 * percentages, in the indicator's adverse direction. Also raises DEFAULTER alerts (returns overdue) and
 * MILESTONE_DELAY alerts. Alerts are de-duplicated by a key, emitted on the outbox ({@code programme.alert.raised})
 * and acknowledged by a person.
 */
public final class VarianceService {

    public enum Kind { VARIANCE, DEFAULTER, MILESTONE_DELAY }

    public enum Severity { WARNING, CRITICAL }

    public record Alert(String id, Kind kind, String programmeId, String indicatorKey, String periodId, String orgUnit, String subjectRef,
                        Severity severity, String message, BigDecimal measure, BigDecimal threshold, String state, Instant createdAt,
                        String acknowledgedBy, Instant acknowledgedAt) {
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final Dialect dialect;
    private final IndicatorRegistry registry;
    private final ProgrammeService programmes;
    private final PeriodService periods;
    private final TargetService targets;
    private final RollupService rollup;
    private final Outbox outbox;

    public VarianceService(Dialect dialect, IndicatorRegistry registry, ProgrammeService programmes, PeriodService periods, TargetService targets,
                           RollupService rollup, Outbox outbox) {
        this.dialect = dialect;
        this.registry = registry;
        this.programmes = programmes;
        this.periods = periods;
        this.targets = targets;
        this.rollup = rollup;
        this.outbox = outbox;
    }

    /** Checks every approved target of the programme and period (all nodes) at {@code asOf}; returns new alerts. */
    public List<Alert> checkVariance(Connection c, String tenant, String programmeId, String periodId, LocalDate asOf) {
        String t = Db.tenant(tenant);
        ProgrammeConfig cfg = registry.config(c, t);
        programmes.get(c, t, programmeId);
        PeriodService.Period p = periods.get(c, t, periodId);
        List<Alert> raised = new ArrayList<>();
        for (TargetService.Target tg : targets.list(c, t, programmeId, periodId, null, true)) {
            IndicatorDefinition ind = cfg.indicators().get(tg.indicatorKey());
            if (ind == null || tg.value().signum() == 0) {
                continue;
            }
            BigDecimal expected = ProgressService.proRata(tg.value(), p, asOf);
            if (expected.signum() == 0) {
                continue;
            }
            Optional<RollupService.Achievement> a = rollup.find(c, t, programmeId, tg.indicatorKey(), periodId, tg.orgUnit());
            BigDecimal ach = a.map(x -> x.cumulativeValue() != null && ind.cumulative() ? x.cumulativeValue() : x.periodValue())
                    .orElse(BigDecimal.ZERO);
            BigDecimal variance = ach.subtract(expected).multiply(HUNDRED).divide(expected, MathContext.DECIMAL64).setScale(2, RoundingMode.HALF_UP);
            BigDecimal adverse = ind.lowerIsBetter() ? variance : variance.negate();
            Severity sev = adverse.compareTo(cfg.varianceCriticalPct()) >= 0 ? Severity.CRITICAL
                    : adverse.compareTo(cfg.varianceWarnPct()) >= 0 ? Severity.WARNING : null;
            if (sev == null) {
                continue;
            }
            String msg = ind.name() + " is " + variance.abs().toPlainString() + "% " + (variance.signum() < 0 ? "below" : "above")
                    + " the pro-rata target";
            raise(c, t, Kind.VARIANCE, programmeId, ind.key(), periodId, tg.orgUnit(), tg.id(), sev, msg, variance,
                    sev == Severity.CRITICAL ? cfg.varianceCriticalPct() : cfg.varianceWarnPct()).ifPresent(raised::add);
        }
        return raised;
    }

    /** DEFAULTER alerts for overdue returns. */
    public List<Alert> raiseDefaulters(Connection c, String tenant, List<ReturnService.ReturnRow> defaulters, LocalDate asOf) {
        List<Alert> out = new ArrayList<>();
        for (ReturnService.ReturnRow r : defaulters) {
            long days = asOf.toEpochDay() - r.dueOn().toEpochDay();
            Severity s = days > 30 ? Severity.CRITICAL : Severity.WARNING;
            raise(c, tenant, Kind.DEFAULTER, r.programmeId(), null, r.periodId(), r.orgUnit(), r.id(), s,
                    "return " + r.returnKey() + " overdue by " + days + " day(s)", BigDecimal.valueOf(days), null).ifPresent(out::add);
        }
        return out;
    }

    /** MILESTONE_DELAY alerts. */
    public List<Alert> raiseMilestoneDelays(Connection c, String tenant, List<MilestoneService.Milestone> delayed) {
        List<Alert> out = new ArrayList<>();
        for (MilestoneService.Milestone m : delayed) {
            int d = m.delayDays() == null ? 0 : m.delayDays();
            raise(c, tenant, Kind.MILESTONE_DELAY, m.programmeId(), null, null, m.orgUnit(), m.id(), d > 30 ? Severity.CRITICAL : Severity.WARNING,
                    "milestone " + m.name() + " delayed by " + d + " day(s)", BigDecimal.valueOf(d), null).ifPresent(out::add);
        }
        return out;
    }

    /** Raises an alert unless an open one with the same key exists (severity escalates in place). */
    public Optional<Alert> raise(Connection c, String tenant, Kind kind, String programmeId, String indicatorKey, String periodId, String orgUnit,
                                 String subjectRef, Severity severity, String message, BigDecimal measure, BigDecimal threshold) {
        String t = Db.tenant(tenant);
        String dedup = kind + ":" + programmeId + ":" + indicatorKey + ":" + periodId + ":" + orgUnit + ":" + subjectRef;
        if (dedup.length() > 255) {
            dedup = kind + ":" + Db.hashOf(dedup);
        }
        Optional<Alert> existing = Jdbc.one(c, "SELECT * FROM PG_ALERT WHERE tenant_id=? AND dedup_key=?", VarianceService::map, t, dedup);
        if (existing.isPresent()) {
            Alert e = existing.get();
            if ("OPEN".equals(e.state()) && e.severity() == Severity.WARNING && severity == Severity.CRITICAL) {
                Jdbc.update(c, "UPDATE PG_ALERT SET severity=?, message=?, measure=? WHERE tenant_id=? AND alert_id=?",
                        severity.name(), message, measure, t, e.id());
                emit(c, t, e.id(), "programme.alert.escalated", Db.kv("alertId", e.id(), "kind", kind.name(), "severity", severity.name()));
                return Optional.of(get(c, t, e.id()));
            }
            return Optional.empty();
        }
        String id = Db.newId();
        String msg = message.length() > 255 ? message.substring(0, 255) : message;
        Jdbc.update(c, "INSERT INTO PG_ALERT(tenant_id, alert_id, kind, programme_id, indicator_key, period_id, org_unit, subject_ref, severity,"
                        + " message, measure, threshold, state, dedup_key, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',?," + dialect.now() + ")",
                t, id, kind.name(), programmeId, indicatorKey, periodId, orgUnit, subjectRef, severity.name(), msg, measure, threshold, dedup);
        emit(c, t, id, "programme.alert.raised", Db.kv("alertId", id, "kind", kind.name(), "severity", severity.name(),
                "programmeId", programmeId, "indicatorKey", indicatorKey, "periodId", periodId, "orgUnit", orgUnit));
        return Optional.of(get(c, t, id));
    }

    public Alert acknowledge(Connection c, String tenant, String alertId, String actor) {
        Db.requireOne(Jdbc.update(c, "UPDATE PG_ALERT SET state='ACKNOWLEDGED', acknowledged_by=?, acknowledged_at=" + dialect.now()
                + " WHERE tenant_id=? AND alert_id=? AND state='OPEN'", actor, Db.tenant(tenant), alertId), "alert");
        return get(c, tenant, alertId);
    }

    public Alert get(Connection c, String tenant, String alertId) {
        return Jdbc.one(c, "SELECT * FROM PG_ALERT WHERE tenant_id=? AND alert_id=?", VarianceService::map, Db.tenant(tenant), alertId)
                .orElseThrow(() -> org.sangam.programme.ProgrammeException.notFound("alert", alertId));
    }

    public List<Alert> open(Connection c, String tenant, String programmeIdOrNull, Kind kindOrNull, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM PG_ALERT WHERE tenant_id=? AND state='OPEN'");
        List<Object> params = new ArrayList<>(List.of(Db.tenant(tenant)));
        if (programmeIdOrNull != null) {
            sql.append(" AND programme_id=?");
            params.add(programmeIdOrNull);
        }
        if (kindOrNull != null) {
            sql.append(" AND kind=?");
            params.add(kindOrNull.name());
        }
        sql.append(" ORDER BY created_at DESC");
        org.sangam.ports.support.Paging.PageRequest page = new org.sangam.ports.support.Paging.PageRequest(0, limit);
        return Jdbc.query(c, sql + org.sangam.ports.support.Paging.window(dialect, page), VarianceService::map, params.toArray());
    }

    public long openCount(Connection c, String tenant, String programmeId, String orgUnit) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM PG_ALERT WHERE tenant_id=? AND programme_id=? AND org_unit=? AND state='OPEN'",
                rs -> rs.getLong(1), Db.tenant(tenant), programmeId, orgUnit).orElse(0L);
    }

    private void emit(Connection c, String t, String rootId, String type, Map<String, Object> payload) {
        if (outbox != null) {
            outbox.append(c, t, rootId, type, Db.json(payload), List.of(ReturnService.EVENTS));
        }
    }

    static Alert map(ResultSet rs) throws SQLException {
        return new Alert(rs.getString("alert_id"), Kind.valueOf(rs.getString("kind")), rs.getString("programme_id"), rs.getString("indicator_key"),
                rs.getString("period_id"), rs.getString("org_unit"), rs.getString("subject_ref"), Severity.valueOf(rs.getString("severity")),
                rs.getString("message"), rs.getBigDecimal("measure"), rs.getBigDecimal("threshold"), rs.getString("state"),
                Jdbc.instant(rs, "created_at"), rs.getString("acknowledged_by"), Jdbc.instant(rs, "acknowledged_at"));
    }
}
