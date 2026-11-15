// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.sangam.compliance.ComplianceException.Code;

/**
 * Owner decision 369 (e): the key of the data-principal references that compliance keeps after erasure. The principal
 * token in audit payloads ({@link ComplianceAudit#principalToken}) and the identifier hash of a one-time-code challenge
 * are HMAC-SHA256 under this key, never a plain SHA-256: a plain hash of a mobile number or an e-mail address can be
 * reversed by trying every candidate, a keyed one cannot without the key.
 *
 * <p>The key comes from the secrets store (sangam-server: {@code sangam.compliance.principal-key-ref}, a
 * {@code secret://} reference; base64, at least {@value #MIN_BYTES} bytes). Without it the features that need it fail
 * closed: every call refuses with {@code PRINCIPAL_KEY_NOT_CONFIGURED} ({@link #unconfigured()}), so no unkeyed
 * reference is ever written. The two uses are separated by a domain label, so a token never equals an identifier hash.
 */
public final class PrincipalKey {

    /** The shortest key accepted (HMAC-SHA256: a key of the hash's own length). */
    public static final int MIN_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    private PrincipalKey(byte[] key) {
        this.key = key;
    }

    /** A key of at least {@value #MIN_BYTES} bytes; a shorter one is a configuration error, refused here. */
    public static PrincipalKey of(byte[] key) {
        if (key == null || key.length < MIN_BYTES) {
            throw new IllegalArgumentException("the compliance principal key must be at least " + MIN_BYTES + " bytes");
        }
        return new PrincipalKey(Arrays.copyOf(key, key.length));
    }

    /** The key in base64 (the secret's value); text that is not base64 is refused without echoing it. */
    public static PrincipalKey fromBase64(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("the compliance principal key is empty");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the compliance principal key is not base64");
        }
        return of(decoded);
    }

    /** No key: every use refuses with {@code PRINCIPAL_KEY_NOT_CONFIGURED} (fail closed). */
    public static PrincipalKey unconfigured() {
        return new PrincipalKey(null);
    }

    public boolean configured() {
        return key != null;
    }

    /** The reference to a principal in audit payloads: 32 hex of HMAC("principal:" tenant NUL reference). */
    public String token(String tenantId, String principalRef) {
        return hex(mac("principal:" + tenantId + "\u0000" + principalRef)).substring(0, 32);
    }

    /** The stored hash of a challenge identifier: hex of HMAC("otp-identifier:" tenant NUL type NUL value). */
    public String identifierHash(String tenantId, String identifierType, String identifierValue) {
        return hex(mac("otp-identifier:" + tenantId + "\u0000" + identifierType + "\u0000" + identifierValue));
    }

    /** The stored hash of a client address (owner decision 369 (d)): hex of HMAC("client-address:" tenant NUL address). */
    public String clientAddressHash(String tenantId, String clientAddress) {
        return hex(mac("client-address:" + tenantId + "\u0000" + clientAddress));
    }

    private byte[] mac(String text) {
        if (key == null) {
            throw new ComplianceException(Code.PRINCIPAL_KEY_NOT_CONFIGURED, "no compliance principal key is configured"
                    + " (sangam.compliance.principal-key-ref, a secret reference); data-principal references are never"
                    + " written unkeyed");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    @Override
    public String toString() {
        return key == null ? "PrincipalKey[not configured]" : "PrincipalKey[configured]";
    }
}
