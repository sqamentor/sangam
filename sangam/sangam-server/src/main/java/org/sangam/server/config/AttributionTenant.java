// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.springframework.core.env.Environment;

/**
 * SL-86: the Spring host's view of the ONE resolver of the attribution tenant, the tenant that untenanted engine,
 * integration, geo, modeller and packs work is charged to. The rule itself ({@code sangam.tenant.default} when set and
 * non-blank, otherwise {@value #FALLBACK}; the reserved shared key refused) is plain Java in the assembly
 * ({@link org.sangam.assembly.host.AttributionTenant}), so every host resolves the same value; this class only reads
 * Spring's {@code Environment} through it.
 *
 * <p>Before SL-86, each wiring read the property itself with its own fallback: the engine and integration skipped a
 * blank value and used {@code "default"}, while geo, modeller and packs fell back to
 * {@code sangam.security.dev-token.tenant} or {@code "dev"} only when the property was absent. {@code application.yaml}
 * always defines it ({@code ${SANGAM_TENANT_DEFAULT:}}, empty by default), so those three used {@code ""}, which Oracle
 * stores as NULL.
 */
public final class AttributionTenant {

    public static final String PROPERTY = org.sangam.assembly.host.AttributionTenant.PROPERTY;
    public static final String FALLBACK = org.sangam.assembly.host.AttributionTenant.FALLBACK;

    private AttributionTenant() {
    }

    public static String resolve(Environment env) {
        return org.sangam.assembly.host.AttributionTenant.resolve(env::getProperty);
    }
}
