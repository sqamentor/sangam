// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.broker;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;

import org.sangam.ports.events.channel.NatsConnections;

/**
 * NATS core transport of the wake-up bus (M-058): {@code publish(subject, empty)} and a dispatcher subscription. The
 * connection comes from {@link NatsConnections} (the same settings and credentials by reference as the NATS event
 * channels) and reconnects by itself; a subject outage is covered by the authoritative poll.
 */
final class NatsWakeupTransport implements BrokerWakeupBus.Transport {

    private static final byte[] EMPTY = new byte[0];

    private final Map<String, String> settings;
    private final Function<String, Optional<String>> secrets;
    private final String connectionName;
    private final String subject;
    private volatile Connection connection;
    private volatile CountDownLatch stop = new CountDownLatch(1);

    NatsWakeupTransport(Map<String, String> settings, Function<String, Optional<String>> secrets, String nodeId) {
        this.settings = Map.copyOf(settings);
        this.secrets = secrets;
        this.connectionName = nodeId + "-wakeup";
        String s = settings.get("subject");
        this.subject = s == null || s.isBlank() ? BrokerWakeupBus.DEFAULT_CHANNEL : s.trim();
    }

    private synchronized Connection connection() throws Exception {
        if (connection == null || connection.getStatus() == Connection.Status.CLOSED) {
            connection = NatsConnections.connect(settings, secrets, connectionName);
        }
        return connection;
    }

    @Override
    public void publish() throws Exception {
        connection().publish(subject, EMPTY);
    }

    @Override
    public void listen(Runnable subscribed, Runnable message) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        stop = latch;
        Connection c = connection();
        Dispatcher d = c.createDispatcher(m -> message.run());
        try {
            d.subscribe(subject);
            c.flush(java.time.Duration.ofSeconds(5));
            subscribed.run();
            latch.await();
        } finally {
            c.closeDispatcher(d);
        }
    }

    @Override
    public synchronized void close() {
        stop.countDown();
        Connection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
