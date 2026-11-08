// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared A-2 outcome mapping of the health connectors (same rules as the government connectors):
 * no-effect transport failures and 408/425/429/503 are retryable with {@code noEffect=true}; other 4xx are business
 * failures (code from a FHIR OperationOutcome or a JSON error body); 5xx is unknown unless the call was idempotent by
 * operationId (class a).
 */
public final class HealthHttp {

    private HealthHttp() {
    }

    public static long deadline(ConnectorRequest request) {
        return request.deadline() == null ? 0 : request.deadline().toEpochMilli();
    }

    public static Duration timeout(Duration configured, ConnectorRequest request) {
        long left = request.remainingMillis(configured.toMillis());
        return Duration.ofMillis(Math.max(1, Math.min(configured.toMillis(), left)));
    }

    /** {@code base} + {@code path}, keeping the base's context path. */
    public static URI join(URI base, String path) {
        String b = base.toString();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return URI.create(b + (path.startsWith("/") ? path : "/" + path));
    }

    public static ConnectorResult failure(String system, HttpTransport.Outcome outcome, boolean idempotentClassA) {
        return switch (outcome) {
            case HttpTransport.Outcome.NoEffect ne ->
                    new ConnectorResult.TransientFailure(system + ": not sent: " + ne.reason(), true, null);
            case HttpTransport.Outcome.Unknown u -> new ConnectorResult.OutcomeUnknown(system + ": " + u.reason(), null);
            case HttpTransport.Outcome.Response r -> {
                int s = r.status();
                String code = errorCode(r);
                String msg = system + " answered HTTP " + s + (code == null ? "" : ": " + code);
                if (s == 429 || s == 503 || s == 408 || s == 425) {
                    yield new ConnectorResult.TransientFailure(msg, true, r.retryAfter().orElse(null));
                }
                if (s >= 400 && s < 500) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("httpStatus", s);
                    String diag = diagnostics(r);
                    if (diag != null) {
                        out.put("diagnostics", diag);
                    }
                    yield new ConnectorResult.BusinessFailure(code == null ? "HTTP_" + s : code, msg, out);
                }
                if (s >= 500 && idempotentClassA) {
                    yield new ConnectorResult.TransientFailure(msg, false, r.retryAfter().orElse(null));
                }
                yield new ConnectorResult.OutcomeUnknown(msg, null);
            }
        };
    }

    public static LookupResult lookupFailure(String system, HttpTransport.Outcome outcome) {
        return switch (outcome) {
            case HttpTransport.Outcome.NoEffect ne -> new LookupResult.Unavailable(system + ": " + ne.reason());
            case HttpTransport.Outcome.Unknown u -> new LookupResult.Unavailable(system + ": " + u.reason());
            case HttpTransport.Outcome.Response r ->
                    new LookupResult.Unavailable(system + " lookup answered HTTP " + r.status());
        };
    }

    /** First OperationOutcome issue code, or {@code errorCode}/{@code code} of a JSON body; null if none. */
    public static String errorCode(HttpTransport.Outcome.Response r) {
        JsonNode n = Json.tree(r.body());
        if (n == null || !n.isObject()) {
            return null;
        }
        if ("OperationOutcome".equals(n.path("resourceType").asText())) {
            JsonNode issue = n.path("issue").path(0);
            String c = issue.path("details").path("coding").path(0).path("code").asText(null);
            return c != null ? c : issue.path("code").asText(null);
        }
        for (String f : new String[]{"errorCode", "error", "code"}) {
            if (n.path(f).isTextual()) {
                return n.path(f).asText();
            }
        }
        return null;
    }

    static String diagnostics(HttpTransport.Outcome.Response r) {
        JsonNode n = Json.tree(r.body());
        if (n != null && "OperationOutcome".equals(n.path("resourceType").asText())) {
            return n.path("issue").path(0).path("diagnostics").asText(null);
        }
        return null;
    }

    /** JSON object as a map without null members (connector outputs are copied with {@link Map#copyOf}). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> noNulls(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = clean(e.getValue());
            if (v != null && e.getKey() != null) {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object clean(Object v) {
        if (v instanceof Map<?, ?> m) {
            return noNulls((Map<String, Object>) m);
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                Object c = clean(o);
                if (c != null) {
                    out.add(c);
                }
            }
            return out;
        }
        return v;
    }

    public static Map<String, Object> map(JsonNode n) {
        return noNulls(Json.toMap(n));
    }
}
