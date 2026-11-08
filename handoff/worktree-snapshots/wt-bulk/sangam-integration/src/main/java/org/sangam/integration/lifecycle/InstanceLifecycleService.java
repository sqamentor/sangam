// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.lifecycle;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Tombstones;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.ledger.OperationLedgerService;
import org.sangam.integration.relay.BackpressureGate;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Business cancel and technical delete never hide external effects (SANGAM.md VI.5, R23, A-4, T10, T11, T39; OJ-03).
 *
 * <p>Both run in <b>one engine transaction</b> ({@code Transactions.of(engineAdapter)}; the Operaton adapter's
 * {@code inEngineTransaction} reuses the command context, so the engine call below joins it):
 * <ol>
 *   <li>Permission re-check inside the transaction ({@code CANCEL_INSTANCE} / {@code DELETE_INSTANCE}) and a mandatory
 *       reason.</li>
 *   <li>Every OFFERED/LOCKED external task of the instance tree goes to {@code CANCEL_REQUESTED}: a tombstone row with
 *       the task's operationId and lock version, kept until lease end + reconciliation retention (30 days).</li>
 *   <li>Audit {@code INSTANCE_CANCELLED} / {@code INSTANCE_DELETED} with actor, reason and the open operation ids
 *       (VI.5 rule 5) on the root's chain; it stays visible after the instance left the UI.</li>
 *   <li>The engine call: business cancel runs the model's cancel/compensation paths; technical delete removes the
 *       runtime rows and drops internal jobs.</li>
 * </ol>
 * Afterwards, late worker results go through {@link #onWorkerResult} (410 {@code GONE_INSTANCE_DELETED}, audited,
 * {@code LATE_RESULT_AFTER_DELETE} item when an effect happened; the instance is never revived), heartbeats report
 * {@link #cancelRequested}, a worker acknowledges with {@link #acknowledgeCancel}, and {@link #maintain} turns expired
 * un-acknowledged tombstones into UNKNOWN_OUTCOME items and purges old tombstones.
 */
public final class InstanceLifecycleService {

    public static final String CANCEL_INSTANCE = "CANCEL_INSTANCE";
    public static final String DELETE_INSTANCE = "DELETE_INSTANCE";

    /** What a cancel/delete did. */
    public record Result(String instanceId, int tombstones, List<UUID> openOperationIds) {
    }

    private final Dialect dialect;
    private final Transactions engineTx;
    private final Tombstones tombstones;
    private final AuditChain audit;
    private final OperationLedgerService ledger;
    private final LifecyclePorts.OpenWorkPort openWork;
    private final LifecyclePorts.EngineInstancePort engine;
    private final LifecyclePorts.Authorizer authorizer;
    private final BackpressureGate gate;

    public InstanceLifecycleService(Dialect dialect, Transactions engineTx, Tombstones tombstones, AuditChain audit,
                                    OperationLedgerService ledger, LifecyclePorts.OpenWorkPort openWork,
                                    LifecyclePorts.EngineInstancePort engine, LifecyclePorts.Authorizer authorizer,
                                    BackpressureGate gate) {
        this.dialect = dialect;
        this.engineTx = engineTx;
        this.tombstones = tombstones;
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ledger = ledger;
        this.openWork = openWork;
        this.engine = engine;
        this.authorizer = authorizer;
        this.gate = gate == null ? BackpressureGate.open() : gate;
    }

    public Result businessCancel(String tenantId, String rootId, String instanceId, String actor, String reason) {
        return run(tenantId, rootId, instanceId, actor, reason, CANCEL_INSTANCE, "INSTANCE_CANCELLED",
                c -> engine.businessCancel(c, instanceId, reason));
    }

    public Result technicalDelete(String tenantId, String rootId, String instanceId, String actor, String reason) {
        return run(tenantId, rootId, instanceId, actor, reason, DELETE_INSTANCE, "INSTANCE_DELETED",
                c -> engine.technicalDelete(c, instanceId, reason));
    }

    private Result run(String tenantId, String rootId, String instanceId, String actor, String reason, String permission,
                       String auditType, java.util.function.Consumer<Connection> engineCall) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required (R23)");
        }
        gate.check(BackpressureGate.Category.CANCELLATION);
        return engineTx.inTransaction(c -> {
            authorizer.require(c, tenantId, actor, permission, instanceId);
            List<UUID> open = ledger.openOperations(c, tenantId, rootId);
            // L2 (root chain) before the L4-level rows written below (VI.1 rule 1)
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("actor", actor);
            p.put("reason", reason);
            p.put("instanceId", instanceId);
            p.put("openOperationIds", open.stream().map(UUID::toString).toList());
            audit.append(c, tenantId, rootId, auditType, p);
            int written = 0;
            for (LifecyclePorts.OpenWork w : openWork.openWork(c, instanceId)) {
                if (tombstones.find(c, tenantId, w.workId()).isPresent()) {
                    continue;
                }
                UUID op = w.activityInstanceId() == null ? null : ledger.intentOfActivity(c, w.activityInstanceId(), null).orElse(null);
                Duration remaining = w.lockExpiresAt() == null ? Duration.ZERO
                        : Duration.between(Instant.now(), w.lockExpiresAt());
                tombstones.write(c, tenantId, w.workId(), op, rootId, w.lockVersion(), remaining.isNegative() ? Duration.ZERO : remaining);
                written++;
            }
            engineCall.accept(c);
            return new Result(instanceId, written, open);
        });
    }

    /**
     * A worker result (complete / fail / outcomeUnknown) for {@code taskId}: {@link Tombstones.NotTombstoned} means
     * handle it normally; {@link Tombstones.Gone} is answered {@code 410 GONE_INSTANCE_DELETED}.
     */
    public Tombstones.LateResultDecision onWorkerResult(String tenantId, String taskId, long lockVersion, Tombstones.ResultKind kind,
                                                        boolean effectHappened, String resultJson) {
        return engineTx.inTransaction(c -> tombstones.onLateResult(c, tenantId, taskId, lockVersion, kind, effectHappened, resultJson));
    }

    /** Heartbeat response field {@code cancelRequested} (VI.3.2 rule 3). */
    public boolean cancelRequested(String tenantId, String taskId) {
        return engineTx.inTransaction(c -> tombstones.find(c, tenantId, taskId)
                .map(t -> t.state() == Tombstones.State.CANCEL_REQUESTED).orElse(false));
    }

    /** The worker stopped without effect: CANCEL_REQUESTED → CANCELLED. */
    public boolean acknowledgeCancel(String tenantId, String taskId, long lockVersion) {
        return engineTx.inTransaction(c -> tombstones.ackCancel(c, tenantId, taskId, lockVersion));
    }

    /** VI.5 rule 4 sweep + tombstone purge (leader-elected). Returns {swept, purged}. */
    public int[] maintain() {
        int swept = engineTx.inTransaction(tombstones::sweepExpiredLeases);
        int purged = engineTx.inTransaction(tombstones::purgeExpired);
        return new int[]{swept, purged};
    }

    /** Operator view: tombstones of a root still waiting for a cancel acknowledgement. */
    public List<String> pendingCancels(String tenantId, String rootId) {
        return engineTx.inTransaction(c -> Jdbc.query(c, "SELECT task_id FROM TOMBSTONE WHERE tenant_id=? AND root_id=?"
                + " AND state='CANCEL_REQUESTED' ORDER BY created_at", rs -> rs.getString(1), tenantId, rootId));
    }

    Dialect dialect() {
        return dialect;
    }
}
