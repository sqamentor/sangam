// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.sql.Connection;
import java.util.function.BiFunction;

import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.sangam.core.api.ReadWork;
import org.sangam.engine.db.EngineDialect;
import org.sangam.engine.tx.CommandState;

/**
 * One engine command around product work on the engine's connection, for {@link OperatonEngineAdapter}: the general
 * {@link #once} and the read-only {@link #read} (round 9). {@code read} repeats the command handling with a
 * {@link ReadWork} callback instead of going through {@code once}, so a read never calls the shared write callback.
 */
final class EngineCommands {

    private final ProcessEngineConfigurationImpl configuration;
    private final SangamEngineSettings settings;
    private final EngineDialect dialect;

    EngineCommands(ProcessEngineConfigurationImpl configuration, SangamEngineSettings settings, EngineDialect dialect) {
        this.configuration = configuration;
        this.settings = settings;
        this.dialect = dialect;
    }

    /** {@code work} in one new engine command (transaction required), with the SL-99 lock and statement timeouts. */
    <T> T once(BiFunction<CommandContext, Connection, T> work) {
        Command<T> cmd = cc -> {
            Connection conn = CommandState.connection(cc);
            CommandState.applyLockTimeouts(cc, dialect, settings.getLockTimeout(), settings.getStatementTimeout()); // SL-99: restored at tx end
            return work.apply(cc, conn);
        };
        return configuration.getCommandExecutorTxRequired().execute(cmd);
    }

    /** As {@code inEngineTransaction}: the active command context when there is one, else one new command. */
    <T> T read(ReadWork<T> work) {
        if (Context.hasActiveCommandContext()) {
            return work.read(CommandState.connection(Context.getCommandContext()));
        }
        Command<T> cmd = cc -> {
            Connection conn = CommandState.connection(cc);
            CommandState.applyLockTimeouts(cc, dialect, settings.getLockTimeout(), settings.getStatementTimeout()); // SL-99: restored at tx end
            return work.read(conn);
        };
        return configuration.getCommandExecutorTxRequired().execute(cmd);
    }
}
