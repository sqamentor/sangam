// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inbound policy per event definition (SANGAM.md VI.2.6 rules 3–4, R38; Flowable FC-04 unique start; Operaton D-33
 * "early messages are not buffered").
 *
 * @param noSubscription what happens when no waiting instance / start event matches: {@link NoSubscription#BUFFER}
 *                       (keep {@code BUFFERED} until a subscription appears or the TTL runs out), {@link NoSubscription#REJECT}
 *                       (expire at once with audit and dead letter) or {@link NoSubscription#START_NEW} (start a new
 *                       instance of {@code startDefinitionKey})
 * @param ttl            buffer TTL (default 24 h)
 * @param uniqueStart    one start per (tenant, definition key, business key)
 * @param deadLetter     copy expired messages to the dead-letter store
 */
public record EventDefinitionPolicy(String eventType, NoSubscription noSubscription, Duration ttl, String messageName,
                                    String startDefinitionKey, boolean uniqueStart, boolean deadLetter) {

    public enum NoSubscription { BUFFER, REJECT, START_NEW }

    public EventDefinitionPolicy {
        noSubscription = noSubscription == null ? NoSubscription.BUFFER : noSubscription;
        ttl = ttl == null ? Duration.ofHours(24) : ttl;
        messageName = messageName == null ? eventType : messageName;
        if (noSubscription == NoSubscription.START_NEW && startDefinitionKey == null) {
            throw new IllegalArgumentException("START_NEW needs a startDefinitionKey");
        }
    }

    public static EventDefinitionPolicy buffer(String eventType) {
        return new EventDefinitionPolicy(eventType, NoSubscription.BUFFER, null, null, null, false, true);
    }

    /** Registry of policies by event type; unknown types get {@link #buffer}. WS-PORTS fills it from event definitions. */
    public static final class Registry {
        private final Map<String, EventDefinitionPolicy> byType = new ConcurrentHashMap<>();

        public Registry put(EventDefinitionPolicy p) {
            byType.put(p.eventType(), p);
            return this;
        }

        public EventDefinitionPolicy get(String eventType) {
            return byType.getOrDefault(eventType, buffer(eventType));
        }
    }
}
