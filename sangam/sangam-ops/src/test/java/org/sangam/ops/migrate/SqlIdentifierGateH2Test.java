// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.migrate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.ops.flowable.FlowableRows;
import org.sangam.ops.sequence.SequenceAdmin;

/**
 * Names that sangam-ops puts into statement text pass {@code org.sangam.core.layer.SqlIdentifier} first:
 * {@link LegacyHistoryMigrator.Table} (name and key columns), {@link FlowableRows#jdbc} (table and column) and
 * {@link SequenceAdmin.Table}. A refused name never reaches the database: the victim table of each attempt is still
 * there afterwards.
 */
class SqlIdentifierGateH2Test {

    private static final List<String> BAD = List.of("t; DROP TABLE VICTIM", "A B", "x; NOTIFY y", "", "ACT_ID_USER--",
            "ACT_ID_USER'", "\"ACT_ID_USER\"", "X".repeat(129));

    @Test
    void aMigratorTableCannotBeBuiltWithAnUnvalidatedNameOrKeyColumn() {
        for (String bad : BAD) {
            assertThrows(IllegalArgumentException.class, () -> new LegacyHistoryMigrator.Table(bad, "ID_"), bad);
            assertThrows(IllegalArgumentException.class, () -> new LegacyHistoryMigrator.Table("ACT_ID_USER", bad), bad);
            assertThrows(IllegalArgumentException.class,
                    () -> new LegacyHistoryMigrator.Table("ACT_ID_MEMBERSHIP", "USER_ID_", bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new LegacyHistoryMigrator.Table(null, "ID_"));
        assertThrows(IllegalArgumentException.class, () -> new LegacyHistoryMigrator.Table("ACT_ID_USER", (String) null));
        assertThrows(IllegalArgumentException.class, () -> new LegacyHistoryMigrator.Table("act_id_user", "ID_"),
            "lower case stays refused, as before");
        // a key column was not validated before: "ID_ > ? OR 1=1 --" went into the keyset predicate as written
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyHistoryMigrator.Table("ACT_ID_USER", "ID_ > ? OR 1=1 --"));
    }

    @Test
    void aMigratorTableHasOneOrTwoKeyColumns() {
        assertThrows(IllegalArgumentException.class, () -> new LegacyHistoryMigrator.Table("ACT_ID_USER"));
        assertThrows(IllegalArgumentException.class, () -> new LegacyHistoryMigrator.Table("ACT_ID_USER", "A_", "B_", "C_"));
        assertThrows(IllegalArgumentException.class, () -> new LegacyHistoryMigrator.Table("ACT_ID_USER", (String[]) null));
    }

    @Test
    void theValidatedKeyColumnsCannotBeReplacedAfterwards() {
        String[] given = {"USER_ID_", "GROUP_ID_"};
        LegacyHistoryMigrator.Table t = new LegacyHistoryMigrator.Table("ACT_ID_MEMBERSHIP", given);
        given[0] = "USER_ID_ > ? OR 1=1 --";
        t.keyColumns()[1] = "X; DROP TABLE VICTIM";
        assertArrayEquals(new String[]{"USER_ID_", "GROUP_ID_"}, t.keyColumns());
        assertEquals("USER_ID_, GROUP_ID_", t.order());
        assertEquals("(USER_ID_ > ? OR (USER_ID_ = ? AND GROUP_ID_ > ?))", t.after());
    }

    @Test
    void theDefaultTablesAreAccepted() {
        assertEquals(16, LegacyHistoryMigrator.DEFAULT_TABLES.size());
        assertEquals("ACT_ID_MEMBERSHIP", LegacyHistoryMigrator.DEFAULT_TABLES.get(3).name());
    }

    @Test
    void flowableRowsRefuseANameBeforeAnySql() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE VICTIM (ID_ VARCHAR(64))");
            s.execute("CREATE TABLE ACT_RU_TASK (ID_ VARCHAR(64), NAME_ VARCHAR(64))");
            s.execute("INSERT INTO ACT_RU_TASK VALUES ('t1', 'a'), ('t2', 'b')");
        }
        FlowableRows rows = FlowableRows.jdbc(ds);
        for (String bad : BAD) {
            assertThrows(IllegalArgumentException.class, () -> rows.count(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> rows.all(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> rows.where(bad, "ID_", List.of("t1")), bad);
            assertThrows(IllegalArgumentException.class, () -> rows.where("ACT_RU_TASK", bad, List.of("t1")), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> rows.count("act_ru_task"), "lower case stays refused");
        // the real names still read, and nothing above reached the database
        assertEquals(2, rows.count("ACT_RU_TASK"));
        assertEquals(2, rows.all("ACT_RU_TASK").size());
        assertEquals(1, rows.where("ACT_RU_TASK", "ID_", List.of("t2", "x' OR '1'='1")).size());
        assertEquals(0, rows.count("ACT_RU_MISSING"), "a table absent from the source counts as 0");
        assertEquals(0, rows.count("VICTIM"), "the victim table is still there");
    }

    @Test
    void aSequenceTableRefusesAnUnvalidatedIdentifier() {
        for (String bad : BAD) {
            assertThrows(IllegalArgumentException.class, () -> new SequenceAdmin.Table(bad, "T", "S", "P", "V"), bad);
            assertThrows(IllegalArgumentException.class, () -> new SequenceAdmin.Table("SEQ", "T", "S", "P", bad), bad);
        }
        assertEquals("RES_BOOKING_SEQ",
                new SequenceAdmin.Table("RES_BOOKING_SEQ", "tenant_id", "series", "period", "last_no").name());
    }
}
