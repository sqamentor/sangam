// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import jakarta.transaction.Status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.JakartaTransactionProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.quarkus.engine.extension.QuarkusProcessEngineConfiguration;

/**
 * The shared-transaction seam of the Quarkus host (P-1): {@link JtaTransactions} joins a running JTA transaction,
 * owns one when none runs, and shares one transaction with the engine on the same Narayana manager and Agroal data
 * source. Plain JUnit, no Quarkus boot. Written for the test phase (not run).
 */
class JtaTransactionsTest {

    @TempDir
    Path objectStore;

    private JtaFixture jta;
    private JtaTransactions tx;

    @BeforeEach
    void start() throws SQLException {
        jta = JtaFixture.start(objectStore);
        tx = new JtaTransactions(jta.transactionManager, jta.dataSource);
        tx.inTransaction(c -> update(c, "CREATE TABLE product_row (id VARCHAR(40) PRIMARY KEY)"));
    }

    @AfterEach
    void stop() {
        jta.close();
    }

    @Test
    void ownsACommittedTransactionWhenNoneRuns() throws Exception {
        tx.inTransaction(c -> update(c, "INSERT INTO product_row VALUES ('a')"));
        assertEquals(Status.STATUS_NO_TRANSACTION, jta.transactionManager.getStatus());
        assertEquals(1, rows("a"));
    }

    @Test
    void rollsBackItsOwnTransactionWhenTheWorkFails() throws Exception {
        IllegalStateException boom = assertThrows(IllegalStateException.class, () -> tx.inTransaction(c -> {
            update(c, "INSERT INTO product_row VALUES ('b')");
            throw new IllegalStateException("boom");
        }));
        assertEquals("boom", boom.getMessage());
        assertEquals(Status.STATUS_NO_TRANSACTION, jta.transactionManager.getStatus());
        assertEquals(0, rows("b"));
    }

    @Test
    void joinsARunningTransactionInsteadOfOpeningASecondOne() throws Exception {
        jta.transactionManager.begin();
        tx.inTransaction(c -> update(c, "INSERT INTO product_row VALUES ('c')"));
        assertEquals(Status.STATUS_ACTIVE, jta.transactionManager.getStatus(), "the joined transaction stays open");
        jta.transactionManager.rollback();
        assertEquals(0, rows("c"), "the caller's rollback undoes the joined work: one transaction, not two");
    }

    @Test
    void marksAJoinedTransactionRollbackOnlyWhenTheWorkFails() throws Exception {
        jta.transactionManager.begin();
        assertThrows(IllegalStateException.class, () -> tx.inTransaction(c -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(Status.STATUS_MARKED_ROLLBACK, jta.transactionManager.getStatus());
        jta.transactionManager.rollback();
    }

    @Test
    void engineAndProductRowsCommitOrRollBackTogether() throws Exception {
        ProcessEngine engine = engine("shared-tx");
        try {
            jta.transactionManager.begin();
            tx.inTransaction(c -> update(c, "INSERT INTO product_row VALUES ('d')"));
            engine.getRepositoryService().createDeployment().name("shared-tx")
                    .addModelInstance("p.bpmn", Bpmn.createExecutableProcess("p").startEvent().endEvent().done())
                    .deploy();
            jta.transactionManager.rollback();
            assertEquals(0, rows("d"));
            assertEquals(0, engine.getRepositoryService().createDeploymentQuery().deploymentName("shared-tx").count(),
                    "the engine's deployment was in the same JTA transaction");

            jta.transactionManager.begin();
            tx.inTransaction(c -> update(c, "INSERT INTO product_row VALUES ('e')"));
            engine.getRepositoryService().createDeployment().name("shared-tx-2")
                    .addModelInstance("p.bpmn", Bpmn.createExecutableProcess("p").startEvent().endEvent().done())
                    .deploy();
            jta.transactionManager.commit();
            assertEquals(1, rows("e"));
            assertEquals(1, engine.getRepositoryService().createDeploymentQuery().deploymentName("shared-tx-2").count());
        } finally {
            engine.close();
        }
    }

    @Test
    void theGuardSeesTheSameTransactionOnlyForTheHostsOwnObjects() {
        QuarkusHost host = new QuarkusHost(org.sangam.assembly.host.SangamProperties.empty(), jta.dataSource,
                jta.transactionManager, new BoundPrincipal());
        EngineCapabilityGuard guard = new EngineCapabilityGuard(host);
        JakartaTransactionProcessEngineConfiguration same = new JakartaTransactionProcessEngineConfiguration();
        same.setTransactionManager(host.transactions().transactionManager());
        same.setDataSource(host.transactions().dataSource());
        assertTrue(guard.sameTransaction(same));
        JakartaTransactionProcessEngineConfiguration otherDataSource = new JakartaTransactionProcessEngineConfiguration();
        otherDataSource.setTransactionManager(host.transactions().transactionManager());
        otherDataSource.setDataSource(new org.h2.jdbcx.JdbcDataSource());
        assertFalse(guard.sameTransaction(otherDataSource));
    }

    private ProcessEngine engine(String name) {
        // the host's own engine configuration class: it runs the bootstrap schema operations in a JTA transaction,
        // as Agroal requires (a plain JTA configuration would use a connection opened before the transaction)
        QuarkusProcessEngineConfiguration config = new QuarkusProcessEngineConfiguration();
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
