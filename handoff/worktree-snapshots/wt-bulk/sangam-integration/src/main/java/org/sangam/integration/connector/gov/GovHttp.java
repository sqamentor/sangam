// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared outcome mapping of the government HTTP connectors (A-2, VI.3 rule 4, R36).
 *
 * <ul>
 *   <li>{@link HttpTransport.Outcome.NoEffect} → {@link ConnectorResult.TransientFailure} with {@code noEffect=true}.</li>
 *   <li>{@link HttpTransport.Outcome.Unknown} → {@link ConnectorResult.OutcomeUnknown}.</li>
 *   <li>429 / 503 (and 408 / 425, which reject the request before processing) → {@code TransientFailure(noEffect=true)}
 *       with the server's Retry-After.</li>
 *   <li>Other 4xx → {@link ConnectorResult.BusinessFailure} (deterministic refusal; the code comes from the body).</li>
 *   <li>Other 5xx → {@link ConnectorResult.OutcomeUnknown}, unless the connector is class (a) and the call was sent
 *       idempotently by operationId: then {@code TransientFailure(noEffect=false)} (a repeat is harmless).</li>
 *   <li>Anything else that is not 2xx (an unfollowed redirect, 1xx) → {@code OutcomeUnknown}.</li>
 * </ul>
 */
final class GovHttp {

    private GovHttp() {
    }

    /** Epoch-millisecond deadline of a request for {@link HttpTransport#execute}; 0 = none. */
    static long deadline(ConnectorRequest request) {
        return request.deadline() == null ? 0 : request.deadline().toEpochMilli();
    }

    /** {@code base} + {@code path}, keeping a context path of the base (unlike {@link URI#resolve}). */
    static URI join(URI base, String path) {
        String b = base.toString();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return URI.create(b + (path.startsWith("/") ? path : "/" + path));
    }

    /** Maps a non-2xx (or no) answer of an effect call. */
    static ConnectorResult failure(String system, HttpTransport.Outcome outcome, boolean idempotentClassA) {
        return switch (outcome) {
            case HttpTransport.Outcome.NoEffect ne ->
                    new ConnectorResult.TransientFailure(system + ": not sent: " + ne.reason(), true, null);
            case HttpTransport.Outcome.Unknown u ->
                    new ConnectorResult.OutcomeUnknown(system + ": " + u.reason(), null);
            case HttpTransport.Outcome.Response r -> responseFailure(system, r, idempotentClassA, errorCode(r, null),
                    errorMessage(r));
        };
    }

    /** Maps a non-2xx response with an already extracted error code and message. */
    static ConnectorResult responseFailure(String system, HttpTransport.Outcome.Response r, boolean idempotentClassA,
                                           String code, String message) {
        int s = r.status();
        String msg = system + " answered HTTP " + s + (message == null || message.isBlank() ? "" : ": " + message);
        if (s == 429 || s == 503 || s == 408 || s == 425) {
            return new ConnectorResult.TransientFailure(msg, true, r.retryAfter().orElse(null));
        }
        if (s >= 400 && s < 500) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("httpStatus", s);
            return new ConnectorResult.BusinessFailure(code == null || code.isBlank() ? "HTTP_" + s : code, msg, out);
        }
        if (s >= 500 && idempotentClassA) {
            return new ConnectorResult.TransientFailure(msg, false, r.retryAfter().orElse(null));
        }
        return new ConnectorResult.OutcomeUnknown(msg, null);
    }

    /** Maps a failed lookup call: every failure of the lookup itself is "try later". */
    static LookupResult lookupFailure(String system, HttpTransport.Outcome outcome) {
        return switch (outcome) {
            case HttpTransport.Outcome.NoEffect ne -> new LookupResult.Unavailable(system + ": " + ne.reason());
            case HttpTransport.Outcome.Unknown u -> new LookupResult.Unavailable(system + ": " + u.reason());
            case HttpTransport.Outcome.Response r ->
                    new LookupResult.Unavailable(system + " lookup answered HTTP " + r.status());
        };
    }

    /** {@code errorCode} (or {@code reason}) of a JSON error body, else {@code fallback}. */
    static String errorCode(HttpTransport.Outcome.Response r, String fallback) {
        JsonNode n = Json.tree(r.body());
        if (n != null && n.isObject()) {
            for (String f : new String[]{"errorCode", "reason", "code"}) {
                JsonNode v = n.get(f);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    return v.asText();
                }
            }
        }
        return fallback;
    }

    /** {@code message} of a JSON error body, or null. */
    static String errorMessage(HttpTransport.Outcome.Response r) {
        JsonNode n = Json.tree(r.body());
        if (n != null && n.isObject() && n.path("message").isTextual()) {
            return n.path("message").asText();
        }
        return null;
    }

    /** Request timeout: the configured value, never more than the time left before the deadline. */
    static Duration timeout(Duration configured, ConnectorRequest request) {
        long left = request.remainingMillis(configured.toMillis());
        return Duration.ofMillis(Math.max(1, Math.min(configured.toMillis(), left)));
    }

    /**
     * JSON object as a map without null-valued members (recursively), because connector outputs are copied with
     * {@link Map#copyOf}, which rejects nulls.
     */
    static Map<String, Object> jsonMap(JsonNode n) {
        return dropNulls(Json.toMap(n));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dropNulls(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof Map<?, ?> inner) {
                v = dropNulls((Map<String, Object>) inner);
            } else if (v instanceof java.util.List<?> list) {
                java.util.List<Object> l = new java.util.ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> im) {
                        l.add(dropNulls((Map<String, Object>) im));
                    } else if (o != null) {
                        l.add(o);
                    }
                }
                v = l;
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    /** Text value of a JSON field, or null when absent or null. */
    static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
