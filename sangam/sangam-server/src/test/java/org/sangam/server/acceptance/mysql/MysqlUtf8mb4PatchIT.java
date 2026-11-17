// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sangam.persistence.mysql.MysqlUtf8mb4Patch;
import org.sangam.server.acceptance.AcceptanceDatabase;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * SL-94 regression on the real MySQL 8.4 of the matrix lane: engine patch P-2 on the <b>real Operaton 2.2.0 MySQL
 * schema</b> (the engine's own {@code activiti.mysql.create.*.sql}, utf8mb3, 42 string foreign keys), each test in a
 * fresh database of its own.
 *
 * <p>Red before the fix: P-2 converted one table at a time and MySQL refused the first FK-linked table with error 3780
 * ({@code ACT_FK_BYTEARR_DEPL} incompatible), which stopped the server at start-up (matrix 2026-11-11, 125 of 127
 * tests). The first test pins that MySQL behaviour on the same schema, then runs the patch.
 */
class MysqlUtf8mb4PatchIT {

    /** Operaton's own order (DbSqlSession.dbSchemaCreate). */
    private static final List<String> OPERATON_SCRIPTS = List.of("engine", "history", "identity", "case.engine",
            "case.history", "decision.engine", "decision.history");

    /** Named FK constraints the Operaton 2.2.0 MySQL create scripts declare (27 engine, 5 identity, 9 case, 1 decision). */
    private static final int OPERATON_FKS = 42;

    private static final int ER_FK_INCOMPATIBLE_COLUMNS = 3780;
    private static final int ER_NO_REFERENCED_ROW_2 = 1452;

    @BeforeAll
    static void mysqlLaneOnly() {
        assumeTrue("mysql".equals(AcceptanceDatabase.name()), "P-2 is a MySQL-only engine patch");
    }

    @Test
    void convertsTheOperatonSchemaKeepingEveryForeignKeyAndIsANoOpAfterwards() throws Exception {
        try (Connection c = freshOperatonDatabase()) {
            List<String> fksBefore = foreignKeys(c);
            List<String> indexesBefore = indexes(c);
            // 42 named FK statements in the seven scripts; the earlier 46 counted the four "-- create foreign key
            // constraints on ..." comment lines of activiti.mysql.create.case.engine.sql (r7-it 2026-11-17)
            assertEquals(OPERATON_FKS, declaredForeignKeys(), "the FK statements of the Operaton 2.2.0 MySQL scripts");
            assertEquals(OPERATON_FKS, fksBefore.size(), "Operaton 2.2.0 MySQL schema: every declared FK exists");
            assertEquals("utf8mb3_bin", tableCollation(c, "ACT_GE_BYTEARRAY"));

            // the root cause, as MySQL states it: one table at a time cannot convert an FK-linked pair
            SQLException refused = assertThrows(SQLException.class, () -> exec(c,
                    "alter table ACT_GE_BYTEARRAY convert to character set utf8mb4 collate utf8mb4_bin"));
            assertEquals(ER_FK_INCOMPATIBLE_COLUMNS, refused.getErrorCode(), refused.getMessage());
            assertTrue(refused.getMessage().contains("ACT_FK_BYTEARR_DEPL"), refused.getMessage());
            assertEquals("utf8mb3_bin", tableCollation(c, "ACT_GE_BYTEARRAY"), "atomic DDL: nothing changed");

            exec(c, "insert into ACT_RE_DEPLOYMENT (ID_, NAME_) values ('d-1', 'deployment one')");
            exec(c, "insert into ACT_GE_BYTEARRAY (ID_, REV_, NAME_, DEPLOYMENT_ID_) values ('b-1', 1, 'x.bpmn', 'd-1')");

            MysqlUtf8mb4Patch.Result r = MysqlUtf8mb4Patch.apply(c);

            assertTrue(r.skipped().isEmpty(), "skipped: " + r.skipped());
            assertEquals(new ArrayList<>(new TreeSet<>(actTables(c))), r.converted());
            assertEquals(OPERATON_FKS, r.foreignKeysRecreated().size(), r.foreignKeysRecreated().toString());
            assertEquals(List.of(), nonTargetStringColumns(c), "every ACT_* string column is utf8mb4_bin");
            assertEquals(fksBefore, foreignKeys(c), "every FK re-created with its name and definition");
            assertEquals(indexesBefore, indexes(c), "every index (FK backing indexes included) unchanged");
            assertEquals(0, journalRows(c));

            // the FKs are enforced again, the data survived, and 4-byte characters now fit (D-04)
            SQLException orphan = assertThrows(SQLException.class, () -> exec(c,
                    "insert into ACT_GE_BYTEARRAY (ID_, REV_, NAME_, DEPLOYMENT_ID_) values ('b-2', 1, 'y', 'none')"));
            assertEquals(ER_NO_REFERENCED_ROW_2, orphan.getErrorCode(), orphan.getMessage());
            assertEquals("d-1", single(c, "select DEPLOYMENT_ID_ from ACT_GE_BYTEARRAY where ID_ = 'b-1'"));
            String emoji = "निधि 😀 𠀋";
            try (PreparedStatement ps = c.prepareStatement("insert into ACT_RE_DEPLOYMENT (ID_, NAME_) values (?, ?)")) {
                ps.setString(1, "d-2");
                ps.setString(2, emoji);
                ps.executeUpdate();
            }
            assertEquals(emoji, single(c, "select NAME_ from ACT_RE_DEPLOYMENT where ID_ = 'd-2'"));

            // idempotent: a second run changes nothing
            MysqlUtf8mb4Patch.Result again = MysqlUtf8mb4Patch.apply(c);
            assertEquals(List.of(), again.converted());
            assertEquals(List.of(), again.foreignKeysRecreated());
            assertTrue(again.skipped().isEmpty());
            assertEquals(fksBefore, foreignKeys(c));
        }
    }

    @Test
    void anInterruptedRunNeverLosesAForeignKeyAndTheReCreateValidatesTheRows() throws Exception {
        try (Connection c = freshOperatonDatabase()) {
            List<String> fksBefore = foreignKeys(c);
            exec(c, "insert into ACT_RE_DEPLOYMENT (ID_, NAME_) values ('d-1', 'deployment one')");
            // a run that journaled ACT_FK_BYTEARR_DEPL and dropped it, then died before converting
            MysqlUtf8mb4Patch.ForeignKey fk = MysqlUtf8mb4Patch.characterForeignKeys(c).stream()
                    .filter(f -> f.name().equals("ACT_FK_BYTEARR_DEPL")).findFirst().orElseThrow();
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into ACT_GE_PROPERTY (NAME_, VALUE_, REV_) values (?, ?, 1)")) {
                ps.setString(1, fk.journalName());
                ps.setString(2, fk.journalValue());
                ps.executeUpdate();
            }
            exec(c, "alter table ACT_GE_BYTEARRAY drop foreign key ACT_FK_BYTEARR_DEPL");
            // while the FK was missing, a row that violates it got in
            exec(c, "insert into ACT_GE_BYTEARRAY (ID_, REV_, NAME_, DEPLOYMENT_ID_) values ('b-x', 1, 'x', 'none')");

            // the next run re-creates the journaled FK with the checks on, so MySQL refuses the orphan, and the
            // definition stays journaled instead of being lost
            SQLException refused = assertThrows(SQLException.class, () -> MysqlUtf8mb4Patch.apply(c));
            assertEquals(ER_NO_REFERENCED_ROW_2, refused.getErrorCode(), refused.getMessage());
            assertEquals(1, journalRows(c));
            assertFalse(foreignKeys(c).stream().anyMatch(f -> f.startsWith("ACT_FK_BYTEARR_DEPL|")));

            exec(c, "delete from ACT_GE_BYTEARRAY where ID_ = 'b-x'");
            MysqlUtf8mb4Patch.Result r = MysqlUtf8mb4Patch.apply(c);
            assertTrue(r.foreignKeysRecreated().contains("ACT_FK_BYTEARR_DEPL"), r.toString());
            assertEquals(List.of(), utf8mb3ActTables(c));
            assertEquals(List.of(), nonTargetStringColumns(c));
            assertEquals(fksBefore, foreignKeys(c));
            assertEquals(0, journalRows(c));
        }
    }

    // ---------------------------------------------------------------------------------------------- fixtures

    /** A new database holding exactly what Operaton 2.2.0 creates on MySQL; the connection uses it as default. */
    private static Connection freshOperatonDatabase() throws SQLException, IOException {
        JdbcDatabaseContainer<?> ct = AcceptanceDatabase.container();
        String base = "jdbc:mariadb://" + ct.getHost() + ":" + ct.getMappedPort(3306) + "/";
        String opts = "?permitMysqlScheme&connectionCollation=utf8mb4_bin";
        String db = ("sl94_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)).toLowerCase(Locale.ROOT);
        // Testcontainers' MySQL image: root has the container password; the lane user owns only its own database
        try (Connection root = DriverManager.getConnection(base + opts, "root", AcceptanceDatabase.password())) {
            exec(root, "create database " + db);
        }
        Connection c = DriverManager.getConnection(base + db + opts, "root", AcceptanceDatabase.password());
        for (String part : OPERATON_SCRIPTS) {
            String res = "org/operaton/bpm/engine/db/create/activiti.mysql.create." + part + ".sql";
            for (String stmt : statements(res)) {
                exec(c, stmt);
            }
        }
        return c;
    }

    /** The FK constraints the scripts declare, counted from the executed statements (comments stripped). */
    private static int declaredForeignKeys() throws IOException {
        java.util.regex.Pattern fk = java.util.regex.Pattern.compile("(?is)\\bconstraint\\s+\\w+\\s+foreign\\s+key\\b");
        int n = 0;
        for (String part : OPERATON_SCRIPTS) {
            for (String stmt : statements("org/operaton/bpm/engine/db/create/activiti.mysql.create." + part + ".sql")) {
                n += (int) fk.matcher(stmt).results().count();
            }
        }
        return n;
    }

    private static List<String> statements(String resource) throws IOException {
        String sql;
        try (InputStream in = MysqlUtf8mb4PatchIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("not on the class path: " + resource);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder body = new StringBuilder();
        for (String line : sql.split("\\R")) {
            if (!line.strip().startsWith("--")) {
                body.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String s : body.toString().split(";")) {
            if (!s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
    }

    /** Every FK of the database, independent of the patch's own reader: name|table|cols|ref table|ref cols|rules. */
    private static List<String> foreignKeys(Connection c) throws SQLException {
        return strings(c, "select concat(r.constraint_name, '|', r.table_name, '|', "
                + "group_concat(k.column_name order by k.ordinal_position), '|', r.referenced_table_name, '|', "
                + "group_concat(k.referenced_column_name order by k.ordinal_position), '|', r.update_rule, '|', "
                + "r.delete_rule) from information_schema.referential_constraints r "
                + "join information_schema.key_column_usage k on k.constraint_schema = r.constraint_schema "
                + "and k.constraint_name = r.constraint_name and k.table_name = r.table_name "
                + "where r.constraint_schema = database() "
                + "group by r.constraint_name, r.table_name, r.referenced_table_name, r.update_rule, r.delete_rule "
                + "order by 1");
    }

    private static List<String> indexes(Connection c) throws SQLException {
        return strings(c, "select concat(table_name, '|', index_name, '|', seq_in_index, '|', column_name, '|', "
                + "non_unique, '|', coalesce(sub_part, '')) from information_schema.statistics "
                + "where table_schema = database() order by 1");
    }

    private static List<String> nonTargetStringColumns(Connection c) throws SQLException {
        return strings(c, "select concat(table_name, '.', column_name, ' ', collation_name) from information_schema.columns "
                + "where table_schema = database() and upper(table_name) like 'ACT\\_%' and character_set_name is not null "
                + "and (character_set_name <> 'utf8mb4' or collation_name <> 'utf8mb4_bin') order by 1");
    }

    private static List<String> actTables(Connection c) throws SQLException {
        return strings(c, "select table_name from information_schema.tables where table_schema = database() "
                + "and upper(table_name) like 'ACT\\_%' and table_type = 'BASE TABLE' order by table_name");
    }

    private static List<String> utf8mb3ActTables(Connection c) throws SQLException {
        return MysqlUtf8mb4Patch.utf8mb3Tables(c);
    }

    private static String tableCollation(Connection c, String table) throws SQLException {
        return single(c, "select table_collation from information_schema.tables where table_schema = database() "
                + "and table_name = '" + table + "'");
    }

    private static int journalRows(Connection c) throws SQLException {
        return Integer.parseInt(single(c, "select count(*) from ACT_GE_PROPERTY where NAME_ like '"
                + MysqlUtf8mb4Patch.JOURNAL_PREFIX + "%'"));
    }

    private static String single(Connection c, String sql) throws SQLException {
        List<String> r = strings(c, sql);
        assertEquals(1, r.size(), sql);
        return r.get(0);
    }

    private static List<String> strings(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
