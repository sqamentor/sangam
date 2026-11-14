// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.fidelius;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X962Parameters;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.BigIntegers;

/**
 * ABDM Fidelius encryption on Bouncy Castle (decision 243; decision 173 scope). Bouncy Castle
 * ({@code bcprov-jdk18on}, the same jar as eSign) is an optional dependency of this module only, never on the default
 * server; the lightweight API is used, so no JCA provider is installed.
 *
 * <p>Protocol, as fidelius-cli (Bouncy Castle) and the abdm_fidelius MIT test vectors use it:
 * <ol>
 *   <li>ECDH on Bouncy Castle's {@code curve25519} in short Weierstrass form: the shared secret is the 32-byte
 *       big-endian x-coordinate of {@code d * Q};</li>
 *   <li>{@code xor = senderNonce XOR receiverNonce} (32 bytes each); HKDF salt = {@code xor[0..20)}, AES-GCM IV =
 *       {@code xor[20..32)};</li>
 *   <li>HKDF-SHA256 (RFC 5869) over the shared secret with that salt, empty info, 32 bytes of output;</li>
 *   <li>AES-256-GCM with a 128-bit tag appended to the ciphertext, no additional authenticated data.</li>
 * </ol>
 *
 * <p>Keys: a public key is the 65-byte uncompressed point {@code 0x04 || X || Y} (or its X.509 form, see
 * {@link #publicKeyFromX509}); a private key is the 32-byte big-endian scalar {@code d}, {@code 1 <= d < n}, used as
 * is (Bouncy Castle scalars are not clamped). A malformed or off-curve key is refused ({@link IllegalArgumentException});
 * decryption failures (wrong key, nonce or altered data) raise {@link FideliusException}. No ABDM conformance or
 * government approval is claimed.
 */
public final class Fidelius {

    public static final int NONCE_LENGTH = 32;
    public static final int KEY_LENGTH = 32;
    public static final int PUBLIC_KEY_LENGTH = 65;
    static final int SALT_LENGTH = 20;
    static final int TAG_BITS = 128;

    static final X9ECParameters CURVE = CustomNamedCurves.getByName("curve25519");
    static final ECDomainParameters DOMAIN = new ECDomainParameters(CURVE);

    private Fidelius() {
    }

    /** A Fidelius key pair: 32-byte big-endian scalar and 65-byte uncompressed public point. */
    public record KeyPair(byte[] privateKey, byte[] publicKey) {
        public KeyPair {
            privateKey = privateKey.clone();
            publicKey = publicKey.clone();
        }

        @Override
        public byte[] privateKey() {
            return privateKey.clone();
        }

        @Override
        public byte[] publicKey() {
            return publicKey.clone();
        }

        @Override
        public String toString() {
            return "Fidelius.KeyPair[publicKey]"; // never the private key
        }
    }

    /** Refused decryption or a failed primitive. */
    public static final class FideliusException extends Exception {
        public FideliusException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A new key pair on curve25519 (Bouncy Castle key generation, as fidelius-cli). */
    public static KeyPair generateKeyPair(SecureRandom random) {
        ECKeyPairGenerator g = new ECKeyPairGenerator();
        g.init(new ECKeyGenerationParameters(DOMAIN, random));
        AsymmetricCipherKeyPair kp = g.generateKeyPair();
        byte[] d = BigIntegers.asUnsignedByteArray(KEY_LENGTH, ((ECPrivateKeyParameters) kp.getPrivate()).getD());
        try {
            return new KeyPair(d, ((ECPublicKeyParameters) kp.getPublic()).getQ().getEncoded(false));
        } finally {
            Arrays.fill(d, (byte) 0);
        }
    }

    /** The public key ({@code d * G}, uncompressed) of a 32-byte big-endian private key. */
    public static byte[] publicKey(byte[] privateKey) {
        return DOMAIN.getG().multiply(privateKey(privateKey).getD()).normalize().getEncoded(false);
    }

    /**
     * The Fidelius shared secret: the 32-byte big-endian x-coordinate of {@code privateKey * peerPublicKey}.
     *
     * @throws IllegalArgumentException for a malformed or off-curve public key, a private key outside
     *                                  {@code [1, n)}, or an agreement at infinity
     */
    public static byte[] sharedSecret(byte[] privateKey, byte[] peerPublicKey) {
        ECDHBasicAgreement a = new ECDHBasicAgreement();
        a.init(privateKey(privateKey));
        try {
            return BigIntegers.asUnsignedByteArray(a.getFieldSize(), a.calculateAgreement(point(peerPublicKey)));
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("no Fidelius shared secret for this key pair", e);
        }
    }

    /** Encrypts {@code plaintext} from the sender to the receiver: ciphertext followed by the 16-byte tag. */
    public static byte[] encrypt(byte[] plaintext, byte[] senderPrivateKey, byte[] senderNonce, byte[] receiverPublicKey,
                                 byte[] receiverNonce) {
        try {
            return gcm(true, plaintext, sharedSecret(senderPrivateKey, receiverPublicKey), senderNonce, receiverNonce);
        } catch (FideliusException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /** Decrypts data the sender encrypted for the receiver; a wrong key, nonce or altered data is refused. */
    public static byte[] decrypt(byte[] encrypted, byte[] receiverPrivateKey, byte[] receiverNonce,
                                 byte[] senderPublicKey, byte[] senderNonce) throws FideliusException {
        return gcm(false, encrypted, sharedSecret(receiverPrivateKey, senderPublicKey), senderNonce, receiverNonce);
    }

    /**
     * The 65-byte uncompressed public key inside an X.509 {@code SubjectPublicKeyInfo} (id-ecPublicKey) whose explicit
     * curve parameters are curve25519, the form fidelius-cli exchanges; any other algorithm or curve is refused.
     */
    public static byte[] publicKeyFromX509(byte[] der) {
        SubjectPublicKeyInfo spki;
        try {
            spki = SubjectPublicKeyInfo.getInstance(der);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not an X.509 SubjectPublicKeyInfo", e);
        }
        AlgorithmIdentifier alg = spki.getAlgorithm();
        if (!X9ObjectIdentifiers.id_ecPublicKey.equals(alg.getAlgorithm())) {
            throw new IllegalArgumentException("not an EC public key: " + alg.getAlgorithm());
        }
        X962Parameters p = X962Parameters.getInstance(alg.getParameters());
        if (p.isNamedCurve() || p.isImplicitlyCA()) {
            throw new IllegalArgumentException("the X.509 key must carry explicit curve25519 parameters");
        }
        X9ECParameters x9 = X9ECParameters.getInstance(p.getParameters());
        if (!x9.getCurve().equals(CURVE.getCurve()) || !x9.getG().equals(CURVE.getG()) || !x9.getN().equals(CURVE.getN())
                || (x9.getH() != null && !x9.getH().equals(CURVE.getH()))) {
            throw new IllegalArgumentException("the X.509 key is not on curve25519");
        }
        byte[] raw = spki.getPublicKeyData().getBytes();
        return point(raw).getQ().getEncoded(false);
    }

    /** The X.509 {@code SubjectPublicKeyInfo} (id-ecPublicKey, explicit curve25519 parameters) of a public key. */
    public static byte[] x509PublicKey(byte[] publicKey) {
        byte[] raw = point(publicKey).getQ().getEncoded(false);
        try {
            return new SubjectPublicKeyInfo(new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey,
                    new X962Parameters(CURVE)), raw).getEncoded(ASN1Encoding.DER);
        } catch (IOException e) {
            throw new IllegalStateException("DER encoding failed", e);
        }
    }

    private static ECPrivateKeyParameters privateKey(byte[] privateKey) {
        if (privateKey == null || privateKey.length != KEY_LENGTH) {
            throw new IllegalArgumentException("a Fidelius private key is a 32-byte big-endian scalar");
        }
        BigInteger d = new BigInteger(1, privateKey);
        if (d.signum() == 0 || d.compareTo(DOMAIN.getN()) >= 0) {
            throw new IllegalArgumentException("the private key scalar is not in [1, n)");
        }
        return new ECPrivateKeyParameters(d, DOMAIN);
    }

    private static ECPublicKeyParameters point(byte[] encoded) {
        if (encoded == null || encoded.length != PUBLIC_KEY_LENGTH || encoded[0] != 0x04) {
            throw new IllegalArgumentException("a Fidelius public key is the 65-byte uncompressed point 0x04||X||Y");
        }
        ECPoint q;
        try {
            q = DOMAIN.getCurve().decodePoint(encoded).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the public key is not a point of curve25519", e);
        }
        if (q.isInfinity() || !q.isValid()) {
            throw new IllegalArgumentException("the public key is not a point of curve25519");
        }
        try {
            return new ECPublicKeyParameters(q, DOMAIN);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the public key is not a valid curve25519 point", e);
        }
    }

    private static byte[] gcm(boolean encrypt, byte[] data, byte[] shared, byte[] senderNonce, byte[] receiverNonce)
            throws FideliusException {
        if (senderNonce == null || receiverNonce == null || senderNonce.length != NONCE_LENGTH
                || receiverNonce.length != NONCE_LENGTH) {
            Arrays.fill(shared, (byte) 0);
            throw new IllegalArgumentException("Fidelius nonces are 32 bytes each");
        }
        byte[] xor = new byte[NONCE_LENGTH];
        for (int i = 0; i < NONCE_LENGTH; i++) {
            xor[i] = (byte) (senderNonce[i] ^ receiverNonce[i]);
        }
        byte[] salt = Arrays.copyOfRange(xor, 0, SALT_LENGTH);
        byte[] iv = Arrays.copyOfRange(xor, SALT_LENGTH, NONCE_LENGTH);
        byte[] key = hkdfSha256(shared, salt, new byte[0], KEY_LENGTH);
        Arrays.fill(shared, (byte) 0);
        try {
            GCMModeCipher c = GCMBlockCipher.newInstance(AESEngine.newInstance());
            c.init(encrypt, new AEADParameters(new KeyParameter(key), TAG_BITS, iv));
            byte[] out = new byte[c.getOutputSize(data.length)];
            int n = c.processBytes(data, 0, data.length, out, 0);
            n += c.doFinal(out, n);
            return n == out.length ? out : Arrays.copyOf(out, n);
        } catch (InvalidCipherTextException e) {
            throw new FideliusException("Fidelius data could not be authenticated (wrong key, nonce or altered data)",
                    e);
        } catch (RuntimeException e) {
            throw new FideliusException("AES-256-GCM failed", e);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(xor, (byte) 0);
        }
    }

    /** HKDF-SHA256 (RFC 5869) extract and expand, Bouncy Castle. */
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator g = new HKDFBytesGenerator(new SHA256Digest());
        g.init(new HKDFParameters(ikm, salt, info));
        byte[] out = new byte[length];
        g.generateBytes(out, 0, length);
        return out;
    }
}
