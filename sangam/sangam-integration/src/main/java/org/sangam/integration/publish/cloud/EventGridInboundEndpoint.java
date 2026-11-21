// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.cloud;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.sangam.integration.connector.Json;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.events.pipeline.CloudEventsFormat;
import org.sangam.ports.support.PortsProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sangam.core.security.LogSafe;

/**
 * Owner decision 189 (SL-170): Azure Event Grid push delivery into an inbound {@code eventgrid} channel. The channel is
 * the opt-in (none deployed = nothing accepted) and cannot deploy without authentication (ChannelAdapters#validate),
 * chosen by {@code properties.authMode}; every request, handshakes included, is authenticated and both modes fail
 * closed:
 * <ul>
 *   <li>{@code sharedSecret} (default, decision 189): {@code properties.authSecretRef} plus exactly one of
 *       {@code properties.authHeader} (an Event Grid delivery attribute header whose value is the secret) or
 *       {@code properties.authQueryParam} (the secret in the subscription's endpoint URL). The comparison is
 *       constant-time over SHA-256 digests; a secret reference that does not resolve refuses everything.</li>
 *   <li>{@code entra} (decision 219): the Microsoft Entra bearer token Event Grid sends to a secured webhook, checked by
 *       {@link EntraTokenValidator} against {@code properties.entraTenantId}, {@code entraAudience} and
 *       {@code entraRequiredRole}. When the tenant's signing keys cannot be fetched the answer is 503 (retried).</li>
 * </ul>
 *
 * <ul>
 *   <li>{@link #options}: the CloudEvents 1.0 webhook abuse-protection handshake. {@code WebHook-Request-Origin} must be
 *       one of {@code properties.allowedOrigins} (comma-separated, default {@value #DEFAULT_ORIGIN}); the answer echoes
 *       it in {@code WebHook-Allowed-Origin}. The asynchronous callback form is not used.</li>
 *   <li>{@link #post}: {@code application/cloudevents+json} (one event) or {@code application/cloudevents-batch+json};
 *       or the Event Grid event schema ({@code application/json} array), whose
 *       {@code Microsoft.EventGrid.SubscriptionValidationEvent} is answered with {@code {"validationResponse": code}}
 *       and whose other events are mapped to CloudEvents (id, source = topic, type = eventType, subject, time =
 *       eventTime, data). Each event goes to {@link InboundEventProcessor#deliver} with its id as the message id; when
 *       any event must be offered again the answer is 503, so Event Grid retries the request (stored events come back
 *       as duplicates).</li>
 * </ul>
 */
public final class EventGridInboundEndpoint {

    public static final String DEFAULT_ORIGIN = "eventgrid.azure.net";
    static final String VALIDATION_EVENT = "Microsoft.EventGrid.SubscriptionValidationEvent";
    static final String BATCH_CONTENT_TYPE = "application/cloudevents-batch+json";
    private static final Pattern VALIDATION_CODE = Pattern.compile("[A-Za-z0-9-]{1,128}");
    private static final Logger LOG = LoggerFactory.getLogger(EventGridInboundEndpoint.class);

    /** 400: the handshake carries no {@code WebHook-Request-Origin}. */
    public static final String CODE_ORIGIN_REQUIRED = "WEBHOOK_ORIGIN_REQUIRED";
    /** 403: the handshake's origin is not one the channel allows. */
    public static final String CODE_ORIGIN_NOT_ALLOWED = "WEBHOOK_ORIGIN_NOT_ALLOWED";
    /** 503: an event of the delivery must be offered again. */
    public static final String CODE_REDELIVERY_REQUIRED = "EVENT_REDELIVERY_REQUIRED";
    /** 503: the delivery token cannot be checked now (signing keys unavailable). */
    public static final String CODE_AUTHENTICATION_UNAVAILABLE = "WEBHOOK_AUTHENTICATION_UNAVAILABLE";

    /**
     * What the REST layer answers. A 2xx answer carries the headers and the body this endpoint built (the handshake
     * echo, the validation response). Every other answer is a refusal ({@link #refused}): a stable {@code code} and,
     * as its body, a fixed message. A refusal never carries text from the request or from an exception (this route is
     * reachable before the caller is authenticated; that detail goes to the log), and the caller renders it as a
     * problem, never as a body of its own.
     */
    public record Answer(int status, Map<String, String> headers, String body, String code, Integer retryAfterSeconds) {
        public Answer {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? "" : body;
            boolean success = status >= 200 && status < 300;
            if (success == (code != null)) {
                throw new IllegalArgumentException("a 2xx answer has no code and every other answer has one");
            }
        }

        /** A 2xx answer. */
        public static Answer ok(Map<String, String> headers, String body) {
            return new Answer(200, headers, body, null, null);
        }

        /** A refusal; {@code retryAfterSeconds} (may be null) is also sent as {@code Retry-After}. */
        public static Answer refusal(int status, String code, String fixedMessage, Integer retryAfterSeconds) {
            return new Answer(status, retryAfterSeconds == null ? Map.of()
                    : Map.of("Retry-After", Integer.toString(retryAfterSeconds)), fixedMessage, code, retryAfterSeconds);
        }

        public boolean refused() {
            return code != null;
        }
    }

    private final EventRegistry registry;
    private final InboundEventProcessor processor;
    private final ChannelAdapters.Environment env;
    private final EntraTokenValidator entra;

    /** Production: Entra signing keys are fetched through the channels' egress-controlled transport (allow-listed hosts). */
    public EventGridInboundEndpoint(EventRegistry registry, InboundEventProcessor processor, ChannelAdapters.Environment env) {
        this(registry, processor, env, new EntraTokenValidator(
                EntraTokenValidator.http(CloudChannels.transport(null, Objects.requireNonNull(env, "env"))), Clock.systemUTC()));
    }

    public EventGridInboundEndpoint(EventRegistry registry, InboundEventProcessor processor, ChannelAdapters.Environment env,
                                    EntraTokenValidator entra) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.env = Objects.requireNonNull(env, "env");
        this.entra = Objects.requireNonNull(entra, "entra");
    }

    /** The CloudEvents abuse-protection handshake (HTTP OPTIONS). */
    public Answer options(String tenant, String channelKey, Map<String, String> headers, Map<String, String> query) {
        ChannelDefinition ch = channel(tenant, channelKey);
        Map<String, String> h = lower(headers);
        Answer unavailable = authenticate(ch, h, query);
        if (unavailable != null) {
            return unavailable;
        }
        String origin = h.get("webhook-request-origin");
        if (origin == null || origin.isBlank()) {
            return Answer.refusal(400, CODE_ORIGIN_REQUIRED, "The WebHook-Request-Origin header is required", null);
        }
        String allowed = CloudChannels.prop(ch, "allowedOrigins", DEFAULT_ORIGIN);
        boolean ok = Arrays.stream(allowed.split(",")).map(String::trim)
                .anyMatch(a -> a.equalsIgnoreCase(origin.trim()));
        if (!ok) {
            return Answer.refusal(403, CODE_ORIGIN_NOT_ALLOWED, "The request origin is not allowed for this channel", null);
        }
        return Answer.ok(Map.of("WebHook-Allowed-Origin", origin.trim(), "Allow", "POST, OPTIONS"), "");
    }

    /**
     * A delivery (or the Event Grid schema validation event). The channel's authentication comes first: it reads the
     * headers and the query only, so the body (empty, oversized, malformed) is judged for an authenticated caller alone.
     */
    public Answer post(String tenant, String channelKey, Map<String, String> headers, Map<String, String> query, String body) {
        ChannelDefinition ch = channel(tenant, channelKey);
        Map<String, String> h = lower(headers);
        Answer unavailable = authenticate(ch, h, query);
        if (unavailable != null) {
            return unavailable;
        }
        if (body == null || body.isEmpty()) {
            throw new PortsProblem("INVALID_INPUT", 422, "channel " + channelKey + ": empty body");
        }
        if (body.length() > processor.maxPayloadBytes()) {
            throw PortsProblem.tooLarge("PAYLOAD_TOO_LARGE", "Event Grid delivery", body.length(), processor.maxPayloadBytes());
        }
        JsonNode root = Json.tree(body);
        if (root == null) {
            throw new PortsProblem("INVALID_INPUT", 422, "channel " + channelKey + ": the body is not JSON");
        }
        String type = h.getOrDefault("content-type", "application/json").toLowerCase(Locale.ROOT);
        List<JsonNode> events = new ArrayList<>();
        if (type.startsWith(CloudEventsFormat.STRUCTURED_CONTENT_TYPE)) {
            events.add(cloudEvent(root));
        } else if (type.startsWith(BATCH_CONTENT_TYPE)) {
            requireArray(root);
            root.forEach(e -> events.add(cloudEvent(e)));
        } else {
            requireArray(root);
            if ("subscriptionvalidation".equalsIgnoreCase(h.getOrDefault("aeg-event-type", ""))
                    || (root.size() > 0 && VALIDATION_EVENT.equals(root.get(0).path("eventType").asText()))) {
                return validation(root);
            }
            root.forEach(e -> events.add(fromEventGridSchema(e)));
        }
        for (JsonNode e : events) {
            Map<String, String> eh = new HashMap<>();
            eh.put("content-type", CloudEventsFormat.STRUCTURED_CONTENT_TYPE);
            InboundEventProcessor.Delivery d = processor.deliver(tenant, ch.key(), Json.write(e), eh, e.path("id").asText());
            if (!d.ack()) {
                // which event and why stay in the server log: the event id is the sender's text
                LOG.warn("Event Grid delivery on channel {}: an event is to be offered again ({})", LogSafe.text(ch.key()), LogSafe.text(d.reason()));
                return Answer.refusal(503, CODE_REDELIVERY_REQUIRED,
                        "An event of this delivery could not be accepted yet; send the delivery again", 30);
            }
        }
        return Answer.ok(Map.of(), "");
    }

    private static Answer validation(JsonNode root) {
        if (root.size() != 1 || !VALIDATION_EVENT.equals(root.get(0).path("eventType").asText())) {
            throw new PortsProblem("INVALID_INPUT", 422, "a subscription validation request carries exactly one "
                    + VALIDATION_EVENT);
        }
        String code = root.get(0).path("data").path("validationCode").asText("");
        if (!VALIDATION_CODE.matcher(code).matches()) {
            throw new PortsProblem("INVALID_INPUT", 422, "the validation code is missing or malformed");
        }
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("validationResponse", code);
        return Answer.ok(Map.of("Content-Type", "application/json"), Json.write(out));
    }

    /** One structured CloudEvent 1.0 with an id, a type and a source. */
    private static JsonNode cloudEvent(JsonNode e) {
        if (e == null || !e.isObject() || !CloudEventsFormat.SPEC_VERSION.equals(e.path("specversion").asText())
                || blank(e.path("id").asText()) || blank(e.path("type").asText()) || blank(e.path("source").asText())) {
            throw new PortsProblem("INVALID_INPUT", 422, "not a CloudEvent 1.0 with id, type and source");
        }
        return e;
    }

    /** An Event Grid schema event as a CloudEvent 1.0. */
    static JsonNode fromEventGridSchema(JsonNode e) {
        if (e == null || !e.isObject() || blank(e.path("id").asText()) || blank(e.path("eventType").asText())) {
            throw new PortsProblem("INVALID_INPUT", 422, "not an Event Grid event with id and eventType");
        }
        ObjectNode ce = Json.MAPPER.createObjectNode();
        ce.put("specversion", CloudEventsFormat.SPEC_VERSION);
        ce.put("id", e.path("id").asText());
        ce.put("type", e.path("eventType").asText());
        String topic = e.path("topic").asText("");
        ce.put("source", topic.isBlank() ? "eventgrid" : topic);
        putText(ce, "subject", e.path("subject"));
        putText(ce, "time", e.path("eventTime"));
        ce.put("datacontenttype", CloudEventsFormat.DATA_CONTENT_TYPE);
        if (e.has("data")) {
            ce.set("data", e.get("data"));
        }
        return ce;
    }

    private static void putText(ObjectNode ce, String name, JsonNode v) {
        if (v != null && v.isTextual() && !v.asText().isBlank()) {
            ce.put(name, v.asText());
        }
    }

    private ChannelDefinition channel(String tenant, String channelKey) {
        ChannelDefinition ch = registry.channel(tenant, channelKey).map(EventRegistry.DeployedChannel::definition)
                .orElseThrow(() -> new IllegalArgumentException("unknown channel " + channelKey));
        if (ch.direction() != ChannelDefinition.Direction.INBOUND || !"eventgrid".equals(ch.adapterType())) {
            throw new IllegalArgumentException("channel " + channelKey + " is not an inbound eventgrid channel");
        }
        return ch;
    }

    /**
     * Fails closed (SecurityException). Returns null when authenticated, or a 503 answer when the Entra signing keys
     * cannot be fetched (Event Grid retries a 503; it does not retry a 401).
     */
    private Answer authenticate(ChannelDefinition ch, Map<String, String> lowerHeaders, Map<String, String> query) {
        String mode = ch.properties().get(ChannelAdapters.EVENT_GRID_AUTH_MODE);
        if (mode != null && ChannelAdapters.EVENT_GRID_ENTRA.equals(mode.trim())) {
            EntraTokenValidator.Expected expected;
            try {
                expected = EntraTokenValidator.Expected.of(ch.properties());
            } catch (IllegalArgumentException e) {
                throw new SecurityException("channel " + ch.key() + ": inbound Event Grid Entra authentication is not"
                        + " configured: " + e.getMessage());
            }
            try {
                entra.validate(lowerHeaders.get("authorization"), expected);
                return null;
            } catch (EntraTokenValidator.KeysUnavailable e) {
                // the key set URI and the cause stay in the server log
                LOG.warn("Event Grid channel {}: the delivery token cannot be checked: {}", ch.key(), e.getMessage());
                return Answer.refusal(503, CODE_AUTHENTICATION_UNAVAILABLE,
                        "The delivery cannot be authenticated at the moment; send it again", 60);
            } catch (SecurityException e) {
                throw new SecurityException("channel " + ch.key() + ": " + e.getMessage());
            }
        }
        if (mode != null && !ChannelAdapters.EVENT_GRID_SHARED_SECRET.equals(mode.trim())) {
            throw new SecurityException("channel " + ch.key() + ": unknown inbound Event Grid authMode");
        }
        sharedSecret(ch, lowerHeaders, query);
        return null;
    }

    /** Fails closed: no secret configured or resolvable, no presented value, or a different value. */
    private void sharedSecret(ChannelDefinition ch, Map<String, String> lowerHeaders, Map<String, String> query) {
        String ref = ch.properties().get("authSecretRef");
        String header = ch.properties().get("authHeader");
        String param = ch.properties().get("authQueryParam");
        if (blank(ref) || blank(header) == blank(param)) {
            throw new SecurityException("channel " + ch.key() + ": inbound Event Grid authentication is not configured");
        }
        String secret = env.secret(ref.trim()).filter(v -> !v.isBlank()).orElseThrow(
                () -> new SecurityException("channel " + ch.key() + ": the authentication secret cannot be resolved"));
        String presented = !blank(header) ? lowerHeaders.get(header.trim().toLowerCase(Locale.ROOT))
                : query == null ? null : query.get(param.trim());
        if (presented == null || !MessageDigest.isEqual(sha256(presented), sha256(secret))) {
            throw new SecurityException("channel " + ch.key() + ": Event Grid delivery not authenticated");
        }
    }

    private static void requireArray(JsonNode root) {
        if (!root.isArray() || root.isEmpty()) {
            throw new PortsProblem("INVALID_INPUT", 422, "expected a non-empty JSON array of events");
        }
    }

    private static Map<String, String> lower(Map<String, String> headers) {
        Map<String, String> h = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null && v != null) {
                    h.put(k.toLowerCase(Locale.ROOT), v);
                }
            });
        }
        return h;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
