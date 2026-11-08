// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.registry;

import java.util.Locale;

/**
 * Data-residency zones are hierarchical codes: {@code IN} contains {@code IN-RJ}, which contains {@code IN-RJ-SDC1}.
 * A tenant with zone Z may live only in a cell whose zone is Z or inside Z (SCALE-ARCHITECTURE.md section 9):
 * a Rajasthan tenant requiring {@code IN-RJ} never lands in {@code IN-MH} or in a generic {@code IN} cell.
 */
public final class ResidencyRules {

    private ResidencyRules() {
    }

    /** True when {@code zone} equals {@code container} or lies inside it. */
    public static boolean within(String zone, String container) {
        if (zone == null || container == null) {
            return false;
        }
        String z = zone.trim().toUpperCase(Locale.ROOT);
        String c = container.trim().toUpperCase(Locale.ROOT);
        return z.equals(c) || z.startsWith(c + "-");
    }

    /** A cell may host the tenant when the cell's zone lies within the tenant's required zone. */
    public static boolean cellSatisfies(String cellZone, String tenantZone) {
        return within(cellZone, tenantZone);
    }
}
