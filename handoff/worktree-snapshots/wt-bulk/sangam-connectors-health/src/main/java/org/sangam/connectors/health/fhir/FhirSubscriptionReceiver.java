// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.integration.connector.Json;
import org.sangam.integration.inbox.ChannelBinding;
import org.sangam.integration.inbox.InboundMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * FHIR Subscription notifications → Sangam inbox (US-HLT-078, VI.2.6).
 *
 * <p>Accepts both forms on a rest-hook endpoint:
 * <ul>
 *   <li><b>R5 Backport IG for R4</b> (topic-based): a Bundle of type {@code history} whose first entry is a
 *       {@code Parameters} (or {@code SubscriptionStatus}) resource with {@code type} (handshake, heartbeat,
 *       event-notification, query-status), {@code subscription}, {@code topic}, {@code eventsSinceSubscriptionStart}
 *       and {@code notification-event} with {@code focus}. Handshakes and heartbeats produce no message.</li>
 *   <li><b>R4 classic</b> rest-hook: the body is the changed resource (payload {@code application/fhir+json}) or empty
 *       (payload none, "ping").</li>
 * </ul>
 * Each event becomes one {@link InboundMessage}: messageId = subscription id + event number (or a content hash), so a
 * redelivered notification is deduplicated by the inbox; eventType from the topic (or resource type) through
 * {@code eventTypes}; correlation key from {@code correlator} (default: the {@code urn:sangam:operation} identifier, else
 * the focus reference). The channel header secret is checked in constant time before anything is parsed.
 *
 * <p>Gap detection: when {@code eventsSinceSubscriptionStart} jumps, {@link Result#missedEvents()} tells the caller to
 * run a {@code $status} / search catch-up for that subscription.
 */
public final class FhirSubscriptionReceiver {

    /** Delivers to the inbox; wire it as {@code inboxService::receive}. */
    @FunctionalInterface
    public interface InboxSink {
        Object receive(ChannelBinding binding, InboundMessage message);
    }

    /** What a notification produced. */
    public record Result(int status, List<InboundMessage> messages, long missedEvents, String kind) {
    }

    private final InboxSink sink;
    private final ChannelBinding binding;
    private final byte[] channelSecret;
    private final String secretHeader;
    private final Map<String, String> eventTypes;
    private final Function<JsonNode, String> correlator;
    private final Map<String, Long> lastEvent = new ConcurrentHashMap<>();

    /**
     * @param sink          the inbox
     * @param binding       channel binding (source {@code fhir-sub:<server>}, tenant)
     * @param secretHeader  header the server sends ({@code Subscription.channel.header}), e.g. {@code Authorization}
     * @param channelSecret its expected value; null disables the check (simulator only)
     * @param eventTypes    topic URL or resource type → Sangam event type, e.g. {@code ClaimResponse} →
     *                      {@code sangam.ushealth.pasUpdate}
     * @param correlator    correlation key from the focus resource; null = default
     */
    public FhirSubscriptionReceiver(InboxSink sink, ChannelBinding binding, String secretHeader, String channelSecret,
                                    Map<String, String> eventTypes, Function<JsonNode, String> correlator) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.secretHeader = secretHeader == null ? "Authorization" : secretHeader;
        this.channelSecret = channelSecret == null ? null : channelSecret.getBytes(StandardCharsets.UTF_8);
        this.eventTypes = eventTypes == null ? Map.of() : Map.copyOf(eventTypes);
        this.correlator = correlator == null ? FhirSubscriptionReceiver::defaultCorrelation : correlator;
    }

    /** Handles one HTTP POST; {@code header} looks up request headers. Returns the HTTP status to answer. */
    public Result receive(Function<String, String> header, byte[] body) {
        if (channelSecret != null) {
            String got = header.apply(secretHeader);
            if (got == null || !MessageDigest.isEqual(channelSecret, got.getBytes(StandardCharsets.UTF_8))) {
                return new Result(401, List.of(), 0, "unauthenticated");
            }
        }
        if (body == null || body.length == 0) {
            return new Result(200, List.of(), 0, "ping");
        }
        JsonNode n = Json.tree(body);
        if (n == null || !n.has("resourceType")) {
            return new Result(400, List.of(), 0, "malformed");
        }
        List<InboundMessage> out = new ArrayList<>();
        long missed = 0;
        String kind;
        if ("Bundle".equals(n.path("resourceType").asText()) && isBackport(n)) {
            JsonNode status = n.path("entry").path(0).path("resource");
            kind = param(status, "type");
            String sub = refOrValue(status, "subscription");
            String topic = param(status, "topic");
            long since = Long.parseLong(firstNonNull(param(status, "eventsSinceSubscriptionStart"), "0"));
            if ("handshake".equals(kind) || "heartbeat".equals(kind) || "query-status".equals(kind)) {
                missed = gap(sub, since, 0);
                return new Result(200, List.of(), missed, kind);
            }
            List<JsonNode> events = notificationEvents(status);
            missed = gap(sub, since, events.size());
            int i = 0;
            for (JsonNode ev : events) {
                String focusRef = ev.path("focus").path("reference").asText(null);
                JsonNode focus = findEntry(n, focusRef);
                String evNo = firstNonNull(ev.path("eventNumber").asText(null), String.valueOf(since - events.size() + 1 + i));
                String type = eventTypes.getOrDefault(topic, eventTypes.getOrDefault(
                        focus == null ? "" : focus.path("resourceType").asText(), "fhir.notification"));
                String corr = focus == null ? focusRef : correlator.apply(focus);
                out.add(new InboundMessage(trim(sub + "#" + evNo), type, corr, binding.tenantId(),
                        Json.write(Map.of("subscription", String.valueOf(sub), "topic", String.valueOf(topic),
                                "eventNumber", evNo, "focusReference", String.valueOf(focusRef),
                                "resource", focus == null ? Map.of() : Json.toMap(focus)))));
                i++;
            }
        } else {
            kind = "resource";
            String type = eventTypes.getOrDefault(n.path("resourceType").asText(), "fhir.notification");
            String id = n.path("resourceType").asText() + "/" + n.path("id").asText() + "/_history/"
                    + n.path("meta").path("versionId").asText(hash(body));
            out.add(new InboundMessage(trim(id), type, correlator.apply(n), binding.tenantId(),
                    Json.write(Map.of("resource", Json.toMap(n)))));
        }
        for (InboundMessage m : out) {
            sink.receive(binding, m);
        }
        return new Result(200, out, missed, kind);
    }

    private long gap(String sub, long since, int inThisBundle) {
        if (sub == null || since <= 0) {
            return 0;
        }
        Long prev = lastEvent.put(sub, since);
        if (prev == null) {
            return 0;
        }
        long expected = prev + inThisBundle;
        return Math.max(0, since - expected);
    }

    private static boolean isBackport(JsonNode b) {
        JsonNode first = b.path("entry").path(0).path("resource");
        String rt = first.path("resourceType").asText();
        return "history".equals(b.path("type").asText())
                && ("SubscriptionStatus".equals(rt) || ("Parameters".equals(rt) && param(first, "type") != null));
    }

    /** Parameters.parameter[name] value (string, code, unsignedInt, canonical) or SubscriptionStatus field. */
    static String param(JsonNode status, String name) {
        if ("SubscriptionStatus".equals(status.path("resourceType").asText())) {
            JsonNode v = status.path(name);
            return v.isMissingNode() || v.isNull() ? null : v.isObject() ? v.path("reference").asText(null) : v.asText();
        }
        for (JsonNode p : status.path("parameter")) {
            if (name.equals(p.path("name").asText())) {
                for (String k : new String[]{"valueString", "valueCode", "valueUnsignedInt", "valueCanonical",
                        "valueUri", "valueInteger64", "valueInteger"}) {
                    if (p.has(k)) {
                        return p.get(k).asText();
                    }
                }
                if (p.has("valueReference")) {
                    return p.path("valueReference").path("reference").asText(null);
                }
            }
        }
        return null;
    }

    private static String refOrValue(JsonNode status, String name) {
        return param(status, name);
    }

    private static List<JsonNode> notificationEvents(JsonNode status) {
        List<JsonNode> out = new ArrayList<>();
        if ("SubscriptionStatus".equals(status.path("resourceType").asText())) {
            status.path("notificationEvent").forEach(out::add);
            return out;
        }
        for (JsonNode p : status.path("parameter")) {
            if ("notification-event".equals(p.path("name").asText())) {
                com.fasterxml.jackson.databind.node.ObjectNode ev = Json.MAPPER.createObjectNode();
                for (JsonNode part : p.path("part")) {
                    if ("focus".equals(part.path("name").asText())) {
                        ev.set("focus", part.path("valueReference"));
                    } else if ("event-number".equals(part.path("name").asText())) {
                        ev.put("eventNumber", part.path("valueString").asText(part.path("valueUnsignedInt").asText()));
                    }
                }
                out.add(ev);
            }
        }
        return out;
    }

    private static JsonNode findEntry(JsonNode bundle, String ref) {
        if (ref == null) {
            return null;
        }
        for (JsonNode e : bundle.path("entry")) {
            String full = e.path("fullUrl").asText("");
            JsonNode r = e.path("resource");
            String local = r.path("resourceType").asText() + "/" + r.path("id").asText();
            if (full.equals(ref) || full.endsWith("/" + ref) || local.equals(ref) || ref.endsWith("/" + local)) {
                return r;
            }
        }
        return null;
    }

    /** {@code urn:sangam:operation} identifier (own or of the referenced request), else the resource reference. */
    public static String defaultCorrelation(JsonNode res) {
        for (JsonNode i : res.path("identifier")) {
            if (FhirConnector.OP_SYSTEM.equals(i.path("system").asText())) {
                return i.path("value").asText();
            }
        }
        JsonNode reqId = res.path("request").path("identifier");
        if (FhirConnector.OP_SYSTEM.equals(reqId.path("system").asText())) {
            return reqId.path("value").asText();
        }
        return res.path("resourceType").asText() + "/" + res.path("id").asText();
    }

    private static String firstNonNull(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }

    private static String trim(String s) {
        return s.length() <= 255 ? s : hash(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] b) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
