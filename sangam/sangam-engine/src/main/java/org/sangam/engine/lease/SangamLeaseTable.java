// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.lease;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.operaton.bpm.engine.OptimisticLockingException;
import org.sangam.core.api.StaleLeaseException;
import org.sangam.core.api.StaleLeaseException.Reason;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.db.EngineDialect;

/**
 * The product fencing table {@code SANGAM_LEASE} (VI.3, R9, R20, FLOWABLE-INTO-OPERATON D-17, FLOWABLE-INTO-OPERATON D-18, FLOWABLE-INTO-OPERATON D-20), productised from the spike's
 * {@code SPIKE_LEASE} (W-8, W-9, W-12) and created by the Liquibase changelog, not by the adapter.
 *
 * <p><b>XI.10.4 fix: the write is idempotent per (work_id, lock_version) and serialized with the engine lock.</b>
 * <ul>
 *   <li>Every fetch of a topic first takes the per-topic row lock {@code SANGAM_FETCH_LOCK} in the same transaction as
 *       Operaton's fetch, so fetches of one topic never overlap ({@link #lockFetch}).</li>
 *   <li>A grant is a compare-and-set on the version read under the row lock:
 *       {@code UPDATE .. SET lock_version = v + 1 .. WHERE work_id = ? AND lock_version = v}. A second grant for the
 *       same version can never apply; zero updated rows raise an optimistic-locking conflict that rolls the whole
 *       fetch back (lease rows and engine locks together), and the fetch is retried. The first grant of a work item
 *       is a plain insert; a concurrent first use fails with a duplicate key, which is classified retryable.</li>
 * </ul>
 * Lease times are epoch milliseconds at the <b>engine clock</b>, the clock Operaton uses for {@code LOCK_EXP_TIME_},
 * so lease and engine lock agree. Nothing here commits: every call runs in the caller's engine command.
 */
public final class SangamLeaseTable {

    /** Lease states (VI.3.1). */
    public enum State { LOCKED, CANCEL_REQUESTED, AVAILABLE, HELD }

    /** A granted lease. */
    public record Lease(long lockVersion, String attemptId, int attemptNo) {
    }

    /** Identity stored with a lease. */
    public record Info(String tenantId, String topic, String rootId, UUID operationId, String capabilityClass,
                       String exclusiveScope) {
    }

    /** A stored lease row. */
    public record Row(String workId, String tenantId, String topic, String rootId, String operationId, long lockVersion,
                      String attemptId, int attemptNo, String workerId, Long expiresAtMs, State state,
                      boolean cancelRequested, String capabilityClass, String exclusiveScope) {
        public boolean held() {
            return state == State.LOCKED || state == State.CANCEL_REQUESTED;
        }
    }

    private static final String COLUMNS = "work_id, tenant_id, topic, root_id, operation_id, lock_version, attempt_id, "
            + "attempt_no, worker_id, expires_at_ms, state, cancel_requested, capability_class, exclusive_scope";

    private final EngineDialect dialect;
    private final Duration lockWait;

    public SangamLeaseTable(EngineDialect dialect, Duration lockWait) {
        this.dialect = dialect;
        this.lockWait = lockWait;
    }

    /** Per-topic fetch lock, created on first use (MySQL: no-op upsert, so a concurrent first use waits, not fails). */
    public void lockFetch(Connection c, String topic) {
        String select = dialect.lockingSelect("topic", "SANGAM_FETCH_LOCK", "topic = ?", lockWait);
        if (Jdbc.one(c, select, rs -> rs.getString(1), topic).isPresent()) {
            return;
        }
        dialect.insertIfAbsent(c, "SANGAM_FETCH_LOCK", new String[]{"topic"}, "topic", topic);
        Jdbc.one(c, select, rs -> rs.getString(1), topic)
                .orElseThrow(() -> new IllegalStateException("fetch lock row for " + topic + " not created"));
    }

    /** The lease row with an exclusive row lock, or null. */
    public Row lock(Connection c, String workId) {
        return Jdbc.one(c, dialect.lockingSelect(COLUMNS, "SANGAM_LEASE", "work_id = ?", lockWait),
                SangamLeaseTable::row, workId).orElse(null);
    }

    /** The lease row without a lock (root resolution before L2). */
    public Optional<Row> peek(Connection c, String workId) {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM SANGAM_LEASE WHERE work_id = ?", SangamLeaseTable::row, workId);
    }

    /**
     * Grants a new lease after an engine acquisition: version + 1 by compare-and-set on {@code previous} (read under
     * the row lock), a new attemptId, attempt number + 1.
     */
    public Lease grant(Connection c, String workId, Row previous, Info info, String workerId, long expiresAtMs,
                       long nowMs) {
        String attemptId = UUID.randomUUID().toString();
        String op = info.operationId() == null ? null : info.operationId().toString();
        if (previous == null) {
            Jdbc.update(c, "INSERT INTO SANGAM_LEASE (" + COLUMNS + ", updated_at_ms) VALUES (?,?,?,?,?,1,?,1,?,?,?,0,?,?,?)",
                    workId, info.tenantId(), info.topic(), info.rootId(), op, attemptId, workerId, expiresAtMs,
                    State.LOCKED.name(), info.capabilityClass(), info.exclusiveScope(), nowMs);
            return new Lease(1, attemptId, 1);
        }
        long next = previous.lockVersion() + 1;
        int attemptNo = previous.attemptNo() + 1;
        int n = Jdbc.update(c, "UPDATE SANGAM_LEASE SET lock_version = ?, attempt_id = ?, attempt_no = ?, worker_id = ?, "
                        + "expires_at_ms = ?, state = ?, cancel_requested = 0, tenant_id = ?, topic = ?, root_id = ?, "
                        + "operation_id = ?, capability_class = ?, exclusive_scope = ?, updated_at_ms = ? "
                        + "WHERE work_id = ? AND lock_version = ?",
                next, attemptId, attemptNo, workerId, expiresAtMs, State.LOCKED.name(), info.tenantId(), info.topic(),
                info.rootId(), op, info.capabilityClass(), info.exclusiveScope(), nowMs, workId, previous.lockVersion());
        if (n != 1) {
            throw new OptimisticLockingException("SANGAM_LEASE " + workId + ": version " + previous.lockVersion()
                    + " was already granted (XI.10.4 idempotent grant)");
        }
        return new Lease(next, attemptId, attemptNo);
    }

    /**
     * Checks that {@code workerId} holds the live lease {@code lockVersion} at {@code nowMs}, with a row lock.
     * Order: no lease, stale version, not owner, expired. Returns the locked row.
     */
    public Row check(Connection c, String workId, String workerId, long lockVersion, long nowMs) {
        Row r = lock(c, workId);
        if (r == null) {
            throw new StaleLeaseException(workId, Reason.NO_LEASE, "no lease was granted for this work");
        }
        if (r.lockVersion() != lockVersion) {
            throw new StaleLeaseException(workId, Reason.STALE_VERSION,
                    "lockVersion " + lockVersion + " is not the current version " + r.lockVersion());
        }
        if (!r.held() || r.workerId() == null || !r.workerId().equals(workerId)) {
            throw new StaleLeaseException(workId, Reason.NOT_OWNER,
                    workerId + " does not hold the lease (owner: " + r.workerId() + ", state " + r.state() + ")");
        }
        if (r.expiresAtMs() == null || r.expiresAtMs() <= nowMs) {
            throw new StaleLeaseException(workId, Reason.EXPIRED, "the lease of " + workerId + " expired at "
                    + (r.expiresAtMs() == null ? "?" : java.time.Instant.ofEpochMilli(r.expiresAtMs())));
        }
        return r;
    }

    /** Heartbeat: new expiry, same version. */
    public void extend(Connection c, String workId, long expiresAtMs, long nowMs) {
        Jdbc.update(c, "UPDATE SANGAM_LEASE SET expires_at_ms = ?, updated_at_ms = ? WHERE work_id = ?",
                expiresAtMs, nowMs, workId);
    }

    /** The lease ends without completing the work (fail, release, expiry): version + 1, no owner. */
    public void end(Connection c, String workId, State newState, long nowMs) {
        Jdbc.update(c, "UPDATE SANGAM_LEASE SET lock_version = lock_version + 1, attempt_id = NULL, worker_id = NULL, "
                + "expires_at_ms = NULL, cancel_requested = 0, state = ?, updated_at_ms = ? WHERE work_id = ?",
                newState.name(), nowMs, workId);
    }

    /** VI.3.1 LOCKED → CANCEL_REQUESTED; returns false when there is no live lease. */
    public boolean requestCancel(Connection c, String workId, long nowMs) {
        return Jdbc.update(c, "UPDATE SANGAM_LEASE SET state = ?, cancel_requested = 1, updated_at_ms = ? "
                + "WHERE work_id = ? AND state = ?", State.CANCEL_REQUESTED.name(), nowMs, workId, State.LOCKED.name()) == 1;
    }

    /** The work finished (complete, BPMN error) or its instance was deleted. */
    public void remove(Connection c, String workId) {
        Jdbc.update(c, "DELETE FROM SANGAM_LEASE WHERE work_id = ?", workId);
    }

    /**
     * R2: claims held leases with {@code expires_at_ms <= nowMs} with dialect-specific acquisition SQL, so concurrent
     * sweepers on several nodes never wait for each other and never deadlock (rows another sweeper holds are skipped):
     * <ul>
     *   <li>PostgreSQL / MySQL 8: {@code ... ORDER BY expires_at_ms LIMIT n FOR UPDATE SKIP LOCKED} on the
     *       {@code SANGAM_LEASE_EXPIRY (state, expires_at_ms)} index. MySQL lock footprint under READ COMMITTED: record
     *       locks on the returned rows only, no gap locks; skipped rows are not locked;</li>
     *   <li>SQL Server: {@code SELECT TOP (n) ... WITH (UPDLOCK, READPAST, ROWLOCK)}: an index seek on the same index
     *       (RCSI on), row locks only, so no lock escalation;</li>
     *   <li>Oracle: two-step, because ROWNUM / FETCH FIRST is applied before SKIP LOCKED: an unlocked candidate read,
     *       then {@code ... WHERE work_id = ? AND <condition> FOR UPDATE SKIP LOCKED} per candidate;</li>
     *   <li>H2 (development): two-step with a blocking row lock.</li>
     * </ul>
     * Runs in the sweep's own short command, which is marked as an acquisition and never takes L2 (VI.1 rule 4).
     */
    public List<Row> lockExpired(Connection c, long nowMs, int limit) {
        String cond = "state IN (?, ?) AND expires_at_ms <= ?";
        Object[] args = {State.LOCKED.name(), State.CANCEL_REQUESTED.name(), nowMs};
        List<String> claimed = switch (dialect) {
            case POSTGRESQL, MYSQL -> Jdbc.query(c, "SELECT work_id FROM SANGAM_LEASE WHERE " + cond
                    + " ORDER BY expires_at_ms LIMIT " + limit + " FOR UPDATE SKIP LOCKED", rs -> rs.getString(1), args);
            case SQLSERVER -> Jdbc.query(c, "SELECT TOP (" + limit + ") work_id FROM SANGAM_LEASE WITH (UPDLOCK, READPAST, "
                    + "ROWLOCK) WHERE " + cond + " ORDER BY expires_at_ms", rs -> rs.getString(1), args);
            case ORACLE -> {
                List<String> out = new ArrayList<>();
                for (String id : candidates(c, cond, args, limit)) {
                    Jdbc.one(c, "SELECT work_id FROM SANGAM_LEASE WHERE work_id = ? AND " + cond
                                    + " FOR UPDATE SKIP LOCKED", rs -> rs.getString(1), id, args[0], args[1], args[2])
                            .ifPresent(out::add);
                }
                yield out;
            }
            case H2 -> candidates(c, cond, args, limit);
        };
        List<String> candidates = claimed;
        List<Row> out = new ArrayList<>();
        for (String workId : candidates) {
            if (out.size() >= limit) {
                break;
            }
            Row r = lock(c, workId);
            if (r != null && r.held() && r.expiresAtMs() != null && r.expiresAtMs() <= nowMs) {
                out.add(r);
            }
        }
        return out;
    }

    /** Unlocked candidate read (first step of the two-step claim), oldest expiry first. */
    private List<String> candidates(Connection c, String cond, Object[] args, int limit) {
        List<String> ids = Jdbc.query(c, "SELECT work_id FROM SANGAM_LEASE WHERE " + cond + " ORDER BY expires_at_ms",
                rs -> rs.getString(1), args);
        return ids.size() > limit ? ids.subList(0, limit) : ids;
    }

    private static Row row(ResultSet rs) throws SQLException {
        long exp = rs.getLong(10);
        Long expiresAt = rs.wasNull() ? null : exp;
        return new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getLong(6), rs.getString(7), rs.getInt(8), rs.getString(9), expiresAt, State.valueOf(rs.getString(11)),
                rs.getInt(12) != 0, rs.getString(13), rs.getString(14));
    }
}
