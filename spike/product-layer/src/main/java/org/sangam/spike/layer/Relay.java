package org.sangam.spike.layer;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox relay (SANGAM.md VI.2.3). Runs on any number of nodes.
 *
 * <ol>
 *   <li><b>Claim</b> (one short transaction): due {@code PENDING} deliveries that are the head of their
 *       (root, destination) line — for ordered destinations, no lower {@code root_seq} of the same line is
 *       {@code PENDING}, {@code CLAIMED} or {@code PARKED}. The scan is state-based, never an id watermark, so a row that
 *       commits late is picked up on a later pass. Rows are locked with skip-locked semantics and get
 *       {@code claim_epoch + 1}.</li>
 *   <li><b>Publish</b> outside any transaction.</li>
 *   <li><b>Mark</b> {@code DONE} fenced by {@code claim_epoch} and {@code state='CLAIMED'}; zero rows means this node lost
 *       its claim, and the publish may be duplicated (allowed).</li>
 *   <li><b>Sweep</b>: expired {@code CLAIMED} rows return to {@code PENDING}; consumers dedup on {@code event_id}.</li>
 *   <li><b>Failures</b> back off exponentially; after {@code maxAttempts} an ordered delivery is {@code PARKED} (it blocks
 *       later events of that root for that destination only) and an unordered one is {@code SKIPPED} with a gap record
 *       in {@code last_error}. Repairs ({@link #requeue}, {@link #skip}) are audited.</li>
 * </ol>
 * Throughput note: one claim cycle advances each ordered line by one event; later events of the line are claimed on
 * the following cycles.
 */
public final class Relay {

    public record Config(String node, int batchSize, Duration leaseTtl, int maxAttempts, Duration backoffBase, Duration backoffCap) {
        public static Config defaults(String node) {
            return new Config(node, 100, Duration.ofSeconds(30), 10, Duration.ofMillis(200), Duration.ofMinutes(5));
        }
    }

    public record Claimed(Outbox.OutboxEvent event, String destination, long epoch, int attempts, boolean ordered) {
    }

    public record CycleResult(int claimed, int published, int failed, int fencedOut) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final Publisher publisher;
    private final Config config;
    private final AuditChain audit;

    public Relay(Dialect dialect, Transactions tx, Publisher publisher, Config config, AuditChain auditOrNull) {
        this.dialect = dialect;
        this.tx = tx;
        this.publisher = publisher;
        this.config = config;
        this.audit = auditOrNull;
    }

    /** Claim, publish, mark. */
    public CycleResult runOnce() {
        List<Claimed> batch = claim();
        int published = 0, failed = 0, fenced = 0;
        for (Claimed cl : batch) {
            try {
                publisher.publish(cl.destination(), cl.event());
                published++;
            } catch (Exception e) {
                failed++;
                if (!markFailed(cl, String.valueOf(e.getMessage()))) {
                    fenced++;
                }
                continue;
            }
            if (!markDone(cl)) {
                fenced++;
            }
        }
        return new CycleResult(batch.size(), published, failed, fenced);
    }

    /** Runs cycles until one claims nothing (bounded). Returns the number of cycles that claimed work. */
    public int drain(int maxCycles) {
        int n = 0;
        while (n < maxCycles && runOnce().claimed() > 0) {
            n++;
        }
        return n;
    }

    public List<Claimed> claim() {
        return tx.inTransaction(c -> {
            String where = "d.state = 'PENDING' AND d.next_attempt_at <= " + dialect.now()
                    + " AND (d.ordered_flag = 0 OR NOT EXISTS (SELECT 1 FROM OUTBOX_DELIVERY y"
                    + " WHERE y.tenant_id = d.tenant_id AND y.root_id = d.root_id AND y.destination = d.destination"
                    + " AND y.root_seq < d.root_seq AND y.state IN ('PENDING','CLAIMED','PARKED')))";
            String sql = dialect.skipLockedSelect("d.outbox_id, d.destination", "OUTBOX_DELIVERY", "d", where,
                    "d.next_attempt_at, d.root_seq", config.batchSize());
            List<Object[]> keys = Jdbc.query(c, sql, rs -> new Object[]{rs.getLong(1), rs.getString(2)});
            List<Claimed> out = new ArrayList<>(keys.size());
            for (Object[] k : keys) {
                Jdbc.update(c, "UPDATE OUTBOX_DELIVERY SET state='CLAIMED', claim_owner=?, claim_epoch=claim_epoch+1, claim_expires_at="
                                + dialect.nowPlusMillisParam() + " WHERE outbox_id=? AND destination=? AND state='PENDING'",
                        config.node(), config.leaseTtl().toMillis(), k[0], k[1]);
                out.add(Jdbc.one(c, "SELECT o.id, o.event_id, o.tenant_id, o.root_id, o.root_seq, o.event_type, o.payload,"
                                + " d.destination, d.claim_epoch, d.attempts, d.ordered_flag FROM OUTBOX_DELIVERY d"
                                + " JOIN OUTBOX o ON o.id = d.outbox_id WHERE d.outbox_id=? AND d.destination=?",
                        rs -> new Claimed(new Outbox.OutboxEvent(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                                rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)),
                                rs.getString(8), rs.getLong(9), rs.getInt(10), rs.getInt(11) == 1),
                        k[0], k[1]).orElseThrow());
            }
            return out;
        });
    }

    /** Marks a claimed delivery DONE; false if the claim was lost (fencing). */
    public boolean markDone(Claimed cl) {
        return tx.inTransaction(c -> Jdbc.update(c,
                "UPDATE OUTBOX_DELIVERY SET state='DONE', claim_owner=NULL, claim_expires_at=NULL, last_error=NULL"
                        + " WHERE outbox_id=? AND destination=? AND claim_epoch=? AND state='CLAIMED'",
                cl.event().outboxId(), cl.destination(), cl.epoch()) == 1);
    }

    /** Records a failed attempt: back off, or PARKED (ordered) / SKIPPED (unordered) after maxAttempts. */
    public boolean markFailed(Claimed cl, String error) {
        int attempts = cl.attempts() + 1;
        boolean exhausted = attempts >= config.maxAttempts();
        String next = exhausted ? (cl.ordered() ? "PARKED" : "SKIPPED") : "PENDING";
        String lastError = exhausted && !cl.ordered()
                ? "GAP: skipped after " + attempts + " attempts: " + error
                : error;
        long backoff = Math.min(config.backoffCap().toMillis(),
                config.backoffBase().toMillis() * (1L << Math.min(attempts - 1, 20)));
        return tx.inTransaction(c -> {
            if (exhausted && audit != null) {
                RootSeq.lock(c, dialect, cl.event().tenantId(), cl.event().rootId());
            }
            int n = Jdbc.update(c, "UPDATE OUTBOX_DELIVERY SET state=?, attempts=?, last_error=?, claim_owner=NULL, claim_expires_at=NULL,"
                            + " next_attempt_at=" + dialect.nowPlusMillisParam()
                            + " WHERE outbox_id=? AND destination=? AND claim_epoch=? AND state='CLAIMED'",
                    next, attempts, lastError, backoff, cl.event().outboxId(), cl.destination(), cl.epoch());
            if (n == 1 && exhausted && audit != null) {
                audit.append(c, cl.event().tenantId(), cl.event().rootId(),
                        cl.ordered() ? "OUTBOX_DELIVERY_PARKED" : "OUTBOX_DELIVERY_SKIPPED", repairPayload(cl.event(), cl.destination(), "relay", error));
            }
            return n == 1;
        });
    }

    /** Crash recovery: expired CLAIMED rows go back to PENDING (the event will be published again). */
    public int sweep() {
        return tx.inTransaction(c -> Jdbc.update(c,
                "UPDATE OUTBOX_DELIVERY SET state='PENDING', claim_owner=NULL, claim_expires_at=NULL"
                        + " WHERE state='CLAIMED' AND claim_expires_at < " + dialect.now()));
    }

    /** Repair API: a PARKED delivery goes back to PENDING with a fresh attempt budget. Audited. */
    public boolean requeue(long outboxId, String destination, String actor, String reason) {
        return repair(outboxId, destination, "PENDING", "OUTBOX_REQUEUE", actor, reason);
    }

    /** Repair API: a PARKED delivery is SKIPPED with a reason, unblocking its line. Audited. */
    public boolean skip(long outboxId, String destination, String actor, String reason) {
        return repair(outboxId, destination, "SKIPPED", "OUTBOX_SKIP", actor, reason);
    }

    private boolean repair(long outboxId, String destination, String target, String auditType, String actor, String reason) {
        return tx.inTransaction(c -> {
            Outbox.OutboxEvent ev = Jdbc.one(c, "SELECT id, event_id, tenant_id, root_id, root_seq, event_type, payload FROM OUTBOX WHERE id=?",
                    rs -> new Outbox.OutboxEvent(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getString(6), rs.getString(7)), outboxId).orElseThrow();
            if (audit != null) {
                RootSeq.lock(c, dialect, ev.tenantId(), ev.rootId());
            }
            int n = Jdbc.update(c, "UPDATE OUTBOX_DELIVERY SET state=?, attempts=0, next_attempt_at=" + dialect.now()
                            + ", last_error=? WHERE outbox_id=? AND destination=? AND state='PARKED'",
                    target, "REPAIR " + auditType + " by " + actor + ": " + reason, outboxId, destination);
            if (n == 1 && audit != null) {
                audit.append(c, ev.tenantId(), ev.rootId(), auditType, repairPayload(ev, destination, actor, reason));
            }
            return n == 1;
        });
    }

    private static Map<String, Object> repairPayload(Outbox.OutboxEvent ev, String destination, String actor, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("outboxId", ev.outboxId());
        p.put("eventId", ev.eventId().toString());
        p.put("rootSeq", ev.rootSeq());
        p.put("destination", destination);
        p.put("actor", actor);
        p.put("reason", reason);
        return p;
    }

    /** Delivery state, for tests and the operator view. */
    public static String state(Connection c, long outboxId, String destination) {
        return Jdbc.one(c, "SELECT state FROM OUTBOX_DELIVERY WHERE outbox_id=? AND destination=?", rs -> rs.getString(1),
                outboxId, destination).orElse(null);
    }
}
