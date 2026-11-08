// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.layer.Hashes;
import org.sangam.integration.connector.Json;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Signed catalogue index of packs and connectors: manifest, licence, publisher, checksum, compatibility. It is a file an operator mirrors:
 * this class reads bytes and makes no network call, so air-gapped sites work the same way. An entry passes only with a valid Ed25519
 * signature over the index, an allowed licence and a matching artefact checksum. No hosted catalogue service or publisher workflow exists.
 */
public final class ExtensionCatalogue {

    public record Entry(String id, String version, String kind, String publisher, String licence, String sha256, String minPlatform, String artifactPath) {
    }

    public static final class CatalogueException extends RuntimeException {
        public CatalogueException(String m) {
            super(m);
        }
    }

    private ExtensionCatalogue() {
    }

    /** Verifies the detached signature, then parses. {@code signatureBase64} covers the exact index bytes. */
    public static List<Entry> read(byte[] index, String signatureBase64, PublicKey trustedKey) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(trustedKey);
            s.update(index);
            if (!s.verify(Base64.getDecoder().decode(signatureBase64))) {
                throw new CatalogueException("CATALOGUE_SIGNATURE_INVALID");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CatalogueException("CATALOGUE_SIGNATURE_INVALID");
        }
        JsonNode root = Json.tree(index);
        if (root == null || !root.path("entries").isArray()) {
            throw new CatalogueException("CATALOGUE_FORMAT_INVALID");
        }
        List<Entry> out = new ArrayList<>();
        for (JsonNode e : root.path("entries")) {
            out.add(new Entry(e.path("id").asText(), e.path("version").asText(), e.path("kind").asText(), e.path("publisher").asText(), e.path("licence").asText(), e.path("sha256").asText(),
                    e.path("minPlatform").asText("0"), e.path("artifact").asText()));
        }
        return out;
    }

    /** Checks before installing: licence allow-list, platform version, artefact checksum. Returns the reasons to refuse; empty means pass. */
    public static List<String> check(Entry e, byte[] artifact, Set<String> allowedLicences, String platformVersion) {
        List<String> refuse = new ArrayList<>();
        if (!allowedLicences.contains(e.licence())) {
            refuse.add("licence not allowed: " + e.licence());
        }
        if (compare(platformVersion, e.minPlatform()) < 0) {
            refuse.add("needs platform " + e.minPlatform());
        }
        if (!Hashes.hex(Hashes.sha256(artifact)).equalsIgnoreCase(e.sha256())) {
            refuse.add("checksum mismatch");
        }
        return refuse;
    }

    static int compare(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0;
            int q = i < y.length ? num(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
