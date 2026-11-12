// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.registration;

/**
 * What a cell tells the control plane at registration. {@code residency} is the data-residency zone of the cell's
 * database (for example {@code IN-RJ} or {@code IN}); placement never puts a tenant in a cell whose residency does not
 * satisfy the tenant's.
 *
 * <p>{@code dedicatedTenant} (M-016, decision 51) is the tenant whose own database this cell serves
 * ({@code sangam.tenancy.dedicated-tenant}), or {@code null} for a shared cell. The control plane then places only
 * that tenant on the cell, and a tenant recorded with isolation DATABASE only on its dedicated cell.
 */
public record CellDescriptor(String cellId, String region, String jurisdiction, String residency, String apiBaseUrl,
                             String adminBaseUrl, String dbVendor, int capacityStartsPerSec, int capacityTenants,
                             String version, String dedicatedTenant) {

    public CellDescriptor {
        dedicatedTenant = dedicatedTenant == null || dedicatedTenant.isBlank() ? null : dedicatedTenant.trim();
    }

    /** True for a cell bound to one tenant's database. */
    public boolean dedicated() {
        return dedicatedTenant != null;
    }
}