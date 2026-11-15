// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import jakarta.transaction.Status;
import jakarta.transaction.Transaction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.JakartaTransactionProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.sangam.core.layer.Dialect;
import org.sangam.rest.support.TxSupport;

/**
 * Extraction phase 3, blocker 3: the TxSupport contract on Narayana and Agroal (H2), as the idempotency store and the
 * audit port use it. The always-new transaction is independent of the suspended one in both directions; the
 * transaction-bound connection is one per transaction, shared with the engine, and released at completion;
 * after-commit work runs only after a commit; rollback-only is honoured. Plain JUnit, no Quarkus boot.
 */
class JtaTxSupportTest {

    @TempDir
    Path objectStore;

    private JtaFixture jta;
    private JtaTxSupport tx;

    @BeforeEach
    void start() throws SQLException {
        jta = JtaFixture.start(objectStore);
        tx = new JtaTxSupport(jta.transactionManager, jta.dataSource, Dialect.POSTGRESQL);
        tx.runInTx(() -> update(tx.connection(), "CREATE TABLE product_row (id VARCHAR(40) PRIMARY KEY)"));
    }

    @AfterEach
    void stop() throws Exception {
        assertEquals(Status.STATUS_NO_TRANSACTION, jta.transactionManager.getStatus(), "no transaction left behind");
        assertEquals(0, tx.boundConnections(), "every bound connection was released at completion");
        jta.close();
    }

    @Test
    void joinsOrOwnsATransaction() throws Exception {
        tx.runInTx(() -> insert("a"));
        assertEquals(1, rows("a"));

        jta.transactionManager.begin();
        tx.runInTx(() -> insert("b"));
        assertEquals(Status.STATUS_ACTIVE, jta.transactionManager.getStatus(), "the joined transaction stays open");
        jta.transactionManager.rollback();
        assertEquals(0, rows("b"), "joined work is undone by the caller's rollback: one transaction");
    }

    @Test
    void aFailureRollsBackAnOwnedTransactionAndIsRethrownUnchanged() throws Exception {
        IllegalArgumentException boom = new IllegalArgumentException("boom");
        assertSame(boom, assertThrows(IllegalArgumentException.class, () -> tx.runInTx(() -> {
            insert("c");
            throw boom;
        })));
        assertEquals(0, rows("c"));

        jta.transactionManager.begin();
        assertThrows(IllegalArgumentException.class, () -> tx.runInTx(() -> {
            throw boom;
        }));
        assertEquals(Status.STATUS_MARKED_ROLLBACK, jta.transactionManager.getStatus(), "a joined one is marked");
        jta.transactionManager.rollback();
    }

    @Test
    void theNewTransactionCommitsAlthoughTheSuspendedOneRollsBack() throws Exception {
        jta.transactionManager.begin();
        Transaction outer = jta.transactionManager.getTransaction();
        insert("outer");
        tx.inNewTx(() -> {
            assertFalse(outer.equals(currentTransaction()), "the work runs in another transaction");
            return insert("claim");
        });
        assertEquals(outer, jta.transactionManager.getTransaction(), "the suspended transaction is resumed");
        jta.transactionManager.rollback();
        assertEquals(1, rows("claim"), "the idempotency claim / rejected-call audit survives the outer rollback");
        assertEquals(0, rows("outer"));
    }

    @Test
    void aFailedNewTransactionLeavesTheSuspendedOneUntouched() throws Exception {
        jta.transactionManager.begin();
        insert("kept");
        IllegalStateException boom = new IllegalStateException("new tx fails");
        assertSame(boom, assertThrows(IllegalStateException.class, () -> tx.inNewTx(() -> {
            insert("lost");
            throw boom;
        })));
        assertEquals(Status.STATUS_ACTIVE, jta.transactionManager.getStatus(), "the outer one is not marked");
        jta.transactionManager.commit();
        assertEquals(1, rows("kept"));
        assertEquals(0, rows("lost"));
    }

    @Test
    void theNewTransactionsViewRunsEveryUnitInItsOwnTransaction() throws Exception {
        jta.transactionManager.begin();
        tx.newTransactions().inTransaction(c -> update(c, "INSERT INTO product_row VALUES ('unit')"));
        jta.transactionManager.rollback();
        assertEquals(1, rows("unit"));
    }

    @Test
    void theBoundConnectionIsOnePerTransactionAndRefusedOutsideOne() {
        tx.runInTx(() -> {
            Connection first = tx.connection();
            assertSame(first, tx.connection());
            assertSame(first, tx.withConnection(c -> c));
            Connection inner = tx.inNewTx(tx::connection);
            assertFalse(first == inner, "the new transaction has its own connection");
            assertSame(first, tx.connection(), "the resumed transaction keeps its own");
        });
        IllegalStateException outside = assertThrows(IllegalStateException.class, tx::connection);
        assertTrue(outside.getMessage().startsWith("no JTA transaction runs"), outside.getMessage());
    }

    @Test
    void engineAndProductRowsShareTheTransaction() throws Exception {
        ProcessEngine engine = engine("tx-support");
        try {
            String result = tx.inTxWithStatus(status -> {
                insert("d");
                deploy(engine, "rolled-back");
                status.setRollbackOnly();
                assertTrue(status.isRollbackOnly());
                return "done";
            });
            assertEquals("done", result, "a transaction marked by its own work returns the result");
            assertEquals(0, rows("d"));
            assertEquals(0, engine.getRepositoryService().createDeploymentQuery().deploymentName("rolled-back").count(),
                    "the engine's deployment was in the same transaction");

            tx.runInTx(() -> {
                insert("e");
                deploy(engine, "committed");
            });
            assertEquals(1, rows("e"));
            assertEquals(1, engine.getRepositoryService().createDeploymentQuery().deploymentName("committed").count());
        } finally {
            engine.close();
        }
    }

    @Test
    void aParticipantsRollbackOnlyIsReportedByTheOwner() throws Exception {
        IllegalStateException unexpected = assertThrows(IllegalStateException.class, () -> tx.runInTx(() -> {
            insert("f");
            tx.inTxWithStatus(status -> {
                status.setRollbackOnly();
                return null;
            });
        }));
        assertTrue(unexpected.getMessage().contains("rollback-only by a joined participant"), unexpected.getMessage());
        assertEquals(0, rows("f"));
    }

    @Test
    void afterCommitWorkRunsOnlyAfterACommit() {
        List<String> ran = new ArrayList<>();
        tx.runInTx(() -> {
            tx.afterCommit(() -> ran.add("committed"));
            assertEquals(List.of(), ran, "not before the commit");
        });
        assertEquals(List.of("committed"), ran);

        tx.inTxWithStatus(status -> {
            tx.afterCommit(() -> ran.add("rolled back"));
            status.setRollbackOnly();
            return null;
        });
        assertThrows(IllegalStateException.class, () -> tx.runInTx(() -> {
            tx.afterCommit(() -> ran.add("failed"));
            throw new IllegalStateException("boom");
        }));
        tx.afterCommit(() -> ran.add("outside"));
        assertEquals(List.of("committed", "outside"), ran);
    }

    @Test
    void theDialectIsTheOneHandedIn() {
        assertEquals(Dialect.POSTGRESQL, tx.dialect());
        assertEquals(Dialect.POSTGRESQL, TxSupport.detectDialect(jta.dataSource), "H2 counts as PostgreSQL");
        assertSame(jta.dataSource, tx.dataSource());
    }

    private Transaction currentTransaction() {
        try {
            return jta.transactionManager.getTransaction();
        } catch (jakarta.transaction.SystemException e) {
            throw new IllegalStateException(e);
        }
    }

    private Integer insert(String id) {
        return tx.withConnection(c -> update(c, "INSERT INTO product_row VALUES ('" + id + "')"));
    }

    private static void deploy(ProcessEngine engine, String name) {
        engine.getRepositoryService().createDeployment().name(name)
                .addModelInstance("p.bpmn", Bpmn.createExecutableProcess("p").startEvent().endEvent().done()).deploy();
    }

    private ProcessEngine engine(String name) {
        JakartaTransactionProcessEngineConfiguration config = new JakartaTransactionProcessEngineConfiguration();
        config.setProcessEngineName(name);
        config.setDataSource(jta.dataSource);
        config.setTransactionManager(jta.transactionManager);
        config.setDatabaseSchemaUpdate(ProcessEngineConfigurationImpl.DB_SCHEMA_UPDATE_CREATE_DROP);
        config.setJobExecutorActivate(false);
        return config.buildProcessEngine();
    }

    private int rows(String id) throws SQLException {
        try (Connection c = jta.dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM product_row WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static Integer update(Connection c, String sql) {
        try (Statement s = c.createStatement()) {
            return s.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
