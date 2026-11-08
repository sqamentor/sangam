// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.cdshooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.connectors.health.HealthHttp;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CDS Hooks 2.0 client (US-HLT-074), used for Da Vinci CRD (US-HLT-070): Sangam plays the CDS client role and calls a
 * payer's CDS service.
 *
 * <ul>
 *   <li>{@code discover}: {@code GET {base}/cds-services}; output {@code services}.</li>
 *   <li>{@code call}: {@code POST {base}/cds-services/{serviceId}} with {@code hook} (order-select, order-sign,
 *       appointment-book, encounter-start, encounter-discharge, order-dispatch), {@code hookInstance} =
 *       operationId, {@code context}, {@code prefetch}, {@code fhirServer} and {@code fhirAuthorization}. The request
 *       is authenticated with a client JWT (ES384 or RS384, {@code aud} = service URL, {@code iss} = client).
 *       Output {@code cards}, {@code systemActions}, and the CRD coverage summary {@code coverageDecision}
 *       (covered / not-covered / conditional), {@code paNeeded} (auth-needed / no-auth / satisfied / …),
 *       {@code docNeeded} (clinical / admin / both / conditional), {@code questionnaires} (DTR links).</li>
 *   <li>{@code feedback}: {@code POST {base}/cds-services/{serviceId}/feedback}.</li>
 * </ul>
 * Calls have no effect at the service (advisory), so the connector is class (a).
 */
public final class CdsHooksConnector implements SangamConnector {

    public static final String TYPE = "cds-hooks";
    public static final String EXT_COVERAGE_INFO = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";

    /**
     * @param baseUri   service base (the part before {@code /cds-services})
     * @param clientIss JWT issuer (the CDS client's URL)
     * @param jwtKey    signing key; null = no Authorization header (simulator)
     * @param keyId     JWKS kid
     */
    public record Config(String id, URI baseUri, String clientIss, PrivateKey jwtKey, String keyId,
                         HttpTransport transport, ConnectorProfile profile, Duration timeout) {
        public Config {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(transport, "transport");
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
    }

    private final Config config;
    private final ConnectorDescriptor descriptor;

    public CdsHooksConnector(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.descriptor = new ConnectorDescriptor(config.id(), TYPE, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of("discover", "call", "feedback"), config.profile());
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        try {
            return switch (r.operation()) {
                case "discover" -> {
                    URI u = HealthHttp.join(config.baseUri(), "/cds-services");
                    HttpTransport.Outcome o = config.transport().execute(r.tenantId(),
                            new HttpTransport.Call("GET", u, headers(u), null, config.timeout(), true), HealthHttp.deadline(r));
                    if (!(o instanceof HttpTransport.Outcome.Response resp) || !resp.is2xx()) {
                        yield HealthHttp.failure("CDS " + config.id(), o, true);
                    }
                    JsonNode n = Json.tree(resp.body());
                    yield ConnectorResult.Success.of(HealthHttp.noNulls(Map.of("services",
                            Json.MAPPER.convertValue(n == null ? Json.MAPPER.createArrayNode() : n.path("services"), List.class))), null);
                }
                case "call" -> call(r);
                case "feedback" -> {
                    URI u = HealthHttp.join(config.baseUri(), "/cds-services/" + r.required("serviceId") + "/feedback");
                    Object fb = r.input().get("feedback");
                    byte[] body = Json.write(fb == null ? Map.of("feedback", List.of()) : fb).getBytes(StandardCharsets.UTF_8);
                    HttpTransport.Outcome o = config.transport().execute(r.tenantId(), new HttpTransport.Call("POST", u,
                            headers(u), body, config.timeout(), true), HealthHttp.deadline(r));
                    if (!(o instanceof HttpTransport.Outcome.Response resp) || !resp.is2xx()) {
                        yield HealthHttp.failure("CDS " + config.id(), o, true);
                    }
                    yield ConnectorResult.Success.of(Map.of("status", resp.status()), null);
                }
                default -> ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION", r.operation());
            };
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_INPUT", e.getMessage());
        }
    }

    private ConnectorResult call(ConnectorRequest r) {
        URI u = HealthHttp.join(config.baseUri(), "/cds-services/" + r.required("serviceId"));
        ObjectNode req = Json.MAPPER.createObjectNode();
        req.put("hook", r.required("hook"));
        req.put("hookInstance", r.operationId().toString());
        if (r.str("fhirServer") != null) {
            req.put("fhirServer", r.str("fhirServer"));
        }
        Object fa = r.input().get("fhirAuthorization");
        if (fa != null) {
            req.set("fhirAuthorization", Json.MAPPER.valueToTree(fa));
        }
        req.set("context", Json.MAPPER.valueToTree(r.input().getOrDefault("context", Map.of())));
        Object pf = r.input().get("prefetch");
        if (pf != null) {
            req.set("prefetch", Json.MAPPER.valueToTree(pf));
        }
        HttpTransport.Outcome o = config.transport().execute(r.tenantId(), new HttpTransport.Call("POST", u, headers(u),
                Json.write(req).getBytes(StandardCharsets.UTF_8), HealthHttp.timeout(config.timeout(), r), true),
                HealthHttp.deadline(r));
        if (!(o instanceof HttpTransport.Outcome.Response resp) || !resp.is2xx()) {
            return HealthHttp.failure("CDS " + config.id(), o, true);
        }
        JsonNode n = Json.tree(resp.body());
        if (n == null) {
            return ConnectorResult.OutcomeUnknown.of("CDS " + config.id() + ": unreadable response");
        }
        return ConnectorResult.Success.of(summarise(n), null);
    }

    /** Cards, system actions and the CRD coverage-information summary. */
    public static Map<String, Object> summarise(JsonNode n) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", Json.MAPPER.convertValue(n.path("cards"), List.class));
        out.put("systemActions", Json.MAPPER.convertValue(n.path("systemActions"), List.class));
        List<Object> questionnaires = new ArrayList<>();
        String covered = null;
        String pa = null;
        String doc = null;
        List<JsonNode> resources = new ArrayList<>();
        for (JsonNode a : n.path("systemActions")) {
            resources.add(a.path("resource"));
        }
        for (JsonNode c : n.path("cards")) {
            for (JsonNode s : c.path("suggestions")) {
                for (JsonNode a : s.path("actions")) {
                    resources.add(a.path("resource"));
                }
            }
        }
        for (JsonNode res : resources) {
            for (JsonNode ext : res.path("extension")) {
                if (!EXT_COVERAGE_INFO.equals(ext.path("url").asText())) {
                    continue;
                }
                for (JsonNode sub : ext.path("extension")) {
                    switch (sub.path("url").asText()) {
                        case "covered" -> covered = covered == null ? sub.path("valueCode").asText() : covered;
                        case "pa-needed" -> pa = pa == null ? sub.path("valueCode").asText() : pa;
                        case "doc-needed" -> doc = doc == null ? sub.path("valueCode").asText() : doc;
                        case "questionnaire" -> questionnaires.add(sub.path("valueCanonical").asText());
                        default -> {
                        }
                    }
                }
            }
        }
        out.put("coverageDecision", covered == null ? "unknown" : covered);
        out.put("paNeeded", pa == null ? "unknown" : pa);
        out.put("docNeeded", doc == null ? "none" : doc);
        out.put("questionnaires", questionnaires);
        out.put("paRequired", "auth-needed".equals(pa));
        return HealthHttp.noNulls(out);
    }

    private Map<String, String> headers(URI audience) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        if (config.jwtKey() != null) {
            h.put("Authorization", "Bearer " + jwt(audience));
        }
        return h;
    }

    private String jwt(URI aud) {
        boolean ec = config.jwtKey() instanceof ECPrivateKey;
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", ec ? "ES384" : "RS384");
        header.put("typ", "JWT");
        if (config.keyId() != null) {
            header.put("kid", config.keyId());
        }
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", config.clientIss());
        claims.put("aud", aud.toString());
        claims.put("exp", now + 300);
        claims.put("iat", now);
        claims.put("jti", UUID.randomUUID().toString());
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String input = enc.encodeToString(Json.write(header).getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        try {
            Signature s = Signature.getInstance(ec ? "SHA384withECDSAinP1363Format" : "SHA384withRSA");
            s.initSign(config.jwtKey());
            s.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + enc.encodeToString(s.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign the CDS Hooks JWT", e);
        }
    }
}
