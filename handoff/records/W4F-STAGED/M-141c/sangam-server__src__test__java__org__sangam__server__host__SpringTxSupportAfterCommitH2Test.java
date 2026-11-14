// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.rest.adapter.operaton.ReadTransactions;
import org.sangam.rest.config.SpringReadTransactions;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * M-141c: the plain-Java {@link TxSupport} contract, kept by the Spring host's implementation exactly as the Spring-only
 * class did before the split. After-commit work runs after a commit and never after a rollback; the status handed to
 * {@code inTxWithStatus} rolls back; joined work sees one connection; a new transaction is independent. Written for the
 * test phase (not run).
 */
class SpringTxSupportAfterCommitH2Test {

    private JdbcDataSource ds;
    private TxSupport tx;

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m141c-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE T (V VARCHAR(20))");
        }
        tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), Dialect.POSTGRESQL);
    }

    private int rows() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(*) FROM T")) {
            r.next();
            return r.getInt(1);
        }
    }

    private void insert(String v) {
        tx.withConnection(c -> {
            try (Statement s = c.createStatement()) {
                return s.executeUpdate("INSERT INTO T VALUES ('" + v + "')");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void afterCommitWorkRunsOnlyOnceTheTransactionCommitted() throws Exception {
        List<Integer> seen = new ArrayList<>();
        tx.runInTx(() -> {
            insert("a");
            tx.afterCommit(() -> {
                try {
                    seen.add(rows());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(seen.isEmpty(), "not before the commit");
        });
        assertEquals(List.of(1), seen, "after the commit, and the committed row is visible to another connection");
    }

    @Test
    void afterCommitWorkNeverRunsWhenTheTransactionRollsBack() throws Exception {
        List<String> seen = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> tx.runInTx(() -> {
            insert("a");
            tx.afterCommit(() -> seen.add("ran"));
            throw new IllegalStateException("boom");
        }));
        assertTrue(seen.isEmpty());
        assertEquals(0, rows());

        String out = tx.inTxWithStatus(status -> {
            insert("b");
            tx.afterCommit(() -> seen.add("ran"));
            status.setRollbackOnly();
            assertTrue(status.isRollbackOnly());
            return "returned";
        });
        assertEquals("returned", out, "rollback-only returns normally");
        assertTrue(seen.isEmpty());
        assertEquals(0, rows());
    }

    @Test
    void outsideATransactionAfterCommitWorkRunsAtOnce() {
        List<String> seen = new ArrayList<>();
        tx.afterCommit(() -> seen.add("ran"));
        assertEquals(List.of("ran"), seen);
    }

    @Test
    void joinedWorkSharesOneConnectionAndANewTransactionIsIndependent() throws Exception {
        AtomicReference<Connection> outer = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> tx.runInTx(() -> {
            outer.set(tx.connection());
            tx.inTx(() -> {
                assertSame(outer.get(), tx.connection(), "a joined unit uses the same connection (P-1)");
                return null;
            });
            tx.inNewTx(() -> {
                insert("independent");
                return null;
            });
            insert("rolled-back");
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, rows(), "only the new transaction's row committed");
        tx.newTransactions().inTransaction(c -> {
            assertFalse(c == null);
            return null;
        });
    }

    @Test
    void readTransactionsJoinTheCurrentTransaction() {
        ReadTransactions reads = new SpringReadTransactions(new DataSourceTransactionManager(ds));
        tx.runInTx(() -> {
            Connection c = tx.connection();
            reads.inReadTx(() -> {
                assertSame(c, tx.connection());
                return null;
            });
        });
        assertEquals("x", reads.inReadTx(() -> "x"));
    }
}
