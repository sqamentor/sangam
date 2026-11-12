// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.support.Json;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CloudEvents 1.0 for event-gateway channels (M-065, R16, SANGAM.md section 6): the JSON event format in
 * <b>structured</b> content mode (the default) and <b>binary</b> content mode (opt-in per channel), in and out.
 *
 * <p>A channel takes part when {@code serializerType} (outbound) or {@code deserializerType} (inbound) is
 * {@value #TYPE}. {@code properties.cloudEventsMode} is {@code structured} (default) or {@code binary}; a channel
 * without {@value #TYPE} keeps the Sangam JSON format unchanged.
 *
 * <ul>
 *   <li><b>Structured:</b> the message body is the CloudEvent JSON object ({@code specversion, id, source, type,
 *       datacontenttype}, extensions, {@code data}); the transport content type is {@value #STRUCTURED_CONTENT_TYPE}
 *       where the transport has one.</li>
 *   <li><b>Binary:</b> the body is the event data; every attribute travels as a transport header named
 *       {@code <prefix><attribute>}. Prefixes follow the protocol bindings: HTTP and NATS {@code ce-}, Kafka
 *       {@code ce_}, AMQP {@code cloudEvents_}. JMS has no CloudEvents binding; JMS property names cannot hold
 *       {@code -} or {@code :}, so JMS uses the AMQP {@code cloudEvents_} prefix (as the integration JmsPublisher does).
 *       Transports with message attributes but no binding (SQS, SNS, Service Bus, Pub/Sub) use {@code ce-}.</li>
 *   <li><b>Receiving</b> detects the mode per message, as the bindings require: a {@code specversion} header under any
 *       of the prefixes means binary, otherwise the body must be a structured CloudEvent.</li>
 * </ul>
 * The CloudEvents {@code id} is the message id (consumers deduplicate on it, R38); {@code type} is the event
 * definition key; the extension {@value #TENANT_EXTENSION} carries the tenant (a claim only: R38 tenant rules apply).
 */
public final class CloudEventsFormat {

    public static final String TYPE = "cloudevents";
    public static final String SPEC_VERSION = "1.0";
    public static final String STRUCTURED_CONTENT_TYPE = "application/cloudevents+json";
    public static final String DATA_CONTENT_TYPE = "application/json";
    public static final String TENANT_EXTENSION = "sangamtenant";
    /** Channel property that selects the content mode. */
    public static final String MODE_PROPERTY = "cloudEventsMode";
    /** Channel property for the CloudEvents {@code source}; default {@code urn:sangam:channel:<channelKey>}. */
    public static final String SOURCE_PROPERTY = "cloudEventsSource";

    /** Header prefixes recognised when receiving, lower case (headers are compared case-insensitively). */
    private static final String[] PREFIXES = {"ce-", "ce_", "cloudevents_", "cloudevents:"};

    public enum Mode { STRUCTURED, BINARY }

    /** A CloudEvent split into its context attributes (no prefix) and its data. */
    public record Event(Map<String, String> attributes, JsonNode data) {
        public String id() {
            return attributes.get("id");
        }

        public String type() {
            return attributes.get("type");
        }
    }

    private CloudEventsFormat() {
    }

    /** True when the channel exchanges CloudEvents (outbound: serializer, inbound: deserializer). */
    public static boolean enabled(ChannelDefinition ch) {
        String t = ch.direction() == ChannelDefinition.Direction.OUTBOUND ? ch.serializerType() : ch.deserializerType();
        return TYPE.equalsIgnoreCase(t);
    }

    /** The channel's content mode; refuses an unknown value (a deploy error, not a surprise at the first message). */
    public static Mode mode(ChannelDefinition ch) {
        String m = ch.property(MODE_PROPERTY, "structured").trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "structured" -> Mode.STRUCTURED;
            case "binary" -> Mode.BINARY;
            default -> throw new IllegalArgumentException("channel " + ch.key() + ": " + MODE_PROPERTY + " '" + m
                    + "' is not supported (structured, binary)");
        };
    }

    public static String source(ChannelDefinition ch) {
        return ch.property(SOURCE_PROPERTY, "urn:sangam:channel:" + ch.key());
    }

    /** Context attributes of an outbound gateway event (deterministic: no time attribute). */
    public static Map<String, String> attributes(ChannelDefinition ch, String tenantId, String eventKey, String messageId) {
        return attributes(source(ch), tenantId, eventKey, messageId);
    }

    /** Context attributes with an explicit {@code source}. */
    public static Map<String, String> attributes(String source, String tenantId, String eventKey, String messageId) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("specversion", SPEC_VERSION);
        a.put("id", messageId);
        a.put("source", source);
        a.put("type", eventKey);
        a.put("datacontenttype", DATA_CONTENT_TYPE);
        if (tenantId != null && !tenantId.isEmpty()) {
            a.put(TENANT_EXTENSION, tenantId);
        }
        return a;
    }

    /** Structured mode: the CloudEvent JSON object with {@code data} = the payload object. */
    public static String structured(Map<String, String> attributes, Map<String, Object> payload) {
        ObjectNode o = Json.object();
        attributes.forEach(o::put);
        o.set("data", Json.MAPPER.valueToTree(payload));
        return Json.write(o);
    }

    /** Binary mode: the transport headers for {@code attributes} under {@code prefix}. */
    public static Map<String, String> headers(Map<String, String> attributes, String prefix) {
        Map<String, String> h = new LinkedHashMap<>();
        attributes.forEach((k, v) -> h.put(prefix + k, v));
        return h;
    }

    /** True when the (case-insensitive) headers carry a binary-mode CloudEvent. */
    public static boolean isBinary(Map<String, String> lowerCaseHeaders) {
        return attribute(lowerCaseHeaders, "specversion") != null;
    }

    /**
     * Decodes one received message: binary when a {@code specversion} header is present, else structured. Throws
     * {@link IllegalArgumentException} for anything that is not a CloudEvents 1.0 event with {@code id},
     * {@code source} and {@code type} (the processor turns it into a REJECTED result, never a redelivery).
     *
     * @param lowerCaseHeaders transport headers with lower-case names
     */
    public static Event decode(String raw, Map<String, String> lowerCaseHeaders) {
        Map<String, String> attrs = new LinkedHashMap<>();
        JsonNode data;
        if (isBinary(lowerCaseHeaders)) {
            lowerCaseHeaders.forEach((k, v) -> {
                for (String p : PREFIXES) {
                    if (k.startsWith(p) && k.length() > p.length() && v != null) {
                        attrs.putIfAbsent(k.substring(p.length()), v);
                    }
                }
            });
            String ct = attrs.getOrDefault("datacontenttype", lowerCaseHeaders.getOrDefault("content-type", DATA_CONTENT_TYPE));
            if (!isJson(ct)) {
                throw new IllegalArgumentException("CloudEvent datacontenttype '" + ct + "' is not JSON");
            }
            data = Json.tree(raw);
        } else {
            JsonNode root = Json.tree(raw);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("not a structured CloudEvent (no specversion header, body is not a JSON object)");
            }
            root.fields().forEachRemaining(e -> {
                if (!e.getKey().equals("data") && !e.getKey().equals("data_base64")) {
                    attrs.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().isValueNode() ? e.getValue().asText() : Json.write(e.getValue()));
                }
            });
            if (root.has("data_base64")) {
                throw new IllegalArgumentException("CloudEvent data_base64 is not supported (JSON data only)");
            }
            String ct = attrs.getOrDefault("datacontenttype", DATA_CONTENT_TYPE);
            if (!isJson(ct)) {
                throw new IllegalArgumentException("CloudEvent datacontenttype '" + ct + "' is not JSON");
            }
            data = root.get("data");
        }
        if (!SPEC_VERSION.equals(attrs.get("specversion"))) {
            throw new IllegalArgumentException("CloudEvent specversion '" + attrs.get("specversion") + "' is not 1.0");
        }
        for (String required : new String[]{"id", "source", "type"}) {
            String v = attrs.get(required);
            if (v == null || v.isBlank()) {
                throw new IllegalArgumentException("CloudEvent attribute '" + required + "' missing");
            }
        }
        return new Event(Map.copyOf(attrs), data == null ? Json.object() : data);
    }

    private static String attribute(Map<String, String> lowerCaseHeaders, String name) {
        for (String p : PREFIXES) {
            String v = lowerCaseHeaders.get(p + name);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static boolean isJson(String contentType) {
        String ct = contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        return ct.equals("application/json") || ct.endsWith("+json") || ct.equals("text/json");
    }
}
