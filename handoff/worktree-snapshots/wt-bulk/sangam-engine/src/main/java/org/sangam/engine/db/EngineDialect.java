// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Locale;

import org.sangam.core.layer.Dialect;

/**
 * Per-dialect SQL of the engine module (A-15, VI.1.6): the four Tier-1 databases plus H2 for development.
 * Detected from JDBC metadata; nothing here assumes PostgreSQL.
 */
public enum EngineDialect {
    POSTGRESQL, ORACLE, SQLSERVER, MYSQL, H2;

    /** Detects the dialect of {@code c}. */
    public static EngineDialect detect(Connection c) {
        try {
            return fromProductName(c.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read database metadata", e);
        }
    }

    public static EngineDialect fromProductName(String productName) {
        String p = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (p.contains("postgres")) {
            return POSTGRESQL;
        }
        if (p.contains("oracle")) {
            return ORACLE;
        }
        if (p.contains("sql server")) {
            return SQLSERVER;
        }
        if (p.contains("mysql") || p.contains("mariadb")) {
            return MYSQL;
        }
        if (p.startsWith("h2")) {
            return H2;
        }
        throw new IllegalArgumentException("unsupported database: " + productName
                + " (Tier 1: PostgreSQL, Oracle, SQL Server, MySQL 8.4; H2 for development)");
    }

    /**
     * The core product-layer dialect for this database. H2 maps to PostgreSQL: the product layer on H2 needs
     * {@code MODE=PostgreSQL} in the JDBC URL (it uses {@code INSERT .. ON CONFLICT DO NOTHING}); development only.
     */
    public Dialect coreDialect() {
        return switch (this) {
            case POSTGRESQL, H2 -> Dialect.POSTGRESQL;
            case ORACLE -> Dialect.ORACLE;
            case SQLSERVER -> Dialect.SQLSERVER;
            case MYSQL -> Dialect.MYSQL;
        };
    }

    /** A blocking row lock by key (L2, lease rows): VI.1.6 L2 statement column. */
    public String lockingSelect(String columns, String table, String where, Duration lockWait) {
        return switch (this) {
            case POSTGRESQL, MYSQL, H2 -> "SELECT " + columns + " FROM " + table + " WHERE " + where + " FOR UPDATE";
            case ORACLE -> "SELECT " + columns + " FROM " + table + " WHERE " + where + " FOR UPDATE WAIT "
                    + Math.max(1, lockWait.toSeconds());
            case SQLSERVER -> "SELECT " + columns + " FROM " + table + " WITH (UPDLOCK, ROWLOCK) WHERE " + where;
        };
    }

    /**
     * Inserts a row unless its key exists, without aborting the transaction, and returns whether it inserted.
     * PostgreSQL {@code ON CONFLICT DO NOTHING}; MySQL a no-op {@code ON DUPLICATE KEY UPDATE} (a failed duplicate
     * insert takes a SHARED lock there and then deadlocks with the later FOR UPDATE, measured in the spike's RootSeq);
     * Oracle, SQL Server and H2 a plain insert whose duplicate-key error rolls back only the statement.
     *
     * @param noopSetCol a non-key column for MySQL's no-op update
     */
    public boolean insertIfAbsent(Connection c, String table, String[] cols, String noopSetCol, Object... values) {
        String colList = String.join(", ", cols);
        String params = String.join(", ", java.util.Collections.nCopies(cols.length, "?"));
        String insert = "INSERT INTO " + table + " (" + colList + ") VALUES (" + params + ")";
        String sql = switch (this) {
            case POSTGRESQL -> insert + " ON CONFLICT DO NOTHING";
            case MYSQL -> insert + " ON DUPLICATE KEY UPDATE " + noopSetCol + " = " + noopSetCol;
            default -> insert;
        };
        try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            org.sangam.core.layer.Jdbc.bind(ps, 1, values);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                return false;
            }
            throw new org.sangam.core.layer.Jdbc.LayerSqlException(sql, e);
        }
    }

    /** Duplicate-key classification for this dialect. */
    public boolean isUniqueViolation(SQLException e) {
        for (SQLException s = e; s != null; s = s.getNextException()) {
            int code = s.getErrorCode();
            String state = s.getSQLState() == null ? "" : s.getSQLState();
            boolean dup = switch (this) {
                case POSTGRESQL, H2 -> state.equals("23505");
                case ORACLE -> code == 1;
                case SQLSERVER -> code == 2627 || code == 2601;
                case MYSQL -> code == 1062 || code == 1586;
            };
            if (dup) {
                return true;
            }
        }
        return false;
    }

    /**
     * VI.1 rule 6: bounded waits inside the current transaction. PostgreSQL {@code SET LOCAL} (transaction scope);
     * SQL Server, MySQL and H2 set the session value (every Sangam command sets it again, so pooled sessions agree);
     * Oracle uses {@code FOR UPDATE WAIT n} in the lock statements instead.
     */
    public void applyLockTimeouts(Connection c, Duration lockTimeout, Duration statementTimeout) {
        String[] stmts = switch (this) {
            case POSTGRESQL -> new String[]{
                    "SET LOCAL lock_timeout = '" + lockTimeout.toMillis() + "ms'",
                    "SET LOCAL statement_timeout = '" + statementTimeout.toMillis() + "ms'"};
            case SQLSERVER -> new String[]{"SET LOCK_TIMEOUT " + lockTimeout.toMillis()};
            case MYSQL -> new String[]{"SET SESSION innodb_lock_wait_timeout = " + Math.max(1, lockTimeout.toSeconds())};
            case H2 -> new String[]{"SET LOCK_TIMEOUT " + lockTimeout.toMillis()};
            case ORACLE -> new String[0];
        };
        try (Statement s = c.createStatement()) {
            for (String sql : stmts) {
                s.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot set lock timeouts on " + this, e);
        }
    }

    /** Largest string the engine's {@code TEXT_} column takes on this database (FLOWABLE-INTO-OPERATON D-07, FLOWABLE-INTO-OPERATON FC-29). */
    public int engineTextLimitChars() {
        // Oracle ACT_*.TEXT_ is NVARCHAR2(2000); PostgreSQL/MySQL varchar(4000); SQL Server nvarchar(4000); H2 4000.
        return this == ORACLE ? 2000 : 4000;
    }
}
