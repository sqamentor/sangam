// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes outbox destinations to {@link Publisher}s. This is the plug-in point for the event registry of WS-PORTS
 * (FLOWABLE-INTO-OPERATON FC-02/FLOWABLE-INTO-OPERATON FC-03 channels): a channel adapter registers a publisher under its destination name (exact) or a
 * prefix ending in {@code *} ({@code kafka:*}, {@code webhook:*}). The longest matching prefix wins.
 *
 * <p>An unknown destination is a permanent failure: the delivery is parked/skipped and dead-lettered instead of being
 * retried forever.
 */
public final class PublisherRegistry implements Publisher {

    private final Map<String, Publisher> exact = new ConcurrentHashMap<>();
    private final Map<String, Publisher> prefixes = new ConcurrentHashMap<>();

    public PublisherRegistry register(String destinationOrPrefix, Publisher publisher) {
        if (destinationOrPrefix.endsWith("*")) {
            prefixes.put(destinationOrPrefix.substring(0, destinationOrPrefix.length() - 1), publisher);
        } else {
            exact.put(destinationOrPrefix, publisher);
        }
        return this;
    }

    public PublisherRegistry unregister(String destinationOrPrefix) {
        exact.remove(destinationOrPrefix);
        prefixes.remove(destinationOrPrefix.endsWith("*") ? destinationOrPrefix.substring(0, destinationOrPrefix.length() - 1)
                : destinationOrPrefix);
        return this;
    }

    public Optional<Publisher> find(String destination) {
        Publisher p = exact.get(destination);
        if (p != null) {
            return Optional.of(p);
        }
        String best = null;
        for (String prefix : prefixes.keySet()) {
            if (destination.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best == null ? Optional.empty() : Optional.of(prefixes.get(best));
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        Publisher p = find(destination).orElseThrow(() -> new PermanentPublishException("no publisher for destination " + destination));
        p.publish(destination, event);
    }
}
