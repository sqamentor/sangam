// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.ledger;

import org.sangam.core.api.ExpiryPolicy;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * The A-10 lease-expiry hook backed by the operation ledger (SANGAM.md XI.10.3 A-10, A-8, VI.3.1, VI.5 rule 4;
 * fixes FLOWABLE-INTO-OPERATON D-20/FLOWABLE-INTO-OPERATON D-21, the measured double payment E3).
 *
 * <p>Installed with {@code EngineAdapter.setExpiryPolicy(...)}. Runs in the engine transaction that ends the lease,
 * before the work can be fetched again:
 * <ul>
 *   <li>Tombstoned work (instance deleted): {@link Decision#HOLD}; the tombstone sweep turns it into UNKNOWN_OUTCOME.</li>
 *   <li>No INTENT operation for the attempt: nothing was dispatched, {@link Decision#REOFFER}.</li>
 *   <li>Otherwise the ledger is fenced to the next lock version and the capability class decides: (a)
 *       {@link Decision#REOFFER} with the same operationId; (b) {@link Decision#REOFFER}, the next attempt looks up
 *       first (the {@code ConnectorInvoker} does this); (c) UNKNOWN_OUTCOME + reconciliation item and
 *       {@link Decision#HOLD}: no automatic repeat.</li>
 * </ul>
 */
public final class LedgerExpiryPolicy implements ExpiryPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerExpiryPolicy.class);

    private final OperationLedgerService ledgerService;

    public LedgerExpiryPolicy(OperationLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Override
    public Decision onExpired(ExpiredWork work, Connection c) {
        boolean tombstoned = Jdbc.one(c, "SELECT 1 FROM TOMBSTONE WHERE task_id=? AND state='CANCEL_REQUESTED'", rs -> 1,
                work.workId()).isPresent();
        if (tombstoned) {
            return Decision.HOLD;
        }
        if (work.activityInstanceId() == null) {
            return Decision.REOFFER;
        }
        Optional<UUID> op = ledgerService.intentOfActivity(c, work.activityInstanceId(), work.attemptId());
        if (op.isEmpty()) {
            op = ledgerService.intentOfActivity(c, work.activityInstanceId(), null);
        }
        if (op.isEmpty()) {
            // A worker reported outcome-unknown (the lease was kept for reconciliation): never re-offer it on expiry.
            boolean awaitingReconciliation = !Jdbc.query(c,"SELECT 1 FROM OPERATION_LEDGER WHERE (activity_instance_id=? OR"
                    + " activity_instance_id LIKE ?) AND state='UNKNOWN_OUTCOME'", rs -> 1, work.activityInstanceId(),
                    work.activityInstanceId().replace("%", "").replace("_", "\\_") + "#%").isEmpty();
            return awaitingReconciliation ? Decision.HOLD : Decision.REOFFER;
        }
        OperationLedger ledger = ledgerService.ledger();
        CapabilityClass.LeaseExpiryAction action;
        try {
            action = ledger.onLeaseExpired(c, op.get(), work.lockVersion() + 1);
        } catch (OperationLedger.StaleLeaseException e) {
            // a newer attempt already moved the ledger on; this expiry concerns an older lease
            return Decision.REOFFER;
        }
        LOG.info("lease of {} (operation {}) expired: {}", work.workId(), op.get(), action);
        return action == CapabilityClass.LeaseExpiryAction.UNKNOWN_OUTCOME ? Decision.HOLD : Decision.REOFFER;
    }
}
