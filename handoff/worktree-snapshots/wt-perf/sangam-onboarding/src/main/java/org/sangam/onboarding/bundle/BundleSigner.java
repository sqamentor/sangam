// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.bundle;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ed25519 signing of tenant bundles over the canonical JSON of the body. Keys are base64 (PKCS#8 private, X.509 public);
 * a verifier trusts a list of public keys by id.
 */
public final class BundleSigner {

    private final String keyId;
    private final PrivateKey key;
    private final Map<String, PublicKey> trusted = new LinkedHashMap<>();

    public BundleSigner(String keyId, String privateKeyBase64, Map<String, String> trustedPublicKeys) {
        this.keyId = keyId;
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            this.key = privateKeyBase64 == null || privateKeyBase64.isBlank() ? null
                    : kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)));
            for (Map.Entry<String, String> e : trustedPublicKeys.entrySet()) {
                trusted.put(e.getKey(), kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(e.getValue()))));
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid bundle key: " + e.getMessage(), e);
        }
    }

    public boolean canSign() {
        return key != null;
    }

    public String keyId() {
        return keyId;
    }

    public String sign(String canonical) {
        if (key == null) {
            throw new IllegalStateException("no bundle signing key configured");
        }
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True only when {@code keyId} is trusted and the signature verifies. */
    public boolean verify(String signerKeyId, String canonical, String signatureBase64) {
        PublicKey pub = trusted.get(signerKeyId);
        if (pub == null || signatureBase64 == null) {
            return false;
        }
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pub);
            s.update(canonical.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
