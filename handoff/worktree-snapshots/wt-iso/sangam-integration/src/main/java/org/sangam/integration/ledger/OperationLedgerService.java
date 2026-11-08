// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.ledger;

import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Operation ledger service (SANGAM.md VI.3 rules 4–5, R36, R37, A-2, A-8): the read and repair side of
 * {@code OPERATION_LEDGER} for the operator view, the delete/erase service and the worker API.
 *
 * <ul>
 *   <li>{@link #operationId} — the R36 identity, the same function the engine adapter and SDKs use.</li>
 *   <li>{@link #view} — ledger entry plus the connector call journal (provider handle, last attempt class).</li>
 *   <li>{@link #openOperations} — operations of a root that are still INTENT or UNKNOWN_OUTCOME (listed in the
 *       {@code INSTANCE_DELETED}/{@code INSTANCE_CANCELLED} audit event, VI.5 rule 5).</li>
 *   <li>{@link #reportUnknown} — a worker's {@code outcomeUnknown} report (VI.3 rule 4), fenced by lockVersion.</li>
 *   <li>{@link #recordOutcome} — a worker's definite outcome for an operation it executed itself (SDK path).</li>
 * </ul>
 */
public final class OperationLedgerService {

    public record OperationView(OperationLedger.Entry entry, ConnectorCallJournal.Call call) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final OperationLedger ledger;
    private final ConnectorCallJournal journal;

    public OperationLedgerService(Dialect dialect, Transactions tx, OperationLedger ledger, ConnectorCallJournal journal) {
        this.dialect = dialect;
        this.tx = tx;
        this.ledger = ledger;
        this.journal = journal;
    }

    public static OperationLedger create(Dialect dialect, ReconciliationQueue reconciliation) {
        return new OperationLedger(dialect, reconciliation);
    }

    public OperationLedger ledger() {
        return ledger;
    }

    public static UUID operationId(String tenant, String rootId, String activityInstanceId, int loopCounter, String retrySafeKey) {
        String ai = retrySafeKey == null || retrySafeKey.isEmpty() ? activityInstanceId : activityInstanceId + "#" + retrySafeKey;
        return OperationLedger.operationId(tenant, rootId, ai, loopCounter);
    }

    public Optional<OperationView> view(UUID operationId) {
        return tx.inTransaction(c -> ledger.find(c, operationId)
                .map(e -> new OperationView(e, journal.find(c, operationId).orElse(null))));
    }

    public List<UUID> openOperations(Connection c, String tenant, String rootId) {
        return Jdbc.query(c, "SELECT operation_id FROM OPERATION_LEDGER WHERE tenant_id=? AND root_id=?"
                        + " AND state IN ('INTENT','UNKNOWN_OUTCOME') ORDER BY created_at",
                rs -> UUID.fromString(rs.getString(1)), tenant, rootId);
    }

    public List<OperationLedger.Entry> byRoot(String tenant, String rootId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT operation_id FROM OPERATION_LEDGER WHERE tenant_id=? AND root_id=? ORDER BY created_at",
                rs -> UUID.fromString(rs.getString(1)), tenant, rootId).stream()
                .map(op -> ledger.find(c, op).orElseThrow()).toList());
    }

    /** The INTENT operation of an activity instance (any retry-safe key), for lease expiry and late results. */
    public Optional<UUID> intentOfActivity(Connection c, String activityInstanceId, String attemptIdOrNull) {
        List<UUID> ops = Jdbc.query(c, "SELECT operation_id FROM OPERATION_LEDGER WHERE (activity_instance_id=? OR activity_instance_id LIKE ?)"
                        + " AND state='INTENT'" + (attemptIdOrNull == null ? "" : " AND attempt_id=?") + " ORDER BY created_at",
                rs -> UUID.fromString(rs.getString(1)),
                attemptIdOrNull == null ? new Object[]{activityInstanceId, likeEscape(activityInstanceId) + "#%"}
                        : new Object[]{activityInstanceId, likeEscape(activityInstanceId) + "#%", attemptIdOrNull});
        return ops.isEmpty() ? Optional.empty() : Optional.of(ops.get(ops.size() - 1));
    }

    /** VI.3 rule 4: the worker cannot know whether the downstream call succeeded. Returns the reconciliation item id. */
    public long reportUnknown(UUID operationId, long lockVersion, String reason) {
        return tx.inTransaction(c -> {
            long id = ledger.markUnknown(c, operationId, lockVersion, reason);
            journal.find(c, operationId).ifPresent(call ->
                    journal.recordResult(c, operationId, ConnectorCallJournal.LastResult.UNKNOWN, null, reason));
            return id;
        });
    }

    /** A worker's definite outcome (fenced by lockVersion; identical repeats are no-ops). */
    public void recordOutcome(UUID operationId, long lockVersion, boolean success, String outcomeJson) {
        tx.inTransaction(c -> {
            ledger.recordOutcome(c, operationId, lockVersion, success, outcomeJson);
            return null;
        });
    }

    /** Records an intent for an SDK worker that calls the downstream itself (R37: intent before dispatch). */
    public OperationLedger.Intent recordIntent(OperationLedger.OperationRef ref, CapabilityClass cls, byte[] fingerprint,
                                               String attemptId, long lockVersion) {
        return tx.inTransaction(c -> ledger.recordIntent(c, ref, cls, fingerprint, attemptId, lockVersion));
    }

    private static String likeEscape(String s) {
        return s.replace("%", "").replace("_", "\\_");
    }

    Dialect dialect() {
        return dialect;
    }
}
