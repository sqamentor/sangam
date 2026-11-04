// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.ports;

import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.ports.events.EventDispatcher;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.OutboundEventService;
import org.sangam.ports.events.channel.ChannelPublisher;
import org.sangam.ports.history.HistoryProjector;
import org.sangam.ports.history.PortsHistoryEventHandler;

/**
 * Connects WS-PORTS' event registry (sangam-flowable-ports, Flowable FC-02/FC-03/FC-25) to this module's inbox and
 * relay. A direct dependency is safe: sangam-flowable-ports depends only on sangam-core, so there is no module cycle.
 *
 * <ul>
 *   <li>Inbox rows whose source starts with {@code evt:} ({@link InboundEventProcessor#SOURCE_PREFIX}) are handed
 *       unchanged to {@link EventDispatcher} (a core {@code Inbox.Handler}). The {@link InboxService} must be built
 *       with the engine's transactions ({@code Transactions.of(engineAdapter)}), so the engine effect, the PROCESSED
 *       mark and the ports event-log row commit together.</li>
 *   <li>Outbox deliveries to {@code channel:*} destinations ({@link OutboundEventService#DESTINATION_PREFIX}) are
 *       published by {@link ChannelPublisher}.</li>
 *   <li>Optionally, the destination {@link PortsHistoryEventHandler#DESTINATION} is published by
 *       {@link HistoryProjector} (CQRS history read model; ordered per root).</li>
 * </ul>
 * Call once at start-up (sangam-server), before {@code InboxService.start()} and {@code OutboxRelayService.start()}.
 */
public final class EventRegistryWiring {

    private EventRegistryWiring() {
    }

    public static void wire(InboxService inbox, PublisherRegistry publishers, EventDispatcher dispatcher,
                            ChannelPublisher channels, HistoryProjector historyProjectorOrNull) {
        if (inbox != null && dispatcher != null) {
            inbox.registerSourceHandler(InboundEventProcessor.SOURCE_PREFIX, dispatcher);
        }
        if (publishers != null && channels != null) {
            publishers.register(OutboundEventService.DESTINATION_PREFIX + "*", channels);
        }
        if (publishers != null && historyProjectorOrNull != null) {
            publishers.register(PortsHistoryEventHandler.DESTINATION, historyProjectorOrNull);
        }
    }
}
