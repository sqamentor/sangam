// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackagePort.PackageSignature;
import org.sangam.rest.port.PackagePort.SignatureCheck;

/**
 * M-074 (R43, SANGAM.md X.2.4.5): the executable package manifest, its hash and the checks a target environment runs
 * on a bundle. Pure functions; no database.
 *
 * <p><b>Manifest.</b> The server builds it from the package's resources: {@code package} = {@code {key, name}},
 * {@code artifacts} = one {@code {path, sha256}} per resource, sorted by path. What the author declares (the X.2.4.5
 * {@code profile}, {@code bindings}, {@code rulesets}, {@code contracts}, {@code policies} and {@code envBindings}, the
 * names of the ENV_BINDINGs the package needs) comes from the optional resource {@value #MANIFEST_RESOURCE}, a JSON
 * object whose members are copied into the manifest; {@code package} and {@code artifacts} are reserved there. The
 * environment and the tenant are deliberately not in the manifest: promotion moves the same bytes, so the hash must
 * not change between environments or tenants.
 *
 * <p><b>Hash.</b> {@code package_hash = SHA-256(JCS(manifest))} ({@link CanonicalJson}); the artifact hashes are inside
 * the manifest, so the package hash commits to every byte. Numbers in the authored part must be integral (decimals as
 * strings, R33), as JCS number formatting is not used.
 *
 * <p><b>Signatures.</b> Ed25519 over {@code JCS({packageHash, role, principalId, seatId, signedAt})} (decision 84,
 * {@link #signedBytes}), checked against the environment's trusted keys by key id.
 */
final class PackageManifests {

    /** The author's part of the manifest, inside the package. */
    static final String MANIFEST_RESOURCE = "sangam-package.json";
    static final int BUNDLE_FORMAT = 1;
    static final String ALG = AuditCheckpoints.ALGORITHM;
    static final String ROLE_AUTHOR = "author";
    static final String ROLE_APPROVER = "approver";

    private static final Set<String> RESERVED = Set.of("package", "artifacts");
    private static final Pattern BINDING_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".bpmn", ".dmn", ".cmmn", ".form", ".json", ".xml",
            ".yaml", ".yml", ".txt");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private PackageManifests() {
    }

    // ------------------------------------------------------------------ manifest and hash

    /** The manifest of a package with these resources (see the class comment). */
    static Map<String, Object> manifest(String key, String name, Map<String, byte[]> resources) {
        Map<String, Object> m = new LinkedHashMap<>(authored(resources));
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("key", key);
        pkg.put("name", name == null ? key : name);
        m.put("package", pkg);
        List<Map<String, Object>> artifacts = new ArrayList<>();
        new TreeMap<>(resources).forEach((path, bytes) -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("path", path);
            a.put("sha256", Hashes.hex(Hashes.sha256(bytes)));
            artifacts.add(a);
        });
        m.put("artifacts", artifacts);
        return m;
    }

    /** {@code SHA-256(JCS(manifest))} as lower-case hex; 422 PACKAGE_MANIFEST_INVALID for a non-integral number. */
    static String hash(Map<String, Object> manifest) {
        String canonical;
        try {
            canonical = CanonicalJson.write(manifest);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.unprocessable("PACKAGE_MANIFEST_INVALID", "the package manifest cannot be hashed: "
                    + e.getMessage());
        }
        return Hashes.hex(Hashes.sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /** The members the author declared in {@value #MANIFEST_RESOURCE}; empty when the package has none. */
    static Map<String, Object> authored(Map<String, byte[]> resources) {
        byte[] raw = resources.get(MANIFEST_RESOURCE);
        if (raw == null) {
            return Map.of();
        }
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (java.io.IOException e) {
            throw invalidManifest(MANIFEST_RESOURCE + " is not a JSON object");
        }
        if (parsed == null) {
            throw invalidManifest(MANIFEST_RESOURCE + " is not a JSON object");
        }
        for (String reserved : RESERVED) {
            if (parsed.containsKey(reserved)) {
                throw invalidManifest("'" + reserved + "' is computed by the server and cannot be declared in "
                        + MANIFEST_RESOURCE);
            }
        }
        envBindings(parsed);   // validates the names
        return parsed;
    }

    /** The ENV_BINDING names the manifest declares, in order, validated (names only, X.2.4.5). */
    static List<String> envBindings(Map<String, Object> manifest) {
        Object v = manifest.get("envBindings");
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw invalidManifest("envBindings must be an array of binding names");
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof String s) || !BINDING_NAME.matcher(s).matches()) {
                throw invalidManifest("envBindings holds an invalid binding name: " + o + " (1..128 of [A-Za-z0-9._-])");
            }
            if (!names.add(s)) {
                throw invalidManifest("envBindings names " + s + " twice");
            }
        }
        return List.copyOf(names);
    }

    static boolean validBindingName(String name) {
        return name != null && BINDING_NAME.matcher(name).matches();
    }

    private static ApiProblem invalidManifest(String detail) {
        return ApiProblem.unprocessable("PACKAGE_MANIFEST_INVALID", detail);
    }

    // ------------------------------------------------------------------ artifacts (text or base64, as Package.resources)

    /** Text resources (by extension) travel as UTF-8 text; everything else as base64 (PackagePort contract). */
    static boolean isText(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.stream().anyMatch(n::endsWith);
    }

    static String encode(String path, byte[] bytes) {
        return isText(path) ? new String(bytes, StandardCharsets.UTF_8) : Base64.getEncoder().encodeToString(bytes);
    }

    /** The bytes of one artifact; empty when a binary artifact is not valid base64. */
    static Optional<byte[]> decode(String path, String content) {
        String v = content == null ? "" : content;
        if (isText(path)) {
            return Optional.of(v.getBytes(StandardCharsets.UTF_8));
        }
        try {
            return Optional.of(Base64.getDecoder().decode(v));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The decoded artifacts and the paths that do not match the manifest (missing, extra, undecodable or changed). */
    record ArtifactCheck(Map<String, byte[]> bytes, List<String> mismatches) {
    }

    @SuppressWarnings("unchecked")
    static ArtifactCheck artifacts(Map<String, Object> manifest, Map<String, String> artifacts) {
        Map<String, String> declared = new TreeMap<>();
        if (manifest.get("artifacts") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> a && a.get("path") instanceof String p && a.get("sha256") instanceof String h) {
                    declared.put(p, h.toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<String, String> given = artifacts == null ? Map.of() : artifacts;
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        TreeSet<String> mismatches = new TreeSet<>();
        TreeSet<String> paths = new TreeSet<>(declared.keySet());
        paths.addAll(given.keySet());
        for (String path : paths) {
            if (path == null || path.isBlank() || path.contains("..")) {
                mismatches.add(String.valueOf(path));
                continue;
            }
            String expected = declared.get(path);
            String content = given.get(path);
            if (expected == null || content == null) {
                mismatches.add(path);
                continue;
            }
            Optional<byte[]> b = decode(path, content);
            if (b.isEmpty()) {
                mismatches.add(path);
                continue;
            }
            bytes.put(path, b.get());
            if (!expected.equals(Hashes.hex(Hashes.sha256(b.get())))) {
                mismatches.add(path);
            }
        }
        return new ArtifactCheck(bytes, List.copyOf(mismatches));
    }

    /** {@code manifest.package.key} (or .name); null when absent. */
    static String packageField(Map<String, Object> manifest, String field) {
        return manifest != null && manifest.get("package") instanceof Map<?, ?> p && p.get(field) instanceof String s
                ? s : null;
    }

    // ------------------------------------------------------------------ signatures

    /** The 32 bytes of a hex package hash; null unless exactly 64 hex characters. */
    static byte[] hashBytes(String hex) {
        if (hex == null || hex.length() != 64) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The bytes a package signature covers (decision 84): UTF-8 of
     * {@code JCS({packageHash, role, principalId, seatId, signedAt})}, signedAt as ISO-8601 UTC. The role, the signer
     * and the time are bound, so a signature relabelled to another role (or person, seat or time) no longer verifies,
     * and the author's and approver's signatures by one key differ. Null without a valid 64-hex hash.
     */
    static byte[] signedBytes(String packageHash, String role, String principalId, String seatId,
                              java.time.Instant signedAt) {
        if (hashBytes(packageHash) == null) {
            return null;
        }
        Map<String, Object> m = new TreeMap<>();
        m.put("packageHash", packageHash.toLowerCase(Locale.ROOT));
        m.put("role", role);
        m.put("principalId", principalId);
        m.put("seatId", seatId);
        m.put("signedAt", signedAt == null ? null : signedAt.toString());
        return CanonicalJson.write(m).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Signs {@link #signedBytes}. The time is cut to milliseconds, so it survives every dialect's timestamp column
     * (PACKAGE_SIGNATURE.signed_at) and the bundle JSON unchanged.
     */
    static PackageSignature sign(AuditCheckpoints.Signer signer, String packageHash, String role, String principalId,
                                 String seatId, java.time.Instant at) {
        java.time.Instant signedAt = at.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        byte[] sig = signer.sign(signedBytes(packageHash, role, principalId, seatId, signedAt));
        return new PackageSignature(packageHash, role, principalId, seatId, ALG, signer.keyId(),
                Base64.getEncoder().encodeToString(sig), signedAt);
    }

    /**
     * One check per signature over {@code packageHash}, then a {@code ROLE_MISSING} entry for each of
     * {@code requiredRoles} without a valid signature. KEY_REVOKED is not produced: trust is the key list itself (a
     * revoked key is removed from it and reports UNKNOWN_KEY).
     */
    static List<SignatureCheck> check(String packageHash, List<PackageSignature> signatures,
                                      AuditCheckpoints.KeyLookup keys, List<String> requiredRoles) {
        boolean validHash = hashBytes(packageHash) != null;
        List<SignatureCheck> out = new ArrayList<>();
        Set<String> validRoles = new LinkedHashSet<>();
        for (PackageSignature s : signatures == null ? List.<PackageSignature>of() : signatures) {
            if (s == null) {
                continue;
            }
            String reason;
            Optional<PublicKey> key = s.keyId() == null ? Optional.empty() : keys.publicKey(s.keyId());
            if (!validHash || (s.packageHash() != null && !s.packageHash().equalsIgnoreCase(packageHash))
                    || !ALG.equals(s.alg()) || s.role() == null || s.signedAt() == null) {
                reason = "BAD_SIGNATURE";
            } else if (key.isEmpty()) {
                reason = "UNKNOWN_KEY";
            } else {
                // the verifier rebuilds the signed bytes from the hash under check and the signature's own labels
                byte[] data = signedBytes(packageHash, s.role(), s.principalId(), s.seatId(), s.signedAt());
                reason = verify(key.get(), data, s.signature()) ? "OK" : "BAD_SIGNATURE";
            }
            if ("OK".equals(reason)) {
                validRoles.add(s.role());
            }
            out.add(new SignatureCheck(s.role(), s.keyId(), "OK".equals(reason), reason));
        }
        for (String role : requiredRoles) {
            if (!validRoles.contains(role)) {
                out.add(new SignatureCheck(role, null, false, "ROLE_MISSING"));
            }
        }
        return List.copyOf(out);
    }

    private static boolean verify(PublicKey key, byte[] data, String signatureBase64) {
        if (signatureBase64 == null) {
            return false;
        }
        try {
            Signature s = Signature.getInstance(ALG);
            s.initVerify(key);
            s.update(data);
            return s.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
