// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
 * On Oracle the skip-locked select of {@code ReconciliationEngine.claim} locks rows chosen from the statement's
 * snapshot, so it can return an item a peer claimed (attempt leased) or resolved, and committed, after that snapshot.
 * H2 cannot reproduce the snapshot, so the peer's committed change is injected right after the skip-locked select:
 * the engine must not claim, look up or escalate that item again.
 */
class ReconciliationEngineSkipLockedFenceH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;

    private final String url = "jdbc:h2:mem:reconfence" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private final Connection keepAlive;
    private final ReconciliationQueue queue = new ReconciliationQueue(D, null);
    private final OperationLedger ledger = new OperationLedger(D, queue);
    private final List<String> tasks = new CopyOnWriteArrayList<>();

    ReconciliationEngineSkipLockedFenceH2Test() throws SQLException {
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws SQLException {
        keepAlive.close();
    }

    @Test
    void anItemAPeerClaimedAfterTheSnapshotIsNotWorkedAgain() {
        long item = unknownItem();
        boolean[] raced = {false};
        ReconciliationEngine.PassResult r = engine(raced, "INSERT INTO RECONCILIATION_ATTEMPT(item_id, attempts, not_found_streak,"
                + " next_attempt_at, last_result, escalated_task_id, created_at, updated_at) VALUES (" + item
                + ",0,0,CURRENT_TIMESTAMP + INTERVAL '1' HOUR,'CLAIMED',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").runOnce();

        assertTrue(raced[0], "the race was injected");
        assertEquals(0, r.examined(), "the peer's item is not claimed");
        assertEquals(List.of(), tasks, "no second escalation / human task");
        assertEquals("CLAIMED", lastResult(item), "the peer's attempt row is left as the peer set it");
    }

    @Test
    void anItemAPeerResolvedAfterTheSnapshotIsNotWorkedAgain() {
        long item = unknownItem();
        boolean[] raced = {false};
        ReconciliationEngine.PassResult r = engine(raced, "UPDATE RECONCILIATION_ITEM SET state='RESOLVED' WHERE id=" + item).runOnce();

        assertTrue(raced[0], "the race was injected");
        assertEquals(0, r.examined(), "the resolved item is not claimed");
        assertEquals(List.of(), tasks, "no escalation of a resolved item");
        assertEquals(null, lastResult(item), "no attempt row is leased for it");
        assertEquals(ReconciliationQueue.State.RESOLVED, tx(null, null).inTransaction(c -> queue.get(c, item)).orElseThrow().state());
    }

    /** An OPEN UNKNOWN_OUTCOME item with no connector call: claiming it would escalate it to a human. */
    private long unknownItem() {
        UUID op = UUID.randomUUID();
        return tx(null, null).inTransaction(c -> {
            ledger.recordIntent(c, op, new OperationLedger.OperationRef("T1", "root-1", "fence:" + op, 0),
                    CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, null, "attempt-1", 0L);
            return ledger.markUnknown(c, op, 0L, "outcome unknown");
        });
    }

    private ReconciliationEngine engine(boolean[] raced, String peerSql) {
        ReconciliationTaskPort humans = (it, connectorId, reason) -> {
            tasks.add(it.id() + " " + reason);
            return "task-" + it.id();
        };
        return new ReconciliationEngine(D, tx(raced, peerSql), queue, ledger, new ConnectorCallJournal(D), null, humans, null,
                ReconciliationEngine.Settings.defaults("node-1"));
    }

    private String lastResult(long item) {
        return tx(null, null).inTransaction(c -> Jdbc.one(c, "SELECT last_result FROM RECONCILIATION_ATTEMPT WHERE item_id=?",
                rs -> rs.getString(1), item)).orElse(null);
    }

    /** Transactions whose connection runs {@code peerSql} (once) on the first statement after the skip-locked select. */
    private Transactions tx(boolean[] raced, String peerSql) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(raced == null ? c : racing(c, raced, peerSql));
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

    private static Connection racing(Connection conn, boolean[] raced, String peerSql) {
        boolean[] selected = {false};
        return (Connection) Proxy.newProxyInstance(ReconciliationEngineSkipLockedFenceH2Test.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, a) -> {
                    if (m.getName().equals("prepareStatement") && !raced[0]) {
                        String sql = String.valueOf(a[0]);
                        if (selected[0]) {
                            raced[0] = true;
                            Jdbc.update(conn, peerSql);
                        } else if (sql.contains("SKIP LOCKED")) {
                            selected[0] = true;
                        }
                    }
                    try {
                        return m.invoke(conn, a);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
    }
}
