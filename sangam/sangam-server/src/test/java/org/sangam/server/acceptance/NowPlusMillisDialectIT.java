// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

/**
 * {@link Dialect#nowPlusMillisParam()} on the lane's real database and driver, with the offsets the product binds: a
 * lease of seconds, the 30-day idempotency-key and tombstone retention, a year, and negative look-backs. On SQL Server
 * the expression was {@code DATEADD(millisecond, ?, ...)}, whose increment is an int, so every offset beyond 24.8 days
 * failed with "Arithmetic overflow error converting expression to data type int" (r7-it sqlserver A-contracts:
 * IdempotencyStoreIT, E4IdempotencyIT, TombstonesIT, E7DeleteIT, TenantQuotaIT). The expression is used in a select
 * list, an INSERT ... VALUES, an UPDATE ... SET and a WHERE comparison, as the product does. Plain JDBC; no server.
 *
 * <p>The offset is compared with {@code now()} read by the same statement. The two clock reads of one statement may
 * differ by the statement's own run time, so the check allows one second; every offset tested differs from its
 * neighbours by far more, and the overflow it guards against is an error, not a drift.
 */
class NowPlusMillisDialectIT {

    static final List<Long> OFFSETS = List.of(1_500L, Duration.ofDays(24).toMillis(), Integer.MAX_VALUE + 1L,
            Duration.ofDays(30).toMillis(), Duration.ofDays(365).toMillis(), -Duration.ofDays(30).toMillis(), -1_500L);
    static final Duration CLOCK_READS = Duration.ofSeconds(1);

    private static Dialect dialect() {
        return switch (AcceptanceDatabase.name()) {
            case "postgres" -> Dialect.POSTGRESQL;
            case "oracle" -> Dialect.ORACLE;
            case "sqlserver" -> Dialect.SQLSERVER;
            case "mysql" -> Dialect.MYSQL;
            default -> throw new IllegalStateException(AcceptanceDatabase.name());
        };
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(AcceptanceDatabase.jdbcUrl(), AcceptanceDatabase.username(),
                AcceptanceDatabase.password());
    }

    private static void near(Instant now, Instant at, long offset, String where) {
        Duration error = Duration.between(now.plusMillis(offset), at).abs();
        assertTrue(error.compareTo(CLOCK_READS) <= 0, where + ": now " + now + " + " + offset + " ms gave " + at);
    }

    @Test
    void addsTheBoundMillisecondsInEveryStatementShape() throws SQLException {
        Dialect d = dialect();
        String t = ("NPM_" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase(Locale.ROOT);
        try (Connection c = open()) {
            c.setAutoCommit(true);
            for (long offset : OFFSETS) {
                Instant[] r = Jdbc.one(c, "SELECT " + d.now() + " AS n, " + d.nowPlusMillisParam() + " AS a" + d.fromDual(),
                        rs -> new Instant[]{Jdbc.instant(rs, "n"), Jdbc.instant(rs, "a")}, offset).orElseThrow();
                near(r[0], r[1], offset, "select list");
            }
            Jdbc.update(c, d.expand("CREATE TABLE " + t + " (id {str64} NOT NULL PRIMARY KEY, base {ts}, at {ts})")
                    + d.createTableSuffix());
            try {
                for (int i = 0; i < OFFSETS.size(); i++) {
                    long offset = OFFSETS.get(i);
                    String id = "r" + i;
                    Jdbc.update(c, "INSERT INTO " + t + "(id, base, at) VALUES (?, " + d.now() + ", " + d.nowPlusMillisParam()
                            + ")", id, offset);
                    near(read(c, t, id, "base"), read(c, t, id, "at"), offset, "insert values");
                    Jdbc.update(c, "UPDATE " + t + " SET base = " + d.now() + ", at = " + d.nowPlusMillisParam()
                            + " WHERE id = ?", offset, id);
                    near(read(c, t, id, "base"), read(c, t, id, "at"), offset, "update set");
                    long below = Jdbc.one(c, "SELECT COUNT(*) FROM " + t + " WHERE id = ? AND at <= "
                            + d.nowPlusMillisParam(), rs -> rs.getLong(1), id, offset + CLOCK_READS.toMillis() * 2).orElse(0L);
                    assertTrue(below == 1, "where comparison, offset " + offset);
                }
            } finally {
                Jdbc.update(c, "DROP TABLE " + t);
            }
        }
    }

    private static Instant read(Connection c, String t, String id, String column) {
        return Jdbc.one(c, "SELECT " + column + " FROM " + t + " WHERE id = ?", rs -> Jdbc.instant(rs, column), id)
                .orElseThrow();
    }
}
