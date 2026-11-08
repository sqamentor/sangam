// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Access tokens for FHIR servers (US-HLT-077).
 *
 * <ul>
 *   <li>{@link Mode#SMART_BACKEND}: SMART Backend Services. OAuth 2.0 client credentials with a signed JWT client
 *       assertion ({@code private_key_jwt}): {@code iss} = {@code sub} = client id, {@code aud} = token endpoint,
 *       {@code exp} at most 5 minutes ahead, unique {@code jti}; RS384 (RSA key) or ES384 (EC P-384 key, JOSE
 *       signature format).</li>
 *   <li>{@link Mode#CLIENT_SECRET}: client credentials with a client secret (HTTP Basic). Used by ABDM gateway
 *       sessions and some payer sandboxes.</li>
 *   <li>{@link Mode#STATIC}: a fixed bearer token (simulator, development).</li>
 *   <li>{@link Mode#NONE}: no Authorization header.</li>
 * </ul>
 * Tokens are cached per instance until 30 s before expiry. The class never logs the token or the key.
 */
public final class SmartBackendAuth {

    public enum Mode { SMART_BACKEND, CLIENT_SECRET, STATIC, NONE }

    /**
     * @param mode          how to get a token
     * @param tokenUri      token endpoint (SMART: from {@code .well-known/smart-configuration})
     * @param clientId      registered client id
     * @param scope         e.g. {@code system/Patient.rs system/Coverage.rs}
     * @param privateKey    RSA or EC P-384 key for SMART_BACKEND
     * @param keyId         {@code kid} of the public key in the client's JWKS
     * @param secret        client secret (CLIENT_SECRET) or the static token (STATIC); a resolved value, never logged
     */
    public record Config(Mode mode, URI tokenUri, String clientId, String scope, PrivateKey privateKey, String keyId,
                         String secret) {
        public Config {
            Objects.requireNonNull(mode, "mode");
        }

        public static Config none() {
            return new Config(Mode.NONE, null, null, null, null, null, null);
        }

        public static Config staticToken(String token) {
            return new Config(Mode.STATIC, null, null, null, null, null, token);
        }
    }

    /** Thrown when no token can be obtained; the request was not sent (no effect). */
    public static final class AuthException extends RuntimeException {
        private final boolean definite;

        public AuthException(String message, boolean definite) {
            super(message);
            this.definite = definite;
        }

        /** True when the authorization server refused the client (4xx); false for a transport problem. */
        public boolean definite() {
            return definite;
        }
    }

    private final Config config;
    private final HttpTransport transport;
    private final Clock clock;
    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    public SmartBackendAuth(Config config, HttpTransport transport, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = transport;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** {@code Authorization} header value, or null for {@link Mode#NONE}. */
    public String authorization(String tenantId, long deadlineMillis) {
        return switch (config.mode()) {
            case NONE -> null;
            case STATIC -> "Bearer " + config.secret();
            case SMART_BACKEND, CLIENT_SECRET -> "Bearer " + token(tenantId, deadlineMillis);
        };
    }

    /** Drops the cached token (after a 401 from the resource server). */
    public void invalidate() {
        token = null;
        expiresAt = Instant.EPOCH;
    }

    private synchronized String token(String tenantId, long deadlineMillis) {
        Instant now = clock.instant();
        if (token != null && now.isBefore(expiresAt.minusSeconds(30))) {
            return token;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        if (config.scope() != null) {
            form.put("scope", config.scope());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        if (config.mode() == Mode.SMART_BACKEND) {
            form.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
            form.put("client_assertion", assertion(now));
        } else {
            String basic = Base64.getEncoder().encodeToString((config.clientId() + ":" + config.secret())
                    .getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", "Basic " + basic);
        }
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> body.append(body.isEmpty() ? "" : "&").append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpTransport.Call call = new HttpTransport.Call("POST", config.tokenUri(), headers,
                body.toString().getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(15), true);
        HttpTransport.Outcome o = transport.execute(tenantId, call, deadlineMillis);
        if (!(o instanceof HttpTransport.Outcome.Response r)) {
            throw new AuthException("token endpoint not reachable: " + o, false);
        }
        if (!r.is2xx()) {
            throw new AuthException("token endpoint answered HTTP " + r.status(), r.status() >= 400 && r.status() < 500
                    && r.status() != 429 && r.status() != 408);
        }
        JsonNode n = Json.tree(r.body());
        if (n == null || !n.path("access_token").isTextual()) {
            throw new AuthException("token endpoint answer has no access_token", false);
        }
        token = n.path("access_token").asText();
        expiresAt = now.plusSeconds(n.path("expires_in").asLong(300));
        return token;
    }

    /** The signed client assertion (public for tests and for ABDM-style reuse). */
    public String assertion(Instant now) {
        PrivateKey key = Objects.requireNonNull(config.privateKey(), "privateKey");
        boolean ec = key instanceof ECPrivateKey;
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", ec ? "ES384" : "RS384");
        header.put("typ", "JWT");
        if (config.keyId() != null) {
            header.put("kid", config.keyId());
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", config.clientId());
        claims.put("sub", config.clientId());
        claims.put("aud", config.tokenUri().toString());
        claims.put("exp", now.plusSeconds(240).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        String signingInput = b64(Json.write(header).getBytes(StandardCharsets.UTF_8)) + "."
                + b64(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        try {
            Signature sig = Signature.getInstance(ec ? "SHA384withECDSAinP1363Format" : "SHA384withRSA");
            sig.initSign(key);
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64(sig.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new AuthException("cannot sign the client assertion: " + e.getClass().getSimpleName(), true);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
