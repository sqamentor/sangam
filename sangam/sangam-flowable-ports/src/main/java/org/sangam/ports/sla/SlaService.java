// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.sla;

import org.sangam.core.engine.WorkCalendar;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.support.Json;
import org.sangam.ports.support.Paging;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * SLA models (EC-08, Flowable Enterprise SLA; R79): an SLA clock per process instance, case or task with a target,
 * an optional warning threshold and a working calendar (WS-ENGINE's {@link WorkCalendar}, FLOWABLE-INTO-OPERATON FC-23). Pause/resume moves
 * the deadline by the paused time (the SLA half of FLOWABLE-INTO-OPERATON D-32; pausing the <em>instance</em> itself is WS-ENGINE's
 * {@code pauseInstance/resumeInstance}). {@link #sweep} raises {@code sangam.sla.warning} and
 * {@code sangam.sla.breached} outbox events exactly once per clock (flag + state in the same transaction).
 * Times are database time (A-1). Table {@code SLA_INSTANCE}.
 */
public class SlaService {

    public enum State { RUNNING, PAUSED, MET, BREACHED, STOPPED }

    /** {@code definitionKey}: process/case definition key the clock belongs to (per-definition statistics). */
    public record SlaDefinition(String key, String target, String warnAt, String calendar, ZoneId zone, String definitionKey) {
    }

    /** Aggregate per (definition key, SLA key) over clocks started in a range. */
    public record SlaStats(String definitionKey, String slaKey, long total, long running, long paused, long met, long warned,
                           long breached, long stopped, Long avgElapsedMillis, Long maxElapsedMillis, Double breachRate) {
    }

    /** A running clock close to its deadline. */
    public record AtRisk(SlaClock clock, long remainingMillis) {
    }

    public record SlaClock(String id, String tenantId, String slaKey, String scopeType, String scopeId, State state, Instant startedAt,
                           Instant dueAt, Instant warnAt, Instant pausedAt, long pausedMillis, boolean warned, Instant breachedAt) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final Outbox outbox;
    private final Function<String, WorkCalendar> calendars;

    public SlaService(Dialect dialect, Transactions tx, Outbox outbox, Function<String, WorkCalendar> calendars) {
        this.dialect = dialect;
        this.tx = tx;
        this.outbox = outbox;
        this.calendars = calendars;
    }

    /** Starts a clock; idempotent per (sla key, scope). */
    public String start(Connection c, String tenant, SlaDefinition def, String scopeType, String scopeId) {
        Optional<String> existing = Jdbc.one(c, "SELECT id FROM SLA_INSTANCE WHERE tenant_id = ? AND sla_key = ? AND scope_type = ? AND scope_id = ?"
                + " AND state IN ('RUNNING','PAUSED')", rs -> rs.getString(1), t(tenant), def.key(), scopeType, scopeId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = Jdbc.dbNow(c, dialect);
        Instant due = add(def.calendar(), now, def.target(), def.zone());
        Instant warn = def.warnAt() == null ? null : add(def.calendar(), now, def.warnAt(), def.zone());
        String id = UUID.randomUUID().toString();
        Jdbc.update(c, "INSERT INTO SLA_INSTANCE(id, tenant_id, sla_key, definition_key, scope_type, scope_id, state, calendar, target_ms, warn_ms,"
                        + " started_at, due_at, warn_at, paused_at, paused_ms, warned_flag, breached_at, stopped_at)"
                        + " VALUES (?,?,?,?,?,?,'RUNNING',?,?,?,?,?,?,NULL,0,0,NULL,NULL)",
                id, t(tenant), def.key(), def.definitionKey(), scopeType, scopeId, def.calendar(), Duration.between(now, due).toMillis(),
                warn == null ? null : Duration.between(now, warn).toMillis(), now, due, warn);
        return id;
    }

    public void pause(String id) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE SLA_INSTANCE SET state = 'PAUSED', paused_at = " + dialect.now()
                + " WHERE id = ? AND state = 'RUNNING'", id));
    }

    /** Resumes and moves due/warn by the paused time. */
    public void resume(String id) {
        tx.inTransaction(c -> {
            SlaClock s = get(c, id).orElseThrow(() -> new IllegalArgumentException("SLA " + id + " not found"));
            if (s.state() != State.PAUSED) {
                return null;
            }
            Instant now = Jdbc.dbNow(c, dialect);
            long paused = Duration.between(s.pausedAt(), now).toMillis();
            Jdbc.update(c, "UPDATE SLA_INSTANCE SET state = 'RUNNING', paused_at = NULL, paused_ms = paused_ms + ?, due_at = ?, warn_at = ?"
                    + " WHERE id = ? AND state = 'PAUSED'", paused, s.dueAt().plusMillis(paused),
                    s.warnAt() == null ? null : s.warnAt().plusMillis(paused), id);
            return null;
        });
    }

    /**
     * Stops the clock: MET when before the deadline, else BREACHED. A running clock whose deadline has passed but that
     * no sweep has reached yet is a breach too (recorded and reported here, once). Stopping a paused clock closes the
     * open pause, so its time counts as paused, not elapsed.
     */
    public void stop(Connection c, String tenant, String slaKey, String scopeType, String scopeId) {
        Instant now = Jdbc.dbNow(c, dialect);
        List<SlaClock> open = Jdbc.query(c, "SELECT " + COLS + " FROM SLA_INSTANCE s WHERE s.tenant_id = ? AND s.sla_key = ? AND s.scope_type = ?"
                + " AND s.scope_id = ? AND s.state IN ('RUNNING','PAUSED','BREACHED') AND s.stopped_at IS NULL", SlaService::map,
                t(tenant), slaKey, scopeType, scopeId);
        for (SlaClock s : open) {
            boolean late = s.state() == State.RUNNING && !s.dueAt().isAfter(now);
            long openPause = s.state() == State.PAUSED && s.pausedAt() != null ? Math.max(0, Duration.between(s.pausedAt(), now).toMillis()) : 0;
            State end = s.state() == State.BREACHED || late ? State.BREACHED : State.MET;
            int n = Jdbc.update(c, "UPDATE SLA_INSTANCE SET state = ?, stopped_at = ?, paused_at = NULL, paused_ms = paused_ms + ?"
                    + (late ? ", breached_at = ?" : "") + " WHERE id = ? AND state = ? AND stopped_at IS NULL",
                    late ? new Object[]{end.name(), now, openPause, now, s.id(), s.state().name()}
                            : new Object[]{end.name(), now, openPause, s.id(), s.state().name()});
            if (late && n == 1) {
                emit(c, s, "sangam.sla.breached");
            }
        }
    }

    /** Raises due warnings and breaches; returns how many events were written. */
    public int sweep(int batch) {
        return tx.inTransaction(c -> {
            int n = 0;
            List<SlaClock> warn = Jdbc.query(c, dialect.skipLockedSelect(COLS, "SLA_INSTANCE", "s",
                    "s.state = 'RUNNING' AND s.warned_flag = 0 AND s.warn_at IS NOT NULL AND s.warn_at <= " + dialect.now(), "s.warn_at", batch),
                    SlaService::map);
            for (SlaClock s : warn) {
                Jdbc.update(c, "UPDATE SLA_INSTANCE SET warned_flag = 1 WHERE id = ?", s.id());
                emit(c, s, "sangam.sla.warning");
                n++;
            }
            List<SlaClock> due = Jdbc.query(c, dialect.skipLockedSelect(COLS, "SLA_INSTANCE", "s",
                    "s.state = 'RUNNING' AND s.due_at <= " + dialect.now(), "s.due_at", batch), SlaService::map);
            for (SlaClock s : due) {
                Jdbc.update(c, "UPDATE SLA_INSTANCE SET state = 'BREACHED', breached_at = " + dialect.now() + " WHERE id = ?", s.id());
                emit(c, s, "sangam.sla.breached");
                n++;
            }
            return n;
        });
    }

    // ------------------------------------------------------------------ aggregates (backs sangam-rest InsightsPort)

    /** Breached clocks (breach time in [from, to)), newest first, paged and count-capped. */
    public Paging.PageResult<SlaClock> breaches(String tenant, String definitionKeyOrNull, Instant from, Instant to, Paging.PageRequest p) {
        StringBuilder w = new StringBuilder("FROM SLA_INSTANCE s WHERE s.tenant_id = ? AND s.breached_at IS NOT NULL");
        List<Object> params = new ArrayList<>(List.of(t(tenant)));
        if (definitionKeyOrNull != null) {
            w.append(" AND s.definition_key = ?");
            params.add(definitionKeyOrNull);
        }
        if (from != null) {
            w.append(" AND s.breached_at >= ?");
            params.add(from);
        }
        if (to != null) {
            w.append(" AND s.breached_at < ?");
            params.add(to);
        }
        String fw = w.toString();
        return tx.inTransaction(c -> {
            long total = Jdbc.one(c, Paging.cappedCount(dialect, fw), rs -> rs.getLong(1), params.toArray()).orElse(0L);
            List<SlaClock> rows = Jdbc.query(c, "SELECT " + COLS + " " + fw + " ORDER BY s.breached_at DESC, s.id" + Paging.window(dialect, p),
                    SlaService::map, params.toArray());
            return Paging.of(rows, total, p);
        });
    }

    /**
     * Running clocks at risk: already warned, or due within {@code window} (database time). Most urgent first; paused
     * clocks are not at risk (their deadline moves on resume).
     */
    public List<AtRisk> atRisk(String tenant, String definitionKeyOrNull, Duration window, int limit) {
        return tx.inTransaction(c -> {
            Instant now = Jdbc.dbNow(c, dialect);
            Instant horizon = now.plus(window);
            String where = "FROM SLA_INSTANCE s WHERE s.tenant_id = ? AND s.state = 'RUNNING' AND (s.warned_flag = 1 OR s.due_at <= ?)"
                    + (definitionKeyOrNull == null ? "" : " AND s.definition_key = ?");
            Object[] params = definitionKeyOrNull == null ? new Object[]{t(tenant), horizon} : new Object[]{t(tenant), horizon, definitionKeyOrNull};
            List<SlaClock> rows = Jdbc.query(c, "SELECT " + COLS + " " + where + " ORDER BY s.due_at, s.id"
                    + Paging.window(dialect, new Paging.PageRequest(0, Math.min(Math.max(limit, 1), Paging.MAX_PAGE))), SlaService::map, params);
            List<AtRisk> out = new ArrayList<>();
            for (SlaClock s : rows) {
                out.add(new AtRisk(s, Duration.between(now, s.dueAt()).toMillis()));
            }
            return out;
        });
    }

    /**
     * Statistics per (definition key, SLA key) for clocks started in [from, to). Counts are computed in SQL; elapsed
     * time (stop − start − paused time, closed clocks only) is aggregated in Java to stay dialect-neutral, over at most
     * the first {@link Paging#MAX_PAGE} closed clocks per group (a sample beyond that).
     */
    public List<SlaStats> stats(String tenant, Instant from, Instant to) {
        return tx.inTransaction(c -> {
            String range = (from == null ? "" : " AND s.started_at >= ?") + (to == null ? "" : " AND s.started_at < ?");
            List<Object> params = new ArrayList<>(List.of(t(tenant)));
            if (from != null) {
                params.add(from);
            }
            if (to != null) {
                params.add(to);
            }
            List<Object[]> groups = Jdbc.query(c, "SELECT s.definition_key, s.sla_key, COUNT(*),"
                            + " SUM(CASE WHEN s.state = 'RUNNING' THEN 1 ELSE 0 END), SUM(CASE WHEN s.state = 'PAUSED' THEN 1 ELSE 0 END),"
                            + " SUM(CASE WHEN s.state = 'MET' THEN 1 ELSE 0 END), SUM(CASE WHEN s.warned_flag = 1 THEN 1 ELSE 0 END),"
                            + " SUM(CASE WHEN s.breached_at IS NOT NULL THEN 1 ELSE 0 END), SUM(CASE WHEN s.state = 'STOPPED' THEN 1 ELSE 0 END)"
                            + " FROM SLA_INSTANCE s WHERE s.tenant_id = ?" + range + " GROUP BY s.definition_key, s.sla_key",
                    rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                            rs.getLong(7), rs.getLong(8), rs.getLong(9)}, params.toArray());
            List<SlaStats> out = new ArrayList<>();
            for (Object[] g : groups) {
                List<Object> p2 = new ArrayList<>(params);
                String defClause;
                if (g[0] == null) {
                    defClause = " AND s.definition_key IS NULL";
                } else {
                    defClause = " AND s.definition_key = ?";
                    p2.add(g[0]);
                }
                p2.add(g[1]);
                List<Long> elapsed = Jdbc.query(c, "SELECT s.started_at, s.stopped_at, s.paused_ms FROM SLA_INSTANCE s WHERE s.tenant_id = ?"
                                + range + defClause + " AND s.sla_key = ? AND s.stopped_at IS NOT NULL ORDER BY s.started_at"
                                + Paging.window(dialect, new Paging.PageRequest(0, Paging.MAX_PAGE)),
                        rs -> Duration.between(Jdbc.instant(rs, "started_at"), Jdbc.instant(rs, "stopped_at")).toMillis() - rs.getLong(3),
                        p2.toArray());
                Long avg = elapsed.isEmpty() ? null : Math.round(elapsed.stream().mapToLong(Long::longValue).average().orElse(0));
                Long max = elapsed.isEmpty() ? null : elapsed.stream().mapToLong(Long::longValue).max().orElse(0);
                long total = (Long) g[2];
                long breached = (Long) g[7];
                out.add(new SlaStats((String) g[0], (String) g[1], total, (Long) g[3], (Long) g[4], (Long) g[5], (Long) g[6], breached,
                        (Long) g[8], avg, max, total == 0 ? null : (double) breached / total));
            }
            return out;
        });
    }

    public Optional<SlaClock> get(String id) {
        return tx.inTransaction(c -> get(c, id));
    }

    public List<SlaClock> forScope(String tenant, String scopeType, String scopeId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT " + COLS.replace("s.", "") + " FROM SLA_INSTANCE WHERE tenant_id = ? AND scope_type = ?"
                + " AND scope_id = ? ORDER BY started_at", SlaService::map, t(tenant), scopeType, scopeId));
    }

    private Optional<SlaClock> get(Connection c, String id) {
        return Jdbc.one(c, "SELECT " + COLS.replace("s.", "") + " FROM SLA_INSTANCE WHERE id = ?", SlaService::map, id);
    }

    private void emit(Connection c, SlaClock s, String type) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("slaId", s.id());
        p.put("slaKey", s.slaKey());
        p.put("scopeType", s.scopeType());
        p.put("scopeId", s.scopeId());
        p.put("dueAt", s.dueAt().toString());
        outbox.append(c, s.tenantId(), s.scopeId(), type, Json.write(p), List.of(Outbox.Destination.unordered("notifications")));
    }

    private Instant add(String calendar, Instant start, String iso, ZoneId zone) {
        String s = iso.trim().toUpperCase(java.util.Locale.ROOT);
        int tIdx = s.indexOf('T');
        Period p = tIdx < 0 ? Period.parse(s) : tIdx == 1 ? Period.ZERO : Period.parse(s.substring(0, tIdx));
        Duration d = tIdx < 0 ? Duration.ZERO : Duration.parse("P" + s.substring(tIdx));
        WorkCalendar wc = calendar == null ? WorkCalendar.PLAIN : calendars.apply(calendar);
        if (wc == null) {
            throw new IllegalArgumentException("unknown calendar " + calendar);
        }
        return wc.add(start, p, d, zone == null ? ZoneId.of("UTC") : zone);
    }

    private static final String COLS = "s.id, s.tenant_id, s.sla_key, s.scope_type, s.scope_id, s.state, s.started_at, s.due_at, s.warn_at,"
            + " s.paused_at, s.paused_ms, s.warned_flag, s.breached_at";

    private static SlaClock map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SlaClock(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), State.valueOf(rs.getString(6)),
                Jdbc.instant(rs, "started_at"), Jdbc.instant(rs, "due_at"), Jdbc.instant(rs, "warn_at"), Jdbc.instant(rs, "paused_at"),
                rs.getLong(11), rs.getInt(12) == 1, Jdbc.instant(rs, "breached_at"));
    }

    private static String t(String tenant) {
        return tenant == null ? "" : tenant;
    }
}
