// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.fwt.events.FwtEventsFixture;
import org.sangam.ports.support.Json;

/**
 * FC-03 RabbitMQ adapters on in-memory stubs of the {@code com.rabbitmq.client} interfaces (no broker): publisher
 * confirms and message properties outbound; manual ack after the inbox write, nack+requeue on infrastructure
 * failure, ack of duplicates / poison / tenant-mismatch messages inbound.
 */
class RabbitAdaptersTest {

    /** Records what the adapter does on the AMQP channel. */
    static final class Broker {
        record Published(String exchange, String routingKey, boolean mandatory, AMQP.BasicProperties props, String body) {
        }

        final List<Published> published = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger connectionsClosed = new AtomicInteger();
        volatile DeliverCallback deliver;
        volatile String consumedQueue;
        volatile boolean nackConfirms;
        volatile boolean failConsume;

        final Channel channel = JmsStubs.proxy(Channel.class, (m, a) -> switch (m) {
            case "confirmSelect" -> {
                calls.add("confirmSelect");
                yield null;
            }
            case "basicPublish" -> {
                published.add(new Published((String) a[0], (String) a[1], (Boolean) a[2], (AMQP.BasicProperties) a[3],
                        new String((byte[]) a[4], StandardCharsets.UTF_8)));
                yield null;
            }
            case "waitForConfirmsOrDie" -> {
                calls.add("waitForConfirmsOrDie " + a[0]);
                if (nackConfirms) {
                    throw new TimeoutException("broker did not confirm");
                }
                yield null;
            }
            case "basicQos" -> {
                calls.add("qos " + a[0]);
                yield null;
            }
            case "basicConsume" -> {
                if (failConsume) {
                    throw new java.io.IOException("NOT_FOUND - no queue");
                }
                consumedQueue = (String) a[0];
                if ((Boolean) a[1]) {
                    throw new AssertionError("inbound must use manual ack");
                }
                deliver = (DeliverCallback) a[2];
                yield "ctag-1";
            }
            case "basicAck" -> {
                calls.add("ack " + a[0]);
                yield null;
            }
            case "basicNack" -> {
                calls.add("nack " + a[0] + " requeue=" + a[2]);
                yield null;
            }
            default -> throw new UnsupportedOperationException(m);
        });

        final Connection connection = JmsStubs.proxy(Connection.class, (m, a) -> switch (m) {
            case "createChannel" -> channel;
            case "close" -> {
                connectionsClosed.incrementAndGet();
                yield null;
            }
            default -> throw new UnsupportedOperationException(m);
        });

        final RabbitAdapters.Connector connector = (ch, env) -> connection;
    }

    private static final ChannelAdapters.Environment ENV = ChannelAdapters.Environment.of(
            Map.of("rabbit-uri", "amqp://guest:guest@127.0.0.1:1/%2f"), Set.of());

    private final Broker broker = new Broker();
    private FwtEventsFixture f;
    private RabbitAdapters.Inbound inbound;
    private long tag;

    @BeforeEach
    void setUp() {
        f = new FwtEventsFixture();
        f.registry.deployEvent("acc", "{\"key\":\"order\",\"payload\":[{\"name\":\"qty\",\"type\":\"integer\",\"required\":true}]}", null);
    }

    @AfterEach
    void tearDown() {
        if (inbound != null) {
            inbound.close();
        }
        f.close();
    }

    private static ChannelDefinition def(String json) {
        return Json.read(json, ChannelDefinition.class);
    }

    // ------------------------------------------------------------------ outbound

    @Test
    void outboundPublishesPersistentMandatoryMessagesAndWaitsForTheConfirm() throws Exception {
        RabbitAdapters.Outbound out = new RabbitAdapters.Outbound(def("{\"key\":\"ro\",\"channelType\":\"rabbitmq\",\"type\":\"outbound\","
                + "\"exchange\":\"events\",\"routingKey\":\"order.paid\",\"properties\":{\"uriRef\":\"rabbit-uri\",\"confirmTimeoutMs\":\"2500\"}}"),
                ENV, broker.connector);
        out.send("acc", "paid", "m-1", "{\"a\":1}");
        assertEquals("confirmSelect", broker.calls.get(0), "publisher confirms switched on before the first publish");
        Broker.Published p = broker.published.get(0);
        assertEquals("events", p.exchange());
        assertEquals("order.paid", p.routingKey());
        assertTrue(p.mandatory(), "unroutable messages come back instead of vanishing");
        assertEquals("m-1", p.props().getMessageId());
        assertEquals(2, p.props().getDeliveryMode(), "persistent");
        assertEquals("application/json", p.props().getContentType());
        assertEquals(Map.of("event-key", "paid", "tenant-id", "acc"), p.props().getHeaders());
        assertEquals("{\"a\":1}", p.body());
        assertTrue(broker.calls.contains("waitForConfirmsOrDie 2500"));
        out.close();
        assertEquals(1, broker.connectionsClosed.get());
    }

    @Test
    void outboundToTheDefaultExchangeRoutesByQueueAndAMissingConfirmThrows() {
        RabbitAdapters.Outbound out = new RabbitAdapters.Outbound(def("{\"key\":\"ro\",\"channelType\":\"rabbitmq\",\"type\":\"outbound\","
                + "\"queue\":\"orders\",\"properties\":{\"uriRef\":\"rabbit-uri\"}}"), ENV, broker.connector);
        broker.nackConfirms = true;
        assertThrows(TimeoutException.class, () -> out.send(null, "paid", "m-2", "{}"), "the relay retries an unconfirmed publish");
        Broker.Published p = broker.published.get(0);
        assertEquals("", p.exchange());
        assertEquals("orders", p.routingKey());
        assertEquals(Map.of("event-key", "paid"), p.props().getHeaders());
        assertTrue(broker.calls.contains("waitForConfirmsOrDie 10000"), "default confirm timeout");
    }

    @Test
    void theRealConnectorValidatesTheSecretReferenceAndFailsOnAnUnreachableBroker() {
        IllegalArgumentException noRef = assertThrows(IllegalArgumentException.class, () -> new RabbitAdapters.Outbound(
                def("{\"key\":\"ro\",\"channelType\":\"rabbitmq\",\"type\":\"outbound\",\"queue\":\"q\"}"), ENV));
        assertTrue(noRef.getMessage().contains("uriRef"));
        IllegalStateException noSecret = assertThrows(IllegalStateException.class, () -> new RabbitAdapters.Outbound(
                def("{\"key\":\"ro\",\"channelType\":\"rabbitmq\",\"type\":\"outbound\",\"queue\":\"q\",\"properties\":{\"uriRef\":\"nope\"}}"), ENV));
        assertTrue(noSecret.getMessage().contains("nope"));
        // 127.0.0.1:1 has no listener: refused locally, no network beyond the loopback
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> new RabbitAdapters.Outbound(
                def("{\"key\":\"ro\",\"channelType\":\"rabbitmq\",\"type\":\"outbound\",\"queue\":\"q\",\"properties\":{\"uriRef\":\"rabbit-uri\"}}"), ENV));
        assertTrue(refused.getMessage().contains("cannot connect"));
        assertThrows(IllegalArgumentException.class, () -> new RabbitAdapters.Inbound(
                def("{\"key\":\"ri\",\"channelType\":\"rabbitmq\"}"), "acc", f.processor, ENV), "inbound needs a queue");
    }

    // ------------------------------------------------------------------ inbound

    private void startInbound(String extra) {
        ChannelDefinition ch = f.registry.deployChannel("acc", "{\"key\":\"rin\",\"channelType\":\"rabbitmq\",\"type\":\"inbound\","
                + "\"queue\":\"orders\",\"channelEventKeyDetection\":{\"fixedValue\":\"order\"},"
                + "\"properties\":{\"uriRef\":\"rabbit-uri\",\"prefetch\":\"7\"}" + extra + "}", null).definition();
        inbound = new RabbitAdapters.Inbound(ch, "acc", f.processor, ENV, broker.connector);
        inbound.start();
        assertNotNull(broker.deliver);
        assertEquals("orders", broker.consumedQueue);
        assertTrue(broker.calls.contains("qos 7"));
    }

    private long deliver(String messageId, String body) throws Exception {
        long t = ++tag;
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().messageId(messageId).headers(Map.of("event-key", "order")).build();
        broker.deliver.handle("ctag-1", new Delivery(new Envelope(t, false, "", "orders"), props, body.getBytes(StandardCharsets.UTF_8)));
        return t;
    }

    @Test
    void aStoredDeliveryIsAckedAndARedeliveryIsAckedAsDuplicate() throws Exception {
        startInbound("");
        long t1 = deliver("m-1", "{\"qty\":4}");
        long t2 = deliver("m-1", "{\"qty\":4}");
        assertTrue(broker.calls.contains("ack " + t1));
        assertTrue(broker.calls.contains("ack " + t2));
        assertEquals(List.of("m-1"), f.strings("SELECT message_id FROM INBOX"));
    }

    @Test
    void poisonAndForeignTenantDeliveriesAreRejectedAndAcked() throws Exception {
        startInbound(",\"channelEventTenantJsonPointerExpression\":\"/tenant\"");
        long poison = deliver("m-p", "[1,2]");
        long foreign = deliver("m-f", "{\"qty\":1,\"tenant\":\"other\"}");
        assertTrue(broker.calls.contains("ack " + poison));
        assertTrue(broker.calls.contains("ack " + foreign));
        assertEquals(0, f.count("SELECT COUNT(*) FROM INBOX"));
        assertEquals(1, f.count("SELECT COUNT(*) FROM EVT_LOG WHERE outcome = 'REJECTED' AND detail LIKE 'TENANT_MISMATCH%'"), "R38");
        assertTrue(broker.calls.stream().noneMatch(c -> c.startsWith("nack")));
    }

    @Test
    void anInfrastructureFailureNacksWithRequeueAndTheRedeliveryIsStored() throws Exception {
        startInbound("");
        f.registry.channel("acc", "rin");
        f.productDs.down = true;
        long t = deliver("m-9", "{\"qty\":1}");
        assertTrue(broker.calls.contains("nack " + t + " requeue=true"));
        assertTrue(broker.calls.stream().noneMatch(c -> c.equals("ack " + t)));
        f.productDs.down = false;
        long again = deliver("m-9", "{\"qty\":1}");
        assertTrue(broker.calls.contains("ack " + again));
        assertEquals(List.of("m-9"), f.strings("SELECT message_id FROM INBOX"));
    }

    @Test
    void aConsumerThatCannotSubscribeReleasesItsConnection() {
        broker.failConsume = true;
        ChannelDefinition ch = f.registry.deployChannel("acc", "{\"key\":\"rin\",\"channelType\":\"rabbitmq\",\"type\":\"inbound\","
                + "\"queue\":\"missing\",\"channelEventKeyDetection\":{\"fixedValue\":\"order\"},\"properties\":{\"uriRef\":\"rabbit-uri\"}}", null)
                .definition();
        RabbitAdapters.Inbound in = new RabbitAdapters.Inbound(ch, "acc", f.processor, ENV, broker.connector);
        assertThrows(IllegalStateException.class, in::start);
        assertEquals(1, broker.connectionsClosed.get(), "InboundChannelManager drops a failed consumer, so start must not leak");
    }
}
