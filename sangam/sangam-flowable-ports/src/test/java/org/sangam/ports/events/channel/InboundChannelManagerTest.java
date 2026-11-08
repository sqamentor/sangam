// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.ports.fwt.events.FwtEventsFixture;

/**
 * Consumer lifecycle per deployed inbound channel version and tenant: start on refresh, stop superseded and retired
 * versions, skip webhook/outbound channels, survive a channel that fails to start (JMS stubs, no broker).
 */
class InboundChannelManagerTest {

    private final JmsStubs.Factory brokerA = new JmsStubs.Factory();
    private final JmsStubs.Factory brokerB = new JmsStubs.Factory();
    private final ChannelAdapters.Environment env = new ChannelAdapters.Environment() {
        @Override
        public Optional<String> secret(String ref) {
            return Optional.empty();
        }

        @Override
        public Optional<Object> jmsConnectionFactory(String name) {
            return switch (name) {
                case "a" -> Optional.of(brokerA.cf);
                case "b" -> Optional.of(brokerB.cf);
                default -> Optional.empty();
            };
        }

        @Override
        public Set<String> egressHosts() {
            return Set.of();
        }
    };

    private FwtEventsFixture f;
    private InboundChannelManager mgr;

    @BeforeEach
    void setUp() {
        f = new FwtEventsFixture();
        for (String t : new String[]{"acc", "ops"}) {
            f.registry.deployEvent(t, "{\"key\":\"order\",\"payload\":[{\"name\":\"qty\",\"type\":\"integer\"}]}", null);
        }
        mgr = new InboundChannelManager(f.registry, f.processor, env);
    }

    @AfterEach
    void tearDown() {
        mgr.close();
        f.close();
    }

    private static String jms(String key, String cf, String dest) {
        return "{\"key\":\"" + key + "\",\"channelType\":\"jms\",\"type\":\"inbound\",\"destination\":\"" + dest + "\","
                + "\"channelEventKeyDetection\":{\"fixedValue\":\"order\"},\"properties\":{\"connectionFactory\":\"" + cf + "\"}}";
    }

    @Test
    void refreshStartsOneConsumerPerTenantAndChannelVersionAndSkipsWebhooksAndOutbound() {
        f.registry.deployChannel("acc", jms("jin", "a", "orders"), null);
        f.registry.deployChannel("ops", jms("jin", "b", "orders"), null);
        f.registry.deployChannel("acc", FwtEventsFixture.webhookChannel("hook", "\"channelEventKeyDetection\":{\"fixedValue\":\"order\"}"), null);
        f.registry.deployChannel("acc", "{\"key\":\"jout\",\"channelType\":\"jms\",\"type\":\"outbound\",\"destination\":\"x\","
                + "\"properties\":{\"connectionFactory\":\"a\"}}", null);
        mgr.refresh();
        assertEquals(Set.of("acc|jin|1", "ops|jin|1"), mgr.runningChannels());
        mgr.refresh();
        assertEquals(1, brokerA.connectionsOpened.get(), "a second refresh does not restart running consumers");

        // each consumer stores into its own channel tenant (R38)
        assertNotNull(brokerB.listener);
        JmsStubs.Msg m = new JmsStubs.Msg("ID:1", "{\"qty\":1}", Map.of());
        brokerB.listener.onMessage(m.message);
        assertEquals(1, m.acks.get());
        assertEquals(java.util.List.of("ops"), f.strings("SELECT tenant_id FROM INBOX"));
    }

    @Test
    void aNewVersionReplacesTheOldConsumerAndARetiredChannelIsStopped() {
        f.registry.deployChannel("acc", jms("jin", "a", "orders"), null);
        mgr.refresh();
        f.registry.deployChannel("acc", jms("jin", "a", "orders.v2"), null);
        mgr.refresh();
        assertEquals(Set.of("acc|jin|2"), mgr.runningChannels());
        assertEquals(2, brokerA.connectionsOpened.get());
        assertEquals(1, brokerA.connectionsClosed.get(), "version 1 consumer stopped");
        assertEquals("consumer:queue/orders.v2", brokerA.consumers.get(1));

        f.registry.retireChannel("acc", "jin", null, "tester");
        mgr.refresh();
        assertEquals(Set.of(), mgr.runningChannels());
        assertEquals(2, brokerA.connectionsClosed.get());
    }

    @Test
    void aChannelThatFailsToStartIsSkippedAndRetriedOnTheNextRefresh() {
        f.registry.deployChannel("acc", jms("jin", "a", "orders"), null);
        f.registry.deployChannel("acc", jms("broken", "missing-factory", "orders"), null);
        brokerB.failConnect = true;
        f.registry.deployChannel("ops", jms("jin", "b", "orders"), null);
        mgr.refresh();
        assertEquals(Set.of("acc|jin|1"), mgr.runningChannels(), "one bad channel does not stop the others");
        brokerB.failConnect = false;
        mgr.refresh();
        assertEquals(Set.of("acc|jin|1", "ops|jin|1"), mgr.runningChannels());
        mgr.close();
        assertEquals(Set.of(), mgr.runningChannels());
        assertEquals(1, brokerA.connectionsClosed.get());
        assertEquals(1, brokerB.connectionsClosed.get());
    }
}
