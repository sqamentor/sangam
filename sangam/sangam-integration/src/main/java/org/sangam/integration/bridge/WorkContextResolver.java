// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge;

import org.sangam.core.api.EngineAdapter;

/**
 * Engine identity of one unit of external work: tenant, root instance, business key. DEC-145 (owner decision 145): the
 * tenant is the work's engine tenant ({@link EngineAdapter.ExternalWork#tenantId()}) and nothing else; the
 * {@code sangamTenantId} and {@code tenantId} variables are untrusted claims that must agree with it
 * ({@link ConnectorTenants}), and there is no configured default tenant. The root instance and business key come from
 * {@code sangamRootId} and {@code sangamBusinessKey}, which the Sangam engine plugin sets on every instance start
 * (WS-ENGINE), falling back to the process instance id.
 */
@FunctionalInterface
public interface WorkContextResolver {

    record WorkContext(String tenantId, String rootId, String businessKey, int loopCounter) {
    }

    /**
     * @throws org.sangam.core.tenant.TenantResolutionException when the work has no engine tenant or a tenant variable
     *                                                          differs from it
     */
    WorkContext resolve(EngineAdapter.ExternalWork work);

    /** The engine-tenant resolver of tenant-owned connector work (decision 145). */
    static WorkContextResolver engineTenant() {
        return w -> {
            String tenant = ConnectorTenants.tenantOwned(w.tenantId(), w.variables(), "external-task connector worker");
            Object r = w.variables() == null ? null : w.variables().get("sangamRootId");
            Object bk = w.variables() == null ? null : w.variables().get("sangamBusinessKey");
            Object loop = w.variables() == null ? null : w.variables().get("loopCounter");
            int lc = loop instanceof Number n ? n.intValue() : 0;
            return new WorkContext(tenant, r == null ? w.processInstanceId() : r.toString(),
                    bk == null ? null : bk.toString(), lc);
        };
    }
}
