// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.integration.publish.cloud.EventGridInboundEndpoint;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.channel.ChannelAdapters;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.support.Json;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.EventPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.server.config.PortsWiring;
import org.springframework.mock.env.MockEnvironment;

/**
 * SL-170 Event Grid route in the server (W4-H REQUEST 00:50:03Z, integrator ruling 00:50:58Z): off by default (404),
 * only a deployed inbound eventgrid channel is reachable, a failed channel authentication is 401
 * WEBHOOK_SIGNATURE_INVALID, and every other answer of the endpoint passes through as is.
 */
class PortsEventBridgeEventGridTest {

    static final String SECRET = "eg-test-secret";

    final Map<String, ChannelDefinition> channels = new HashMap<>();
    final List<String> delivered = new ArrayList<>();

    final EventRegistry registry = new EventRegistry(null, null, Duration.ZERO) {
        @Override
        public Optional<DeployedChannel> channel(String tenantId, String key) {
            return Optional.ofNullable(channels.get(key)).map(c -> new DeployedChannel(tenantId, 1, c));
        }
    };

    final InboundEventProcessor processor = new InboundEventProcessor(null, null, null, null) {
        @Override
        public Delivery deliver(String tenant, String channelKey, String raw, Map<String, String> headers, String id) {
            delivered.add(tenant + ":" + channelKey + ":" + id);
            return new Delivery(Disposition.ACK, new Result(Outcome.ACCEPTED, "E", id, null), null);
        }
    };

    final ChannelAdapters.Environment channelEnv = ChannelAdapters.Environment.of(Map.of("secret://eg/in", SECRET), Set.of());

    void deploy(String key, String type, String direction) {
        channels.put(key, Json.read("{\"key\":\"" + key + "\",\"channelType\":\"" + type + "\",\"type\":\"" + direction
                + "\",\"deserializerType\":\"cloudevents\",\"properties\":{\"authSecretRef\":\"secret://eg/in\","
                + "\"authHeader\":\"X-Sangam-Key\"}}", ChannelDefinition.class));
    }

    PortsEventBridge bridge(EventGridInboundEndpoint eg) {
        return new PortsEventBridge(registry, null, null, null, new RestProperties(), false, eg);
    }

    static String ce(String id) {
        return "{\"specversion\":\"1.0\",\"id\":\"" + id + "\",\"type\":\"OrderPlaced\",\"source\":\"/orders\",\"data\":{}}";
    }

    @Test
    void theRouteIsOffByDefault() {
        deploy("eg", "eventgrid", "inbound");
        assertNull(PortsWiring.eventGrid(new MockEnvironment(), registry, processor, channelEnv),
                "sangam.ports.events.eventgrid.enabled defaults to false");
        ApiProblem p = assertThrows(ApiProblem.class, () -> bridge(null).eventGridHook("POST", "T1", "eg",
                Map.of("X-Sangam-Key", SECRET), Map.of(), ce("e-1")));
        assertEquals(404, p.status());
        assertTrue(delivered.isEmpty());
        assertThrows(IllegalStateException.class, () -> PortsWiring.eventGrid(
                new MockEnvironment().withProperty("sangam.ports.events.eventgrid.enabled", "true"), registry, null,
                channelEnv), "enabled without an inbound processor stops start-up");
    }

    @Test
    void onlyADeployedInboundEventGridChannelIsReachable() {
        deploy("hook", "webhook", "inbound");
        deploy("out", "eventgrid", "outbound");
        PortsEventBridge b = bridge(new EventGridInboundEndpoint(registry, processor, channelEnv));
        for (String ch : List.of("missing", "hook", "out")) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> b.eventGridHook("POST", "T1", ch,
                    Map.of("X-Sangam-Key", SECRET), Map.of(), ce("e-1")));
            assertEquals(404, p.status(), ch);
        }
        assertTrue(delivered.isEmpty());
    }

    @Test
    void aFailedChannelAuthenticationIs401AndAnAcceptedDeliveryPassesThrough() {
        deploy("eg", "eventgrid", "inbound");
        PortsEventBridge b = bridge(PortsWiring.eventGrid(
                new MockEnvironment().withProperty("sangam.ports.events.eventgrid.enabled", "true"), registry, processor,
                channelEnv));
        Map<String, String> wrong = new HashMap<>(Map.of("Content-Type", "application/cloudevents+json",
                "X-Sangam-Key", SECRET + "x"));
        ApiProblem p = assertThrows(ApiProblem.class, () -> b.eventGridHook("POST", "T1", "eg", wrong, Map.of(), ce("e-1")));
        assertEquals(401, p.status());
        assertEquals("WEBHOOK_SIGNATURE_INVALID", p.code());
        assertTrue(delivered.isEmpty(), "nothing is read as events before the channel authenticates");

        Map<String, String> ok = Map.of("Content-Type", "application/cloudevents+json", "X-Sangam-Key", SECRET);
        EventPort.HookAnswer a = b.eventGridHook("POST", "T1", "eg", ok, Map.of(), ce("e-2"));
        assertEquals(200, a.status());
        assertEquals(List.of("T1:eg:e-2"), delivered);

        EventPort.HookAnswer handshake = b.eventGridHook("OPTIONS", "T1", "eg",
                Map.of("X-Sangam-Key", SECRET, "WebHook-Request-Origin", EventGridInboundEndpoint.DEFAULT_ORIGIN),
                Map.of(), null);
        assertEquals(200, handshake.status());
        assertEquals(EventGridInboundEndpoint.DEFAULT_ORIGIN, handshake.headers().get("WebHook-Allowed-Origin"));
    }
}
