// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.events.model.EventDefinition;
import org.sangam.ports.support.Json;
import org.sangam.ports.support.PortsProblem;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Inbound event pipeline (FLOWABLE-INTO-OPERATON FC-03). The SPI shapes follow Flowable's event-registry API
 * ({@code InboundEventDeserializer}, {@code InboundEventKeyDetector}, {@code InboundEventTenantDetector},
 * {@code InboundEventPayloadExtractor}, {@code InboundEventTransformer}; Apache-2.0), re-implemented here without
 * the Spring coupling. The default pipeline is JSON.
 */
public final class InboundEventPipeline {

    /** Raw transport payload to a parsed object. */
    @FunctionalInterface
    public interface InboundEventDeserializer<T> {
        T deserialize(String raw);
    }

    /** Finds the event definition key. */
    @FunctionalInterface
    public interface InboundEventKeyDetector<T> {
        String detectEventDefinitionKey(T event, Map<String, String> headers);
    }

    /** Finds a tenant in the event (only honoured for channels with {@code allowPayloadTenant}). */
    @FunctionalInterface
    public interface InboundEventTenantDetector<T> {
        String detectTenantId(T event, Map<String, String> headers);
    }

    /** Extracts typed payload values for the definition's fields. */
    @FunctionalInterface
    public interface InboundEventPayloadExtractor<T> {
        Map<String, Object> extractPayload(EventDefinition definition, T event);
    }

    /** Result of the pipeline. */
    public record EventInstance(String tenantId, String eventKey, String messageId, Map<String, Object> payload,
                                Map<String, Object> correlationValues, String businessKey, Map<String, String> headers) {
    }

    private final ChannelDefinition channel;
    private final String channelTenant;
    private final InboundEventDeserializer<JsonNode> deserializer;
    private final InboundEventKeyDetector<JsonNode> keyDetector;
    private final InboundEventTenantDetector<JsonNode> tenantDetector;
    private final InboundEventPayloadExtractor<JsonNode> payloadExtractor;
    private final Function<String, EventDefinition> definitions;

    private InboundEventPipeline(ChannelDefinition channel, String channelTenant, Function<String, EventDefinition> definitions) {
        this.channel = channel;
        this.channelTenant = channelTenant;
        this.definitions = definitions;
        this.deserializer = raw -> {
            JsonNode root = Json.tree(raw);
            return channel.payloadJsonPointer() == null ? root : Json.at(root, channel.payloadJsonPointer());
        };
        this.keyDetector = keyDetector(channel.channelEventKeyDetection());
        this.tenantDetector = (event, headers) -> {
            if (channel.tenantHeader() != null) {
                String t = headers.get(channel.tenantHeader().toLowerCase(java.util.Locale.ROOT));
                if (t != null) {
                    return t;
                }
            }
            if (channel.tenantJsonPointer() != null) {
                JsonNode n = Json.at(event, channel.tenantJsonPointer());
                return n == null ? null : n.asText();
            }
            return null;
        };
        this.payloadExtractor = InboundEventPipeline::extract;
    }

    /** JSON pipeline for a channel deployed in {@code channelTenant}; {@code definitions} resolves event keys. */
    public static InboundEventPipeline json(ChannelDefinition channel, String channelTenant,
                                            Function<String, EventDefinition> definitions) {
        String d = channel.deserializerType();
        if (d != null && !"json".equalsIgnoreCase(d)) {
            throw new IllegalArgumentException("channel " + channel.key() + ": deserializer '" + d
                    + "' is not supported (json only; XML and expression deserializers are not ported)");
        }
        return new InboundEventPipeline(channel, channelTenant, definitions);
    }

    public EventInstance process(String raw, Map<String, String> headers, String transportMessageId) {
        Map<String, String> h = lower(headers);
        JsonNode event = deserializer.deserialize(raw);
        if (event == null) {
            throw new IllegalArgumentException("channel " + channel.key() + ": payload pointer matched nothing");
        }
        String key = keyDetector.detectEventDefinitionKey(event, h);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("channel " + channel.key() + ": no event key detected");
        }
        EventDefinition def = definitions.apply(key);
        if (def == null) {
            throw new IllegalArgumentException("channel " + channel.key() + ": unknown event definition '" + key + "'");
        }
        String tenant = resolveTenant(tenantDetector.detectTenantId(event, h));
        Map<String, Object> payload = payloadExtractor.extractPayload(def, event);
        Map<String, Object> correlation = new LinkedHashMap<>();
        for (EventDefinition.Field f : def.correlationParameters()) {
            Object v = coerce(Json.at(event, f.name()), f.type(), f.name());
            if (v == null && f.isRequired()) {
                throw new IllegalArgumentException("event " + key + ": correlation parameter '" + f.name() + "' missing");
            }
            if (v != null) {
                correlation.put(f.name(), v);
            }
        }
        if (def.startUniqueReference() != null && correlation.get(def.startUniqueReference()) == null) {
            // FW-32: the unique-start reference is implicitly required; rejected at intake, not at dispatch
            throw new IllegalArgumentException("event " + key + ": unique start reference '" + def.startUniqueReference() + "' missing");
        }
        String businessKey = null;
        if (def.businessKeyField() != null) {
            JsonNode bk = Json.at(event, def.businessKeyField());
            businessKey = bk == null || bk.isNull() ? null : bk.asText();
        }
        String messageId = transportMessageId;
        if (channel.messageIdJsonPointer() != null) {
            JsonNode id = Json.at(event, channel.messageIdJsonPointer());
            if (id != null && !id.isNull()) {
                messageId = id.asText();
            }
        }
        if (messageId == null) {
            messageId = h.get("message-id");
        }
        if (messageId == null) {
            throw new IllegalArgumentException("channel " + channel.key() + ": no message id (set messageIdJsonPointer or a message-id header)");
        }
        return new EventInstance(tenant, key, messageId, payload, correlation, businessKey, h);
    }

    /**
     * FW-29 (Flowable's payload tenant detection lets an event pick any tenant): the channel tenant wins; a different
     * detected tenant is honoured only when the channel has {@code allowPayloadTenant=true} <b>and</b> the tenant is in
     * the channel's {@code properties.allowedTenants} (comma list, or {@code *} as an explicit admin choice). Blank
     * detected values count as "not detected"; the default tenant is {@code null} or {@code ""} interchangeably.
     */
    private String resolveTenant(String detected) {
        String d = detected == null || detected.isBlank() ? null : detected.trim();
        if (d == null || d.equals(channelTenant) || (channelTenant == null && d.isEmpty())) {
            return channelTenant;
        }
        if (channel.payloadTenantAllowed() && tenantAllowed(channel, d)) {
            return d;
        }
        throw new PortsProblem("TENANT_MISMATCH", 403, "channel " + channel.key() + " is bound to tenant " + channelTenant
                + " but the event names tenant " + d + (channel.payloadTenantAllowed() ? ", which is not in allowedTenants" : "")
                + " (R38)");
    }

    /** True when {@code tenant} is listed in the channel's {@code allowedTenants} property ({@code *} = any). */
    public static boolean tenantAllowed(ChannelDefinition channel, String tenant) {
        String list = channel.property("allowedTenants", "");
        if ("*".equals(list.trim())) {
            return true;
        }
        for (String t : list.split(",")) {
            if (t.trim().equals(tenant)) {
                return true;
            }
        }
        return false;
    }

    private static InboundEventKeyDetector<JsonNode> keyDetector(ChannelDefinition.KeyDetection d) {
        if (d == null) {
            return (e, h) -> {
                JsonNode n = e.get("eventKey");
                return n == null ? h.get("event-key") : n.asText();
            };
        }
        if (d.fixedValue() != null) {
            return (e, h) -> d.fixedValue();
        }
        if (d.jsonField() != null) {
            return (e, h) -> {
                JsonNode n = e.get(d.jsonField());
                return n == null ? null : n.asText();
            };
        }
        if (d.jsonPointerExpression() != null) {
            return (e, h) -> {
                JsonNode n = Json.at(e, d.jsonPointerExpression());
                return n == null ? null : n.asText();
            };
        }
        if (d.header() != null) {
            return (e, h) -> h.get(d.header().toLowerCase(java.util.Locale.ROOT));
        }
        throw new IllegalArgumentException("channelEventKeyDetection needs fixedValue, jsonField, jsonPointerExpression or header");
    }

    static Map<String, Object> extract(EventDefinition def, JsonNode event) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (EventDefinition.Field f : def.payload()) {
            if (PortsProblem.isReservedVariable(f.name())) {
                continue;   // FW-30: never let an event set engine/ports switches such as _SANGAM_SKIP_EXPRESSION_ENABLED
            }
            JsonNode n = Json.at(event, f.name());
            Object v = coerce(n, f.type(), f.name());
            if (v == null && f.isRequired()) {
                throw new IllegalArgumentException("event " + def.key() + ": payload field '" + f.name() + "' missing");
            }
            if (n != null) {
                out.put(f.name(), v);
            }
        }
        return out;
    }

    /** Type coercion for payload and correlation fields. JSON values stay JSON text (R33 json type). */
    public static Object coerce(JsonNode n, String type, String field) {
        if (n == null || n.isNull()) {
            return null;
        }
        String t = type == null ? "string" : type.toLowerCase(java.util.Locale.ROOT);
        try {
            return switch (t) {
                case "string" -> n.isValueNode() ? n.asText() : Json.write(n);
                case "integer" -> {
                    if (n.isNumber() && !n.canConvertToInt()) {
                        throw new NumberFormatException("out of range");
                    }
                    yield n.isNumber() ? n.intValue() : Integer.parseInt(n.asText().trim());
                }
                case "long" -> {
                    if (n.isNumber() && !n.canConvertToLong()) {
                        throw new NumberFormatException("out of range");
                    }
                    yield n.isNumber() ? n.longValue() : Long.parseLong(n.asText().trim());
                }
                case "double" -> n.isNumber() ? n.doubleValue() : Double.parseDouble(n.asText().trim());
                case "boolean" -> n.isBoolean() ? n.booleanValue() : Boolean.parseBoolean(n.asText().trim());
                case "date" -> java.util.Date.from(Instant.parse(n.asText().trim()));
                case "json" -> Json.write(n);
                default -> throw new IllegalArgumentException("unsupported field type '" + type + "'");
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IllegalArgumentException("field '" + field + "' is not a valid " + t + ": " + n.asText(), e);
        }
    }

    private static Map<String, String> lower(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null) {
                    out.put(k.toLowerCase(java.util.Locale.ROOT), v);
                }
            });
        }
        return out;
    }
}
