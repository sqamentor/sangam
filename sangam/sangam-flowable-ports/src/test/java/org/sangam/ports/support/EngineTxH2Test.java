// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.ports.PortsH2Engine;

/**
 * FW-23 (Q1): product rows written through the ports' {@code Transactions} (EngineTx.transactions and the plugin's
 * command transactions) roll back with a failed engine command, even when the engine itself wrote nothing.
 */
class EngineTxH2Test {

    private static long links(PortsH2Engine h2) throws Exception {
        try (Connection c = h2.connection(); ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM INSTANCE_LINK")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void engineTxTransactionsRollBackWithTheCommand() throws Exception {
        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            var tx = EngineTx.transactions(h2.engine);
            assertThrows(IllegalStateException.class, () -> tx.inTransaction(c -> {
                Jdbc.update(c, "INSERT INTO INSTANCE_LINK(tenant_id, link_type, source_type, source_id, target_type, target_id,"
                        + " created_at) VALUES ('t','reference','process','a','case','x',CURRENT_TIMESTAMP)");
                throw new IllegalStateException("boom");
            }));
            assertEquals(0, links(h2));
        }
    }

    /**
     * Operaton 2.2's Context.getCommandContext() throws outside a command (ENGINE-09025) and never answers null, so
     * the question is asked with Context.hasActiveCommandContext(): outside a command inCommand() is false and
     * connection() refuses with its own message; inside one both answer.
     */
    @Test
    void inCommandAndConnectionAnswerOutsideAndInsideACommand() throws Exception {
        org.junit.jupiter.api.Assertions.assertFalse(EngineTx.inCommand(), "no engine at all: not in a command");
        IllegalStateException refused = assertThrows(IllegalStateException.class, EngineTx::connection);
        assertEquals("not inside an engine command", refused.getMessage());
        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            org.junit.jupiter.api.Assertions.assertFalse(EngineTx.inCommand(), "an engine exists, no command runs");
            boolean inside = EngineTx.execute(h2.engine, cc -> EngineTx.inCommand() && EngineTx.connection() != null);
            org.junit.jupiter.api.Assertions.assertTrue(inside);
            org.junit.jupiter.api.Assertions.assertFalse(EngineTx.inCommand(), "after the command");
        }
    }

    @Test
    void pluginCommandTransactionsRollBackWithTheCommand() throws Exception {
        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            assertThrows(IllegalStateException.class, () -> EngineTx.execute(h2.engine, cc -> {
                h2.plugin.links().addReference("t", "process", "b", "case", "x", "u");   // joins this command
                throw new IllegalStateException("boom");
            }));
            assertEquals(0, links(h2));
            h2.plugin.links().addReference("t", "process", "b", "case", "x", "u");
            assertEquals(1, links(h2), "committed work stays");
        }
    }
}
