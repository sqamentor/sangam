// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.catalogue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.modeller.catalogue.ExtensionCatalogue.CatalogueException;
import org.sangam.modeller.catalogue.ExtensionCatalogue.Entry;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The hosting side of the platform-wide catalogue (owner decision 380): writes the index of the approved entries in
 * the exact form {@link ExtensionCatalogue#read} reads, and signs it with the host's own catalogue key (P3, decision 37:
 * a key of its own, given by secret reference). The bytes are deterministic (entries ordered by id, then version), so
 * the same approved set always gives the same index and, Ed25519 being deterministic, the same signature.
 */
public final class CatalogueIndex {

    /** Index format written by the host. */
    public static final int FORMAT = 1;

    /** Ids and versions are path segments of the hosted artefact URL: lower-case letters, digits, dot, dash, underscore. */
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");

    private static final ObjectMapper JSON = new ObjectMapper();

    private CatalogueIndex() {
    }

    /** The artefact path of an entry, relative to the hosted index ({@code artifacts/<id>/<version>}). */
    public static String artifactPath(String id, String version) {
        return "artifacts/" + token(id, "id") + "/" + token(version, "version");
    }

    /** {@code value} if it is a valid id or version token; else {@code CATALOGUE_ENTRY_ID_INVALID}. */
    public static String token(String value, String what) {
        if (value == null || !TOKEN.matcher(value).matches() || value.contains("..")) {
            throw new CatalogueException("CATALOGUE_ENTRY_ID_INVALID",
                    what + " must be 1 to 100 of a-z 0-9 . _ - (starting with a letter or digit, no '..')");
        }
        return value;
    }

    /** The index bytes of {@code entries}. */
    public static byte[] write(List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Entry::id).thenComparing(Entry::version));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : sorted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("version", e.version());
            m.put("kind", e.kind());
            m.put("publisher", e.publisher());
            m.put("licence", e.licence());
            m.put("sha256", e.sha256());
            m.put("minPlatform", e.minPlatform());
            m.put("artifact", e.artifactPath());
            out.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("entries", out);
        try {
            return JSON.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("catalogue index serialisation failed", e);
        }
    }

    /** The base64 detached Ed25519 signature over the exact index bytes. */
    public static String sign(byte[] index, AuditCheckpoints.Signer signer) {
        return Base64.getEncoder().encodeToString(signer.sign(index));
    }

    /** The host's signer from its two secret references (PKCS#8 private key and X.509 public key, base64). */
    public static AuditCheckpoints.Signer signer(String privateKeyRef, String publicKeyRef,
                                                 org.sangam.integration.connector.SecretResolver secrets) {
        String priv = CatalogueKeys.resolve(privateKeyRef, secrets);
        String pub = CatalogueKeys.resolve(publicKeyRef, secrets);
        AuditCheckpoints.Signer s;
        try {
            s = AuditCheckpoints.ed25519(priv, pub);
        } catch (RuntimeException e) {
            throw new CatalogueException("CATALOGUE_KEY_INVALID", "the catalogue signing key is not a base64 PKCS#8 / X.509 Ed25519 pair");
        }
        // the two references must name one key pair: a probe signed by the private key verifies with the public key
        byte[] probe = "sangam-catalogue-key-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            java.security.Signature v = java.security.Signature.getInstance("Ed25519");
            v.initVerify(CatalogueKeys.publicKey(pub));
            v.update(probe);
            if (!v.verify(s.sign(probe))) {
                throw new CatalogueException("CATALOGUE_KEY_INVALID", "the catalogue signing key and public key are not one pair");
            }
        } catch (java.security.GeneralSecurityException | IllegalStateException e) {
            throw new CatalogueException("CATALOGUE_KEY_INVALID", "the catalogue signing key and public key are not one pair");
        }
        return s;
    }
}
