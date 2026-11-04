// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic HTTP/REST connector (R71; Flowable FC-06 HTTP task; Operaton connect-plugin parity B-16).
 *
 * <p>Request: {@code method}, {@code path} (appended to the base URI; {@code {name}} placeholders are filled from the
 * input, URL-encoded), {@code query} (map), {@code headers} (map), {@code body} (any JSON value; a string is sent as
 * is). The operationId is sent in the configured idempotency header (default {@code Idempotency-Key}). Timeouts, retries
 * and mTLS come from the {@link HttpTransport}; egress is checked on every hop (R27).
 *
 * <p>Response mapping: 2xx → success with {@code status}, {@code headers} and {@code body} (parsed JSON when the
 * content type is JSON); 4xx except 408/409/429 → business failure {@code HTTP_<status>}; 409 → business failure
 * unless the body says it is a duplicate of the same operation; 408/429/503 → transient no-effect; other 5xx and
 * transport losses → outcome unknown, or transient for an idempotent (class a) endpoint.
 *
 * <p>Lookup (class a/b): {@code GET lookupPath} with {@code {operationId}}; 200 → found (a body field
 * {@code status=FAILED} marks a failed effect), 404 → not found.
 */
public final class HttpRestConnector implements SangamConnector {

    /** Static configuration of one REST endpoint. */
    public record Config(String id, URI baseUri, CapabilityClass capabilityClass, String idempotencyHeader,
                         String lookupPath, String authorization, Map<String, String> defaultHeaders, Duration timeout,
                         ConnectorProfile profile) {
        public Config {
            idempotencyHeader = idempotencyHeader == null ? "Idempotency-Key" : idempotencyHeader;
            defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");

    private final Config config;
    private final HttpTransport transport;
    private final SecretResolver secrets;
    private final ConnectorDescriptor descriptor;

    public HttpRestConnector(Config config, HttpTransport transport, SecretResolver secrets) {
        this.config = config;
        this.transport = transport;
        this.secrets = secrets == null ? SecretResolver.fromEnvironment() : secrets;
        this.descriptor = new ConnectorDescriptor(config.id(), "http", config.capabilityClass(), List.of(), config.profile());
    }

    /** Simulator profile against {@code RestEchoSimulator} ({@code POST /api/operations}, lookup by id), class (a). */
    public static HttpRestConnector simulator(String id, URI simulatorBase) {
        return new HttpRestConnector(new Config(id, simulatorBase, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, "Idempotency-Key",
                "/api/operations/{operationId}", null, Map.of(), Duration.ofSeconds(10), ConnectorProfile.SIMULATOR),
                HttpTransport.forSimulator(), SecretResolver.of(Map.of()));
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        String method = r.str("method", "POST").toUpperCase(java.util.Locale.ROOT);
        URI uri;
        try {
            uri = resolve(r.str("path", "/"), r, r.map("query"));
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
        }
        Map<String, String> headers = headers(r);
        byte[] body = bodyOf(r.input().get("body"));
        if (body.length > 0) {
            headers.putIfAbsent("Content-Type", "application/json");
        }
        boolean idempotent = method.equals("GET") || method.equals("PUT") || method.equals("DELETE")
                || config.capabilityClass() == CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP;
        HttpTransport.Call call = new HttpTransport.Call(method, uri, headers, body, config.timeout(), idempotent);
        HttpTransport.Outcome o = transport.execute(r.tenantId(), call, deadline(r));
        return map(o, idempotent);
    }

    @Override
    public LookupResult lookup(ConnectorRequest r) {
        if (config.lookupPath() == null || config.capabilityClass() == CapabilityClass.C_NEITHER) {
            return new LookupResult.NotSupported();
        }
        URI uri = resolve(config.lookupPath(), r, Map.of());
        HttpTransport.Outcome o = transport.execute(r.tenantId(), HttpTransport.Call.get(uri, headers(r)), deadline(r));
        return switch (o) {
            case HttpTransport.Outcome.Response resp when resp.status() == 404 -> new LookupResult.NotFound();
            case HttpTransport.Outcome.Response resp when resp.is2xx() -> {
                Map<String, Object> out = responseMap(resp);
                Object b = out.get("body");
                boolean failed = b instanceof Map<?, ?> m && "FAILED".equalsIgnoreCase(String.valueOf(m.get("status")));
                yield failed ? LookupResult.Found.failed("DOWNSTREAM_FAILED", out)
                        : LookupResult.Found.success(out, resp.header("Location").orElse(null));
            }
            case HttpTransport.Outcome.Response resp -> new LookupResult.Unavailable("lookup HTTP " + resp.status());
            case HttpTransport.Outcome.NoEffect ne -> new LookupResult.Unavailable(ne.reason());
            case HttpTransport.Outcome.Unknown u -> new LookupResult.Unavailable(u.reason());
        };
    }

    /** Shared mapping of a transport outcome to a connector result (also used by {@link SoapConnector}). */
    static ConnectorResult map(HttpTransport.Outcome o, boolean idempotent) {
        return switch (o) {
            case HttpTransport.Outcome.NoEffect ne -> ConnectorResult.TransientFailure.noEffect(ne.reason());
            case HttpTransport.Outcome.Unknown u -> idempotent
                    ? new ConnectorResult.TransientFailure(u.reason(), false, null)
                    : ConnectorResult.OutcomeUnknown.of(u.reason());
            case HttpTransport.Outcome.Response resp -> {
                int s = resp.status();
                Map<String, Object> out = responseMap(resp);
                if (resp.is2xx()) {
                    Object b = out.get("body");
                    boolean dup = b instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("duplicate"));
                    yield new ConnectorResult.Success(out, resp.header("Location").orElse(null), dup);
                }
                if (s == 408 || s == 429 || s == 503) {
                    yield new ConnectorResult.TransientFailure("HTTP " + s, true, resp.retryAfter().orElse(null));
                }
                if (s >= 400 && s < 500) {
                    yield new ConnectorResult.BusinessFailure("HTTP_" + s, abbreviate(resp.bodyString()), out);
                }
                yield idempotent ? new ConnectorResult.TransientFailure("HTTP " + s, false, null)
                        : ConnectorResult.OutcomeUnknown.of("HTTP " + s + " after the request was sent");
            }
        };
    }

    static Map<String, Object> responseMap(HttpTransport.Outcome.Response resp) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", resp.status());
        String ct = resp.header("Content-Type").orElse("");
        if (ct.contains("json")) {
            var tree = Json.tree(resp.body());
            out.put("body", tree == null ? resp.bodyString() : Json.MAPPER.convertValue(tree, Object.class));
        } else {
            out.put("body", resp.bodyString());
        }
        return out;
    }

    private Map<String, String> headers(ConnectorRequest r) {
        Map<String, String> h = new LinkedHashMap<>(config.defaultHeaders());
        for (Map.Entry<String, Object> e : r.map("headers").entrySet()) {
            h.put(e.getKey(), String.valueOf(e.getValue()));
        }
        h.put(config.idempotencyHeader(), r.operationId().toString());
        h.put("X-Sangam-Operation-Id", r.operationId().toString());
        if (r.attemptId() != null) {
            h.put("X-Sangam-Attempt-Id", r.attemptId());
        }
        if (config.authorization() != null) {
            String a = secrets.require(config.authorization());
            if (a.startsWith("basic:")) {
                a = "Basic " + Base64.getEncoder().encodeToString(a.substring(6).getBytes(StandardCharsets.UTF_8));
            }
            h.put("Authorization", a);
        }
        return h;
    }

    private URI resolve(String pathTemplate, ConnectorRequest r, Map<String, Object> query) {
        Matcher m = PLACEHOLDER.matcher(pathTemplate);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String v = key.equals("operationId") ? r.operationId().toString() : r.str(key);
            if (v == null) {
                throw new IllegalArgumentException("path placeholder {" + key + "} has no input value");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20")));
        }
        m.appendTail(sb);
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, Object> e : query.entrySet()) {
            q.append(q.isEmpty() ? "?" : "&").append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        String base = config.baseUri().toString();
        String path = sb.toString();
        if (base.endsWith("/") && path.startsWith("/")) {
            path = path.substring(1);
        } else if (!base.endsWith("/") && !path.startsWith("/") && !path.isEmpty()) {
            path = "/" + path;
        }
        URI uri = URI.create(base + path + q);
        if (!uri.getHost().equalsIgnoreCase(config.baseUri().getHost())) {
            throw new IllegalArgumentException("path must stay on the configured host");
        }
        return uri;
    }

    private static byte[] bodyOf(Object body) {
        if (body == null) {
            return new byte[0];
        }
        if (body instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        if (body instanceof byte[] b) {
            return b;
        }
        return Json.bytes(body);
    }

    private static long deadline(ConnectorRequest r) {
        return r.deadline() == null ? 0 : r.deadline().toEpochMilli();
    }

    static String abbreviate(String s) {
        return s == null ? null : s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
