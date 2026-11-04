// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge;

import org.sangam.core.api.EngineAdapter;

/**
 * Engine identity of one unit of external work: tenant, root instance, business key. The default reads the variables
 * {@code sangamTenantId}, {@code sangamRootId} and {@code sangamBusinessKey}, which the Sangam engine plugin sets on
 * every instance start (WS-ENGINE), and falls back to the process instance id and a configured default tenant.
 * The tenant is never taken from client-supplied data (VI.4 rule 6).
 */
@FunctionalInterface
public interface WorkContextResolver {

    record WorkContext(String tenantId, String rootId, String businessKey, int loopCounter) {
    }

    WorkContext resolve(EngineAdapter.ExternalWork work);

    static WorkContextResolver fromVariables(String defaultTenant) {
        return w -> {
            Object t = w.variables().get("sangamTenantId");
            Object r = w.variables().get("sangamRootId");
            Object bk = w.variables().get("sangamBusinessKey");
            Object loop = w.variables().get("loopCounter");
            int lc = loop instanceof Number n ? n.intValue() : 0;
            return new WorkContext(t == null ? defaultTenant : t.toString(), r == null ? w.processInstanceId() : r.toString(),
                    bk == null ? null : bk.toString(), lc);
        };
    }
}
