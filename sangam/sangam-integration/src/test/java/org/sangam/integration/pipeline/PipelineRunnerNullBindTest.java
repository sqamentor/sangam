// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * SL-107 / SL-88: {@link PipelineRunner#setValue}, the one bind point of the pipeline's JDBC extract parameters and
 * load rows (DELETE by key and INSERT, {@code PipelineRunner} lines with {@code setValue(ps, ...)}). A null goes
 * through {@code Jdbc.setNull} with the type the target database needs, never an untyped {@code setObject(i, null)}
 * (mssql-jdbc sends that as varbinary, refused by DATETIMEOFFSET/DATE/FLOAT columns: "Implicit conversion from data
 * type varbinary to datetimeoffset"); a value is bound as is. No statement text is passed: the external schema may
 * change while the server runs, so the type is described per statement.
 */
class PipelineRunnerNullBindTest {

    /** Records every setter on a statement of a database named {@code product} with declared parameter types. */
    private static PreparedStatement statement(String product, List<String> calls, int... declared) {
        DatabaseMetaData md = proxy(DatabaseMetaData.class, (p, m, a) -> switch (m.getName()) {
            case "getDatabaseProductName" -> product;
            case "getURL" -> "jdbc:test:" + product;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        Connection c = proxy(Connection.class, (p, m, a) -> switch (m.getName()) {
            case "getMetaData" -> md;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        ParameterMetaData pmd = proxy(ParameterMetaData.class, (p, m, a) -> switch (m.getName()) {
            case "getParameterCount" -> declared.length;
            case "getParameterType" -> declared[(Integer) a[0] - 1];
            default -> throw new UnsupportedOperationException(m.getName());
        });
        return proxy(PreparedStatement.class, (p, m, a) -> switch (m.getName()) {
            case "getConnection" -> c;
            case "getParameterMetaData" -> {
                calls.add("describe");
                yield pmd;
            }
            case "setNull" -> {
                calls.add("setNull(" + a[0] + "," + a[1] + ")");
                yield null;
            }
            case "setObject" -> {
                calls.add("setObject(" + a[0] + "," + a[1] + ")");
                yield null;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler h) {
        return (T) Proxy.newProxyInstance(PipelineRunnerNullBindTest.class.getClassLoader(), new Class<?>[]{type}, h);
    }

    @Test
    void aNullIntoASqlServerDateTimeOffsetColumnIsBoundWithTheDeclaredType() throws SQLException {
        List<String> calls = new ArrayList<>();
        PreparedStatement ps = statement("Microsoft SQL Server", calls, Types.NVARCHAR, -155 /* DATETIMEOFFSET */);

        PipelineRunner.setValue(ps, 2, null);

        assertEquals(List.of("describe", "setNull(2,-155)"), calls,
                "typed from the server's parameter metadata, never Types.NULL (varbinary)");
    }

    @Test
    void aNullOnPostgresStaysUntypedAndOnOracleIsVarchar() throws SQLException {
        List<String> pg = new ArrayList<>();
        PipelineRunner.setValue(statement("PostgreSQL", pg, Types.TIMESTAMP_WITH_TIMEZONE), 1, null);
        assertEquals(List.of("setNull(1," + Types.NULL + ")"), pg);

        List<String> ora = new ArrayList<>();
        PipelineRunner.setValue(statement("Oracle", ora, Types.TIMESTAMP_WITH_TIMEZONE), 1, null);
        assertEquals(List.of("setNull(1," + Types.VARCHAR + ")"), ora);
    }

    @Test
    void aValueIsBoundAsIsWithoutDescribingTheStatement() throws SQLException {
        List<String> calls = new ArrayList<>();
        PipelineRunner.setValue(statement("Microsoft SQL Server", calls, Types.NVARCHAR), 1, "R-17");
        assertEquals(List.of("setObject(1,R-17)"), calls);
    }

    @Test
    void eachNullDescribesItsStatementBecauseNothingIsCachedForExternalSchemas() throws SQLException {
        List<String> calls = new ArrayList<>();
        PreparedStatement ps = statement("Microsoft SQL Server", calls, Types.VARBINARY, -155);
        PipelineRunner.setValue(ps, 1, null);
        PipelineRunner.setValue(ps, 2, null);
        assertEquals(List.of("describe", "setNull(1," + Types.VARBINARY + ")", "describe", "setNull(2,-155)"), calls);
    }
}