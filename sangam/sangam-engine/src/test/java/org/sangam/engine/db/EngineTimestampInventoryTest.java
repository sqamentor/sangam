// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Decision 150/203: the engine inventory equals the timestamp columns of Operaton's own create scripts on the class
 * path, every row is EVENT_INSTANT, and the MySQL session-zoned set matches Operaton's MySQL scripts. Written, NOT_RUN.
 */
class EngineTimestampInventoryTest {

    private static final String[] PARTS = {"engine", "history", "identity", "case.engine", "case.history",
            "decision.engine", "decision.history"};

    private static String script(String db, String part) throws Exception {
        try (InputStream in = EngineTimestampInventoryTest.class.getClassLoader()
                .getResourceAsStream("org/operaton/bpm/engine/db/create/activiti." + db + ".create." + part + ".sql")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** {@code TABLE.COLUMN} of every column whose type starts with {@code typePrefix}. */
    private static Set<String> columns(String db, String typePrefix) throws Exception {
        Set<String> out = new TreeSet<>();
        Pattern table = Pattern.compile("(?i)create table (\\w+)");
        Pattern col = Pattern.compile("(?i)^\\s*(\\w+)\\s+" + typePrefix + "\\b");
        for (String part : PARTS) {
            String current = null;
            for (String line : script(db, part).split("\n")) {
                Matcher t = table.matcher(line);
                if (t.find()) {
                    current = t.group(1).toUpperCase(Locale.ROOT);
                    continue;
                }
                Matcher c = col.matcher(line);
                if (current != null && c.find()) {
                    out.add(current + "." + c.group(1).toUpperCase(Locale.ROOT));
                }
                if (line.trim().startsWith(");")) {
                    current = null;
                }
            }
        }
        return out;
    }

    @Test
    void inventoryEqualsOperatonsTimestampColumnsAndIsAllEventInstant() throws Exception {
        Set<String> listed = new TreeSet<>();
        String[] lines = EngineTimestampInventory.csv().lines().toArray(String[]::new); // LF or CRLF checkout
        assertEquals("table,column,token,class,changeset", lines[0]);
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] f = lines[i].split(",", -1);
            assertEquals(5, f.length, lines[i]);
            assertEquals("EVENT_INSTANT", f[3], lines[i]);
            assertTrue(f[4].startsWith("operaton-2.2.0:"), lines[i]);
            assertTrue(listed.add(f[0] + "." + f[1]), "listed twice: " + lines[i]);
        }
        Set<String> postgres = columns("postgres", "timestamp");
        assertEquals(66, postgres.size(), "Operaton 2.2.0 has 66 timestamp columns; a new one needs a class first");
        assertEquals(postgres, listed);
        assertEquals(postgres, columns("mssql", "datetime2"), "same columns on SQL Server");
        assertEquals(postgres, columns("oracle", "TIMESTAMP"), "same columns on Oracle");
    }

    @Test
    void mysqlSessionZonedColumnsAreExactlyOperatonsTimestampTypedOnes() throws Exception {
        assertEquals(new TreeSet<>(EngineTimestampInventory.MYSQL_SESSION_ZONED), columns("mysql", "timestamp"));
        assertEquals(new TreeSet<>(EngineTimestampInventory.MYSQL_SESSION_ZONED), columns("mariadb", "timestamp"));
    }
}
