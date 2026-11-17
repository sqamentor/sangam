// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * {@link SqlIdentifier} is the one gate for names that go into statement text (SQL cannot bind identifiers). Every
 * factory refuses what could end or extend a statement, and accepts the names the product really uses.
 */
class SqlIdentifierTest {

    /** Refused by every factory, whatever its case rule. */
    private static final List<String> NEVER = Arrays.asList(
            null, "", " ", "t; DROP TABLE x", "A B", "x; NOTIFY y", "a;NOTIFY x", "t--", "t/*c*/", "a'b", "a\"b",
            "\"quoted\"", "`tick`", "[bracket]", "a,b", "a=b", "a(b)", "a\nb", "a\tb", "a\u0000b", "1abc", "9",
            "t$", "t#", "a-b", "té", "तालिका", "ａｂ", " t", "t ", "a b_c");

    private static final List<Function<String, SqlIdentifier>> FACTORIES = List.of(
            SqlIdentifier::of, SqlIdentifier::upperCase, SqlIdentifier::qualified,
            s -> SqlIdentifier.lowerCase(s, 63));

    @Test
    void everyFactoryRefusesWhatIsNotOnePlainName() {
        for (Function<String, SqlIdentifier> factory : FACTORIES) {
            for (String bad : NEVER) {
                assertThrows(IllegalArgumentException.class, () -> factory.apply(bad), String.valueOf(bad));
            }
        }
    }

    @Test
    void aSimpleIdentifierIsLettersDigitsAndUnderscores() {
        for (String ok : List.of("ACT_HI_PROCINST", "ID_", "tenant_id", "Rest_Document", "_x", "a", "T1", "last_no",
                "RES_BOOKING_SEQ", "PROC_DEF_KEY_")) {
            assertEquals(ok, SqlIdentifier.of(ok).sql());
            assertEquals(ok, SqlIdentifier.of(ok).toString(), "concatenation yields the validated name");
        }
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.of("public.t"), "a dot needs qualified()");
    }

    @Test
    void aTablePrefixIsASchemaAndOrANamePartAndNothingElse() {
        assertEquals("", SqlIdentifier.tablePrefix(null));
        assertEquals("", SqlIdentifier.tablePrefix(""));
        for (String ok : List.of("SANGAM_", "ops.", "ops.SG_", "db.ops.")) {
            assertEquals(ok, SqlIdentifier.tablePrefix(ok));
        }
        for (String bad : List.of("x; DROP TABLE ACT_HI_PROCINST --", "ops..", ".", "a b", "o'", "\"ops\".", "ops.--",
                "1ops.", "a.b.c.d.")) {
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.tablePrefix(bad), bad);
        }
    }

    @Test
    void theLengthIsBounded() {
        String longest = "a".repeat(SqlIdentifier.MAX_LENGTH);
        assertEquals(longest, SqlIdentifier.of(longest).sql());
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.of(longest + "a"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.upperCase("A".repeat(SqlIdentifier.MAX_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.qualified("s." + longest + "a"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlIdentifier.of("x".repeat(100_000)));
        assertTrue(e.getMessage().length() < 200, "the message quotes a bounded part of the refused text");
    }

    @Test
    void upperCaseIsForEngineAndLegacyNames() {
        for (String ok : List.of("ACT_RU_EXECUTION", "ACT_GE_BYTEARRAY", "ID_", "USER_ID_", "GROUP_ID_", "SEQ", "T", "V2")) {
            assertEquals(ok, SqlIdentifier.upperCase(ok).sql());
        }
        for (String bad : List.of("act_ru_task", "Act_Ru_Task", "ID_x")) {
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.upperCase(bad), bad);
        }
    }

    @Test
    void lowerCaseIsForPostgresChannels() {
        assertEquals("sangam_outbox", SqlIdentifier.lowerCase("sangam_outbox", 63).sql());
        assertEquals("a".repeat(63), SqlIdentifier.lowerCase("a".repeat(63), 63).sql());
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.lowerCase("a".repeat(64), 63));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.lowerCase("Sangam", 63));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.lowerCase("SANGAM", 63));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.lowerCase("ok", 0));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.lowerCase("ok", SqlIdentifier.MAX_LENGTH + 1));
    }

    @Test
    void qualifiedIsSimpleNamesJoinedBySingleDots() {
        for (String ok : List.of("t", "public.t", "feeds.daily_load", "db.dbo.T1", "srv.db.dbo.t")) {
            assertEquals(ok, SqlIdentifier.qualified(ok).sql());
        }
        for (String bad : List.of(".", "a.", ".a", "a..b", "a.b.c.d.e", "a. b", "a.1b", "a.b;c", "public.t; DROP TABLE x")) {
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.qualified(bad), bad);
        }
    }

    @Test
    void allOfValidatesEveryNameAndKeepsTheOrder() {
        assertEquals(List.of("a", "B", "c_1"), SqlIdentifier.allOf(List.of("a", "B", "c_1")).stream().map(SqlIdentifier::sql).toList());
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.allOf(List.of("a", "b c")));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.allOf(Arrays.asList("a", null)));
    }

    @Test
    void namesValidatesAndReturnsUnmodifiableText() {
        List<String> names = SqlIdentifier.names(List.of("event_id", "TENANT_ID_"));
        assertEquals(List.of("event_id", "TENANT_ID_"), names);
        assertThrows(UnsupportedOperationException.class, () -> names.add("x; DROP TABLE y"));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.names(List.of("id", "t; DROP TABLE x")));
    }

    /** Statement text built by the type holds validated identifiers and bind marks only. */
    @Test
    void statementTextIsBuiltFromIdentifiersAndBindMarks() {
        assertEquals("SELECT COUNT(*) FROM ACT_HI_PROCINST", SqlIdentifier.countSql(SqlIdentifier.upperCase("ACT_HI_PROCINST")));
        assertEquals("INSERT INTO feeds.rows (a, b, run_id) VALUES (?,?,?)", SqlIdentifier.insertSql(
                SqlIdentifier.qualified("feeds.rows"), SqlIdentifier.allOf(List.of("a", "b", "run_id"))));
        assertEquals("INSERT INTO T (ID_) VALUES (?)", SqlIdentifier.insertSql(SqlIdentifier.of("T"), SqlIdentifier.allOf(List.of("ID_"))));
        assertEquals("INSERT INTO T () VALUES ()", SqlIdentifier.insertSql(SqlIdentifier.of("T"), List.of()),
                "no column: the text as it stands, refused by the database as before");
    }

    @Test
    void equalNamesAreEqual() {
        assertEquals(SqlIdentifier.of("ID_"), SqlIdentifier.upperCase("ID_"));
        assertEquals(SqlIdentifier.of("ID_").hashCode(), SqlIdentifier.upperCase("ID_").hashCode());
        assertNotEquals(SqlIdentifier.of("ID_"), SqlIdentifier.of("id_"));
    }
}
