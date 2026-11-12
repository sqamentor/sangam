// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * M-044 (W3-F REQUEST 2026-11-12T14:17:20Z): PROCESS_TRACE (changeset 0043-12) holds one W3C trace id per process
 * instance, keyed by the instance, with the tenant and root optional and an index on the root.
 */
class ProcessTraceTableTest {

    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    static void insert(Connection c, String pi, String tenant, String root, String trace) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO PROCESS_TRACE(process_instance_id, tenant_id,"
                + " root_process_instance_id, trace_id, created_at_ms) VALUES (?,?,?,?,?)")) {
            ps.setString(1, pi);
            ps.setString(2, tenant);
            ps.setString(3, root);
            ps.setString(4, trace);
            ps.setLong(5, 1_762_950_000_000L);
            ps.executeUpdate();
        }
    }

    @Test
    void oneTraceRowPerProcessInstance() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "sa", "")) {
            SangamSchema.apply(c, SangamSchema.MASTER_CHANGELOG, null);
            try (ResultSet rs = c.getMetaData().getColumns(null, null, "PROCESS_TRACE", "TRACE_ID")) {
                assertTrue(rs.next());
                assertEquals(32, rs.getInt("COLUMN_SIZE"));
                assertEquals("NO", rs.getString("IS_NULLABLE"));
            }
            try (ResultSet rs = c.getMetaData().getPrimaryKeys(null, null, "PROCESS_TRACE")) {
                assertTrue(rs.next());
                assertEquals("PROCESS_INSTANCE_ID", rs.getString("COLUMN_NAME"));
            }
            boolean rootIndex = false;
            try (ResultSet rs = c.getMetaData().getIndexInfo(null, null, "PROCESS_TRACE", false, false)) {
                while (rs.next()) {
                    rootIndex |= "PROCESS_TRACE_ROOT".equals(rs.getString("INDEX_NAME"))
                            && "ROOT_PROCESS_INSTANCE_ID".equals(rs.getString("COLUMN_NAME"));
                }
            }
            assertTrue(rootIndex, "index PROCESS_TRACE_ROOT on root_process_instance_id");

            insert(c, "pi-root", null, null, TRACE);                  // single-tenant root: tenant and root optional
            insert(c, "pi-child", "t1", "pi-root", TRACE);
            assertThrows(SQLException.class, () -> insert(c, "pi-root", null, null, TRACE), "one row per instance");
            assertThrows(SQLException.class, () -> insert(c, "pi-x", null, null, null), "trace id required");
            try (PreparedStatement ps = c.prepareStatement("SELECT process_instance_id, trace_id FROM PROCESS_TRACE"
                    + " WHERE root_process_instance_id=?")) {
                ps.setString(1, "pi-root");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("pi-child", rs.getString(1));
                    assertEquals(TRACE, rs.getString(2));
                }
            }
        }
    }
}
