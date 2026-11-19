// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SkipLockedRecheck#stillMatches} re-runs the skip-locked predicate, with its binds, on one row. */
class SkipLockedRecheckH2Test {

    @Test
    void matchesWhileTheRowMeetsThePredicateAndNotAfterItChanges() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = h2.getConnection()) {
            Jdbc.update(c, "CREATE TABLE WORK(id BIGINT PRIMARY KEY, state VARCHAR(16), owner VARCHAR(16))");
            Jdbc.update(c, "INSERT INTO WORK VALUES (1, 'PENDING', 'n1'), (2, 'PENDING', 'n1')");
            String where = "w.state = ? AND w.owner = ?";

            assertTrue(SkipLockedRecheck.stillMatches(c, "WORK", "w", "id", 1L, where, "PENDING", "n1"));
            assertFalse(SkipLockedRecheck.stillMatches(c, "WORK", "w", "id", 1L, where, "PENDING", "n2"), "binds are applied");
            assertFalse(SkipLockedRecheck.stillMatches(c, "WORK", "w", "id", 3L, where, "PENDING", "n1"), "missing row");

            Jdbc.update(c, "UPDATE WORK SET state='DONE' WHERE id=1");

            assertFalse(SkipLockedRecheck.stillMatches(c, "WORK", "w", "id", 1L, where, "PENDING", "n1"), "row changed");
            assertTrue(SkipLockedRecheck.stillMatches(c, "WORK", "w", "id", 2L, where, "PENDING", "n1"), "only the key's row");
        }
    }
}
