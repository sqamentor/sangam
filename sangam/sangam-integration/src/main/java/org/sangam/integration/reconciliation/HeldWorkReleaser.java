// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.reconciliation;

import java.util.UUID;

/**
 * Releases external work that was held for reconciliation (A-10 HOLD / {@code failExternal(retries=0)} incident) once
 * its operation is resolved. The work is re-offered; the next attempt finds the ledger settled and completes with the
 * stored outcome (confirm/mark-failed) or dispatches again with the same operationId (retry), so no new effect is
 * created by the release itself. Operaton: {@code OperatonHeldWorkReleaser} ({@code ExternalTaskService.setRetries},
 * which also resolves the incident).
 */
@FunctionalInterface
public interface HeldWorkReleaser {

    /** Returns the number of work items released (0 when the instance no longer exists). */
    int release(String tenantId, String rootId, UUID operationId, String activityInstanceId);

    /**
     * SL-180 (owner decision 402): releases the held work of a resolved {@code UNKNOWN_OUTCOME} item. Besides the
     * parked work {@link #release(String, String, UUID, String)} finds, the item's own work {@code workId} is released
     * while it is still held by the maximum lock of the outcome-unknown report (retries left), so a resolved item is
     * offered again at once instead of after the lock ends. {@code operationId} and {@code rootId} may be null (an item
     * opened without a ledger operation); {@code workId} may be null (nothing of its own to release).
     */
    default int release(String tenantId, String rootId, UUID operationId, String activityInstanceId, String workId) {
        return rootId == null || operationId == null ? 0 : release(tenantId, rootId, operationId, activityInstanceId);
    }

    HeldWorkReleaser NONE = (t, r, o, a) -> 0;
}
