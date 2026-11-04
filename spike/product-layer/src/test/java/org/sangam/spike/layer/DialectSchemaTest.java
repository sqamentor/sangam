package org.sangam.spike.layer;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit checks of the dialect variants (Oracle and SQL Server are not executed in the spike). */
class DialectSchemaTest {

    @Test
    void everyDialectExpandsEveryTypeToken() {
        for (Dialect d : Dialect.values()) {
            for (String ddl : Schema.ddl(d)) {
                assertFalse(ddl.contains("{"), d + ": unexpanded token in " + ddl);
            }
        }
        assertEquals(Schema.tables().size(), Schema.ddl(Dialect.POSTGRESQL).stream().filter(s -> s.startsWith("CREATE TABLE")).count());
    }

    @Test
    void skipLockedVariants() {
        assertTrue(Dialect.POSTGRESQL.skipLockedSelect("d.id", "T", "d", "d.x=1", "d.id", 5)
                .endsWith("ORDER BY d.id LIMIT 5 FOR UPDATE OF d SKIP LOCKED"));
        assertTrue(Dialect.ORACLE.skipLockedSelect("d.id", "T", "d", "d.x=1", "d.id", 5)
                .endsWith("AND ROWNUM <= 5 FOR UPDATE SKIP LOCKED"));
        assertTrue(Dialect.SQLSERVER.skipLockedSelect("d.id", "T", "d", "d.x=1", "d.id", 5)
                .contains("SELECT TOP (5) d.id FROM T d WITH (UPDLOCK, READPAST, ROWLOCK)"));
        assertEquals("BITAND(a, b)", Dialect.ORACLE.bitAnd("a", "b"));
        assertTrue(Dialect.MYSQL.skipLockedSelect("d.id", "T", "d", "d.x=1", "d.id", 5)
                .endsWith("ORDER BY d.id LIMIT 5 FOR UPDATE SKIP LOCKED"));
        assertEquals("(a & b)", Dialect.MYSQL.bitAnd("a", "b"));
        assertTrue(Schema.ddl(Dialect.MYSQL).stream().filter(s -> s.startsWith("CREATE TABLE"))
                .allMatch(s -> s.endsWith("COLLATE=utf8mb4_bin")));
    }

    @Test
    void canonicalJsonSortsKeysAndRejectsFloats() {
        assertEquals("{\"a\":[1,\"x\"],\"b\":{\"c\":null,\"d\":true}}",
                CanonicalJson.write(Map.of("b", new java.util.HashMap<>(java.util.Map.of("d", true)) {{ put("c", null); }}, "a", java.util.List.of(1, "x"))));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Map.of("x", 1.5)));
    }
}
