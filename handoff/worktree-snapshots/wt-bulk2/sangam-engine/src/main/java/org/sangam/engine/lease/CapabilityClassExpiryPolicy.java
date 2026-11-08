// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.lease;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

import org.sangam.core.engine.EngineMetrics;
import org.sangam.core.engine.OperationAwareExpiryPolicy;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;

/**
 * Default lease-expiry policy (A-10, A-8, VI.3 rule 5, FLOWABLE-INTO-OPERATON D-21): decides <b>in the transaction that ends the lease and
 * before the work can be offered again</b>.
 *
 * <ul>
 *   <li>If the operation has a ledger row ({@code OPERATION_LEDGER}, found by operationId or by the engine work id,
 *       A-14), the core {@link OperationLedger#onLeaseExpired} applies the capability class: class (c) moves the
 *       operation to UNKNOWN_OUTCOME and opens a reconciliation item; the work is then <b>held</b>.</li>
 *   <li>Without a ledger row, the declared class decides: (a)/(b) re-offer, (c) or undeclared (A-8 default) hold.</li>
 * </ul>
 * A held work item becomes an incident ({@code LEASE_EXPIRED_HELD}), visible in Cockpit and in the reconciliation
 * console; it is released with {@code setRetries(id, n)} after reconciliation.
 */
public final class CapabilityClassExpiryPolicy implements OperationAwareExpiryPolicy {

    private final OperationLedger ledger;
    private final EngineMetrics metrics;

    public CapabilityClassExpiryPolicy(Dialect coreDialect, EngineMetrics metrics) {
        this.ledger = new OperationLedger(coreDialect, new ReconciliationQueue(coreDialect, new AuditChain(coreDialect)));
        this.metrics = metrics == null ? EngineMetrics.NONE : metrics;
    }

    @Override
    public Decision onExpired(ExpiredWork work, Context ctx, Connection c) {
        Optional<UUID> op = ledgerOperation(c, ctx.operationId(), work.workId());
        Decision decision;
        if (op.isPresent()) {
            CapabilityClass.LeaseExpiryAction action = ledger.onLeaseExpired(c, op.get(), work.lockVersion() + 1);
            decision = action == CapabilityClass.LeaseExpiryAction.UNKNOWN_OUTCOME ? Decision.HOLD : Decision.REOFFER;
        } else {
            CapabilityClass cls = CapabilityClass.fromDeclaration(ctx.capabilityClass());
            decision = cls.allowsAutomaticRetry() ? Decision.REOFFER : Decision.HOLD;
        }
        metrics.leaseExpired(work.topic(), decision.name());
        return decision;
    }

    private static Optional<UUID> ledgerOperation(Connection c, UUID operationId, String workId) {
        if (operationId != null) {
            Optional<String> byId = Jdbc.one(c, "SELECT operation_id FROM OPERATION_LEDGER WHERE operation_id = ?",
                    rs -> rs.getString(1), operationId.toString());
            if (byId.isPresent()) {
                return Optional.of(operationId);
            }
        }
        return Jdbc.query(c, "SELECT operation_id FROM OPERATION_LEDGER WHERE engine_work_id = ? AND state = 'INTENT'",
                rs -> UUID.fromString(rs.getString(1)), workId).stream().findFirst();
    }
}
