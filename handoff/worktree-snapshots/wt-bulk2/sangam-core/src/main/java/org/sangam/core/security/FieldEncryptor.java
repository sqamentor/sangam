// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.security;

import java.util.Map;

/**
 * Encryption of sensitive values at rest with a key per tenant (port; implemented by sangam-security's
 * {@code AesGcmFieldEncryptor}). Used for form drafts, sensitive JSON columns and other personal data held outside the
 * audit chain (DPDP, X.2.3.8).
 *
 * <p>The ciphertext is a self-describing text envelope {@code sgmenc:v1:<keyId>:<base64(nonce || ciphertext || tag)>}.
 * The key id is versioned ({@code <tenant>/k<n>}), so values written under an old key stay readable after a rotation and
 * can be re-encrypted lazily with {@link #reencrypt}. The {@code context} is authenticated additional data: it binds a
 * value to its tenant and purpose (e.g. {@code "form-draft:" + draftId}), so a ciphertext copied to another row,
 * purpose or tenant fails to decrypt.
 */
public interface FieldEncryptor {

    String PREFIX = "sgmenc:v1:";

    /** Encrypts {@code plaintext} under the tenant's active key. */
    String encrypt(String tenantId, String context, String plaintext);

    /** Decrypts an envelope written by {@link #encrypt}; fails if tenant, context or data do not match. */
    String decrypt(String tenantId, String context, String envelope);

    /** Encrypts a JSON object (canonical form). */
    String encryptJson(String tenantId, String context, Map<String, ?> json);

    Map<String, Object> decryptJson(String tenantId, String context, String envelope);

    /** Re-encrypts under the active key when the envelope uses an older key; otherwise returns it unchanged. */
    String reencrypt(String tenantId, String context, String envelope);

    /** The key id an envelope was written with. */
    static String keyIdOf(String envelope) {
        if (!isEncrypted(envelope)) {
            throw new IllegalArgumentException("not a Sangam encryption envelope");
        }
        String rest = envelope.substring(PREFIX.length());
        return rest.substring(0, rest.lastIndexOf(':'));
    }

    static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX) && value.lastIndexOf(':') > PREFIX.length();
    }
}
