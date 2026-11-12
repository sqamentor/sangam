// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackagePort.PackageSignature;
import org.sangam.rest.port.PackagePort.SignatureCheck;

/**
 * M-074 (X.2.4.5, T70): the package manifest and hash are deterministic and commit to every byte; the author's
 * manifest part is validated; a target finds every changed, missing or extra artifact; signatures (over the JCS of hash,
 * role, signer, seat and time, decision 84) verify only with a trusted key and unrelabelled, and a missing approver
 * signature is reported.
 */
class PackageManifestsTest {

    static Map<String, byte[]> resources(String bpmn, String manifest) {
        Map<String, byte[]> r = new LinkedHashMap<>();
        r.put("bill.bpmn", bpmn.getBytes(StandardCharsets.UTF_8));
        r.put("logo.png", new byte[]{1, 2, 3});
        if (manifest != null) {
            r.put(PackageManifests.MANIFEST_RESOURCE, manifest.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    @Test
    void theHashIsSha256OfTheCanonicalManifestAndCommitsToEveryByte() {
        Map<String, byte[]> r = resources("<definitions/>", "{\"envBindings\":[\"treasury-endpoint\"]}");
        Map<String, Object> m = PackageManifests.manifest("p1-bill", "Bill approval", r);
        String expected = Hashes.hex(Hashes.sha256(CanonicalJson.write(m).getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, PackageManifests.hash(m));
        assertEquals(List.of("treasury-endpoint"), PackageManifests.envBindings(m));
        assertEquals("p1-bill", PackageManifests.packageField(m, "key"));

        Map<String, byte[]> reordered = new LinkedHashMap<>();
        List.copyOf(r.keySet()).reversed().forEach(k -> reordered.put(k, r.get(k)));
        assertEquals(expected, PackageManifests.hash(PackageManifests.manifest("p1-bill", "Bill approval", reordered)),
                "resource order does not change the hash");
        assertNotEquals(expected, PackageManifests.hash(PackageManifests.manifest("p1-bill", "Bill approval",
                resources("<definitions />", "{\"envBindings\":[\"treasury-endpoint\"]}"))), "one changed byte");
        assertNotEquals(expected, PackageManifests.hash(PackageManifests.manifest("p2-bill", "Bill approval", r)),
                "the key is inside the manifest");
    }

    @Test
    void theAuthoredPartIsValidated() {
        assertEquals(Map.of(), PackageManifests.authored(resources("<x/>", null)), "no manifest resource: nothing declared");
        assertEquals("PACKAGE_MANIFEST_INVALID", assertThrows(ApiProblem.class, () -> PackageManifests.authored(
                resources("<x/>", "[1,2]"))).code(), "not an object");
        assertEquals("PACKAGE_MANIFEST_INVALID", assertThrows(ApiProblem.class, () -> PackageManifests.authored(
                resources("<x/>", "{\"artifacts\":[]}"))).code(), "artifacts is computed by the server");
        assertEquals("PACKAGE_MANIFEST_INVALID", assertThrows(ApiProblem.class, () -> PackageManifests.authored(
                resources("<x/>", "{\"package\":{\"key\":\"other\"}}"))).code(), "package is computed by the server");
        assertEquals("PACKAGE_MANIFEST_INVALID", assertThrows(ApiProblem.class, () -> PackageManifests.authored(
                resources("<x/>", "{\"envBindings\":[\"a b\"]}"))).code(), "invalid binding name");
        assertEquals("PACKAGE_MANIFEST_INVALID", assertThrows(ApiProblem.class, () -> PackageManifests.authored(
                resources("<x/>", "{\"envBindings\":[\"a\",\"a\"]}"))).code(), "duplicate binding name");
        Map<String, Object> decimals = PackageManifests.manifest("k", "k", resources("<x/>", "{\"limits\":{\"max\":1.5}}"));
        assertEquals("PACKAGE_MANIFEST_INVALID", assertThrows(ApiProblem.class, () -> PackageManifests.hash(decimals))
                .code(), "decimals must be strings (R33)");
    }

    @Test
    void theTargetFindsChangedMissingAndExtraArtifacts() {
        Map<String, byte[]> r = resources("<definitions/>", null);
        Map<String, Object> m = PackageManifests.manifest("k", "k", r);
        Map<String, String> artifacts = new LinkedHashMap<>();
        r.forEach((k, v) -> artifacts.put(k, PackageManifests.encode(k, v)));
        assertEquals("AQID", artifacts.get("logo.png"), "binary as base64");
        assertEquals("<definitions/>", artifacts.get("bill.bpmn"), "text as text");
        PackageManifests.ArtifactCheck ok = PackageManifests.artifacts(m, artifacts);
        assertTrue(ok.mismatches().isEmpty(), ok.mismatches().toString());
        assertEquals(PackageManifests.hash(m), PackageManifests.hash(PackageManifests.manifest("k", "k", ok.bytes())));

        Map<String, String> tampered = new LinkedHashMap<>(artifacts);
        tampered.put("bill.bpmn", "<definitions />");
        tampered.remove("logo.png");
        tampered.put("extra.dmn", "<definitions/>");
        assertEquals(List.of("bill.bpmn", "extra.dmn", "logo.png"), PackageManifests.artifacts(m, tampered).mismatches());

        Map<String, String> badBase64 = new LinkedHashMap<>(artifacts);
        badBase64.put("logo.png", "not base64!");
        assertEquals(List.of("logo.png"), PackageManifests.artifacts(m, badBase64).mismatches());
    }

    @Test
    void signaturesVerifyWithATrustedKeyOnly() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        AuditCheckpoints.Signer signer = AuditCheckpoints.ed25519(
                Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()), pub);
        String hash = PackageManifests.hash(PackageManifests.manifest("k", "k", resources("<x/>", null)));
        PackageSignature approver = PackageManifests.sign(signer, hash, PackageManifests.ROLE_APPROVER, "bob", "seat-b",
                Instant.parse("2026-11-12T00:00:00Z"));
        assertEquals("Ed25519", approver.alg());
        assertEquals(signer.keyId(), approver.keyId());

        List<SignatureCheck> ok = PackageManifests.check(hash, List.of(approver), AuditCheckpoints.ed25519Keys(List.of(pub)),
                List.of(PackageManifests.ROLE_APPROVER));
        assertEquals(List.of(new SignatureCheck("approver", signer.keyId(), true, "OK")), ok);

        List<SignatureCheck> unknown = PackageManifests.check(hash, List.of(approver), AuditCheckpoints.ed25519Keys(List.of()),
                List.of(PackageManifests.ROLE_APPROVER));
        assertEquals("UNKNOWN_KEY", unknown.get(0).reason());
        assertEquals("ROLE_MISSING", unknown.get(1).reason());

        byte[] sig = Base64.getDecoder().decode(approver.signature());
        sig[0] ^= 1;
        PackageSignature flipped = new PackageSignature(hash, "approver", "bob", "seat-b", "Ed25519", approver.keyId(),
                Base64.getEncoder().encodeToString(sig), approver.signedAt());
        assertEquals("BAD_SIGNATURE", PackageManifests.check(hash, List.of(flipped),
                AuditCheckpoints.ed25519Keys(List.of(pub)), List.of()).get(0).reason());

        String otherHash = PackageManifests.hash(PackageManifests.manifest("k2", "k2", resources("<x/>", null)));
        SignatureCheck replayed = PackageManifests.check(otherHash, List.of(approver),
                AuditCheckpoints.ed25519Keys(List.of(pub)), List.of()).get(0);
        assertFalse(replayed.valid(), "a signature over another hash does not verify");

        List<SignatureCheck> none = PackageManifests.check(hash, List.of(), AuditCheckpoints.ed25519Keys(List.of(pub)),
                List.of(PackageManifests.ROLE_APPROVER));
        assertEquals(List.of(new SignatureCheck("approver", null, false, "ROLE_MISSING")), none);
    }

    /** Decision 84: role, signer, seat and time are inside the signed bytes. */
    @Test
    void aRelabelledSignatureIsRejected() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        AuditCheckpoints.Signer signer = AuditCheckpoints.ed25519(
                Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()), pub);
        AuditCheckpoints.KeyLookup keys = AuditCheckpoints.ed25519Keys(List.of(pub));
        String hash = PackageManifests.hash(PackageManifests.manifest("k", "k", resources("<x/>", null)));
        Instant at = Instant.parse("2026-11-12T10:15:30.123456789Z");
        PackageSignature author = PackageManifests.sign(signer, hash, PackageManifests.ROLE_AUTHOR, "alice", "seat-a", at);
        PackageSignature approver = PackageManifests.sign(signer, hash, PackageManifests.ROLE_APPROVER, "alice", "seat-a",
                at);
        assertEquals(Instant.parse("2026-11-12T10:15:30.123Z"), author.signedAt(), "cut to milliseconds");
        assertNotEquals(author.signature(), approver.signature(), "one key, two roles: two different signatures");
        assertEquals("{\"packageHash\":\"" + hash + "\",\"principalId\":\"alice\",\"role\":\"author\",\"seatId\":\"seat-a\","
                + "\"signedAt\":\"2026-11-12T10:15:30.123Z\"}", new String(PackageManifests.signedBytes(hash, "author",
                "alice", "seat-a", author.signedAt()), StandardCharsets.UTF_8), "JCS: sorted members");
        assertTrue(PackageManifests.check(hash, List.of(author), keys, List.of(PackageManifests.ROLE_AUTHOR)).get(0).valid());

        PackageSignature relabelled = new PackageSignature(hash, PackageManifests.ROLE_APPROVER, author.principalId(),
                author.seatId(), author.alg(), author.keyId(), author.signature(), author.signedAt());
        List<SignatureCheck> roleSwap = PackageManifests.check(hash, List.of(relabelled), keys,
                List.of(PackageManifests.ROLE_APPROVER));
        assertEquals("BAD_SIGNATURE", roleSwap.get(0).reason(), "an author signature cannot pass as the approver's");
        assertEquals("ROLE_MISSING", roleSwap.get(1).reason());

        PackageSignature otherPerson = new PackageSignature(hash, author.role(), "mallory", author.seatId(), author.alg(),
                author.keyId(), author.signature(), author.signedAt());
        PackageSignature otherSeat = new PackageSignature(hash, author.role(), author.principalId(), "seat-x", author.alg(),
                author.keyId(), author.signature(), author.signedAt());
        PackageSignature otherTime = new PackageSignature(hash, author.role(), author.principalId(), author.seatId(),
                author.alg(), author.keyId(), author.signature(), author.signedAt().plusMillis(1));
        PackageSignature noRole = new PackageSignature(hash, null, author.principalId(), author.seatId(), author.alg(),
                author.keyId(), author.signature(), author.signedAt());
        for (PackageSignature s : List.of(otherPerson, otherSeat, otherTime, noRole)) {
            assertEquals("BAD_SIGNATURE", PackageManifests.check(hash, List.of(s), keys, List.of()).get(0).reason(),
                    s.toString());
        }
    }

    @Test
    void hashBytesAcceptsExactly64HexCharacters() {
        assertEquals(32, PackageManifests.hashBytes("ab".repeat(32)).length);
        assertNull(PackageManifests.hashBytes("ab"));
        assertNull(PackageManifests.hashBytes("zz".repeat(32)));
        assertNull(PackageManifests.hashBytes(null));
    }

    @Test
    void aBindingValueIsAReferenceNeverASecret() {
        JdbcEnvBindings.requireReference("https://treasury.example/api");
        JdbcEnvBindings.requireReference("secret://treasury/api-key");
        assertEquals("VALIDATION_FAILED", assertThrows(ApiProblem.class,
                () -> JdbcEnvBindings.requireReference("s3cr3t-value")).code());
        assertEquals("VALIDATION_FAILED", assertThrows(ApiProblem.class,
                () -> JdbcEnvBindings.requireReference("mailto:x@example.org")).code());
    }
}
