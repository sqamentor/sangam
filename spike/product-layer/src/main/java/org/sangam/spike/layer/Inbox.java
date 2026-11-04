package org.sangam.spike.layer;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inbound inbox (SANGAM.md VI.2.6, R38).
 *
 * <ol>
 *   <li><b>Receive and commit, then ack the broker.</b> {@link #receive} inserts {@code (tenant, source, messageId)};
 *       a duplicate key is a redelivery: the caller acks and drops it ({@link ReceiveResult#DUPLICATE}).
 *       The tenant must come from the channel binding / authenticated source, never from the payload alone.</li>
 *   <li><b>Process.</b> Rows are claimed like relay deliveries (skip-locked, {@code claim_epoch + 1}). The processing
 *       transaction calls {@link #markProcessed} or {@link #markBuffered} as its last write, fenced by the epoch.</li>
 *   <li><b>No subscription yet.</b> The row is {@code BUFFERED} and re-polled until its TTL runs out;
 *       {@link #expire} then moves it to {@code EXPIRED} with an audit entry on the tenant's security chain.</li>
 * </ol>
 * States: RECEIVED → CLAIMED → PROCESSED | BUFFERED (→ CLAIMED …) | EXPIRED.
 */
public final class Inbox {

    public enum State { RECEIVED, CLAIMED, BUFFERED, PROCESSED, EXPIRED }

    public enum ReceiveResult { ACCEPTED, DUPLICATE }

    public record Message(String tenantId, String source, String messageId, String correlationKey, String payload, long epoch) {
    }

    /** Processing callback, run inside the processing transaction. Return false when no subscription matches yet. */
    @FunctionalInterface
    public interface Handler {
        boolean handle(Connection c, Message m);
    }

    public static final class ClaimLostException extends RuntimeException {
        public ClaimLostException(Message m) {
            super("inbox claim lost: " + m.source() + "/" + m.messageId());
        }
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final String node;
    private final Duration claimTtl;
    private final Duration bufferRetry;
    private final AuditChain audit;

    public Inbox(Dialect dialect, Transactions tx, String node, Duration claimTtl, Duration bufferRetry, AuditChain auditOrNull) {
        this.dialect = dialect;
        this.tx = tx;
        this.node = node;
        this.claimTtl = claimTtl;
        this.bufferRetry = bufferRetry;
        this.audit = auditOrNull;
    }

    /** Inserts the message in the caller's transaction. {@code ttl}: per event definition, default 24 h. */
    public ReceiveResult receive(Connection c, String tenant, String source, String messageId, String correlationKey,
                                 String payload, Duration ttl) {
        boolean inserted = Jdbc.insertIfAbsent(c, dialect,
                "INSERT INTO INBOX(tenant_id, source, message_id, received_at, state, correlation_key, payload, claim_owner, claim_epoch,"
                        + " claim_expires_at, next_attempt_at, expires_at) VALUES (?,?,?," + dialect.now() + ",'RECEIVED',?,?,NULL,0,NULL,"
                        + dialect.now() + "," + dialect.nowPlusMillisParam() + ")",
                tenant, source, messageId, correlationKey, payload, ttl.toMillis());
        return inserted ? ReceiveResult.ACCEPTED : ReceiveResult.DUPLICATE;
    }

    /** {@link #receive} in its own transaction; ack the broker after this returns. */
    public ReceiveResult receive(String tenant, String source, String messageId, String correlationKey, String payload, Duration ttl) {
        return tx.inTransaction(c -> receive(c, tenant, source, messageId, correlationKey, payload, ttl));
    }

    /** Claims due RECEIVED/BUFFERED rows that have not expired (own short transaction). */
    public List<Message> claim(int batch) {
        return tx.inTransaction(c -> {
            String where = "i.state IN ('RECEIVED','BUFFERED') AND i.next_attempt_at <= " + dialect.now()
                    + " AND i.expires_at > " + dialect.now();
            List<Object[]> keys = Jdbc.query(c,
                    dialect.skipLockedSelect("i.tenant_id, i.source, i.message_id", "INBOX", "i", where, "i.received_at", batch),
                    rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)});
            List<Message> out = new ArrayList<>();
            for (Object[] k : keys) {
                Jdbc.update(c, "UPDATE INBOX SET state='CLAIMED', claim_owner=?, claim_epoch=claim_epoch+1, claim_expires_at="
                        + dialect.nowPlusMillisParam() + " WHERE tenant_id=? AND source=? AND message_id=?", node, claimTtl.toMillis(), k[0], k[1], k[2]);
                out.add(Jdbc.one(c, "SELECT tenant_id, source, message_id, correlation_key, payload, claim_epoch FROM INBOX"
                                + " WHERE tenant_id=? AND source=? AND message_id=?",
                        rs -> new Message(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)),
                        k[0], k[1], k[2]).orElseThrow());
            }
            return out;
        });
    }

    /** Last write of the processing transaction: the message's effect commits with PROCESSED or not at all. */
    public void markProcessed(Connection c, Message m) {
        finish(c, m, "PROCESSED", 0);
    }

    /** No matching subscription yet: keep the message BUFFERED and re-poll after {@code bufferRetry}. */
    public void markBuffered(Connection c, Message m) {
        finish(c, m, "BUFFERED", bufferRetry.toMillis());
    }

    private void finish(Connection c, Message m, String state, long retryMillis) {
        int n = Jdbc.update(c, "UPDATE INBOX SET state=?, claim_owner=NULL, claim_expires_at=NULL, next_attempt_at="
                        + dialect.nowPlusMillisParam() + " WHERE tenant_id=? AND source=? AND message_id=? AND claim_epoch=? AND state='CLAIMED'",
                state, retryMillis, m.tenantId(), m.source(), m.messageId(), m.epoch());
        if (n == 0) {
            throw new ClaimLostException(m);
        }
    }

    /** Claim + one processing transaction per message. Returns the number of messages PROCESSED. */
    public int processOnce(int batch, Handler handler) {
        int processed = 0;
        for (Message m : claim(batch)) {
            boolean done = tx.inTransaction(c -> {
                boolean matched = handler.handle(c, m);
                if (matched) {
                    markProcessed(c, m);
                } else {
                    markBuffered(c, m);
                }
                return matched;
            });
            if (done) {
                processed++;
            }
        }
        return processed;
    }

    /** TTL expiry: BUFFERED/RECEIVED rows past {@code expires_at} become EXPIRED, each with an audit entry. */
    public int expire() {
        List<String> tenants = tx.inTransaction(c -> Jdbc.query(c, "SELECT DISTINCT tenant_id FROM INBOX WHERE state IN ('RECEIVED','BUFFERED')"
                + " AND expires_at <= " + dialect.now(), rs -> rs.getString(1)));
        int total = 0;
        for (String tenant : tenants) {
            total += expire(tenant);
        }
        return total;
    }

    private int expire(String tenant) {
        return tx.inTransaction(c -> {
            if (audit != null) {
                RootSeq.lock(c, dialect, tenant, "sec:" + tenant);   // security chain (L2 surrogate) before the inbox rows
            }
            List<Message> due = Jdbc.query(c, dialect.skipLockedSelect(
                            "i.tenant_id, i.source, i.message_id, i.correlation_key, i.claim_epoch", "INBOX", "i",
                            "i.tenant_id = ? AND i.state IN ('RECEIVED','BUFFERED') AND i.expires_at <= " + dialect.now(), "i.received_at", 1000),
                    rs -> new Message(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), null, rs.getLong(5)), tenant);
            for (Message m : due) {
                Jdbc.update(c, "UPDATE INBOX SET state='EXPIRED' WHERE tenant_id=? AND source=? AND message_id=?",
                        m.tenantId(), m.source(), m.messageId());
                if (audit != null) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("source", m.source());
                    p.put("messageId", m.messageId());
                    p.put("correlationKey", m.correlationKey());
                    audit.append(c, m.tenantId(), "sec:" + m.tenantId(), "INBOX_MESSAGE_EXPIRED", p);
                }
            }
            return due.size();
        });
    }

    /** Crash recovery: expired CLAIMED rows go back to RECEIVED. */
    public int sweep() {
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE INBOX SET state='RECEIVED', claim_owner=NULL, claim_expires_at=NULL"
                + " WHERE state='CLAIMED' AND claim_expires_at < " + dialect.now()));
    }

    public static State state(Connection c, String tenant, String source, String messageId) {
        return Jdbc.one(c, "SELECT state FROM INBOX WHERE tenant_id=? AND source=? AND message_id=?",
                rs -> State.valueOf(rs.getString(1)), tenant, source, messageId).orElse(null);
    }
}
