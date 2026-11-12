// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Decision 97 / W3-H defect: SEC_TENANT stores the tenant's isolation (changeset 0043-11). SHARED is the default (rows
 * written before the column are read as what they were served as), only SHARED, SCHEMA and DATABASE are accepted, and
 * every dialect names the CHECK so its rollback can drop it.
 */
class TenantIsolationColumnTest {

    static void insert(Connection c, String id, String isolation) throws SQLException {
        String sql = "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at"
                + (isolation == null ? "" : ", isolation") + ") VALUES (?, 'n', 'ACTIVE', '{}', '{}', 0, CURRENT_TIMESTAMP"
                + (isolation == null ? "" : ", ?") + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            if (isolation != null) {
                ps.setString(2, isolation);
            }
            ps.executeUpdate();
        }
    }

    static String isolation(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT isolation FROM SEC_TENANT WHERE tenant_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    @Test
    void theIsolationIsStoredDefaultsToSharedAndIsChecked() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "sa", "")) {
            SangamSchema.apply(c, SangamSchema.MASTER_CHANGELOG, null);
            try (ResultSet rs = c.getMetaData().getColumns(null, null, "SEC_TENANT", "ISOLATION")) {
                assertTrue(rs.next());
                assertEquals("NO", rs.getString("IS_NULLABLE"));
            }
            insert(c, "legacy", null);
            insert(c, "db", "DATABASE");
            insert(c, "schema", "SCHEMA");
            assertEquals("SHARED", isolation(c, "legacy"), "the default");
            assertEquals("DATABASE", isolation(c, "db"), "not silently stored as SHARED");
            assertEquals("SCHEMA", isolation(c, "schema"));
            assertThrows(SQLException.class, () -> insert(c, "bad", "POOLED"), "the CHECK refuses other values");
            assertThrows(SQLException.class, () -> insert(c, "lower", "database"), "values are upper case");
        }
    }

    @Test
    void everyDialectAddsTheNamedCheck() throws IOException {
        for (String dbms : new String[]{"postgresql", "oracle", "mssql", "mysql", "h2"}) {
            String sql = read("db/changelog/" + dbms + "/sangam-0001-baseline.sql");
            assertTrue(sql.contains("--changeset sangam:0043-11-sec_tenant-alter dbms:" + dbms), dbms);
            assertTrue(sql.contains("DEFAULT 'SHARED' NOT NULL CONSTRAINT CK_SEC_TENANT_ISOLATION CHECK (isolation IN"
                    + " ('SHARED', 'SCHEMA', 'DATABASE'))"), dbms);
            assertTrue(sql.contains("ALTER TABLE SEC_TENANT DROP COLUMN isolation;"), dbms + " rollback");
        }
        assertTrue(read("db/changelog/mssql/sangam-0001-baseline.sql").contains("CONSTRAINT DF_SEC_TENANT_ISOLATION DEFAULT"
                + " 'SHARED'"), "SQL Server names the default so the rollback can drop it");
    }

    static String read(String resource) throws IOException {
        try (InputStream in = TenantIsolationColumnTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
