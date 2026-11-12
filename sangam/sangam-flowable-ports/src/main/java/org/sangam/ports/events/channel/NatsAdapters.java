// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NATS JetStream event channels (M-064; optional dependency {@code io.nats:jnats}, Apache-2.0). Connection settings
 * come from {@link NatsConnections}.
 *
 * <ul>
 *   <li><b>Outbound:</b> a JetStream publish to {@code destination} (or {@code topics[0]}) that waits for the
 *       stream's acknowledgement, so the relay marks the delivery done only once the stream stored it. The message id
 *       is the {@code Nats-Msg-Id} (JetStream de-duplicates retries within the stream's window). Headers
 *       {@code message-id}, {@code event-key}, {@code tenant-id}, {@code Content-Type}; CloudEvents binary mode adds
 *       {@code ce-<attr>} headers (CloudEvents NATS binding, NATS 2.2+).</li>
 *   <li><b>Inbound:</b> a pull consumer bound to an existing durable ({@code properties.stream},
 *       {@code properties.consumer}; created by the operator, so the channel never changes the broker's
 *       configuration). Each fetched message goes to {@link InboundEventProcessor#deliver}: acknowledged when it was
 *       stored, a duplicate, rejected or parked (SL-142); otherwise {@code nak} with {@code redeliverBackoffMs} delay.
 *       Message id: {@code Nats-Msg-Id}, else {@code message-id}, else the CloudEvents id.</li>
 * </ul>
 */
final class NatsAdapters {

    private static final Logger LOG = LoggerFactory.getLogger(NatsAdapters.class);

    private NatsAdapters() {
    }

    /** The family's entry, loaded by class name through {@code ChannelAdapters.vendorFamily} only when jnats is present. */
    static final class Family implements ChannelAdapters.Provider {
        Family() {
        }

        @Override
        public java.util.Set<String> types() {
            return java.util.Set.of("nats");
        }

        @Override
        public ChannelAdapters.OutboundChannelAdapter outbound(ChannelDefinition ch, ChannelAdapters.Environment env) {
            return new Outbound(ch, env);
        }

        @Override
        public ChannelAdapters.InboundChannelAdapter inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor,
                                                            ChannelAdapters.Environment env) {
            return new Inbound(ch, tenant, processor, env);
        }
    }

    /** One received message (production: a jnats {@link Message}; tests pass their own). */
    interface Received {
        String subject();

        Map<String, String> headers();

        byte[] data();

        void ack();

        void nakWithDelay(Duration delay);
    }

    /** The JetStream operations the adapters use (production: {@link JetStreamLink}). */
    interface Link extends AutoCloseable {
        /** Publishes and waits for the stream's acknowledgement. */
        void publish(String subject, Map<String, String> headers, byte[] body, String messageId) throws Exception;

        List<Received> fetch(int batch, Duration wait) throws Exception;

        @Override
        void close();
    }

    @FunctionalInterface
    interface LinkFactory {
        Link open(ChannelDefinition ch, ChannelAdapters.Environment env, boolean inbound) throws Exception;
    }

    /** jnats-backed link: one connection per channel adapter. */
    static final class JetStreamLink implements Link {
        private final Connection connection;
        private final JetStream js;
        private final JetStreamSubscription subscription;

        JetStreamLink(ChannelDefinition ch, ChannelAdapters.Environment env, boolean inbound) throws Exception {
            this.connection = NatsConnections.connect(ch.properties(), env::secret, env.nodeId() + "-" + ch.key());
            try {
                this.js = connection.jetStream();
                this.subscription = inbound
                        ? js.subscribe(null, PullSubscribeOptions.bind(ch.properties().get("stream"), ch.properties().get("consumer")))
                        : null;
            } catch (Exception e) {
                connection.close();
                throw e;
            }
        }

        @Override
        public void publish(String subject, Map<String, String> headers, byte[] body, String messageId) throws Exception {
            Headers h = new Headers();
            headers.forEach(h::add);
            PublishAck ack = js.publish(subject, h, body, PublishOptions.builder().messageId(messageId).build());
            if (ack.hasError()) {
                throw new IllegalStateException("JetStream refused the message: " + ack.getError());
            }
        }

        @Override
        public List<Received> fetch(int batch, Duration wait) {
            List<Received> out = new ArrayList<>();
            for (Message m : subscription.fetch(batch, wait)) {
                out.add(new Received() {
                    @Override
                    public String subject() {
                        return m.getSubject();
                    }

                    @Override
                    public Map<String, String> headers() {
                        Map<String, String> h = new LinkedHashMap<>();
                        if (m.getHeaders() != null) {
                            for (String k : m.getHeaders().keySet()) {
                                h.put(k, m.getHeaders().getFirst(k));
                            }
                        }
                        return h;
                    }

                    @Override
                    public byte[] data() {
                        return m.getData();
                    }

                    @Override
                    public void ack() {
                        m.ack();
                    }

                    @Override
                    public void nakWithDelay(Duration delay) {
                        m.nakWithDelay(delay);
                    }
                });
            }
            return out;
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class Outbound implements ChannelAdapters.OutboundChannelAdapter {
        private final ChannelDefinition ch;
        private final Link link;
        private final String subject;

        Outbound(ChannelDefinition ch, ChannelAdapters.Environment env) {
            this(ch, env, JetStreamLink::new);
        }

        Outbound(ChannelDefinition ch, ChannelAdapters.Environment env, LinkFactory links) {
            this.ch = ch;
            this.subject = ch.destination() != null ? ch.destination() : ch.topics().isEmpty() ? null : ch.topics().get(0);
            if (subject == null) {
                throw new IllegalArgumentException("nats channel " + ch.key() + " needs destination or topics[0]");
            }
            try {
                this.link = links.open(ch, env, false);
            } catch (Exception e) {
                throw new IllegalStateException("nats channel " + ch.key() + " cannot connect", e);
            }
        }

        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            send(ChannelAdapters.Outgoing.json(tenantId, eventKey, messageId, body));
        }

        @Override
        public void send(ChannelAdapters.Outgoing m) throws Exception {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("message-id", m.messageId());
            h.put("event-key", m.eventKey());
            if (m.tenantId() != null) {
                h.put("tenant-id", m.tenantId());
            }
            h.put("Content-Type", m.contentType());
            m.cloudEventsOrEmpty().forEach((k, v) -> h.put("ce-" + k, v));
            link.publish(subject, h, m.body().getBytes(StandardCharsets.UTF_8), m.messageId());
        }

        @Override
        public void close() {
            link.close();
        }
    }

    static final class Inbound extends PollingInbound {
        private final ChannelAdapters.Environment env;
        private final LinkFactory links;
        private Link link;

        Inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, ChannelAdapters.Environment env) {
            this(ch, tenant, processor, env, JetStreamLink::new);
        }

        Inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, ChannelAdapters.Environment env,
                LinkFactory links) {
            super(ch, tenant, processor);
            this.env = env;
            this.links = links;
        }

        @Override
        protected int pollOnce() throws Exception {
            if (link == null) {
                link = links.open(ch, env, true);
            }
            int batch = Integer.parseInt(ch.property("batch", "10").trim());
            Duration wait = Duration.ofMillis(Long.parseLong(ch.property("pollWaitMs", "5000").trim()));
            List<Received> msgs = link.fetch(batch, wait);
            for (Received m : msgs) {
                Map<String, String> h = new LinkedHashMap<>(m.headers());
                h.put("nats-subject", m.subject());
                String id = first(h, "Nats-Msg-Id", "message-id");
                boolean ack;
                try {
                    ack = deliver(new String(m.data(), StandardCharsets.UTF_8), h, id);
                } catch (RuntimeException e) {
                    LOG.warn("nats channel {}: message not stored, redelivered later: {}", ch.key(), e.toString());
                    ack = false;
                }
                if (ack) {
                    m.ack();
                } else {
                    m.nakWithDelay(Duration.ofMillis(ChannelAdapters.redeliverBackoffMs(ch)));
                }
            }
            return msgs.size();
        }

        private static String first(Map<String, String> h, String... names) {
            for (String n : names) {
                for (Map.Entry<String, String> e : h.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(n) && e.getValue() != null) {
                        return e.getValue();
                    }
                }
            }
            return null;
        }

        @Override
        protected void release() {
            if (link != null) {
                link.close();
                link = null;
            }
        }
    }
}
