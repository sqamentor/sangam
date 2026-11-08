// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.sangam.ports.events.pipeline.OutboundEventSerializer;
import org.sangam.ports.support.Json;

/** FC-03 outbound serializers: envelope shape, payload-only shape, type lookup. */
class OutboundEventSerializerTest {

    private static Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orderId", "O-1");
        p.put("amount", 500L);
        p.put("note", null);
        return p;
    }

    @Test
    void jsonEnvelopeCarriesKeyTenantMessageIdAndPayload() {
        JsonNode n = Json.tree(OutboundEventSerializer.JSON.serialize("paid", "acc", "m-1", payload()));
        assertEquals("paid", n.get("eventKey").asText());
        assertEquals("acc", n.get("tenantId").asText());
        assertEquals("m-1", n.get("messageId").asText());
        assertEquals("O-1", n.at("/payload/orderId").asText());
        assertEquals(500L, n.at("/payload/amount").asLong());
        assertEquals(true, n.at("/payload/note").isNull(), "null payload values are kept, not dropped");
    }

    @Test
    void defaultTenantIsSerializedAsNull() {
        JsonNode n = Json.tree(OutboundEventSerializer.JSON.serialize("paid", null, "m-1", Map.of()));
        assertEquals(true, n.get("tenantId").isNull());
        assertEquals(0, n.get("payload").size());
    }

    @Test
    void payloadOnlyWritesTheBareBusinessObject() {
        JsonNode n = Json.tree(OutboundEventSerializer.JSON_PAYLOAD_ONLY.serialize("paid", "acc", "m-1", payload()));
        assertEquals("O-1", n.get("orderId").asText());
        assertEquals(false, n.has("eventKey"));
        assertEquals(false, n.has("tenantId"));
    }

    @Test
    void lookupByTypeIsCaseInsensitiveAndUnknownTypesAreRefused() {
        assertSame(OutboundEventSerializer.JSON, OutboundEventSerializer.of(null));
        assertSame(OutboundEventSerializer.JSON, OutboundEventSerializer.of("JSON"));
        assertSame(OutboundEventSerializer.JSON_PAYLOAD_ONLY, OutboundEventSerializer.of("Json-Payload"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> OutboundEventSerializer.of("xml"));
        assertEquals(true, e.getMessage().contains("xml"));
    }
}
