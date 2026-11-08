// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ channel adapters (optional dependency {@code com.rabbitmq:amqp-client}, Apache-2.0/MPL-2.0/GPL-2.0
 * triple licence; used under Apache-2.0). Connection URI from {@code properties.uriRef} (a secret reference, as it
 * carries credentials). Inbound: manual ack after the inbox write, nack with requeue otherwise; prefetch from
 * {@code properties.prefetch}. Outbound: publisher confirms, persistent messages.
 */
final class RabbitAdapters {

    private static final Logger LOG = LoggerFactory.getLogger(RabbitAdapters.class);

    private RabbitAdapters() {
    }

    /** Opens the broker connection of a channel (production: {@link #connect}; tests pass an in-memory stub). */
    @FunctionalInterface
    interface Connector {
        Connection connect(ChannelDefinition ch, ChannelAdapters.Environment env);
    }

    static Connection connect(ChannelDefinition ch, ChannelAdapters.Environment env) {
        String ref = ch.properties().get("uriRef");
        if (ref == null) {
            throw new IllegalArgumentException("rabbitmq channel " + ch.key() + " needs properties.uriRef");
        }
        String uri = env.secret(ref).orElseThrow(() -> new IllegalStateException("secret " + ref + " not found"));
        try {
            ConnectionFactory f = new ConnectionFactory();
            f.setUri(uri);
            f.setAutomaticRecoveryEnabled(true);
            return f.newConnection(env.nodeId() + "-" + ch.key());
        } catch (Exception e) {
            throw new IllegalStateException("rabbitmq channel " + ch.key() + " cannot connect", e);
        }
    }

    static final class Outbound implements ChannelAdapters.OutboundChannelAdapter {
        private final ChannelDefinition ch;
        private final Connection connection;
        private final Channel channel;

        Outbound(ChannelDefinition ch, ChannelAdapters.Environment env) {
            this(ch, env, RabbitAdapters::connect);
        }

        Outbound(ChannelDefinition ch, ChannelAdapters.Environment env, Connector connector) {
            this.ch = ch;
            this.connection = connector.connect(ch, env);
            try {
                this.channel = connection.createChannel();
                channel.confirmSelect();
            } catch (Exception e) {
                throw new IllegalStateException("rabbitmq channel " + ch.key() + " cannot open channel", e);
            }
        }

        @Override
        public synchronized void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            Map<String, Object> headers = new HashMap<>();
            headers.put("event-key", eventKey);
            if (tenantId != null) {
                headers.put("tenant-id", tenantId);
            }
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().messageId(messageId).contentType("application/json")
                    .deliveryMode(2).headers(headers).build();
            String exchange = ch.exchange() == null ? "" : ch.exchange();
            String routingKey = ch.routingKey() != null ? ch.routingKey() : ch.queue() != null ? ch.queue() : ch.destination();
            channel.basicPublish(exchange, routingKey, true, props, body.getBytes(StandardCharsets.UTF_8));
            channel.waitForConfirmsOrDie(Long.parseLong(ch.property("confirmTimeoutMs", "10000")));
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (Exception e) {
                LOG.warn("rabbitmq channel {}: close failed: {}", ch.key(), e.toString());
            }
        }
    }

    static final class Inbound implements ChannelAdapters.InboundChannelAdapter {
        private final ChannelDefinition ch;
        private final String tenant;
        private final InboundEventProcessor processor;
        private final ChannelAdapters.Environment env;
        private final Connector connector;
        private Connection connection;

        Inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, ChannelAdapters.Environment env) {
            this(ch, tenant, processor, env, RabbitAdapters::connect);
        }

        Inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, ChannelAdapters.Environment env, Connector connector) {
            this.connector = connector;
            this.ch = ch;
            this.tenant = tenant;
            this.processor = processor;
            this.env = env;
            if (ch.queue() == null && ch.destination() == null) {
                throw new IllegalArgumentException("rabbitmq channel " + ch.key() + " needs queue");
            }
        }

        @Override
        public synchronized void start() {
            connection = connector.connect(ch, env);
            try {
                Channel channel = connection.createChannel();
                channel.basicQos(Integer.parseInt(ch.property("prefetch", "50")));
                String queue = ch.queue() != null ? ch.queue() : ch.destination();
                channel.basicConsume(queue, false, (tag, d) -> onDelivery(channel, d), tag -> { });
            } catch (Exception e) {
                // the caller discards a consumer that failed to start: release its connection now, or every retry leaks one
                close();
                throw new IllegalStateException("rabbitmq channel " + ch.key() + " cannot consume", e);
            }
        }

        private void onDelivery(Channel channel, Delivery d) throws java.io.IOException {
            long tag = d.getEnvelope().getDeliveryTag();
            try {
                Map<String, String> headers = new HashMap<>();
                if (d.getProperties().getHeaders() != null) {
                    d.getProperties().getHeaders().forEach((k, v) -> headers.put(k, String.valueOf(v)));
                }
                String id = d.getProperties().getMessageId();
                processor.receive(tenant, ch.key(), new String(d.getBody(), StandardCharsets.UTF_8), headers, id);
                channel.basicAck(tag, false);
            } catch (RuntimeException e) {
                LOG.warn("rabbitmq channel {}: delivery not stored, requeue: {}", ch.key(), e.toString());
                channel.basicNack(tag, false, true);
            }
        }

        @Override
        public synchronized void close() {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    LOG.warn("rabbitmq channel {}: close failed: {}", ch.key(), e.toString());
                }
                connection = null;
            }
        }
    }
}
