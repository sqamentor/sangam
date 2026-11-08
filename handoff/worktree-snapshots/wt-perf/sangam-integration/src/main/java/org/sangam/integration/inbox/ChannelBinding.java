// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import java.util.Objects;

/**
 * The authenticated inbound channel a message arrived on (SANGAM.md VI.2.6 rule 5, R38, VI.4 rule 6).
 *
 * <p>The tenant of an inbound message comes <b>only</b> from here: the channel's configuration (a Kafka topic, JMS
 * queue or webhook path bound to one tenant) or the authenticated caller (mTLS subject, verified webhook signature).
 * A tenant id inside the payload is never trusted on its own; {@link #tenantFor(String)} accepts it only when it
 * equals the bound tenant or when the binding is explicitly multi-tenant and the claimed tenant is in its allowed set.
 *
 * @param source         inbox {@code source} (dedup scope), e.g. {@code kafka:payments}, {@code webhook:pg}, {@code pfms}
 * @param tenantId       the bound tenant, or null for a multi-tenant binding
 * @param allowedTenants for a multi-tenant binding: the tenants the authenticated source may speak for
 * @param principal      the authenticated source (certificate subject, key id), for audit
 */
public record ChannelBinding(String source, String tenantId, java.util.Set<String> allowedTenants, String principal) {

    public ChannelBinding {
        Objects.requireNonNull(source, "source");
        allowedTenants = allowedTenants == null ? java.util.Set.of() : java.util.Set.copyOf(allowedTenants);
        if (tenantId == null && allowedTenants.isEmpty()) {
            throw new IllegalArgumentException("a channel binding needs a tenant or an allowed-tenant set (R38)");
        }
    }

    public static ChannelBinding of(String source, String tenantId, String principal) {
        return new ChannelBinding(source, tenantId, null, principal);
    }

    /** Resolves the tenant for one message; {@code claimedTenant} may come from a header or the payload. */
    public String tenantFor(String claimedTenant) {
        if (tenantId != null) {
            if (claimedTenant != null && !claimedTenant.equals(tenantId)) {
                throw new SecurityException("message claims tenant " + claimedTenant + " on a channel bound to " + tenantId);
            }
            return tenantId;
        }
        if (claimedTenant == null || !allowedTenants.contains(claimedTenant)) {
            throw new SecurityException("tenant " + claimedTenant + " is not allowed on channel " + source);
        }
        return claimedTenant;
    }
}
