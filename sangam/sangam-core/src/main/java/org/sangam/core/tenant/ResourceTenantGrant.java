// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.tenant;

import org.sangam.core.layer.TenantKey;

/**
 * An explicitly authorised tenant context for shared work on a tenant-owned resource (owner decision 145), read by the
 * caller from the resource's authoritative metadata (for example the owning tenant stored with a batch, a job
 * definition or a product row), never from a process or case variable.
 *
 * @param tenantId     the owning tenant; never blank and never the reserved shared key
 * @param resourceType the kind of resource the ownership was read from (recorded with the resolution)
 * @param resourceId   the resource's id (recorded with the resolution)
 * @param authority    where the ownership was read (the metadata source), recorded as the resolution evidence
 */
public record ResourceTenantGrant(String tenantId, String resourceType, String resourceId, String authority) {

    public ResourceTenantGrant {
        requireText(tenantId, "tenantId");
        requireText(resourceType, "resourceType");
        requireText(resourceId, "resourceId");
        requireText(authority, "authority");
        TenantKey.requireNotShared(tenantId);
    }

    private static void requireText(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("resource tenant grant: " + name + " is required");
        }
    }
}
