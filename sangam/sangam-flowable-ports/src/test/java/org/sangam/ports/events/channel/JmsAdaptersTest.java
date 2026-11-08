// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.fwt.events.FwtEventsFixture;
import org.sangam.ports.support.Json;

/**
 * FC-03 JMS adapters on in-memory {@code jakarta.jms} stubs: outbound properties and destinations; inbound
 * CLIENT_ACKNOWLEDGE after the inbox write, session recover on infrastructure failure, ack of duplicates and poison
 * messages, the R38/FW-29 channel-tenant rule.
 */
class JmsAdaptersTest {

    private final JmsStubs.Factory broker = new JmsStubs.Factory();
    private final ChannelAdapters.Environment env = new ChannelAdapters.Environment() {
        @Override
        public Optional<String> secret(String ref) {
            return Optional.empty();
        }

        @Override
        public Optional<Object> jmsConnectionFactory(String name) {
            return switch (name) {
                case "artemis" -> Optional.of(broker.cf);
                case "wrong" -> Optional.of("not a factory");
                default -> Optional.empty();
            };
        }

        @Override
        public Set<String> egressHosts() {
            return Set.of();
        }
    };

    private FwtEventsFixture f;
    private JmsAdapters.Inbound inbound;

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
    void outboundSendsPersistentTextWithCorrelationIdAndRoutingProperties() throws Exception {
        JmsAdapters.Outbound out = new JmsAdapters.Outbound(def("{\"key\":\"o\",\"channelType\":\"jms\",\"type\":\"outbound\","
                + "\"destination\":\"orders.out\",\"properties\":{\"connectionFactory\":\"artemis\"}}"), env);
        out.send("acc", "paid", "m-1", "{\"a\":1}");
        out.send(null, "paid", "m-2", "{}");
        assertEquals(2, broker.sent.size());
        JmsStubs.Sent s = broker.sent.get(0);
        assertEquals("queue", s.destinationType());
        assertEquals("orders.out", s.destination());
        assertEquals("{\"a\":1}", s.body());
        assertEquals("m-1", s.correlationId());
        assertEquals(Map.of("messageId", "m-1", "eventKey", "paid", "tenantId", "acc"), s.properties());
        assertEquals(Map.of("messageId", "m-2", "eventKey", "paid"), broker.sent.get(1).properties(), "default tenant: no tenant property");
        assertEquals(2, broker.contextsClosed.get(), "one context per send, always closed");
    }

    @Test
    void outboundToATopic() throws Exception {
        new JmsAdapters.Outbound(def("{\"key\":\"o\",\"channelType\":\"jms\",\"type\":\"outbound\",\"queue\":\"news\","
                + "\"properties\":{\"connectionFactory\":\"artemis\",\"destinationType\":\"TOPIC\"}}"), env).send("acc", "k", "m", "{}");
        assertEquals("topic", broker.sent.get(0).destinationType());
        assertEquals("news", broker.sent.get(0).destination(), "queue is the fallback destination name");
    }

    @Test
    void misconfiguredChannelsFailAtConstruction() {
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> new JmsAdapters.Outbound(
                def("{\"key\":\"o\",\"channelType\":\"jms\",\"type\":\"outbound\",\"destination\":\"q\"}"), env));
        assertTrue(missing.getMessage().contains("'default' not provided"), missing.getMessage());
        assertThrows(IllegalStateException.class, () -> new JmsAdapters.Outbound(def("{\"key\":\"o\",\"channelType\":\"jms\","
                + "\"type\":\"outbound\",\"destination\":\"q\",\"properties\":{\"connectionFactory\":\"wrong\"}}"), env));
        assertThrows(IllegalArgumentException.class, () -> new JmsAdapters.Outbound(def("{\"key\":\"o\",\"channelType\":\"jms\","
                + "\"type\":\"outbound\",\"properties\":{\"connectionFactory\":\"artemis\"}}"), env), "destination required");
    }

    // ------------------------------------------------------------------ inbound

    private void startInbound(String extra) {
        ChannelDefinition ch = f.registry.deployChannel("acc", "{\"key\":\"jin\",\"channelType\":\"jms\",\"type\":\"inbound\","
                + "\"destination\":\"orders\",\"channelEventKeyDetection\":{\"fixedValue\":\"order\"}" + extra
                + (extra.contains("\"properties\"") ? "" : ",\"properties\":{\"connectionFactory\":\"artemis\"}") + "}", null).definition();
        inbound = new JmsAdapters.Inbound(ch, "acc", f.processor, env);
        inbound.start();
        assertTrue(broker.started);
        assertNotNull(broker.listener);
    }

    private JmsStubs.Msg deliver(String jmsId, String body, Map<String, Object> props) {
        JmsStubs.Msg m = new JmsStubs.Msg(jmsId, body, props);
        broker.listener.onMessage(m.message);
        return m;
    }

    @Test
    void aStoredMessageIsAcknowledgedAndARedeliveryIsADuplicateThatIsAcknowledgedAgain() {
        startInbound("");
        assertEquals(List.of("consumer:queue/orders"), broker.consumers);
        JmsStubs.Msg first = deliver("ID:1", "{\"qty\":2}", Map.of("messageId", "biz-1"));
        assertEquals(1, first.acks.get());
        JmsStubs.Msg again = deliver("ID:2", "{\"qty\":2}", Map.of("messageId", "biz-1"));
        assertEquals(1, again.acks.get(), "duplicate: acknowledged, not redelivered for ever");
        assertEquals(List.of("biz-1"), f.strings("SELECT message_id FROM INBOX"), "stored once, under the business message id");
        assertEquals(0, broker.recovers.get());

        deliver("ID:3", "{\"qty\":3}", Map.of());
        assertEquals(List.of("ID:3", "biz-1"), f.strings("SELECT message_id FROM INBOX ORDER BY message_id"),
                "without a messageId property the JMS message id is the transport id");
    }

    @Test
    void aPoisonMessageIsRejectedAndAcknowledged() {
        startInbound("");
        JmsStubs.Msg bad = deliver("ID:p", "{\"qty\":\"many\"}", Map.of());
        assertEquals(1, bad.acks.get());
        assertEquals(0, broker.recovers.get());
        assertEquals(1, f.count("SELECT COUNT(*) FROM EVT_LOG WHERE channel_key = 'jin' AND outcome = 'REJECTED'"));
        assertEquals(0, f.count("SELECT COUNT(*) FROM INBOX"));
    }

    @Test
    void aDatabaseOutageRecoversTheSessionWithoutAckSoTheBrokerRedelivers() {
        startInbound("");
        f.registry.channel("acc", "jin");   // warm cache, as on a running node
        f.productDs.down = true;
        JmsStubs.Msg m = deliver("ID:9", "{\"qty\":1}", Map.of());
        assertEquals(0, m.acks.get(), "not acknowledged while the inbox write failed");
        assertEquals(1, broker.recovers.get());
        f.productDs.down = false;
        JmsStubs.Msg redelivered = deliver("ID:9", "{\"qty\":1}", Map.of());
        assertEquals(1, redelivered.acks.get());
        assertEquals(List.of("ID:9"), f.strings("SELECT message_id FROM INBOX"));
    }

    @Test
    void theChannelTenantWinsOverATenantNamedByTheMessage() {
        startInbound(",\"channelEventTenantJsonPointerExpression\":\"/tenant\"");
        JmsStubs.Msg foreign = deliver("ID:t1", "{\"qty\":1,\"tenant\":\"other\"}", Map.of("tenantId", "other"));
        assertEquals(1, foreign.acks.get(), "a tenant mismatch is a poison message: rejected and acknowledged");
        assertEquals(0, f.count("SELECT COUNT(*) FROM INBOX"));
        assertEquals(1, f.count("SELECT COUNT(*) FROM EVT_LOG WHERE outcome = 'REJECTED' AND detail LIKE 'TENANT_MISMATCH%'"));
        deliver("ID:t2", "{\"qty\":1,\"tenant\":\"acc\"}", Map.of());
        assertEquals(List.of("acc"), f.strings("SELECT tenant_id FROM INBOX"), "R38: stored in the channel's tenant");
    }

    @Test
    void aTopicWithSubscriptionNameUsesASharedDurableConsumerAndCloseReleasesTheConnection() {
        startInbound(",\"properties\":{\"connectionFactory\":\"artemis\",\"destinationType\":\"topic\",\"subscriptionName\":\"sangam-acc\"}");
        assertEquals(List.of("shared-durable:topic/orders:sangam-acc"), broker.consumers);
        inbound.close();
        inbound.close();
        assertEquals(1, broker.connectionsClosed.get(), "close is idempotent");
        inbound = null;
    }

    @Test
    void aBrokerThatRefusesTheConnectionFailsStart() {
        broker.failConnect = true;
        ChannelDefinition ch = f.registry.deployChannel("acc", "{\"key\":\"jin\",\"channelType\":\"jms\",\"type\":\"inbound\","
                + "\"destination\":\"orders\",\"channelEventKeyDetection\":{\"fixedValue\":\"order\"},"
                + "\"properties\":{\"connectionFactory\":\"artemis\"}}", null).definition();
        JmsAdapters.Inbound in = new JmsAdapters.Inbound(ch, "acc", f.processor, env);
        IllegalStateException e = assertThrows(IllegalStateException.class, in::start);
        assertTrue(e.getMessage().contains("cannot start"));
    }

    @Test
    void aConsumerThatFailsAfterConnectingReleasesItsConnection() {
        broker.failConsumer = true;
        ChannelDefinition ch = f.registry.deployChannel("acc", "{\"key\":\"jin\",\"channelType\":\"jms\",\"type\":\"inbound\","
                + "\"destination\":\"missing\",\"channelEventKeyDetection\":{\"fixedValue\":\"order\"},"
                + "\"properties\":{\"connectionFactory\":\"artemis\"}}", null).definition();
        JmsAdapters.Inbound in = new JmsAdapters.Inbound(ch, "acc", f.processor, env);
        assertThrows(IllegalStateException.class, in::start);
        assertEquals(1, broker.connectionsOpened.get());
        assertEquals(1, broker.connectionsClosed.get(), "InboundChannelManager drops a failed consumer, so start must not leak");
    }
}
