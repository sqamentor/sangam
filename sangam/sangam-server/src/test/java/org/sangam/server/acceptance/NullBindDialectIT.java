// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

/**
 * SL-88 on the lane's real database and driver: a SQL NULL bound through {@link Jdbc} reaches every product column
 * type ({@code {ts}}, {@code {blob}}, {@code {bytes32}}, {@code {long}}, {@code {int}}, {@code {small}},
 * {@code {str}}, {@code {text}}), in the statement shapes that failed on SQL Server 2025 ("Implicit conversion from
 * data type varbinary to datetimeoffset is not allowed": HistoryProjector's {@code SET end_time = ?, business_key =
 * COALESCE(?, business_key)} update and SecurityBootstrap's SEC_POLICY_CHANGE insert), as a batch, and through
 * {@link Jdbc#setNull} without statement text. Plain JDBC on {@link AcceptanceDatabase}; no server is started.
 */
class NullBindDialectIT {

    private static Dialect dialect() {
        return switch (AcceptanceDatabase.name()) {
            case "postgres" -> Dialect.POSTGRESQL;
            case "oracle" -> Dialect.ORACLE;
            case "sqlserver" -> Dialect.SQLSERVER;
            case "mysql" -> Dialect.MYSQL;
            default -> throw new IllegalStateException(AcceptanceDatabase.name());
        };
    }

    @Test
    void nullsReachEveryProductColumnType() throws SQLException {
        Dialect d = dialect();
        String t = ("SL88_NB_" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase(Locale.ROOT);
        try (Connection c = DriverManager.getConnection(AcceptanceDatabase.jdbcUrl(), AcceptanceDatabase.username(),
                AcceptanceDatabase.password())) {
            c.setAutoCommit(true);
            Jdbc.update(c, d.expand("CREATE TABLE " + t + " (id {str64} NOT NULL PRIMARY KEY, state {str64}, at {ts},"
                    + " b {blob}, h {bytes32}, n {long}, i {int}, s {small}, k {str}, x {text}, seq {long} NOT NULL)")
                    + d.createTableSuffix());
            try {
                // SEC_POLICY_CHANGE shape: an insert with null timestamp, binary, number and text columns
                Jdbc.update(c, "INSERT INTO " + t + "(id, state, at, b, h, n, i, s, k, x, seq) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        "r1", "active", null, null, null, null, null, null, null, null, 0L);
                assertAllNull(c, t, "r1");

                // HistoryProjector shape: SET state = ?, end_time = ?, business_key = COALESCE(?, business_key)
                Instant end = Instant.parse("2026-11-11T05:00:00.123Z");
                assertEquals(1, Jdbc.update(c, "UPDATE " + t + " SET state = ?, at = ?, k = COALESCE(?, k),"
                        + " seq = seq + 1 WHERE id = ?", "completed", end, null, "r1"));
                assertEquals(end, Jdbc.one(c, "SELECT at FROM " + t + " WHERE id = ?", rs -> Jdbc.instant(rs, "at"), "r1")
                        .orElseThrow());
                assertEquals(1, Jdbc.update(c, "UPDATE " + t + " SET state = ?, at = ?, k = COALESCE(?, k),"
                        + " seq = seq + 1 WHERE id = ?", "active", null, null, "r1"));
                assertAllNull(c, t, "r1");

                // a batch (one described statement, several null rows) and a null in a WHERE comparison
                Jdbc.batch(c, "INSERT INTO " + t + "(id, state, at, b, h, n, i, s, k, x, seq) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        List.of(new Object[]{"r2", null, null, null, null, null, null, null, null, null, 0L},
                                new Object[]{"r3", null, null, new byte[]{1}, null, 7L, null, null, null, null, 0L}));
                assertAllNull(c, t, "r2");
                assertEquals(0, Jdbc.query(c, "SELECT id FROM " + t + " WHERE at = ?", rs -> rs.getString(1),
                        (Object) null).size());

                // Jdbc.setNull without statement text (pipeline writes to external databases)
                String sql = "UPDATE " + t + " SET at = ?, b = ?, n = ? WHERE id = ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    Jdbc.setNull(ps, null, 1);
                    Jdbc.setNull(ps, null, 2);
                    Jdbc.setNull(ps, null, 3);
                    ps.setString(4, "r3");
                    assertEquals(1, ps.executeUpdate());
                }
                assertAllNull(c, t, "r3");
            } finally {
                Jdbc.update(c, "DROP TABLE " + t);
            }
        }
    }

    private static void assertAllNull(Connection c, String t, String id) {
        Object[] row = Jdbc.one(c, "SELECT at, b, h, n, i, s, k, x FROM " + t + " WHERE id = ?", rs -> {
            Object[] r = new Object[8];
            for (int k = 0; k < r.length; k++) {
                r[k] = rs.getObject(k + 1);
            }
            return r;
        }, id).orElseThrow();
        String[] names = {"at", "b", "h", "n", "i", "s", "k", "x"};
        for (int k = 0; k < row.length; k++) {
            assertNull(row[k], id + "." + names[k]);
        }
    }
}
