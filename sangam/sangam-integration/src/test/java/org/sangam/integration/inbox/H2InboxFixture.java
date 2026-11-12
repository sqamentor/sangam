// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.relay.BackpressureGate;
import org.sangam.integration.relay.DeadLetterStore;
import org.sangam.integration.schema.IntegrationSchema;

/** An inbox service on in-memory H2 (PostgreSQL mode) for the broker consumer tests (M-008). */
final class H2InboxFixture implements AutoCloseable {

    static final Dialect D = Dialect.POSTGRESQL;

    final String url;
    private final Connection keepAlive;
    final InboxService inbox;

    H2InboxFixture(String name) throws SQLException {
        url = "jdbc:h2:mem:" + name + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        Transactions tx = tx();
        InboxService.Settings settings = new InboxService.Settings("node-1", 10, Duration.ofMinutes(1), Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofSeconds(30));
        inbox = new InboxService(D, tx, settings, new AuditChain(D), new EventDefinitionPolicy.Registry(),
                (c, m) -> null, BackpressureGate.open(), new DeadLetterStore(D, tx));
    }

    Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    /** Message ids stored in the inbox for {@code tenant}. */
    List<String> stored(String tenant) {
        return tx().inTransaction(c -> Jdbc.query(c, "SELECT message_id FROM INBOX WHERE tenant_id=? ORDER BY message_id",
                rs -> rs.getString(1), tenant));
    }

    @Override
    public void close() throws SQLException {
        keepAlive.close();
    }
}
