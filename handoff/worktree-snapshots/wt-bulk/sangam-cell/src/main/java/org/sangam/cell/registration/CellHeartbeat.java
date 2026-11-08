// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.registration;

/**
 * Periodic load report of a cell (default every 10 s). {@code status}: UP, DEGRADED, DRAINING. {@code outboxFillPct}
 * is the R40 gauge; at 50 % or more the control plane stops placing new tenants on the cell.
 */
public record CellHeartbeat(String cellId, String status, double startsPerSec, double p99CommandMs, int activeNodes,
                            double dbPoolUsagePct, double outboxFillPct, long tenants, String version) {
}
