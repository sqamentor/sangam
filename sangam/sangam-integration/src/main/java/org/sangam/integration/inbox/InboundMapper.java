// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import java.util.Map;

/**
 * Inbound pipeline shape (Flowable FC-03: deserializer, {@code InboundEventKeyDetector}, {@code InboundEventTenantDetector},
 * payload extractor) reduced to one function from a transport message to an {@link InboundMessage}. WS-PORTS'
 * channel models produce implementations; {@link #cloudEvents} is the default for CloudEvents binary-mode messages.
 *
 * @param <T> transport message type
 */
@FunctionalInterface
public interface InboundMapper<T> {

    /** Maps a transport message; returns null to drop it (acked, not stored), throws to reject it. */
    InboundMessage map(T message, Map<String, String> headers, String body);

    /**
     * CloudEvents: {@code id} → messageId, {@code type} → eventType, extension {@code sangamcorrelation} or
     * {@code subject} → correlationKey, extension {@code sangamtenant} → claimed tenant (checked against the binding).
     * Header names are matched case-insensitively with any of the {@code ce-}, {@code ce_} or {@code cloudEvents_}
     * prefixes.
     */
    static <T> InboundMapper<T> cloudEvents(String fallbackEventType) {
        return (msg, headers, body) -> {
            String id = ce(headers, "id");
            if (id == null) {
                throw new IllegalArgumentException("CloudEvents id missing");
            }
            String type = ce(headers, "type");
            String corr = ce(headers, "sangamcorrelation");
            return new InboundMessage(id, type == null ? fallbackEventType : type, corr != null ? corr : ce(headers, "subject"),
                    ce(headers, "sangamtenant"), body);
        };
    }

    static String ce(Map<String, String> headers, String attr) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey().toLowerCase(java.util.Locale.ROOT);
            if (k.equals("ce-" + attr) || k.equals("ce_" + attr) || k.equals("cloudevents_" + attr)) {
                return e.getValue();
            }
        }
        return null;
    }
}
