// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.evidence;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical packet hashing and device-signature verification (tamper evidence, N05).
 *
 * <p>Canonical form (clients in any language must produce the same bytes): the packet is converted to plain JSON
 * values, then every number becomes a decimal string ({@code BigDecimal.stripTrailingZeros().toPlainString()}, so
 * {@code 2}, {@code 2.0} and {@code 2.00} all hash as {@code "2"}), instants are ISO-8601 UTC strings, nulls are
 * dropped from objects, and the result is written with the core RFC 8785-style {@link CanonicalJson} (sorted keys,
 * no whitespace). The hash is lowercase hex SHA-256 of the UTF-8 bytes.
 */
public final class PacketHasher {

    private PacketHasher() {
    }

    public static String canonical(CapturePacket p) {
        return CanonicalJson.write(normalise(FieldJson.MAPPER.convertValue(p.hashedFields(), Object.class)));
    }

    public static String hash(CapturePacket p) {
        return Hashes.hex(Hashes.sha256(canonical(p)));
    }

    static Object normalise(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> out = new TreeMap<>();
            m.forEach((k, x) -> {
                Object n = normalise(x);
                if (n != null) {
                    out.put(String.valueOf(k), n);
                }
            });
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            c.forEach(x -> out.add(normalise(x)));
            return out;
        }
        if (v instanceof Number n) {
            BigDecimal d = n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
            return d.compareTo(BigDecimal.ZERO) == 0 ? "0" : d.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Instant t) {
            return t.toString();
        }
        return v;
    }

    /**
     * Verifies a base64 DER signature (SHA256withECDSA) over the UTF-8 bytes of the packet hash hex, with a base64
     * X.509 SubjectPublicKeyInfo key (as registered for the device).
     */
    public static boolean verifySignature(String publicKeyB64, String keyAlg, String hashHex, String signatureB64) {
        if (publicKeyB64 == null || signatureB64 == null) {
            return false;
        }
        try {
            String alg = keyAlg == null || keyAlg.isBlank() ? "EC" : keyAlg;
            PublicKey key = KeyFactory.getInstance(alg).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)));
            Signature s = Signature.getInstance("EC".equals(alg) ? "SHA256withECDSA" : "Ed25519".equals(alg) ? "Ed25519" : "SHA256with" + alg);
            s.initVerify(key);
            s.update(hashHex.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(signatureB64));
        } catch (Exception e) {
            return false;
        }
    }

    /** Validates that a registered key parses (enrolment-time check). */
    public static void requireValidKey(String publicKeyB64, String keyAlg) {
        try {
            String alg = keyAlg == null || keyAlg.isBlank() ? "EC" : keyAlg;
            KeyFactory.getInstance(alg).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)));
        } catch (Exception e) {
            throw new FieldException("INVALID_DEVICE_KEY", "device public key is not a valid X.509 " + keyAlg + " key");
        }
    }
}
