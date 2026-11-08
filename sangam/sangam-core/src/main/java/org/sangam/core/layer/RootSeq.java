package org.sangam.core.layer;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * The per-root counter row ({@code ROOT_SEQ}), the spike's stand-in for the {@code RT_ROOT} columns
 * {@code next_outbox_seq} (VI.2.2) and {@code audit_seq} plus chain head (X.2.3.3).
 *
 * <p>Locking it is the L2 surrogate: every command that appends outbox or audit rows for a root holds this row
 * lock until commit, so {@code root_seq} and audit {@code seq} order equal commit order per root, with no gaps.
 * Callers must take it before any L4 lock (VI.1 rule 1) and, for several roots, in ascending id order.
 *
 * <p><b>Round trips (SG-15 T-SCALE).</b> On PostgreSQL the create-lock-read of the row is one statement,
 * {@code INSERT .. ON CONFLICT (tenant_id, root_id) DO UPDATE .. RETURNING}: the conflict arm locks the existing row
 * exactly like {@code SELECT .. FOR UPDATE} (it waits for a concurrent holder, then sees its committed version, READ
 * COMMITTED), the insert arm creates and locks a new row. Before, the same work took an insert-if-absent and a
 * locking select (two round trips), and an outbox allocation a third ({@code UPDATE next_outbox_seq}). Other
 * databases (and H2, which has no {@code DO UPDATE .. RETURNING}) keep the two-step form.
 */
final class RootSeq {

    record Row(long nextOutboxSeq, long nextAuditSeq, byte[] auditHeadHash) {
    }

    /** A locked row plus the transaction's database time (PostgreSQL {@code CURRENT_TIMESTAMP} is per transaction). */
    record Locked(Row row, Instant dbNow) {
    }

    private static final String UPSERT = "INSERT INTO ROOT_SEQ(tenant_id, root_id, next_outbox_seq, next_audit_seq, audit_head_hash)"
            + " VALUES (?,?,?,1,NULL) ON CONFLICT (tenant_id, root_id) DO UPDATE SET next_outbox_seq = ";

    private RootSeq() {
    }

    static Row lock(Connection c, Dialect d, String tenant, String rootId) {
        if (d == Dialect.MYSQL) {
            return lockMysql(c, d, tenant, rootId);
        }
        if (onePass(c, d)) {
            return lockAndNow(c, d, tenant, rootId).row();
        }
        Jdbc.insertIfAbsent(c, d,
                "INSERT INTO ROOT_SEQ(tenant_id, root_id, next_outbox_seq, next_audit_seq, audit_head_hash) VALUES (?,?,1,1,NULL)",
                tenant, rootId);
        return Jdbc.one(c, d.lockingSelect("next_outbox_seq, next_audit_seq, audit_head_hash", "ROOT_SEQ", "tenant_id=? AND root_id=?"),
                rs -> new Row(rs.getLong(1), rs.getLong(2), rs.getBytes(3)), tenant, rootId).orElseThrow();
    }

    /**
     * Locks the row (creating it on first use) and returns it with the database time, in one round trip on
     * PostgreSQL, in three elsewhere (lock as {@link #lock}, then the clock).
     */
    static Locked lockAndNow(Connection c, Dialect d, String tenant, String rootId) {
        if (onePass(c, d)) {
            return Jdbc.one(c, UPSERT + "ROOT_SEQ.next_outbox_seq RETURNING next_outbox_seq, next_audit_seq, audit_head_hash, "
                            + d.now() + " AS n",
                    rs -> new Locked(new Row(rs.getLong(1), rs.getLong(2), rs.getBytes(3)), Jdbc.instant(rs, "n")),
                    tenant, rootId, 1L).orElseThrow();
        }
        Row row = lock(c, d, tenant, rootId);
        return new Locked(row, Jdbc.dbNow(c, d));
    }

    /**
     * MySQL: lock first, create only when missing, with a no-op upsert. InnoDB takes a SHARED lock on the existing
     * row when an INSERT fails with duplicate key 1062; two such transactions that then ask for the exclusive lock
     * ({@code FOR UPDATE}) deadlock each other (measured: 1213 with 6 concurrent appenders on one root).
     * {@code INSERT .. ON DUPLICATE KEY UPDATE} takes the exclusive lock directly.
     */
    private static Row lockMysql(Connection c, Dialect d, String tenant, String rootId) {
        String select = d.lockingSelect("next_outbox_seq, next_audit_seq, audit_head_hash", "ROOT_SEQ", "tenant_id=? AND root_id=?");
        Jdbc.RowMapper<Row> map = rs -> new Row(rs.getLong(1), rs.getLong(2), rs.getBytes(3));
        var row = Jdbc.one(c, select, map, tenant, rootId);
        if (row.isPresent()) {
            return row.get();
        }
        Jdbc.update(c, "INSERT INTO ROOT_SEQ(tenant_id, root_id, next_outbox_seq, next_audit_seq, audit_head_hash)"
                + " VALUES (?,?,1,1,NULL) ON DUPLICATE KEY UPDATE next_outbox_seq = next_outbox_seq", tenant, rootId);
        return Jdbc.one(c, select, map, tenant, rootId).orElseThrow();
    }

    /** Allocates the next {@code root_seq} under the row lock. */
    static long nextOutboxSeq(Connection c, Dialect d, String tenant, String rootId) {
        if (onePass(c, d)) {
            // new row: stored next is 2, allocated 1; existing row: stored next+1, allocated the old next
            return Jdbc.one(c, UPSERT + "ROOT_SEQ.next_outbox_seq + 1 RETURNING next_outbox_seq - 1",
                    rs -> rs.getLong(1), tenant, rootId, 2L).orElseThrow();
        }
        long seq = lock(c, d, tenant, rootId).nextOutboxSeq();
        Jdbc.update(c, "UPDATE ROOT_SEQ SET next_outbox_seq=? WHERE tenant_id=? AND root_id=?", seq + 1, tenant, rootId);
        return seq;
    }

    static void advanceAudit(Connection c, String tenant, String rootId, long nextSeq, byte[] headHash) {
        Jdbc.update(c, "UPDATE ROOT_SEQ SET next_audit_seq=?, audit_head_hash=? WHERE tenant_id=? AND root_id=?",
                nextSeq, headHash, tenant, rootId);
    }

    /**
     * Whether the single-statement forms apply: the PostgreSQL dialect on a real PostgreSQL server. H2 in
     * PostgreSQL mode shares the dialect but has neither {@code DO UPDATE .. RETURNING} nor writable CTEs. The product
     * name is client-side driver metadata (no round trip).
     */
    static boolean onePass(Connection c, Dialect d) {
        if (d != Dialect.POSTGRESQL) {
            return false;
        }
        try {
            return "PostgreSQL".equals(c.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException("database product name", e);
        }
    }
}
