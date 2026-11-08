// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.registration;

/**
 * What a cell tells the control plane at registration. {@code residency} is the data-residency zone of the cell's
 * database (for example {@code IN-RJ} or {@code IN}); placement never puts a tenant in a cell whose residency does not
 * satisfy the tenant's.
 */
public record CellDescriptor(String cellId, String region, String jurisdiction, String residency, String apiBaseUrl,
                             String adminBaseUrl, String dbVendor, int capacityStartsPerSec, int capacityTenants,
                             String version) {
}
