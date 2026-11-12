// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.sangam.core.layer.Dialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The receiving side of the relay wake-up (M-005, R1, §4.4/§5.2; FLOWABLE-INTO-OPERATON D-40 "long polling is not
 * cluster-aware"). {@link WakeupNotifier#postgres} sends a payload-free {@code pg_notify} after each relay cycle that
 * published something; this listener runs {@code LISTEN} on every node and calls its subscribers (the worker long-poll,
 * later the task-list push), so a waiting long-poll on <i>any</i> node polls again at once.
 *
 * <ul>
 *   <li><b>A hint only (R1).</b> Subscribers keep their 1 s authoritative poll. A lost notification, a broken
 *       connection or a non-PostgreSQL database costs latency, never work.</li>
 *   <li><b>Dedicated, non-pooled connection.</b> {@code LISTEN} is a session state, and the listener waits on its
 *       connection for as long as the node runs. A pooled connection would be held forever and its session state
 *       would leak to the next borrower, so the connection comes from a {@link ConnectionSource} that opens a
 *       physical connection (the server wiring uses the pool's own driver settings, without the pool).</li>
 *   <li><b>Reconnect.</b> Any failure closes the connection and reconnects with exponential back-off
 *       ({@link Settings#reconnectMin()} .. {@link Settings#reconnectMax()}). Notifications sent while no session
 *       was listening are lost by PostgreSQL's design, so every (re)connect wakes the subscribers once.</li>
 *   <li><b>Liveness.</b> A {@code SELECT 1} runs after {@link Settings#liveness()} without a notification, under a
 *       network timeout ({@link Settings#networkTimeout()}), so a half-open TCP connection is found and replaced.</li>
 *   <li><b>Other dialects.</b> No {@code LISTEN}: the listener is in-JVM only ({@link #wake()},
 *       {@link #inJvmNotifier()}), and a relay on this node can still wake its own waiters.</li>
 * </ul>
 * The driver API ({@code org.postgresql.PGConnection#getNotifications(int)}) is called through
 * {@link NotificationReader#PGJDBC}, because the PostgreSQL driver is a runtime-only dependency of the server; a
 * connection of another driver stops the listener with a warning (poll only).
 */
public final class WakeupListener implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WakeupListener.class);
    /** An unquoted PostgreSQL identifier in lower case (LISTEN folds unquoted names to lower case). */
    private static final Pattern CHANNEL_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /** Opens a new physical (non-pooled) connection. */
    @FunctionalInterface
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    /** Waits up to {@code timeoutMillis} for notifications on {@code c}; returns how many arrived (0 on timeout). */
    @FunctionalInterface
    public interface NotificationReader {
        int await(Connection c, int timeoutMillis) throws SQLException;

        /** pgJDBC's {@code PGConnection.getNotifications(int)}; {@link SQLFeatureNotSupportedException} for other drivers. */
        NotificationReader PGJDBC = new PgJdbcReader();
    }

    /**
     * @param notificationWait longest single wait for a notification (also the stop latency)
     * @param liveness       idle time after which the connection is checked with {@code SELECT 1}
     * @param networkTimeout socket timeout of the listener connection (bounds the liveness check)
     * @param reconnectMin   first back-off after a failure
     * @param reconnectMax   largest back-off
     */
    public record Settings(Duration notificationWait, Duration liveness, Duration networkTimeout, Duration reconnectMin,
                           Duration reconnectMax) {
        public Settings {
            positive(notificationWait, "notificationWait");
            positive(liveness, "liveness");
            positive(networkTimeout, "networkTimeout");
            positive(reconnectMin, "reconnectMin");
            positive(reconnectMax, "reconnectMax");
            if (reconnectMax.compareTo(reconnectMin) < 0) {
                throw new IllegalArgumentException("reconnectMax must not be below reconnectMin");
            }
            if (networkTimeout.compareTo(notificationWait) <= 0) {
                throw new IllegalArgumentException("networkTimeout must exceed notificationWait (the wait runs on the same socket)");
            }
        }

        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(10),
                    Duration.ofSeconds(1), Duration.ofSeconds(30));
        }

        private static void positive(Duration d, String name) {
            if (d == null || d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }

    private final String channel;
    private final ConnectionSource source;
    private final NotificationReader reader;
    private final Settings settings;
    private final List<Runnable> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong notifications = new AtomicLong();
    private final AtomicLong connects = new AtomicLong();
    private volatile boolean listening;
    private volatile Thread thread;
    private volatile Connection current;

    /**
     * @param channel the notification channel (an unquoted lower-case identifier, normally {@link WakeupNotifier#CHANNEL})
     * @param source  opens the dedicated connection; null makes an in-JVM-only listener
     * @param reader  waits for notifications on the connection
     */
    public WakeupListener(String channel, ConnectionSource source, NotificationReader reader, Settings settings) {
        if (channel == null || !CHANNEL_NAME.matcher(channel).matches()) {
            throw new IllegalArgumentException("wake-up channel must be a lower-case identifier: " + channel);
        }
        this.channel = channel;
        this.source = source;
        this.reader = Objects.requireNonNull(reader, "reader");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * The listener of {@code dialect}: {@code LISTEN} on a dedicated connection from {@code source} for PostgreSQL,
     * in-JVM only for every other dialect or without a source.
     */
    public static WakeupListener forDialect(Dialect dialect, ConnectionSource source, String channel) {
        return new WakeupListener(channel, dialect == Dialect.POSTGRESQL ? source : null, NotificationReader.PGJDBC,
                Settings.defaults());
    }

    /** Adds a subscriber; it must return quickly (schedule, never poll inline) and is called from the listener thread. */
    public WakeupListener subscribe(Runnable subscriber) {
        subscribers.add(Objects.requireNonNull(subscriber, "subscriber"));
        return this;
    }

    /** Wakes every subscriber in this JVM (also the in-JVM notifier of the other dialects). Never throws. */
    public void wake() {
        for (Runnable r : subscribers) {
            try {
                r.run();
            } catch (RuntimeException e) {
                LOG.debug("wake-up subscriber failed", e);
            }
        }
    }

    /** A {@link WakeupNotifier} that wakes this JVM's subscribers directly (dialects without {@code LISTEN}). */
    public WakeupNotifier inJvmNotifier() {
        return published -> wake();
    }

    /** True when this listener uses {@code LISTEN} (PostgreSQL with a connection source). */
    public boolean usesListen() {
        return source != null;
    }

    /** True while the {@code LISTEN} session is established. */
    public boolean listening() {
        return listening;
    }

    /** Notifications received since start. */
    public long notifications() {
        return notifications.get();
    }

    /** Successful connects (the first one included). */
    public long connects() {
        return connects.get();
    }

    /** Starts the listener thread (no-op without {@code LISTEN}). Idempotent. */
    public void start() {
        if (source == null || !running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::run, "sangam-wakeup-listener");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void run() {
        long backoff = settings.reconnectMin().toMillis();
        while (running.get()) {
            Connection c = null;
            try {
                c = source.open();
                current = c;
                c.setAutoCommit(true);
                networkTimeout(c);
                try (Statement s = c.createStatement()) {
                    s.execute("LISTEN " + channel);
                }
                listening = true;
                long n = connects.incrementAndGet();
                if (n == 1) {
                    LOG.info("wake-up listener: LISTEN {} on a dedicated connection", channel);
                } else {
                    LOG.info("wake-up listener: LISTEN {} re-established (connect {})", channel, n);
                }
                backoff = settings.reconnectMin().toMillis();
                // notifications sent while no session listened are lost: one wake-up covers them
                wake();
                listen(c);
            } catch (SQLFeatureNotSupportedException e) {
                LOG.warn("wake-up listener stopped: {}; long-polls use their 1 s poll only", e.getMessage());
                running.set(false);
            } catch (SQLException | RuntimeException e) {
                if (running.get()) {
                    LOG.warn("wake-up listener on {} failed ({}); reconnecting in {} ms, the 1 s poll stays authoritative",
                            channel, e.toString(), backoff);
                }
            } finally {
                listening = false;
                current = null;
                closeQuietly(c);
            }
            if (running.get()) {
                if (!pause(backoff)) {
                    return;
                }
                backoff = Math.min(settings.reconnectMax().toMillis(), backoff * 2);
            }
        }
    }

    private void listen(Connection c) throws SQLException {
        int waitMs = (int) Math.min(Integer.MAX_VALUE, settings.notificationWait().toMillis());
        long livenessNanos = settings.liveness().toNanos();
        long lastSeen = System.nanoTime();
        while (running.get()) {
            int n = reader.await(c, waitMs);
            if (n > 0) {
                notifications.addAndGet(n);
                lastSeen = System.nanoTime();
                wake();
            } else if (System.nanoTime() - lastSeen >= livenessNanos) {
                try (Statement s = c.createStatement()) {
                    s.execute("SELECT 1");
                }
                lastSeen = System.nanoTime();
            }
        }
    }

    private void networkTimeout(Connection c) throws SQLException {
        try {
            c.setNetworkTimeout(Runnable::run, (int) Math.min(Integer.MAX_VALUE, settings.networkTimeout().toMillis()));
        } catch (SQLFeatureNotSupportedException | UnsupportedOperationException e) {
            LOG.debug("the driver has no network timeout; the liveness check relies on the socket defaults");
        }
    }

    private static boolean pause(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void closeQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (SQLException | RuntimeException ignored) {
            // the connection is discarded either way
        }
    }

    @Override
    public void close() {
        running.set(false);
        Thread t = thread;
        if (t == null) {
            return;
        }
        try {
            t.join(Math.max(100, 2 * settings.notificationWait().toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            closeQuietly(current);
            t.interrupt();
        }
    }

    /** {@code org.postgresql.PGConnection#getNotifications(int)} through the driver's public extension interface. */
    static final class PgJdbcReader implements NotificationReader {

        static final String PG_CONNECTION = "org.postgresql.PGConnection";

        private volatile Class<?> type;
        private volatile Method getNotifications;

        @Override
        public int await(Connection c, int timeoutMillis) throws SQLException {
            Class<?> pg = type;
            Method m = getNotifications;
            if (pg == null || m == null) {
                try {
                    pg = Class.forName(PG_CONNECTION, false, c.getClass().getClassLoader());
                    m = pg.getMethod("getNotifications", int.class);
                } catch (ReflectiveOperationException | LinkageError e) {
                    throw new SQLFeatureNotSupportedException("the JDBC driver is not pgJDBC (" + PG_CONNECTION
                            + " is not available)", e);
                }
                type = pg;
                getNotifications = m;
            }
            if (!c.isWrapperFor(pg)) {
                throw new SQLFeatureNotSupportedException("the connection is not a pgJDBC connection: " + c.getClass().getName());
            }
            try {
                Object[] received = (Object[]) m.invoke(c.unwrap(pg), timeoutMillis);
                return received == null ? 0 : received.length;
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof SQLException s) {
                    throw s;
                }
                throw new SQLException("getNotifications failed", e.getCause());
            } catch (IllegalAccessException e) {
                throw new SQLException("getNotifications is not accessible", e);
            }
        }
    }
}
