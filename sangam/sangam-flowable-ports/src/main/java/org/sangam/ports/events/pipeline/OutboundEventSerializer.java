// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.pipeline;

import org.sangam.ports.support.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** Outbound serializer (FLOWABLE-INTO-OPERATON FC-03): Flowable's {@code OutboundEventSerializer} shape; JSON is the shipped format. */
@FunctionalInterface
public interface OutboundEventSerializer {

    String serialize(String eventKey, String tenantId, String messageId, Map<String, Object> payload);

    /** {@code {"eventKey":..,"tenantId":..,"messageId":..,"payload":{..}}}. */
    OutboundEventSerializer JSON = (eventKey, tenantId, messageId, payload) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventKey", eventKey);
        m.put("tenantId", tenantId);
        m.put("messageId", messageId);
        m.put("payload", payload);
        return Json.write(m);
    };

    /** Payload only, for consumers that expect the bare business object. */
    OutboundEventSerializer JSON_PAYLOAD_ONLY = (eventKey, tenantId, messageId, payload) -> Json.write(payload);

    /**
     * Structured-mode CloudEvent (M-065) with the default source {@code urn:sangam}. The channel publisher uses
     * {@link CloudEventsFormat} directly, with the channel's source and content mode; this form exists so that the
     * serializer list below stays the one list of supported types.
     */
    OutboundEventSerializer CLOUDEVENTS_STRUCTURED = (eventKey, tenantId, messageId, payload) -> CloudEventsFormat.structured(
            CloudEventsFormat.attributes("urn:sangam", tenantId, eventKey, messageId), payload);

    static OutboundEventSerializer of(String type) {
        if (type == null || "json".equalsIgnoreCase(type)) {
            return JSON;
        }
        if ("json-payload".equalsIgnoreCase(type)) {
            return JSON_PAYLOAD_ONLY;
        }
        if (CloudEventsFormat.TYPE.equalsIgnoreCase(type)) {
            return CLOUDEVENTS_STRUCTURED;
        }
        throw new IllegalArgumentException("serializer '" + type + "' is not supported (json, json-payload, cloudevents)");
    }
}
