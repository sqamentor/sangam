// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.support.Json;

/**
 * M-065 CloudEvents content modes: deterministic context attributes, structured encoding, and decoding of both modes
 * under every binding prefix the gateway meets (HTTP/NATS/Pub/Sub {@code ce-}, Kafka {@code ce_}, AMQP/JMS
 * {@code cloudEvents_} and {@code cloudEvents:}); anything that is not a JSON CloudEvents 1.0 event is refused.
 */
class CloudEventsFormatTest {

    static ChannelDefinition ch(String json) {
        return Json.read(json, ChannelDefinition.class);
    }

    @Test
    void attributesAreDeterministicAndCarryTheTenantExtension() {
        ChannelDefinition c = ch("{\"key\":\"orders\",\"channelType\":\"kafka\",\"type\":\"outbound\",\"serializerType\":\"cloudevents\"}");
        Map<String, String> a = CloudEventsFormat.attributes(c, "T1", "OrderPlaced", "m-1");
        assertEquals(Map.of("specversion", "1.0", "id", "m-1", "source", "urn:sangam:channel:orders", "type", "OrderPlaced",
                "datacontenttype", "application/json", "sangamtenant", "T1"), a);
        assertFalse(a.containsKey("time"), "no wall-clock attribute: the same event encodes the same way on a retry");
        assertEquals(a, CloudEventsFormat.attributes(c, "T1", "OrderPlaced", "m-1"));
        assertTrue(CloudEventsFormat.enabled(c));
        assertEquals(CloudEventsFormat.Mode.STRUCTURED, CloudEventsFormat.mode(c), "structured is the default");
    }

    @Test
    void anUnknownModeIsADeployError() {
        ChannelDefinition c = ch("{\"key\":\"o\",\"channelType\":\"kafka\",\"type\":\"outbound\",\"serializerType\":\"cloudevents\","
                + "\"properties\":{\"cloudEventsMode\":\"batched\"}}");
        assertThrows(IllegalArgumentException.class, () -> CloudEventsFormat.mode(c));
    }

    @Test
    void structuredRoundTrip() {
        Map<String, String> a = CloudEventsFormat.attributes("urn:x", "T1", "OrderPlaced", "m-1");
        String json = CloudEventsFormat.structured(a, Map.of("order", 7));
        CloudEventsFormat.Event e = CloudEventsFormat.decode(json, Map.of());
        assertEquals("m-1", e.id());
        assertEquals("OrderPlaced", e.type());
        assertEquals("T1", e.attributes().get("sangamtenant"));
        assertEquals(7, e.data().path("order").asInt());
    }

    @Test
    void binaryModeIsReadUnderEveryBindingPrefix() {
        for (String prefix : new String[]{"ce-", "ce_", "cloudevents_", "cloudevents:"}) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put(prefix + "specversion", "1.0");
            h.put(prefix + "id", "m-9");
            h.put(prefix + "source", "urn:x");
            h.put(prefix + "type", "OrderPlaced");
            h.put("content-type", "application/json");
            assertTrue(CloudEventsFormat.isBinary(h), prefix);
            CloudEventsFormat.Event e = CloudEventsFormat.decode("{\"order\":9}", h);
            assertEquals("m-9", e.id(), prefix);
            assertEquals(9, e.data().path("order").asInt(), prefix);
        }
    }

    @Test
    void nonConformingEventsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> CloudEventsFormat.decode("[1]", Map.of()), "not an object");
        assertThrows(IllegalArgumentException.class,
                () -> CloudEventsFormat.decode("{\"specversion\":\"0.3\",\"id\":\"1\",\"source\":\"s\",\"type\":\"t\"}", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> CloudEventsFormat.decode("{\"specversion\":\"1.0\",\"source\":\"s\",\"type\":\"t\"}", Map.of()), "no id");
        assertThrows(IllegalArgumentException.class,
                () -> CloudEventsFormat.decode("{\"specversion\":\"1.0\",\"id\":\"1\",\"source\":\"s\",\"type\":\"t\",\"data_base64\":\"AA==\"}",
                        Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> CloudEventsFormat.decode("{\"specversion\":\"1.0\",\"id\":\"1\",\"source\":\"s\",\"type\":\"t\","
                        + "\"datacontenttype\":\"application/xml\",\"data\":\"<a/>\"}", Map.of()));
        Map<String, String> h = Map.of("ce-specversion", "1.0", "ce-id", "1", "ce-source", "s", "ce-type", "t",
                "content-type", "text/plain");
        assertThrows(IllegalArgumentException.class, () -> CloudEventsFormat.decode("hello", h), "binary data must be JSON");
    }
}
