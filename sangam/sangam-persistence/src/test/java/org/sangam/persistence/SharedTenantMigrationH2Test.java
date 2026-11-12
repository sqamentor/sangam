// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SL-86 migration: changeset 0036-01 moves shared CF_CONFIG rows from tenant_id '' to the reserved shared key '-', and
 * its preflight HALTs the migration (changing nothing) when a '' row would collide with an existing '-' row in a key.
 * The changeset is re-run by removing its DATABASECHANGELOG row after the planted data is in place.
 */
class SharedTenantMigrationH2Test {

    private static final String CHANGESET = "0036-01-cf_config-shared-tenant";

    @Test
    void sharedRowsMoveToTheSharedKey() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:sl86a;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")) {
            SangamSchema.apply(c, SangamSchema.MASTER_CHANGELOG, null);
            insert(c, "", "k1");
            insert(c, "RJ", "k1");
            rerun(c);
            assertEquals(List.of("-|k1", "RJ|k1"), rows(c), "'' became '-', a real tenant is untouched");
        }
    }

    @Test
    void aPlantedCollisionHaltsTheMigrationAndChangesNothing() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:sl86b;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")) {
            SangamSchema.apply(c, SangamSchema.MASTER_CHANGELOG, null);
            insert(c, "", "k1");
            insert(c, "-", "k1");   // the same key under both: the UPDATE would violate the primary key
            insert(c, "", "k2");
            RuntimeException e = assertThrows(RuntimeException.class, () -> rerun(c));
            assertEquals(List.of("|k1", "|k2", "-|k1"), rows(c), "a halted migration changes nothing: " + e.getMessage());
        }
    }

    private static void insert(Connection c, String tenant, String key) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO CF_CONFIG(tenant_id, config_kind, config_key, version, definition_json, content_hash, active,"
                    + " defined_by, defined_at) VALUES ('" + tenant + "', 'COMMITTEE_KIND', '" + key + "', 1, '{}', 'h', 1, 'ops',"
                    + " CURRENT_TIMESTAMP)");
        }
    }

    private static void rerun(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            assertEquals(1, s.executeUpdate("DELETE FROM DATABASECHANGELOG WHERE ID = '" + CHANGESET + "'"));
        }
        SangamSchema.apply(c, SangamSchema.MASTER_CHANGELOG, null);
    }

    private static List<String> rows(Connection c) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT tenant_id, config_key FROM CF_CONFIG ORDER BY tenant_id, config_key")) {
            while (rs.next()) {
                out.add(rs.getString(1) + "|" + rs.getString(2));
            }
        }
        return out;
    }
}
