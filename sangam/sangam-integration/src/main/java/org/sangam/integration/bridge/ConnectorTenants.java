// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge;

import java.util.List;
import java.util.Map;

import org.sangam.core.tenant.ExecutionTenantPolicy;

/**
 * DEC-145 (owner decision 145) for the integration bridge: the tenant of connector and send-event work is the engine
 * tenant of the execution or external task, and nothing else. Connector work is tenant-owned
 * ({@link ExecutionTenantPolicy#tenantOwned}): it selects the tenant's connector binding, credentials and outbox
 * stream, so work without an engine tenant fails closed ({@code TENANT_CONTEXT_MISSING}) and is never given a configured
 * default tenant. The tenant variables {@value #SANGAM_TENANT_VARIABLE} (set by the engine plugin) and
 * {@value #TENANT_VARIABLE} (pack data) are untrusted claims: neither substitutes for or overrides the engine tenant,
 * and one that differs is refused ({@code TENANT_CONTEXT_CONFLICT}) before any connector call or outbox write.
 */
public final class ConnectorTenants {

    /** The tenant variable the Sangam engine plugin sets on every instance start (a claim, never the source). */
    public static final String SANGAM_TENANT_VARIABLE = "sangamTenantId";
    /** The tenant case variable of packs (sangam-packs {@code Vars.TENANT_VARIABLE}; a claim, never the source). */
    public static final String TENANT_VARIABLE = "tenantId";
    /** Every variable that has been read as a tenant: each is checked against the engine tenant. */
    public static final List<String> CLAIM_VARIABLES = List.of(SANGAM_TENANT_VARIABLE, TENANT_VARIABLE);

    private ConnectorTenants() {
    }

    /**
     * The engine tenant of tenant-owned connector work.
     *
     * @param engineTenant the tenant id on the execution or external task (null when the engine has none)
     * @param variables    the work's variables (the claims are read from them), or null
     * @param label        fixed label of the calling site (never data)
     * @throws org.sangam.core.tenant.TenantResolutionException when the engine tenant is missing or a claim differs
     */
    public static String tenantOwned(String engineTenant, Map<String, ?> variables, String label) {
        String resolved = ExecutionTenantPolicy.tenantOwned(engineTenant, null, label).tenantId();
        if (variables != null) {
            for (String name : CLAIM_VARIABLES) {
                Object v = variables.get(name);
                // a non-text value is still a claim, never ignored
                ExecutionTenantPolicy.tenantOwned(engineTenant, v == null ? null : v.toString(), label);
            }
        }
        return resolved;
    }
}
