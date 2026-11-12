// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.integration.connector.Json;

/**
 * Compact JWS with RS256 (RFC 7515/7518) for the connectors that sign or read tokens over plain HTTP (M-067 GCP ID
 * tokens from a service-account key, M-072 and the simulators that issue tokens). JDK crypto only; no library.
 * {@link #verifyRs256} checks the algorithm and the signature only: issuer, audience and expiry are the caller's checks.
 */
public final class Jwt {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private Jwt() {
    }

    /** Signs header and claims with RS256; {@code alg} and {@code typ} are set here, {@code kid} when given. */
    public static String signRs256(String kid, Map<String, Object> claims, PrivateKey key) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        if (kid != null && !kid.isBlank()) {
            header.put("kid", kid);
        }
        String input = B64URL.encodeToString(Json.bytes(header)) + "." + B64URL.encodeToString(Json.bytes(claims));
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + B64URL.encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RS256 signing failed: " + e.getMessage(), e);
        }
    }

    /** The decoded header of a compact JWS, or empty when it is not one. */
    public static Optional<JsonNode> header(String jwt) {
        return part(jwt, 0);
    }

    /** The decoded claims of a compact JWS without any check, or empty when it is not one. */
    public static Optional<JsonNode> claims(String jwt) {
        return part(jwt, 1);
    }

    /** The claims when the header says RS256 and the signature verifies with {@code key}; else empty. */
    public static Optional<JsonNode> verifyRs256(String jwt, PublicKey key) {
        if (jwt == null || key == null) {
            return Optional.empty();
        }
        String[] p = jwt.split("\\.", -1);
        if (p.length != 3) {
            return Optional.empty();
        }
        Optional<JsonNode> h = part(jwt, 0);
        if (h.isEmpty() || !"RS256".equals(h.get().path("alg").asText())) {
            return Optional.empty();
        }
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key);
            s.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
            return s.verify(B64URL_DEC.decode(p[2])) ? part(jwt, 1) : Optional.empty();
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** A PKCS#8 RSA private key from PEM ({@code -----BEGIN PRIVATE KEY-----}), as in a service-account key file. */
    public static PrivateKey rsaPrivateKeyFromPem(String pem) {
        if (pem == null || !pem.contains("BEGIN PRIVATE KEY")) {
            throw new IllegalArgumentException("expected a PKCS#8 PEM private key (BEGIN PRIVATE KEY)");
        }
        String b64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("the PEM private key cannot be read (the key is not shown)");
        }
    }

    /** The public JWK of an RSA key ({@code kty, kid, use, alg, n, e}), for a JWKS document. */
    public static Map<String, Object> jwk(RSAPublicKey key, String kid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kty", "RSA");
        m.put("kid", kid);
        m.put("use", "sig");
        m.put("alg", "RS256");
        m.put("n", B64URL.encodeToString(unsigned(key.getModulus())));
        m.put("e", B64URL.encodeToString(unsigned(key.getPublicExponent())));
        return m;
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    private static Optional<JsonNode> part(String jwt, int i) {
        if (jwt == null) {
            return Optional.empty();
        }
        String[] p = jwt.split("\\.", -1);
        if (p.length != 3) {
            return Optional.empty();
        }
        try {
            JsonNode n = Json.tree(B64URL_DEC.decode(p[i]));
            return n == null || !n.isObject() ? Optional.empty() : Optional.of(n);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
