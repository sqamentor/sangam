// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.store;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.config.master.MasterCode;
import org.sangam.core.layer.Dialect;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-130: {@link JdbcConfigStore#put(Connection, MasterCode)} writes on the caller's connection, so the bulk row's
 * transaction commits or rolls back the master code with the rest of its work, and a repeat replaces the same key.
 */
class MasterCodeCallerConnectionH2Test {

    JdbcDataSource ds;
    JdbcConfigStore store;

    static MasterCode code(String name) {
        return new MasterCode("t1", "HOA", "MAJOR", "2054", null, Map.of("en", name), Map.of("active", true),
                LocalDate.of(2026, 4, 1), null, "bulk");
    }

    @BeforeEach
    void db() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            ConfigSchema.create(c, Dialect.POSTGRESQL);
        }
        store = new JdbcConfigStore(ds, Dialect.POSTGRESQL);
    }

    @Test
    void aRolledBackCallerTransactionLeavesNoCode() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            store.put(c, code("Treasury"));
            c.rollback();
        }
        assertTrue(store.find("t1", "HOA", "MAJOR", null, "2054").isEmpty());
    }

    @Test
    void aCommittedCallerTransactionKeepsOneRowPerKeyAndRepeatsReplace() throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            store.put(c, code("Treasury"));
            store.put(c, code("Treasury")); // a retried row in the same transaction
            c.commit();
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            store.put(c, code("Treasuries"));
            c.commit();
        }
        var found = store.find("t1", "HOA", "MAJOR", null, "2054");
        assertEquals(1, found.size());
        assertEquals("Treasuries", found.get(0).name().get("en"));
    }

    @Test
    void theOldMethodStillCommitsItsOwnTransaction() {
        store.put(code("Treasury"));
        assertEquals(1, store.find("t1", "HOA", "MAJOR", null, "2054").size());
    }
}
