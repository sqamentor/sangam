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
 */
public final class AcceptanceIdp {

    /** Audience the server checks by default ({@code SANGAM_SECURITY_OIDC_AUDIENCE}). */
    public static final String AUDIENCE = "sangam-server";

    private static AcceptanceIdp instance;

    private final RSAKey key;
    private final HttpServer server;
    private final String issuer;

    private AcceptanceIdp() throws IOException, JOSEException {
        key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256).generate();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/jwks", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, jwks.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(jwks);
            }
        });
        server.start();
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/acceptance";
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

    public String jwksUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    /** A signed access token for {@code person} in {@code tenant} with the given groups, valid for one hour. */
    public String token(String tenant, String person, List<String> groups) {
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
