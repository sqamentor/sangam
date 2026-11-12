// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * M-091 relay heartbeat: {@link OutboxRelayService#lastCycleCompletedAt()} moves only when a cycle completes (claim
 * done); a cycle whose claim fails leaves it where it was, so {@code sangam.relay.last.cycle.age} grows and the alert
 * fires. {@link OutboxRelayService#startedAt()} is set by {@code start()} only.
 */
class OutboxRelayHeartbeatTest {

    static final Dialect D = Dialect.POSTGRESQL;

    String url;
    Connection keepAlive;
    final AtomicBoolean databaseDown = new AtomicBoolean();

    @BeforeEach
    void schema() throws Exception {
        url = "jdbc:h2:mem:heartbeat" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                if (databaseDown.get()) {
                    throw new IllegalStateException("database down");
                }
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    OutboxRelayService relay() {
        return new OutboxRelayService(D, tx(), new PublisherRegistry(), OutboxRelayService.Settings.defaults("node-1"),
                null, null, null, null);
    }

    @Test
    void aCompletedCycleMovesTheHeartbeatAndAFailedClaimDoesNot() throws Exception {
        try (OutboxRelayService relay = relay()) {
            assertTrue(relay.lastCycleCompletedAt().isEmpty(), "no cycle yet");
            assertTrue(relay.startedAt().isEmpty(), "not started");

            Instant before = Instant.now().minusMillis(1);
            assertEquals(0, relay.cycle(), "an empty outbox: nothing claimed, the cycle still completes");
            Instant first = relay.lastCycleCompletedAt().orElseThrow();
            assertFalse(first.isBefore(before));

            Thread.sleep(20);
            databaseDown.set(true);
            assertThrows(RuntimeException.class, relay::cycle, "the claim fails");
            assertEquals(first, relay.lastCycleCompletedAt().orElseThrow(), "a failed cycle is no heartbeat");

            databaseDown.set(false);
            relay.cycle();
            assertTrue(relay.lastCycleCompletedAt().orElseThrow().isAfter(first), "the next completed cycle moves it");
        }
    }

    @Test
    void startRecordsTheStartAndTheLoopBeats() throws Exception {
        try (OutboxRelayService relay = relay()) {
            Instant before = Instant.now().minusMillis(1);
            relay.start();
            assertFalse(relay.startedAt().orElseThrow().isBefore(before));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (relay.lastCycleCompletedAt().isEmpty() && System.nanoTime() < end) {
                Thread.sleep(10);
            }
            assertTrue(relay.lastCycleCompletedAt().isPresent(), "the loop completed a cycle");
        }
    }
}
