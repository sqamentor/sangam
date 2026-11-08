// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.cells;

/**
 * Cell directory entry. {@code status} is the reported status (UP, DEGRADED, DRAINING) or DOWN (no heartbeat within
 * the timeout, computed by {@link CellDirectory}) or RETIRED (administrative). {@code placementOpen} is the
 * administrative switch for new placements.
 */
public record Cell(String cellId, String region, String jurisdiction, String residency, String apiBaseUrl,
                   String adminBaseUrl, String dbVendor, int capacityStartsPerSec, int capacityTenants, String status,
                   boolean placementOpen, double startsPerSec, double p99CommandMs, int activeNodes,
                   double dbPoolUsagePct, double outboxFillPct, long tenantsReported, String version,
                   long lastHeartbeatMs, long registeredAtMs) {

    public static final String UP = "UP";
    public static final String DEGRADED = "DEGRADED";
    public static final String DRAINING = "DRAINING";
    public static final String DOWN = "DOWN";
    public static final String RETIRED = "RETIRED";

    public boolean serving() {
        return UP.equals(status) || DEGRADED.equals(status) || DRAINING.equals(status);
    }

    public Cell withStatus(String s) {
        return new Cell(cellId, region, jurisdiction, residency, apiBaseUrl, adminBaseUrl, dbVendor, capacityStartsPerSec,
                capacityTenants, s, placementOpen, startsPerSec, p99CommandMs, activeNodes, dbPoolUsagePct, outboxFillPct,
                tenantsReported, version, lastHeartbeatMs, registeredAtMs);
    }
}
