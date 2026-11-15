// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * An executor node id is the job executor's lock owner, which may be operator-set free text or a Kubernetes pod name
 * (up to 253 characters); Operaton keeps it in ACT_RU_JOB.LOCK_OWNER_ varchar(255). Changesets 0042-01..03 widen every
 * EXEC_* column holding it to 255, keeping nullability and EXEC_NODE's primary key.
 */
class ExecutorNodeIdWidthTest {

    @Test
    void nodeIdColumnsHold255CharactersAfterTheChangelog() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")) {
            SangamSchema.apply(c, SangamSchema.MASTER_CHANGELOG, null);
            Map<String, String> columns = Map.of("EXEC_PARTITION", "OWNER_NODE", "EXEC_MAP", "COORDINATOR_NODE",
                    "EXEC_NODE", "NODE_ID");
            for (Map.Entry<String, String> e : columns.entrySet()) {
                try (ResultSet rs = c.getMetaData().getColumns(null, null, e.getKey(), e.getValue())) {
                    assertTrue(rs.next(), e.toString());
                    assertEquals(255, rs.getInt("COLUMN_SIZE"), e.toString());
                    assertEquals(e.getKey().equals("EXEC_NODE") ? "NO" : "YES", rs.getString("IS_NULLABLE"), e.toString());
                }
            }
            try (ResultSet rs = c.getMetaData().getPrimaryKeys(null, null, "EXEC_NODE")) {
                assertTrue(rs.next());
                assertEquals("NODE_ID", rs.getString("COLUMN_NAME"));
            }
            String pod = "n".repeat(253);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO EXEC_NODE(node_id, incarnation, started_at,"
                    + " heartbeat_at) VALUES (?, 'i', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                ps.setString(1, pod);
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE EXEC_PARTITION SET owner_node=?")) {
                ps.setString(1, pod);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void sqlServerReaddsTheNodeKeyAroundTheAlter() throws IOException {
        String sql = read("db/changelog/mssql/sangam-0001-baseline.sql");
        assertTrue(sql.contains("ALTER TABLE EXEC_NODE DROP CONSTRAINT PK_EXEC_NODE; ALTER TABLE EXEC_NODE ALTER COLUMN node_id"
                + " NVARCHAR(255) NOT NULL; ALTER TABLE EXEC_NODE ADD CONSTRAINT PK_EXEC_NODE PRIMARY KEY (node_id);"));
        assertTrue(sql.contains("ALTER TABLE EXEC_PARTITION ALTER COLUMN owner_node NVARCHAR(255) NULL;"));
        assertTrue(read("db/changelog/oracle/sangam-0001-baseline.sql")
                .contains("ALTER TABLE EXEC_MAP MODIFY (coordinator_node VARCHAR2(255 CHAR));"));
        assertTrue(read("db/changelog/mysql/sangam-0001-baseline.sql")
                .contains("ALTER TABLE EXEC_NODE MODIFY COLUMN node_id VARCHAR(255) NOT NULL;"));
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = ExecutorNodeIdWidthTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
