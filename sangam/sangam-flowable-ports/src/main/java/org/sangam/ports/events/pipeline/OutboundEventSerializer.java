// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.pipeline;

import org.sangam.ports.support.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** Outbound serializer (FC-03): Flowable's {@code OutboundEventSerializer} shape; JSON is the shipped format. */
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

    static OutboundEventSerializer of(String type) {
        if (type == null || "json".equalsIgnoreCase(type)) {
            return JSON;
        }
        if ("json-payload".equalsIgnoreCase(type)) {
            return JSON_PAYLOAD_ONLY;
        }
        throw new IllegalArgumentException("serializer '" + type + "' is not supported (json, json-payload)");
    }
}
