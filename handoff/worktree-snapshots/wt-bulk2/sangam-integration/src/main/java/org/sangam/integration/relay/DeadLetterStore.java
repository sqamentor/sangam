// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Dead-letter store ({@code INTEGRATION_DEAD_LETTER}) for poison outbox deliveries (PARKED/SKIPPED, VI.2.3 rule 5)
 * and expired inbox messages (VI.2.6 rule 3, "optional dead-letter channel"). It is the operator's work list behind
 * {@code /ops/outbox}: each entry can be requeued or discarded, and both repairs are audited by the caller.
 * The source rows stay where they are (the outbox delivery stays PARKED/SKIPPED, the inbox row EXPIRED); the dead
 * letter is the index plus a copy of the payload, scrubbed by erasure.
 */
public final class DeadLetterStore {

    public enum Kind { OUTBOX, INBOX }

    public enum State { OPEN, REQUEUED, DISCARDED }

    public record Entry(long id, String tenantId, Kind kind, String refId, String destination, String rootId, String eventType,
                        String payload, String error, State state, Instant createdAt) {
    }

    private final Dialect dialect;
    private final Transactions tx;

    public DeadLetterStore(Dialect dialect, Transactions tx) {
        this.dialect = dialect;
        this.tx = tx;
    }

    /** Records one dead letter; an OPEN entry with the same (kind, refId) is reused. */
    public long record(Connection c, String tenantId, Kind kind, String refId, String destination, String rootId,
                       String eventType, String payload, String error) {
        Optional<Long> existing = Jdbc.one(c, "SELECT id FROM INTEGRATION_DEAD_LETTER WHERE kind=? AND ref_id=? AND state='OPEN'",
                rs -> rs.getLong(1), kind, refId);
        if (existing.isPresent()) {
            Jdbc.update(c, "UPDATE INTEGRATION_DEAD_LETTER SET error=? WHERE id=?", truncate(error), existing.get());
            return existing.get();
        }
        return Jdbc.insertReturningId(c, dialect,
                "INSERT INTO INTEGRATION_DEAD_LETTER(tenant_id, kind, ref_id, destination, root_id, event_type, payload, error, state,"
                        + " created_at) VALUES (?,?,?,?,?,?,?,?,'OPEN'," + dialect.now() + ")",
                "id", tenantId, kind, refId, destination, rootId, eventType, payload, truncate(error));
    }

    public long record(String tenantId, Kind kind, String refId, String destination, String rootId, String eventType,
                       String payload, String error) {
        return tx.inTransaction(c -> record(c, tenantId, kind, refId, destination, rootId, eventType, payload, error));
    }

    public List<Entry> listOpen(String tenantId, int limit) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT * FROM INTEGRATION_DEAD_LETTER WHERE tenant_id=? AND state='OPEN' ORDER BY id",
                DeadLetterStore::map, tenantId).stream().limit(limit).toList());
    }

    public Optional<Entry> get(Connection c, long id) {
        return Jdbc.one(c, "SELECT * FROM INTEGRATION_DEAD_LETTER WHERE id=?", DeadLetterStore::map, id);
    }

    /** Marks an OPEN entry resolved; false when it was not open. */
    public boolean resolve(Connection c, long id, State target, String actor, String reason) {
        return Jdbc.update(c, "UPDATE INTEGRATION_DEAD_LETTER SET state=?, resolved_by=?, resolution=?, resolved_at=" + dialect.now()
                + " WHERE id=? AND state='OPEN'", target, actor, reason, id) == 1;
    }

    /** Erasure: scrubs payloads of the given roots. */
    public int erase(Connection c, String tenantId, List<String> rootIds) {
        int n = 0;
        for (String r : rootIds) {
            n += Jdbc.update(c, "UPDATE INTEGRATION_DEAD_LETTER SET payload=NULL WHERE tenant_id=? AND root_id=?", tenantId, r);
        }
        return n;
    }

    public int countOpen(Connection c) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM INTEGRATION_DEAD_LETTER WHERE state='OPEN'", rs -> rs.getInt(1)).orElse(0);
    }

    private static Entry map(ResultSet rs) throws SQLException {
        return new Entry(rs.getLong("id"), rs.getString("tenant_id"), Kind.valueOf(rs.getString("kind")), rs.getString("ref_id"),
                rs.getString("destination"), rs.getString("root_id"), rs.getString("event_type"), rs.getString("payload"),
                rs.getString("error"), State.valueOf(rs.getString("state")), Jdbc.instant(rs, "created_at"));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 4000 ? s : s.substring(0, 4000);
    }
}
