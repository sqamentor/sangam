// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Relay;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.events.EventLog;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.OutboundEventService;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.Json;

/**
 * FC-03 relay publisher: outbox delivery {@code channel:<key>} → serializer → adapter, with the core {@link Relay}'s
 * retry, tenant-bound channel lookup (R38) and the adapter cache per channel version.
 */
class ChannelPublisherH2Test {

    private PortsH2Engine h2;
    private EventRegistry registry;
    private OutboundEventService outbound;
    private HttpServer server;
    private final List<Map.Entry<String, String>> received = new CopyOnWriteArrayList<>();   // (idempotency key, body)
    private final AtomicInteger failNext = new AtomicInteger();
    private ChannelAdapters.Environment env;

    @BeforeEach
    void setUp() throws Exception {
        h2 = PortsH2Engine.start(s -> s.setOutbox(new Outbox(Dialect.POSTGRESQL)));
        registry = h2.plugin.eventRegistry();
        outbound = new OutboundEventService(registry, new Outbox(Dialect.POSTGRESQL), new EventLog(Dialect.POSTGRESQL, true));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(Map.entry(ex.getRequestHeaders().getFirst("Idempotency-Key"),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            ex.sendResponseHeaders(failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 500 : 204, -1);
            ex.close();
        });
        server.start();
        env = ChannelAdapters.Environment.of(Map.of(), Set.of("127.0.0.1"));
        registry.deployEvent("acc", "{\"key\":\"paid\",\"payload\":[{\"name\":\"amount\",\"type\":\"long\",\"required\":true},"
                + "{\"name\":\"orderId\",\"type\":\"string\"}]}", null);
        registry.deployChannel("acc", httpChannel("hook", null), null);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        h2.close();
    }

    private String httpChannel(String key, String serializer) {
        return "{\"key\":\"" + key + "\",\"channelType\":\"http\",\"type\":\"outbound\",\"url\":\"http://127.0.0.1:"
                + server.getAddress().getPort() + "/" + key + "\"" + (serializer == null ? "" : ",\"serializerType\":\"" + serializer + "\"")
                + ",\"properties\":{\"allowPlainHttp\":\"true\"}}";
    }

    private Outbox.Appended send(String tenant, String channel, Map<String, Object> values, String scope) {
        return EngineTx.execute(h2.engine, cc -> outbound.send(EngineTx.connection(), tenant, "root-1", "paid", List.of(channel), values, scope));
    }

    private Relay relay(ChannelPublisher p) {
        return new Relay(Dialect.POSTGRESQL, EngineTx.transactions(h2.engine), p,
                new Relay.Config("n1", 10, Duration.ofSeconds(30), 5, Duration.ZERO, Duration.ZERO), null);
    }

    private String deliveryState(long outboxId, String destination) throws Exception {
        try (Connection c = h2.connection()) {
            return Relay.state(c, outboxId, destination);
        }
    }

    @Test
    void theRelayDeliversTheSerializedEventAndRetriesAFailedDeliveryWithTheSameMessageId() throws Exception {
        Outbox.Appended a = send("acc", "hook", Map.of("amount", 500L, "orderId", "O-1", "ignored", "x"), "op-1");
        failNext.set(1);
        try (ChannelPublisher p = new ChannelPublisher(registry, env)) {
            Relay relay = relay(p);
            Relay.CycleResult first = relay.runOnce();
            assertEquals(1, first.failed(), "HTTP 500: the delivery fails and stays pending");
            assertFalse("DONE".equals(deliveryState(a.outboxId(), "channel:hook")));
            relay.drain(5);
            assertEquals("DONE", deliveryState(a.outboxId(), "channel:hook"));
            relay.drain(5);
        }
        assertEquals(2, received.size(), "one failed attempt, one success, nothing after DONE");
        assertEquals(received.get(0).getKey(), received.get(1).getKey());
        JsonNode body = Json.tree(received.get(1).getValue());
        assertEquals("paid", body.get("eventKey").asText());
        assertEquals("acc", body.get("tenantId").asText());
        assertEquals(received.get(1).getKey(), body.get("messageId").asText());
        assertEquals(500L, body.at("/payload/amount").asLong());
        assertEquals("O-1", body.at("/payload/orderId").asText());
        assertFalse(body.get("payload").has("ignored"), "only declared payload fields leave the system");
    }

    @Test
    void thePayloadOnlySerializerSendsTheBareBusinessObject() throws Exception {
        registry.deployChannel("acc", httpChannel("bare", "json-payload"), null);
        send("acc", "bare", Map.of("amount", 7L), "op-2");
        try (ChannelPublisher p = new ChannelPublisher(registry, env)) {
            relay(p).drain(5);
        }
        assertEquals(Map.of("amount", 7), Json.map(received.get(0).getValue()));
    }

    @Test
    void aChannelIsResolvedOnlyInTheEventsOwnTenant() {
        // R38: an outbox row of tenant "other" naming channel:hook must not reach tenant acc's channel
        Outbox.OutboxEvent foreign = new Outbox.OutboxEvent(1L, UUID.randomUUID(), "other", "r", 1, "sangam.event.paid",
                "{\"eventKey\":\"paid\",\"messageId\":\"m\",\"payload\":{}}");
        try (ChannelPublisher p = new ChannelPublisher(registry, env)) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> p.publish("channel:hook", foreign));
            assertTrue(e.getMessage().contains("no longer deployed in other"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> p.publish("kafka:hook", foreign));
        }
        assertEquals(0, received.size());
        assertThrows(IllegalArgumentException.class, () -> send("other", "hook", Map.of("amount", 1L), "op-3"),
                "the send side refuses the foreign channel too");
        assertTrue(ChannelPublisher.handles("channel:x"));
        assertFalse(ChannelPublisher.handles("http:x"));
        assertFalse(ChannelPublisher.handles(null));
    }

    /** Records each adapter the publisher creates and whether it was closed. */
    static final class StubAdapter implements ChannelAdapters.OutboundChannelAdapter {
        final ChannelDefinition def;
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        StubAdapter(ChannelDefinition def) {
            this.def = def;
        }

        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) {
            if (closed) {
                throw new IllegalStateException("closed adapter used");
            }
            sent.add(tenantId + "|" + messageId);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void aRedeployedChannelVersionGetsAFreshAdapterAndTheSupersededOneIsClosed() throws Exception {
        List<StubAdapter> created = new CopyOnWriteArrayList<>();
        ChannelPublisher p = new ChannelPublisher(registry, env, (d, e) -> {
            StubAdapter s = new StubAdapter(d);
            created.add(s);
            return s;
        });
        Outbox.OutboxEvent ev = new Outbox.OutboxEvent(1L, UUID.randomUUID(), "acc", "r", 1, "sangam.event.paid",
                "{\"eventKey\":\"paid\",\"messageId\":\"m-1\",\"payload\":{}}");
        p.publish("channel:hook", ev);
        p.publish("channel:hook", ev);
        assertEquals(1, created.size(), "cached per (tenant, channel, version)");

        registry.deployChannel("acc", httpChannel("hook", null).replace("/hook\"", "/hook-v2\""), null);
        p.publish("channel:hook", ev);
        assertEquals(2, created.size(), "version 2 gets its own adapter");
        assertTrue(created.get(1).def.url().endsWith("/hook-v2"));
        assertTrue(created.get(0).closed, "the version-1 adapter (an open producer / connection) is released");
        assertFalse(created.get(1).closed);

        registry.retireChannel("acc", "hook", null, "tester");
        assertThrows(IllegalStateException.class, () -> p.publish("channel:hook", ev));
        assertTrue(created.get(1).closed, "a retired channel's adapter is released too");
        assertEquals(List.of("acc|m-1", "acc|m-1"), created.get(0).sent);
        p.close();
    }

    @Test
    void closeReleasesEveryAdapter() throws Exception {
        List<StubAdapter> created = new CopyOnWriteArrayList<>();
        registry.deployChannel("acc", httpChannel("hook2", null), null);
        ChannelPublisher p = new ChannelPublisher(registry, env, (d, e) -> {
            StubAdapter s = new StubAdapter(d);
            created.add(s);
            return s;
        });
        Outbox.OutboxEvent ev = new Outbox.OutboxEvent(1L, UUID.randomUUID(), "acc", "r", 1, "sangam.event.paid",
                "{\"eventKey\":\"paid\",\"payload\":{}}");
        p.publish("channel:hook", ev);
        p.publish("channel:hook2", ev);
        p.close();
        assertEquals(2, created.size());
        assertTrue(created.stream().allMatch(s -> s.closed));
        assertEquals(ev.eventId().toString(), created.get(0).sent.get(0).substring(4), "message id falls back to the outbox event id");
    }
}
