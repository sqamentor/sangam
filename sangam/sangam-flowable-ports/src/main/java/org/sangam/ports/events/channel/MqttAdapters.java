// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MQTT event channels (M-064; optional dependency {@code org.eclipse.paho:org.eclipse.paho.client.mqttv3}, EPL-2.0 or
 * EDL-1.0, the client the telemetry consumer already uses). MQTT 3.1.1 has no message metadata, so a message carries
 * the whole event: Sangam JSON or a <b>structured</b> CloudEvent (the CloudEvents MQTT binding: "with MQTT 3.1.1, the
 * content mode is always structured"); binary mode is refused at deploy.
 *
 * <p>Properties: {@code serverUri} ({@code tcp://} or {@code ssl://host:port}), optional {@code username} and
 * {@code passwordRef} (a secret reference), {@code qos} (1, default, or 2), {@code clientId} (default
 * {@code <node>-<channel>}).
 *
 * <ul>
 *   <li><b>Outbound:</b> a synchronous publish at QoS 1 (or 2), so the relay marks the delivery done only after the
 *       broker acknowledged it; topic = {@code destination} or {@code topics[0]}.</li>
 *   <li><b>Inbound:</b> a persistent session ({@code cleanSession=false}) with manual acks: a message is acknowledged
 *       only after {@link InboundEventProcessor#deliver} returned "acknowledge" (stored, duplicate, rejected, or parked
 *       for a MOVED/PURGED tenant, SL-142). Otherwise it stays unacknowledged; MQTT redelivers an unacknowledged QoS 1
 *       message only on a new connection, so after {@code redeliverBackoffMs} the consumer reconnects on its own thread.
 *       The message id must come from the event ({@code messageIdJsonPointer}, or the CloudEvents {@code id}): MQTT
 *       packet ids are reused.</li>
 * </ul>
 */
final class MqttAdapters {

    private static final Logger LOG = LoggerFactory.getLogger(MqttAdapters.class);

    private MqttAdapters() {
    }

    /** The family's entry, loaded by class name through {@code ChannelAdapters.vendorFamily} only when Paho is present. */
    static final class Family implements ChannelAdapters.Provider {
        Family() {
        }

        @Override
        public java.util.Set<String> types() {
            return java.util.Set.of("mqtt");
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

    /** Opens a client (production: Paho; tests pass a stub). */
    @FunctionalInterface
    interface Connector {
        MqttClient client(String serverUri, String clientId) throws MqttException;
    }

    static MqttConnectOptions options(ChannelDefinition ch, ChannelAdapters.Environment env, boolean cleanSession) {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(cleanSession);
        o.setAutomaticReconnect(true);
        String user = ch.properties().get("username");
        if (user != null) {
            o.setUserName(user);
        }
        String ref = ch.properties().get("passwordRef");
        if (ref != null) {
            o.setPassword(env.secret(ref).orElseThrow(() -> new IllegalStateException("secret " + ref + " not found")).toCharArray());
        }
        return o;
    }

    static int qos(ChannelDefinition ch) {
        int q = Integer.parseInt(ch.property("qos", "1").trim());
        if (q != 1 && q != 2) {
            throw new IllegalArgumentException("mqtt channel " + ch.key() + ": qos must be 1 or 2 (at-least-once)");
        }
        return q;
    }

    static String clientId(ChannelDefinition ch, ChannelAdapters.Environment env, String suffix) {
        return ch.property("clientId", env.nodeId() + "-" + ch.key()) + suffix;
    }

    static final class Outbound implements ChannelAdapters.OutboundChannelAdapter {
        private final ChannelDefinition ch;
        private final MqttClient client;
        private final String topic;
        private final int qos;

        Outbound(ChannelDefinition ch, ChannelAdapters.Environment env) {
            this(ch, env, (uri, id) -> new MqttClient(uri, id, new MemoryPersistence()));
        }

        Outbound(ChannelDefinition ch, ChannelAdapters.Environment env, Connector connector) {
            this.ch = ch;
            this.topic = ch.destination() != null ? ch.destination() : ch.topics().isEmpty() ? null : ch.topics().get(0);
            if (topic == null) {
                throw new IllegalArgumentException("mqtt channel " + ch.key() + " needs destination or topics[0]");
            }
            this.qos = qos(ch);
            try {
                this.client = connector.client(ch.properties().get("serverUri"), clientId(ch, env, "-out"));
                client.connect(options(ch, env, true));
            } catch (MqttException e) {
                throw new IllegalStateException("mqtt channel " + ch.key() + " cannot connect", e);
            }
        }

        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            MqttMessage m = new MqttMessage(body.getBytes(StandardCharsets.UTF_8));
            m.setQos(qos);
            m.setRetained(false);
            client.publish(topic, m);   // MqttClient.publish blocks until the QoS 1/2 handshake completed
        }

        @Override
        public void close() {
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            } catch (MqttException e) {
                LOG.warn("mqtt channel {}: close failed: {}", ch.key(), e.toString());
            }
        }
    }

    static final class Inbound implements ChannelAdapters.InboundChannelAdapter, MqttCallback {
        private final ChannelDefinition ch;
        private final String tenant;
        private final InboundEventProcessor processor;
        private final ChannelAdapters.Environment env;
        private final Connector connector;
        private final AtomicBoolean reconnecting = new AtomicBoolean();
        private volatile boolean open;
        private MqttClient client;
        private MqttConnectOptions options;

        Inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, ChannelAdapters.Environment env) {
            this(ch, tenant, processor, env, (uri, id) -> new MqttClient(uri, id, new MemoryPersistence()));
        }

        Inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, ChannelAdapters.Environment env,
                Connector connector) {
            this.ch = ch;
            this.tenant = tenant;
            this.processor = processor;
            this.env = env;
            this.connector = connector;
            if (ch.topics().isEmpty()) {
                throw new IllegalArgumentException("mqtt channel " + ch.key() + " needs topics");
            }
            qos(ch);
        }

        @Override
        public synchronized void start() {
            try {
                client = connector.client(ch.properties().get("serverUri"), clientId(ch, env, "-in"));
                client.setManualAcks(true);
                client.setCallback(this);
                options = options(ch, env, false);   // QoS 1 messages survive a reconnect
                open = true;
                connectAndSubscribe();
            } catch (MqttException | RuntimeException e) {
                close();
                throw new IllegalStateException("mqtt channel " + ch.key() + " cannot subscribe", e);
            }
        }

        private void connectAndSubscribe() throws MqttException {
            client.connect(options);
            int q = qos(ch);
            for (String t : ch.topics()) {
                client.subscribe(t, q);
            }
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            boolean ack;
            try {
                ack = ChannelAdapters.deliver(processor, tenant, ch, new String(message.getPayload(), StandardCharsets.UTF_8),
                        Map.of("mqtt-topic", topic), null);
            } catch (RuntimeException e) {
                LOG.warn("mqtt channel {}: message not stored, redelivered after a reconnect: {}", ch.key(), e.toString());
                ack = false;
            }
            if (ack) {
                try {
                    client.messageArrivedComplete(message.getId(), message.getQos());
                } catch (MqttException e) {
                    LOG.warn("mqtt channel {}: ack failed: {}", ch.key(), e.toString());
                }
            } else {
                scheduleReconnect();
            }
        }

        /** MQTT redelivers unacknowledged messages only on a new connection: reconnect off the callback thread. */
        private void scheduleReconnect() {
            if (!reconnecting.compareAndSet(false, true)) {
                return;
            }
            Thread t = new Thread(() -> {
                try {
                    ChannelAdapters.sleep(ChannelAdapters.redeliverBackoffMs(ch));
                    synchronized (this) {
                        if (open) {
                            if (client.isConnected()) {
                                client.disconnect();
                            }
                            connectAndSubscribe();
                        }
                    }
                } catch (MqttException e) {
                    LOG.warn("mqtt channel {}: reconnect failed (automatic reconnect continues): {}", ch.key(), e.toString());
                } finally {
                    reconnecting.set(false);
                }
            }, "sangam-mqtt-reconnect-" + ch.key());
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void connectionLost(Throwable cause) {
            LOG.warn("mqtt channel {}: connection lost, reconnecting: {}", ch.key(), String.valueOf(cause));
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // consumer only
        }

        @Override
        public synchronized void close() {
            open = false;
            if (client != null) {
                try {
                    if (client.isConnected()) {
                        client.disconnect();
                    }
                    client.close();
                } catch (MqttException e) {
                    LOG.warn("mqtt channel {}: close failed: {}", ch.key(), e.toString());
                }
                client = null;
            }
        }
    }
}
