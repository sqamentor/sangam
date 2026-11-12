// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;

/**
 * M-005: the LISTEN consumer of the relay's {@code pg_notify}. Without a database: the connection is a recording
 * proxy and notifications come from a queue, so the listener's own logic (LISTEN on connect, wake on notification,
 * reconnect with a wake-up, no reconnect loop for a foreign driver, in-JVM mode for the other dialects) is exercised.
 */
class WakeupListenerTest {

    static final WakeupListener.Settings FAST = new WakeupListener.Settings(Duration.ofMillis(20), Duration.ofMillis(60),
            Duration.ofMillis(500), Duration.ofMillis(10), Duration.ofMillis(40));

    /** Connections that record their SQL; {@code closed} counts closes. */
    static final class FakeDb implements WakeupListener.ConnectionSource {
        final List<String> sql = new CopyOnWriteArrayList<>();
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        volatile boolean failSelect;

        @Override
        public Connection open() {
            opened.incrementAndGet();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, args) -> switch (m.getName()) {
                        case "createStatement" -> statement();
                        case "close" -> {
                            closed.incrementAndGet();
                            yield null;
                        }
                        case "isWrapperFor" -> false;
                        case "toString" -> "fake-connection";
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        default -> null;
                    });
        }

        Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                    (p, m, args) -> {
                        if (m.getName().equals("execute")) {
                            String q = (String) args[0];
                            sql.add(q);
                            if (failSelect && q.equals("SELECT 1")) {
                                throw new SQLException("connection reset");
                            }
                            return false;
                        }
                        return null;
                    });
        }

        long count(String q) {
            return sql.stream().filter(q::equals).count();
        }
    }

    /** Notifications from a queue; a queued exception is thrown once. */
    static final class QueueReader implements WakeupListener.NotificationReader {
        final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();

        @Override
        public int await(Connection c, int timeoutMillis) throws SQLException {
            Object next;
            try {
                next = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
            if (next instanceof SQLException e) {
                throw e;
            }
            return next == null ? 0 : (Integer) next;
        }
    }

    static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(5);
        }
    }

    @Test
    void listensOnItsChannelAndWakesTheSubscribersOnEveryNotification() throws Exception {
        FakeDb db = new FakeDb();
        QueueReader reader = new QueueReader();
        AtomicInteger woken = new AtomicInteger();
        try (WakeupListener l = new WakeupListener(WakeupNotifier.CHANNEL, db, reader, FAST)) {
            l.subscribe(woken::incrementAndGet);
            l.start();
            await(l::listening, "LISTEN established");
            assertEquals(List.of("LISTEN sangam_outbox"), db.sql.stream().filter(s -> s.startsWith("LISTEN")).toList());
            // the connect itself wakes once: notifications sent before LISTEN are lost
            await(() -> woken.get() == 1, "wake-up on connect");
            reader.queue.add(2);
            await(() -> woken.get() == 2, "wake-up on a notification");
            assertEquals(2, l.notifications());
            assertEquals(1, db.opened.get(), "one dedicated connection while it is healthy");
        }
    }

    @Test
    void aBrokenConnectionIsReplacedAndTheReconnectWakesTheSubscribers() throws Exception {
        FakeDb db = new FakeDb();
        QueueReader reader = new QueueReader();
        AtomicInteger woken = new AtomicInteger();
        try (WakeupListener l = new WakeupListener(WakeupNotifier.CHANNEL, db, reader, FAST)) {
            l.subscribe(woken::incrementAndGet);
            l.start();
            await(() -> l.connects() == 1, "first connect");
            reader.queue.add(new SQLException("An I/O error occurred while sending to the backend"));
            await(() -> l.connects() == 2, "reconnect");
            assertTrue(db.closed.get() >= 1, "the broken connection is closed");
            assertEquals(2, db.count("LISTEN sangam_outbox"), "LISTEN again on the new session");
            await(() -> woken.get() >= 2, "wake-up after the reconnect");
        }
    }

    @Test
    void anIdleConnectionIsCheckedAndAFailedCheckReconnects() throws Exception {
        FakeDb db = new FakeDb();
        QueueReader reader = new QueueReader();
        try (WakeupListener l = new WakeupListener(WakeupNotifier.CHANNEL, db, reader, FAST)) {
            l.start();
            await(() -> db.count("SELECT 1") >= 1, "liveness check after the idle time");
            db.failSelect = true;
            await(() -> l.connects() >= 2, "reconnect after a failed liveness check");
        }
    }

    @Test
    void aForeignDriverStopsTheListenerInsteadOfLoopingOnReconnects() throws Exception {
        FakeDb db = new FakeDb();
        WakeupListener.NotificationReader foreign = (c, t) -> {
            throw new SQLFeatureNotSupportedException("the JDBC driver is not pgJDBC");
        };
        try (WakeupListener l = new WakeupListener(WakeupNotifier.CHANNEL, db, foreign, FAST)) {
            l.start();
            await(() -> db.closed.get() == 1, "the connection is closed");
            Thread.sleep(150);
            assertEquals(1, db.opened.get(), "no reconnect loop");
            assertFalse(l.listening());
        }
    }

    @Test
    void thePgJdbcReaderRefusesAConnectionOfAnotherDriver() {
        // the test class path has no pgJDBC: the reader reports it as not supported (the listener then stops)
        Connection c = new FakeDb().open();
        assertThrows(SQLFeatureNotSupportedException.class, () -> WakeupListener.NotificationReader.PGJDBC.await(c, 10));
    }

    @Test
    void otherDialectsWakeInJvmOnlyAndNeverOpenAConnection() {
        FakeDb db = new FakeDb();
        AtomicInteger woken = new AtomicInteger();
        for (Dialect d : Dialect.values()) {
            if (d == Dialect.POSTGRESQL) {
                continue;
            }
            WakeupListener l = WakeupListener.forDialect(d, db, WakeupNotifier.CHANNEL).subscribe(woken::incrementAndGet);
            assertFalse(l.usesListen(), d.name());
            l.start();
            l.inJvmNotifier().cycleCompleted(3);
            l.close();
        }
        assertEquals(0, db.opened.get(), "no LISTEN connection outside PostgreSQL");
        assertEquals(Dialect.values().length - 1, woken.get());
        assertTrue(WakeupListener.forDialect(Dialect.POSTGRESQL, db, WakeupNotifier.CHANNEL).usesListen());
    }

    @Test
    void aFailingSubscriberDoesNotStopTheOthers() {
        AtomicInteger woken = new AtomicInteger();
        WakeupListener l = new WakeupListener(WakeupNotifier.CHANNEL, null, WakeupListener.NotificationReader.PGJDBC, FAST)
                .subscribe(() -> {
                    throw new IllegalStateException("boom");
                })
                .subscribe(woken::incrementAndGet);
        l.wake();
        assertEquals(1, woken.get());
    }

    @Test
    void theChannelMustBeAPlainLowerCaseIdentifier() {
        for (String bad : new String[]{null, "", "Sangam", "a;NOTIFY x", "1abc", "a b"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new WakeupListener(bad, null, WakeupListener.NotificationReader.PGJDBC, FAST), String.valueOf(bad));
        }
    }

    @Test
    void settingsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new WakeupListener.Settings(Duration.ZERO, Duration.ofSeconds(1),
                Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new WakeupListener.Settings(Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new WakeupListener.Settings(Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1)));
        WakeupListener.Settings d = WakeupListener.Settings.defaults();
        assertEquals(Duration.ofSeconds(1), d.notificationWait());
    }
}
