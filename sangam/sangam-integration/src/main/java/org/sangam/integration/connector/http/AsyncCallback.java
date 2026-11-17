// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Async invoke with a callback (M-067, SANGAM.md §5.1 "sync result or async callback", R27 authenticated callbacks).
 *
 * <p>The connector tells the called function where and how to report its result: the callback {@code url} (an inbound
 * event channel of Sangam, {@code POST /events/by-channel?channel=}), the {@code eventType} to use and the {@code correlationKey},
 * which is the operationId of the call. The function answers by sending a CloudEvent to that url with
 * {@code ce-type = eventType}, {@code ce-sangamcorrelation = correlationKey} and a unique {@code ce-id}, signed with the
 * channel's HMAC ({@code X-Sangam-Signature}). The existing inbound pipeline then authenticates it, de-duplicates it by
 * its id (a replayed callback is answered as a duplicate and has no second effect) and correlates it to the waiting
 * message event. Nothing new is received outside that pipeline.
 *
 * <p>Placement: {@link Placement#BODY} adds the envelope as the JSON field {@code field} of an object body (or wraps any
 * other body as {@code {"payload": body, field: envelope}}); this is the only way that reaches an AWS Lambda
 * {@code Event} invocation, which passes no headers. {@link Placement#HEADERS} sends it as {@code X-Sangam-Callback-Url},
 * {@code X-Sangam-Callback-Event-Type} and {@code X-Sangam-Correlation-Key}.
 *
 * <p>A 2xx answer (normally 202 Accepted) is a success whose output carries {@code callback} with the correlation key and
 * event type the process waits for; the downstream result arrives later as the event.
 */
public record AsyncCallback(URI url, String eventType, Placement placement, String field) {

    public enum Placement {
        BODY, HEADERS;

        public static Placement parse(String s) {
            return s == null || s.isBlank() ? BODY : valueOf(s.trim().toUpperCase(Locale.ROOT));
        }
    }

    public static final String DEFAULT_FIELD = "sangamCallback";

    public AsyncCallback {
        Objects.requireNonNull(url, "callback url");
        if (url.getScheme() == null || url.getHost() == null) {
            throw new IllegalArgumentException("callback url must be absolute: " + url);
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("callback event type is required");
        }
        placement = placement == null ? Placement.BODY : placement;
        field = field == null || field.isBlank() ? DEFAULT_FIELD : field;
    }

    /** The envelope sent to the function for one call. */
    public Map<String, Object> envelope(String correlationKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url.toString());
        m.put("eventType", eventType);
        m.put("correlationKey", correlationKey);
        return m;
    }

    /** The headers of {@link Placement#HEADERS}. */
    public Map<String, String> headers(String correlationKey) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Sangam-Callback-Url", url.toString());
        h.put("X-Sangam-Callback-Event-Type", eventType);
        h.put("X-Sangam-Correlation-Key", correlationKey);
        return h;
    }

    /** What the success output carries for the process: the key and event type it waits for. */
    public Map<String, Object> awaiting(String correlationKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("awaiting", true);
        m.put("eventType", eventType);
        m.put("correlationKey", correlationKey);
        return m;
    }
}
