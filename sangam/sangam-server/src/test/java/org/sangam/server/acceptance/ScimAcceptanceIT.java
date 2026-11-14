// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.scim.ScimFilter;
import org.sangam.security.scim.ScimStore;

/**
 * M-099 SCIM store on the lane's real database and driver (owner decision 154; PostgreSQL, Oracle, SQL Server, MySQL
 * through {@code -Dsangam.db}): the generated {@code 0044-01-sec_scim_resource} DDL, the NOT NULL key columns that carry
 * the 409 uniqueness on every dialect (SQL Server allows one NULL per unique index), LIKE with {@code ESCAPE '!'}
 * (Oracle refuses an escape before other characters; SQL Server treats {@code [} as a class), case-insensitive
 * userName and displayName, case-exact externalId, OFFSET paging, the CLOB / NVARCHAR(MAX) attributes and the
 * optimistic version. Plain JDBC on {@link AcceptanceDatabase}; no server is started.
 *
 * <p>Status: written, NOT_RUN on every lane, Oracle and SQL Server included. When the lane database has no product
 * schema yet, the test runs the generated changeset itself and drops the table again.
 */
class ScimAcceptanceIT {

    private static final String CHANGESET = "--changeset sangam:0044-01-sec_scim_resource";

    private static SecurityDialect dialect() {
        return switch (AcceptanceDatabase.name()) {
            case "postgres" -> SecurityDialect.POSTGRESQL;
            case "oracle" -> SecurityDialect.ORACLE;
            case "sqlserver" -> SecurityDialect.SQLSERVER;
            case "mysql" -> SecurityDialect.MYSQL;
            default -> throw new IllegalStateException(AcceptanceDatabase.name());
        };
    }

    private static String changelogDir() {
        return switch (AcceptanceDatabase.name()) {
            case "postgres" -> "postgresql";
            case "sqlserver" -> "mssql";
            default -> AcceptanceDatabase.name();
        };
    }

    /** The statements of the generated changeset for this database, read from the product changelog on the class path. */
    static List<String> generatedDdl(String dir) throws IOException {
        String path = "db/changelog/" + dir + "/sangam-0001-baseline.sql";
        List<String> out = new ArrayList<>();
        try (InputStream in = ScimAcceptanceIT.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " is not on the class path");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean inside = false;
            StringBuilder stmt = new StringBuilder();
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (line.startsWith("--changeset ")) {
                    if (inside) {
                        break;
                    }
                    inside = line.startsWith(CHANGESET + " ");
                    continue;
                }
                if (!inside || line.startsWith("--") || line.isBlank()) {
                    continue;
                }
                stmt.append(line).append('\n');
                if (line.stripTrailing().endsWith(";")) {
                    String s = stmt.toString().strip();
                    out.add(s.substring(0, s.length() - 1));
                    stmt.setLength(0);
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(CHANGESET + " not found in " + path);
        }
        return out;
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        for (String name : List.of(table, table.toLowerCase(Locale.ROOT))) {
            try (ResultSet rs = c.getMetaData().getTables(null, null, name, new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void theScimStoreWorksOnTheLaneDatabase() throws Exception {
        SecurityDialect d = dialect();
        ScimStore store = new ScimStore(d);
        String t = "scim-it-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        try (Connection c = DriverManager.getConnection(AcceptanceDatabase.jdbcUrl(), AcceptanceDatabase.username(),
                AcceptanceDatabase.password())) {
            boolean created = false;
            if (!tableExists(c, "SEC_SCIM_RESOURCE")) {
                try (Statement s = c.createStatement()) {
                    for (String sql : generatedDdl(changelogDir())) {
                        s.execute(sql);
                    }
                }
                created = true;
            }
            try {
                String big = "{\"title\":\"" + "x".repeat(10_000) + "\"}";   // beyond VARCHAR2(4000): CLOB on Oracle
                assertTrue(store.insert(c, t, ScimStore.USER, "p1", "Alice.Rao", "Alice Rao", "ext_1", big, now));
                assertTrue(store.insert(c, t, ScimStore.USER, "p2", "bob%x", "Bob [ops]", null, null, now));
                assertTrue(store.insert(c, t, ScimStore.USER, "p3", "carol", "Carol", "EXT_1", null, now),
                        "externalId is case-exact");
                assertFalse(store.insert(c, t, ScimStore.USER, "p4", "ALICE.RAO", null, null, null, now),
                        "userName is unique ignoring case");
                assertFalse(store.insert(c, t, ScimStore.USER, "p5", "dave", null, "ext_1", null, now), "externalId is unique");
                assertTrue(store.insert(c, t, ScimStore.GROUP, "g1", null, "Clerks", null, null, now),
                        "two resources without externalId: NOT NULL keys, no second-NULL clash on SQL Server");
                assertTrue(store.insert(c, t, ScimStore.GROUP, "g2", null, "Clerks", null, null, now),
                        "group displayName is not unique");

                assertEquals(big, store.find(c, t, ScimStore.USER, "p1").orElseThrow().attributes());
                assertEquals(3, store.list(c, t, ScimStore.USER, List.of(), 1, 10).total());
                assertEquals(List.of("p2"), ids(store.list(c, t, ScimStore.USER, ScimFilter.parse("userName sw \"BOB%\""), 1, 10)));
                assertEquals(List.of(), ids(store.list(c, t, ScimStore.USER, ScimFilter.parse("userName co \"_\""), 1, 10)),
                        "_ is literal");
                assertEquals(List.of("p2"), ids(store.list(c, t, ScimStore.USER, ScimFilter.parse("displayName co \"[OPS]\""), 1, 10)),
                        "[ is literal on SQL Server too");
                assertEquals(List.of("p1"), ids(store.list(c, t, ScimStore.USER, ScimFilter.parse("externalId eq \"ext_1\""), 1, 10)));
                assertEquals(List.of("p1"), ids(store.list(c, t, ScimStore.USER,
                        ScimFilter.parse("userName eq \"alice.rao\" and displayName sw \"ali\""), 1, 10)));
                ScimStore.Page second = store.list(c, t, ScimStore.USER, List.of(), 2, 1);
                assertEquals(3, second.total());
                assertEquals(1, second.rows().size());
                assertEquals(2, store.list(c, t, ScimStore.GROUP, ScimFilter.parse("displayName eq \"CLERKS\""), 1, 10).total());

                assertTrue(store.update(c, t, ScimStore.USER, "p3", 1, "carol", "Carol R", "ext-3", null, now.plusSeconds(1)));
                assertFalse(store.update(c, t, ScimStore.USER, "p3", 1, "carol", "Carol X", "ext-3", null, now.plusSeconds(2)),
                        "a stale version changes nothing");
                assertEquals(2, store.find(c, t, ScimStore.USER, "p3").orElseThrow().version());
                assertEquals("Carol R", store.find(c, t, ScimStore.USER, "p3").orElseThrow().displayName());
                assertFalse(store.delete(c, t, ScimStore.USER, "p3", 1));
                assertTrue(store.delete(c, t, ScimStore.USER, "p3", 2));
                assertTrue(store.find(c, t, ScimStore.USER, "p3").isEmpty());
            } finally {
                if (created) {
                    try (Statement s = c.createStatement()) {
                        s.execute("DROP TABLE SEC_SCIM_RESOURCE");
                    }
                } else {
                    Jdbc.update(c, "DELETE FROM SEC_SCIM_RESOURCE WHERE tenant_id=?", t);
                }
            }
        }
    }

    private static List<String> ids(ScimStore.Page page) {
        return page.rows().stream().map(ScimStore.Row::id).toList();
    }
}
