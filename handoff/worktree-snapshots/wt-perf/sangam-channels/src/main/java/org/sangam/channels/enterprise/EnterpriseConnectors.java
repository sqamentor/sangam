// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.enterprise;

import com.fasterxml.jackson.databind.JsonNode;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SAP and ServiceNow connectors over public HTTP interfaces. No vendor library is bundled. SAP RFC needs the customer's own
 * restricted client library and is not implemented here.
 */
public final class EnterpriseConnectors {

    private EnterpriseConnectors() {
    }

    // ---- ServiceNow Table API ------------------------------------------------------------------

    /**
     * Public Table API. Operations: {@code create}, {@code update}, {@code read}. The operationId is written to the configured
     * {@code correlation_id} column on create, and lookup queries it, so the connector is class (b): the outcome can be found, but
     * the API does not deduplicate a repeat.
     */
    public static SangamConnector serviceNow(String id, ConnectorProfile profile, URI instance, String authRef, String correlationColumn, HttpTransport transport, SecretResolver secrets) {
        ConnectorDescriptor d = new ConnectorDescriptor(id, "servicenow", CapabilityClass.B_LOOKUP_ONLY, List.of("create", "update", "read"), profile);
        return new SangamConnector() {
            @Override
            public ConnectorDescriptor descriptor() {
                return d;
            }

            @Override
            public ConnectorResult invoke(ConnectorRequest r) {
                try {
                    String table = safe(r.required("table"));
                    String base = instance.toString().replaceAll("/+$", "") + "/api/now/table/" + table;
                    Map<String, Object> fields = new LinkedHashMap<>(r.map("fields"));
                    return switch (r.operation()) {
                        case "create" -> {
                            fields.put(correlationColumn, r.operationId().toString());
                            yield result(send(r, "POST", base, fields), false);
                        }
                        case "update" -> result(send(r, "PATCH", base + "/" + safe(r.required("sysId")), fields), true);
                        case "read" -> result(send(r, "GET", base + "/" + safe(r.required("sysId")), null), true);
                        default -> ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION", r.operation());
                    };
                } catch (IllegalArgumentException e) {
                    return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
                }
            }

            @Override
            public LookupResult lookup(ConnectorRequest r) {
                String table = r.str("table");
                if (table == null || !"create".equals(r.operation())) {
                    return "create".equals(r.operation()) ? new LookupResult.Unavailable("no table in the request") : new LookupResult.NotSupported();
                }
                String query = URLEncoder.encode(correlationColumn + "=" + r.operationId(), StandardCharsets.UTF_8);
                String uri = instance.toString().replaceAll("/+$", "") + "/api/now/table/" + safe(table) + "?sysparm_limit=1&sysparm_query=" + query;
                return switch (send(r, "GET", uri, null)) {
                    case HttpTransport.Outcome.Response resp when resp.is2xx() -> {
                        JsonNode first = tree(resp).path("result").path(0);
                        yield first.isMissingNode() ? new LookupResult.NotFound()
                                : LookupResult.Found.success(Map.of("record", Json.MAPPER.convertValue(first, Object.class)), first.path("sys_id").asText(null));
                    }
                    case HttpTransport.Outcome.Response resp -> new LookupResult.Unavailable("HTTP " + resp.status());
                    case HttpTransport.Outcome.NoEffect n -> new LookupResult.Unavailable(n.reason());
                    case HttpTransport.Outcome.Unknown u -> new LookupResult.Unavailable(u.reason());
                };
            }

            private HttpTransport.Outcome send(ConnectorRequest r, String method, String uri, Object body) {
                Map<String, String> h = new LinkedHashMap<>();
                h.put("Accept", "application/json");
                h.put("Content-Type", "application/json");
                h.put("Authorization", secrets.require(authRef));
                long deadline = r.deadline() == null ? 0 : r.deadline().toEpochMilli();
                return transport.execute(r.tenantId(), new HttpTransport.Call(method, URI.create(uri), h, body == null ? null : Json.bytes(body), null, !method.equals("POST")), deadline);
            }

            private ConnectorResult result(HttpTransport.Outcome o, boolean idempotent) {
                return map(o, idempotent, resp -> {
                    JsonNode rec = tree(resp).path("result");
                    return ConnectorResult.Success.of(Map.of("record", Json.MAPPER.convertValue(rec, Object.class)), rec.path("sys_id").asText(null));
                });
            }
        };
    }

    /** Inbound ServiceNow webhook check: hex HMAC-SHA256 of the raw body with a shared secret set in the outbound REST message. */
    public static boolean serviceNowWebhookValid(String secret, byte[] rawBody, String signatureHex) {
        if (signatureHex == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(rawBody), HexFormat.of().parseHex(signatureHex));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    // ---- SAP OData -----------------------------------------------------------------------------

    /**
     * SAP Gateway OData over public HTTP conventions: {@code read} is GET, {@code create} is POST with the CSRF token fetch
     * (header {@code X-CSRF-Token: Fetch}) and session cookie. Class (c) by default: OData has no universal idempotency key, so a
     * lost answer on create becomes UNKNOWN_OUTCOME. A deployment whose service stores an external id can use the HTTP/REST
     * connector's lookup path instead.
     */
    public static SangamConnector sapOData(String id, ConnectorProfile profile, URI serviceRoot, String authRef, HttpTransport transport, SecretResolver secrets) {
        ConnectorDescriptor d = new ConnectorDescriptor(id, "sap-odata", CapabilityClass.C_NEITHER, List.of("read", "create"), profile);
        return new SangamConnector() {
            @Override
            public ConnectorDescriptor descriptor() {
                return d;
            }

            @Override
            public ConnectorResult invoke(ConnectorRequest r) {
                try {
                    String root = serviceRoot.toString().replaceAll("/+$", "");
                    String entitySet = safe(r.required("entitySet"));
                    long deadline = r.deadline() == null ? 0 : r.deadline().toEpochMilli();
                    Map<String, String> base = new LinkedHashMap<>();
                    base.put("Accept", "application/json");
                    base.put("Authorization", secrets.require(authRef));
                    if ("read".equals(r.operation())) {
                        String key = r.str("key");
                        String q = r.str("query", "");
                        if (!q.isEmpty() && (q.contains("#") || q.contains(" "))) {
                            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", "query must be URL-encoded");
                        }
                        URI uri = URI.create(root + "/" + entitySet + (key == null ? "" : "(" + URLEncoder.encode(key, StandardCharsets.UTF_8) + ")") + (q.isEmpty() ? "" : "?" + q));
                        return map(transport.execute(r.tenantId(), HttpTransport.Call.get(uri, base), deadline), true,
                                resp -> ConnectorResult.Success.of(Map.of("body", Json.MAPPER.convertValue(tree(resp), Object.class)), null));
                    }
                    if (!"create".equals(r.operation())) {
                        return ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION", r.operation());
                    }
                    HttpTransport.Outcome posted = null;
                    // A 403 with "X-CSRF-Token: Required" was refused before processing, so one retry with a new token is safe.
                    for (int attempt = 0; attempt < 2; attempt++) {
                        Map<String, String> fetch = new LinkedHashMap<>(base);
                        fetch.put("X-CSRF-Token", "Fetch");
                        HttpTransport.Outcome f = transport.execute(r.tenantId(), HttpTransport.Call.get(URI.create(root + "/"), fetch), deadline);
                        if (!(f instanceof HttpTransport.Outcome.Response fr) || !fr.is2xx() || fr.header("X-CSRF-Token").isEmpty()) {
                            return ConnectorResult.TransientFailure.noEffect("could not fetch the CSRF token: " + (f instanceof HttpTransport.Outcome.Response x ? "HTTP " + x.status() : f.toString()));
                        }
                        Map<String, String> h = new LinkedHashMap<>(base);
                        h.put("Content-Type", "application/json");
                        h.put("X-CSRF-Token", fr.header("X-CSRF-Token").get());
                        List<String> cookies = fr.headers().entrySet().stream().filter(e -> "set-cookie".equalsIgnoreCase(e.getKey())).flatMap(e -> e.getValue().stream())
                                .map(c -> c.split(";", 2)[0]).toList();
                        if (!cookies.isEmpty()) {
                            h.put("Cookie", String.join("; ", cookies));
                        }
                        posted = transport.execute(r.tenantId(), HttpTransport.Call.post(URI.create(root + "/" + entitySet), h, Json.bytes(r.map("fields")), false), deadline);
                        if (!(posted instanceof HttpTransport.Outcome.Response pr && pr.status() == 403
                                && "required".equalsIgnoreCase(pr.header("X-CSRF-Token").orElse("")))) {
                            break;
                        }
                    }
                    return map(posted, false,
                            resp -> ConnectorResult.Success.of(Map.of("body", Json.MAPPER.convertValue(tree(resp), Object.class)), resp.header("Location").orElse(null)));
                } catch (IllegalArgumentException e) {
                    return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
                }
            }
        };
    }

    // ---- shared --------------------------------------------------------------------------------

    private static ConnectorResult map(HttpTransport.Outcome o, boolean idempotent, java.util.function.Function<HttpTransport.Outcome.Response, ConnectorResult> ok) {
        return switch (o) {
            case HttpTransport.Outcome.NoEffect n -> ConnectorResult.TransientFailure.noEffect(n.reason());
            case HttpTransport.Outcome.Unknown u -> idempotent ? new ConnectorResult.TransientFailure(u.reason(), false, null) : ConnectorResult.OutcomeUnknown.of(u.reason());
            case HttpTransport.Outcome.Response r -> {
                int s = r.status();
                if (r.is2xx()) {
                    yield ok.apply(r);
                }
                if (s == 429 || s == 503) {
                    yield new ConnectorResult.TransientFailure("HTTP " + s, true, r.retryAfter().orElse(null));
                }
                if (s >= 500) {
                    yield idempotent ? new ConnectorResult.TransientFailure("HTTP " + s, false, null) : ConnectorResult.OutcomeUnknown.of("HTTP " + s + " after send");
                }
                yield ConnectorResult.BusinessFailure.of("HTTP_" + s, r.bodyString().length() > 500 ? r.bodyString().substring(0, 500) : r.bodyString());
            }
        };
    }

    private static JsonNode tree(HttpTransport.Outcome.Response r) {
        JsonNode t = Json.tree(r.body());
        return t == null ? Json.MAPPER.createObjectNode() : t;
    }

    private static String safe(String segment) {
        if (!segment.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid path segment: " + segment);
        }
        return segment;
    }
}
