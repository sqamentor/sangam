// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.batch;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.support.Json;
import org.sangam.ports.support.Paging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Product batch service (Flowable {@code BatchService}; FLOWABLE-INTO-OPERATON D-34 bulk task completion, FLOWABLE-INTO-OPERATON FC-35 change tenant, FLOWABLE-INTO-OPERATON FC-18
 * migration post-steps, FLOWABLE-INTO-OPERATON FC-19 case cut-over, bulk correlation). Operaton's own batch framework (B-14) stays the tool
 * for migration, modification, deletion, restart and history operations; this service covers the operations
 * Operaton has no batch for. Tables {@code PORT_BATCH} / {@code PORT_BATCH_ITEM}. Backs sangam-rest {@code OpsPort}
 * batches.
 *
 * <p><b>Lifecycle.</b> {@code PENDING_APPROVAL → RUNNING ⇄ PAUSED → COMPLETED | COMPLETED_WITH_FAILURES};
 * {@code PENDING_APPROVAL → REJECTED}; {@code RUNNING | PAUSED | PENDING_APPROVAL → CANCELLED}.
 * <ul>
 *   <li><b>Four eyes.</b> {@link FourEyesPolicy} decides at creation (default: types listed as sensitive, or more
 *       than a threshold of items). Such a batch starts in {@code PENDING_APPROVAL}; {@link #approve} by a different
 *       person than the creator starts it, {@link #reject} ends it (VI.4 maker-checker; who may approve is checked by
 *       WS-SECURITY before the call; this service enforces approver ≠ creator).</li>
 *   <li><b>Pause/resume.</b> A paused batch is skipped by {@link #runOnce}; an item claimed just before the pause
 *       is put back to {@code PENDING} unexecuted.</li>
 * </ul>
 * Execution is chunked and resumable: {@link #runOnce} claims up to {@code chunk} pending items of running batches
 * with {@code SKIP LOCKED} (several nodes can work one batch), runs each item in its own transaction through its
 * {@link ItemHandler} and records {@code DONE} or {@code FAILED}. Item handlers must be idempotent (R36).
 */
public class ProductBatchService {

    public enum State { PENDING_APPROVAL, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_FAILURES, CANCELLED, REJECTED }

    @FunctionalInterface
    public interface ItemHandler {
        /** Runs one item; may return a short result text. Throw to mark the item failed. */
        String execute(String tenantId, String itemRef, Map<String, Object> config) throws Exception;
    }

    /** Decides whether a new batch needs a second person's approval. */
    @FunctionalInterface
    public interface FourEyesPolicy {
        boolean required(String tenantId, String type, int itemCount);

        static FourEyesPolicy of(java.util.Set<String> sensitiveTypes, int itemThreshold) {
            return (t, type, n) -> sensitiveTypes.contains(type) || n > itemThreshold;
        }

        FourEyesPolicy NEVER = (t, type, n) -> false;
    }

    /** Thrown for maker-checker violations and illegal state changes (REST: 409 / 403). */
    public static final class BatchStateException extends IllegalStateException {
        public BatchStateException(String message) {
            super(message);
        }
    }

    public record Batch(String id, String tenantId, String type, State state, int total, int done, int failed,
                        Map<String, Object> config, String reason, boolean fourEyesRequired, String approvedBy, Instant approvedAt,
                        String createdBy, Instant createdAt, Instant updatedAt, Instant completedAt) {
    }

    public record Item(int seq, String itemRef, String state, int attempts, String result) {
    }

    private static final String COLS = "id, tenant_id, batch_type, state, total_items, done_items, failed_items, config, reason,"
            + " four_eyes_flag, approved_by, approved_at, created_by, created_at, updated_at, completed_at";

    private final Dialect dialect;
    private final Transactions tx;
    private final int maxAttempts;
    private final FourEyesPolicy fourEyes;
    private final Map<String, ItemHandler> handlers = new ConcurrentHashMap<>();

    public ProductBatchService(Dialect dialect, Transactions tx, int maxAttempts) {
        this(dialect, tx, maxAttempts, FourEyesPolicy.NEVER);
    }

    public ProductBatchService(Dialect dialect, Transactions tx, int maxAttempts, FourEyesPolicy fourEyes) {
        this.dialect = dialect;
        this.tx = tx;
        this.maxAttempts = maxAttempts;
        this.fourEyes = fourEyes == null ? FourEyesPolicy.NEVER : fourEyes;
    }

    public ProductBatchService register(String type, ItemHandler handler) {
        handlers.put(type, handler);
        return this;
    }

    /** Whether a batch of this type and size would need four-eyes approval (for previews). */
    public boolean fourEyesRequired(String tenant, String type, int itemCount) {
        return fourEyes.required(tenant, type, itemCount);
    }

    public String create(String tenant, String type, List<String> items, Map<String, Object> config, String user) {
        return create(tenant, type, items, config, user, null);
    }

    public String create(String tenant, String type, List<String> items, Map<String, Object> config, String user, String reason) {
        if (!handlers.containsKey(type)) {
            throw new IllegalArgumentException("no batch handler for type " + type);
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("batch without items");
        }
        boolean four = fourEyes.required(tenant, type, items.size());
        String id = UUID.randomUUID().toString();
        tx.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO PORT_BATCH(id, tenant_id, batch_type, state, total_items, done_items, failed_items, config, reason,"
                            + " four_eyes_flag, approved_by, approved_at, created_by, created_at, updated_at, completed_at)"
                            + " VALUES (?,?,?,?,?,0,0,?,?,?,NULL,NULL,?," + dialect.now() + "," + dialect.now() + ",NULL)",
                    id, t(tenant), type, (four ? State.PENDING_APPROVAL : State.RUNNING).name(), items.size(),
                    Json.write(config == null ? Map.of() : config), truncate(reason), four ? 1 : 0, user);
            int seq = 0;
            for (String ref : items) {
                Jdbc.update(c, "INSERT INTO PORT_BATCH_ITEM(batch_id, item_seq, item_ref, state, attempts, result, updated_at)"
                        + " VALUES (?,?,?,'PENDING',0,NULL," + dialect.now() + ")", id, seq++, ref);
            }
            return null;
        });
        return id;
    }

    // ------------------------------------------------------------------ four eyes, pause, resume, cancel

    /** Second-person approval: starts a {@code PENDING_APPROVAL} batch. The approver must differ from the creator. */
    public Batch approve(String batchId, String approver) {
        return tx.inTransaction(c -> {
            Batch b = lock(c, batchId);
            if (b.state() != State.PENDING_APPROVAL) {
                throw new BatchStateException("batch " + batchId + " is " + b.state() + ", not awaiting approval");
            }
            if (Objects.equals(b.createdBy(), approver)) {
                throw new BatchStateException("four-eyes: the creator of batch " + batchId + " cannot approve it");
            }
            Jdbc.update(c, "UPDATE PORT_BATCH SET state = 'RUNNING', approved_by = ?, approved_at = " + dialect.now() + ", updated_at = "
                    + dialect.now() + " WHERE id = ? AND state = 'PENDING_APPROVAL'", approver, batchId);
            return get(c, batchId).orElseThrow();
        });
    }

    public Batch reject(String batchId, String approver, String reason) {
        return tx.inTransaction(c -> {
            Batch b = lock(c, batchId);
            if (b.state() != State.PENDING_APPROVAL) {
                throw new BatchStateException("batch " + batchId + " is " + b.state() + ", not awaiting approval");
            }
            if (Objects.equals(b.createdBy(), approver)) {
                throw new BatchStateException("four-eyes: the creator of batch " + batchId + " cannot reject it (cancel instead)");
            }
            Jdbc.update(c, "UPDATE PORT_BATCH SET state = 'REJECTED', approved_by = ?, approved_at = " + dialect.now()
                    + ", reason = ?, updated_at = " + dialect.now() + ", completed_at = " + dialect.now() + " WHERE id = ?",
                    approver, truncate(reason), batchId);
            Jdbc.update(c, "UPDATE PORT_BATCH_ITEM SET state = 'CANCELLED' WHERE batch_id = ? AND state = 'PENDING'", batchId);
            return get(c, batchId).orElseThrow();
        });
    }

    public Batch pause(String batchId) {
        return transition(batchId, State.RUNNING, State.PAUSED);
    }

    public Batch resume(String batchId) {
        return transition(batchId, State.PAUSED, State.RUNNING);
    }

    public Batch cancel(String batchId) {
        return tx.inTransaction(c -> {
            Batch b = lock(c, batchId);
            if (b.state() != State.RUNNING && b.state() != State.PAUSED && b.state() != State.PENDING_APPROVAL) {
                throw new BatchStateException("batch " + batchId + " is " + b.state() + " and cannot be cancelled");
            }
            Jdbc.update(c, "UPDATE PORT_BATCH SET state = 'CANCELLED', updated_at = " + dialect.now() + ", completed_at = " + dialect.now()
                    + " WHERE id = ?", batchId);
            Jdbc.update(c, "UPDATE PORT_BATCH_ITEM SET state = 'CANCELLED' WHERE batch_id = ? AND state = 'PENDING'", batchId);
            return get(c, batchId).orElseThrow();
        });
    }

    private Batch transition(String batchId, State from, State to) {
        return tx.inTransaction(c -> {
            Batch b = lock(c, batchId);
            if (b.state() != from) {
                throw new BatchStateException("batch " + batchId + " is " + b.state() + ", expected " + from);
            }
            Jdbc.update(c, "UPDATE PORT_BATCH SET state = ?, updated_at = " + dialect.now() + " WHERE id = ? AND state = ?",
                    to.name(), batchId, from.name());
            return get(c, batchId).orElseThrow();
        });
    }

    // ------------------------------------------------------------------ execution

    /** Works up to {@code chunk} pending items of running batches. Returns the number of items processed. */
    public int runOnce(int chunk) {
        List<Object[]> claimed = tx.inTransaction(c -> {
            List<Object[]> rows = Jdbc.query(c, dialect.skipLockedSelect("i.batch_id, i.item_seq, i.item_ref", "PORT_BATCH_ITEM", "i",
                    "i.state = 'PENDING' AND i.batch_id IN (SELECT b.id FROM PORT_BATCH b WHERE b.state = 'RUNNING')",
                    "i.batch_id, i.item_seq", chunk), rs -> new Object[]{rs.getString(1), rs.getInt(2), rs.getString(3)});
            for (Object[] r : rows) {
                Jdbc.update(c, "UPDATE PORT_BATCH_ITEM SET state = 'RUNNING', attempts = attempts + 1, updated_at = " + dialect.now()
                        + " WHERE batch_id = ? AND item_seq = ?", r[0], r[1]);
            }
            return rows;
        });
        int n = 0;
        for (Object[] r : claimed) {
            if (runItem((String) r[0], (Integer) r[1], (String) r[2])) {
                n++;
            }
        }
        return n;
    }

    private boolean runItem(String batchId, int seq, String ref) {
        Batch b = batch(batchId).orElse(null);
        if (b == null || b.state() == State.CANCELLED || b.state() == State.REJECTED) {
            tx.inTransaction(c -> Jdbc.update(c, "UPDATE PORT_BATCH_ITEM SET state = 'CANCELLED' WHERE batch_id = ? AND item_seq = ?", batchId, seq));
            return false;
        }
        if (b.state() != State.RUNNING) {
            // paused between claim and execution: give the item back, do not count the attempt
            tx.inTransaction(c -> Jdbc.update(c, "UPDATE PORT_BATCH_ITEM SET state = 'PENDING', attempts = attempts - 1 WHERE batch_id = ?"
                    + " AND item_seq = ? AND state = 'RUNNING'", batchId, seq));
            return false;
        }
        String result;
        boolean ok;
        try {
            result = handlers.get(b.type()).execute(b.tenantId(), ref, b.config());
            ok = true;
        } catch (Exception e) {
            result = e.getClass().getSimpleName() + ": " + e.getMessage();
            ok = false;
        }
        String res = result == null ? null : result.length() > 3900 ? result.substring(0, 3900) : result;
        boolean success = ok;
        tx.inTransaction(c -> {
            Jdbc.update(c, "UPDATE PORT_BATCH_ITEM SET state = ?, result = ?, updated_at = " + dialect.now() + " WHERE batch_id = ? AND item_seq = ?",
                    success ? "DONE" : "FAILED", res, batchId, seq);
            Jdbc.update(c, "UPDATE PORT_BATCH SET " + (success ? "done_items = done_items + 1" : "failed_items = failed_items + 1")
                    + ", updated_at = " + dialect.now() + " WHERE id = ?", batchId);
            finishIfDone(c, batchId);
            return null;
        });
        return true;
    }

    private void finishIfDone(java.sql.Connection c, String batchId) {
        long open = Jdbc.one(c, "SELECT COUNT(*) FROM PORT_BATCH_ITEM WHERE batch_id = ? AND state IN ('PENDING','RUNNING')",
                rs -> rs.getLong(1), batchId).orElse(0L);
        if (open == 0) {
            Jdbc.update(c, "UPDATE PORT_BATCH SET state = CASE WHEN failed_items > 0 THEN 'COMPLETED_WITH_FAILURES' ELSE 'COMPLETED' END,"
                    + " completed_at = " + dialect.now() + " WHERE id = ? AND state IN ('RUNNING','PAUSED')", batchId);
        }
    }

    /** Puts failed items (below the attempt limit) back to PENDING. */
    public int retryFailed(String batchId) {
        return tx.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE PORT_BATCH_ITEM SET state = 'PENDING' WHERE batch_id = ? AND state = 'FAILED' AND attempts < ?",
                    batchId, maxAttempts);
            if (n > 0) {
                Jdbc.update(c, "UPDATE PORT_BATCH SET state = 'RUNNING', failed_items = failed_items - ?, completed_at = NULL WHERE id = ?"
                        + " AND state IN ('COMPLETED_WITH_FAILURES','RUNNING')", n, batchId);
            }
            return n;
        });
    }

    /** Re-queues items stuck in RUNNING after a node crash (older than {@code staleMillis}). */
    public int recoverStale(long staleMillis) {
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE PORT_BATCH_ITEM SET state = 'PENDING' WHERE state = 'RUNNING' AND updated_at < "
                + dialect.nowPlusMillisParam(), -staleMillis));
    }

    // ------------------------------------------------------------------ queries

    public Optional<Batch> batch(String id) {
        return tx.inTransaction(c -> get(c, id));
    }

    /** Batches of a tenant, newest first, optionally by state; paged and count-capped (A-16). */
    public Paging.PageResult<Batch> list(String tenant, State stateOrNull, Paging.PageRequest p) {
        String fw = "FROM PORT_BATCH WHERE tenant_id = ?" + (stateOrNull == null ? "" : " AND state = ?");
        Object[] params = stateOrNull == null ? new Object[]{t(tenant)} : new Object[]{t(tenant), stateOrNull.name()};
        return tx.inTransaction(c -> {
            long total = Jdbc.one(c, Paging.cappedCount(dialect, fw), rs -> rs.getLong(1), params).orElse(0L);
            List<Batch> rows = Jdbc.query(c, "SELECT " + COLS + " " + fw + " ORDER BY created_at DESC, id" + Paging.window(dialect, p),
                    ProductBatchService::map, params);
            return Paging.of(rows, total, p);
        });
    }

    /** Batches waiting for a second person (approval inbox). */
    public List<Batch> awaitingApproval(String tenant) {
        return list(tenant, State.PENDING_APPROVAL, new Paging.PageRequest(0, Paging.MAX_PAGE)).items();
    }

    public List<Item> items(String batchId, String stateOrNull) {
        String where = stateOrNull == null ? "batch_id = ?" : "batch_id = ? AND state = ?";
        Object[] p = stateOrNull == null ? new Object[]{batchId} : new Object[]{batchId, stateOrNull};
        return tx.inTransaction(c -> new ArrayList<>(Jdbc.query(c, "SELECT item_seq, item_ref, state, attempts, result FROM PORT_BATCH_ITEM WHERE "
                + where + " ORDER BY item_seq", rs -> new Item(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)), p)));
    }

    private Batch lock(java.sql.Connection c, String id) {
        Jdbc.one(c, dialect.lockingSelect("id", "PORT_BATCH", "id = ?"), rs -> rs.getString(1), id)
                .orElseThrow(() -> new IllegalArgumentException("batch " + id + " not found"));
        return get(c, id).orElseThrow();
    }

    private Optional<Batch> get(java.sql.Connection c, String id) {
        return Jdbc.one(c, "SELECT " + COLS + " FROM PORT_BATCH WHERE id = ?", ProductBatchService::map, id);
    }

    private static Batch map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Batch(rs.getString(1), rs.getString(2), rs.getString(3), State.valueOf(rs.getString(4)), rs.getInt(5), rs.getInt(6),
                rs.getInt(7), Json.map(rs.getString(8)), rs.getString(9), rs.getInt(10) == 1, rs.getString(11),
                Jdbc.instant(rs, "approved_at"), rs.getString(13), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"),
                Jdbc.instant(rs, "completed_at"));
    }

    private static String t(String tenant) {
        return tenant == null ? "" : tenant;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 250 ? s : s.substring(0, 250);
    }
}
