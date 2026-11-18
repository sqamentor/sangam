// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import org.sangam.core.layer.*;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit checks of the dialect variants, plus two checks on the acceptance run's database ({@code -Dsangam.db}),
 * each in the {@link Dialect} the server detects on it:
 * <ul>
 *   <li>every column sangam-core's {@link Schema} defines (the columns the core classes read and write) exists in the
 *       Liquibase-created product table of the same name;</li>
 *   <li>the dialect's skip-locked claim gives concurrent claimers exclusive batches in priority order.</li>
 * </ul>
 * Dialect-generic by construction: no schema or database is created (that needs privileges the server account does not
 * have on every database, e.g. MySQL), the probe tables live next to the Liquibase tables in the connection's own
 * catalog/schema under a per-run name prefix, and every catalog lookup goes through JDBC {@link DatabaseMetaData}.
 * It needs none of {@link LayerSpecSupport}'s PostgreSQL-only isolated product tables, so it does not extend it.
 */
class DialectSchemaIT extends ContractSupport {

    /** The two statement forms {@link Schema#ddl} uses; anything else fails the test rather than being skipped. */
    private static final Pattern CREATE_TABLE = Pattern.compile("^CREATE TABLE (\\w+) \\(");
    private static final Pattern CREATE_INDEX = Pattern.compile("^CREATE (UNIQUE )?INDEX (\\w+) ON (\\w+) \\(");

    @Test
    void coreSchemaColumnsExistInTheLiquibaseTables() throws Exception {
        Dialect dialect = dialect();
        String prefix = ("SDS_" + RUN + "_").toUpperCase(Locale.ROOT);
        List<String> missing = new ArrayList<>();
        List<String> created = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(true);
            try {
                for (String ddl : Schema.ddl(dialect)) {
                    Matcher table = CREATE_TABLE.matcher(ddl);
                    st.execute(probeDdl(ddl, prefix));
                    if (table.find()) {
                        created.add(prefix + table.group(1));
                    }
                }
                Map<String, String> stored = tablesInOwnSchema(c);
                for (String t : Schema.tables()) {
                    List<String> core = columns(c, stored.get((prefix + t).toUpperCase(Locale.ROOT)));
                    List<String> lb = columns(c, stored.get(t.toUpperCase(Locale.ROOT)));
                    assertFalse(core.isEmpty(), dialect + ": core DDL created " + t);
                    for (String col : core) {
                        if (!lb.contains(col)) {
                            missing.add(t + "." + col);
                        }
                    }
                }
            } finally {
                for (int i = created.size() - 1; i >= 0; i--) {
                    st.execute("DROP TABLE " + created.get(i));
                }
                c.setAutoCommit(auto);
            }
        }
        assertTrue(missing.isEmpty(), dialect + ": core columns missing from the Liquibase schema: " + missing);
    }

    /**
     * Decision 388: every product table of the changelog exists in the connection's own schema, and an unqualified
     * name (as the product's SQL writes it) resolves to that table. PostgreSQL searches pg_catalog before the
     * search_path, so a product table named like a catalog relation (PG_CONFIG, the view pg_catalog.pg_config) was
     * shadowed there: to_regclass resolves the name exactly as an unqualified statement does, against the live server's
     * catalog (the generator refuses the names it knows; this check also covers relations a newer server adds).
     */
    @Test
    void everyProductTableResolvesToTheOwnSchema() throws Exception {
        Dialect dialect = dialect();
        List<String> absent = new ArrayList<>();
        List<String> shadowed = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            Map<String, String> stored = tablesInOwnSchema(c);
            for (String t : org.sangam.persistence.SangamSchema.PRODUCT_TABLES) {
                if (!stored.containsKey(t.toUpperCase(Locale.ROOT))) {
                    absent.add(t);
                }
            }
            if (dialect == Dialect.POSTGRESQL) {
                try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT n.nspname, current_schema() FROM"
                        + " pg_catalog.pg_class r JOIN pg_catalog.pg_namespace n ON n.oid = r.relnamespace"
                        + " WHERE r.oid = to_regclass(?)")) {
                    for (String t : org.sangam.persistence.SangamSchema.PRODUCT_TABLES) {
                        ps.setString(1, t);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                shadowed.add(t + " -> (unresolved)");
                            } else if (!rs.getString(1).equals(rs.getString(2))) {
                                shadowed.add(t + " -> " + rs.getString(1) + "." + t.toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                }
            }
        }
        assertTrue(absent.isEmpty(), dialect + ": product tables missing from the connection's schema: " + absent);
        assertTrue(shadowed.isEmpty(), dialect + ": unqualified product table names resolve outside the connection's"
                + " schema: " + shadowed);
    }

    /**
     * The core DDL statement with every table and index name prefixed (columns, types and keys untouched). Fails on a
     * statement form it does not know, and on any unprefixed product table name left in the result (e.g. a new
     * {@code REFERENCES}), so the probe can never touch the Liquibase tables.
     */
    private static String probeDdl(String ddl, String prefix) {
        Matcher table = CREATE_TABLE.matcher(ddl);
        Matcher index = CREATE_INDEX.matcher(ddl);
        String out;
        if (table.find()) {
            out = "CREATE TABLE " + prefix + table.group(1) + " (" + ddl.substring(table.end());
        } else if (index.find()) {
            out = "CREATE " + (index.group(1) == null ? "" : index.group(1)) + "INDEX " + prefix + index.group(2)
                    + " ON " + prefix + index.group(3) + " (" + ddl.substring(index.end());
        } else {
            throw new AssertionError("unrecognised core DDL statement: " + ddl);
        }
        for (String t : Schema.tables()) {
            if (Pattern.compile("(?<!\\w)" + t + "(?!\\w)").matcher(out).find()) {
                throw new AssertionError("core DDL references product table " + t + " outside its name: " + ddl);
            }
        }
        return out;
    }

    /** Tables of the connection's own catalog and schema, keyed by upper-cased name, valued by the stored name. */
    private static Map<String, String> tablesInOwnSchema(Connection c) throws SQLException {
        Map<String, String> byUpper = new HashMap<>();
        try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), c.getSchema(), "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                String previous = byUpper.put(name.toUpperCase(Locale.ROOT), name);
                assertTrue(previous == null, "two tables differ only in case: " + previous + ", " + name);
            }
        }
        return byUpper;
    }

    /** Column names (lower-cased; all product identifiers are unquoted) of one table of the connection's schema. */
    private static List<String> columns(Connection c, String storedName) throws SQLException {
        List<String> out = new ArrayList<>();
        if (storedName == null) {
            return out;
        }
        DatabaseMetaData md = c.getMetaData();
        String esc = md.getSearchStringEscape();
        String pattern = esc == null || esc.isEmpty() ? storedName
                : storedName.replace(esc, esc + esc).replace("_", esc + "_").replace("%", esc + "%");
        try (ResultSet rs = md.getColumns(c.getCatalog(), c.getSchema(), pattern, "%")) {
            while (rs.next()) {
                if (storedName.equals(rs.getString("TABLE_NAME"))) {
                    out.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
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
        // The locking query repeats the predicate, so a row changed after the window's snapshot is re-checked on lock.
        assertEquals("SELECT /*sangam:limit=5*/ d.id FROM T d WHERE (d.x=1) AND d.ROWID IN (SELECT sgm_rid FROM (SELECT d.ROWID AS"
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
     * Behaviour behind the skip-locked SQL (on the run's database, in its dialect): two concurrent claimers get
     * exclusive batches, each in priority order, the second the next rows; and Jdbc.query honours the limit marker the
     * Oracle variant uses to stop fetching (and so locking) at the limit.
     */
    @Test
    void skipLockedClaimsAreExclusiveAndOrdered() throws Exception {
        Dialect dialect = dialect();
        String t = ("acc_skip_" + RUN).toLowerCase(Locale.ROOT);
        inTx(c -> {
            Jdbc.update(c, dialect.expand("CREATE TABLE " + t + " (id {long} PRIMARY KEY, x {int} NOT NULL)")
                    + dialect.createTableSuffix());
            return null;
        });
        try {
            inTx(c -> {
                for (long id : new long[]{5, 3, 1, 4, 2, 6}) {
                    Jdbc.update(c, "INSERT INTO " + t + "(id, x) VALUES (?, 1)", id);
                }
                return null;
            });
            try (Connection first = openTx(); Connection second = openTx()) {
                String sql = dialect.skipLockedSelect("d.id", t, "d", "d.x=1", "d.id", 2);
                List<Long> a = Jdbc.query(first, sql, rs -> rs.getLong(1));
                List<Long> b = Jdbc.query(second, sql, rs -> rs.getLong(1));
                assertEquals(List.of(1L, 2L), a, dialect + ": first claimer: the first rows in order");
                assertEquals(List.of(3L, 4L), b, dialect + ": second claimer: the next rows, none of the first's");
                List<Long> marked = Jdbc.query(second, "SELECT /*sangam:limit=1*/ d.id FROM " + t
                        + " d WHERE d.id > 4 ORDER BY d.id", rs -> rs.getLong(1));
                assertEquals(List.of(5L), marked, dialect + ": the limit marker stops the fetch at the limit");
                first.rollback();
                second.rollback();
            }
        } finally {
            inTx(c -> {
                Jdbc.update(c, "DROP TABLE " + t);
                return null;
            });
        }
    }

    /** A connection of the server's pool with autocommit off that the test commits or rolls back explicitly. */
    private Connection openTx() throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        return c;
    }

    @Test
    void canonicalJsonSortsKeysAndRejectsFloats() {
        assertEquals("{\"a\":[1,\"x\"],\"b\":{\"c\":null,\"d\":true}}",
                CanonicalJson.write(Map.of("b", new java.util.HashMap<>(java.util.Map.of("d", true)) {{ put("c", null); }}, "a", java.util.List.of(1, "x"))));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Map.of("x", 1.5)));
    }
}
