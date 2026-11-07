// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code ALARM_EPISODE} persistence (N12). Every write is fenced by {@code version} (optimistic check on top of the
 * row locks); a lost race throws {@link ConcurrentEpisodeUpdateException}, which is transient (the inbox retries).
 *
 * <p>Uniqueness of the active episode per (tenant, device, sensor, rule) is not a database constraint (a partial
 * unique index is not portable across the four dialects): callers create episodes only while holding the device row
 * lock ({@link DeviceRegistry#lock}) and after {@link #lockActive} found none.
 */
public final class AlarmEpisodeStore {

    /** Optimistic-version conflict: transient, never a payload error. */
    public static final class ConcurrentEpisodeUpdateException extends IllegalStateException {
        public ConcurrentEpisodeUpdateException(String episodeId) {
            super("409 EPISODE_CHANGED: alarm episode " + episodeId + " was changed concurrently");
        }
    }

    static final String COLUMNS = "episode_id, tenant_id, device_id, sensor, rule_id, state, severity, breach_count, first_breach_at,"
            + " opened_at, last_no, peak_value, last_reading_at, cleared_at, acked_by, acked_at, ack_note, ack_due_at,"
            + " overdue_notified_at, closed_at, close_reason, reopen_count, last_event, last_event_outcome, last_event_error,"
            + " created_at, updated_at, version";

    private static final String ACTIVE = "state IN ('PENDING','OPEN','CLEARED')";

    private final Dialect dialect;
    private final Transactions tx;

    public AlarmEpisodeStore(Dialect dialect, Transactions tx) {
        this.dialect = dialect;
        this.tx = tx;
    }

    public Dialect dialect() {
        return dialect;
    }

    /**
     * New in-memory episode (state PENDING, version 0); persist with {@link #insert}. The id is supplied by the caller:
     * {@link TelemetryRuleHandler} derives it from the reading that opened the episode, so a retried reading (after a
     * rollback) produces the same id and therefore the same, de-duplicated, event transport ids.
     */
    AlarmEpisode newEpisode(String episodeId, String tenant, String deviceId, String sensor, String ruleId, Instant now) {
        AlarmEpisode e = new AlarmEpisode(episodeId, tenant, deviceId, sensor, ruleId);
        e.state = AlarmEpisode.State.PENDING;
        e.createdAt = now;
        e.updatedAt = now;
        e.version = 0;
        return e;
    }

    void insert(Connection c, AlarmEpisode e) {
        e.version = 1;
        Jdbc.update(c, "INSERT INTO ALARM_EPISODE(" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                e.episodeId(), e.tenantId(), e.deviceId(), e.sensor(), e.ruleId(), e.state, e.severity, e.breachCount, e.firstBreachAt,
                e.openedAt, dec(e.lastValue), dec(e.peakValue), e.lastReadingAt, e.clearedAt, e.ackedBy, e.ackedAt, e.ackNote, e.ackDueAt,
                e.overdueNotifiedAt, e.closedAt, e.closeReason, e.reopenCount, e.lastEvent, e.lastEventOutcome, e.lastEventError,
                e.createdAt, e.updatedAt, e.version);
    }

    /** Writes every mutable column; fenced by the version read. */
    void update(Connection c, AlarmEpisode e) {
        long expected = e.version;
        int n = Jdbc.update(c, "UPDATE ALARM_EPISODE SET state=?, severity=?, breach_count=?, first_breach_at=?, opened_at=?, last_no=?,"
                        + " peak_value=?, last_reading_at=?, cleared_at=?, acked_by=?, acked_at=?, ack_note=?, ack_due_at=?,"
                        + " overdue_notified_at=?, closed_at=?, close_reason=?, reopen_count=?, last_event=?, last_event_outcome=?,"
                        + " last_event_error=?, updated_at=?, version=? WHERE episode_id=? AND tenant_id=? AND version=?",
                e.state, e.severity, e.breachCount, e.firstBreachAt, e.openedAt, dec(e.lastValue), dec(e.peakValue), e.lastReadingAt,
                e.clearedAt, e.ackedBy, e.ackedAt, e.ackNote, e.ackDueAt, e.overdueNotifiedAt, e.closedAt, e.closeReason, e.reopenCount,
                e.lastEvent, e.lastEventOutcome, truncate(e.lastEventError), e.updatedAt, expected + 1, e.episodeId(), e.tenantId(), expected);
        if (n != 1) {
            throw new ConcurrentEpisodeUpdateException(e.episodeId());
        }
        e.version = expected + 1;
    }

    /** Locks and returns the non-closed episode for (tenant, device, sensor, rule), if any. */
    Optional<AlarmEpisode> lockActive(Connection c, String tenant, String deviceId, String sensor, String ruleId) {
        List<AlarmEpisode> rows = Jdbc.query(c, dialect.lockingSelect(COLUMNS, "ALARM_EPISODE",
                        "tenant_id=? AND device_id=? AND sensor=? AND rule_id=? AND " + ACTIVE),
                AlarmEpisodeStore::map, tenant, deviceId, sensor, ruleId);
        if (rows.size() > 1) {
            // Cannot happen while every writer holds the device lock; refuse to guess and let an operator close one.
            throw new IllegalStateException("more than one active alarm episode for " + tenant + "/" + deviceId + "/" + sensor + "/" + ruleId);
        }
        return rows.stream().findFirst();
    }

    /**
     * Device time of the newest reading applied to any episode (open or closed) of (tenant, device, sensor, rule): a
     * reading not newer than this is a late or replayed one and must not start a new episode.
     */
    Optional<Instant> latestReadingAt(Connection c, String tenant, String deviceId, String sensor, String ruleId) {
        return Jdbc.one(c, "SELECT MAX(last_reading_at) AS m FROM ALARM_EPISODE WHERE tenant_id=? AND device_id=? AND sensor=?"
                        + " AND rule_id=?", rs -> Optional.ofNullable(Jdbc.instant(rs, "m")), tenant, deviceId, sensor, ruleId)
                .flatMap(o -> o);
    }

    /** Locks one episode by id. */
    Optional<AlarmEpisode> lock(Connection c, String tenant, String episodeId) {
        return Jdbc.one(c, dialect.lockingSelect(COLUMNS, "ALARM_EPISODE", "episode_id=? AND tenant_id=?"),
                AlarmEpisodeStore::map, episodeId, tenant);
    }

    public Optional<AlarmEpisode> get(Connection c, String tenant, String episodeId) {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM ALARM_EPISODE WHERE episode_id=? AND tenant_id=?",
                AlarmEpisodeStore::map, episodeId, tenant);
    }

    public Optional<AlarmEpisode> get(String tenant, String episodeId) {
        return tx.inTransaction(c -> get(c, tenant, episodeId));
    }

    /** Non-closed episodes of a tenant, oldest first (operator console). */
    public List<AlarmEpisode> listActive(String tenant) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT " + COLUMNS + " FROM ALARM_EPISODE WHERE tenant_id=? AND " + ACTIVE
                + " ORDER BY created_at, episode_id", AlarmEpisodeStore::map, tenant));
    }

    /** Episodes of one device, newest first, at most {@code limit}. */
    public List<AlarmEpisode> listByDevice(String tenant, String deviceId, int limit) {
        int max = Math.max(1, Math.min(limit, 1000));
        return tx.inTransaction(c -> {
            List<AlarmEpisode> all = Jdbc.query(c, "SELECT " + COLUMNS + " FROM ALARM_EPISODE WHERE tenant_id=? AND device_id=?"
                    + " ORDER BY created_at DESC, episode_id DESC", AlarmEpisodeStore::map, tenant, deviceId);
            return all.size() > max ? List.copyOf(all.subList(0, max)) : all;
        });
    }

    /** Open or cleared episodes whose acknowledgement deadline has passed (database clock) and nobody acknowledged. */
    public List<AlarmEpisode> overdueAcks(Connection c, String tenant) {
        return Jdbc.query(c, "SELECT " + COLUMNS + " FROM ALARM_EPISODE WHERE tenant_id=? AND state IN ('OPEN','CLEARED')"
                + " AND acked_at IS NULL AND ack_due_at IS NOT NULL AND ack_due_at <= " + dialect.now()
                + " ORDER BY ack_due_at, episode_id", AlarmEpisodeStore::map, tenant);
    }

    /** Claims (skip-locked) overdue episodes not yet notified, across tenants. */
    List<AlarmEpisode> claimOverdueUnnotified(Connection c, int limit) {
        return Jdbc.query(c, dialect.skipLockedSelect(qualified("e"), "ALARM_EPISODE", "e",
                        "e.state IN ('OPEN','CLEARED') AND e.acked_at IS NULL AND e.overdue_notified_at IS NULL"
                                + " AND e.ack_due_at IS NOT NULL AND e.ack_due_at <= " + dialect.now(), "e.ack_due_at", limit),
                AlarmEpisodeStore::map);
    }

    private static String qualified(String alias) {
        StringBuilder sb = new StringBuilder();
        for (String col : COLUMNS.split(",")) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(alias).append('.').append(col.trim());
        }
        return sb.toString();
    }

    static AlarmEpisode map(ResultSet rs) throws SQLException {
        AlarmEpisode e = new AlarmEpisode(rs.getString("episode_id"), rs.getString("tenant_id"), rs.getString("device_id"),
                rs.getString("sensor"), rs.getString("rule_id"));
        e.state = AlarmEpisode.State.valueOf(rs.getString("state"));
        e.severity = rs.getString("severity");
        e.breachCount = rs.getInt("breach_count");
        e.firstBreachAt = Jdbc.instant(rs, "first_breach_at");
        e.openedAt = Jdbc.instant(rs, "opened_at");
        e.lastValue = dec(rs.getString("last_no"));
        e.peakValue = dec(rs.getString("peak_value"));
        e.lastReadingAt = Jdbc.instant(rs, "last_reading_at");
        e.clearedAt = Jdbc.instant(rs, "cleared_at");
        e.ackedBy = rs.getString("acked_by");
        e.ackedAt = Jdbc.instant(rs, "acked_at");
        e.ackNote = rs.getString("ack_note");
        e.ackDueAt = Jdbc.instant(rs, "ack_due_at");
        e.overdueNotifiedAt = Jdbc.instant(rs, "overdue_notified_at");
        e.closedAt = Jdbc.instant(rs, "closed_at");
        e.closeReason = rs.getString("close_reason");
        e.reopenCount = rs.getInt("reopen_count");
        e.lastEvent = rs.getString("last_event");
        e.lastEventOutcome = rs.getString("last_event_outcome");
        e.lastEventError = rs.getString("last_event_error");
        e.createdAt = Jdbc.instant(rs, "created_at");
        e.updatedAt = Jdbc.instant(rs, "updated_at");
        e.version = rs.getLong("version");
        return e;
    }

    private static String dec(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 4000 ? s : s.substring(0, 4000);
    }
}
