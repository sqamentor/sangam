// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.support;

import org.sangam.core.layer.Hashes;
import org.sangam.registry.RegistryPorts;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/**
 * Default {@link RegistryPorts.SignerPort}: Ed25519 from the JDK (no third-party dependency). The key id is the first
 * 16 hex characters of the SHA-256 of the public key. Production binds the port to the tenant key store, an HSM or
 * the eSign connector; this signer is for a standalone office or development.
 */
public final class Ed25519Signer implements RegistryPorts.SignerPort, RegistryPorts.DevelopmentOnly {

    public static final String ALGORITHM = "Ed25519";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;
    private final boolean ephemeral;

    /** A signer over configured key material (production use). */
    public Ed25519Signer(PrivateKey privateKey, PublicKey publicKey) {
        this(privateKey, publicKey, false);
    }

    private Ed25519Signer(PrivateKey privateKey, PublicKey publicKey, boolean ephemeral) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.keyId = Hashes.hex(Hashes.sha256(publicKey.getEncoded())).substring(0, 16);
        this.ephemeral = ephemeral;
    }

    /**
     * A signer with a fresh key pair. Signatures do not survive a restart, so this is development only:
     * {@link org.sangam.registry.RegistryModule} refuses to start with it on a non-development profile.
     */
    public static Ed25519Signer ephemeral() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
            return new Ed25519Signer(kp.getPrivate(), kp.getPublic(), true);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 not available", e);
        }
    }

    /** A signer from base64 PKCS#8 private and X.509 public key encodings (from configuration or a key file). */
    public static Ed25519Signer fromBase64(String pkcs8, String x509) {
        try {
            KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
            return new Ed25519Signer(kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8))),
                    kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(x509))));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("invalid Ed25519 key material", e);
        }
    }

    @Override
    public RegistryPorts.Signature sign(String tenant, String purpose, byte[] data) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(privateKey);
            s.update(data);
            return new RegistryPorts.Signature(ALGORITHM, keyId, s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    @Override
    public boolean verify(String tenant, String keyId, String algorithm, byte[] data, byte[] signature) {
        if (!this.keyId.equals(keyId) || !ALGORITHM.equals(algorithm)) {
            return false;
        }
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(publicKey);
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    @Override
    public Optional<String> publicKey(String tenant, String keyId) {
        return this.keyId.equals(keyId) ? Optional.of(Base64.getEncoder().encodeToString(publicKey.getEncoded()))
                : Optional.empty();
    }

    public String keyId() {
        return keyId;
    }

    @Override
    public String developmentOnlyReason() {
        return ephemeral ? "signer: ephemeral Ed25519 key (signatures stop verifying after a restart); configure"
                + " Ed25519Signer.fromBase64(pkcs8, x509) or bind the SignerPort to the key store / HSM / eSign service" : null;
    }
}
