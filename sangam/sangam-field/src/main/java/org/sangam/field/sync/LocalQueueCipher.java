// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.sync;

import org.sangam.field.FieldException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Reference implementation of {@link LocalQueueContract} for JVM clients (desktop field app, Android via the same
 * algorithm) and for conformance tests of other clients. The key is supplied by the caller (platform keystore). The
 * AAD is {@link LocalQueueContract#aad} ({@code tenantId|deviceId|operationId|userId}, SL-129), the same string the
 * PWA's {@code sangam-ui/src/lib/offline/crypto.ts aad()} builds.
 */
public final class LocalQueueCipher {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final String keyId;

    public LocalQueueCipher(SecretKey key, String keyId) {
        if (key == null || key.getEncoded() != null && key.getEncoded().length * 8 != LocalQueueContract.KEY_BITS) {
            throw new FieldException("INVALID_KEY", "queue key must be AES-256");
        }
        this.key = key;
        this.keyId = keyId;
    }

    public static SecretKey newKey() {
        try {
            KeyGenerator g = KeyGenerator.getInstance("AES");
            g.init(LocalQueueContract.KEY_BITS, RANDOM);
            return g.generateKey();
        } catch (Exception e) {
            throw new FieldException("CRYPTO_UNAVAILABLE", "AES-256 unavailable", e);
        }
    }

    /**
     * Seals one entry for the signed-in {@code userId} (SL-129): the AAD binds tenant, device, operation and user, so
     * only the same user on the same device opens it.
     */
    public LocalQueueContract.Envelope seal(String tenantId, String deviceId, String operationId, String userId, String packetJson) {
        byte[] aad = LocalQueueContract.aad(tenantId, deviceId, operationId, userId).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] iv = new byte[LocalQueueContract.IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance(LocalQueueContract.CIPHER);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(LocalQueueContract.TAG_BITS, iv));
            c.updateAAD(aad);
            byte[] ct = c.doFinal(packetJson.getBytes(StandardCharsets.UTF_8));
            return new LocalQueueContract.Envelope(LocalQueueContract.VERSION, keyId, operationId,
                    Base64.getEncoder().encodeToString(iv), Base64.getEncoder().encodeToString(ct), Instant.now().toString());
        } catch (Exception e) {
            throw new FieldException("CRYPTO_FAILED", "cannot seal queue entry", e);
        }
    }

    /**
     * Opens an entry for {@code userId}; a wrong tenant, device, operation or user (AAD) or any tampering fails the GCM
     * tag ({@code QUEUE_TAMPERED}).
     */
    public String open(String tenantId, String deviceId, String userId, LocalQueueContract.Envelope env) {
        env.validate();
        byte[] aad = LocalQueueContract.aad(tenantId, deviceId, env.op(), userId).getBytes(StandardCharsets.UTF_8);
        try {
            Cipher c = Cipher.getInstance(LocalQueueContract.CIPHER);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(LocalQueueContract.TAG_BITS, Base64.getDecoder().decode(env.iv())));
            c.updateAAD(aad);
            return new String(c.doFinal(Base64.getDecoder().decode(env.ct())), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new FieldException("QUEUE_TAMPERED", "queue entry failed authentication", e);
        }
    }
}
