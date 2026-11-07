// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.tx;

import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandContextListener;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;

/**
 * Per-command Sangam hooks on <b>every</b> engine command, not only on adapter commands (job executions, Operaton
 * REST, delegates): the Q1 explicit rollback listener (D-22) and, when configured, the L2 flush backstop
 * ({@link org.sangam.engine.lock.RootLockFlushListener}). Registered as a post-context interceptor for the REQUIRED
 * and REQUIRES_NEW chains; each hook is installed once per command context and never opens a database session.
 */
public class RollbackGuardInterceptor extends CommandInterceptor {

    private final CommandContextListener closeListener;

    public RollbackGuardInterceptor(CommandContextListener closeListenerOrNull) {
        this.closeListener = closeListenerOrNull;
    }

    @Override
    public <T> T execute(Command<T> command) {
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (cc != null) {
            CommandState.installRollbackGuard(cc);
            if (command instanceof org.operaton.bpm.engine.impl.cmd.AcquireJobsCmd
                    || command instanceof org.operaton.bpm.engine.impl.cmd.FetchExternalTasksCmd) {
                CommandState.of(cc).markAcquisition(); // R2: acquisition never takes L2 (not even the backstop)
            }
            if (closeListener != null && CommandState.of(cc).markCloseListenerRegistered()) {
                cc.registerCommandContextListener(closeListener);
            }
        }
        return next.execute(command);
    }
}
