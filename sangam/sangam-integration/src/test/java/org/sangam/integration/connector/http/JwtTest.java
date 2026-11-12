// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** M-067: the RS256 JWT helper used for Google tokens and the identity simulators. */
class JwtTest {

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    @Test
    void aSignedTokenVerifiesAndDecodes() throws Exception {
        KeyPair k = rsa();
        String jwt = Jwt.signRs256("kid-1", Map.of("sub", "s", "aud", "a"), k.getPrivate());
        assertEquals("RS256", Jwt.header(jwt).orElseThrow().path("alg").asText());
        assertEquals("kid-1", Jwt.header(jwt).orElseThrow().path("kid").asText());
        assertEquals("s", Jwt.verifyRs256(jwt, k.getPublic()).orElseThrow().path("sub").asText());
    }

    @Test
    void anotherKeyOrATamperedPayloadIsRefused() throws Exception {
        KeyPair k = rsa();
        String jwt = Jwt.signRs256("kid-1", Map.of("sub", "s"), k.getPrivate());
        assertTrue(Jwt.verifyRs256(jwt, rsa().getPublic()).isEmpty());
        String[] p = jwt.split("\\.");
        String forged = p[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"admin\"}".getBytes())
                + "." + p[2];
        assertTrue(Jwt.verifyRs256(forged, k.getPublic()).isEmpty());
        assertTrue(Jwt.verifyRs256("not.a.jwt", k.getPublic()).isEmpty());
        assertTrue(Jwt.verifyRs256(null, k.getPublic()).isEmpty());
    }

    @Test
    void anUnsignedAlgIsRefused() throws Exception {
        KeyPair k = rsa();
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes()) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"s\"}".getBytes()) + ".";
        assertTrue(Jwt.verifyRs256(none, k.getPublic()).isEmpty());
    }

    @Test
    void aPemKeyRoundTripsAndTheJwkCarriesTheModulus() throws Exception {
        KeyPair k = rsa();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String jwt = Jwt.signRs256("k", Map.of("x", 1), Jwt.rsaPrivateKeyFromPem(pem));
        assertTrue(Jwt.verifyRs256(jwt, k.getPublic()).isPresent());
        Map<String, Object> jwk = Jwt.jwk((RSAPublicKey) k.getPublic(), "k");
        assertEquals("RSA", jwk.get("kty"));
        assertEquals("k", jwk.get("kid"));
        assertTrue(jwk.containsKey("n") && jwk.containsKey("e"));
    }
}
