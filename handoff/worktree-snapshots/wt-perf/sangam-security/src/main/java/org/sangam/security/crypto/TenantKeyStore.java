// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.crypto;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Optional;

/**
 * Per-tenant data keys (DEK), versioned for rotation. Key ids are {@code <tenant>/k<version>}. Exactly one key per
 * tenant is {@code ACTIVE} (used to encrypt); older keys are {@code DECRYPT_ONLY}; a {@code DESTROYED} key no longer
 * exists (crypto-shredding: every value under it becomes unreadable, e.g. for tenant off-boarding).
 */
public interface TenantKeyStore {

    record KeyInfo(String keyId, String tenantId, int version, String status, String kekId) {
    }

    /** The active key, created on first use. */
    TenantKey active(String tenantId);

    Optional<TenantKey> byId(String tenantId, String keyId);

    /** Creates a new active version; the previous active key becomes decrypt-only. Returns the new key id. */
    String rotate(String tenantId);

    /** Destroys a non-active key (crypto-shredding). */
    void destroy(String tenantId, String keyId);

    List<KeyInfo> keys(String tenantId);

    /** An unwrapped data key. */
    record TenantKey(String keyId, SecretKey key) {
    }
}
