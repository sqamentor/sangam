// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.List;

import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.integration.relay.PublisherRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relay publisher for a module's own event destination ({@code case-events}, {@code programme-events},
 * {@code programme-dashboard}, {@code field-events}). Every module event is already durable in {@code OUTBOX} (the
 * record dashboards and analytics read); this publisher forwards each event to the destinations a site subscribes
 * ({@code sangam.<module>.events.forward-to}, e.g. {@code channel:case-kafka} or {@code webhook:dashboard}) through the
 * integration {@link PublisherRegistry}, and otherwise acknowledges it. Without a registered publisher the relay would
 * treat the destination as unknown and park every delivery (permanent failure).
 */
public final class CaseEventForwarder implements Publisher {

    private static final Logger LOG = LoggerFactory.getLogger(CaseEventForwarder.class);

    private final PublisherRegistry registry;
    private final String ownDestination;
    private final List<String> forwardTo;
    private final Publisher before;

    /**
     * @param before an optional in-process consumer run first (e.g. engine message correlation); its failure makes the
     *               relay retry the delivery
     */
    public CaseEventForwarder(PublisherRegistry registry, String ownDestination, List<String> forwardTo, Publisher before) {
        this.registry = registry;
        this.ownDestination = ownDestination;
        this.forwardTo = forwardTo == null ? List.of()
                : forwardTo.stream().filter(d -> d != null && !d.isBlank() && !d.equals(ownDestination)).toList();
        this.before = before;
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        if (before != null) {
            before.publish(destination, event);
        }
        for (String target : forwardTo) {
            registry.publish(target, event);
        }
        if (forwardTo.isEmpty() && LOG.isDebugEnabled()) {
            LOG.debug("{} event {} ({}) acknowledged; no forward-to destinations configured", ownDestination,
                    event.eventId(), event.eventType());
        }
    }
}
