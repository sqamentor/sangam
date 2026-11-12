// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-097 / D11: {@link AuditChain} takes its event ids and payload salts from an injected {@link IdSource}. The same
 * deterministic source gives the same event id and salt; the chain still verifies; the default constructor keeps the
 * system source (16-byte salts, version 7 ids).
 */
class AuditChainIdSourceH2Test {

    private static final long MS = 0x0192_3456_789AL;

    @Test
    void aDeterministicSourceGivesDeterministicIdsAndSalts() {
        Drawn a = appendOne(IdSource.of(() -> MS, new SplittableRandom(42)));
        Drawn b = appendOne(IdSource.of(() -> MS, new SplittableRandom(42)));
        assertEquals(a.eventId(), b.eventId(), "same source, same event id");
        assertArrayEquals(a.salt(), b.salt(), "same source, same salt");

        IdSource expected = IdSource.of(() -> MS, new SplittableRandom(42));
        assertEquals(expected.timeOrderedUuid().toString(), a.eventId(), "the event id is the source's version 7 UUID");
        UUID saltUuid = expected.randomUuid();
        assertArrayEquals(ByteBuffer.allocate(16).putLong(saltUuid.getMostSignificantBits())
                .putLong(saltUuid.getLeastSignificantBits()).array(), a.salt(), "the salt is the next version 4 UUID's bytes");

        Drawn other = appendOne(IdSource.of(() -> MS, new SplittableRandom(43)));
        assertNotEquals(a.eventId(), other.eventId());
        assertTrue(a.verified() && b.verified() && other.verified(), "each chain verifies");
    }

    @Test
    void theDefaultConstructorUsesTheSystemSource() {
        JdbcDataSource ds = h2();
        AuditChain chain = new AuditChain(Dialect.POSTGRESQL);
        Transactions tx = Transactions.of(ds);
        AuditChain.AuditEvent e1 = tx.inTransaction(c -> chain.append(c, "t", "r", "E", Map.of("n", 1)));
        AuditChain.AuditEvent e2 = tx.inTransaction(c -> chain.append(c, "t", "r", "E", Map.of("n", 2)));
        assertEquals(7, UUID.fromString(e1.eventId()).version());
        assertNotEquals(e1.eventId(), e2.eventId());
        List<byte[]> salts = tx.inTransaction(c -> Jdbc.query(c, "SELECT salt FROM AUDIT_PAYLOAD ORDER BY event_id",
                rs -> rs.getBytes(1)));
        assertEquals(16, salts.get(0).length);
        assertTrue(!java.util.Arrays.equals(salts.get(0), salts.get(1)), "fresh salt per event");
        assertTrue(tx.inTransaction(c -> chain.verify(c, "t", "r")).ok());
    }

    private record Drawn(String eventId, byte[] salt, boolean verified) {
    }

    private static Drawn appendOne(IdSource ids) {
        JdbcDataSource ds = h2();
        Transactions tx = Transactions.of(ds);
        AuditChain chain = new AuditChain(Dialect.POSTGRESQL, ids);
        AuditChain.AuditEvent e = tx.inTransaction(c -> chain.append(c, "t", "root-1", "E", Map.of("k", "v")));
        byte[] salt = tx.inTransaction(c -> Jdbc.one(c, "SELECT salt FROM AUDIT_PAYLOAD WHERE event_id=?",
                rs -> rs.getBytes(1), e.eventId()).orElseThrow());
        boolean ok = tx.inTransaction(c -> chain.verify(c, "t", "root-1")).ok();
        return new Drawn(e.eventId(), salt, ok);
    }

    private static JdbcDataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return ds;
    }
}
