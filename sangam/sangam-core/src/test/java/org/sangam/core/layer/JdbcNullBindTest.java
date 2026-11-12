// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * SL-88: every SQL NULL that {@link Jdbc} binds carries the JDBC type its database accepts for the parameter's target.
 *
 * <p>The SQL Server cases reproduce the matrix failure "Implicit conversion from data type varbinary to
 * datetimeoffset is not allowed" (HistoryProjector's {@code UPDATE HRM_PROCESS_INSTANCE SET state = ?, end_time = ?,
 * ...} with a null end time; SecurityBootstrap's SEC_POLICY_CHANGE insert with a null decided_at): mssql-jdbc sends a
 * {@code Types.NULL} null as varbinary, so the binder must use the parameter's declared type, which SQL Server reports
 * through {@link PreparedStatement#getParameterMetaData()}. The driver is simulated by a recording proxy (the real
 * server is covered by the acceptance lane's NullBindDialectIT).
 */
class JdbcNullBindTest {

    /** {@code microsoft.sql.Types.DATETIMEOFFSET}, what mssql-jdbc reports for a DATETIMEOFFSET parameter. */
    private static final int MSSQL_DATETIMEOFFSET = -155;

    private static final String HISTORY_UPDATE = "UPDATE HRM_PROCESS_INSTANCE SET state = ?, end_time = ?,"
            + " business_key = COALESCE(?, business_key), last_seq = last_seq + 1 WHERE id = ?";

    @Test
    void sqlServerNullTakesTheDeclaredParameterType() {
        String sql = HISTORY_UPDATE + " /*" + UUID.randomUUID() + "*/";
        FakeDb db = FakeDb.sqlServer(Types.NVARCHAR, MSSQL_DATETIMEOFFSET, Types.NVARCHAR, Types.NVARCHAR);

        Jdbc.update(db.connection(), sql, "completed", null, null, "pi-1");

        assertEquals(Map.of(2, MSSQL_DATETIMEOFFSET, 3, Types.NVARCHAR), db.nulls,
                "a null end_time must be bound as DATETIMEOFFSET, never as Types.NULL (mssql-jdbc: varbinary)");
    }

    @Test
    void sqlServerBinaryAndTimestampNullsInOneInsert() {
        String sql = "INSERT INTO SEC_POLICY_CHANGE(tenant_id, decided_at, definition) VALUES (?,?,?) /*"
                + UUID.randomUUID() + "*/";
        FakeDb db = FakeDb.sqlServer(Types.NVARCHAR, MSSQL_DATETIMEOFFSET, Types.VARBINARY);

        Jdbc.update(db.connection(), sql, "acc", null, null);

        // no single type fits both: varbinary is refused by DATETIMEOFFSET, nvarchar by VARBINARY
        assertEquals(Map.of(2, MSSQL_DATETIMEOFFSET, 3, Types.VARBINARY), db.nulls);
    }

    @Test
    void sqlServerDescribesEachStatementTextOnce() {
        String sql = HISTORY_UPDATE + " /*" + UUID.randomUUID() + "*/";
        FakeDb db = FakeDb.sqlServer(Types.NVARCHAR, MSSQL_DATETIMEOFFSET, Types.NVARCHAR, Types.NVARCHAR);

        for (int k = 0; k < 3; k++) {
            Jdbc.update(db.connection(), sql, "active", null, "bk", "pi-" + k);
        }
        Jdbc.batch(db.connection(), sql, List.of(new Object[]{"a", null, null, "x"}, new Object[]{"b", null, null, "y"}));

        assertEquals(1, db.describes.get(), "one sp_describe_undeclared_parameters round trip per statement text");
        assertEquals(MSSQL_DATETIMEOFFSET, db.nulls.get(2));
    }

    @Test
    void sqlServerWithoutStatementTextStillDescribes() throws SQLException {
        FakeDb db = FakeDb.sqlServer(MSSQL_DATETIMEOFFSET);
        try (PreparedStatement ps = db.connection().prepareStatement("UPDATE T SET at = ?")) {
            Jdbc.bind(ps, 1, (Object) null);
        }
        assertEquals(Map.of(1, MSSQL_DATETIMEOFFSET), db.nulls);
    }

    @Test
    void sqlServerUndescribableStatementBindsNvarchar() {
        String sql = "SELECT id FROM T WHERE (? IS NULL OR at < ?) /*" + UUID.randomUUID() + "*/";
        FakeDb db = FakeDb.sqlServer();
        db.describeFails = true;

        Jdbc.query(db.connection(), sql, rs -> rs.getString(1), null, null);

        // no column-typed target: SQL Server converts an NVARCHAR null implicitly into every non-binary type
        assertEquals(Map.of(1, Types.NVARCHAR, 2, Types.NVARCHAR), db.nulls);
    }

    @Test
    void sqlServerNonNullValuesKeepTheirSetters() {
        String sql = HISTORY_UPDATE + " /*" + UUID.randomUUID() + "*/";
        FakeDb db = FakeDb.sqlServer(Types.NVARCHAR, MSSQL_DATETIMEOFFSET, Types.NVARCHAR, Types.NVARCHAR);

        Jdbc.update(db.connection(), sql, "completed", Instant.parse("2026-11-11T05:00:00Z"), "bk", "pi-1");

        assertEquals(Map.of(), db.nulls);
        assertEquals(0, db.describes.get(), "no describe round trip for a statement without nulls");
        assertEquals(List.of("setObject", "setTimestamp", "setObject", "setObject"), db.setters);
    }

    @Test
    void oracleNullIsVarchar() {
        FakeDb db = FakeDb.of("Oracle");
        Jdbc.update(db.connection(), "UPDATE T SET a = ?, b = ? WHERE id = ?", null, null, "x");
        assertEquals(Map.of(1, Types.VARCHAR, 2, Types.VARCHAR), db.nulls);
        assertEquals(0, db.describes.get());
    }

    @Test
    void postgresMysqlAndH2NullsStayUntyped() {
        for (String product : List.of("PostgreSQL", "MySQL", "MariaDB", "H2")) {
            FakeDb db = FakeDb.of(product);
            Jdbc.update(db.connection(), "UPDATE T SET n = ?, at = ? WHERE id = ?", null, null, "x");
            // a VARCHAR null into a numeric or timestamp column fails on PostgreSQL: the server types an untyped null
            assertEquals(Map.of(1, Types.NULL, 2, Types.NULL), db.nulls, product);
            assertEquals(0, db.describes.get(), product);
        }
    }

    /** Real driver: null timestamp, binary, number and text through the same helper on H2. */
    @Test
    void h2RoundTripOfTypedColumnsWithNulls() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:sl88_" + UUID.randomUUID() + ";MODE=PostgreSQL")) {
            Jdbc.update(c, "CREATE TABLE P (id VARCHAR(64) PRIMARY KEY, at TIMESTAMP WITH TIME ZONE,"
                    + " b VARBINARY(32), n BIGINT, s VARCHAR(64))");
            Jdbc.update(c, "INSERT INTO P(id, at, b, n, s) VALUES (?,?,?,?,?)", "p1", null, null, null, null);
            Jdbc.update(c, "UPDATE P SET at = ?, s = COALESCE(?, s) WHERE id = ?", null, null, "p1");
            Object[] row = Jdbc.one(c, "SELECT at, b, n, s FROM P WHERE id = ?",
                    rs -> new Object[]{rs.getObject(1), rs.getObject(2), rs.getObject(3), rs.getObject(4)}, "p1").orElseThrow();
            for (Object v : row) {
                assertNull(v);
            }
        }
    }

    /** Recording JDBC proxies: product name, parameter metadata, and every setter call per statement. */
    static final class FakeDb {
        final String product;
        final int[] declared;
        final Map<Integer, Integer> nulls = new LinkedHashMap<>();
        final List<String> setters = new ArrayList<>();
        final AtomicInteger describes = new AtomicInteger();
        boolean describeFails;
        private final String url = "jdbc:fake:" + UUID.randomUUID();

        private FakeDb(String product, int... declared) {
            this.product = product;
            this.declared = declared;
        }

        static FakeDb sqlServer(int... declared) {
            return new FakeDb("Microsoft SQL Server", declared);
        }

        static FakeDb of(String product) {
            return new FakeDb(product);
        }

        Connection connection() {
            DatabaseMetaData md = proxy(DatabaseMetaData.class, (p, m, a) -> switch (m.getName()) {
                case "getDatabaseProductName" -> product;
                case "getURL" -> url;
                default -> throw new UnsupportedOperationException(m.getName());
            });
            Connection[] self = new Connection[1];
            self[0] = proxy(Connection.class, (p, m, a) -> switch (m.getName()) {
                case "getMetaData" -> md;
                case "prepareStatement" -> statement(self[0]);
                default -> throw new UnsupportedOperationException(m.getName());
            });
            return self[0];
        }

        private PreparedStatement statement(Connection c) {
            return proxy(PreparedStatement.class, (p, m, a) -> {
                String name = m.getName();
                switch (name) {
                    case "getConnection":
                        return c;
                    case "getParameterMetaData":
                        describes.incrementAndGet();
                        if (describeFails) {
                            throw new SQLException("The undeclared parameter '@P0' is used more than once");
                        }
                        return proxy(ParameterMetaData.class, (p2, m2, a2) -> switch (m2.getName()) {
                            case "getParameterCount" -> declared.length;
                            case "getParameterType" -> declared[(Integer) a2[0] - 1];
                            default -> throw new UnsupportedOperationException(m2.getName());
                        });
                    case "setNull":
                        nulls.put((Integer) a[0], (Integer) a[1]);
                        setters.add(name);
                        return null;
                    case "setString", "setTimestamp", "setObject", "setBytes", "setInt":
                        setters.add(name);
                        return null;
                    case "executeUpdate":
                        return 1;
                    case "executeBatch":
                        return new int[]{1, 1};
                    case "addBatch", "close", "setMaxRows", "setFetchSize":
                        return null;
                    case "executeQuery":
                        return proxy(java.sql.ResultSet.class, (p2, m2, a2) -> switch (m2.getName()) {
                            case "next" -> false;
                            case "close" -> null;
                            default -> throw new UnsupportedOperationException(m2.getName());
                        });
                    default:
                        throw new UnsupportedOperationException(name);
                }
            });
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, InvocationHandler h) {
            return (T) Proxy.newProxyInstance(JdbcNullBindTest.class.getClassLoader(), new Class<?>[]{type}, h);
        }
    }
}
