// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.port;

import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.core.layer.Transactions;
import org.sangam.resources.support.Sql;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delivers the module's outbox events after commit (VI.2). Services never call the display feed or the notification
 * gateway directly: {@link org.sangam.resources.ResourcesContext#notify} and
 * {@link org.sangam.resources.ResourcesContext#feed} append to the core {@link Outbox} in the business transaction, and
 * the core {@code Relay} hands each committed event to this publisher, which calls the real ports. Nothing is shown on a
 * display board or sent to a person for a transaction that rolls back.
 *
 * <p>Wiring: {@code new Relay(dialect, tx, new ResourcesOutboxPublisher(notifier, feed, tx).orElse(otherPublisher), ...)},
 * or route the two destinations to it from the server's publisher. Delivery is at-least-once: the event id is passed as
 * {@code data.eventId} so receivers can deduplicate (VI.2.3 rule 4).
 */
public final class ResourcesOutboxPublisher implements Publisher {

    public static final String DEST_NOTIFICATION = "sangam.resources.notification";
    public static final String DEST_DISPLAY_FEED = "sangam.resources.display-feed";
    public static final String EVT_NOTIFICATION = "RES_NOTIFICATION";
    public static final String EVT_FEED = "RES_DISPLAY_FEED";

    private final ResourcesPorts.NotificationPort notifications;
    private final ResourcesPorts.DisplayFeedPort feed;
    private final Transactions tx;
    private final Publisher fallback;

    /** @param tx optional; when set, the notification port is called inside a fresh transaction */
    public ResourcesOutboxPublisher(ResourcesPorts.NotificationPort notifications, ResourcesPorts.DisplayFeedPort feed,
                                    Transactions tx) {
        this(notifications, feed, tx, null);
    }

    private ResourcesOutboxPublisher(ResourcesPorts.NotificationPort notifications, ResourcesPorts.DisplayFeedPort feed,
                                     Transactions tx, Publisher fallback) {
        this.notifications = notifications;
        this.feed = feed;
        this.tx = tx;
        this.fallback = fallback;
    }

    /** Chains another publisher for every destination this one does not own. */
    public ResourcesOutboxPublisher orElse(Publisher other) {
        return new ResourcesOutboxPublisher(notifications, feed, tx, other);
    }

    public static boolean owns(String destination) {
        return DEST_NOTIFICATION.equals(destination) || DEST_DISPLAY_FEED.equals(destination);
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        if (DEST_NOTIFICATION.equals(destination)) {
            Map<String, Object> m = Sql.mapJson(event.payload());
            Map<String, Object> data = new LinkedHashMap<>(Sql.asMap(m.get("data")));
            data.put("eventId", event.eventId().toString());
            String recipient = Sql.str(m.get("recipientId"));
            String template = Sql.str(m.get("templateKey"));
            if (tx != null) {
                tx.inTransaction(c -> {
                    notifications.notify(c, event.tenantId(), recipient, template, data);
                    return null;
                });
            } else {
                notifications.notify(null, event.tenantId(), recipient, template, data);
            }
        } else if (DEST_DISPLAY_FEED.equals(destination)) {
            Map<String, Object> m = Sql.mapJson(event.payload());
            Map<String, Object> data = new LinkedHashMap<>(Sql.asMap(m.get("data")));
            data.put("eventId", event.eventId().toString());
            Object at = m.get("at");
            feed.publish(event.tenantId(), new ResourcesPorts.FeedEvent(Sql.str(m.get("servicePointId")),
                    Sql.str(m.get("kind")), Sql.str(m.get("tokenId")), Sql.str(m.get("displayNo")),
                    Sql.str(m.get("counterId")), Sql.str(m.get("counterName")),
                    at == null ? null : Instant.parse(at.toString()), data));
        } else if (fallback != null) {
            fallback.publish(destination, event);
        } else {
            throw new IllegalArgumentException("no publisher for destination " + destination);
        }
    }

    /** Payload of a notification event. */
    public static Map<String, Object> notificationPayload(String recipientId, String templateKey, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipientId", recipientId);
        m.put("templateKey", templateKey);
        m.put("data", data == null ? Map.of() : stringify(data));
        return m;
    }

    /** Payload of a display-feed event. */
    public static Map<String, Object> feedPayload(ResourcesPorts.FeedEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("servicePointId", e.servicePointId());
        m.put("kind", e.kind());
        m.put("tokenId", e.tokenId());
        m.put("displayNo", e.displayNo());
        m.put("counterId", e.counterId());
        m.put("counterName", e.counterName());
        m.put("at", e.at() == null ? null : e.at().toString());
        m.put("data", e.data() == null ? Map.of() : stringify(e.data()));
        return m;
    }

    /** JSON-safe copy (time values as ISO strings). */
    private static Map<String, Object> stringify(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, v instanceof java.time.temporal.Temporal ? v.toString() : v));
        return out;
    }
}
