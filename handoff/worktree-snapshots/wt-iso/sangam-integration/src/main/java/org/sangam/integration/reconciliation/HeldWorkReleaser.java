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

    HeldWorkReleaser NONE = (t, r, o, a) -> 0;
}
