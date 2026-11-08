// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import java.util.Map;

/**
 * The one manual-resolution operation for reconciliation items (VI.4, A-10): resolve the item and the ledger row,
 * then release work the expiry policy held for it, so the instance continues (the released attempt reads the settled
 * ledger). Every caller (the officer's REST console, the workbench task) resolves through it; a bare
 * {@link ReconciliationQueue#resolve} leaves held work parked forever.
 */
public interface ReconciliationResolver {

    /**
     * @throws IllegalArgumentException the action is not allowed for the item (for example retry after a delete)
     * @throws ReconciliationQueue.AlreadyResolvedException the item is no longer open
     */
    ReconciliationQueue.Item resolve(long itemId, ReconciliationQueue.Action action, String actor,
                                     Map<String, Object> result, String reason, String evidence);
}
