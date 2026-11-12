// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.parichay;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.http.Jwt;
import org.sangam.integration.sim.SimHttpServer;

/**
 * Parichay / Jan Parichay OIDC identity-provider simulator (M-077 request of W3-D; ADR-D-13: the public OIDC behaviour
 * of a government single sign-on, simulated). Routes:
 * <ul>
 *   <li>{@code GET /.well-known/openid-configuration}: issuer, authorization, token and JWKS endpoints, RS256.</li>
 *   <li>{@code GET /jwks}: the signing key as a JWKS document.</li>
 *   <li>{@code POST /token}: {@code grant_type=authorization_code} with a code from {@link #login}; answers
 *       {@code id_token} (RS256), {@code access_token}, {@code token_type}, {@code expires_in}. A code works once.</li>
 * </ul>
 * The ID token carries {@code iss}, {@code aud} (the client id), {@code iat}, {@code exp}, {@code nonce} when given, and
 * the claim set of the login. {@link #FIXTURE_CLAIMS} is the default claim set: {@code sub}, {@code email},
 * {@code email_verified}, {@code name}, {@code acr}, {@code amr}, {@code tenant}.
 */
public final class ParichayIdpSimulator extends SimHttpServer {

    public static final String CLIENT_ID = "sim-parichay-client";
    /** Development literal; never valid against a real IdP. */
    public static final String CLIENT_SECRET = "sim-parichay-secret";
    public static final String KEY_ID = "sim-parichay-1";

    /** The default claims of a login (T89 fixture). */
    public static final Map<String, Object> FIXTURE_CLAIMS = fixture();

    private record Login(Map<String, Object> claims, String nonce) {
    }

    private final KeyPair key;
    private final Map<String, Login> codes = new LinkedHashMap<>();

    public ParichayIdpSimulator() {
        super("parichay-idp");
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            this.key = g.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        route("GET", "/.well-known/openid-configuration", this::discovery);
        route("GET", "/jwks", r -> Response.json(200, Json.write(Map.of("keys",
                List.of(Jwt.jwk((RSAPublicKey) key.getPublic(), KEY_ID))))));
        route("POST", "/token", this::token);
    }

    public String issuer() {
        return baseUri().toString();
    }

    public RSAPublicKey publicKey() {
        return (RSAPublicKey) key.getPublic();
    }

    /** A person logs in with {@code claims} (null = {@link #FIXTURE_CLAIMS}); returns the one-time code. */
    public String login(Map<String, Object> claims, String nonce) {
        String code = UUID.randomUUID().toString();
        synchronized (codes) {
            codes.put(code, new Login(claims == null ? FIXTURE_CLAIMS : Map.copyOf(claims), nonce));
        }
        return code;
    }

    /** An ID token for {@code claims} directly (tests of a token consumer). */
    public String idToken(Map<String, Object> claims, String nonce) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> c = new LinkedHashMap<>(claims == null ? FIXTURE_CLAIMS : claims);
        c.put("iss", issuer());
        c.put("aud", CLIENT_ID);
        c.put("iat", now);
        c.put("exp", now + 600);
        if (nonce != null) {
            c.put("nonce", nonce);
        }
        return Jwt.signRs256(KEY_ID, c, key.getPrivate());
    }

    private Response discovery(Request req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", issuer());
        m.put("authorization_endpoint", uri("/authorize").toString());
        m.put("token_endpoint", uri("/token").toString());
        m.put("jwks_uri", uri("/jwks").toString());
        m.put("response_types_supported", List.of("code"));
        m.put("subject_types_supported", List.of("public"));
        m.put("id_token_signing_alg_values_supported", List.of("RS256"));
        m.put("scopes_supported", List.of("openid", "profile", "email"));
        m.put("claims_supported", List.copyOf(FIXTURE_CLAIMS.keySet()));
        return Response.json(200, Json.write(m));
    }

    private Response token(Request req) {
        Map<String, String> f = new LinkedHashMap<>();
        for (String p : req.bodyString().split("&")) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                f.put(URLDecoder.decode(p.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        if (!CLIENT_ID.equals(f.get("client_id")) || !CLIENT_SECRET.equals(f.get("client_secret"))) {
            return Response.json(401, "{\"error\":\"invalid_client\"}");
        }
        Login l;
        synchronized (codes) {
            l = codes.remove(String.valueOf(f.get("code")));
        }
        if (!"authorization_code".equals(f.get("grant_type")) || l == null) {
            return Response.json(400, "{\"error\":\"invalid_grant\"}");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id_token", idToken(l.claims(), l.nonce()));
        m.put("access_token", "sim-parichay-" + UUID.randomUUID());
        m.put("token_type", "Bearer");
        m.put("expires_in", 600);
        return Response.json(200, Json.write(m));
    }

    private static Map<String, Object> fixture() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sub", "sim-officer-001");
        m.put("email", "officer@sim.gov.invalid");
        m.put("email_verified", true);
        m.put("name", "Simulated Officer");
        m.put("acr", "urn:sangam:acr:mfa");
        m.put("amr", List.of("pwd", "otp"));
        m.put("tenant", "sim-tenant");
        return java.util.Collections.unmodifiableMap(m);
    }
}
