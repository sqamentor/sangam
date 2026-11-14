// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.channel.PollingInbound;
import org.sangam.ports.events.model.ChannelDefinition;

/**
 * Owner decision 189: Service Bus CloudEvents binary mode, off unless the channel sets
 * {@code properties.binaryMode=customProperties}; then the attributes travel as {@code cloudEvents_*} custom properties.
 */
class ServiceBusBinaryModeTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final Map<String, String> SECRETS = Map.of("secret://sb/key", "sb-key-value");

    private HttpServer server;
    private String base;
    private final List<Map<String, String>> sentHeaders = new CopyOnWriteArrayList<>();
    private final List<String> sentBodies = new CopyOnWriteArrayList<>();
    private volatile int heads;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            Map<String, String> h = new LinkedHashMap<>();
            x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), String.join(",", v)));
            String path = x.getRequestURI().getRawPath();
            byte[] body = new byte[0];
            int status = 201;
            if (path.endsWith("/messages") && "POST".equals(x.getRequestMethod())) {
                sentHeaders.add(h);
                sentBodies.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } else if (path.endsWith("/messages/head")) {
                if (heads++ == 0) {
                    x.getResponseHeaders().add("BrokerProperties", "{\"MessageId\":\"m-9\"}");
                    x.getResponseHeaders().add("Location", base + "/orders/messages/9/lock-9");
                    x.getResponseHeaders().add("Content-Type", "application/json");
                    x.getResponseHeaders().add("cloudEvents_id", "\"m-9\"");
                    x.getResponseHeaders().add("cloudEvents_type", "\"OrderPlaced\"");
                    x.getResponseHeaders().add("cloudEvents_specversion", "\"1.0\"");
                    x.getResponseHeaders().add("cloudEvents_source", "\"urn:sangam:channel:c1\"");
                    x.getResponseHeaders().add("Priority", "\"High\"");
                    body = "{\"order\":9}".getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 204;
                }
            } else {
                status = 200;
            }
            x.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                x.getResponseBody().write(body);
            }
            x.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private CloudChannels provider() {
        HttpTransport t = new HttpTransport(EgressPolicy.of(List.of(EgressPolicy.Rule.parse("*", base, true)), 0, 1 << 20),
                null, Duration.ofSeconds(2), Duration.ofSeconds(5), RetryPolicy.none());
        return new CloudChannels(Clock.fixed(NOW, ZoneOffset.UTC), (ch, env) -> t);
    }

    private ChannelDefinition serviceBus(String direction, boolean optIn) throws IOException {
        return Json.MAPPER.readValue("{\"key\":\"c1\",\"channelType\":\"servicebus\",\"type\":\"" + direction
                + "\",\"properties\":{\"namespace\":\"ns1\",\"endpoint\":\"" + base + "\",\"entityPath\":\"orders\","
                + "\"sasKeyName\":\"send\",\"sasKeyRef\":\"secret://sb/key\""
                + (optIn ? ",\"binaryMode\":\"customProperties\"" : "") + "}}", ChannelDefinition.class);
    }

    private static ChannelAdapters.Environment env() {
        return ChannelAdapters.Environment.of(SECRETS, Set.of("127.0.0.1"));
    }

    private static ChannelAdapters.Outgoing binary() {
        Map<String, String> ce = new LinkedHashMap<>();
        ce.put("specversion", "1.0");
        ce.put("id", "m-2");
        ce.put("source", "urn:sangam:channel:c1");
        ce.put("type", "OrderPlaced");
        ce.put("sangamtenant", "T1");
        return new ChannelAdapters.Outgoing("T1", "OrderPlaced", "m-2", "{\"order\":8}", "application/json", ce);
    }

    @Test
    void withoutTheOptInBinaryModeIsRefusedAndNothingIsSent() throws Exception {
        ChannelAdapters.OutboundChannelAdapter o = provider().outbound(serviceBus("outbound", false), env());
        assertThrows(IllegalStateException.class, () -> o.send(binary()));
        assertTrue(sentHeaders.isEmpty());
    }

    @Test
    void withTheOptInTheAttributesAreCustomProperties() throws Exception {
        provider().outbound(serviceBus("outbound", true), env()).send(binary());
        Map<String, String> h = sentHeaders.get(0);
        assertEquals("\"m-2\"", h.get("cloudevents_id"), "a string custom property is double-quoted");
        assertEquals("\"OrderPlaced\"", h.get("cloudevents_type"));
        assertEquals("\"1.0\"", h.get("cloudevents_specversion"), "unquoted it would become the double 1.0");
        assertEquals("\"T1\"", h.get("cloudevents_sangamtenant"));
        assertEquals("application/json", h.get("content-type"));
        assertEquals("m-2", Json.tree(h.get("brokerproperties")).path("MessageId").asText());
        assertEquals("{\"order\":8}", sentBodies.get(0), "the body is the data only");
    }

    @Test
    void anAttributeThatCannotBeAHeaderIsRefused() throws Exception {
        Map<String, String> ce = new LinkedHashMap<>(binary().cloudEventsOrEmpty());
        ce.put("subject", "line\nbreak");
        ChannelAdapters.Outgoing bad = new ChannelAdapters.Outgoing("T1", "E", "m-3", "{}", "application/json", ce);
        assertThrows(IllegalStateException.class, () -> provider().outbound(serviceBus("outbound", true), env()).send(bad));
        assertTrue(sentHeaders.isEmpty());
    }

    /** Records the headers the consumer passes on. */
    static final class Seen extends InboundEventProcessor {
        final List<Map<String, String>> headers = new ArrayList<>();

        Seen() {
            super(null, null, null, null);
        }

        @Override
        public Delivery deliver(String tenant, String channelKey, String raw, Map<String, String> h, String id) {
            headers.add(h);
            return new Delivery(Disposition.ACK, new Result(Outcome.ACCEPTED, "E", id, null), null);
        }
    }

    private static int pollOnce(ChannelAdapters.InboundChannelAdapter a) throws Exception {
        java.lang.reflect.Method m = PollingInbound.class.getDeclaredMethod("pollOnce");
        m.setAccessible(true);
        return (int) m.invoke(a);
    }

    @Test
    void withTheOptInReceivedAttributesArePassedOnUnquoted() throws Exception {
        Seen p = new Seen();
        assertEquals(1, pollOnce(provider().inbound(serviceBus("inbound", true), "T1", p, env())));
        Map<String, String> h = p.headers.get(0);
        assertEquals("m-9", h.get("cloudevents_id"));
        assertEquals("OrderPlaced", h.get("cloudevents_type"));
        assertEquals("urn:sangam:channel:c1", h.get("cloudevents_source"));
        assertNull(h.get("priority"), "only the CloudEvents properties are passed on");
    }

    @Test
    void withoutTheOptInReceivedAttributesAreNotRead() throws Exception {
        Seen p = new Seen();
        assertEquals(1, pollOnce(provider().inbound(serviceBus("inbound", false), "T1", p, env())));
        assertFalse(p.headers.get(0).keySet().stream().anyMatch(k -> k.startsWith("cloudevents_")));
    }

    @Test
    void unquotingRemovesOnePairOfQuotesAndUnescapesNothing() {
        assertEquals("Example product", AzureChannels.unquote("\"Example product\""));
        assertEquals("a\\b", AzureChannels.unquote("\"a\\b\""));
        assertEquals("299.98", AzureChannels.unquote("299.98"), "a typed property keeps its text");
        assertEquals("true", AzureChannels.unquote("true"));
        assertEquals("\"", AzureChannels.unquote("\""));
    }

    @Test
    void onlyValuesThatComeBackUnchangedAreSentAsStrings() {
        assertTrue(AzureChannels.quotable("urn:sangam:channel:c1"));
        assertTrue(AzureChannels.quotable("2026-11-12T10:00:00Z"), "RFC 3339 time stays a string");
        assertFalse(AzureChannels.quotable("say \"hi\""), "no escaping is documented");
        assertFalse(AzureChannels.quotable("a\\b"));
        assertFalse(AzureChannels.quotable(" padded"));
        assertFalse(AzureChannels.quotable("Fri, 04 Mar 2011 08:49:37 GMT"), "would be stored as a DateTime");
        assertFalse(AzureChannels.quotable(null));
        assertEquals("\"x\"", AzureChannels.quote("x"));
    }
}
