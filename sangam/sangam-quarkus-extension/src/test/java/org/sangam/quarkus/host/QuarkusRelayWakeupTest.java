// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.transaction.Status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.assembly.integration.RelayWakeup;
import org.sangam.core.layer.Dialect;

/**
 * Owner decision 316: on the Quarkus host (Agroal pool) the relay wake-up is the polling mode on every dialect,
 * PostgreSQL included; the host never opens a {@code LISTEN} session, and a notification that cannot be sent costs
 * nothing but latency and leaves no transaction behind.
 */
class QuarkusRelayWakeupTest {

    @TempDir
    Path objectStore;

    private JtaFixture jta;
    private QuarkusHost host;

    @BeforeEach
    void start() throws SQLException {
        jta = JtaFixture.start(objectStore);
        host = new QuarkusHost(SangamProperties.of(Map.of()), jta.dataSource, jta.transactionManager,
                new BoundPrincipal());
    }

    @AfterEach
    void stop() {
        jta.close();
    }

    @Test
    void everyDialectGetsThePollingModeAndNoListenSession() {
        for (Dialect d : Dialect.values()) {
            RelayWakeup w = host.relayWakeup(d);
            assertEquals(RelayWakeup.Mode.POLLING, w.mode(), d.name());
            assertFalse(w.listener().usesListen(), d.name());
            w.listener().start();
            assertFalse(w.listener().listening(), d.name());
            assertEquals(0, w.listener().connects(), d.name());
            w.listener().close();
        }
    }

    /**
     * The fixture database is H2, which has no {@code pg_notify}: the notification fails, as on a PostgreSQL that is
     * briefly unavailable. The relay is not failed, this node's subscribers are still woken, and the JTA transaction the
     * notifier began on this thread is ended.
     */
    @Test
    void aNotificationThatCannotBeSentCostsOnlyLatency() throws Exception {
        RelayWakeup w = host.relayWakeup(Dialect.POSTGRESQL);
        AtomicInteger woken = new AtomicInteger();
        w.listener().subscribe(woken::incrementAndGet);
        assertDoesNotThrow(() -> w.notifier().cycleCompleted(2));
        assertEquals(1, woken.get());
        assertEquals(Status.STATUS_NO_TRANSACTION, jta.transactionManager.getStatus());
    }
}
