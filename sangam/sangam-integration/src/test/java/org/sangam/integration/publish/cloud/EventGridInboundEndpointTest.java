// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.Json;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.support.PortsProblem;

/** Owner decision 189: Event Grid push delivery, its two validation handshakes and the shared-secret check. */
class EventGridInboundEndpointTest {

    private static final String SECRET = "eg-test-secret";
    private static final Map<String, String> SECRETS = Map.of("secret://eg/in", SECRET, "secret://eg/blank", " ");

    private final Map<String, ChannelDefinition> channels = new HashMap<>();
    private final Recorder processor = new Recorder();

    /** Remembers each delivery; ids starting with "later" are to be offered again. */
    static final class Recorder extends InboundEventProcessor {
        final List<String> ids = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();

        Recorder() {
            super(null, null, null, null);
        }

        @Override
        public Delivery deliver(String tenant, String channelKey, String raw, Map<String, String> headers, String id) {
            ids.add(id);
            bodies.add(raw);
            return id.startsWith("later") ? new Delivery(Disposition.REDELIVER, null, "LOCKED")
                    : new Delivery(Disposition.ACK, new Result(Outcome.ACCEPTED, "E", id, null), null);
        }
    }

    private EventGridInboundEndpoint endpoint() {
        EventRegistry registry = new EventRegistry(null, null, Duration.ZERO) {
            @Override
            public Optional<DeployedChannel> channel(String tenantId, String key) {
                return Optional.ofNullable(channels.get(key)).map(c -> new DeployedChannel(tenantId, 1, c));
            }
        };
        return new EventGridInboundEndpoint(registry, processor, ChannelAdapters.Environment.of(SECRETS, Set.of()));
    }

    private void deploy(String key, String type, String direction, String props) throws IOException {
        channels.put(key, Json.MAPPER.readValue("{\"key\":\"" + key + "\",\"channelType\":\"" + type + "\",\"type\":\""
                + direction + "\",\"deserializerType\":\"cloudevents\",\"properties\":{" + props + "}}",
                ChannelDefinition.class));
    }

    private void headerChannel() throws IOException {
        deploy("eg", "eventgrid", "inbound", "\"authSecretRef\":\"secret://eg/in\",\"authHeader\":\"X-Sangam-Key\"");
    }

    private static Map<String, String> auth(String... more) {
        Map<String, String> h = new HashMap<>();
        h.put("x-sangam-KEY", SECRET);
        for (int i = 0; i + 1 < more.length; i += 2) {
            h.put(more[i], more[i + 1]);
        }
        return h;
    }

    private static String ce(String id) {
        return "{\"specversion\":\"1.0\",\"id\":\"" + id + "\",\"type\":\"OrderPlaced\",\"source\":\"/orders\","
                + "\"data\":{\"order\":7}}";
    }

    @Test
    void aStructuredCloudEventIsDeliveredWithItsId() throws Exception {
        headerChannel();
        EventGridInboundEndpoint.Answer a = endpoint().post("T1", "eg",
                auth("Content-Type", "application/cloudevents+json; charset=utf-8"), Map.of(), ce("e-1"));
        assertEquals(200, a.status());
        assertEquals(List.of("e-1"), processor.ids);
        assertEquals(7, Json.tree(processor.bodies.get(0)).path("data").path("order").asInt());
    }

    @Test
    void aBatchIsDeliveredAndAnEventToRetryMakesEventGridRetryTheRequest() throws Exception {
        headerChannel();
        EventGridInboundEndpoint.Answer ok = endpoint().post("T1", "eg",
                auth("Content-Type", "application/cloudevents-batch+json"), Map.of(), "[" + ce("a") + "," + ce("b") + "]");
        assertEquals(200, ok.status());
        assertEquals(List.of("a", "b"), processor.ids);
        EventGridInboundEndpoint.Answer retry = endpoint().post("T1", "eg",
                auth("Content-Type", "application/cloudevents-batch+json"), Map.of(), "[" + ce("c") + "," + ce("later-1")
                        + "," + ce("d") + "]");
        assertEquals(503, retry.status());
        assertEquals(List.of("a", "b", "c", "later-1"), processor.ids, "the rest of the batch waits for the retry");
    }

    @Test
    void everyRequestNeedsTheSharedSecret() throws Exception {
        headerChannel();
        EventGridInboundEndpoint e = endpoint();
        Map<String, String> none = Map.of("Content-Type", "application/cloudevents+json");
        assertThrows(SecurityException.class, () -> e.post("T1", "eg", none, Map.of(), ce("x")));
        Map<String, String> wrong = new HashMap<>(none);
        wrong.put("X-Sangam-Key", SECRET + "x");
        assertThrows(SecurityException.class, () -> e.post("T1", "eg", wrong, Map.of(), ce("x")));
        assertThrows(SecurityException.class, () -> e.options("T1", "eg", Map.of("WebHook-Request-Origin",
                "eventgrid.azure.net"), Map.of()));
        assertThrows(SecurityException.class, () -> e.post("T1", "eg", Map.of("aeg-event-type", "SubscriptionValidation"),
                Map.of(), validationBody("abc")));
        assertEquals(List.of(), processor.ids, "nothing reaches the processor unauthenticated");
    }

    @Test
    void theSecretCanTravelInTheQueryOfTheSubscriptionUrl() throws Exception {
        deploy("egq", "eventgrid", "inbound", "\"authSecretRef\":\"secret://eg/in\",\"authQueryParam\":\"code\"");
        EventGridInboundEndpoint e = endpoint();
        Map<String, String> h = Map.of("Content-Type", "application/cloudevents+json");
        assertEquals(200, e.post("T1", "egq", h, Map.of("code", SECRET), ce("q-1")).status());
        assertThrows(SecurityException.class, () -> e.post("T1", "egq", h, Map.of("code", "nope"), ce("q-2")));
        assertThrows(SecurityException.class, () -> e.post("T1", "egq", auth("Content-Type",
                "application/cloudevents+json"), Map.of(), ce("q-3")), "the header does not count for a query channel");
    }

    @Test
    void anUnresolvableOrBlankSecretRefusesEverything() throws Exception {
        deploy("eg0", "eventgrid", "inbound", "\"authSecretRef\":\"secret://eg/blank\",\"authHeader\":\"X-Sangam-Key\"");
        deploy("eg1", "eventgrid", "inbound", "\"authSecretRef\":\"secret://eg/none\",\"authHeader\":\"X-Sangam-Key\"");
        deploy("eg2", "eventgrid", "inbound", "\"authHeader\":\"X-Sangam-Key\"");
        Map<String, String> blankKey = Map.of("Content-Type", "application/cloudevents+json", "X-Sangam-Key", " ");
        for (String k : List.of("eg0", "eg1", "eg2")) {
            assertThrows(SecurityException.class, () -> endpoint().post("T1", k, blankKey, Map.of(), ce("z")), k);
        }
    }

    @Test
    void theCloudEventsHandshakeEchoesAnAllowedOrigin() throws Exception {
        headerChannel();
        EventGridInboundEndpoint.Answer a = endpoint().options("T1", "eg", auth("WebHook-Request-Origin",
                "eventgrid.azure.net"), Map.of());
        assertEquals(200, a.status());
        assertEquals("eventgrid.azure.net", a.headers().get("WebHook-Allowed-Origin"));
        assertEquals(403, endpoint().options("T1", "eg", auth("WebHook-Request-Origin", "evil.example"), Map.of()).status());
        assertEquals(400, endpoint().options("T1", "eg", auth(), Map.of()).status());
    }

    // ---- code scanning #290 #291 #295 #296: a refusal is a code and a fixed message, never the sender's text -----------

    @Test
    void aRefusalCarriesAStableCodeAndAFixedMessage() throws Exception {
        headerChannel();
        EventGridInboundEndpoint.Answer noOrigin = endpoint().options("T1", "eg", auth(), Map.of());
        assertEquals(400, noOrigin.status());
        assertTrue(noOrigin.refused());
        assertEquals(EventGridInboundEndpoint.CODE_ORIGIN_REQUIRED, noOrigin.code());

        String evil = "evil\"<script>.example";
        EventGridInboundEndpoint.Answer badOrigin = endpoint().options("T1", "eg", auth("WebHook-Request-Origin", evil),
                Map.of());
        assertEquals(403, badOrigin.status());
        assertEquals(EventGridInboundEndpoint.CODE_ORIGIN_NOT_ALLOWED, badOrigin.code());
        assertEquals("The request origin is not allowed for this channel", badOrigin.body());
        assertTrue(badOrigin.headers().isEmpty(), "the refused origin is not echoed in a header either");

        EventGridInboundEndpoint.Answer ok = endpoint().options("T1", "eg", auth("WebHook-Request-Origin",
                "eventgrid.azure.net"), Map.of());
        assertEquals(false, ok.refused());
        assertEquals(null, ok.code());
    }

    @Test
    void anEventToOfferAgainIsRefusedWithoutItsIdOrTheReason() throws Exception {
        headerChannel();
        EventGridInboundEndpoint.Answer retry = endpoint().post("T1", "eg",
                auth("Content-Type", "application/cloudevents+json"), Map.of(), ce("later-\\\"<script>"));
        assertEquals(List.of("later-\"<script>"), processor.ids, "the event reached the processor with its id");
        assertEquals(503, retry.status());
        assertEquals(EventGridInboundEndpoint.CODE_REDELIVERY_REQUIRED, retry.code());
        assertEquals(Integer.valueOf(30), retry.retryAfterSeconds());
        assertEquals("30", retry.headers().get("Retry-After"));
        assertEquals("An event of this delivery could not be accepted yet; send the delivery again", retry.body());
        assertTrue(!retry.body().contains("script") && !retry.body().contains("later") && !retry.body().contains("LOCKED"),
                "neither the sender's event id nor the processor's reason");
    }

    @Test
    void anAnswerIsEitherASuccessOrARefusalWithACode() {
        assertThrows(IllegalArgumentException.class, () -> new EventGridInboundEndpoint.Answer(503, Map.of(), "text", null, null),
                "an error without a code cannot be built");
        assertThrows(IllegalArgumentException.class, () -> new EventGridInboundEndpoint.Answer(200, Map.of(), "", "CODE", null));
    }

    @Test
    void theBodyIsJudgedOnlyAfterTheChannelAuthenticated() throws Exception {
        headerChannel();
        EventGridInboundEndpoint e = endpoint();
        Map<String, String> none = Map.of("Content-Type", "application/cloudevents+json");
        assertThrows(SecurityException.class, () -> e.post("T1", "eg", none, Map.of(), ""), "empty body, no secret");
        assertThrows(SecurityException.class, () -> e.post("T1", "eg", none, Map.of(), null));
        String oversized = "x".repeat(processor.maxPayloadBytes() + 1);
        assertThrows(SecurityException.class, () -> e.post("T1", "eg", none, Map.of(), oversized), "oversized, no secret");
        // authenticated, the same bodies are refused for what they are
        Map<String, String> ok = auth("Content-Type", "application/cloudevents+json");
        assertEquals(422, assertThrows(PortsProblem.class, () -> e.post("T1", "eg", ok, Map.of(), "")).status());
        assertEquals(413, assertThrows(PortsProblem.class, () -> e.post("T1", "eg", ok, Map.of(), oversized)).status());
        assertTrue(processor.ids.isEmpty());
    }

    private static String validationBody(String code) {
        return "[{\"id\":\"v-1\",\"topic\":\"/subscriptions/x/resourceGroups/y\",\"subject\":\"\",\"eventType\":\""
                + EventGridInboundEndpoint.VALIDATION_EVENT + "\",\"eventTime\":\"2026-11-12T10:00:00Z\","
                + "\"data\":{\"validationCode\":\"" + code + "\",\"validationUrl\":\"https://example.invalid/v\"},"
                + "\"dataVersion\":\"1\",\"metadataVersion\":\"1\"}]";
    }

    @Test
    void theEventGridSchemaValidationEventIsAnsweredWithItsCode() throws Exception {
        headerChannel();
        EventGridInboundEndpoint.Answer a = endpoint().post("T1", "eg", auth("aeg-event-type", "SubscriptionValidation",
                "Content-Type", "application/json"), Map.of(), validationBody("512d38b6-c7b8-40c8-89fe-f46f9e9622b6"));
        assertEquals(200, a.status());
        assertEquals("512d38b6-c7b8-40c8-89fe-f46f9e9622b6", Json.tree(a.body()).path("validationResponse").asText());
        assertEquals(List.of(), processor.ids, "a handshake is not an event");
        assertThrows(PortsProblem.class, () -> endpoint().post("T1", "eg", auth("aeg-event-type",
                "SubscriptionValidation"), Map.of(), validationBody("<script>")));
    }

    @Test
    void eventGridSchemaEventsBecomeCloudEvents() throws Exception {
        headerChannel();
        String body = "[{\"id\":\"g-1\",\"topic\":\"/subscriptions/s/topics/orders\",\"subject\":\"orders/7\","
                + "\"eventType\":\"OrderPlaced\",\"eventTime\":\"2026-11-12T10:00:00Z\",\"data\":{\"order\":7},"
                + "\"dataVersion\":\"1\"}]";
        assertEquals(200, endpoint().post("T1", "eg", auth("aeg-event-type", "Notification", "Content-Type",
                "application/json"), Map.of(), body).status());
        JsonNode ce = Json.tree(processor.bodies.get(0));
        assertEquals("1.0", ce.path("specversion").asText());
        assertEquals("g-1", ce.path("id").asText());
        assertEquals("OrderPlaced", ce.path("type").asText());
        assertEquals("/subscriptions/s/topics/orders", ce.path("source").asText());
        assertEquals("orders/7", ce.path("subject").asText());
        assertEquals("2026-11-12T10:00:00Z", ce.path("time").asText());
        assertEquals(7, ce.path("data").path("order").asInt());
    }

    @Test
    void malformedDeliveriesAndOtherChannelsAreRefused() throws Exception {
        headerChannel();
        deploy("wh", "webhook", "inbound", "");
        deploy("out", "eventgrid", "outbound", "");
        EventGridInboundEndpoint e = endpoint();
        Map<String, String> ceHeaders = auth("Content-Type", "application/cloudevents+json");
        assertThrows(PortsProblem.class, () -> e.post("T1", "eg", ceHeaders, Map.of(), "not json"));
        assertThrows(PortsProblem.class, () -> e.post("T1", "eg", ceHeaders, Map.of(), "{\"id\":\"x\"}"));
        assertThrows(PortsProblem.class, () -> e.post("T1", "eg", ceHeaders, Map.of(), ""));
        assertThrows(PortsProblem.class, () -> e.post("T1", "eg", auth("Content-Type", "application/json"), Map.of(), "[]"));
        assertThrows(IllegalArgumentException.class, () -> e.post("T1", "missing", ceHeaders, Map.of(), ce("m")));
        assertThrows(IllegalArgumentException.class, () -> e.post("T1", "wh", ceHeaders, Map.of(), ce("m")));
        assertThrows(IllegalArgumentException.class, () -> e.post("T1", "out", ceHeaders, Map.of(), ce("m")));
        assertTrue(processor.ids.isEmpty());
    }
}
