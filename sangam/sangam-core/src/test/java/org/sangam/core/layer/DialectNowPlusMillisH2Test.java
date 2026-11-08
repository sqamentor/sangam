// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

/**
 * Regression: {@link Dialect#nowPlusMillisParam()} for POSTGRESQL is also what the H2 development database
 * (MODE=PostgreSQL) runs. H2 rejected {@code INTERVAL '1 millisecond'} and an untyped {@code ? * INTERVAL}, so every
 * outbox claim, leader renewal and sweep failed on H2. The expression must parse and add exactly the bound
 * milliseconds.
 */
class DialectNowPlusMillisH2Test {

    @Test
    void addsTheBoundMillisecondsOnH2PostgresMode() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:nowplus;MODE=PostgreSQL", "sa", "");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT CURRENT_TIMESTAMP, " + Dialect.POSTGRESQL.nowPlusMillisParam())) {
            ps.setLong(1, 1500L);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                OffsetDateTime now = rs.getObject(1, OffsetDateTime.class);
                OffsetDateTime later = rs.getObject(2, OffsetDateTime.class);
                assertEquals(Duration.ofMillis(1500), Duration.between(now, later));
            }
        }
    }
}
