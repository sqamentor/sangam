// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import org.sangam.core.layer.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit checks of the dialect variants (Oracle and SQL Server are not executed in the spike), plus one DB check on
 * the server: every column sangam-core's {@link Schema} defines (the columns the core classes read and write) exists in
 * the Liquibase-created product table of the same name.
 */
class DialectSchemaIT extends LayerSpecSupport {

    @Test
    void coreSchemaColumnsExistInTheLiquibaseTables() throws Exception {
        String probe = ("acc_core_" + RUN).toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> missing = new java.util.ArrayList<>();
        try (java.sql.Connection c = dataSource.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + probe);
            st.execute("SET search_path TO " + probe);
            try {
                for (String ddl : Schema.ddl(Dialect.POSTGRESQL)) {
                    st.execute(ddl);
                }
            } finally {
                st.execute("SET search_path TO public");
            }
            for (String t : Schema.tables()) {
                java.util.List<String> core = Jdbc.query(c, "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = ?", rs -> rs.getString(1), probe, t.toLowerCase(java.util.Locale.ROOT));
                java.util.List<String> lb = Jdbc.query(c, "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ?", rs -> rs.getString(1), t.toLowerCase(java.util.Locale.ROOT));
                assertFalse(core.isEmpty(), "core DDL created " + t);
                for (String col : core) {
                    if (!lb.contains(col)) {
                        missing.add(t + "." + col);
                    }
                }
            }
            st.execute("DROP SCHEMA " + probe + " CASCADE");
        }
        assertTrue(missing.isEmpty(), "core columns missing from the Liquibase schema: " + missing);
    }

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
        // Oracle (deliberate change in Dialect.skipLockedSelect): an ordered ROWID candidate window of 8 x limit, then
        // ORDER BY .. FOR UPDATE SKIP LOCKED inside it; the limit marker makes Jdbc.query stop fetching (= locking) at 5.
        assertEquals("SELECT /*sangam:limit=5*/ d.id FROM T d WHERE d.ROWID IN (SELECT sgm_rid FROM (SELECT d.ROWID AS"
                        + " sgm_rid FROM T d WHERE (d.x=1) ORDER BY d.id) WHERE ROWNUM <= 40) ORDER BY d.id FOR UPDATE SKIP LOCKED",
                Dialect.ORACLE.skipLockedSelect("d.id", "T", "d", "d.x=1", "d.id", 5));
        assertTrue(Dialect.SQLSERVER.skipLockedSelect("d.id", "T", "d", "d.x=1", "d.id", 5)
                .contains("SELECT TOP (5) d.id FROM T d WITH (UPDLOCK, READPAST, ROWLOCK)"));
        assertEquals("BITAND(a, b)", Dialect.ORACLE.bitAnd("a", "b"));
        assertTrue(Dialect.MYSQL.skipLockedSelect("d.id", "T", "d", "d.x=1", "d.id", 5)
                .endsWith("ORDER BY d.id LIMIT 5 FOR UPDATE SKIP LOCKED"));
        assertEquals("(a & b)", Dialect.MYSQL.bitAnd("a", "b"));
        assertTrue(Schema.ddl(Dialect.MYSQL).stream().filter(s -> s.startsWith("CREATE TABLE"))
                .allMatch(s -> s.endsWith("COLLATE=utf8mb4_bin")));
    }

    /**
     * Behaviour behind the skip-locked SQL (on the suite's PostgreSQL): two concurrent claimers get exclusive batches,
     * each in priority order, the second the next rows; and Jdbc.query honours the limit marker the Oracle variant uses
     * to stop fetching (and so locking) at the limit.
     */
    @Test
    void skipLockedClaimsAreExclusiveAndOrdered() throws Exception {
        String t = ("acc_skip_" + RUN).toLowerCase(java.util.Locale.ROOT);
        run(c -> {
            Jdbc.update(c, "CREATE TABLE " + t + " (id BIGINT PRIMARY KEY, x INT NOT NULL)");
            for (long id : new long[]{5, 3, 1, 4, 2, 6}) {
                Jdbc.update(c, "INSERT INTO " + t + "(id, x) VALUES (?, 1)", id);
            }
        });
        try (java.sql.Connection first = openTx(); java.sql.Connection second = openTx()) {
            String sql = Dialect.POSTGRESQL.skipLockedSelect("d.id", t, "d", "d.x=1", "d.id", 2);
            java.util.List<Long> a = Jdbc.query(first, sql, rs -> rs.getLong(1));
            java.util.List<Long> b = Jdbc.query(second, sql, rs -> rs.getLong(1));
            assertEquals(java.util.List.of(1L, 2L), a, "first claimer: the first rows in order");
            assertEquals(java.util.List.of(3L, 4L), b, "second claimer: the next rows, none of the first's");
            java.util.List<Long> marked = Jdbc.query(second, "SELECT /*sangam:limit=1*/ d.id FROM " + t
                    + " d WHERE d.id > 4 ORDER BY d.id", rs -> rs.getLong(1));
            assertEquals(java.util.List.of(5L), marked, "the limit marker stops the fetch at the limit");
            first.rollback();
            second.rollback();
        } finally {
            run(c -> Jdbc.update(c, "DROP TABLE " + t));
        }
    }

    @Test
    void canonicalJsonSortsKeysAndRejectsFloats() {
        assertEquals("{\"a\":[1,\"x\"],\"b\":{\"c\":null,\"d\":true}}",
                CanonicalJson.write(Map.of("b", new java.util.HashMap<>(java.util.Map.of("d", true)) {{ put("c", null); }}, "a", java.util.List.of(1, "x"))));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Map.of("x", 1.5)));
    }
}
