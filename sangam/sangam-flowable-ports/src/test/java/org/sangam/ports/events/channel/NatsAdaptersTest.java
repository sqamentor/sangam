// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.support.Json;

/**
 * M-064 NATS JetStream channels over a scripted link: the publish carries the JetStream de-duplication id and the
 * Sangam or CloudEvents binary headers; the pull consumer acknowledges only what the processor said to acknowledge and
 * naks the rest with the redelivery delay; a processor failure is a nak, never an ack. The connection settings resolve
 * credentials by reference.
 */
class NatsAdaptersTest {

    static ChannelDefinition ch(String json) {
        return Json.read(json, ChannelDefinition.class);
    }

    static final ChannelAdapters.Environment ENV = ChannelAdapters.Environment.of(Map.of("secret://nats/pw", "pw-value"), java.util.Set.of());

    record Published(String subject, Map<String, String> headers, String body, String messageId) {
    }

    static final class ScriptedLink implements NatsAdapters.Link {
        final List<Published> published = new ArrayList<>();
        final List<List<NatsAdapters.Received>> batches = new ArrayList<>();
        boolean closed;

        @Override
        public void publish(String subject, Map<String, String> headers, byte[] body, String messageId) {
            published.add(new Published(subject, headers, new String(body, StandardCharsets.UTF_8), messageId));
        }

        @Override
        public List<NatsAdapters.Received> fetch(int batch, Duration wait) {
            return batches.isEmpty() ? List.of() : batches.remove(0);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static final class Msg implements NatsAdapters.Received {
        final Map<String, String> headers;
        final String data;
        String settled;

        Msg(Map<String, String> headers, String data) {
            this.headers = headers;
            this.data = data;
        }

        @Override
        public String subject() {
            return "orders.in";
        }

        @Override
        public Map<String, String> headers() {
            return headers;
        }

        @Override
        public byte[] data() {
            return data.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void ack() {
            settled = "ack";
        }

        @Override
        public void nakWithDelay(Duration delay) {
            settled = "nak " + delay.toMillis();
        }
    }

    static final class ScriptedProcessor extends InboundEventProcessor {
        final Map<String, Object> answers;
        final List<String> ids = new ArrayList<>();

        ScriptedProcessor(Map<String, Object> answers) {
            super(null, null, null, null);
            this.answers = answers;
        }

        @Override
        public Delivery deliver(String tenant, String channelKey, String raw, Map<String, String> headers, String id) {
            ids.add(id);
            Object a = answers.get(id);
            if (a instanceof RuntimeException e) {
                throw e;
            }
            return Boolean.TRUE.equals(a)
                    ? new Delivery(Disposition.ACK, new Result(Outcome.ACCEPTED, "E", id, null), null)
                    : new Delivery(Disposition.REDELIVER, null, "TENANT_NOT_ADMITTED: tenant " + tenant);
        }
    }

    @Test
    void publishCarriesTheDedupIdAndHeaders() throws Exception {
        ScriptedLink link = new ScriptedLink();
        NatsAdapters.Outbound o = new NatsAdapters.Outbound(ch("{\"key\":\"o\",\"channelType\":\"nats\",\"type\":\"outbound\","
                + "\"destination\":\"orders.out\",\"properties\":{\"serverUri\":\"nats://h:4222\"}}"), ENV, (c, e, in) -> link);
        o.send("T1", "OrderPlaced", "m-1", "{\"a\":1}");
        Published p = link.published.get(0);
        assertEquals("orders.out", p.subject());
        assertEquals("m-1", p.messageId(), "Nats-Msg-Id: JetStream drops a retried duplicate inside its window");
        assertEquals("OrderPlaced", p.headers().get("event-key"));
        assertEquals("T1", p.headers().get("tenant-id"));
        assertEquals("application/json", p.headers().get("Content-Type"));

        o.send(new ChannelAdapters.Outgoing("T1", "OrderPlaced", "m-2", "{\"a\":2}", "application/json",
                Map.of("specversion", "1.0", "id", "m-2", "type", "OrderPlaced")));
        Map<String, String> h = link.published.get(1).headers();
        assertEquals("1.0", h.get("ce-specversion"));
        assertEquals("m-2", h.get("ce-id"));
        o.close();
        assertTrue(link.closed);
    }

    @Test
    void consumerAcksOnlyWhatTheProcessorAcknowledged() throws Exception {
        ScriptedLink link = new ScriptedLink();
        Msg admitted = new Msg(Map.of("Nats-Msg-Id", "m-1"), "{\"a\":1}");
        Msg frozen = new Msg(Map.of("message-id", "m-2"), "{\"a\":2}");
        Msg failing = new Msg(Map.of("Nats-Msg-Id", "m-3"), "{\"a\":3}");
        link.batches.add(List.of(admitted, frozen, failing));
        ScriptedProcessor p = new ScriptedProcessor(Map.of("m-1", true, "m-2", false, "m-3", new IllegalStateException("db down")));
        NatsAdapters.Inbound in = new NatsAdapters.Inbound(ch("{\"key\":\"i\",\"channelType\":\"nats\",\"type\":\"inbound\","
                + "\"properties\":{\"serverUri\":\"nats://h:4222\",\"stream\":\"S\",\"consumer\":\"C\",\"redeliverBackoffMs\":\"2500\"}}"),
                "T1", p, ENV, (c, e, inbound) -> {
                    assertTrue(inbound);
                    return link;
                });
        assertEquals(3, in.pollOnce());
        assertEquals(List.of("m-1", "m-2", "m-3"), p.ids);
        assertEquals("ack", admitted.settled);
        assertEquals("nak 2500", frozen.settled, "not admitted: redelivered after the backoff");
        assertEquals("nak 2500", failing.settled, "a failure is never acknowledged");
        assertEquals(0, in.pollOnce());
        in.release();
        assertTrue(link.closed);
    }

    @Test
    void connectionSettingsResolveCredentialsByReference() {
        assertThrows(IllegalArgumentException.class, () -> NatsConnections.options(Map.of(), ref -> Optional.empty(), "n"));
        Map<String, String> s = new LinkedHashMap<>();
        s.put("serverUri", "nats://a:4222, nats://b:4222");
        s.put("username", "svc");
        s.put("passwordRef", "secret://nats/pw");
        io.nats.client.Options o = NatsConnections.options(s, ENV::secret, "node-1-orders");
        assertEquals(2, o.getServers().size());
        assertEquals("node-1-orders", o.getConnectionName());
        s.put("passwordRef", "secret://nats/missing");
        assertThrows(IllegalStateException.class, () -> NatsConnections.options(s, ENV::secret, "n"));
        assertFalse(ChannelAdapters.vendorFamily("nats").isEmpty());
    }
}
