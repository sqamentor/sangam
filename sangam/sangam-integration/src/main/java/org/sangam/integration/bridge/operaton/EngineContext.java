// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge.operaton;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;

import org.sangam.integration.bridge.ConnectorTenants;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/** Small helpers over Operaton internals used by the bridge (engine identity, the command's JDBC connection). */
final class EngineContext {

    private EngineContext() {
    }

    /** The JDBC connection of the current engine command (product rows commit with engine state, Q1). */
    static Connection connection() {
        // Operaton 2.2: Context.getCommandContext() throws outside a command (ENGINE-09025), it never answers null
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (cc == null) {
            throw new IllegalStateException("no Operaton command context on this thread");
        }
        return cc.getDbSqlSession().getSqlSession().getConnection();
    }

    static String rootId(DelegateExecution execution) {
        if (execution instanceof ExecutionEntity e && e.getRootProcessInstanceId() != null) {
            return e.getRootProcessInstanceId();
        }
        return execution.getProcessInstanceId();
    }

    /**
     * DEC-145: the engine tenant of tenant-owned connector or send-event work ({@link ConnectorTenants#tenantOwned});
     * the tenant variables are untrusted claims and there is no default tenant.
     *
     * @throws org.sangam.core.tenant.TenantResolutionException when the engine tenant is missing or a claim differs
     */
    static String tenant(DelegateExecution execution, String label) {
        Map<String, Object> claims = new HashMap<>();
        for (String name : ConnectorTenants.CLAIM_VARIABLES) {
            claims.put(name, execution.getVariable(name));
        }
        return ConnectorTenants.tenantOwned(execution.getTenantId(), claims, label);
    }

    static int loopCounter(DelegateExecution execution) {
        Object v = execution.getVariableLocal("loopCounter");
        if (v == null) {
            v = execution.getVariable("loopCounter");
        }
        return v instanceof Number n ? n.intValue() : 0;
    }
}
