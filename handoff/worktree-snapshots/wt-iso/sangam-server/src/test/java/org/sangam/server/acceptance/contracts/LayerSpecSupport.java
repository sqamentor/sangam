// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;

/**
 * Base of the product-layer specs (Stage B {@code spike/product-layer} unit tests) as DB-backed ITs against
 * sangam-core's real classes on the server's Liquibase-defined tables, in a private clone
 * ({@link IsolatedProductTables}) truncated before every test as Stage B's {@code PgTestBase} did.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class LayerSpecSupport extends ContractSupport {

    /** Name kept from Stage B's PgTestBase: the dialect under test (the contract ports run on PostgreSQL). */
    final Dialect PG = Dialect.POSTGRESQL;
    DataSource DS;
    IsolatedProductTables iso;

    /** Resolves the isolated tables on first use, so the specs' field initialisers can take it. */
    final Transactions TX = new Transactions() {
        @Override
        public <T> T inTransaction(Function<Connection, T> work) {
            ensureIso();
            return iso.tx().inTransaction(work);
        }
    };

    @BeforeEach
    void isolatedTables() {
        ensureIso();
        iso.truncate();
    }

    /** Also usable from a {@code @BeforeAll} (PER_CLASS): Spring has injected the instance by then. */
    void ensureIso() {
        if (iso == null) {
            iso = IsolatedProductTables.get(dataSource);
            DS = iso.ds;
        }
    }

    @Override
    <T> T inTx(Function<Connection, T> work) {
        return TX.inTransaction(work);
    }

    boolean txBool(Function<Connection, Boolean> work) {
        return TX.inTransaction(work);
    }

    long txLong(Function<Connection, Long> work) {
        return TX.inTransaction(work);
    }

    int txInt(Function<Connection, Integer> work) {
        return TX.inTransaction(work);
    }

    void run(Consumer<Connection> work) {
        TX.inTransaction(c -> {
            work.accept(c);
            return null;
        });
    }

    /** A connection with autocommit off that the test commits or rolls back explicitly (for interleavings). */
    Connection openTx() throws SQLException {
        ensureIso();
        Connection c = DS.getConnection();
        c.setAutoCommit(false);
        return c;
    }
}
