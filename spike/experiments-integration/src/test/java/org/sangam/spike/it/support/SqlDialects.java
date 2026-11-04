package org.sangam.spike.it.support;

import org.sangam.spike.layer.Dialect;

/**
 * Test-side SQL that product-layer {@link Dialect} does not (yet) offer: paging, statistics refresh, a plan probe and
 * a catalogue lookup. One switch point per database, keyed on the product-layer {@link Dialect} by NAME, so a MySQL
 * constant added to product-layer later compiles here unchanged (it is already handled). Candidates for product-layer
 * {@code Dialect} (reported, not added here: product-layer is owned elsewhere).
 */
public final class SqlDialects {

    private SqlDialects() {
    }

    private static String n(Dialect d) {
        return d.name();
    }

    /**
     * Paging suffix after an {@code ORDER BY}: PostgreSQL / MySQL {@code LIMIT n OFFSET m}; Oracle 12c+ and SQL Server
     * 2012+ {@code OFFSET m ROWS FETCH NEXT n ROWS ONLY} (both require the ORDER BY, which every caller has).
     */
    public static String page(Dialect d, int offset, int limit) {
        return switch (n(d)) {
            case "ORACLE", "SQLSERVER" -> " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
            default -> " LIMIT " + limit + " OFFSET " + offset;
        };
    }

    /** Refreshes optimizer statistics of one table after a bulk load. */
    public static String analyze(Dialect d, String table) {
        return switch (n(d)) {
            case "ORACLE" -> "BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + table.toUpperCase() + "'); END;";
            case "SQLSERVER" -> "UPDATE STATISTICS " + table;
            case "MYSQL" -> "ANALYZE TABLE " + table;
            default -> "ANALYZE " + table;
        };
    }

    /** {@code EXPLAIN (ANALYZE, BUFFERS)} prefix on PostgreSQL; {@code null} where the plan is not captured. */
    public static String explainAnalyzePrefix(Dialect d) {
        return "POSTGRESQL".equals(n(d)) ? "EXPLAIN (ANALYZE, BUFFERS) " : null;
    }

    /** Table names of the current schema/database matching a LIKE pattern (pattern in upper case). */
    public static String tablesLike(Dialect d, String upperPattern) {
        return switch (n(d)) {
            case "ORACLE" -> "SELECT table_name FROM user_tables WHERE table_name LIKE '" + upperPattern + "' ORDER BY 1";
            case "SQLSERVER" -> "SELECT table_name FROM information_schema.tables WHERE table_catalog = DB_NAME()"
                    + " AND upper(table_name) LIKE '" + upperPattern + "' ORDER BY 1";
            case "MYSQL" -> "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
                    + " AND upper(table_name) LIKE '" + upperPattern + "' ORDER BY 1";
            default -> "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()"
                    + " AND upper(table_name) LIKE '" + upperPattern + "' ORDER BY 1";
        };
    }
}
