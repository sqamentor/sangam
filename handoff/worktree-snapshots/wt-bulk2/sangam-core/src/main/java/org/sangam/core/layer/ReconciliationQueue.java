package org.sangam.core.layer;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reconciliation workbench queue (SANGAM.md VI.3 rule 4, VI.5 rules 3–4, OJ-02/OJ-03).
 *
 * <p>Items: {@link Kind#UNKNOWN_OUTCOME} and {@link Kind#LATE_RESULT_AFTER_DELETE}. Resolution actions:
 * <i>confirm success with result</i>, <i>retry with the same operationId</i>, <i>mark failed</i>. Resolving updates the
 * operation ledger in the same transaction and, when an {@link AuditChain} is configured, writes a
 * {@code RECONCILIATION_ACTION} class-B event on the root's chain.
 */
public final class ReconciliationQueue {

    /** POISON_INBOUND: an inbound message that could not be read, dead-lettered by sangam-integration (one item per letter). */
    public enum Kind { UNKNOWN_OUTCOME, LATE_RESULT_AFTER_DELETE, POISON_INBOUND }

    public enum Action { CONFIRM_SUCCESS, RETRY_SAME_OPERATION, MARK_FAILED }

    public enum State { OPEN, RESOLVED }

    public record Item(long id, String tenantId, Kind kind, UUID operationId, String rootId, String taskId, State state,
                       String details, Action action, String resolvedBy, String resolution) {
    }

    public static final class AlreadyResolvedException extends RuntimeException {
        public AlreadyResolvedException(long id) {
            super("reconciliation item " + id + " is not open");
        }
    }

    private final Dialect dialect;
    private final AuditChain audit;

    public ReconciliationQueue(Dialect dialect, AuditChain auditOrNull) {
        this.dialect = dialect;
        this.audit = auditOrNull;
    }

    /** Opens an item; an open item of the same kind for the same operation (or task) is reused. */
    public long open(Connection c, String tenant, Kind kind, UUID operationId, String rootId, String taskId, String detailsJson) {
        Optional<Long> existing = operationId != null
                ? Jdbc.one(c, "SELECT id FROM RECONCILIATION_ITEM WHERE tenant_id=? AND kind=? AND operation_id=? AND state='OPEN'",
                rs -> rs.getLong(1), tenant, kind, operationId)
                : taskId != null
                ? Jdbc.one(c, "SELECT id FROM RECONCILIATION_ITEM WHERE tenant_id=? AND kind=? AND task_id=? AND state='OPEN'",
                rs -> rs.getLong(1), tenant, kind, taskId)
                : Optional.empty();
        if (existing.isPresent()) {
            return existing.get();
        }
        return Jdbc.insertReturningId(c, dialect,
                "INSERT INTO RECONCILIATION_ITEM(tenant_id, kind, operation_id, root_id, task_id, state, details, created_at)"
                        + " VALUES (?,?,?,?,?,'OPEN',?," + dialect.now() + ")",
                "id", tenant, kind, operationId, rootId, taskId, detailsJson);
    }

    /**
     * Resolves an open item. {@link Action#RETRY_SAME_OPERATION} is refused for {@link Kind#LATE_RESULT_AFTER_DELETE}:
     * the instance is deleted and is never brought back to life (VI.5 rule 3).
     */
    public Item resolve(Connection c, long itemId, Action action, String actor, String resolutionJson) {
        Item item = get(c, itemId).orElseThrow(() -> new IllegalArgumentException("no reconciliation item " + itemId));
        if (item.kind() == Kind.LATE_RESULT_AFTER_DELETE && action == Action.RETRY_SAME_OPERATION) {
            throw new IllegalArgumentException("retry is not possible after the instance was deleted");
        }
        if (audit != null && item.rootId() != null) {
            RootSeq.lock(c, dialect, item.tenantId(), item.rootId());   // L2 surrogate before the L4-level ledger row (VI.1 rule 1)
        }
        int n = Jdbc.update(c, "UPDATE RECONCILIATION_ITEM SET state='RESOLVED', action=?, resolved_by=?, resolution=?, resolved_at="
                + dialect.now() + " WHERE id=? AND state='OPEN'", action, actor, resolutionJson, itemId);
        if (n == 0) {
            throw new AlreadyResolvedException(itemId);
        }
        if (item.operationId() != null) {
            switch (action) {
                case CONFIRM_SUCCESS -> Jdbc.update(c, "UPDATE OPERATION_LEDGER SET state='SUCCEEDED', outcome=?, updated_at=" + dialect.now()
                        + " WHERE operation_id=?", resolutionJson, item.operationId());
                case MARK_FAILED -> Jdbc.update(c, "UPDATE OPERATION_LEDGER SET state='FAILED', outcome=?, updated_at=" + dialect.now()
                        + " WHERE operation_id=?", resolutionJson, item.operationId());
                case RETRY_SAME_OPERATION -> Jdbc.update(c, "UPDATE OPERATION_LEDGER SET state='INTENT', updated_at=" + dialect.now()
                        + " WHERE operation_id=? AND state='UNKNOWN_OUTCOME'", item.operationId());
            }
        }
        if (audit != null && item.rootId() != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("itemId", itemId);
            p.put("kind", item.kind().name());
            p.put("action", action.name());
            p.put("actor", actor);
            p.put("operationId", item.operationId() == null ? null : item.operationId().toString());
            audit.append(c, item.tenantId(), item.rootId(), "RECONCILIATION_ACTION", p);
        }
        return get(c, itemId).orElseThrow();
    }

    public Optional<Item> get(Connection c, long id) {
        return Jdbc.one(c, "SELECT * FROM RECONCILIATION_ITEM WHERE id=?", ReconciliationQueue::map, id);
    }

    public List<Item> listOpen(Connection c, String tenant) {
        return Jdbc.query(c, "SELECT * FROM RECONCILIATION_ITEM WHERE tenant_id=? AND state='OPEN' ORDER BY id",
                ReconciliationQueue::map, tenant);
    }

    private static Item map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String op = rs.getString("operation_id");
        String action = rs.getString("action");
        return new Item(rs.getLong("id"), rs.getString("tenant_id"), Kind.valueOf(rs.getString("kind")),
                op == null ? null : UUID.fromString(op), rs.getString("root_id"), rs.getString("task_id"),
                State.valueOf(rs.getString("state")), rs.getString("details"), action == null ? null : Action.valueOf(action),
                rs.getString("resolved_by"), rs.getString("resolution"));
    }
}
