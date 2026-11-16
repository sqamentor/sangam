// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * RM-30 (P-1 without a transaction manager): {@link EngineTxSupport} on a standalone engine. A product row written on
 * {@link TxSupport#connection()} and an engine step (a process start) made by the same work commit once, or roll back
 * together on a failure after both and on rollback-only; {@link TxSupport#inNewTx} commits on its own;
 * {@link TxSupport#afterCommit} runs after the commit and never after a rollback.
 */
class EngineTxSupportH2Test {

    static final String TENANT = "t1";
    static final String BPMN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"d\""
            + " targetNamespace=\"http://sangam.org/test\">\n"
            + "  <process id=\"p1\" isExecutable=\"true\"><startEvent id=\"s\"/>"
            + "<sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"u\"/><userTask id=\"u\"/></process>\n"
            + "</definitions>\n";

    JdbcDataSource ds;
    OperatonEngineAdapter engine;
    EngineTxSupport tx;

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:etx" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform");
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        engine = SangamEngineFactory.build(ds, s, "h2");
        engine.deploy(TENANT, "p1", Map.of("p1.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("CREATE TABLE PRODUCT_ROW (id VARCHAR(64) PRIMARY KEY)");
        }
        tx = new EngineTxSupport(engine, ds, Dialect.POSTGRESQL);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    void productRow(String id) {
        try (PreparedStatement ps = tx.connection().prepareStatement("INSERT INTO PRODUCT_ROW(id) VALUES (?)")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    long count(String sql, String arg) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    long rows(String id) throws Exception {
        return count("SELECT COUNT(*) FROM PRODUCT_ROW WHERE id=?", id);
    }

    long instances(String businessKey) throws Exception {
        return count("SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE BUSINESS_KEY_=?", businessKey);
    }

    @Test
    void aFailureAfterTheProductRowAndTheEngineStepRollsBothBack() throws Exception {
        String key = "bk-" + UUID.randomUUID();
        List<Runnable> unused = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> tx.runInTx(() -> {
            productRow(key);
            engine.startProcess(TENANT, "p1", key, Map.of());
            tx.afterCommit(() -> unused.add(() -> { }));
            throw new IllegalStateException("injected after both writes");
        }));
        assertEquals(0, rows(key), "the product row rolled back");
        assertEquals(0, instances(key), "the engine step rolled back with it");
        assertEquals(0, unused.size(), "nothing runs after a rollback");
    }

    @Test
    void withoutAFailureBothCommitOnceAndAfterCommitRunsAfterwards() throws Exception {
        String key = "bk-" + UUID.randomUUID();
        List<Long> seenAfterCommit = new ArrayList<>();
        tx.runInTx(() -> {
            productRow(key);
            tx.runInTx(() -> engine.startProcess(TENANT, "p1", key, Map.of()));   // joins
            tx.afterCommit(() -> {
                try {
                    seenAfterCommit.add(rows(key) + instances(key));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        });
        assertEquals(1, rows(key));
        assertEquals(1, instances(key));
        assertEquals(List.of(2L), seenAfterCommit, "after the commit both are visible to another connection");
    }

    @Test
    void rollbackOnlyRollsBothBackAndReturnsTheValue() throws Exception {
        String key = "bk-" + UUID.randomUUID();
        String v = tx.inTxWithStatus(status -> {
            productRow(key);
            engine.startProcess(TENANT, "p1", key, Map.of());
            status.setRollbackOnly();
            return "value";
        });
        assertEquals("value", v);
        assertEquals(0, rows(key));
        assertEquals(0, instances(key));
    }

    @Test
    void aNewTransactionCommitsOnItsOwn() throws Exception {
        String key = "bk-" + UUID.randomUUID();
        String own = key + "-own";
        assertThrows(IllegalStateException.class, () -> tx.runInTx(() -> {
            productRow(key);
            tx.inNewTx(() -> {
                productRow(own);
                return null;
            });
            throw new IllegalStateException("outer fails");
        }));
        assertEquals(0, rows(key));
        assertEquals(1, rows(own), "the own transaction committed independently");
    }
}
