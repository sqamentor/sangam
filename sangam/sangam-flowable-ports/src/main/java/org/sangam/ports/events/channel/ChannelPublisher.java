// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.OutboundEventService;
import org.sangam.ports.events.pipeline.OutboundEventSerializer;
import org.sangam.ports.support.Json;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relay publisher for outbound channels (FLOWABLE-INTO-OPERATON FC-03): WS-INTEGRATION's relay calls {@link #publish} for every outbox
 * delivery whose destination starts with {@code channel:}. The adapter is created per (tenant, channel, version) and
 * cached; a redeployed channel version gets a fresh adapter.
 */
public class ChannelPublisher implements Publisher, AutoCloseable {

    private final EventRegistry registry;
    private final ChannelAdapters.Environment env;
    private final Map<String, ChannelAdapters.OutboundChannelAdapter> adapters = new ConcurrentHashMap<>();

    public ChannelPublisher(EventRegistry registry, ChannelAdapters.Environment env) {
        this.registry = registry;
        this.env = env;
    }

    public static boolean handles(String destination) {
        return destination != null && destination.startsWith(OutboundEventService.DESTINATION_PREFIX);
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        if (!handles(destination)) {
            throw new IllegalArgumentException("not a channel destination: " + destination);
        }
        String channelKey = destination.substring(OutboundEventService.DESTINATION_PREFIX.length());
        EventRegistry.DeployedChannel ch = registry.channel(event.tenantId(), channelKey)
                .orElseThrow(() -> new IllegalStateException("channel " + channelKey + " no longer deployed in " + event.tenantId()));
        String cacheKey = event.tenantId() + "|" + channelKey + "|" + ch.version();
        ChannelAdapters.OutboundChannelAdapter adapter = adapters.computeIfAbsent(cacheKey, k -> ChannelAdapters.outbound(ch.definition(), env));
        JsonNode body = Json.tree(event.payload());
        String eventKey = body.path("eventKey").asText();
        String messageId = body.path("messageId").asText(event.eventId().toString());
        Map<String, Object> payload = Json.map(body.path("payload"));
        String serialized = OutboundEventSerializer.of(ch.definition().serializerType())
                .serialize(eventKey, event.tenantId(), messageId, payload);
        adapter.send(event.tenantId(), eventKey, messageId, serialized);
    }

    @Override
    public void close() {
        adapters.values().forEach(ChannelAdapters.OutboundChannelAdapter::close);
        adapters.clear();
    }
}
