// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Channel definition ({@code .channel} model, FLOWABLE-INTO-OPERATON FC-03). Field names follow Flowable's channel JSON
 * ({@code channelType, type, deserializerType, serializerType, topics, destination, channelEventKeyDetection,
 * channelEventTenantJsonPointerExpression}); Sangam adds {@code url, exchange, routingKey, queue, properties,
 * messageIdJsonPointer, allowPayloadTenant}.
 *
 * <p>Tenant rule (R38): the tenant is the tenant the channel was deployed into. A payload/header tenant detector is
 * honoured only when {@code allowPayloadTenant=true} (an admin decision for a shared, multi-tenant channel); otherwise
 * a detected tenant that differs from the channel tenant rejects the event.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChannelDefinition(
        @JsonProperty("key") String key,
        @JsonProperty("name") String name,
        @JsonProperty("channelType") String channelType,
        @JsonProperty("type") String type,
        @JsonProperty("deserializerType") String deserializerType,
        @JsonProperty("serializerType") String serializerType,
        @JsonProperty("topics") List<String> topics,
        @JsonProperty("destination") String destination,
        @JsonProperty("queue") String queue,
        @JsonProperty("exchange") String exchange,
        @JsonProperty("routingKey") String routingKey,
        @JsonProperty("url") String url,
        @JsonProperty("channelEventKeyDetection") KeyDetection channelEventKeyDetection,
        @JsonProperty("channelEventTenantJsonPointerExpression") String tenantJsonPointer,
        @JsonProperty("tenantHeader") String tenantHeader,
        @JsonProperty("allowPayloadTenant") Boolean allowPayloadTenant,
        @JsonProperty("messageIdJsonPointer") String messageIdJsonPointer,
        @JsonProperty("payloadJsonPointer") String payloadJsonPointer,
        @JsonProperty("properties") Map<String, String> properties) {

    public enum Direction { INBOUND, OUTBOUND }

    public ChannelDefinition {
        Objects.requireNonNull(key, "channel key");
        topics = topics == null ? List.of() : List.copyOf(topics);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public Direction direction() {
        return "outbound".equalsIgnoreCase(type) ? Direction.OUTBOUND : Direction.INBOUND;
    }

    /** {@code http, webhook, jms, kafka, rabbitmq}. */
    public String adapterType() {
        return channelType == null ? "webhook" : channelType.toLowerCase(java.util.Locale.ROOT);
    }

    public boolean payloadTenantAllowed() {
        return Boolean.TRUE.equals(allowPayloadTenant);
    }

    public String property(String name, String fallback) {
        String v = properties.get(name);
        return v == null ? fallback : v;
    }

    /** How the event definition key is found: a fixed key, a JSON field, a JSON pointer or a header. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeyDetection(@JsonProperty("fixedValue") String fixedValue,
                               @JsonProperty("jsonField") String jsonField,
                               @JsonProperty("jsonPointerExpression") String jsonPointerExpression,
                               @JsonProperty("header") String header) {
    }
}
