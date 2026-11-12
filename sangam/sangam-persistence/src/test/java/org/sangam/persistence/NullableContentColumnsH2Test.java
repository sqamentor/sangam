// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-87: after the master changelog, the three text columns that may legitimately be empty are nullable (Oracle stores
 * '' as NULL, so a NOT NULL column refused them with ORA-01400), and AI_DRAFT.content, whose empty value is refused by
 * the service instead, stays NOT NULL. Checks the generated NULLABLE changesets 0035-01..03 end to end on H2.
 */
class NullableContentColumnsH2Test {

    @Test
    void emptyableContentColumnsAreNullableAfterTheChangelog() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:sl87;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")) {
            SangamSchema.apply(c, SangamSchema.MASTER_CHANGELOG, null);
            DatabaseMetaData md = c.getMetaData();
            assertEquals(DatabaseMetaData.columnNullable, nullable(md, "CH_MESSAGE", "BODY"));
            assertEquals(DatabaseMetaData.columnNullable, nullable(md, "AI_AGENT_RUN", "INPUT_TEXT"));
            assertEquals(DatabaseMetaData.columnNullable, nullable(md, "OPS_BULK_ROW", "EXTERNAL_ID"));
            assertEquals(DatabaseMetaData.columnNoNulls, nullable(md, "AI_DRAFT", "CONTENT"), "an empty draft is refused, not stored");
        }
    }

    private static int nullable(DatabaseMetaData md, String table, String column) throws Exception {
        try (ResultSet rs = md.getColumns(null, null, table, column)) {
            assertTrue(rs.next(), table + "." + column + " exists");
            return rs.getInt("NULLABLE");
        }
    }
}
