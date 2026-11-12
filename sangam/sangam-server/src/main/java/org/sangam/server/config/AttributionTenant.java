// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.core.layer.TenantKey;
import org.springframework.core.env.Environment;

/**
 * SL-86: the ONE resolver of the attribution tenant, the tenant that untenanted engine, integration, geo, modeller and
 * packs work is charged to. It is {@code sangam.tenant.default} when set and non-blank, otherwise {@value #FALLBACK}.
 *
 * <p>Before, each wiring read the property itself with its own fallback: the engine and integration skipped a blank
 * value and used {@code "default"}, while geo, modeller and packs fell back to {@code sangam.security.dev-token.tenant}
 * or {@code "dev"} only when the property was absent. {@code application.yaml} always defines it
 * ({@code ${SANGAM_TENANT_DEFAULT:}}, empty by default), so those three used {@code ""}, which Oracle stores as NULL.
 * The fallback is {@code "default"} because the engine's rows (L2 root lock, job identity, incidents, history) already
 * use it. The value may be a real tenant (single-tenant installations); it must not be the reserved shared key.
 */
public final class AttributionTenant {

    public static final String PROPERTY = "sangam.tenant.default";
    public static final String FALLBACK = "default";

    private AttributionTenant() {
    }

    public static String resolve(Environment env) {
        String t = env.getProperty(PROPERTY);
        String tenant = t == null || t.isBlank() ? FALLBACK : t.trim();
        try {
            return TenantKey.requireNotShared(tenant);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PROPERTY + ": " + e.getMessage(), e);
        }
    }
}
