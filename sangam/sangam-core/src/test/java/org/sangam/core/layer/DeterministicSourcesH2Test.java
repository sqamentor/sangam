// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.api.LeaseTable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-097 / D11: the outbox event id, the relay's backoff jitter and the lease attempt id come from the injected
 * {@link IdSource}, so a test (or a steered host) gets the same values on every run.
 */
class DeterministicSourcesH2Test {

    private static final List<Outbox.Destination> FEED = List.of(Outbox.Destination.ordered("feed"));

    private static JdbcDataSource database() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
        }
        return ds;
    }

    /** A source whose ids come from {@code seed} and whose unitRandom is always {@code unit}. */
    private static IdSource source(long seed, double unit) {
        IdSource ids = IdSource.of(() -> 1_700_000_000_000L, new SplittableRandom(seed));
        return new IdSource() {
            @Override
            public UUID randomUuid() {
                return ids.randomUuid();
            }

            @Override
            public UUID timeOrderedUuid() {
                return ids.timeOrderedUuid();
            }

            @Override
            public double unitRandom() {
                return unit;
            }
        };
    }

    @Test
    void outboxEventIdsComeFromTheInjectedSource() throws Exception {
        JdbcDataSource ds = database();
        Transactions tx = Transactions.of(ds);
        Outbox outbox = new Outbox(Dialect.POSTGRESQL, IdSource.of(() -> 1_700_000_000_000L, new SplittableRandom(5)));
        IdSource expected = IdSource.of(() -> 1_700_000_000_000L, new SplittableRandom(5));
        Outbox.Appended first = tx.inTransaction(c -> outbox.append(c, "t1", "case-1", "E", "{}", FEED));
        Outbox.Appended second = tx.inTransaction(c -> outbox.append(c, "t1", "case-1", "E", "{}", FEED));
        assertEquals(expected.timeOrderedUuid(), first.eventId());
        assertEquals(expected.timeOrderedUuid(), second.eventId());
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT event_id FROM OUTBOX WHERE id = ?")) {
            ps.setLong(1, first.outboxId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(first.eventId().toString(), rs.getString(1), "the stored event_id is the injected one");
            }
        }
    }

    @Test
    void relayBackoffJitterComesFromTheInjectedSource() throws Exception {
        // base = cap = 1 h: the delay after the first failure is floor(unit * (3_600_000 + 1)) ms
        assertEquals(List.of(1, 1), deliveriesAfterAFailure(0.0), "jitter 0: due again at once, delivered on the next cycle");
        assertEquals(List.of(1, 0), deliveriesAfterAFailure(0.999), "jitter ~1: about an hour away, not claimed again");
    }

    /** Claims per cycle for one event whose first publish fails, on a relay with the given jitter. */
    private static List<Integer> deliveriesAfterAFailure(double unit) throws Exception {
        JdbcDataSource ds = database();
        Transactions tx = Transactions.of(ds);
        Outbox outbox = new Outbox(Dialect.POSTGRESQL);
        tx.inTransaction(c -> outbox.append(c, "t1", "case-1", "E", "{}", FEED));
        int[] calls = {0};
        Relay relay = new Relay(Dialect.POSTGRESQL, tx, (d, e) -> {
            if (calls[0]++ == 0) {
                throw new IllegalStateException("destination down");
            }
        }, new Relay.Config("n1", 10, Duration.ofSeconds(30), 10, Duration.ofHours(1), Duration.ofHours(1)), null, null,
                source(1, unit));
        List<Integer> claimed = new ArrayList<>();
        Relay.CycleResult r1 = relay.runOnce();
        assertEquals(1, r1.failed());
        claimed.add(r1.claimed());
        claimed.add(relay.runOnce().claimed());
        return claimed;
    }

    @Test
    void leaseAttemptIdsComeFromTheInjectedSource() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        IdSource expected = IdSource.of(() -> 0L, new SplittableRandom(9));
        IdSource ids = IdSource.of(() -> 0L, new SplittableRandom(9));
        try (Connection c = ds.getConnection()) {
            LeaseTable.createIfMissing(c);
            Instant exp = Instant.ofEpochMilli(1_700_000_060_000L);
            LeaseTable.Lease first = LeaseTable.grant(c, "w1", "worker-a", exp, ids);
            LeaseTable.Lease second = LeaseTable.grant(c, "w1", "worker-b", exp, ids);
            assertEquals(expected.randomUuid().toString(), first.attemptId());
            assertEquals(expected.randomUuid().toString(), second.attemptId());
            assertEquals(1, first.lockVersion());
            assertEquals(2, second.lockVersion());
            assertEquals(second.attemptId(), LeaseTable.lock(c, "w1").attemptId());
        }
    }
}
