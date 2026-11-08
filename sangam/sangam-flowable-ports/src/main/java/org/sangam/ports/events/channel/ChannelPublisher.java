// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.OutboundEventService;
import org.sangam.ports.events.model.ChannelDefinition;
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
    private final java.util.function.BiFunction<ChannelDefinition, ChannelAdapters.Environment, ChannelAdapters.OutboundChannelAdapter> factory;
    private final Map<String, ChannelAdapters.OutboundChannelAdapter> adapters = new ConcurrentHashMap<>();

    public ChannelPublisher(EventRegistry registry, ChannelAdapters.Environment env) {
        this(registry, env, ChannelAdapters::outbound);
    }

    /** Test seam: the adapter factory (production: {@link ChannelAdapters#outbound}). */
    ChannelPublisher(EventRegistry registry, ChannelAdapters.Environment env,
                     java.util.function.BiFunction<ChannelDefinition, ChannelAdapters.Environment, ChannelAdapters.OutboundChannelAdapter> factory) {
        this.registry = registry;
        this.env = env;
        this.factory = factory;
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
        String prefix = event.tenantId() + "|" + channelKey + "|";
        java.util.Optional<EventRegistry.DeployedChannel> found = registry.channel(event.tenantId(), channelKey);
        if (found.isEmpty()) {
            closeAll(prefix, null);   // retired / deleted: release the broker connection of the old version
            throw new IllegalStateException("channel " + channelKey + " no longer deployed in " + event.tenantId());
        }
        EventRegistry.DeployedChannel ch = found.get();
        String cacheKey = prefix + ch.version();
        ChannelAdapters.OutboundChannelAdapter adapter = adapters.get(cacheKey);
        if (adapter == null) {
            adapter = adapters.computeIfAbsent(cacheKey, k -> factory.apply(ch.definition(), env));
            // a redeployed channel version supersedes the cached adapter of the previous version: close it, otherwise
            // every redeploy leaks one open producer / broker connection until the relay stops
            closeAll(prefix, cacheKey);
        }
        JsonNode body = Json.tree(event.payload());
        String eventKey = body.path("eventKey").asText();
        String messageId = body.path("messageId").asText(event.eventId().toString());
        Map<String, Object> payload = Json.map(body.path("payload"));
        String serialized = OutboundEventSerializer.of(ch.definition().serializerType())
                .serialize(eventKey, event.tenantId(), messageId, payload);
        adapter.send(event.tenantId(), eventKey, messageId, serialized);
    }

    private void closeAll(String prefix, String keep) {
        for (String k : java.util.Set.copyOf(adapters.keySet())) {
            if (k.startsWith(prefix) && !k.equals(keep)) {
                ChannelAdapters.OutboundChannelAdapter old = adapters.remove(k);
                if (old != null) {
                    old.close();
                }
            }
        }
    }

    @Override
    public void close() {
        adapters.values().forEach(ChannelAdapters.OutboundChannelAdapter::close);
        adapters.clear();
    }
}
