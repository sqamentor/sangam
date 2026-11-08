// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;

/**
 * IFMS finding (LOW): on plain H2 the product layer's PostgreSQL SQL (ON CONFLICT DO NOTHING) failed with an obscure
 * syntax error. Sangam supports H2 only in MODE=PostgreSQL and now refuses any other mode at engine start-up.
 */
class H2ModeGuardTest {

    @Test
    void refusesPlainH2WithTheFix() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:plainmode", "sa", "")) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> DatabaseGuard.requireH2PostgresMode(c));
            assertTrue(e.getMessage().contains("MODE=PostgreSQL"), e.getMessage());
        }
    }

    @Test
    void acceptsPostgresMode() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:pgmode;MODE=PostgreSQL", "sa", "")) {
            assertDoesNotThrow(() -> DatabaseGuard.requireH2PostgresMode(c));
        }
    }
}
