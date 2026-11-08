package org.sangam.core.layer;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal class-B audit hash chain per root (SANGAM.md X.2.3.3–X.2.3.4).
 *
 * <ul>
 *   <li>{@code chain_id = root_id}; {@code seq} is gapless, allocated under the root row lock (L2 surrogate), so
 *       {@code seq} order equals commit order. The tenant security chain uses {@code chain_id = "sec:" + tenant}.</li>
 *   <li>{@code payload_digest = SHA-256(salt || JCS(payload))}; the payload and salt live in {@code AUDIT_PAYLOAD},
 *       outside the chain, so erasure (delete salt + payload) keeps the chain verifiable.</li>
 *   <li>{@code hash_n = SHA-256(prev_hash_{n-1} || JCS(envelope_n))}, the envelope including {@code payload_digest}
 *       and {@code prev_hash} and excluding {@code hash}; genesis
 *       {@code prev_hash_0 = SHA-256("sangam-audit-v1:" || tenant || ":" || chain)}.</li>
 *   <li>Written synchronously in the command transaction (L5 insert), never through the outbox.</li>
 * </ul>
 * Not implemented in the spike: signed Merkle checkpoints and their export (X.2.3.4 rule 4). Without them a DBA
 * who rewrites a whole chain <i>and</i> the {@code ROOT_SEQ} head is not detected.
 */
public final class AuditChain {

    public record AuditEvent(String eventId, String chainId, long seq, byte[] hash) {
    }

    public record VerifyResult(boolean ok, long eventsChecked, Long firstBadSeq, String reason) {
        static VerifyResult good(long n) {
            return new VerifyResult(true, n, null, null);
        }

        static VerifyResult bad(long n, long seq, String reason) {
            return new VerifyResult(false, n, seq, reason);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Dialect dialect;

    public AuditChain(Dialect dialect) {
        this.dialect = dialect;
    }

    public static byte[] genesis(String tenant, String chainId) {
        return Hashes.sha256("sangam-audit-v1:" + tenant + ":" + chainId);
    }

    /** Appends one event to the root's chain inside the caller's transaction. */
    public AuditEvent append(Connection c, String tenant, String rootId, String eventType, Map<String, ?> payload) {
        RootSeq.Locked locked = RootSeq.lockAndNow(c, dialect, tenant, rootId);   // one round trip on PostgreSQL
        RootSeq.Row head = locked.row();
        long seq = head.nextAuditSeq();
        byte[] prev = seq == 1 ? genesis(tenant, rootId) : head.auditHeadHash();
        Instant occurredAt = locked.dbNow().truncatedTo(ChronoUnit.MICROS);
        String eventId = Hashes.uuidV7().toString();
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String payloadText = CanonicalJson.write(payload == null ? Map.of() : payload);
        byte[] payloadDigest = Hashes.sha256(salt, payloadText.getBytes(StandardCharsets.UTF_8));
        String envelope = envelope(eventId, tenant, rootId, seq, eventType, 1, occurredAt, payloadDigest, prev);
        byte[] hash = Hashes.sha256(prev, envelope.getBytes(StandardCharsets.UTF_8));

        String insertEvent = "INSERT INTO AUDIT_EVENT(tenant_id, chain_id, seq, event_id, event_type, schema_version, occurred_at,"
                + " envelope, payload_digest, prev_hash, hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        String insertPayload = "INSERT INTO AUDIT_PAYLOAD(event_id, salt, payload) VALUES (?,?,?)";
        if (RootSeq.onePass(c, dialect)) {
            // SG-15: the event, its payload and the new chain head in one statement (writable CTEs run in full,
            // atomically, in the caller's transaction; a key violation fails the whole statement as before)
            int n = Jdbc.update(c, "WITH e AS (" + insertEvent + "), p AS (" + insertPayload + ")"
                            + " UPDATE ROOT_SEQ SET next_audit_seq=?, audit_head_hash=? WHERE tenant_id=? AND root_id=?",
                    tenant, rootId, seq, eventId, eventType, 1, occurredAt, envelope, payloadDigest, prev, hash,
                    eventId, salt, payloadText, seq + 1, hash, tenant, rootId);
            if (n != 1) {
                throw new IllegalStateException("audit head of " + rootId + " not advanced");
            }
            return new AuditEvent(eventId, rootId, seq, hash);
        }
        Jdbc.update(c, insertEvent, tenant, rootId, seq, eventId, eventType, 1, occurredAt, envelope, payloadDigest, prev, hash);
        Jdbc.update(c, insertPayload, eventId, salt, payloadText);
        RootSeq.advanceAudit(c, tenant, rootId, seq + 1, hash);
        return new AuditEvent(eventId, rootId, seq, hash);
    }

    /** Erasure (X.2.3.8): deletes the payload and its salt; the chain still verifies. */
    public void erasePayload(Connection c, String eventId, String reason) {
        Jdbc.update(c, "UPDATE AUDIT_PAYLOAD SET salt=NULL, payload=NULL, erased_at=" + dialect.now() + ", erase_reason=? WHERE event_id=?",
                reason, eventId);
    }

    /**
     * Recomputes the chain: gapless {@code seq}, each {@code prev_hash} link, each envelope rebuilt from its columns,
     * each hash, each non-erased payload digest, and the chain head stored on {@code ROOT_SEQ}.
     * Reports the first divergent event.
     */
    public VerifyResult verify(Connection c, String tenant, String rootId) {
        record Row(long seq, String eventId, String type, int schemaVersion, Instant occurredAt, String envelope,
                   byte[] payloadDigest, byte[] prevHash, byte[] hash, byte[] salt, String payload, Instant erasedAt) {
        }
        List<Row> rows = Jdbc.query(c,
                "SELECT e.seq, e.event_id, e.event_type, e.schema_version, e.occurred_at, e.envelope, e.payload_digest, e.prev_hash, e.hash,"
                        + " p.salt, p.payload, p.erased_at FROM AUDIT_EVENT e LEFT JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                        + " WHERE e.tenant_id=? AND e.chain_id=? ORDER BY e.seq",
                rs -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), Jdbc.instant(rs, "occurred_at"),
                        rs.getString(6), rs.getBytes(7), rs.getBytes(8), rs.getBytes(9), rs.getBytes(10), rs.getString(11),
                        Jdbc.instant(rs, "erased_at")),
                tenant, rootId);
        byte[] expectedPrev = genesis(tenant, rootId);
        long expectedSeq = 1;
        long n = 0;
        for (Row r : rows) {
            if (r.seq != expectedSeq) {
                return VerifyResult.bad(n, expectedSeq, "gap: expected seq " + expectedSeq + " but found " + r.seq);
            }
            if (!Arrays.equals(r.prevHash, expectedPrev)) {
                return VerifyResult.bad(n, r.seq, "prev_hash does not link to the previous event");
            }
            String rebuilt = envelope(r.eventId, tenant, rootId, r.seq, r.type, r.schemaVersion, r.occurredAt, r.payloadDigest, r.prevHash);
            if (!rebuilt.equals(r.envelope)) {
                return VerifyResult.bad(n, r.seq, "envelope does not match its columns");
            }
            byte[] hash = Hashes.sha256(r.prevHash, r.envelope.getBytes(StandardCharsets.UTF_8));
            if (!Arrays.equals(hash, r.hash)) {
                return VerifyResult.bad(n, r.seq, "hash mismatch");
            }
            if (r.erasedAt == null) {
                if (r.salt == null || r.payload == null) {
                    return VerifyResult.bad(n, r.seq, "payload missing without an erasure record");
                }
                byte[] digest = Hashes.sha256(r.salt, r.payload.getBytes(StandardCharsets.UTF_8));
                if (!Arrays.equals(digest, r.payloadDigest)) {
                    return VerifyResult.bad(n, r.seq, "payload digest mismatch");
                }
            }
            expectedPrev = r.hash;
            expectedSeq++;
            n++;
        }
        var head = Jdbc.one(c, "SELECT next_audit_seq, audit_head_hash FROM ROOT_SEQ WHERE tenant_id=? AND root_id=?",
                rs -> new Object[]{rs.getLong(1), rs.getBytes(2)}, tenant, rootId);
        if (head.isPresent()) {
            long nextSeq = (Long) head.get()[0];
            byte[] headHash = (byte[]) head.get()[1];
            if (nextSeq != expectedSeq || (n > 0 && !Arrays.equals(headHash, expectedPrev))) {
                return VerifyResult.bad(n, expectedSeq, "chain head mismatch (truncated tail?)");
            }
        } else if (n > 0) {
            return VerifyResult.bad(n, expectedSeq, "chain head missing");
        }
        return VerifyResult.good(n);
    }

    private static String envelope(String eventId, String tenant, String chainId, long seq, String type, int schemaVersion,
                                   Instant occurredAt, byte[] payloadDigest, byte[] prevHash) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("event_id", eventId);
        env.put("tenant_id", tenant);
        env.put("class", "B");
        env.put("type", type);
        env.put("schema_version", schemaVersion);
        env.put("chain_id", chainId);
        env.put("seq", seq);
        env.put("root_id", chainId.startsWith("sec:") ? null : chainId);
        env.put("occurred_at", occurredAt.toString());
        env.put("payload_digest", Hashes.hex(payloadDigest));
        env.put("prev_hash", Hashes.hex(prevHash));
        return CanonicalJson.write(env);
    }
}
