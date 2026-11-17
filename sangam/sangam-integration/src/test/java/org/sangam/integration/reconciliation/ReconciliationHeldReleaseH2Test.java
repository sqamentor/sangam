// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.schema.IntegrationSchema;

/**
 * SL-180 (owner decision 402): resolving an {@code UNKNOWN_OUTCOME} item releases its held work at once. The release
 * names the item's own work (its task id) and root, also for an item that was opened without a ledger operation (the
 * engine-adapter fallback), which was skipped before, so its work stayed held until the maximum lock ended.
 */
class ReconciliationHeldReleaseH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;

    record Call(String tenant, String root, UUID operation, String activityInstance, String work) {
    }

    private final String url = "jdbc:h2:mem:heldrelease" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private final Connection keepAlive;
    private final ReconciliationQueue queue = new ReconciliationQueue(D, null);
    private final OperationLedger ledger = new OperationLedger(D, queue);
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final ReconciliationEngine engine;

    ReconciliationHeldReleaseH2Test() throws SQLException {
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        HeldWorkReleaser recording = new HeldWorkReleaser() {
            @Override
            public int release(String tenantId, String rootId, UUID operationId, String activityInstanceId) {
                throw new AssertionError("the resolve must name the held work (the five-argument release)");
            }

            @Override
            public int release(String tenantId, String rootId, UUID operationId, String activityInstanceId, String workId) {
                calls.add(new Call(tenantId, rootId, operationId, activityInstanceId, workId));
                return 1;
            }
        };
        engine = new ReconciliationEngine(D, tx(), queue, ledger, new ConnectorCallJournal(D), null,
                (item, connectorId, reason) -> null, recording, ReconciliationEngine.Settings.defaults("node-1"));
    }

    @AfterEach
    void close() throws SQLException {
        keepAlive.close();
    }

    private Transactions tx() {
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

    @Test
    void anItemWithoutALedgerOperationReleasesItsOwnHeldWork() {
        long id = tx().inTransaction(c -> queue.open(c, "T1", ReconciliationQueue.Kind.UNKNOWN_OUTCOME, null,
                "root-pi-1", "work-1", "{}"));
        engine.resolve(id, ReconciliationQueue.Action.RETRY_SAME_OPERATION, "officer-1", Map.of(), "no effect", null);
        assertEquals(List.of(new Call("T1", "root-pi-1", null, null, "work-1")), calls,
                "the held work of the item is released at once, by its task id and root");
    }

    @Test
    void anItemOfALedgerOperationNamesItsWorkToo() {
        UUID op = UUID.randomUUID();
        long id = tx().inTransaction(c -> queue.open(c, "T1", ReconciliationQueue.Kind.UNKNOWN_OUTCOME, op,
                "root-pi-2", "work-2", "{}"));
        engine.resolve(id, ReconciliationQueue.Action.MARK_FAILED, "officer-1", Map.of(), "provider says no", null);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("work-2", calls.get(0).work());
        assertEquals(op, calls.get(0).operation());
        assertEquals("root-pi-2", calls.get(0).root());
    }
}
