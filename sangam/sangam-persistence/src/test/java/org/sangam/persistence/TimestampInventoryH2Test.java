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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Decisions 150 and 203: the generated {@code db/timestamp-inventory.csv} lists every timestamp and date column of the
 * product tables exactly once with a class, so the UTC conversion tool never meets an unclassified column. The column
 * set is read from the applied H2 schema, not from the generator, so a table added outside the generator shows up here.
 */
class TimestampInventoryH2Test {

    static final Set<String> CLASSES = Set.of("EVENT_INSTANT", "LOCAL_BUSINESS", "DATE_ONLY", "PROTECTED", "TOOL");
    /** Created by {@code sangam-0002-onboarding.sql}, outside the generator catalogue; not inventoried (stated gap). */
    static final Set<String> OUTSIDE_GENERATOR = Set.of("ONB_RUN");

    static Map<String, String[]> inventory() throws IOException {
        try (InputStream in = TimestampInventoryH2Test.class.getClassLoader()
                .getResourceAsStream("db/timestamp-inventory.csv")) {
            assertTrue(in != null, "db/timestamp-inventory.csv is generated next to the changelogs");
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            assertEquals("table,column,token,class,changeset", lines.get(0));
            Map<String, String[]> out = new HashMap<>();
            for (String line : lines.subList(1, lines.size())) {
                String[] f = line.split(",", -1);
                assertEquals(5, f.length, line);
                String key = f[0].toUpperCase() + "." + f[1].toUpperCase();
                assertTrue(out.put(key, f) == null, "listed once: " + key);
            }
            return out;
        }
    }

    @Test
    void everyProductTimestampAndDateColumnIsClassifiedOnce() throws Exception {
        Map<String, String[]> inv = inventory();
        Set<String> inDb = new TreeSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")) {
            SangamSchema.apply(c, SangamSchema.MASTER_CHANGELOG, null);
            try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                    + " WHERE TABLE_SCHEMA = 'PUBLIC' AND DATA_TYPE IN ('TIMESTAMP WITH TIME ZONE', 'DATE')");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    if (!SangamSchema.PRODUCT_TABLES.contains(table) || OUTSIDE_GENERATOR.contains(table)) {
                        continue;
                    }
                    String key = table + "." + rs.getString(2);
                    inDb.add(key);
                    String[] row = inv.get(key);
                    assertTrue(row != null, "not inventoried: " + key);
                    assertEquals(rs.getString(3).equals("DATE") ? "{date}" : "{ts}", row[2], key);
                }
            }
        }
        assertEquals(new TreeSet<>(inv.keySet()), inDb, "the inventory lists no column the schema lacks");
        for (String[] row : inv.values()) {
            assertTrue(CLASSES.contains(row[3]), String.join(",", row));
            if (row[2].equals("{date}")) {
                assertEquals("DATE_ONLY", row[3], "date-only business fields are never converted: " + row[0] + "." + row[1]);
            }
        }
        assertEquals("PROTECTED", inv.get("AUDIT_EVENT.OCCURRED_AT")[3],
                "hash-chained audit rows are never converted without a preservation strategy");
        assertEquals("TOOL", inv.get("OPS_UTC_RUN.CUTOVER_AT")[3]);
        assertEquals("EVENT_INSTANT", inv.get("HRM_TASK.CREATE_TIME")[3]);
        assertEquals("EVENT_INSTANT", inv.get("LDG_ENTRY.AT_TIME")[3]);
        assertEquals("DATE_ONLY", inv.get("PG_PERIOD.DUE_ON")[3]);
    }
}
