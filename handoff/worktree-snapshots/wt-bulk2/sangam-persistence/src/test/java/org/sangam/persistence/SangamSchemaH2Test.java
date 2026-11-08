// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;

/** Fast smoke test: the master changelog (XML and YAML entry points) applies on H2 and creates every product table. */
class SangamSchemaH2Test {

    @Test
    void masterChangelogCreatesAllProductTablesAndIsIdempotent() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:schema1;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")) {
            SangamSchema.apply(c, SangamSchema.MASTER_CHANGELOG, null);
            SangamSchema.apply(c, "db/changelog/sangam-master.yaml", null); // same changesets: nothing new
            // IR-8 changelog parses and is a no-op on H2 (its changesets are PostgreSQL/Oracle/SQL Server only)
            SangamSchema.apply(c, SangamSchema.HISTORY_PARTITIONING_CHANGELOG, SangamSchema.HISTORY_PARTITIONING_CONTEXT);
            for (String t : SangamSchema.PRODUCT_TABLES) {
                try (ResultSet rs = c.getMetaData().getTables(null, null, t, null)) {
                    assertTrue(rs.next(), "missing table " + t);
                }
            }
            try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM DATABASECHANGELOG")) {
                rs.next();
                assertEquals(SangamSchema.PRODUCT_TABLES.size() + SangamSchema.ADDITIONAL_CHANGESETS, rs.getInt(1));
            }
        }
    }
}
