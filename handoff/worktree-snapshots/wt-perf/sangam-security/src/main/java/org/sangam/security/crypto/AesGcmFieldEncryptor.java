// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.crypto;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.security.FieldEncryptor;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.security.approval.ApprovalBindingService;
import org.sangam.security.support.Sql;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * {@link FieldEncryptor} with AES-256-GCM (96-bit random nonce, 128-bit tag) and one data key per tenant from a
 * {@link TenantKeyStore}. The authenticated data is {@code "sangam-field:v1|" + tenant + "|" + keyId + "|" + context},
 * so a value only decrypts for the same tenant, key and purpose. JSON is written canonically (decimals as strings,
 * the same normalisation as approval hashing) so equal content gives equal plaintext.
 *
 * <p>Errors never include plaintext or key material. A wrong tenant, context or a tampered value fails with
 * {@code 403 DECRYPTION_DENIED}; a destroyed key fails with {@code 410 KEY_DESTROYED} (crypto-shredded data).
 */
public final class AesGcmFieldEncryptor implements FieldEncryptor {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;

    private final TenantKeyStore keys;

    public AesGcmFieldEncryptor(TenantKeyStore keys) {
        this.keys = keys;
    }

    @Override
    public String encrypt(String tenantId, String context, String plaintext) {
        requireTenant(tenantId);
        if (plaintext == null) {
            return null;
        }
        TenantKeyStore.TenantKey k = keys.active(tenantId);
        try {
            byte[] nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, k.key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(tenantId, k.keyId(), context));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String body = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(ByteBuffer.allocate(NONCE + ct.length).put(nonce).put(ct).array());
            return PREFIX + k.keyId() + ":" + body;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    @Override
    public String decrypt(String tenantId, String context, String envelope) {
        requireTenant(tenantId);
        if (envelope == null) {
            return null;
        }
        if (!FieldEncryptor.isEncrypted(envelope)) {
            throw denied("value is not encrypted");
        }
        String keyId = FieldEncryptor.keyIdOf(envelope);
        TenantKeyStore.TenantKey k = keys.byId(tenantId, keyId).orElseThrow(() ->
                keyId.startsWith(tenantId + "/")
                        ? new SangamSecurityException("KEY_DESTROYED", 410, "the key of this value was destroyed")
                        : denied("value belongs to another tenant"));
        try {
            byte[] all = Base64.getUrlDecoder().decode(envelope.substring(envelope.lastIndexOf(':') + 1));
            if (all.length < NONCE + 16) {
                throw denied("value is truncated");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, k.key(), new GCMParameterSpec(TAG_BITS, all, 0, NONCE));
            cipher.updateAAD(aad(tenantId, keyId, context));
            return new String(cipher.doFinal(all, NONCE, all.length - NONCE), StandardCharsets.UTF_8);
        } catch (AEADBadTagException | IllegalArgumentException e) {
            throw denied("value does not authenticate for this tenant and context");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }

    @Override
    public String encryptJson(String tenantId, String context, Map<String, ?> json) {
        return json == null ? null : encrypt(tenantId, context, CanonicalJson.write(ApprovalBindingService.normalise(json)));
    }

    @Override
    public Map<String, Object> decryptJson(String tenantId, String context, String envelope) {
        String text = decrypt(tenantId, context, envelope);
        return text == null ? null : Sql.jsonMap(text);
    }

    @Override
    public String reencrypt(String tenantId, String context, String envelope) {
        if (envelope == null) {
            return null;
        }
        if (FieldEncryptor.keyIdOf(envelope).equals(keys.active(tenantId).keyId())) {
            return envelope;
        }
        return encrypt(tenantId, context, decrypt(tenantId, context, envelope));
    }

    private static byte[] aad(String tenantId, String keyId, String context) {
        return ("sangam-field:v1|" + tenantId + "|" + keyId + "|" + (context == null ? "" : context))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank() || tenantId.contains("/") || tenantId.contains(":")) {
            throw new IllegalArgumentException("a valid tenant id is required");
        }
    }

    private static SangamSecurityException denied(String msg) {
        return new SangamSecurityException("DECRYPTION_DENIED", 403, msg);
    }
}
