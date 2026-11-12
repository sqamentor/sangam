// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** SL-88: {@link Jdbc#setNull}, the typed-null primitive for code that binds values itself (pipelines, lease table). */
class JdbcSetNullTest {

    @Test
    void sqlServerUsesTheDeclaredType() throws SQLException {
        JdbcNullBindTest.FakeDb db = JdbcNullBindTest.FakeDb.sqlServer(Types.NVARCHAR, -155);
        try (PreparedStatement ps = db.connection().prepareStatement("INSERT INTO T(a, at) VALUES (?, ?)")) {
            Jdbc.setNull(ps, null, 2);
        }
        assertEquals(Map.of(2, -155), db.nulls);
    }

    @Test
    void otherDatabasesKeepTheirNullType() throws SQLException {
        for (var e : Map.of("Oracle", Types.VARCHAR, "PostgreSQL", Types.NULL, "MariaDB", Types.NULL).entrySet()) {
            JdbcNullBindTest.FakeDb db = JdbcNullBindTest.FakeDb.of(e.getKey());
            try (PreparedStatement ps = db.connection().prepareStatement("INSERT INTO T(at) VALUES (?)")) {
                Jdbc.setNull(ps, "INSERT INTO T(at) VALUES (?)", 1);
            }
            assertEquals(Map.of(1, e.getValue()), db.nulls, e.getKey());
        }
    }
}
