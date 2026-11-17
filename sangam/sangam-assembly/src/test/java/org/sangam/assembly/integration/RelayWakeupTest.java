// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.integration;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.relay.WakeupListener;
import org.sangam.integration.relay.WakeupNotifier;

/**
 * Owner decision 316: a host that cannot hold a PostgreSQL {@code LISTEN} session asks for the polling wake-up by
 * name. Polling never opens a connection, wakes this JVM's subscribers itself and still notifies the listening nodes;
 * {@code LISTEN} is never chosen, or left, silently.
 */
class RelayWakeupTest {

    /** Counts the product transactions the notifier opens; never runs the work (no database here). */
    private static final class CountingTx implements Transactions {
        final AtomicInteger opened = new AtomicInteger();
        RuntimeException failure;

        @Override
        public <T> T inTransaction(Function<Connection, T> work) {
            opened.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return null;
        }

        @Override
        public <T> T read(ReadWork<T> work) {
            opened.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return null;
        }
    }

    private static final WakeupListener.ConnectionSource NEVER_OPENED = () -> {
        throw new AssertionError("polling must never open a LISTEN connection");
    };

    @Test
    void pollingOnPostgresqlNeverListens() {
        RelayWakeup w = RelayWakeup.polling(Dialect.POSTGRESQL, new CountingTx());
        assertEquals(RelayWakeup.Mode.POLLING, w.mode());
        assertFalse(w.listener().usesListen());
        w.listener().start(); // a no-op without LISTEN: no thread, no connection
        assertFalse(w.listener().listening());
        assertEquals(0, w.listener().connects());
        w.listener().close();
    }

    @Test
    void pollingWakesThisJvmAndStillNotifiesPostgresql() {
        CountingTx tx = new CountingTx();
        RelayWakeup w = RelayWakeup.polling(Dialect.POSTGRESQL, tx);
        AtomicInteger woken = new AtomicInteger();
        w.listener().subscribe(woken::incrementAndGet);
        w.notifier().cycleCompleted(3);
        assertEquals(1, woken.get(), "the relay's own node wakes its subscribers without LISTEN");
        assertEquals(1, tx.opened.get(), "pg_notify is still sent for the nodes that listen");
    }

    @Test
    void aFailedNotifyNeverFailsTheRelayNorLosesTheInJvmWakeUp() {
        CountingTx tx = new CountingTx();
        tx.failure = new IllegalStateException("database unavailable");
        RelayWakeup w = RelayWakeup.polling(Dialect.POSTGRESQL, tx);
        AtomicInteger woken = new AtomicInteger();
        w.listener().subscribe(woken::incrementAndGet);
        assertDoesNotThrow(() -> w.notifier().cycleCompleted(1));
        assertEquals(1, woken.get());
    }

    @Test
    void pollingOnTheOtherDialectsIsInJvmOnly() {
        for (Dialect d : new Dialect[] {Dialect.ORACLE, Dialect.SQLSERVER, Dialect.MYSQL}) {
            CountingTx tx = new CountingTx();
            RelayWakeup w = RelayWakeup.polling(d, tx);
            AtomicInteger woken = new AtomicInteger();
            w.listener().subscribe(woken::incrementAndGet);
            w.notifier().cycleCompleted(1);
            assertEquals(RelayWakeup.Mode.POLLING, w.mode());
            assertEquals(1, woken.get(), d.name());
            assertEquals(0, tx.opened.get(), d + " has no notification to send");
        }
    }

    @Test
    void listenNeedsPostgresqlAndAConnectionSource() {
        CountingTx tx = new CountingTx();
        assertThrows(IllegalArgumentException.class, () -> RelayWakeup.listen(Dialect.POSTGRESQL, tx, null));
        assertThrows(IllegalArgumentException.class, () -> RelayWakeup.listen(Dialect.ORACLE, tx, NEVER_OPENED));
        RelayWakeup w = RelayWakeup.listen(Dialect.POSTGRESQL, tx, NEVER_OPENED); // not started: nothing is opened
        assertEquals(RelayWakeup.Mode.LISTEN, w.mode());
        assertTrue(w.listener().usesListen());
    }

    @Test
    void theModeAlwaysMatchesTheListener() {
        WakeupListener inJvm = WakeupListener.forDialect(Dialect.POSTGRESQL, null, WakeupNotifier.CHANNEL);
        WakeupListener listening = WakeupListener.forDialect(Dialect.POSTGRESQL, NEVER_OPENED, WakeupNotifier.CHANNEL);
        assertThrows(IllegalArgumentException.class,
                () -> new RelayWakeup(RelayWakeup.Mode.LISTEN, inJvm, WakeupNotifier.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new RelayWakeup(RelayWakeup.Mode.POLLING, listening, WakeupNotifier.NONE));
    }

    @Test
    void theDescriptionNamesTheMode() {
        assertTrue(RelayWakeup.polling(Dialect.POSTGRESQL, new CountingTx()).describe().contains("polling"));
        assertTrue(RelayWakeup.listen(Dialect.POSTGRESQL, new CountingTx(), NEVER_OPENED).describe()
                .contains("LISTEN on a dedicated connection"));
    }
}
