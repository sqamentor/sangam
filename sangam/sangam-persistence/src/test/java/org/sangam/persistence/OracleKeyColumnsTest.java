// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Regression (ORA-02329): Oracle refuses a TIMESTAMP WITH TIME ZONE column in a PRIMARY KEY, UNIQUE constraint or
 * plain-column unique index. The generator turns such keys into a unique index on SYS_EXTRACT_UTC(column); this test
 * scans every table of the Oracle changelog, so a future table cannot reintroduce the failure.
 */
class OracleKeyColumnsTest {

    private static final Pattern TABLE = Pattern.compile("CREATE TABLE (\\w+) \\((.*?)\\n\\);", Pattern.DOTALL);
    private static final Pattern TSTZ = Pattern.compile("^\\s+(\\w+) TIMESTAMP(?:\\(\\d\\))? WITH TIME ZONE",
            Pattern.MULTILINE);
    private static final Pattern KEY = Pattern.compile("(?:PRIMARY KEY|UNIQUE) \\(([^)]*)\\)");
    private static final Pattern UNIQUE_INDEX = Pattern.compile("CREATE UNIQUE INDEX (\\w+) ON (\\w+) \\(([^;]*)\\);");

    @Test
    void noTimeZoneColumnIsAPlainKeyColumnOnOracle() throws IOException {
        String sql = read("db/changelog/oracle/sangam-0001-baseline.sql");
        List<String> violations = new ArrayList<>();
        java.util.Map<String, Set<String>> tz = new java.util.HashMap<>();
        Matcher t = TABLE.matcher(sql);
        int tables = 0;
        while (t.find()) {
            tables++;
            Set<String> cols = new HashSet<>();
            Matcher c = TSTZ.matcher(t.group(2));
            while (c.find()) {
                cols.add(c.group(1).toLowerCase(Locale.ROOT));
            }
            tz.put(t.group(1), cols);
            Matcher k = KEY.matcher(t.group(2));
            while (k.find()) {
                for (String col : k.group(1).split(",")) {
                    if (cols.contains(col.trim().toLowerCase(Locale.ROOT))) {
                        violations.add(t.group(1) + " key column " + col.trim());
                    }
                }
            }
        }
        Matcher u = UNIQUE_INDEX.matcher(sql);
        while (u.find()) {
            Set<String> cols = tz.getOrDefault(u.group(2), Set.of());
            for (String col : u.group(3).split(",")) {
                if (cols.contains(col.trim().toLowerCase(Locale.ROOT))) {
                    violations.add(u.group(1) + " unique index column " + col.trim());
                }
            }
        }
        assertTrue(tables > 200, "changelog parsed (" + tables + " tables)");
        assertTrue(violations.isEmpty(), "ORA-02329 on Oracle: " + violations);
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = OracleKeyColumnsTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
