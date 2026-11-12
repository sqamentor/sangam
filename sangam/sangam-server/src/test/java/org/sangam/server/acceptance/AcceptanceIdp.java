// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * A minimal OIDC token issuer for the acceptance suite, so tests authenticate exactly as production does: RS256 JWTs
 * verified by the server's own {@code OidcConfig} decoder (issuer, expiry and audience checks) against a JWKS served
 * over HTTP. Any number of distinct people, tenants and roles can be issued, which maker-checker (two different
 * people) and per-tenant runs need. A fresh RSA key per JVM; nothing is committed.
 *
 * <p>SL-70: a second, separate issuer ({@link #platformIssuer()}, own key, OIDC discovery document) stands for the
 * platform realm. Only its tokens may carry the platform administrator role, and only when the server lists it in
 * {@code sangam.security.platform-issuer}; {@link #token} is always a tenant-realm token.
 */
public final class AcceptanceIdp {

    /** Audience the server checks by default ({@code SANGAM_SECURITY_OIDC_AUDIENCE}). */
    public static final String AUDIENCE = "sangam-server";

    private static AcceptanceIdp instance;

    private final RSAKey key;
    private final RSAKey platformKey;
    private final HttpServer server;
    private final String issuer;
    private final String platformIssuer;

    private AcceptanceIdp() throws IOException, JOSEException {
        key = rsa();
        platformKey = rsa();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serve("/jwks", new JWKSet(key.toPublicJWK()).toString());
        serve("/platform/jwks", new JWKSet(platformKey.toPublicJWK()).toString());
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        issuer = base + "/acceptance";
        platformIssuer = base + "/platform";
        serve("/platform/.well-known/openid-configuration", "{\"issuer\":\"" + platformIssuer + "\",\"jwks_uri\":\""
                + base + "/platform/jwks\",\"subject_types_supported\":[\"public\"],"
                + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}");
    }

    private static RSAKey rsa() throws JOSEException {
        return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256).generate();
    }

    private void serve(String path, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
    }

    public static synchronized AcceptanceIdp get() {
        if (instance == null) {
            try {
                instance = new AcceptanceIdp();
            } catch (IOException | JOSEException e) {
                throw new IllegalStateException("cannot start the acceptance token issuer", e);
            }
        }
        return instance;
    }

    public String issuer() {
        return issuer;
    }

    /** The platform realm's issuer (SL-70): list it in {@code sangam.security.platform-issuer} to trust it. */
    public String platformIssuer() {
        return platformIssuer;
    }

    public String jwksUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    /** A signed tenant-realm access token for {@code person} in {@code tenant} with the given groups, valid one hour. */
    public String token(String tenant, String person, List<String> groups) {
        return sign(issuer, key, tenant, person, groups);
    }

    /** A signed platform-realm access token (SL-70; home tenant {@code tenant}), valid for one hour. */
    public String platformToken(String tenant, String person, List<String> groups) {
        return sign(platformIssuer, platformKey, tenant, person, groups);
    }

    /**
     * A forgery for negative tests: a tenant-realm token (tenant key) that claims the platform issuer. The server must
     * refuse it, because a platform issuer's tokens are verified only against that issuer's own keys.
     */
    public String forgedPlatformToken(String tenant, String person, List<String> groups) {
        return sign(platformIssuer, key, tenant, person, groups);
    }

    private static String sign(String issuer, RSAKey key, String tenant, String person, List<String> groups) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject(person).audience(AUDIENCE)
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(3600))).jwtID(UUID.randomUUID().toString())
                .claim("tenant", tenant).claim("preferred_username", person).claim("roles", groups)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }
}
