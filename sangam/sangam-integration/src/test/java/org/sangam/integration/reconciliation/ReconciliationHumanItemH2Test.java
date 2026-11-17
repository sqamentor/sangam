// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.reconciliation;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.schema.IntegrationSchema;

/**
 * Owner decision 290: an operation whose automatic re-dispatches are used up gets a new OPEN item that goes to a human
 * at once: the ledger is UNKNOWN_OUTCOME again, the attempt row is escalated before any lookup could claim it, and the
 * human task is opened after the commit.
 */
class ReconciliationHumanItemH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;

    private final String url = "jdbc:h2:mem:humanitem" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private final Connection keepAlive;
    private final ReconciliationQueue queue = new ReconciliationQueue(D, null);
    private final OperationLedger ledger = new OperationLedger(D, queue);
    private final List<String> tasks = new CopyOnWriteArrayList<>();
    private final ReconciliationEngine engine;

    ReconciliationHumanItemH2Test() throws SQLException {
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        ReconciliationTaskPort humans = (item, connectorId, reason) -> {
            tasks.add(item.id() + " " + reason);
            return "task-" + item.id();
        };
        engine = new ReconciliationEngine(D, tx(), queue, ledger, new ConnectorCallJournal(D), null, humans, null,
                ReconciliationEngine.Settings.defaults("node-1"));
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

            @Override
            public <T> T read(ReadWork<T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.read(c);
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

    private String escalatedTask(long itemId) {
        return tx().inTransaction(c -> Jdbc.one(c, "SELECT escalated_task_id FROM RECONCILIATION_ATTEMPT WHERE item_id=?",
                rs -> rs.getString(1), itemId)).orElse(null);
    }

    @Test
    void aUsedUpOperationGetsANewItemThatGoesStraightToAHuman() {
        UUID op = UUID.randomUUID();
        long resolved = tx().inTransaction(c -> {
            ledger.recordIntent(c, op, new OperationLedger.OperationRef("T1", "pack-unknown-root", "pack:" + op, 0),
                    CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, null, "attempt-1", 0L);
            long first = ledger.markUnknown(c, op, 0L, "outcome unknown");
            queue.resolve(c, first, ReconciliationQueue.Action.RETRY_SAME_OPERATION, ReconciliationEngine.SYSTEM_ACTOR, "{}");
            return first;
        });
        assertEquals(OperationLedger.State.INTENT, tx().inTransaction(c -> ledger.find(c, op)).orElseThrow().state(),
                "a retry resolution returns the ledger to INTENT");

        long item = tx().inTransaction(c -> engine.openForHuman(c, op, "re-dispatches used up"));
        assertNotEquals(resolved, item, "a new item, the resolved one stays resolved");
        ReconciliationQueue.Item opened = tx().inTransaction(c -> queue.get(c, item)).orElseThrow();
        assertEquals(ReconciliationQueue.State.OPEN, opened.state());
        assertEquals(ReconciliationQueue.Kind.UNKNOWN_OUTCOME, opened.kind());
        assertEquals(op, opened.operationId());
        assertEquals(OperationLedger.State.UNKNOWN_OUTCOME, tx().inTransaction(c -> ledger.find(c, op)).orElseThrow().state(),
                "a human confirmation or failure updates the ledger as for any unknown outcome");
        assertEquals(ReconciliationEngine.HUMAN_PENDING, escalatedTask(item), "no automatic lookup claims it");
        assertTrue(tasks.isEmpty(), "the task is opened after the commit");

        engine.escalateOpened(item, "re-dispatches used up");
        assertEquals("task-" + item, escalatedTask(item));
        assertEquals(List.of(item + " re-dispatches used up"), tasks);
    }
}
