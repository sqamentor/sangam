// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.connectors.health.HealthHttp;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generic FHIR R4 REST client on a JSON model (US-HLT-075). It is version- and profile-neutral, so the same client
 * serves US Core servers, Da Vinci payer endpoints and the ABDM gateway / HIP / HIU FHIR bundles.
 *
 * <p>Resources are plain Jackson trees ({@link JsonNode}); HAPI FHIR structures were not used because the R4
 * structures jar alone is about 20 MB, which is out of proportion for a workflow layer that only routes resources
 * (ADR-D-22: full clinical records stay in the EHR).
 *
 * <p>Every call carries {@code X-Request-Id} = operationId (R36), {@code Accept: application/fhir+json}, and an access
 * token from {@link SmartBackendAuth}. A 401 drops the cached token and the call is retried once.
 */
public final class FhirClient {

    public static final String FHIR_JSON = "application/fhir+json";

    /**
     * @param baseUri   FHIR base, e.g. {@code https://payer.example/fhir/r4}
     * @param transport HTTP transport (egress policy, mTLS)
     * @param auth      token source
     * @param timeout   per-request timeout
     * @param headers   extra headers on every call (ABDM: {@code X-CM-ID}, {@code X-HIP-ID}; payer: API keys)
     */
    public record Config(URI baseUri, HttpTransport transport, SmartBackendAuth auth, Duration timeout,
                         Map<String, String> headers) {
        public Config {
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(transport, "transport");
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /** A FHIR answer: the transport outcome plus the parsed body when there was one. */
    public record Answer(HttpTransport.Outcome outcome, JsonNode body) {
        public boolean ok() {
            return outcome instanceof HttpTransport.Outcome.Response r && r.is2xx();
        }

        public int status() {
            return outcome instanceof HttpTransport.Outcome.Response r ? r.status() : -1;
        }

        public String header(String name) {
            return outcome instanceof HttpTransport.Outcome.Response r ? r.header(name).orElse(null) : null;
        }
    }

    private final Config config;

    public FhirClient(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public Config config() {
        return config;
    }

    public Answer read(String tenant, String type, String id, String requestId, long deadline) {
        return send(tenant, "GET", type + "/" + enc(id), null, Map.of(), requestId, deadline, true);
    }

    /** GET search; {@code params} values are URL-encoded here. */
    public Answer search(String tenant, String type, Map<String, String> params, String requestId, long deadline) {
        return send(tenant, "GET", type + query(params), null, Map.of(), requestId, deadline, true);
    }

    /** Next page of a searchset Bundle ({@code link[relation=next]}), or null when there is none. */
    public Answer next(String tenant, JsonNode bundle, String requestId, long deadline) {
        for (JsonNode l : bundle.path("link")) {
            if ("next".equals(l.path("relation").asText())) {
                return sendAbsolute(tenant, "GET", URI.create(l.path("url").asText()), null, Map.of(), requestId,
                        deadline, true);
            }
        }
        return null;
    }

    /** Create; with {@code ifNoneExist} (e.g. {@code identifier=urn:sangam:op|<operationId>}) it is a conditional create. */
    public Answer create(String tenant, JsonNode resource, String ifNoneExist, String requestId, long deadline) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Prefer", "return=representation");
        if (ifNoneExist != null) {
            h.put("If-None-Exist", ifNoneExist);
        }
        return send(tenant, "POST", resource.path("resourceType").asText(), resource, h, requestId, deadline,
                ifNoneExist != null);
    }

    /** Update (PUT); {@code ifMatch} is a version id for optimistic locking, may be null. */
    public Answer update(String tenant, JsonNode resource, String ifMatch, String requestId, long deadline) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Prefer", "return=representation");
        if (ifMatch != null) {
            h.put("If-Match", "W/\"" + ifMatch + "\"");
        }
        return send(tenant, "PUT", resource.path("resourceType").asText() + "/" + enc(resource.path("id").asText()),
                resource, h, requestId, deadline, true);
    }

    /** POST of a batch or transaction Bundle to the base. */
    public Answer transaction(String tenant, JsonNode bundle, String requestId, long deadline, boolean idempotent) {
        return send(tenant, "POST", "", bundle, Map.of("Prefer", "return=representation"), requestId, deadline,
                idempotent);
    }

    /** Operation, e.g. {@code Claim/$submit}, {@code Questionnaire/$questionnaire-package}, {@code Patient/$everything}. */
    public Answer operation(String tenant, String path, JsonNode parameters, String requestId, long deadline,
                            boolean idempotent) {
        return send(tenant, parameters == null ? "GET" : "POST", path, parameters, Map.of(), requestId, deadline,
                idempotent);
    }

    /** {@code .well-known/smart-configuration} of the server (token endpoint discovery). */
    public Answer smartConfiguration(String tenant, long deadline) {
        return send(tenant, "GET", ".well-known/smart-configuration", null, Map.of(), null, deadline, true);
    }

    public Answer capabilities(String tenant, long deadline) {
        return send(tenant, "GET", "metadata", null, Map.of(), null, deadline, true);
    }

    private Answer send(String tenant, String method, String path, JsonNode body, Map<String, String> extra,
                        String requestId, long deadline, boolean idempotent) {
        URI uri = path.isEmpty() ? config.baseUri() : HealthHttp.join(config.baseUri(), path);
        return sendAbsolute(tenant, method, uri, body, extra, requestId, deadline, idempotent);
    }

    private Answer sendAbsolute(String tenant, String method, URI uri, JsonNode body, Map<String, String> extra,
                                String requestId, long deadline, boolean idempotent) {
        Answer a = once(tenant, method, uri, body, extra, requestId, deadline, idempotent);
        if (a.status() == 401 && config.auth() != null) {
            config.auth().invalidate();
            a = once(tenant, method, uri, body, extra, requestId, deadline, idempotent);
        }
        return a;
    }

    private Answer once(String tenant, String method, URI uri, JsonNode body, Map<String, String> extra,
                        String requestId, long deadline, boolean idempotent) {
        Map<String, String> h = new LinkedHashMap<>(config.headers());
        h.put("Accept", FHIR_JSON);
        if (body != null) {
            h.put("Content-Type", FHIR_JSON + "; charset=utf-8");
        }
        if (requestId != null) {
            h.put("X-Request-Id", requestId);
        }
        h.putAll(extra);
        if (config.auth() != null) {
            try {
                String authz = config.auth().authorization(tenant, deadline);
                if (authz != null) {
                    h.put("Authorization", authz);
                }
            } catch (SmartBackendAuth.AuthException e) {
                return new Answer(new HttpTransport.Outcome.NoEffect("AUTH: " + e.getMessage(), 0), null);
            }
        }
        byte[] bytes = body == null ? null : Json.write(body).getBytes(StandardCharsets.UTF_8);
        HttpTransport.Call call = new HttpTransport.Call(method, uri, h, bytes, config.timeout(), idempotent);
        HttpTransport.Outcome o = config.transport().execute(tenant, call, deadline);
        JsonNode parsed = o instanceof HttpTransport.Outcome.Response r && r.body().length > 0 ? Json.tree(r.body()) : null;
        return new Answer(o, parsed);
    }

    public static String query(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        params.forEach((k, v) -> sb.append(sb.length() > 1 ? "&" : "").append(enc(k)).append('=').append(enc(v)));
        return sb.toString();
    }

    static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Resources of the entries of a Bundle. */
    public static List<JsonNode> entries(JsonNode bundle) {
        java.util.ArrayList<JsonNode> out = new java.util.ArrayList<>();
        for (JsonNode e : bundle.path("entry")) {
            if (e.has("resource")) {
                out.add(e.get("resource"));
            }
        }
        return out;
    }
}
