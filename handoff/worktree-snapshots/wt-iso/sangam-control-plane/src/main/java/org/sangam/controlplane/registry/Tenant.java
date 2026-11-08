// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.registry;

/**
 * A tenant node of the n-level hierarchy (nation, state/UT, department, district, hospital/office, ...; the level
 * names are data, ADR-D-20). {@code path} is the {@code /}-joined chain of tenant codes from the root. {@code cellId}
 * is set for placed tenants; a pure grouping node (for example a nation) may have none.
 *
 * <p>Status: ACTIVE, DRAINING (a move is freezing it), MOVING (copy/verify running), RETIRED.
 */
public record Tenant(String tenantCode, String parentCode, String path, int depth, String levelKind, String name,
                     String jurisdiction, String residency, String sector, double expectedStartsPerSec, String cellId,
                     String status, long routeEpoch, long createdAtMs, long updatedAtMs) {

    public static final String ACTIVE = "ACTIVE";
    public static final String DRAINING = "DRAINING";
    public static final String MOVING = "MOVING";
    public static final String RETIRED = "RETIRED";

    public boolean routable() {
        return cellId != null && !RETIRED.equals(status);
    }
}
