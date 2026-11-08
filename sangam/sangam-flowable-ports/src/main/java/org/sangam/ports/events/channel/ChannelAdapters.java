// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Channel adapter SPI and factory (FLOWABLE-INTO-OPERATON FC-03). Broker clients are optional dependencies: an adapter type is available
 * only when its client library is on the class path ({@link #available}). Air-gapped default (ARCHITECTURE §1.9):
 * outbound HTTP is allowed only to hosts in {@link Environment#egressHosts()}.
 */
public final class ChannelAdapters {

    private ChannelAdapters() {
    }

    /** Sends one serialized event. Must throw on failure so the relay retries (at-least-once; consumers dedupe by message id). */
    public interface OutboundChannelAdapter extends AutoCloseable {
        void send(String tenantId, String eventKey, String messageId, String body) throws Exception;

        @Override
        default void close() {
        }
    }

    /** A running consumer that feeds {@link InboundEventProcessor}. */
    public interface InboundChannelAdapter extends AutoCloseable {
        void start();

        @Override
        void close();
    }

    /**
     * Deployment-provided resources: secrets (never stored in channel JSON, only {@code secretRef} names), JMS
     * connection factories by name, the egress allow-list and the node id.
     */
    public interface Environment {
        Optional<String> secret(String ref);

        default Optional<Object> jmsConnectionFactory(String name) {
            return Optional.empty();
        }

        Set<String> egressHosts();

        default String nodeId() {
            return "sangam";
        }

        default boolean egressAllowed(URI uri) {
            return uri.getHost() != null && egressHosts().contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
        }

        static Environment of(Map<String, String> secrets, Set<String> egressHosts) {
            return new Environment() {
                @Override
                public Optional<String> secret(String ref) {
                    return Optional.ofNullable(secrets.get(ref));
                }

                @Override
                public Set<String> egressHosts() {
                    return egressHosts;
                }
            };
        }
    }

    public static boolean available(String adapterType) {
        return switch (adapterType) {
            case "http", "webhook" -> true;
            case "kafka" -> present("org.apache.kafka.clients.consumer.KafkaConsumer");
            case "jms" -> present("jakarta.jms.ConnectionFactory");
            case "rabbitmq" -> present("com.rabbitmq.client.ConnectionFactory");
            default -> false;
        };
    }

    public static OutboundChannelAdapter outbound(ChannelDefinition ch, Environment env) {
        require(ch);
        return switch (ch.adapterType()) {
            case "http", "webhook" -> new HttpOutboundAdapter(ch, env);
            case "kafka" -> new KafkaAdapters.Outbound(ch);
            case "jms" -> new JmsAdapters.Outbound(ch, env);
            case "rabbitmq" -> new RabbitAdapters.Outbound(ch, env);
            default -> throw new IllegalArgumentException("no outbound adapter for " + ch.adapterType());
        };
    }

    /** Webhook channels have no consumer: the REST layer calls {@link WebhookInboundEndpoint}. Returns null for them. */
    public static InboundChannelAdapter inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, Environment env) {
        require(ch);
        return switch (ch.adapterType()) {
            case "webhook" -> null;
            case "kafka" -> new KafkaAdapters.Inbound(ch, tenant, processor, env);
            case "jms" -> new JmsAdapters.Inbound(ch, tenant, processor, env);
            case "rabbitmq" -> new RabbitAdapters.Inbound(ch, tenant, processor, env);
            default -> throw new IllegalArgumentException("no inbound adapter for " + ch.adapterType());
        };
    }

    private static void require(ChannelDefinition ch) {
        if (!available(ch.adapterType())) {
            throw new IllegalStateException("channel " + ch.key() + ": adapter '" + ch.adapterType()
                    + "' is not available (add its optional client dependency, see README)");
        }
    }

    private static boolean present(String cls) {
        try {
            Class.forName(cls, false, ChannelAdapters.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
