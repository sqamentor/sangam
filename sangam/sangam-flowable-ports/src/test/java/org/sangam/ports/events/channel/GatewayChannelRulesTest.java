// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.support.Json;

/**
 * M-064/M-065 deploy rules and the outbound message shape: one validation per family (direction, properties,
 * CloudEvents mode the transport can carry), the vendor families loaded through the provider seam, and the three
 * outbound encodings (plain Sangam JSON, structured CloudEvent, binary CloudEvent attributes).
 */
class GatewayChannelRulesTest {

    static ChannelDefinition ch(String json) {
        return Json.read(json, ChannelDefinition.class);
    }

    static String msg(IllegalArgumentException e) {
        return e.getMessage();
    }

    @Test
    void vendorFamiliesLoadThroughTheProviderSeam() {
        assertInstanceOf(MqttAdapters.Family.class, ChannelAdapters.vendorFamily("mqtt").orElseThrow());
        assertInstanceOf(NatsAdapters.Family.class, ChannelAdapters.vendorFamily("nats").orElseThrow());
        assertTrue(ChannelAdapters.vendorFamily("kafka").isEmpty(), "kafka keeps its direct arm");
        assertTrue(ChannelAdapters.vendorFamily("sqs").isEmpty(), "cloud families come from the environment's providers");
    }

    @Test
    void binaryModeIsRefusedWhereTheTransportCannotCarryIt() {
        for (String t : new String[]{"mqtt", "eventbridge", "servicebus", "eventgrid"}) {
            ChannelDefinition c = ch("{\"key\":\"o\",\"channelType\":\"" + t + "\",\"type\":\"outbound\",\"serializerType\":\"cloudevents\","
                    + "\"destination\":\"d\",\"properties\":{\"cloudEventsMode\":\"binary\",\"serverUri\":\"tcp://h:1883\",\"region\":\"r\","
                    + "\"accessKeyRef\":\"a\",\"secretKeyRef\":\"s\",\"namespace\":\"n\",\"entityPath\":\"q\",\"sasKeyName\":\"k\","
                    + "\"sasKeyRef\":\"r\",\"endpoint\":\"e\",\"topic\":\"t\",\"keyRef\":\"k\"}}");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(c), t);
            assertTrue(msg(e).contains("binary"), t + ": " + msg(e));
        }
        ChannelDefinition kafka = ch("{\"key\":\"o\",\"channelType\":\"kafka\",\"type\":\"outbound\",\"serializerType\":\"cloudevents\","
                + "\"topics\":[\"t\"],\"properties\":{\"cloudEventsMode\":\"binary\"}}");
        assertDoesNotThrow(() -> ChannelAdapters.validate(kafka));
    }

    @Test
    void familyRules() {
        assertTrue(msg(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(
                "{\"key\":\"g\",\"channelType\":\"eventgrid\",\"type\":\"outbound\",\"properties\":{\"endpoint\":\"e\",\"topic\":\"t\",\"keyRef\":\"k\"}}"))))
                .contains("CloudEvents only"));
        assertTrue(msg(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(
                "{\"key\":\"s\",\"channelType\":\"sns\",\"type\":\"inbound\",\"properties\":{}}"))))
                .contains("outbound only"));
        assertTrue(msg(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(
                "{\"key\":\"q\",\"channelType\":\"sqs\",\"type\":\"outbound\",\"properties\":{\"region\":\"r\",\"queueUrl\":\"u\",\"accessKeyRef\":\"a\"}}"))))
                .contains("secretKeyRef"));
        assertTrue(msg(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(
                "{\"key\":\"n\",\"channelType\":\"nats\",\"type\":\"inbound\",\"properties\":{\"serverUri\":\"nats://h:4222\",\"stream\":\"S\"}}"))))
                .contains("consumer"));
        assertTrue(msg(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(
                "{\"key\":\"m\",\"channelType\":\"mqtt\",\"type\":\"inbound\",\"topics\":[\"a/b\"],\"properties\":{\"serverUri\":\"tcp://h:1883\"}}"))))
                .contains("messageIdJsonPointer"), "MQTT reuses packet ids: the inbox key must come from the event");
        assertDoesNotThrow(() -> ChannelAdapters.validate(ch("{\"key\":\"m\",\"channelType\":\"mqtt\",\"type\":\"inbound\","
                + "\"topics\":[\"a/b\"],\"deserializerType\":\"cloudevents\",\"properties\":{\"serverUri\":\"tcp://h:1883\"}}")));
        assertTrue(msg(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(
                "{\"key\":\"p\",\"channelType\":\"pubsub\",\"type\":\"outbound\",\"properties\":{\"project\":\"p\",\"topic\":\"t\"}}"))))
                .contains("serviceAccountKeyRef"));
        assertTrue(msg(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(
                "{\"key\":\"k\",\"channelType\":\"kafka\",\"type\":\"inbound\",\"topics\":[\"t\"],\"properties\":{\"redeliverBackoffMs\":\"soon\"}}"))))
                .contains("redeliverBackoffMs"));
        assertTrue(msg(assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.validate(ch(
                "{\"key\":\"x\",\"channelType\":\"carrier-pigeon\",\"type\":\"outbound\"}")))).contains("unknown"));
    }

    @Test
    void redeliverBackoffHasADefaultAndAFloor() {
        assertEquals(5000, ChannelAdapters.redeliverBackoffMs(ch("{\"key\":\"k\",\"channelType\":\"kafka\",\"type\":\"inbound\"}")));
        assertEquals(100, ChannelAdapters.redeliverBackoffMs(ch("{\"key\":\"k\",\"channelType\":\"kafka\",\"type\":\"inbound\","
                + "\"properties\":{\"redeliverBackoffMs\":\"1\"}}")));
    }

    @Test
    void outboundEncodings() {
        Map<String, Object> payload = Map.of("order", 7);
        ChannelAdapters.Outgoing plain = ChannelPublisher.outgoing(ch("{\"key\":\"o\",\"channelType\":\"kafka\",\"type\":\"outbound\"}"),
                "T1", "OrderPlaced", "m-1", payload);
        assertFalse(plain.binary());
        assertEquals("application/json", plain.contentType());

        ChannelAdapters.Outgoing structured = ChannelPublisher.outgoing(ch("{\"key\":\"o\",\"channelType\":\"kafka\",\"type\":\"outbound\","
                + "\"serializerType\":\"cloudevents\"}"), "T1", "OrderPlaced", "m-1", payload);
        assertFalse(structured.binary());
        assertEquals("application/cloudevents+json", structured.contentType());
        assertEquals("m-1", Json.tree(structured.body()).path("id").asText());
        assertEquals(7, Json.tree(structured.body()).path("data").path("order").asInt());

        ChannelAdapters.Outgoing binary = ChannelPublisher.outgoing(ch("{\"key\":\"o\",\"channelType\":\"kafka\",\"type\":\"outbound\","
                + "\"serializerType\":\"cloudevents\",\"properties\":{\"cloudEventsMode\":\"binary\"}}"), "T1", "OrderPlaced", "m-1", payload);
        assertTrue(binary.binary());
        assertEquals("{\"order\":7}", binary.body());
        assertEquals("m-1", binary.cloudEventsOrEmpty().get("id"));
        assertEquals("OrderPlaced", binary.cloudEventsOrEmpty().get("type"));
        assertEquals("T1", binary.cloudEventsOrEmpty().get("sangamtenant"));
    }

    @Test
    void anAdapterWithoutMetadataRefusesBinaryInsteadOfDroppingTheAttributes() {
        ChannelAdapters.OutboundChannelAdapter plainOnly = (tenant, key, id, body) -> {
        };
        ChannelAdapters.Outgoing binary = new ChannelAdapters.Outgoing("T1", "E", "m", "{}", "application/json", Map.of("id", "m"));
        assertThrows(IllegalStateException.class, () -> plainOnly.send(binary));
    }
}
