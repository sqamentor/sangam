package org.sangam.core.layer;

import java.sql.Connection;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Technical delete never hides external effects (SANGAM.md VI.5).
 *
 * <ul>
 *   <li>Before runtime rows are removed, every OFFERED/LOCKED task goes to CANCEL_REQUESTED and gets a tombstone
 *       ({@link #write}); {@code expires_at} = lease end + reconciliation retention (default 30 days).
 *       {@link #recordDeletion} writes the {@code INSTANCE_DELETED} audit event with the open operation ids.</li>
 *   <li>A late worker result matching a tombstone ({@link #onLateResult}) gets {@code 410 GONE_INSTANCE_DELETED}, is
 *       audited ({@code LATE_RESULT_AFTER_DELETE}), and opens a {@code LATE_RESULT_AFTER_DELETE} reconciliation item when
 *       the result says an effect happened. The instance is never revived.</li>
 *   <li>A tombstoned lease that expires without a cancel acknowledgement becomes UNKNOWN_OUTCOME
 *       ({@link #sweepExpiredLeases}).</li>
 * </ul>
 * Internal jobs of the deleted instance are dropped by the engine adapter (VI.5 rule 2), not here.
 */
public final class Tombstones {

    public enum State { CANCEL_REQUESTED, CANCELLED, UNKNOWN_OUTCOME }

    public enum ResultKind { COMPLETE, FAIL, OUTCOME_UNKNOWN }

    public record Tombstone(String tenantId, String taskId, UUID operationId, String rootId, long lockVersion, State state) {
    }

    public sealed interface LateResultDecision permits NotTombstoned, Gone {
    }

    /** No tombstone: handle the result normally. */
    public record NotTombstoned() implements LateResultDecision {
    }

    /** {@code 410 GONE_INSTANCE_DELETED}; {@code reconciliationItemId} is null when no effect was reported. */
    public record Gone(int httpStatus, String code, Long reconciliationItemId) implements LateResultDecision {
    }

    private final Dialect dialect;
    private final ReconciliationQueue reconciliation;
    private final AuditChain audit;
    private final Duration retention;

    public Tombstones(Dialect dialect, ReconciliationQueue reconciliation, AuditChain audit, Duration retention) {
        this.dialect = dialect;
        this.reconciliation = reconciliation;
        this.audit = audit;
        this.retention = retention;
    }

    public Tombstones(Dialect dialect, ReconciliationQueue reconciliation, AuditChain audit) {
        this(dialect, reconciliation, audit, Duration.ofDays(30));
    }

    /** Writes a CANCEL_REQUESTED tombstone for one task in the delete transaction. */
    public void write(Connection c, String tenant, String taskId, UUID operationId, String rootId, long lockVersion,
                      Duration remainingLease) {
        long lease = Math.max(0, remainingLease.toMillis());
        Jdbc.update(c, "INSERT INTO TOMBSTONE(tenant_id, task_id, operation_id, root_id, lock_version, state, created_at,"
                        + " lease_expires_at, expires_at) VALUES (?,?,?,?,?,'CANCEL_REQUESTED'," + dialect.now() + ","
                        + dialect.nowPlusMillisParam() + "," + dialect.nowPlusMillisParam() + ")",
                tenant, taskId, operationId, rootId, lockVersion, lease, lease + retention.toMillis());
    }

    /** {@code INSTANCE_DELETED} audit event: actor, reason and the open operation ids (VI.5 rule 5). */
    public void recordDeletion(Connection c, String tenant, String rootId, String actor, String reason, List<UUID> openOperationIds) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("actor", actor);
        p.put("reason", reason);
        p.put("openOperationIds", openOperationIds.stream().map(UUID::toString).toList());
        audit.append(c, tenant, rootId, "INSTANCE_DELETED", p);
    }

    public Optional<Tombstone> find(Connection c, String tenant, String taskId) {
        return Jdbc.one(c, "SELECT tenant_id, task_id, operation_id, root_id, lock_version, state FROM TOMBSTONE WHERE tenant_id=? AND task_id=?",
                rs -> new Tombstone(rs.getString(1), rs.getString(2), rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)),
                        rs.getString(4), rs.getLong(5), State.valueOf(rs.getString(6))), tenant, taskId);
    }

    /**
     * Handles a worker result for a task that may have been deleted.
     *
     * @param effectHappened whether the result reports that an external effect happened. For
     *                       {@link ResultKind#OUTCOME_UNKNOWN} an item is always opened (the effect may have happened).
     */
    public LateResultDecision onLateResult(Connection c, String tenant, String taskId, long lockVersion, ResultKind kind,
                                           boolean effectHappened, String resultJson) {
        Optional<Tombstone> t = find(c, tenant, taskId);
        if (t.isEmpty()) {
            return new NotTombstoned();
        }
        Tombstone ts = t.get();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("taskId", taskId);
        p.put("operationId", ts.operationId() == null ? null : ts.operationId().toString());
        p.put("lockVersion", lockVersion);
        p.put("resultKind", kind.name());
        p.put("effectHappened", effectHappened);
        audit.append(c, tenant, ts.rootId(), "LATE_RESULT_AFTER_DELETE", p);
        Long item = null;
        if (effectHappened || kind == ResultKind.OUTCOME_UNKNOWN) {
            Map<String, Object> d = new LinkedHashMap<>(p);
            d.put("result", resultJson);
            item = reconciliation.open(c, tenant, ReconciliationQueue.Kind.LATE_RESULT_AFTER_DELETE, ts.operationId(), ts.rootId(),
                    taskId, CanonicalJson.write(d));
        }
        return new Gone(410, "GONE_INSTANCE_DELETED", item);
    }

    /** The worker acknowledged the cooperative cancel: the tombstone becomes CANCELLED (no effect pending). */
    public boolean ackCancel(Connection c, String tenant, String taskId, long lockVersion) {
        return Jdbc.update(c, "UPDATE TOMBSTONE SET state='CANCELLED' WHERE tenant_id=? AND task_id=? AND lock_version=? AND state='CANCEL_REQUESTED'",
                tenant, taskId, lockVersion) == 1;
    }

    /** VI.5 rule 4: tombstoned leases that expired without a cancel ack become UNKNOWN_OUTCOME items. */
    public int sweepExpiredLeases(Connection c) {
        List<Tombstone> due = Jdbc.query(c, dialect.skipLockedSelect("t.tenant_id, t.task_id, t.operation_id, t.root_id, t.lock_version, t.state",
                        "TOMBSTONE", "t", "t.state='CANCEL_REQUESTED' AND t.lease_expires_at < " + dialect.now(), "t.lease_expires_at", 500),
                rs -> new Tombstone(rs.getString(1), rs.getString(2), rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)),
                        rs.getString(4), rs.getLong(5), State.valueOf(rs.getString(6))));
        for (Tombstone t : due) {
            Jdbc.update(c, "UPDATE TOMBSTONE SET state='UNKNOWN_OUTCOME' WHERE tenant_id=? AND task_id=?", t.tenantId(), t.taskId());
            if (t.operationId() != null) {
                Jdbc.update(c, "UPDATE OPERATION_LEDGER SET state='UNKNOWN_OUTCOME', updated_at=" + dialect.now()
                        + " WHERE operation_id=? AND state='INTENT'", t.operationId());
            }
            reconciliation.open(c, t.tenantId(), ReconciliationQueue.Kind.UNKNOWN_OUTCOME, t.operationId(), t.rootId(), t.taskId(),
                    CanonicalJson.write(Map.of("reason", "lease expired on a deleted instance without cancel acknowledgement")));
        }
        return due.size();
    }

    /** Removes tombstones past {@code expires_at} that have no open reconciliation item (VI.5.1: never delete open items). */
    public int purgeExpired(Connection c) {
        return Jdbc.update(c, "DELETE FROM TOMBSTONE WHERE expires_at < " + dialect.now()
                + " AND NOT EXISTS (SELECT 1 FROM RECONCILIATION_ITEM r WHERE r.tenant_id = TOMBSTONE.tenant_id"
                + " AND r.task_id = TOMBSTONE.task_id AND r.state = 'OPEN')");
    }
}
