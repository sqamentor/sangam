// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts and stops the broker consumers for every deployed inbound channel (Kafka, JMS, RabbitMQ). Call
 * {@link #refresh} at start-up and after an app/channel deployment; it starts new channel versions and stops removed
 * or superseded ones. Webhook channels need no consumer.
 */
public class InboundChannelManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(InboundChannelManager.class);

    private final EventRegistry registry;
    private final InboundEventProcessor processor;
    private final ChannelAdapters.Environment env;
    private final Map<String, ChannelAdapters.InboundChannelAdapter> running = new ConcurrentHashMap<>();

    public InboundChannelManager(EventRegistry registry, InboundEventProcessor processor, ChannelAdapters.Environment env) {
        this.registry = registry;
        this.processor = processor;
        this.env = env;
    }

    public synchronized void refresh() {
        Set<String> wanted = new HashSet<>();
        for (EventRegistry.DeployedChannel ch : registry.allChannels()) {
            ChannelDefinition d = ch.definition();
            if (d.direction() != ChannelDefinition.Direction.INBOUND || "webhook".equals(d.adapterType())) {
                continue;
            }
            String id = ch.tenantId() + "|" + d.key() + "|" + ch.version();
            wanted.add(id);
            if (running.containsKey(id)) {
                continue;
            }
            if (!ChannelAdapters.available(d.adapterType())) {
                LOG.warn("inbound channel {} ({}) skipped: client library not on the class path", d.key(), d.adapterType());
                continue;
            }
            try {
                ChannelAdapters.InboundChannelAdapter a = ChannelAdapters.inbound(d, ch.tenantId(), processor, env);
                a.start();
                running.put(id, a);
                LOG.info("inbound channel {} v{} ({}) started for tenant {}", d.key(), ch.version(), d.adapterType(), ch.tenantId());
            } catch (RuntimeException e) {
                LOG.error("inbound channel {} failed to start: {}", d.key(), e.toString());
            }
        }
        for (String id : Set.copyOf(running.keySet())) {
            if (!wanted.contains(id)) {
                running.remove(id).close();
            }
        }
    }

    public Set<String> runningChannels() {
        return Set.copyOf(running.keySet());
    }

    @Override
    public synchronized void close() {
        running.values().forEach(ChannelAdapters.InboundChannelAdapter::close);
        running.clear();
    }
}
