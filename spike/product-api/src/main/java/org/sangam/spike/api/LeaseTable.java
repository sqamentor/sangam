package org.sangam.spike.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.sangam.spike.api.StaleLeaseException.Reason;

/**
 * Adapter-owned fencing table for worker leases (VI.3.2 rule 2), shared by every {@link EngineAdapter}
 * implementation so that both engines are fenced by exactly the same rules:
 *
 * <pre>
 * SPIKE_LEASE(work_id PK, lock_version, attempt_id, worker_id, expires_at)
 * SPIKE_FETCH_LOCK(topic PK)        -- one row per topic; serializes concurrent fetches of a topic
 * </pre>
 *
 * <p>{@code expires_at} is epoch milliseconds at the <b>engine clock</b> (a 64-bit integer, so there is no time-zone or
 * DST conversion). {@code worker_id}/{@code attempt_id}/{@code expires_at} are null while no lease is held.
 *
 * <p><b>Dialects (A-15).</b> The SQL is chosen per database, detected from JDBC metadata
 * ({@link Dialect#detect(Connection)}): PostgreSQL, Oracle (19c+; tested on 23 Free), SQL Server (2019+; tested on 2022)
 * and H2 (tests). Per dialect: create-if-missing DDL, the upsert used by {@link #grant}, and the row-lock clause.
 *
 * <p>Every method works on the connection of the engine command it is called from; the caller runs it in the same
 * engine command as the engine change (Q1), so the lease row and the engine state commit or roll back together.
 * Nothing here commits.
 */
public final class LeaseTable {

    /** A granted lease. */
    public record Lease(long lockVersion, String attemptId) {
    }

    /** A lease row as stored. {@code workerId == null} means no lease is held (released, failed or expired). */
    public record Row(String workId, long lockVersion, String attemptId, String workerId, Long expiresAtMillis) {
        public boolean held() {
            return workerId != null;
        }

        public Instant expiresAt() {
            return expiresAtMillis == null ? null : Instant.ofEpochMilli(expiresAtMillis);
        }
    }

    /** Database dialects with their own lease SQL. */
    public enum Dialect {
        POSTGRESQL, ORACLE, SQLSERVER, MYSQL, H2, GENERIC;

        /** Detects the dialect from {@link java.sql.DatabaseMetaData#getDatabaseProductName()}. */
        public static Dialect detect(Connection c) {
            try {
                return fromProductName(c.getMetaData().getDatabaseProductName());
            } catch (SQLException e) {
                throw new IllegalStateException("cannot read database metadata", e);
            }
        }

        public static Dialect fromProductName(String productName) {
            String p = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
            if (p.contains("postgres")) {
                return POSTGRESQL;
            }
            if (p.contains("oracle")) {
                return ORACLE;
            }
            if (p.contains("microsoft sql server") || p.contains("sql server")) {
                return SQLSERVER;
            }
            // MySQL Connector/J reports "MySQL"; MariaDB Connector/J reports "MySQL" or "MariaDB" by server.
            if (p.contains("mysql") || p.contains("mariadb")) {
                return MYSQL;
            }
            if (p.equals("h2") || p.startsWith("h2 ")) {
                return H2;
            }
            return GENERIC;
        }

        /** {@code select <columns> from <table> where <where>} with this dialect's exclusive row lock. */
        String selectForUpdate(String columns, String table, String where, String orderBy) {
            String order = orderBy == null ? "" : " order by " + orderBy;
            if (this == SQLSERVER) {
                return "select " + columns + " from " + table + " with (updlock, rowlock) where " + where + order;
            }
            return "select " + columns + " from " + table + " where " + where + order + " for update";
        }

        /** Create-if-missing DDL for one table; {@code columns} use the portable types below. */
        String createIfMissing(String table, String columns) {
            return switch (this) {
                case ORACLE -> "begin execute immediate 'create table " + table + " (" + columns
                        + ")'; exception when others then if sqlcode != -955 then raise; end if; end;";
                case SQLSERVER -> "if object_id('" + table + "', 'U') is null create table " + table + " (" + columns + ")";
                // InnoDB (row locks), case-sensitive ids. utf8mb4: varchar(255) key = 1020 bytes < 3072-byte limit.
                case MYSQL -> "create table if not exists " + table + " (" + columns + ")"
                        + " engine=InnoDB default charset=utf8mb4 collate=utf8mb4_bin";
                default -> "create table if not exists " + table + " (" + columns + ")";
            };
        }

        String bigint() {
            return this == ORACLE ? "number(19)" : "bigint";
        }

        String varchar(int n) {
            return this == ORACLE ? "varchar2(" + n + ")" : "varchar(" + n + ")";
        }
    }

    private static final String COLUMNS = "work_id, lock_version, attempt_id, worker_id, expires_at";

    private LeaseTable() {
    }

    /** Creates SPIKE_LEASE and SPIKE_FETCH_LOCK if they do not exist, with the dialect's DDL. */
    public static void createIfMissing(Connection c) {
        Dialect d = Dialect.detect(c);
        String lease = "work_id " + d.varchar(64) + " not null primary key, "
                + "lock_version " + d.bigint() + " not null, "
                + "attempt_id " + d.varchar(64) + ", "
                + "worker_id " + d.varchar(255) + ", "
                + "expires_at " + d.bigint();
        String fetchLock = "topic " + d.varchar(255) + " not null primary key";
        try (Statement s = c.createStatement()) {
            s.execute(d.createIfMissing("spike_lease", lease));
            s.execute(d.createIfMissing("spike_fetch_lock", fetchLock));
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create spike_lease on " + d, e);
        }
    }

    /**
     * Takes the per-topic fetch lock (an exclusive row lock on SPIKE_FETCH_LOCK, held until the engine command ends),
     * creating the row on first use. Two fetches of the same topic through any adapter on this database are therefore
     * serialized: the second one runs its engine query only after the first committed its engine locks and leases.
     * A concurrent first use may fail with a duplicate key; the caller retries the whole command.
     */
    public static void lockFetch(Connection c, String topic) {
        Dialect d = Dialect.detect(c);
        try (PreparedStatement ps = c.prepareStatement(d.selectForUpdate("topic", "spike_fetch_lock", "topic = ?", null))) {
            ps.setString(1, topic);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        if (d == Dialect.MYSQL) {
            // A no-op upsert: a concurrent first use waits on the other inserter's row lock and then holds the row lock
            // itself, instead of failing with duplicate key 1062 (SQLState 23000, not in every adapter's retry list).
            update(c, "insert into spike_fetch_lock (topic) values (?) on duplicate key update topic = topic", topic);
            return;
        }
        update(c, "insert into spike_fetch_lock (topic) values (?)", topic);
    }

    /** The lease row with an exclusive row lock, or {@code null} if there is none. */
    public static Row lock(Connection c, String workId) {
        Dialect d = Dialect.detect(c);
        try (PreparedStatement ps = c.prepareStatement(d.selectForUpdate(COLUMNS, "spike_lease", "work_id = ?", null))) {
            ps.setString(1, workId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * New lease after an acquisition: lock_version + 1 (1 for new work), new attemptId. One dialect upsert
     * (PostgreSQL {@code INSERT … ON CONFLICT}, MySQL {@code INSERT … ON DUPLICATE KEY UPDATE}, Oracle/SQL Server/H2
     * {@code MERGE}), so a grant never fails with a
     * duplicate key when the row already exists.
     */
    public static Lease grant(Connection c, String workId, String workerId, Instant expiresAt) {
        String attemptId = UUID.randomUUID().toString();
        long exp = expiresAt.toEpochMilli();
        Dialect d = Dialect.detect(c);
        // MERGE: target alias t, source alias s (Oracle, SQL Server, H2)
        String mergeSet = "lock_version = t.lock_version + 1, attempt_id = s.attempt_id, "
                + "worker_id = s.worker_id, expires_at = s.expires_at";
        String mergeInsert = "when not matched then insert (" + COLUMNS + ") "
                + "values (s.work_id, 1, s.attempt_id, s.worker_id, s.expires_at)";
        switch (d) {
            case POSTGRESQL -> update(c, "insert into spike_lease (" + COLUMNS + ") values (?, 1, ?, ?, ?) "
                            + "on conflict (work_id) do update set lock_version = spike_lease.lock_version + 1, "
                            + "attempt_id = excluded.attempt_id, worker_id = excluded.worker_id, "
                            + "expires_at = excluded.expires_at",
                    workId, attemptId, workerId, exp);
            case ORACLE -> update(c, "merge into spike_lease t using (select ? work_id, ? attempt_id, ? worker_id, "
                            + "? expires_at from dual) s on (t.work_id = s.work_id) "
                            + "when matched then update set " + mergeSet + " " + mergeInsert,
                    workId, attemptId, workerId, exp);
            case SQLSERVER -> update(c, "merge spike_lease with (holdlock) as t using (select ? as work_id, "
                            + "? as attempt_id, ? as worker_id, cast(? as bigint) as expires_at) as s "
                            + "on t.work_id = s.work_id "
                            + "when matched then update set " + mergeSet + " " + mergeInsert + ";",
                    workId, attemptId, workerId, exp);
            // MySQL: values re-bound instead of VALUES(col) (deprecated since 8.0.20) or a row alias (not on MariaDB).
            case MYSQL -> update(c, "insert into spike_lease (" + COLUMNS + ") values (?, 1, ?, ?, ?) "
                            + "on duplicate key update lock_version = lock_version + 1, attempt_id = ?, worker_id = ?, "
                            + "expires_at = ?",
                    workId, attemptId, workerId, exp, attemptId, workerId, exp);
            case H2 -> update(c, "merge into spike_lease t using (select cast(? as varchar(64)) work_id, "
                            + "cast(? as varchar(64)) attempt_id, cast(? as varchar(255)) worker_id, "
                            + "cast(? as bigint) expires_at) s on t.work_id = s.work_id "
                            + "when matched then update set " + mergeSet + " " + mergeInsert,
                    workId, attemptId, workerId, exp);
            default -> {
                Row current = lock(c, workId);
                if (current == null) {
                    update(c, "insert into spike_lease (" + COLUMNS + ") values (?, 1, ?, ?, ?)",
                            workId, attemptId, workerId, exp);
                } else {
                    update(c, "update spike_lease set lock_version = lock_version + 1, attempt_id = ?, worker_id = ?, "
                            + "expires_at = ? where work_id = ?", attemptId, workerId, exp, workId);
                }
            }
        }
        Row granted = lock(c, workId);
        if (granted == null || !attemptId.equals(granted.attemptId())) {
            throw new IllegalStateException("lease grant for " + workId + " was not applied");
        }
        return new Lease(granted.lockVersion(), attemptId);
    }

    /**
     * Checks that {@code workerId} holds the live lease {@code lockVersion} on {@code workId} at {@code now}, with a
     * row lock, and throws {@link StaleLeaseException} otherwise. Order: no lease, stale version, not owner, expired.
     */
    public static void check(Connection c, String workId, String workerId, long lockVersion, Instant now) {
        Row r = lock(c, workId);
        if (r == null) {
            throw new StaleLeaseException(workId, Reason.NO_LEASE, "no lease was granted for this work");
        }
        if (r.lockVersion() != lockVersion) {
            throw new StaleLeaseException(workId, Reason.STALE_VERSION,
                    "lockVersion " + lockVersion + " is not the current version " + r.lockVersion());
        }
        if (r.workerId() == null || !r.workerId().equals(workerId)) {
            throw new StaleLeaseException(workId, Reason.NOT_OWNER,
                    workerId + " does not hold the lease (owner: " + r.workerId() + ")");
        }
        if (r.expiresAtMillis() == null || r.expiresAtMillis() <= now.toEpochMilli()) {
            throw new StaleLeaseException(workId, Reason.EXPIRED,
                    "the lease of " + workerId + " expired at " + (r.expiresAtMillis() == null ? "?" : r.expiresAt()));
        }
    }

    /** Heartbeat: new expiry, same lock_version. */
    public static void extend(Connection c, String workId, Instant expiresAt) {
        update(c, "update spike_lease set expires_at = ? where work_id = ?", expiresAt.toEpochMilli(), workId);
    }

    /** The lease ends without completing the work (fail, release, expiry): lock_version + 1, no owner. */
    public static void end(Connection c, String workId) {
        update(c, "update spike_lease set lock_version = lock_version + 1, attempt_id = null, worker_id = null, "
                + "expires_at = null where work_id = ?", workId);
    }

    /** The work is finished (complete, BPMN error) or its instance was deleted: the row is removed. */
    public static void remove(Connection c, String workId) {
        update(c, "delete from spike_lease where work_id = ?", workId);
    }

    /**
     * Held leases with {@code expires_at <= now}, each row-locked and re-checked under the lock, in work_id order (a
     * fixed lock order, so two sweeps cannot deadlock each other). Candidates are found with a plain read and then
     * locked by primary key, so no locking range scan runs (SQL Server would hold update locks on every scanned row).
     * The caller decides per row (expiry policy, A-10) and then calls {@link #end}.
     */
    public static List<Row> lockExpired(Connection c, Instant now) {
        List<String> candidates = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select work_id from spike_lease "
                + "where worker_id is not null and expires_at <= ? order by work_id")) {
            ps.setLong(1, now.toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        List<Row> out = new ArrayList<>(candidates.size());
        for (String workId : candidates) {
            Row r = lock(c, workId);
            if (r != null && r.held() && r.expiresAtMillis() != null && r.expiresAtMillis() <= now.toEpochMilli()) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Expiry sweep without a policy: every held lease with expires_at &lt;= now ends with lock_version + 1. Returns the
     * row count. Kept for callers that apply no expiry policy (Flowable adapter, experiments).
     */
    public static int expire(Connection c, Instant now) {
        return update(c, "update spike_lease set lock_version = lock_version + 1, attempt_id = null, worker_id = null, "
                + "expires_at = null where worker_id is not null and expires_at <= ?", now.toEpochMilli());
    }

    private static Row row(ResultSet rs) throws SQLException {
        String workId = rs.getString(1);
        long version = rs.getLong(2);
        String attempt = rs.getString(3);
        String worker = rs.getString(4);
        long exp = rs.getLong(5);
        Long expiresAt = rs.wasNull() ? null : exp;
        return new Row(workId, version, attempt, worker, expiresAt);
    }

    private static int update(Connection c, String sql, Object... args) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
