// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

/**
 * Google Cloud tokens over plain HTTP (M-067, M-075; no vendor SDK).
 *
 * <ul>
 *   <li><b>ID token</b> for Cloud Run, Cloud Functions and IAP-protected endpoints: {@code Authorization: Bearer <id
 *       token>} whose {@code aud} is the receiving service URL ({@link #idTokenAuthorizer}).</li>
 *   <li><b>Access token</b> for Google APIs such as Secret Manager ({@link #accessToken}).</li>
 * </ul>
 * Two sources: a <b>service-account key</b> (JSON with {@code client_email}, {@code private_key},
 * {@code private_key_id}, {@code token_uri}; read by reference through the secret port) used for the JWT-bearer grant
 * (RFC 7523: an RS256 assertion with {@code target_audience} for an ID token or {@code scope} for an access token, sent to
 * {@code token_uri}); or the <b>metadata server</b> of the workload ({@code Metadata-Flavor: Google}). Both requests go
 * through the connector's {@link HttpTransport}, so the egress allow-list applies; the metadata server is a link-local
 * address and needs an explicit egress entry with {@code allowPrivate}. Tokens are cached per tenant and audience/scope
 * until 60 seconds before they expire. A failed token request throws {@link HttpRestConnector.AuthUnavailableException}:
 * the business request is then never sent.
 */
public final class GcpTokens {

    public static final String GRANT_JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    public static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final Duration EARLY = Duration.ofSeconds(60);

    private record Key(String clientEmail, PrivateKey privateKey, String keyId, URI tokenUri) {
    }

    private record Token(String value, Instant expiresAt) {
    }

    private final Supplier<Key> key;
    private final URI metadataBase;
    private final HttpTransport transport;
    private final Clock clock;
    private final Map<String, Token> cache = new LinkedHashMap<>();

    private GcpTokens(Supplier<Key> key, URI metadataBase, HttpTransport transport, Clock clock) {
        this.key = key;
        this.metadataBase = metadataBase;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Tokens from a service-account key held by reference. The key JSON is read from the secret port when a token is
     * needed (a rotated key needs no rebuild).
     */
    public static GcpTokens serviceAccount(String keyRef, SecretResolver secrets, HttpTransport transport, Clock clock) {
        Objects.requireNonNull(keyRef, "service-account key reference");
        Objects.requireNonNull(secrets, "secrets");
        return new GcpTokens(() -> parseKey(read(secrets, keyRef)), null, transport, clock);
    }

    /** Tokens from the metadata server at {@code metadataBase} (normally {@code http://metadata.google.internal}). */
    public static GcpTokens metadata(URI metadataBase, HttpTransport transport, Clock clock) {
        Objects.requireNonNull(metadataBase, "metadata url");
        return new GcpTokens(null, metadataBase, transport, clock);
    }

    /** The token endpoint of the service-account key (null on the metadata source), for the egress allow-list. */
    public URI tokenEndpoint() {
        return key == null ? null : key.get().tokenUri();
    }

    /** An {@link HttpRestConnector.Authorizer} sending {@code Bearer <ID token for audience>}. */
    public HttpRestConnector.Authorizer idTokenAuthorizer(String audience) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("a GCP ID token needs an audience (the receiving service URL)");
        }
        return tenantId -> "Bearer " + idToken(tenantId, audience);
    }

    public synchronized String idToken(String tenantId, String audience) {
        return cached("id|" + tenantId + "|" + audience, () -> key == null ? metadataIdToken(tenantId, audience)
                : grant(tenantId, "target_audience", audience, "id_token"));
    }

    public synchronized String accessToken(String tenantId, String scope) {
        String s = scope == null || scope.isBlank() ? CLOUD_PLATFORM_SCOPE : scope;
        return cached("access|" + tenantId + "|" + s, () -> key == null ? metadataAccessToken(tenantId)
                : grant(tenantId, "scope", s, "access_token"));
    }

    private String cached(String k, Supplier<Token> fetch) {
        Token t = cache.get(k);
        Instant now = clock.instant();
        if (t == null || !now.isBefore(t.expiresAt())) {
            t = fetch.get();
            cache.put(k, t);
        }
        return t.value();
    }

    private Token grant(String tenantId, String claimName, String claimValue, String field) {
        Key k = key.get();
        long now = clock.instant().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", k.clientEmail());
        claims.put("sub", k.clientEmail());
        claims.put("aud", k.tokenUri().toString());
        claims.put("iat", now);
        claims.put("exp", now + 3600);
        claims.put(claimName, claimValue);
        String assertion = Jwt.signRs256(k.keyId(), claims, k.privateKey());
        String form = "grant_type=" + URLEncoder.encode(GRANT_JWT_BEARER, StandardCharsets.UTF_8) + "&assertion="
                + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Accept", "application/json");
        HttpTransport.Outcome o = transport.execute(tenantId, new HttpTransport.Call("POST", k.tokenUri(), h,
                form.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(15), true), 0);
        JsonNode tree = okJson(o, "token endpoint");
        String token = tree.path(field).asText(null);
        if (token == null || token.isBlank()) {
            throw new HttpRestConnector.AuthUnavailableException("token endpoint returned no " + field);
        }
        return new Token(token, expiry(token, tree.path("expires_in").asLong(0)));
    }

    private Token metadataIdToken(String tenantId, String audience) {
        URI u = metadataBase.resolve("/computeMetadata/v1/instance/service-accounts/default/identity?audience="
                + URLEncoder.encode(audience, StandardCharsets.UTF_8) + "&format=full");
        HttpTransport.Outcome o = transport.execute(tenantId, HttpTransport.Call.get(u, Map.of("Metadata-Flavor", "Google")), 0);
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            throw new HttpRestConnector.AuthUnavailableException("metadata identity: " + describe(o));
        }
        String token = r.bodyString().trim();
        if (token.isEmpty()) {
            throw new HttpRestConnector.AuthUnavailableException("metadata identity returned no token");
        }
        return new Token(token, expiry(token, 0));
    }

    private Token metadataAccessToken(String tenantId) {
        URI u = metadataBase.resolve("/computeMetadata/v1/instance/service-accounts/default/token");
        HttpTransport.Outcome o = transport.execute(tenantId, HttpTransport.Call.get(u, Map.of("Metadata-Flavor", "Google")), 0);
        JsonNode tree = okJson(o, "metadata token");
        String token = tree.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new HttpRestConnector.AuthUnavailableException("metadata token returned no access_token");
        }
        return new Token(token, expiry(token, tree.path("expires_in").asLong(0)));
    }

    /** {@code exp} of a JWT when it has one, else now + expires_in (default 5 minutes), minus the early margin. */
    private Instant expiry(String token, long expiresIn) {
        Instant now = clock.instant();
        Instant exp = Jwt.claims(token).map(c -> c.path("exp").asLong(0)).filter(v -> v > 0)
                .map(Instant::ofEpochSecond).orElse(now.plusSeconds(expiresIn > 0 ? expiresIn : 300));
        Instant early = exp.minus(EARLY);
        return early.isAfter(now) ? early : now;
    }

    private static JsonNode okJson(HttpTransport.Outcome o, String what) {
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            throw new HttpRestConnector.AuthUnavailableException(what + ": " + describe(o));
        }
        JsonNode tree = Json.tree(r.body());
        if (tree == null || !tree.isObject()) {
            throw new HttpRestConnector.AuthUnavailableException(what + " returned no JSON object");
        }
        return tree;
    }

    private static String read(SecretResolver secrets, String ref) {
        try {
            String v = secrets.require(ref);
            if (v == null || v.isBlank()) {
                throw new HttpRestConnector.AuthUnavailableException(ref + " resolves to an empty value");
            }
            return v;
        } catch (HttpRestConnector.AuthUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new HttpRestConnector.AuthUnavailableException(ref + " cannot be resolved (" + e.getMessage() + ")");
        }
    }

    private static Key parseKey(String json) {
        JsonNode n = Json.tree(json);
        if (n == null || !n.isObject()) {
            throw new HttpRestConnector.AuthUnavailableException("service-account key is not a JSON object (not shown)");
        }
        String email = n.path("client_email").asText(null);
        String pem = n.path("private_key").asText(null);
        String tokenUri = n.path("token_uri").asText("https://oauth2.googleapis.com/token");
        if (email == null || pem == null) {
            throw new HttpRestConnector.AuthUnavailableException("service-account key needs client_email and private_key");
        }
        try {
            return new Key(email, Jwt.rsaPrivateKeyFromPem(pem), n.path("private_key_id").asText(null), URI.create(tokenUri));
        } catch (IllegalArgumentException e) {
            throw new HttpRestConnector.AuthUnavailableException("service-account key: " + e.getMessage());
        }
    }

    private static String describe(HttpTransport.Outcome o) {
        return switch (o) {
            case HttpTransport.Outcome.Response r -> "HTTP " + r.status();
            case HttpTransport.Outcome.NoEffect ne -> ne.reason();
            case HttpTransport.Outcome.Unknown u -> u.reason();
        };
    }
}
