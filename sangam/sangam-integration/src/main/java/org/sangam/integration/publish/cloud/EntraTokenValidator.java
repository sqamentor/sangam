// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.cloud;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;

/**
 * Owner decision 219: validates the Microsoft Entra bearer token that Event Grid sends with every delivery to a secured
 * webhook ("Event Grid passes the Microsoft Entra Bearer token to the webhook client in every message",
 * learn.microsoft.com/en-us/azure/event-grid/secure-webhook-delivery, read 2026-11-13). The rules follow
 * learn.microsoft.com/en-us/entra/identity-platform/access-tokens (read 2026-11-13):
 *
 * <ul>
 *   <li>{@code Authorization: Bearer <JWT>}; header {@code alg} RS256 with a {@code kid}; no {@code crit}.</li>
 *   <li>{@code tid} equals the configured tenant; {@code iss} is exactly {@code https://sts.windows.net/{tid}/} (v1.0) or
 *       {@code {authority}/{tid}/v2.0} (v2.0). The key set is fetched only from the configured tenant
 *       ({@code {authority}/{tenant}/discovery/keys} or {@code .../discovery/v2.0/keys}), never from a URL in the token.</li>
 *   <li>The signature with the key of that {@code kid} (RSA, at least 2048 bits, {@code use} sig or absent); a key
 *       {@code issuer} property, exact or with the {@code {tenantid}} template, must name the same issuer.</li>
 *   <li>{@code aud} is one of the configured audiences (the webhook application ID or application ID URI);
 *       {@code exp} and {@code nbf} hold with {@value #SKEW_MINUTES} minutes of clock skew.</li>
 *   <li>{@code roles} contains the required app role (default {@value #DEFAULT_ROLE}, the role Microsoft's setup
 *       script assigns to the Event Grid service principal).</li>
 * </ul>
 *
 * Key sets are cached for at most 24 hours and fetched again, at most once every 5 minutes, when a token names an
 * unknown {@code kid} (key rollover). Every failure is a {@link SecurityException} except a key set that cannot be
 * fetched, which is {@link KeysUnavailable} so the endpoint can answer 503 and Event Grid retries. Messages never
 * contain the token.
 */
public final class EntraTokenValidator {

    public static final String DEFAULT_AUTHORITY = "https://login.microsoftonline.com";
    public static final String DEFAULT_ROLE = "AzureEventGridSecureWebhookSubscriber";
    static final long SKEW_MINUTES = 5;
    static final Duration KEY_TTL = Duration.ofHours(24);
    static final Duration MIN_REFETCH = Duration.ofMinutes(5);
    static final int MAX_TOKEN_CHARS = 16 * 1024;
    static final int MIN_RSA_BITS = 2048;

    /** Fetches a JSON Web Key Set document. */
    @FunctionalInterface
    public interface KeySource {
        /** The JWKS JSON; throws {@link KeysUnavailable} when it cannot be fetched. */
        String fetch(URI jwksUri);
    }

    /** The key set could not be fetched: a transient condition, never an acceptance. */
    public static final class KeysUnavailable extends RuntimeException {
        public KeysUnavailable(String message) {
            super(message);
        }
    }

    /** What the channel configures. */
    public record Expected(String tenantId, Set<String> audiences, String requiredRole, String authority) {
        public Expected {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("entraTenantId is required");
            }
            tenantId = tenantId.trim().toLowerCase(Locale.ROOT);
            audiences = audiences == null ? Set.of() : Set.copyOf(audiences);
            if (audiences.isEmpty()) {
                throw new IllegalArgumentException("entraAudience is required");
            }
            requiredRole = requiredRole == null ? DEFAULT_ROLE : requiredRole.trim();
            if (requiredRole.isEmpty()) {
                throw new IllegalArgumentException("entraRequiredRole cannot be blank");
            }
            authority = authority == null || authority.isBlank() ? DEFAULT_AUTHORITY : stripSlash(authority.trim());
            if (!authority.startsWith("https://")) {
                throw new IllegalArgumentException("entraAuthority must be https");
            }
        }

        /** From the channel properties entraTenantId, entraAudience (comma-separated), entraRequiredRole, entraAuthority. */
        public static Expected of(Map<String, String> properties) {
            Set<String> aud = new LinkedHashSet<>();
            String a = properties.get("entraAudience");
            if (a != null) {
                for (String s : a.split(",")) {
                    if (!s.isBlank()) {
                        aud.add(s.trim());
                    }
                }
            }
            return new Expected(properties.get("entraTenantId"), aud, properties.get("entraRequiredRole"),
                    properties.get("entraAuthority"));
        }
    }

    /** The authenticated caller. */
    public record Caller(String issuer, String tenantId, String appId, String objectId, Set<String> roles, Instant expires) {
    }

    private record KeySet(Instant fetched, Map<String, JsonNode> byKid) {
    }

    private final KeySource keys;
    private final Clock clock;
    private final Map<URI, KeySet> cache = new ConcurrentHashMap<>();

    public EntraTokenValidator(KeySource keys, Clock clock) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The production key source: a GET through the egress-controlled transport; anything but a 2xx JSON is unavailable. */
    public static KeySource http(HttpTransport transport) {
        Objects.requireNonNull(transport, "transport");
        return uri -> {
            HttpTransport.Outcome o;
            try {
                o = transport.execute("system", HttpTransport.Call.get(uri, Map.of("Accept", "application/json")), 0);
            } catch (RuntimeException e) {
                throw new KeysUnavailable("Entra signing keys cannot be fetched from " + uri + ": " + e.getClass().getSimpleName());
            }
            if (o instanceof HttpTransport.Outcome.Response r && r.is2xx()) {
                return r.bodyString();
            }
            throw new KeysUnavailable("Entra signing keys cannot be fetched from " + uri + ": " + CloudChannels.describe(o));
        };
    }

    /** Validates the {@code Authorization} header value; returns the caller or throws. */
    public Caller validate(String authorization, Expected expected) {
        Objects.requireNonNull(expected, "expected");
        String token = bearer(authorization);
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw denied("not a signed JWT");
        }
        JsonNode header = jsonPart(parts[0]);
        JsonNode claims = jsonPart(parts[1]);
        if (!"RS256".equals(header.path("alg").asText(null))) {
            throw denied("only RS256 tokens are accepted");
        }
        if (header.has("crit")) {
            throw denied("critical header parameters are not supported");
        }
        String kid = header.path("kid").asText("");
        if (kid.isBlank()) {
            throw denied("the token names no signing key");
        }
        // the issuer and tenant are checked before any key is fetched; the key set URI comes from configuration only
        String tid = text(claims, "tid").toLowerCase(Locale.ROOT);
        if (!tid.equals(expected.tenantId())) {
            throw denied("the token is not from the configured tenant");
        }
        String iss = text(claims, "iss");
        boolean v1 = iss.equals("https://sts.windows.net/" + tid + "/");
        boolean v2 = iss.equals(expected.authority() + "/" + tid + "/v2.0");
        if (!v1 && !v2) {
            throw denied("unexpected issuer");
        }
        URI jwks = URI.create(expected.authority() + "/" + expected.tenantId() + (v1 ? "/discovery/keys" : "/discovery/v2.0/keys"));
        JsonNode jwk = key(jwks, kid);
        verifySignature(jwk, parts, iss, tid);

        Instant now = clock.instant();
        long skew = Duration.ofMinutes(SKEW_MINUTES).toSeconds();
        if (!claims.path("exp").canConvertToLong() || now.getEpochSecond() > claims.path("exp").asLong() + skew) {
            throw denied("the token has expired");
        }
        if (claims.has("nbf") && (!claims.path("nbf").canConvertToLong()
                || now.getEpochSecond() + skew < claims.path("nbf").asLong())) {
            throw denied("the token is not yet valid");
        }
        if (!audienceMatches(claims.path("aud"), expected.audiences())) {
            throw denied("the token is for another audience");
        }
        Set<String> roles = new LinkedHashSet<>();
        claims.path("roles").forEach(r -> roles.add(r.asText()));
        if (!roles.contains(expected.requiredRole())) {
            throw denied("the caller lacks the required app role");
        }
        String appId = claims.path(v1 ? "appid" : "azp").asText(null);
        return new Caller(iss, tid, appId, claims.path("oid").asText(null), Set.copyOf(roles),
                Instant.ofEpochSecond(claims.path("exp").asLong()));
    }

    private JsonNode key(URI jwks, String kid) {
        Instant now = clock.instant();
        KeySet ks = cache.get(jwks);
        if (ks == null || !now.isBefore(ks.fetched().plus(KEY_TTL))) {
            ks = load(jwks, now);
        } else if (!ks.byKid().containsKey(kid) && !now.isBefore(ks.fetched().plus(MIN_REFETCH))) {
            ks = load(jwks, now);   // key rollover
        }
        JsonNode k = ks.byKid().get(kid);
        if (k == null) {
            throw denied("the signing key is not in the tenant's key set");
        }
        return k;
    }

    private KeySet load(URI jwks, Instant now) {
        String body;
        try {
            body = keys.fetch(jwks);
        } catch (KeysUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KeysUnavailable("the Entra key set from " + jwks + " cannot be fetched: " + e.getClass().getSimpleName());
        }
        JsonNode doc = body == null ? null : Json.tree(body);
        if (doc == null || !doc.path("keys").isArray()) {
            throw new KeysUnavailable("the Entra key set from " + jwks + " is not a JSON Web Key Set");
        }
        Map<String, JsonNode> byKid = new HashMap<>();
        doc.path("keys").forEach(k -> {
            String id = k.path("kid").asText("");
            if (!id.isBlank()) {
                byKid.put(id, k);
            }
        });
        KeySet ks = new KeySet(now, Map.copyOf(byKid));
        cache.put(jwks, ks);
        return ks;
    }

    private static void verifySignature(JsonNode jwk, String[] parts, String iss, String tid) {
        if (!"RSA".equals(jwk.path("kty").asText()) || (jwk.has("use") && !"sig".equals(jwk.path("use").asText()))) {
            throw denied("the named key is not an RSA signing key");
        }
        if (jwk.has("issuer") && !iss.equals(jwk.path("issuer").asText().replace("{tenantid}", tid))) {
            throw denied("the signing key belongs to another issuer");
        }
        try {
            BigInteger n = new BigInteger(1, b64(jwk.path("n").asText("")));
            BigInteger e = new BigInteger(1, b64(jwk.path("e").asText("")));
            RSAPublicKey pk = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
            if (pk.getModulus().bitLength() < MIN_RSA_BITS) {
                throw denied("the signing key is too short");
            }
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(pk);
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!s.verify(b64(parts[2]))) {
                throw denied("the token signature is not valid");
            }
        } catch (GeneralSecurityException | IllegalArgumentException | ArithmeticException ex) {
            throw denied("the token signature cannot be verified");
        }
    }

    private static boolean audienceMatches(JsonNode aud, Set<String> accepted) {
        if (aud.isTextual()) {
            return accepted.contains(aud.asText());
        }
        if (aud.isArray()) {
            for (JsonNode a : aud) {
                if (a.isTextual() && accepted.contains(a.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String bearer(String authorization) {
        if (authorization == null) {
            throw denied("no bearer token");
        }
        String v = authorization.trim();
        if (v.length() < 8 || !v.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw denied("no bearer token");
        }
        String token = v.substring(7).trim();
        if (token.isEmpty() || token.length() > MAX_TOKEN_CHARS) {
            throw denied("no usable bearer token");
        }
        return token;
    }

    private static JsonNode jsonPart(String part) {
        JsonNode n;
        try {
            n = Json.tree(new String(b64(part), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw denied("the token is malformed");
        }
        if (n == null || !n.isObject()) {
            throw denied("the token is malformed");
        }
        return n;
    }

    private static String text(JsonNode claims, String name) {
        JsonNode v = claims.path(name);
        if (!v.isTextual() || v.asText().isBlank()) {
            throw denied("the token has no " + name + " claim");
        }
        return v.asText();
    }

    private static byte[] b64(String s) {
        if (s.indexOf('=') >= 0 || s.indexOf('+') >= 0 || s.indexOf('/') >= 0) {
            throw new IllegalArgumentException("not base64url without padding");
        }
        return Base64.getUrlDecoder().decode(s);
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static SecurityException denied(String why) {
        return new SecurityException("Entra delivery token rejected: " + why);
    }
}
