// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.move;

import java.util.Map;

/**
 * Port: the control plane's commands to a cell's tenant-admin API for a move. Every call is idempotent on
 * {@code moveId} (sent as Idempotency-Key). Data flows cell to cell (export handle pulled by the target), never through
 * the control plane, so residency is preserved. The cell-side endpoints are integration request IR-4 (README).
 */
public interface CellOps {

    /** Target: create the tenant in IMPORTING state (rejects traffic, accepts import). */
    void prepareImport(String targetCell, String tenant, String moveId);

    /** Source: stop new starts and inbound events for the tenant (503 Retry-After), let in-flight work finish. */
    void freeze(String sourceCell, String tenant, String moveId);

    /** Source: drain progress: {@code inFlightCommands}, {@code pendingOutbox}, {@code lockedWork}, {@code dueTimers}. */
    DrainStatus drainStatus(String sourceCell, String tenant);

    /** Source: consistent export of every tenant row (engine + product tables, audit chain); returns a pull handle. */
    ExportHandle export(String sourceCell, String tenant, String moveId);

    /** Target: pull the export from the source and load it; returns rows loaded per table. */
    Map<String, Long> importFrom(String targetCell, String tenant, String moveId, ExportHandle handle);

    /** Per-table row counts and order-independent content hash of a tenant's rows; equal on both sides = verified. */
    TenantChecksum checksum(String cell, String tenant);

    /** Target: open for traffic, append TENANT_MOVED_IN to its audit chain anchored on the source chain head. */
    void activate(String targetCell, String tenant, String moveId, String sourceAuditHead);

    /** Source: mark MOVED (answers 421 Misdirected so stale gateways re-resolve), keep data until purge. */
    void markMoved(String sourceCell, String tenant, String moveId, String targetCell);

    /** Source rollback: unfreeze. Target rollback: drop the partial import. */
    void unfreeze(String sourceCell, String tenant, String moveId);

    void dropImport(String targetCell, String tenant, String moveId);

    /** Source: purge after the retention window (legal-hold aware on the cell side, R61). */
    void purge(String sourceCell, String tenant, String moveId);

    record DrainStatus(long inFlightCommands, long pendingOutbox, long lockedWork, long dueTimers) {
        public boolean drained() {
            return inFlightCommands == 0 && pendingOutbox == 0 && lockedWork == 0;
        }
    }

    record ExportHandle(String url, String token, long rows, String watermark, String auditHead) {
    }

    record TenantChecksum(Map<String, Long> rowCounts, String contentHash) {
    }
}
