// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SecurityDialect#bitAnd}: the VI.4 permission test {@code bitAnd(x.perms, ?) <> 0} must prepare and evaluate on
 * every dialect with the permission bit as a bind parameter (A-15: every statement is per dialect).
 * <ul>
 *   <li>PostgreSQL, SQL Server and MySQL use {@code &}, Oracle {@code BITAND}: unchanged SQL, run by the acceptance
 *       suites on those databases ({@code E2AuthorizationIT} and every VI.4 list).</li>
 *   <li>H2 (development) runs here, in the PostgreSQL mode the server uses and in H2's regular mode. Before the fix H2
 *       got {@code BITAND(x.perms, ?)}, which H2 cannot prepare ("Unknown data type: ?1": a bare parameter as a
 *       function argument has no type), so every VI.4 list and object check of a non-system identity failed on H2.</li>
 * </ul>
 */
class SecurityDialectTest {

    @Test
    void everyDialectHasItsOwnBitwiseAnd() {
        assertEquals("(g.perms & ?)", SecurityDialect.POSTGRESQL.bitAnd("g.perms", "?"));
        assertEquals("(g.perms & ?)", SecurityDialect.SQLSERVER.bitAnd("g.perms", "?"));
        assertEquals("(g.perms & ?)", SecurityDialect.MYSQL.bitAnd("g.perms", "?"));
        assertEquals("BITAND(g.perms, ?)", SecurityDialect.ORACLE.bitAnd("g.perms", "?"));
        assertEquals("BITAND(CAST(g.perms AS BIGINT), CAST(? AS BIGINT))", SecurityDialect.H2.bitAnd("g.perms", "?"));
    }

    @ParameterizedTest
    @ValueSource(strings = {";MODE=PostgreSQL", ""})
    void h2PreparesAndEvaluatesTheBitTestWithABoundBit(String mode) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:bitand" + System.nanoTime() + mode, "sa", "")) {
            c.createStatement().execute("CREATE TABLE G (id INTEGER NOT NULL, perms BIGINT NOT NULL)");
            c.createStatement().execute("INSERT INTO G VALUES (1, 6), (2, 1), (3, " + ((1L << 40) | 2L) + ")");
            String hasBit = "SELECT id FROM G g WHERE " + SecurityDialect.H2.bitAnd("g.perms", "?") + " <> 0 ORDER BY id";
            assertEquals(List.of(1, 3), ids(c, hasBit, 2L));
            assertEquals(List.of(1), ids(c, hasBit, 4L));
            assertEquals(List.of(), ids(c, hasBit, 8L));
            assertEquals(List.of(3), ids(c, hasBit, 1L << 40), "a bit above 32 bits: BIGINT, not INTEGER");
            assertEquals(List.of(1, 3), ids(c, hasBit, 2), "an Integer bind is typed the same way");
            // either operand may be the parameter
            assertEquals(List.of(2), ids(c, "SELECT id FROM G g WHERE " + SecurityDialect.H2.bitAnd("?", "g.perms")
                    + " <> 0 ORDER BY id", 1L));
        }
    }

    static List<Integer> ids(Connection c, String sql, Object bit) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, bit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        }
        return out;
    }
}
