package org.sangam.core.layer;

import java.sql.Connection;

/**
 * The per-root counter row ({@code ROOT_SEQ}), the spike's stand-in for the {@code RT_ROOT} columns
 * {@code next_outbox_seq} (VI.2.2) and {@code audit_seq} plus chain head (X.2.3.3).
 *
 * <p>Locking it is the L2 surrogate: every command that appends outbox or audit rows for a root holds this row
 * lock until commit, so {@code root_seq} and audit {@code seq} order equal commit order per root, with no gaps.
 * Callers must take it before any L4 lock (VI.1 rule 1) and, for several roots, in ascending id order.
 */
final class RootSeq {

    record Row(long nextOutboxSeq, long nextAuditSeq, byte[] auditHeadHash) {
    }

    private RootSeq() {
    }

    static Row lock(Connection c, Dialect d, String tenant, String rootId) {
        if (d == Dialect.MYSQL) {
            return lockMysql(c, d, tenant, rootId);
        }
        Jdbc.insertIfAbsent(c, d,
                "INSERT INTO ROOT_SEQ(tenant_id, root_id, next_outbox_seq, next_audit_seq, audit_head_hash) VALUES (?,?,1,1,NULL)",
                tenant, rootId);
        return Jdbc.one(c, d.lockingSelect("next_outbox_seq, next_audit_seq, audit_head_hash", "ROOT_SEQ", "tenant_id=? AND root_id=?"),
                rs -> new Row(rs.getLong(1), rs.getLong(2), rs.getBytes(3)), tenant, rootId).orElseThrow();
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
        long seq = lock(c, d, tenant, rootId).nextOutboxSeq();
        Jdbc.update(c, "UPDATE ROOT_SEQ SET next_outbox_seq=? WHERE tenant_id=? AND root_id=?", seq + 1, tenant, rootId);
        return seq;
    }

    static void advanceAudit(Connection c, String tenant, String rootId, long nextSeq, byte[] headHash) {
        Jdbc.update(c, "UPDATE ROOT_SEQ SET next_audit_seq=?, audit_head_hash=? WHERE tenant_id=? AND root_id=?",
                nextSeq, headHash, tenant, rootId);
    }
}
