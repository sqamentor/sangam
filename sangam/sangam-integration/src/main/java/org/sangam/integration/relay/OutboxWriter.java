// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Outbox;

import java.sql.Connection;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The write side used by commands, delegates and the history projector (VI.2.2; Flowable FC-02 "send event",
 * FC-25/FC-43 "history and typed events onto the outbox").
 *
 * <p>{@link #append} runs <b>inside the command transaction</b> (the engine transaction): the event commits with the
 * state change or not at all. Destinations come from a {@link SubscriptionResolver}; the event registry of WS-PORTS
 * implements it from its channel definitions, and fixed destinations (history projector, wake-up) can be added.
 */
public final class OutboxWriter {

    /** event type (and tenant) → subscribed destinations. */
    @FunctionalInterface
    public interface SubscriptionResolver {
        Collection<Outbox.Destination> destinations(String tenantId, String eventType);

        static SubscriptionResolver fixed(List<Outbox.Destination> destinations) {
            return (t, e) -> destinations;
        }

        default SubscriptionResolver and(SubscriptionResolver other) {
            return (t, e) -> {
                java.util.LinkedHashMap<String, Outbox.Destination> all = new java.util.LinkedHashMap<>();
                destinations(t, e).forEach(d -> all.putIfAbsent(d.name(), d));
                other.destinations(t, e).forEach(d -> all.putIfAbsent(d.name(), d));
                return List.copyOf(all.values());
            };
        }
    }

    private final Outbox outbox;
    private final SubscriptionResolver subscriptions;

    public OutboxWriter(Dialect dialect, SubscriptionResolver subscriptions) {
        this.outbox = new Outbox(dialect);
        this.subscriptions = subscriptions;
    }

    /**
     * Appends an event for {@code rootId}. Returns null when no destination subscribes (nothing is written).
     *
     * @param payload a JSON-able map (decimals as strings, R33); written canonically
     */
    public Outbox.Appended append(Connection c, String tenantId, String rootId, String eventType, Map<String, ?> payload) {
        Collection<Outbox.Destination> dests = subscriptions.destinations(tenantId, eventType);
        if (dests == null || dests.isEmpty()) {
            return null;
        }
        return outbox.append(c, tenantId, rootId, eventType, CanonicalJson.write(payload == null ? Map.of() : payload), dests);
    }

    /** Appends with explicit destinations (send-event task with a fixed channel). */
    public Outbox.Appended append(Connection c, String tenantId, String rootId, String eventType, String payloadJson,
                                  Collection<Outbox.Destination> destinations) {
        return outbox.append(c, tenantId, rootId, eventType, payloadJson, destinations);
    }
}
