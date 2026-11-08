// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.crypto;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Key-encryption keys (KEK) that wrap the per-tenant data keys. Several KEKs can be known at once (by id) so the KEK
 * itself can be rotated: new data keys are wrapped with {@link #currentId()}, old ones still unwrap with their KEK.
 * Implementations: a PKCS#12 key store, base64 keys from the server's secret store, or (development only) an ephemeral
 * random key. An HSM/PKCS#11 provider can implement the same interface.
 */
public interface MasterKeyProvider {

    String currentId();

    SecretKey current();

    Optional<SecretKey> byId(String kekId);

    /** True for a key that does not survive a restart (development only; refused on production profiles). */
    default boolean ephemeral() {
        return false;
    }

    /** KEKs given as base64 AES-256 keys, e.g. from environment variables or a vault ({@code id → base64}). */
    static MasterKeyProvider ofBase64(String currentId, Map<String, String> keys) {
        Map<String, SecretKey> m = new java.util.LinkedHashMap<>();
        keys.forEach((id, b64) -> m.put(id, aes(Base64.getDecoder().decode(b64))));
        if (!m.containsKey(currentId)) {
            throw new IllegalArgumentException("current KEK id " + currentId + " is not among the keys");
        }
        return new MasterKeyProvider() {
            public String currentId() { return currentId; }
            public SecretKey current() { return m.get(currentId); }
            public Optional<SecretKey> byId(String id) { return Optional.ofNullable(m.get(id)); }
        };
    }

    /** KEKs held in a PKCS#12 key store as secret-key entries; the alias is the KEK id. */
    static MasterKeyProvider ofKeyStore(Path pkcs12, char[] storePassword, String currentAlias, char[] keyPassword) {
        try (InputStream in = Files.newInputStream(pkcs12)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, storePassword);
            Map<String, SecretKey> m = new java.util.LinkedHashMap<>();
            for (String alias : java.util.Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(alias) && ks.getKey(alias, keyPassword) instanceof SecretKey sk) {
                    m.put(alias, aes(sk.getEncoded()));
                }
            }
            if (!m.containsKey(currentAlias)) {
                throw new IllegalArgumentException("key store has no secret key '" + currentAlias + "'");
            }
            return new MasterKeyProvider() {
                public String currentId() { return currentAlias; }
                public SecretKey current() { return m.get(currentAlias); }
                public Optional<SecretKey> byId(String id) { return Optional.ofNullable(m.get(id)); }
            };
        } catch (java.io.IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("cannot load the master key store: " + e.getMessage(), e);
        }
    }

    /** Development only: a random KEK per JVM; data written with it is unreadable after a restart. */
    static MasterKeyProvider ephemeralForDevelopment() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        SecretKey key = aes(k);
        return new MasterKeyProvider() {
            public String currentId() { return "dev-ephemeral"; }
            public SecretKey current() { return key; }
            public Optional<SecretKey> byId(String id) { return "dev-ephemeral".equals(id) ? Optional.of(key) : Optional.empty(); }
            public boolean ephemeral() { return true; }
        };
    }

    private static SecretKey aes(byte[] raw) {
        if (raw.length != 32) {
            throw new IllegalArgumentException("a KEK must be 256 bits");
        }
        return new SecretKeySpec(raw, "AES");
    }
}
