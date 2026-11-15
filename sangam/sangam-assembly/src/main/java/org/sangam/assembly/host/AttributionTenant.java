// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.util.Objects;

import org.sangam.core.layer.TenantKey;

/**
 * SL-86: the ONE resolver of the attribution tenant, the tenant that untenanted engine, integration, geo, modeller and
 * packs work is charged to, as plain Java for every host (extraction phase 3, owner decision 276). It is
 * {@value #PROPERTY} when set and non-blank, otherwise {@value #FALLBACK}.
 *
 * <p>The fallback is {@code "default"} because the engine's rows (L2 root lock, job identity, incidents, history)
 * already use it; the dev token's tenant is never a fallback. The value may be a real tenant (single-tenant
 * installations); the reserved shared key is refused, naming the property. The Spring host's
 * {@code org.sangam.server.config.AttributionTenant} reads its {@code Environment} through this class; the Quarkus host
 * reads its MicroProfile configuration through it, so both hosts resolve one value the same way.
 */
public final class AttributionTenant {

    public static final String PROPERTY = "sangam.tenant.default";
    public static final String FALLBACK = "default";

    private AttributionTenant() {
    }

    /**
     * The attribution tenant of {@code properties}.
     *
     * @throws IllegalStateException naming {@value #PROPERTY} when the value is the reserved shared key
     */
    public static String resolve(SangamProperties properties) {
        Objects.requireNonNull(properties, "properties");
        String t = properties.get(PROPERTY);
        String tenant = t == null || t.isBlank() ? FALLBACK : t.trim();
        try {
            return TenantKey.requireNotShared(tenant);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PROPERTY + ": " + e.getMessage(), e);
        }
    }
}
