// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.move;

/**
 * A tenant move. States: REQUESTED, PREPARING, DRAINING, COPYING, VERIFYING, CUT_OVER, RETAINING (source kept for the
 * rollback window), COMPLETED, ROLLING_BACK, ROLLED_BACK, FAILED.
 */
public record TenantMove(String moveId, String tenantCode, String sourceCell, String targetCell, String state,
                         String processInstanceId, String requestedBy, String reason, long rowsCopied,
                         String checksumSource, String checksumTarget, String sourceWatermark, long startedAtMs,
                         Long finishedAtMs, String lastError) {

    public boolean active() {
        return !("COMPLETED".equals(state) || "ROLLED_BACK".equals(state) || "FAILED".equals(state));
    }
}
