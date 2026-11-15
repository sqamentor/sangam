// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Objects;
import java.util.Optional;

import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.sangam.config.api.CaseBindings;
import org.sangam.config.store.JdbcConfigStore;

/**
 * The stored case bindings for {@link org.sangam.config.api.ConfigConnector} (SL-119, SL-124). Inside an engine command
 * (a pack delegate's synchronous connector call) the binding is read on that command's own connection, the one
 * {@link ConfigCaseBindingPlugin} wrote it on at the case start, so a config read in the start transaction itself sees
 * the not yet committed binding. Outside a command (the relay delivering a submitted effect) it is read in a new
 * transaction, where the start has long committed.
 */
public final class CommandCaseBindings implements CaseBindings {

    private final JdbcConfigStore store;

    public CommandCaseBindings(JdbcConfigStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Optional<String> binding(String tenantId, String rootId) {
        // Operaton's Context.getCommandContext() throws outside a command (ENGINE-09025); it never answers null
        if (Context.hasActiveCommandContext()) {
            CommandContext cc = Context.getCommandContext();
            return store.caseBinding(cc.getDbSqlSession().getSqlSession().getConnection(), tenantId, rootId);
        }
        return store.caseBinding(tenantId, rootId);
    }
}
