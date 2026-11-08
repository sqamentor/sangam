// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.dialect;

import org.sangam.core.layer.Dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * The SQL variants the security layer needs (A-15: every statement is per dialect). It covers the four Tier-1
 * databases of {@link Dialect} plus H2 (development only, ARCHITECTURE.md §1.5). The core {@link Dialect} has no H2
 * entry and core files are not changed, so this enum carries H2 and maps to the core enum where core components
 * ({@code AuditChain}) are reused; H2 must then run with {@code MODE=PostgreSQL}.
 */
public enum SecurityDialect {
    POSTGRESQL, ORACLE, SQLSERVER, MYSQL, H2;

    public static SecurityDialect of(Dialect d) {
        return switch (d) {
            case POSTGRESQL -> POSTGRESQL;
            case ORACLE -> ORACLE;
            case SQLSERVER -> SQLSERVER;
            case MYSQL -> MYSQL;
        };
    }

    /** Detects the dialect from the JDBC metadata. MariaDB is treated as MySQL (LIC-4: the MariaDB driver is shipped). */
    public static SecurityDialect detect(Connection c) {
        try {
            String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (product.contains("postgres")) {
                return POSTGRESQL;
            }
            if (product.contains("oracle")) {
                return ORACLE;
            }
            if (product.contains("microsoft") || product.contains("sql server")) {
                return SQLSERVER;
            }
            if (product.contains("mysql") || product.contains("mariadb")) {
                return MYSQL;
            }
            if (product.contains("h2")) {
                return H2;
            }
            throw new IllegalStateException("unsupported database: " + product);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read database metadata", e);
        }
    }

    /** The core dialect used for reused core components (H2 runs in PostgreSQL mode). */
    public Dialect core() {
        return switch (this) {
            case POSTGRESQL, H2 -> Dialect.POSTGRESQL;
            case ORACLE -> Dialect.ORACLE;
            case SQLSERVER -> Dialect.SQLSERVER;
            case MYSQL -> Dialect.MYSQL;
        };
    }

    /** Bitwise AND of two integer expressions. Oracle and H2 have {@code BITAND}; the others use {@code &}. */
    public String bitAnd(String a, String b) {
        return switch (this) {
            case ORACLE, H2 -> "BITAND(" + a + ", " + b + ")";
            default -> "(" + a + " & " + b + ")";
        };
    }

    /** Two-argument string concatenation; {@code CONCAT(a, b)} exists on all five databases. */
    public String concat(String a, String b) {
        return "CONCAT(" + a + ", " + b + ")";
    }

    /** Database clock (A-1). */
    public String now() {
        return switch (this) {
            case POSTGRESQL, H2 -> "CURRENT_TIMESTAMP";
            case ORACLE -> "SYSTIMESTAMP";
            case SQLSERVER -> "SYSDATETIMEOFFSET()";
            case MYSQL -> "UTC_TIMESTAMP(6)";
        };
    }

    public String fromDual() {
        return this == ORACLE ? " FROM DUAL" : "";
    }

    /**
     * Appends paging to an ordered select. PostgreSQL, MySQL and H2 use {@code LIMIT/OFFSET}; Oracle (12c+) and SQL
     * Server use {@code OFFSET .. ROWS FETCH NEXT .. ROWS ONLY} (SQL Server requires the {@code ORDER BY}, which every
     * caller supplies). Values are validated integers and inlined, so plans are not split by bind peeking.
     */
    public String page(String orderedSelect, int offset, int limit) {
        if (offset < 0 || limit <= 0) {
            throw new IllegalArgumentException("offset must be >= 0 and limit > 0");
        }
        return switch (this) {
            case POSTGRESQL, MYSQL, H2 -> orderedSelect + " LIMIT " + limit + " OFFSET " + offset;
            case ORACLE, SQLSERVER -> orderedSelect + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        };
    }

    /**
     * A capped count (A-16): counts at most {@code cap + 1} matching rows so the cost is bounded, and the caller reports
     * "more than {@code cap}" when the result exceeds {@code cap}.
     *
     * @param fromWhere {@code FROM <table> r WHERE <predicate>} (no select list)
     */
    public String cappedCount(String fromWhere, int cap) {
        if (cap <= 0) {
            throw new IllegalArgumentException("cap must be positive");
        }
        int n = cap + 1;
        return switch (this) {
            case POSTGRESQL, MYSQL, H2 -> "SELECT COUNT(*) FROM (SELECT 1 AS x " + fromWhere + " LIMIT " + n + ") q";
            case ORACLE -> "SELECT COUNT(*) FROM (SELECT 1 AS x " + fromWhere + " FETCH FIRST " + n + " ROWS ONLY) q";
            case SQLSERVER -> "SELECT COUNT(*) FROM (SELECT TOP (" + n + ") 1 AS x " + fromWhere + ") q";
        };
    }

    /** A blocking row lock by key. */
    public String lockingSelect(String columns, String table, String where) {
        return switch (this) {
            case POSTGRESQL, MYSQL, H2 -> "SELECT " + columns + " FROM " + table + " WHERE " + where + " FOR UPDATE";
            case ORACLE -> "SELECT " + columns + " FROM " + table + " WHERE " + where + " FOR UPDATE WAIT 2";
            case SQLSERVER -> "SELECT " + columns + " FROM " + table + " WITH (UPDLOCK, ROWLOCK) WHERE " + where;
        };
    }

    /** Duplicate-key classification (X.2.2.8). */
    public boolean isUniqueViolation(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s) {
                switch (this) {
                    case POSTGRESQL, H2 -> {
                        if ("23505".equals(s.getSQLState())) {
                            return true;
                        }
                    }
                    case ORACLE -> {
                        if (s.getErrorCode() == 1) {
                            return true;
                        }
                    }
                    case SQLSERVER -> {
                        if (s.getErrorCode() == 2627 || s.getErrorCode() == 2601) {
                            return true;
                        }
                    }
                    case MYSQL -> {
                        if (s.getErrorCode() == 1062 || s.getErrorCode() == 1586) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** PostgreSQL only: {@code ON CONFLICT DO NOTHING} keeps the transaction usable after a duplicate. */
    public boolean supportsOnConflictDoNothing() {
        return this == POSTGRESQL;
    }
}
