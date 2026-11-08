// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.sangam.integration.connector.Json;
import org.sangam.integration.inbox.PoisonInbound;
import org.sangam.integration.relay.BackpressureGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * MQTT → inbox (N12, R38, R40). Optional: needs Eclipse Paho. Topic {@code sangam/<tenant>/telemetry/<deviceId>/<sensor>}
 * under a configured filter; the tenant segment is only a claim, checked against the channel binding.
 *
 * <p>QoS 1 with manual acks: the broker is acked only after the inbox commit. Poison readings and refused devices are
 * logged (or dead-lettered) and acked. On back-pressure or a database failure the message stays unacked; note that
 * Paho and the broker redeliver it only after a reconnect, so the consumer forces one.
 */
public final class MqttTelemetryConsumer implements MqttCallback, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MqttTelemetryConsumer.class);

    private final MqttClient client;
    private final MqttConnectOptions options;
    private final String topicFilter;
    private final TelemetryIngestor ingestor;
    private final TelemetryIngestor.Channel channel;
    private final PoisonInbound poison;

    public MqttTelemetryConsumer(String brokerUri, String clientId, MqttConnectOptions options, String topicFilter,
                                 TelemetryIngestor ingestor, TelemetryIngestor.Channel channel, PoisonInbound poisonOrNull)
            throws MqttException {
        this.client = new MqttClient(brokerUri, clientId, new MemoryPersistence());
        this.options = options;
        this.options.setCleanSession(false);   // QoS 1 messages survive a reconnect
        this.options.setAutomaticReconnect(true);
        this.topicFilter = topicFilter;
        this.ingestor = ingestor;
        this.channel = channel;
        this.poison = poisonOrNull;
        client.setManualAcks(true);
        client.setCallback(this);
    }

    public void start() throws MqttException {
        client.connect(options);
        client.subscribe(topicFilter, 1);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String body = new String(message.getPayload(), StandardCharsets.UTF_8);
        String[] t = topic.split("/");
        String tenant = t.length >= 5 ? t[1] : null;
        String device = t.length >= 5 ? t[3] : null;
        String sensor = t.length >= 5 ? t[4] : null;
        boolean ack = true;
        try {
            TelemetryReading r = TelemetryReading.fromMap(Json.readMap(body), device, sensor);
            TelemetryIngestor.Outcome o = ingestor.ingest(channel, r, null, tenant);
            if (!o.stored()) {
                LOG.warn("telemetry from {} refused: {} {}", topic, o.status(), o.detail());
            }
        } catch (BackpressureGate.BackpressureException e) {
            ack = false;
        } catch (IllegalArgumentException | java.io.UncheckedIOException | SecurityException | PoisonInbound.PoisonMessageException e) {
            if (poison != null && poison.record(channel.binding(), topic + "@" + message.getId(), TelemetryRuleHandler.EVENT_TYPE, body,
                    String.valueOf(e.getMessage())) < 0) {
                ack = false;
            } else {
                LOG.warn("poison telemetry on {}: {}", topic, e.getMessage());
            }
        } catch (RuntimeException e) {
            LOG.warn("inbox unavailable for telemetry on {}", topic, e);
            ack = false;
        }
        try {
            if (ack) {
                client.messageArrivedComplete(message.getId(), message.getQos());
            } else {
                client.disconnect();   // automatic reconnect does not follow an explicit disconnect
                client.connect(options);
                client.subscribe(topicFilter, 1);
            }
        } catch (MqttException e) {
            LOG.warn("MQTT ack/reconnect failed", e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        LOG.warn("MQTT connection lost; reconnecting: {}", cause.getMessage());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // consumer only
    }

    @Override
    public void close() throws MqttException {
        if (client.isConnected()) {
            client.disconnect();
        }
        client.close();
    }

    /** Header-free helper for tests and tools. */
    public static Map<String, Object> parse(byte[] payload) {
        return Json.readMap(new String(payload, StandardCharsets.UTF_8));
    }
}
