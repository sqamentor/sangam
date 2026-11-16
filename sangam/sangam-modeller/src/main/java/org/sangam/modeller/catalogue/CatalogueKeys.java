// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.catalogue;

import org.sangam.integration.connector.SecretResolver;
import org.sangam.modeller.catalogue.ExtensionCatalogue.CatalogueException;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Catalogue keys by secret reference (owner decision 380 P3, decision 37: one key per purpose and environment): a key
 * is never configuration. Each reference must be a {@code secret://} reference that the resolver answers; a literal,
 * an unresolved reference or a value that is not an Ed25519 key is refused with a named code.
 */
public final class CatalogueKeys {

    private CatalogueKeys() {
    }

    /** The trusted public keys (base64 X.509 Ed25519 behind each reference); at least one is required. */
    public static List<PublicKey> trusted(List<String> references, SecretResolver secrets) {
        List<PublicKey> keys = new ArrayList<>();
        for (String ref : references) {
            if (ref != null && !ref.isBlank()) {
                keys.add(publicKey(resolve(ref.strip(), secrets)));
            }
        }
        if (keys.isEmpty()) {
            throw new CatalogueException("CATALOGUE_KEYS_MISSING", "no trusted catalogue public key reference is configured");
        }
        return List.copyOf(keys);
    }

    /** The value behind a {@code secret://} reference; a literal or an unresolved reference is refused. */
    public static String resolve(String reference, SecretResolver secrets) {
        if (reference == null || !reference.startsWith("secret://")) {
            throw new CatalogueException("CATALOGUE_KEY_NOT_A_REFERENCE", "catalogue keys are given as secret:// references, never as values");
        }
        return secrets.resolve(reference).filter(v -> !v.isBlank())
                .orElseThrow(() -> new CatalogueException("CATALOGUE_KEY_NOT_FOUND", "the secret provider has no value for " + reference));
    }

    static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CatalogueException("CATALOGUE_KEY_INVALID", "a trusted catalogue key is not a base64 X.509 Ed25519 public key");
        }
    }
}
