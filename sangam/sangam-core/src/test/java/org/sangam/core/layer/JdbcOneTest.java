// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link Jdbc#one}: "no row" is an empty result; an existing row whose mapped value is null fails loudly and names the
 * statement (SL-57/SL-97: {@code JdbcLedgerStore.currencyOf} on a row with a NULL currency surfaced as a bare
 * {@code NullPointerException} from {@code Optional.of(null)}, with no hint of the statement).
 */
class JdbcOneTest {

    private Connection c;

    @BeforeEach
    void open() throws SQLException {
        c = DriverManager.getConnection("jdbc:h2:mem:jdbcone_" + UUID.randomUUID() + ";MODE=PostgreSQL");
        Jdbc.update(c, "CREATE TABLE L (id VARCHAR(16) PRIMARY KEY, currency VARCHAR(3), k VARCHAR(8))");
        Jdbc.update(c, "INSERT INTO L(id, currency, k) VALUES (?,?,?)", "with", "INR", "a");
        Jdbc.update(c, "INSERT INTO L(id, currency, k) VALUES (?,?,?)", "gap", null, "a");
    }

    @AfterEach
    void close() throws SQLException {
        c.close();
    }

    @Test
    void aMappedValueIsReturned() {
        assertEquals(Optional.of("INR"), Jdbc.one(c, "SELECT currency FROM L WHERE id = ?", rs -> rs.getString(1), "with"));
    }

    @Test
    void noRowIsAnEmptyResult() {
        assertEquals(Optional.empty(), Jdbc.one(c, "SELECT currency FROM L WHERE id = ?", rs -> rs.getString(1), "none"));
    }

    @Test
    void anExistingRowMappedToNullFailsAndNamesTheStatement() {
        String sql = "SELECT currency FROM L WHERE id = ?";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Jdbc.one(c, sql, rs -> rs.getString(1), "gap"));
        assertTrue(e.getMessage().contains("row exists but mapped value is NULL"), e.getMessage());
        assertTrue(e.getMessage().contains(sql), e.getMessage());
    }

    @Test
    void moreThanOneRowStillFails() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Jdbc.one(c, "SELECT id FROM L WHERE k = ?", rs -> rs.getString(1), "a"));
        assertTrue(e.getMessage().startsWith("expected at most one row"), e.getMessage());
    }
}