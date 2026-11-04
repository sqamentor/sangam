// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge.operaton;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;

import java.sql.Connection;

/** Small helpers over Operaton internals used by the bridge (engine identity, the command's JDBC connection). */
final class EngineContext {

    private EngineContext() {
    }

    /** The JDBC connection of the current engine command (product rows commit with engine state, Q1). */
    static Connection connection() {
        CommandContext cc = Context.getCommandContext();
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

    static String tenant(DelegateExecution execution, String fallback) {
        Object v = execution.getVariable("sangamTenantId");
        if (v != null) {
            return v.toString();
        }
        return execution.getTenantId() != null ? execution.getTenantId() : fallback;
    }

    static int loopCounter(DelegateExecution execution) {
        Object v = execution.getVariableLocal("loopCounter");
        if (v == null) {
            v = execution.getVariable("loopCounter");
        }
        return v instanceof Number n ? n.intValue() : 0;
    }
}
