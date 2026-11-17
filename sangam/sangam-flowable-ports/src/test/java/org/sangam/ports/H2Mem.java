// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Test support: drops a named in-memory H2 database.
 *
 * <p>The test fixtures open {@code jdbc:h2:mem:...;DB_CLOSE_DELAY=-1} so the database outlives the engine's pooled
 * connections during a test. With that flag H2 keeps the database (every table, index and row) for the whole JVM until
 * someone runs {@code SHUTDOWN}; closing the Operaton engine is not enough. Without this call each fixture leaked about
 * 40 MB, and the sangam-flowable-ports test JVM ran out of heap after ~1500-1700 tests (R8-heap).
 */
public final class H2Mem {

    private H2Mem() {
    }

    /** Runs {@code SHUTDOWN} on the in-memory database at {@code jdbcUrl}; does nothing for other URLs. */
    public static void drop(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:h2:mem:")) {
            return;
        }
        // IFEXISTS=TRUE: never re-create a database that is already gone just to shut it down
        try (Connection c = DriverManager.getConnection(jdbcUrl + ";IFEXISTS=TRUE", "sa", "");
                Statement st = c.createStatement()) {
            st.execute("SHUTDOWN");
        } catch (SQLException ex) {
            if (ex.getErrorCode() != 90146) { // DATABASE_NOT_FOUND_WITH_IF_EXISTS_1: already dropped
                throw new IllegalStateException("could not drop " + jdbcUrl, ex);
            }
        }
    }
}
