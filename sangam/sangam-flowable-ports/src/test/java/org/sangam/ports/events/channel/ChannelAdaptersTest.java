// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.support.Json;

/** FC-03 adapter factory: availability by class path, adapter selection, air-gapped egress allow-list. */
class ChannelAdaptersTest {

    private static ChannelDefinition ch(String json) {
        return Json.read(json, ChannelDefinition.class);
    }

    @Test
    void availabilityFollowsTheOptionalClientLibraries() {
        assertTrue(ChannelAdapters.available("http"));
        assertTrue(ChannelAdapters.available("webhook"));
        assertTrue(ChannelAdapters.available("jms"), "jakarta.jms-api is on the test class path");
        assertTrue(ChannelAdapters.available("rabbitmq"), "amqp-client is on the test class path");
        assertTrue(ChannelAdapters.available("kafka"));
        assertFalse(ChannelAdapters.available("mqtt"));
    }

    @Test
    void outboundAndInboundSelection() {
        ChannelAdapters.Environment env = ChannelAdapters.Environment.of(Map.of(), Set.of("hooks.example"));
        assertInstanceOf(HttpOutboundAdapter.class, ChannelAdapters.outbound(
                ch("{\"key\":\"o\",\"channelType\":\"webhook\",\"type\":\"outbound\",\"url\":\"https://hooks.example/x\"}"), env));
        assertNull(ChannelAdapters.inbound(ch("{\"key\":\"i\",\"channelType\":\"webhook\"}"), "acc", null, env),
                "webhooks have no consumer; the REST layer calls WebhookInboundEndpoint");
        assertThrows(IllegalArgumentException.class, () -> ChannelAdapters.inbound(ch("{\"key\":\"i\",\"channelType\":\"http\"}"), "acc", null, env));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ChannelAdapters.outbound(ch("{\"key\":\"o\",\"channelType\":\"mqtt\",\"type\":\"outbound\"}"), env));
        assertTrue(e.getMessage().contains("not available"));
    }

    @Test
    void egressIsAllowedOnlyToListedHostsAndHostNamesCompareCaseInsensitively() {
        ChannelAdapters.Environment env = ChannelAdapters.Environment.of(Map.of("a", "b"), Set.of("Hooks.Example.ORG"));
        assertEquals("b", env.secret("a").orElseThrow());
        assertTrue(env.secret("missing").isEmpty());
        assertEquals("sangam", env.nodeId());
        assertTrue(env.jmsConnectionFactory("default").isEmpty());
        // DNS names are case-insensitive (RFC 4343): an allow-list entry written in mixed case must still match
        assertTrue(env.egressAllowed(URI.create("https://hooks.example.org/in")));
        assertTrue(env.egressAllowed(URI.create("https://HOOKS.example.org/in")));
        assertFalse(env.egressAllowed(URI.create("https://evil.example.org/in")));
        assertFalse(env.egressAllowed(URI.create("https://hooks.example.org.evil.test/in")));
        assertFalse(env.egressAllowed(URI.create("mailto:x@hooks.example.org")), "no host");
    }
}
