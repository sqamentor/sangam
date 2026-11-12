// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.bulk;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.ops.OpsSchema;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression SL-87: a row rejected for a missing external id was stored with {@code external_id = ''} in the NOT NULL
 * column OPS_BULK_ROW.external_id. Oracle stores '' as NULL, so ORA-01400 aborted the whole chunk upload instead of
 * reporting one invalid row. The column is nullable now (changeset 0035-03): such a row is stored with NULL, reported as
 * FAILED/INVALID_ROW in the error report, and its external id reads back as "". Valid rows always carry their id.
 *
 * <p>Runs in H2's PostgreSQL mode (as BulkIngestH2Test): H2's Oracle mode cannot run the audit chain (no ON CONFLICT; its
 * duplicate-key code is 23505, not ORA-00001). The stored-NULL assertion is the Oracle property.
 */
class BulkRowMissingExternalIdH2Test {

    @Test
    void aRowWithoutExternalIdIsReportedNotFatal() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            OpsSchema.create(c, Dialect.POSTGRESQL);
        }
        BulkIngest b = new BulkIngest(Dialect.POSTGRESQL, Transactions.of(ds), new AuditChain(Dialect.POSTGRESQL), null, "node-1")
                .register(new BulkIngest.Sink() {
                    public String objectType() {
                        return "test.item";
                    }

                    public String permission() {
                        return "test.load";
                    }

                    public String apply(Connection c, String tenant, String actor, String ext, Map<String, Object> data,
                                        String existingRef) {
                        return "item:" + ext;
                    }
                });
        String job = b.create("t1", "test.item", 1, "alice").id();
        BulkIngest.ChunkReceipt r = b.upload("t1", job, 0, List.of(
                new BulkIngest.RowInput("a-1", Map.of("v", 1)),
                new BulkIngest.RowInput("", Map.of("v", 2)),
                new BulkIngest.RowInput(null, Map.of("v", 3))), "alice");
        assertEquals(3, r.rows());
        assertEquals(2, r.invalidRows(), "both rows without an external id are invalid, the chunk is accepted");

        List<BulkIngest.RowStatus> errors = b.errors("t1", job, 0, 50).items();
        assertEquals(2, errors.size());
        for (BulkIngest.RowStatus e : errors) {
            assertEquals("", e.externalId(), "a stored NULL external id reads back as empty");
            assertEquals("INVALID_ROW", e.errorCode());
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT row_seq, external_id FROM OPS_BULK_ROW ORDER BY row_seq")) {
            rs.next();
            assertEquals("a-1", rs.getString(2), "a valid row keeps its id");
            rs.next();
            assertNull(rs.getString(2), "an empty external id is stored as NULL ('' is NULL on Oracle)");
            rs.next();
            assertNull(rs.getString(2), "a missing external id is stored as NULL");
        }
    }
}
