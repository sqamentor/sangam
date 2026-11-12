// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.broker;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.sangam.integration.relay.WakeupListener;
import org.sangam.integration.relay.WakeupNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The relay wake-up over a broker, for databases without {@code LISTEN/NOTIFY} (M-058, P1-012(b), R1, §4.4/§5.2):
 * Oracle, SQL Server and MySQL nodes get the same cluster-wide hint PostgreSQL nodes get from {@code pg_notify}.
 * Two transports, both optional dependencies: NATS core publish/subscribe ({@code io.nats:jnats}, Apache-2.0; the
 * connection comes from the one NATS connection factory, {@code NatsConnections}) and Redis {@code PUBLISH/SUBSCRIBE}
 * ({@code redis.clients:jedis}, MIT).
 *
 * <ul>
 *   <li><b>A hint only.</b> The notification carries no payload (no tenant data on a shared channel). Every node's
 *       {@link WakeupListener} subscribers keep their authoritative poll, so a lost message, a broker outage or a slow
 *       subscriber costs latency, never work. {@link #cycleCompleted} never throws.</li>
 *   <li><b>Receiving.</b> One daemon thread per node subscribes and calls {@link WakeupListener#wake()} for each
 *       notification, and once after every (re)subscribe (messages sent while it was not subscribed are gone, as with
 *       {@code LISTEN}). A failure reconnects with exponential back-off.</li>
 * </ul>
 */
public final class BrokerWakeupBus implements WakeupNotifier, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerWakeupBus.class);

    /** The default subject / channel name (shared by every node of the deployment). */
    public static final String DEFAULT_CHANNEL = WakeupNotifier.CHANNEL;

    /** One broker's publish and subscribe. */
    public interface Transport extends AutoCloseable {
        /** Sends one payload-free notification. */
        void publish() throws Exception;

        /**
         * Subscribes and blocks until {@link #close} or a failure: {@code subscribed} after the subscription is active,
         * {@code message} per notification (both must return quickly).
         */
        void listen(Runnable subscribed, Runnable message) throws Exception;

        @Override
        void close();
    }

    private final String name;
    private final Transport transport;
    private final WakeupListener listener;
    private final Duration reconnectMin;
    private final Duration reconnectMax;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    public BrokerWakeupBus(String name, Transport transport, WakeupListener listener, Duration reconnectMin, Duration reconnectMax) {
        this.name = Objects.requireNonNull(name, "name");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.reconnectMin = reconnectMin == null ? Duration.ofSeconds(1) : reconnectMin;
        this.reconnectMax = reconnectMax == null ? Duration.ofSeconds(30) : reconnectMax;
    }

    /** NATS core subject {@code settings.subject} (default {@value #DEFAULT_CHANNEL}). */
    public static BrokerWakeupBus nats(Map<String, String> settings, Function<String, Optional<String>> secrets,
                                       String nodeId, WakeupListener listener) {
        return new BrokerWakeupBus("nats", new NatsWakeupTransport(settings, secrets, nodeId), listener, null, null);
    }

    /** Redis channel {@code settings.channel} (default {@value #DEFAULT_CHANNEL}). */
    public static BrokerWakeupBus redis(Map<String, String> settings, Function<String, Optional<String>> secrets,
                                        String nodeId, WakeupListener listener) {
        return new BrokerWakeupBus("redis", new RedisWakeupTransport(settings, secrets, nodeId), listener, null, null);
    }

    @Override
    public void cycleCompleted(int published) {
        try {
            transport.publish();
        } catch (Exception e) {
            LOG.debug("{} wake-up not sent (the poll stays authoritative): {}", name, e.toString());
        }
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this::loop, "sangam-wakeup-" + name);
            thread.setDaemon(true);
            thread.start();
        }
    }

    void loop() {
        long backoff = reconnectMin.toMillis();
        while (running.get()) {
            CountDownLatch subscribed = new CountDownLatch(1);
            try {
                transport.listen(() -> {
                    subscribed.countDown();
                    listener.wake();
                }, listener::wake);
                backoff = reconnectMin.toMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                if (subscribed.getCount() == 0) {
                    backoff = reconnectMin.toMillis();   // it was working: start the back-off afresh
                }
                LOG.warn("{} wake-up subscription lost, retrying in {} ms (the poll stays authoritative): {}", name, backoff,
                        e.toString());
            }
            if (!running.get()) {
                return;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(reconnectMax.toMillis(), backoff * 2);
        }
    }

    public boolean running() {
        return running.get();
    }

    @Override
    public void close() {
        running.set(false);
        transport.close();
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
