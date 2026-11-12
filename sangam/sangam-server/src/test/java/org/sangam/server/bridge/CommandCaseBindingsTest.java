// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.sangam.config.store.ConfigSchema;
import org.sangam.config.store.JdbcConfigStore;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

/**
 * SL-124, same-transaction window: {@link ConfigCaseBindingPlugin} writes {@code CFG_CASE_BINDING} on the engine
 * command's connection, while {@code JdbcConfigStore.caseBinding(tenant, root)} opens a connection of its own and so
 * cannot see that row before the start commits (a synchronous config read in the start transaction would fall back to
 * today's versions). {@link CommandCaseBindings} reads on the command's connection inside a command, so it sees it.
 */
class CommandCaseBindingsTest {

    static final String URL = "jdbc:h2:mem:sl124-bindings;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    @Test
    void theStartTransactionSeesItsOwnUncommittedBinding() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(URL);
        try (Connection c = ds.getConnection()) {
            ConfigSchema.create(c, Dialect.POSTGRESQL);
        }
        JdbcConfigStore store = new JdbcConfigStore(ds, Dialect.POSTGRESQL);
        CommandCaseBindings bindings = new CommandCaseBindings(store);
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl(URL);
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        ProcessEngine engine = cfg.buildProcessEngine();
        try {
            Command<String[]> start = ctx -> {
                // as ConfigCaseBindingPlugin.onStart writes it
                Connection c = ctx.getDbSqlSession().getSqlSession().getConnection();
                Jdbc.insertIfAbsent(c, Dialect.POSTGRESQL, "INSERT INTO CFG_CASE_BINDING (tenant_id, root_id, binding, "
                        + "bound_at) VALUES (?,?,?,?)", "RJ", "ROOT-1", "RJ@1#aaaaaaaaaaaa", Instant.now());
                return new String[] {bindings.binding("RJ", "ROOT-1").orElse(null),
                        store.caseBinding("RJ", "ROOT-1").orElse(null)};
            };
            String[] seen = ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
                    .getCommandExecutorTxRequired().execute(start);
            assertEquals("RJ@1#aaaaaaaaaaaa", seen[0], "the command's own connection sees the uncommitted binding");
            assertEquals(null, seen[1], "a separate connection does not: the window this closes");
            assertEquals(Optional.of("RJ@1#aaaaaaaaaaaa"), bindings.binding("RJ", "ROOT-1"), "after commit, outside a command");
            assertTrue(bindings.binding("RJ", "ROOT-2").isEmpty());
        } finally {
            engine.close();
        }
    }
}
