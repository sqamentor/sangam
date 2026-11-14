// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Integrator ruling 2026-11-13 (W4-G INFO 04:01:32Z): every changeset that adds a column with a constraint can be
 * rolled back. SQL Server refuses DROP COLUMN while a default constraint exists, so each SQL Server DEFAULT of an added
 * column is named and its rollback drops it before the column; on every dialect a named CHECK of an added column is
 * dropped before the column, and no added column carries an inline UNIQUE (unique keys are indexes, dropped first).
 * Read from the generated changelogs; the rollbacks themselves run in the db-matrix (NOT_RUN in this coding phase).
 */
class AlterRollbackShapeTest {

    static final String[] DIALECTS = {"postgresql", "oracle", "mssql", "mysql", "h2"};
    /** An added column: PostgreSQL, MySQL and H2 ADD COLUMN, SQL Server ADD, Oracle ADD (...). */
    static final Pattern ADD = Pattern.compile("^ALTER TABLE (\\w+) ADD (?:COLUMN )?\\(?(\\w+) (.*)$");
    static final Pattern NAMED_DEFAULT = Pattern.compile("CONSTRAINT (DF_\\w+) DEFAULT ");
    static final Pattern NAMED_CHECK = Pattern.compile("CONSTRAINT (CK_\\w+) CHECK ");

    record ChangeSet(String id, List<String> statements, String rollback) {
    }

    static List<ChangeSet> changeSets(String dbms) throws IOException {
        String sql;
        try (InputStream in = AlterRollbackShapeTest.class.getClassLoader()
                .getResourceAsStream("db/changelog/" + dbms + "/sangam-0001-baseline.sql")) {
            assertTrue(in != null, dbms);
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<ChangeSet> out = new ArrayList<>();
        String[] parts = sql.split("\n--changeset ");
        for (int i = 1; i < parts.length; i++) {
            String[] lines = parts[i].split("\n");
            List<String> statements = new ArrayList<>();
            String rollback = "";
            for (int j = 1; j < lines.length; j++) {
                String l = lines[j].strip();
                if (l.startsWith("--rollback ")) {
                    rollback = l.substring("--rollback ".length());
                } else if (!l.isEmpty() && !l.startsWith("--")) {
                    statements.add(l);
                }
            }
            out.add(new ChangeSet(lines[0].split(" ")[0], statements, rollback));
        }
        return out;
    }

    @Test
    void everySqlServerDefaultOfAnAddedColumnIsNamedAndDroppedFirst() throws IOException {
        Set<String> names = new HashSet<>();
        int checked = 0;
        for (ChangeSet cs : changeSets("mssql")) {
            for (String s : cs.statements()) {
                Matcher add = ADD.matcher(s);
                if (!add.matches() || !add.group(3).contains("DEFAULT")) {
                    continue;
                }
                String table = add.group(1);
                String column = add.group(2);
                Matcher df = NAMED_DEFAULT.matcher(add.group(3));
                assertTrue(df.find(), cs.id() + ": unnamed SQL Server default on " + table + "." + column + ": " + s);
                assertTrue(names.add(df.group(1)), "default constraint name used twice: " + df.group(1));
                int dropDefault = cs.rollback().indexOf("ALTER TABLE " + table + " DROP CONSTRAINT " + df.group(1) + ";");
                int dropColumn = cs.rollback().indexOf("ALTER TABLE " + table + " DROP COLUMN " + column + ";");
                assertTrue(dropDefault >= 0 && dropColumn > dropDefault,
                        cs.id() + ": the rollback must drop " + df.group(1) + " before " + column + ": " + cs.rollback());
                checked++;
            }
        }
        // 0017-01..03, 0041-01, 0041-04 (fixed under the ruling), 0043-11, 0044-05..07, 0045-11; a new one raises this.
        assertEquals(10, checked, "the SQL Server added columns with a default");
        for (String id : new String[]{"0017-01-evt_channel_def-alter", "0017-02-evt_event_def-alter",
                "0017-03-port_batch-alter", "0041-01-cmp_breach-alter", "0041-04-cmp_rights_request-alter"}) {
            assertTrue(changeSets("mssql").stream().anyMatch(cs -> cs.id().equals("sangam:" + id)
                    && cs.statements().stream().anyMatch(st -> NAMED_DEFAULT.matcher(st).find())), id);
        }
    }

    @Test
    void everyNamedCheckOfAnAddedColumnIsDroppedFirstAndNoAddedColumnIsInlineUnique() throws IOException {
        for (String dbms : DIALECTS) {
            int checks = 0;
            for (ChangeSet cs : changeSets(dbms)) {
                for (String s : cs.statements()) {
                    Matcher add = ADD.matcher(s);
                    if (!add.matches() || s.contains(" ADD CONSTRAINT ")) {
                        continue;
                    }
                    String table = add.group(1);
                    String column = add.group(2);
                    assertFalse(add.group(3).contains("UNIQUE") || add.group(3).contains("PRIMARY KEY")
                            || add.group(3).contains("REFERENCES"), dbms + " " + cs.id() + ": inline key on an added column");
                    assertEquals(add.group(3).contains("CHECK"), NAMED_CHECK.matcher(add.group(3)).find(),
                            dbms + " " + cs.id() + ": an added column's CHECK is named");
                    Matcher ck = NAMED_CHECK.matcher(add.group(3));
                    if (ck.find()) {
                        String drop = "ALTER TABLE " + table + (dbms.equals("mysql") ? " DROP CHECK " : " DROP CONSTRAINT ")
                                + ck.group(1) + ";";
                        int dropCheck = cs.rollback().indexOf(drop);
                        int dropColumn = cs.rollback().indexOf("ALTER TABLE " + table + " DROP COLUMN " + column + ";");
                        assertTrue(dropCheck >= 0 && dropColumn > dropCheck, dbms + " " + cs.id() + ": " + cs.rollback());
                        checks++;
                    }
                }
            }
            assertEquals(4, checks, dbms + ": the four alters with a CHECK (0043-11, 0044-05..07)");
        }
    }
}
