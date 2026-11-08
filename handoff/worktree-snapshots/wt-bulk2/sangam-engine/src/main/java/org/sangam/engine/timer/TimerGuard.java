// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.timer;

import java.sql.Connection;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;

import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.impl.persistence.entity.TimerEntity;
import org.sangam.core.engine.TimerCatchUpPolicy;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.db.EngineDialect;

/**
 * <b>DST timer guard (A-12, R31, FLOWABLE-INTO-OPERATON D-01, FLOWABLE-INTO-OPERATON D-30).</b> One row per timer series in {@code SANGAM_TIMER_GUARD}, written in
 * the job's own transaction:
 * <ul>
 *   <li>{@code last_fired_interval}: the interval of the last firing ({@link CycleExpression#intervalOf}). A firing
 *       whose interval is not later is <b>suppressed</b> (its follow-up timer is still scheduled by the engine), so a
 *       repeated local hour or an engine-computed due date that falls back can never fire twice;</li>
 *   <li>the catch-up policy: {@link TimerCatchUpPolicy#SKIP_MISSED} suppresses a firing that is more than one period
 *       late; {@code FIRE_ONCE} fires it once; {@code FIRE_ALL} fires every missed slot (the calendar then schedules
 *       the next slot after the fired one, bounded by {@code maxCatchUpFires});</li>
 *   <li>{@code fire_count}: firings so far, which bounds {@code Rn} to n firings in total (FLOWABLE-INTO-OPERATON D-29).</li>
 * </ul>
 * A series is {@code jobDefinitionId:processInstanceId} (or {@code jobDefinitionId:start} for timer start events).
 */
public final class TimerGuard {

    /** Guard outcome. */
    public enum Verdict { FIRE, SUPPRESS_DUPLICATE_INTERVAL, SUPPRESS_MISSED }

    private static final String[] COLS = {"series_key", "tenant_id", "process_definition_key", "activity_id",
            "last_fired_interval", "last_fired_due_ms", "fire_count", "suppressed_count", "catch_up_policy",
            "updated_at_ms"};

    private final EngineDialect dialect;
    private final Duration lockWait;
    private final ZoneId localZone;
    private final ZoneId cadenceZone;

    public TimerGuard(EngineDialect dialect, Duration lockWait, ZoneId localZone, ZoneId cadenceZone) {
        this.dialect = dialect;
        this.lockWait = lockWait;
        this.localZone = localZone;
        this.cadenceZone = cadenceZone;
    }

    public static String seriesKey(JobEntity job) {
        return job.getJobDefinitionId() + ":" + (job.getProcessInstanceId() == null ? "start" : job.getProcessInstanceId());
    }

    private record Guard(long lastInterval, long fireCount) {
    }

    /** Firings recorded so far for the series (read without a lock; for the calendar's {@code Rn} bound). */
    public long fireCount(Connection c, String seriesKey) {
        return Jdbc.one(c, "SELECT fire_count FROM SANGAM_TIMER_GUARD WHERE series_key = ?", rs -> rs.getLong(1), seriesKey)
                .orElse(0L);
    }

    /** Decides whether {@code timer} fires now and records the decision (row-locked). */
    public Verdict decide(Connection c, TimerEntity timer, TimerCatchUpPolicy policy, long nowMs) {
        // ISO repeating interval: UTC grid or wall-clock interval; cron: the local wall-clock minute.
        CycleExpression cycle = CycleExpression.isRepeating(timer.getRepeat())
                ? CycleExpression.parse(timer.getRepeat(), localZone) : null;
        long dueMs = timer.getDuedate() == null ? nowMs : timer.getDuedate().getTime();
        java.time.Instant due = java.time.Instant.ofEpochMilli(dueMs);
        long interval = cycle != null ? cycle.intervalOf(due, cadenceZone)
                : due.atZone(cadenceZone).toLocalDateTime().toEpochSecond(java.time.ZoneOffset.UTC) / 60;
        String key = seriesKey(timer);
        String select = dialect.lockingSelect("last_fired_interval, fire_count", "SANGAM_TIMER_GUARD",
                "series_key = ?", lockWait);
        Optional<Guard> g = Jdbc.one(c, select, rs -> new Guard(rs.getLong(1), rs.getLong(2)), key);
        if (g.isEmpty()) {
            dialect.insertIfAbsent(c, "SANGAM_TIMER_GUARD", COLS, "updated_at_ms", key, timer.getTenantId(),
                    timer.getProcessDefinitionKey(), timer.getActivityId(), Long.MIN_VALUE, -1L, 0L, 0L, policy.name(), nowMs);
            g = Jdbc.one(c, select, rs -> new Guard(rs.getLong(1), rs.getLong(2)), key);
        }
        Guard guard = g.orElseThrow();
        if (interval <= guard.lastInterval()) {
            suppressed(c, key, guard.lastInterval(), policy, nowMs);
            return Verdict.SUPPRESS_DUPLICATE_INTERVAL;
        }
        long period = cycle != null ? cycle.nominalPeriodMillis() : 0;
        if (policy == TimerCatchUpPolicy.SKIP_MISSED && period > 0 && nowMs - dueMs > period) {
            suppressed(c, key, interval, policy, nowMs);
            return Verdict.SUPPRESS_MISSED;
        }
        Jdbc.update(c, "UPDATE SANGAM_TIMER_GUARD SET last_fired_interval = ?, last_fired_due_ms = ?, "
                        + "fire_count = fire_count + 1, catch_up_policy = ?, updated_at_ms = ? WHERE series_key = ?",
                interval, dueMs, policy.name(), nowMs, key);
        return Verdict.FIRE;
    }

    private void suppressed(Connection c, String key, long interval, TimerCatchUpPolicy policy, long nowMs) {
        Jdbc.update(c, "UPDATE SANGAM_TIMER_GUARD SET last_fired_interval = ?, suppressed_count = suppressed_count + 1, "
                + "catch_up_policy = ?, updated_at_ms = ? WHERE series_key = ?", interval, policy.name(), nowMs, key);
    }

    public ZoneId localZone() {
        return localZone;
    }

    public ZoneId cadenceZone() {
        return cadenceZone;
    }
}
