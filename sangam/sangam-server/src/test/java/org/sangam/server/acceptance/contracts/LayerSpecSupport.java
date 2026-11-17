// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import org.sangam.core.api.ReadWork;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.acceptance.AcceptanceDatabase;

/**
 * Base of the product-layer specs (Stage B {@code spike/product-layer} unit tests) as DB-backed ITs against
 * sangam-core's real classes on the server's Liquibase-defined tables, in a private per-run namespace
 * ({@link IsolatedProductTables}) truncated before every test as Stage B's {@code PgTestBase} did. Runs on every
 * acceptance database ({@code -Dsangam.db}), each in its own {@link #DIALECT}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class LayerSpecSupport extends ContractSupport {

    /**
     * The dialect under test: the acceptance run's database, known before Spring injects anything so the specs' field
     * initialisers can take it; {@link #ensureIso()} checks it against the dialect detected on the isolated pool.
     */
    final Dialect DIALECT = IsolationPlan.dialectOf(AcceptanceDatabase.name());
    DataSource DS;
    IsolatedProductTables iso;

    /** Resolves the isolated tables on first use, so the specs' field initialisers can take it. */
    final Transactions TX = new Transactions() {
        @Override
        public <T> T inTransaction(Function<Connection, T> work) {
            ensureIso();
            return iso.tx().inTransaction(work);
        }

        @Override
        public <T> T read(ReadWork<T> work) {
            ensureIso();
            return iso.tx().read(work);
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
            IsolatedProductTables t = IsolatedProductTables.get();
            Dialect detected = TxSupport.detectDialect(t.ds);
            if (detected != DIALECT) {
                throw new IllegalStateException("isolated pool is " + detected + ", the run is " + DIALECT);
            }
            iso = t;
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

    /** The paging clause for an {@code ORDER BY} query in {@link #DIALECT} ({@link IsolationPlan#page}). */
    String page(int limit, int offset) {
        return IsolationPlan.page(DIALECT, limit, offset);
    }

    /**
     * Creates a spec-owned table in the isolated namespace unless it exists: {@code template} is portable DDL with
     * {@link Dialect} type tokens (for example {@code {str64}}), expanded for {@link #DIALECT} with its table options.
     */
    void createTableIfMissing(String table, String template) throws SQLException {
        ensureIso();
        try (Connection c = DS.getConnection()) {
            if (!tableExists(c, table)) {
                try (Statement st = c.createStatement()) {
                    st.execute(DIALECT.expand(template) + DIALECT.createTableSuffix());
                }
            }
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), c.getSchema(), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
