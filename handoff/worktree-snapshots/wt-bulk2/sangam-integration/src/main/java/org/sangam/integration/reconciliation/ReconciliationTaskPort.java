// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.reconciliation;

import org.sangam.core.layer.ReconciliationQueue;

/**
 * Escalation of a reconciliation item to a human (OJ-02/OJ-03 reconciliation workbench; VI.3 rule 4). Implemented by
 * the engine/packs side: typically starts the {@code sangam-reconciliation} BPMN process whose user task offers the
 * three actions (confirm success with result / retry with the same operationId / mark failed) to the reconciliation
 * seat, and calls {@link ReconciliationEngine#resolveManually} when the task completes.
 */
@FunctionalInterface
public interface ReconciliationTaskPort {

    /**
     * Opens the human task; returns its id (stored on the attempt row). Must be idempotent per item id (the engine may
     * call it again after a crash): use the item id as business key.
     */
    String openHumanTask(ReconciliationQueue.Item item, String connectorId, String reason);
}
