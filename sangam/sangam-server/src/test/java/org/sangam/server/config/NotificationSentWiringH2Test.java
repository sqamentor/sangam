// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;
import org.sangam.integration.packs.PackOperationStore;
import org.sangam.integration.schema.IntegrationSchema;
import org.sangam.packs.port.PackRuntime;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.PacksAuditSink;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Owner decision 313 through the server's wiring: the pack operation store bean
 * ({@link IntegrationWiring#sangamPackOperationStore}) carries the notification settlement listener, and the server's
 * pack audit sink ({@link PacksAuditSink}) appends on the settling connection. A queued notification settled COMPLETED
 * writes exactly one NOTIFICATION_SENT in the settling transaction; a second settle writes none; a settlement that
 * rolls back leaves none. Before, the store had no listener (no NOTIFICATION_SENT at all), and the sink's connection
 * overload fell back to the engine-command lookup, which throws outside a command.
 */
class NotificationSentWiringH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;
    private static final String TENANT = "T1";
    private static final String CASE = "CASE-313";

    private final JdbcDataSource ds = new JdbcDataSource();
    private final Connection keepAlive;
    private final Transactions tx;
    private final PackOperationStore store;
    private final OperationLedger ledger = new OperationLedger(D, new ReconciliationQueue(D, null));

    NotificationSentWiringH2Test() throws Exception {
        ds.setURL("jdbc:h2:mem:sentwiring" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        keepAlive = ds.getConnection();
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        for (String sql : PackOperationStore.DDL) {
            String s = D.expand(sql);
            Jdbc.update(keepAlive, s.startsWith("CREATE TABLE") ? s + D.createTableSuffix() : s);
        }
        tx = Transactions.of(ds);
        TxSupport support = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), D);
        store = new IntegrationWiring().sangamPackOperationStore(support);
        // the server's pack audit sink, as PacksWiring installs it
        PackRuntime.install(PackRuntime.ports().withAudit(new PacksAuditSink(new AuditChain(D), support)));
    }

    @AfterEach
    void close() throws Exception {
        PackRuntime.reset();
        keepAlive.close();
    }

    private long sent() {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=?"
                + " AND event_type='NOTIFICATION_SENT'", rs -> rs.getString(1), TENANT, CASE)).size();
    }

    /** A queued (PENDING, dispatched) notification, as the gateway stores a submitted one. */
    private String queued() {
        String op = UUID.randomUUID().toString();
        UUID ledgerOp = UUID.fromString(op);
        tx.inTransaction(c -> {
            store.insert(c, new PackOperationStore.Row(op, TENANT, "notify.email", "send", ledgerOp, "root-1", "pi-1", CASE,
                    "hash-1", "{\"template\":\"t1\",\"recipient\":\"citizen-1\"}", PackOperationStore.Status.PENDING, null,
                    null, null, false, 1, false));
            store.markDispatched(c, TENANT, op);
            ledger.recordIntent(c, ledgerOp, new OperationLedger.OperationRef(TENANT, "root-1", "pack:" + op, 0),
                    CapabilityClass.B_LOOKUP_ONLY, null, "attempt-1", 0L);
            return null;
        });
        return op;
    }

    private boolean settle(Connection c, String op, String ref) {
        return store.settle(c, TENANT, op, PackOperationStore.Status.COMPLETED, null, ref, Json.write(Map.of()));
    }

    private boolean settle(String op, String ref) {
        Boolean done = tx.inTransaction(c -> settle(c, op, ref));
        return done;
    }

    @Test
    void aQueuedNotificationSettledCompletedWritesExactlyOneNotificationSent() {
        String op = queued();
        assertEquals(0, sent(), "queueing alone writes nothing");

        assertTrue(settle(op, "smtp-77"));
        assertEquals(1, sent(), "written by the server's sink on the settling transaction");

        assertFalse(settle(op, "smtp-78"), "a final row is never settled again");
        assertEquals(1, sent(), "a second settle writes none");
    }

    @Test
    void aSettlementThatRollsBackLeavesNoNotificationSent() {
        String op = queued();
        assertThrows(IllegalStateException.class, () -> tx.inTransaction(c -> {
            assertTrue(settle(c, op, "smtp-90"));
            throw new IllegalStateException("the settling transaction fails after the settle");
        }));
        assertEquals(0, sent(), "the event rolled back with the settlement");

        assertTrue(settle(op, "smtp-91"), "the operation is still open and settles later");
        assertEquals(1, sent());
    }
}
