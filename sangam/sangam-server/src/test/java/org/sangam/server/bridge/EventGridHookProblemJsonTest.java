// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.sangam.integration.publish.cloud.EntraTokenValidator;
import org.sangam.integration.publish.cloud.EventGridInboundEndpoint;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.support.Json;
import org.sangam.rest.port.EventPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.web.EventFormController;
import org.sangam.rest.web.ProblemHandler;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Code scanning java/xss (#290, #291, #295, #296): the Event Grid hook is reachable before the caller is authenticated
 * at the Sangam layer, and its refusals were plain-text concatenations (the event id, the redelivery reason, the
 * message of the key-fetch exception) sent under {@code application/json}. The real controller, bridge, endpoint and
 * problem handler are wired here; every refusal must be an RFC 9457 problem document with a stable code and none of
 * the sender's or the exception's text.
 */
class EventGridHookProblemJsonTest {

    static final String SECRET = "eg-test-secret";
    static final String ENTRA_TENANT = "00000000-0000-4000-8000-000000000001";
    static final String KEY_FETCH_FAILURE = "Entra signing keys cannot be fetched from https://keys.internal.example/x:"
            + " ConnectException <b>10.1.2.3</b>";
    static final String ROUTE = "/api/v1/hooks/eventgrid/T1/";

    final Map<String, ChannelDefinition> channels = new HashMap<>();
    final List<String> delivered = new ArrayList<>();

    final EventRegistry registry = new EventRegistry(null, null, Duration.ZERO) {
        @Override
        public Optional<DeployedChannel> channel(String tenantId, String key) {
            return Optional.ofNullable(channels.get(key)).map(c -> new DeployedChannel(tenantId, 1, c));
        }
    };

    /** Ids starting with "later" are to be offered again; every other event is accepted. */
    final InboundEventProcessor processor = new InboundEventProcessor(null, null, null, null) {
        @Override
        public Delivery deliver(String tenant, String channelKey, String raw, Map<String, String> headers, String id) {
            delivered.add(id);
            return id.startsWith("later") ? new Delivery(Disposition.REDELIVER, null, "TENANT_NOT_ADMITTED: tenant " + tenant)
                    : new Delivery(Disposition.ACK, new Result(Outcome.ACCEPTED, "E", id, null), null);
        }
    };

    void deploy(String key, String properties) {
        channels.put(key, Json.read("{\"key\":\"" + key + "\",\"channelType\":\"eventgrid\",\"type\":\"inbound\","
                + "\"deserializerType\":\"cloudevents\",\"properties\":{" + properties + "}}", ChannelDefinition.class));
    }

    MockMvc mvc() {
        deploy("eg", "\"authSecretRef\":\"secret://eg/in\",\"authHeader\":\"X-Sangam-Key\"");
        deploy("ege", "\"authMode\":\"entra\",\"entraTenantId\":\"" + ENTRA_TENANT + "\",\"entraAudience\":\"api://hook\"");
        EntraTokenValidator noKeys = new EntraTokenValidator(uri -> {
            throw new EntraTokenValidator.KeysUnavailable(KEY_FETCH_FAILURE);
        }, Clock.systemUTC());
        EventGridInboundEndpoint endpoint = new EventGridInboundEndpoint(registry, processor,
                ChannelAdapters.Environment.of(Map.of("secret://eg/in", SECRET), Set.of()), noKeys);
        PortsEventBridge bridge = new PortsEventBridge(registry, null, null, null, new RestProperties(), false, endpoint);
        return mvc(bridge);
    }

    static MockMvc mvc(EventPort events) {
        return MockMvcBuilders.standaloneSetup(new EventFormController(events, null, null))
                .setControllerAdvice(new ProblemHandler()).build();
    }

    static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A token of the configured tenant and issuer; its key set is asked for before the signature is looked at. */
    static String entraToken() {
        return "Bearer " + b64("{\"typ\":\"JWT\",\"alg\":\"RS256\",\"kid\":\"k1\"}") + "."
                + b64("{\"tid\":\"" + ENTRA_TENANT + "\",\"iss\":\"https://login.microsoftonline.com/" + ENTRA_TENANT
                + "/v2.0\"}") + "." + b64("signature");
    }

    /** A structured CloudEvent whose id is given as JSON string content (already escaped). */
    static String ce(String jsonEscapedId) {
        return "{\"specversion\":\"1.0\",\"id\":\"" + jsonEscapedId + "\",\"type\":\"OrderPlaced\",\"source\":\"/orders\","
                + "\"data\":{}}";
    }

    static MockHttpServletRequestBuilder delivery(String channel, String body) {
        return post(ROUTE + channel).contentType("application/cloudevents+json").content(body);
    }

    /** The response is one RFC 9457 document: the media type, valid JSON, the status and the code. */
    static JsonNode problem(MockHttpServletResponse res, int status, String code) throws Exception {
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(status, res.getStatus(), body);
        assertNotNull(res.getContentType(), "a content type is set");
        assertTrue(res.getContentType().startsWith("application/problem+json"), res.getContentType());
        JsonNode json = org.sangam.integration.connector.Json.tree(body);
        assertNotNull(json, "the body is valid JSON: " + body);
        assertTrue(json.isObject(), body);
        assertEquals(code, json.path("code").asText(), body);
        assertEquals(status, json.path("status").asInt(), body);
        return json;
    }

    @Test
    void keysUnavailableIsA503ProblemWithRetryAfterAndNoExceptionText() throws Exception {
        MockHttpServletResponse res = mvc().perform(delivery("ege", ce("e-1")).header("Authorization", entraToken()))
                .andReturn().getResponse();
        JsonNode p = problem(res, 503, EventGridInboundEndpoint.CODE_AUTHENTICATION_UNAVAILABLE);
        assertEquals("60", res.getHeader("Retry-After"));
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        for (String leaked : List.of("keys.internal.example", "ConnectException", "10.1.2.3", "<b>", "cannot be fetched")) {
            assertFalse(body.contains(leaked), "the key-fetch failure stays in the log: " + leaked);
        }
        assertEquals("The delivery cannot be authenticated at the moment; send it again", p.path("detail").asText());
        assertTrue(delivered.isEmpty());
    }

    @Test
    void anUnacknowledgedDeliveryIsA503ProblemWithoutTheSendersEventId() throws Exception {
        MockHttpServletResponse res = mvc().perform(delivery("eg", ce("later-\\\"<script>alert(1)</script>"))
                .header("X-Sangam-Key", SECRET)).andReturn().getResponse();
        assertEquals(List.of("later-\"<script>alert(1)</script>"), delivered, "the event reached the processor");
        JsonNode p = problem(res, 503, EventGridInboundEndpoint.CODE_REDELIVERY_REQUIRED);
        assertEquals("30", res.getHeader("Retry-After"));
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("<script>"), "the event id is not in the answer, raw or otherwise: " + body);
        assertFalse(body.contains("later-"), body);
        assertFalse(body.contains("TENANT_NOT_ADMITTED"), "the redelivery reason stays in the log");
        assertEquals("An event of this delivery could not be accepted yet; send the delivery again", p.path("detail").asText());
    }

    @Test
    void aBadOriginIsA403ProblemThatDoesNotEchoTheOrigin() throws Exception {
        MockHttpServletResponse res = mvc().perform(options(ROUTE + "eg").header("X-Sangam-Key", SECRET)
                .header("WebHook-Request-Origin", "evil\"<script>.example")).andReturn().getResponse();
        problem(res, 403, EventGridInboundEndpoint.CODE_ORIGIN_NOT_ALLOWED);
        assertFalse(res.getContentAsString(StandardCharsets.UTF_8).contains("<script>"));
        assertNull(res.getHeader("WebHook-Allowed-Origin"));

        MockHttpServletResponse none = mvc().perform(options(ROUTE + "eg").header("X-Sangam-Key", SECRET))
                .andReturn().getResponse();
        problem(none, 400, EventGridInboundEndpoint.CODE_ORIGIN_REQUIRED);
    }

    @Test
    void anEmptyOrOversizedBodyIsJudgedOnlyAfterTheChannelAuthenticated() throws Exception {
        MockMvc mvc = mvc();
        problem(mvc.perform(post(ROUTE + "eg").contentType("application/cloudevents+json")).andReturn().getResponse(),
                401, "WEBHOOK_SIGNATURE_INVALID");
        String oversized = "x".repeat(InboundEventProcessor.DEFAULT_MAX_PAYLOAD_BYTES + 1);
        problem(mvc.perform(delivery("eg", oversized)).andReturn().getResponse(), 401, "WEBHOOK_SIGNATURE_INVALID");
        problem(mvc.perform(post(ROUTE + "eg").contentType("application/cloudevents+json").header("X-Sangam-Key", SECRET))
                .andReturn().getResponse(), 422, "INVALID_INPUT");
        problem(mvc.perform(delivery("eg", oversized).header("X-Sangam-Key", SECRET)).andReturn().getResponse(),
                413, "PAYLOAD_TOO_LARGE");
        assertTrue(delivered.isEmpty());
    }

    @Test
    void theSuccessAnswersKeepTheirProtocolShape() throws Exception {
        MockMvc mvc = mvc();
        MockHttpServletResponse accepted = mvc.perform(delivery("eg", ce("e-7")).header("X-Sangam-Key", SECRET))
                .andReturn().getResponse();
        assertEquals(200, accepted.getStatus());
        assertEquals(0, accepted.getContentAsByteArray().length, "an accepted delivery has no body");
        assertEquals(List.of("e-7"), delivered);

        MockHttpServletResponse handshake = mvc.perform(options(ROUTE + "eg").header("X-Sangam-Key", SECRET)
                .header("WebHook-Request-Origin", EventGridInboundEndpoint.DEFAULT_ORIGIN)).andReturn().getResponse();
        assertEquals(200, handshake.getStatus());
        assertEquals(EventGridInboundEndpoint.DEFAULT_ORIGIN, handshake.getHeader("WebHook-Allowed-Origin"));
        assertEquals(0, handshake.getContentAsByteArray().length);

        String validation = "[{\"id\":\"v-1\",\"topic\":\"/subscriptions/x\",\"subject\":\"\",\"eventType\":"
                + "\"Microsoft.EventGrid.SubscriptionValidationEvent\",\"eventTime\":\"2026-11-12T10:00:00Z\","
                + "\"data\":{\"validationCode\":\"512d38b6-c7b8-40c8-89fe-f46f9e9622b6\"},\"dataVersion\":\"1\"}]";
        MockHttpServletResponse validated = mvc.perform(post(ROUTE + "eg").contentType("application/json")
                .header("aeg-event-type", "SubscriptionValidation").header("X-Sangam-Key", SECRET).content(validation))
                .andReturn().getResponse();
        assertEquals(200, validated.getStatus());
        assertTrue(validated.getContentType().startsWith("application/json"), validated.getContentType());
        assertEquals("512d38b6-c7b8-40c8-89fe-f46f9e9622b6", org.sangam.integration.connector.Json
                .tree(validated.getContentAsString(StandardCharsets.UTF_8)).path("validationResponse").asText());
    }

    @Test
    void aPortThatReturnsAnErrorAsAnAnswerIsNotWrittenOut() throws Exception {
        EventPort errorAsAnswer = (EventPort) Proxy.newProxyInstance(EventPort.class.getClassLoader(),
                new Class<?>[]{EventPort.class}, (proxy, method, args) -> {
                    if (method.getName().equals("eventGridHook")) {
                        return new EventPort.HookAnswer(503, Map.of("Retry-After", "30"), "event <script> is to be offered again");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        MockHttpServletResponse res = mvc(errorAsAnswer).perform(delivery("eg", ce("e-1"))).andReturn().getResponse();
        problem(res, 500, "INTERNAL_ERROR");
        assertFalse(res.getContentAsString(StandardCharsets.UTF_8).contains("<script>"));
    }
}
