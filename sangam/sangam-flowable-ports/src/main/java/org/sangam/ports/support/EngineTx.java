// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.support;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.util.function.Function;

/**
 * Access to the engine transaction from product code (Q1): product rows written through {@link #connection()} commit
 * or roll back with the engine command. The explicit-rollback listener for the Q1 defect (FLOWABLE-INTO-OPERATON D-22) is installed by
 * WS-ENGINE's adapter; product code here never commits the connection itself.
 */
public final class EngineTx {

    private EngineTx() {
    }

    /** True when called inside an engine command (listener, delegate, behaviour, history handler). */
    public static boolean inCommand() {
        return Context.getCommandContext() != null;
    }

    /** The JDBC connection of the current engine command. */
    public static Connection connection() {
        CommandContext cc = Context.getCommandContext();
        if (cc == null) {
            throw new IllegalStateException("not inside an engine command");
        }
        return cc.getDbSqlSession().getSqlSession().getConnection();
    }

    public static ProcessEngineConfigurationImpl config(ProcessEngine engine) {
        return (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    }

    /** Runs {@code cmd} as one engine command (joins an active command if there is one). */
    public static <T> T execute(ProcessEngine engine, Command<T> cmd) {
        return config(engine).getCommandExecutorTxRequired().execute(cmd);
    }

    /** {@link Transactions} that run product work inside an engine command (one transaction with engine state). */
    public static Transactions transactions(ProcessEngine engine) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                return execute(engine, cc -> work.apply(cc.getDbSqlSession().getSqlSession().getConnection()));
            }
        };
    }
}
