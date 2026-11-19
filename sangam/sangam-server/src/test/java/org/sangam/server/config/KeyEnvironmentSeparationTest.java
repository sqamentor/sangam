// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.integration.connector.SecretResolver;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;

/**
 * Owner decision 385 (3) (decision 37, environment part, option b): a key of this environment whose fingerprint is on
 * another environment's list is refused by name; production refuses to start without a list; the fingerprints are
 * SHA-256 of public keys and HMAC-SHA256 under the fixed label of secrets, never a value or a plain digest of a secret;
 * a refusal carries no key, reference or fingerprint.
 */
class KeyEnvironmentSeparationTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir
    Path dir;

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private static String[] ed25519() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new String[] {Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())};
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One environment's keys: a key-encryption key, a pepper, an audit pair by reference, a trusted bundle key. */
    private final class Env {
        final MockEnvironment env = new MockEnvironment();
        final Map<String, String> secrets = new HashMap<>();
        final String kek = random(32);
        final String pepper = random(32);
        final String[] audit = ed25519();
        final String trusted = ed25519()[1];

        Env(String name) {
            env.setConversionService(new ApplicationConversionService());
            env.setProperty(KeyEnvironmentSeparation.ENVIRONMENT, name);
            env.setProperty("sangam.security.kek.keys.k1", kek);
            env.setProperty("sangam.registry.vault.hmac-pepper", pepper);
            env.setProperty("sangam.audit.checkpoint.private-key-ref", "secret://audit/private");
            env.setProperty("sangam.audit.checkpoint.public-key-ref", "secret://audit/public");
            secrets.put("secret://audit/private", audit[0]);
            secrets.put("secret://audit/public", audit[1]);
            env.setProperty("sangam.onboarding.bundle.trusted-keys.publisher", trusted);
        }

        List<KeyPurposeSeparation.Entry> entries() {
            return KeyPurposeSeparation.entries(env, SecretResolver.of(secrets));
        }

        KeyEnvironmentSeparation.Result check(boolean production) {
            return KeyEnvironmentSeparation.check(env, entries(), production);
        }

        /** This environment's published list. */
        Path export(Path file) {
            env.setProperty(KeyEnvironmentSeparation.EXPORT, file.toString());
            check(false);
            env.setProperty(KeyEnvironmentSeparation.EXPORT, "");
            return file;
        }

        Env list(Path file) {
            env.setProperty(KeyEnvironmentSeparation.FOREIGN, file.toString());
            return this;
        }

        String refused(boolean production) {
            return assertThrows(IllegalStateException.class, () -> check(production)).getMessage();
        }
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static String hmac(byte[] b) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("sangam:key-fingerprint:v1".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(b));
    }

    @Test
    void productionRefusesToStartWithoutAList() {
        Env prod = new Env("production");
        String m = prod.refused(true);
        assertTrue(m.startsWith(KeyEnvironmentSeparation.LIST_REQUIRED), m);
        assertTrue(m.contains(KeyEnvironmentSeparation.FOREIGN) && m.contains("385"), m);
        Env unnamed = new Env("production").list(new Env("staging").export(dir.resolve("staging.txt")));
        unnamed.env.setProperty(KeyEnvironmentSeparation.ENVIRONMENT, "");
        assertTrue(unnamed.refused(true).startsWith(KeyEnvironmentSeparation.LIST_REQUIRED));
    }

    @Test
    void productionRefusesAListWithNoFingerprintOfAnotherEnvironment() throws Exception {
        Path empty = Files.writeString(dir.resolve("empty.txt"), "# nothing yet\n\n");
        assertTrue(new Env("production").list(empty).refused(true).contains("names no fingerprint"));
        // a merged list holding only this environment's own lines is no list of another environment
        Env prod = new Env("production");
        Path own = prod.export(dir.resolve("own.txt"));
        assertTrue(prod.list(own).refused(true).contains("names no fingerprint"));
    }

    @Test
    void environmentsWithKeysOfTheirOwnStart() {
        Path staging = new Env("staging").export(dir.resolve("staging.txt"));
        KeyEnvironmentSeparation.Result r = new Env("production").list(staging).check(true);
        assertEquals("production", r.environment());
        assertTrue(r.foreign() >= 6, "the staging kek, pepper and audit pair (raw forms too): " + r.foreign());
        assertTrue(r.own() >= 6, String.valueOf(r.own()));
    }

    @Test
    void aSecretOfAnotherEnvironmentIsRefusedByName() {
        Env staging = new Env("staging");
        Path list = staging.export(dir.resolve("staging.txt"));
        Env prod = new Env("production").list(list);
        prod.env.setProperty("sangam.security.kek.keys.k1", staging.kek);
        String m = prod.refused(true);
        assertTrue(m.startsWith(KeyEnvironmentSeparation.SHARED), m);
        assertTrue(m.contains("sangam.security.kek.keys.k1") && m.contains("key-encryption key")
                && m.contains("environment staging"), m);
        // outside production too: a shared key is refused in every environment that has a list
        assertTrue(prod.refused(false).startsWith(KeyEnvironmentSeparation.SHARED));
    }

    @Test
    void aSigningKeyPairOfAnotherEnvironmentIsRefusedFromEitherHalf() {
        Env staging = new Env("staging");
        Path list = staging.export(dir.resolve("staging.txt"));
        Env prod = new Env("production").list(list);
        prod.secrets.put("secret://audit/public", staging.audit[1]);
        assertTrue(prod.refused(true).contains("sangam.audit.checkpoint.public-key-ref"));
        Env prod2 = new Env("production").list(list);
        prod2.secrets.put("secret://audit/private", staging.audit[0]);
        assertTrue(prod2.refused(true).contains("sangam.audit.checkpoint.private-key-ref"));
    }

    @Test
    void theFingerprintsAreSha256OfPublicKeysAndHmacOfSecretsNeverAValue() throws Exception {
        Env staging = new Env("staging");
        String text = Files.readString(staging.export(dir.resolve("staging.txt")));
        byte[] kek = Base64.getDecoder().decode(staging.kek);
        byte[] pub = Base64.getDecoder().decode(staging.audit[1]);
        assertTrue(text.contains("staging " + hmac(kek) + " sangam.security.kek.keys.k1"), text);
        assertFalse(text.contains(sha256(kek)), "a secret's plain digest is never published");
        assertTrue(text.contains("staging " + sha256(pub) + " sangam.audit.checkpoint.public-key-ref"), text);
        assertFalse(text.contains(staging.kek) || text.contains(staging.pepper) || text.contains(staging.audit[0])
                || text.contains("secret://"), text);
        assertFalse(text.contains(sha256(Base64.getDecoder().decode(staging.trusted))), "a trusted key is not published");
    }

    @Test
    void aTrustedKeyMayBeTrustedByEveryEnvironment() {
        Env staging = new Env("staging");
        Path list = staging.export(dir.resolve("staging.txt"));
        Env prod = new Env("production").list(list);
        prod.env.setProperty("sangam.onboarding.bundle.trusted-keys.publisher", staging.trusted);
        assertEquals("production", prod.check(true).environment());
    }

    @Test
    void theRefusalCarriesNoKeyNoReferenceAndNoFingerprint() throws Exception {
        Env staging = new Env("staging");
        Path list = staging.export(dir.resolve("staging.txt"));
        Env prod = new Env("production").list(list);
        prod.env.setProperty("sangam.registry.vault.hmac-pepper", staging.pepper);
        String m = prod.refused(true);
        assertFalse(m.contains(staging.pepper) || m.contains("secret://")
                || m.contains(hmac(Base64.getDecoder().decode(staging.pepper))), m);
    }

    @Test
    void aListThatCannotBeUsedIsRefusedInEveryEnvironment() throws Exception {
        assertTrue(new Env("test").list(dir.resolve("missing.txt")).refused(false).contains("cannot be read"));
        Path bad = Files.writeString(dir.resolve("bad.txt"), "staging " + "ab".repeat(32) + "\nstaging not-hex\n");
        String m = new Env("test").list(bad).refused(false);
        assertTrue(m.contains("line 2") && !m.contains("not-hex"), m);
        Path upper = Files.writeString(dir.resolve("upper.txt"), "Staging " + "ab".repeat(32) + "\n");
        assertTrue(new Env("test").list(upper).refused(false).contains("line 1"));
    }

    @Test
    void aListOrAnExportNeedsTheEnvironmentsName() {
        Env e = new Env("x").list(dir.resolve("any.txt"));
        e.env.setProperty(KeyEnvironmentSeparation.ENVIRONMENT, "");
        assertTrue(e.refused(false).contains("needs " + KeyEnvironmentSeparation.ENVIRONMENT));
        Env bad = new Env("Production!");
        assertTrue(bad.refused(false).contains("must match"));
    }

    @Test
    void outsideProductionNoListStarts() {
        Env dev = new Env("dev");
        KeyEnvironmentSeparation.Result r = dev.check(false);
        assertEquals(0, r.foreign());
        MockEnvironment none = new MockEnvironment();
        assertEquals(0, KeyEnvironmentSeparation.check(none, List.of(), false).own());
    }

    @Test
    void theWiringRefusesAProductionStartWithoutAList() {
        Env prod = new Env("production");
        prod.env.setProperty("sangam.security.production", "true");
        org.springframework.beans.factory.support.DefaultListableBeanFactory bf =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        bf.registerSingleton("secrets", SecretResolver.of(prod.secrets));
        String m = assertThrows(IllegalStateException.class, () -> new KeyPurposeSeparationWiring()
                .sangamKeyPurposeSeparation(prod.env, bf.getBeanProvider(SecretResolver.class))).getMessage();
        assertTrue(m.startsWith(KeyEnvironmentSeparation.LIST_REQUIRED), m);
        prod.list(new Env("staging").export(dir.resolve("staging.txt")));
        assertEquals("production", new KeyPurposeSeparationWiring()
                .sangamKeyPurposeSeparation(prod.env, bf.getBeanProvider(SecretResolver.class)).environment());
    }


    /** Decisions 387 (4) and 390 (4): the key custodian's key pair. */
    private static final KeyPair CUSTODIAN = custodianPair();

    private static KeyPair custodianPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every key of {@code e} with its purpose, by its first fingerprint, as a custodian attests them. */
    private static List<KeyAttestation.Key> keysOf(Env e) {
        List<KeyAttestation.Key> out = new java.util.ArrayList<>();
        for (KeyPurposeSeparation.Entry x : e.entries()) {
            x.fingerprints().stream().sorted().findFirst()
                    .ifPresent(f -> out.add(new KeyAttestation.Key(f, x.purpose())));
        }
        return out;
    }

    private Env attested(Env e, String environment, List<KeyAttestation.Key> keys, java.security.PrivateKey by)
            throws Exception {
        Path file = Files.writeString(dir.resolve("attestation-" + RANDOM.nextInt(1_000_000) + ".json"),
                KeyAttestation.sign(environment, "2026-11-16T00:00:00Z", keys, by));
        e.env.setProperty(KeyEnvironmentSeparation.ATTESTATION, file.toString());
        e.env.setProperty(KeyEnvironmentSeparation.CUSTODIAN,
                Base64.getEncoder().encodeToString(CUSTODIAN.getPublic().getEncoded()));
        return e;
    }

    @Test
    void decision390AProductionStartsOverTheCustodiansAttestationInPlaceOfAList() throws Exception {
        Env prod = new Env("production");
        attested(prod, "production", keysOf(prod), CUSTODIAN.getPrivate());
        KeyEnvironmentSeparation.Result r = prod.check(true);
        assertEquals(keysOf(prod).size(), r.attested(), "every own key setting attested");
        assertTrue(r.attested() >= 4, String.valueOf(r.attested()));
        assertEquals(0, r.foreign());
        assertFalse(r.bootstrap());
    }

    @Test
    void decision390AStartRefusedForANewKeyStillWritesTheExportWithPurposesForTheCustodian() throws Exception {
        Env prod = new Env("production");
        List<KeyAttestation.Key> keys = new java.util.ArrayList<>(keysOf(prod));
        keys.removeIf(k -> k.purpose().equals("key-encryption key")); // the rotated key is not attested yet
        attested(prod, "production", keys, CUSTODIAN.getPrivate());
        Path export = dir.resolve("rotation-export.txt");
        prod.env.setProperty(KeyEnvironmentSeparation.EXPORT, export.toString());
        assertTrue(prod.refused(true).startsWith(KeyAttestation.INVALID));
        List<String> lines = Files.readAllLines(export).stream().filter(l -> !l.startsWith("#")).toList();
        assertFalse(lines.isEmpty(), "the refused start left the export");
        for (KeyPurposeSeparation.Entry e : prod.entries()) {
            for (String f : e.fingerprints()) {
                assertTrue(lines.contains("production " + f + " " + e.setting() + " " + e.purpose()),
                        e.setting() + " with its purpose, in " + lines.size() + " lines");
            }
        }
        // the export is still a valid list for the other environments
        Env staging = new Env("staging").list(export);
        assertEquals(lines.stream().map(l -> l.split(" ")[1]).distinct().count(), staging.check(true).foreign());
    }

    @Test
    void decision390AKeyNotAttestedOrAttestedForAnotherPurposeIsRefusedByName() throws Exception {
        Env prod = new Env("production");
        List<KeyAttestation.Key> keys = new java.util.ArrayList<>(keysOf(prod));
        KeyAttestation.Key kek = keys.stream().filter(k -> k.purpose().equals("key-encryption key")).findFirst()
                .orElseThrow();
        keys.remove(kek);
        attested(prod, "production", keys, CUSTODIAN.getPrivate());
        String m = prod.refused(true);
        assertTrue(m.startsWith(KeyAttestation.INVALID) && m.contains("sangam.security.kek.keys.k1"), m);
        assertFalse(m.contains(kek.fingerprint()), "a refusal never prints a fingerprint");
        keys.add(new KeyAttestation.Key(kek.fingerprint(), "audit checkpoint signing"));
        attested(prod, "production", keys, CUSTODIAN.getPrivate());
        assertTrue(prod.refused(true).contains("sangam.security.kek.keys.k1"), "the purpose is part of the attestation");
        // outside production an attestation that is set is checked too
        assertTrue(prod.refused(false).startsWith(KeyAttestation.INVALID));
    }

    @Test
    void decision390AnAttestationNotSignedByTheCustodianOrForAnotherEnvironmentIsRefused() throws Exception {
        Env prod = new Env("production");
        KeyPair other = custodianPair();
        attested(prod, "production", keysOf(prod), other.getPrivate());
        assertTrue(prod.refused(true).contains("does not verify"), "signed by another key");
        attested(prod, "staging", keysOf(prod), CUSTODIAN.getPrivate());
        assertTrue(prod.refused(true).contains("is for environment staging"));
        // a statement changed after signing
        attested(prod, "production", keysOf(prod), CUSTODIAN.getPrivate());
        Path file = Path.of(prod.env.getProperty(KeyEnvironmentSeparation.ATTESTATION));
        com.fasterxml.jackson.databind.node.ObjectNode doc = (com.fasterxml.jackson.databind.node.ObjectNode)
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(file));
        String statement = new String(Base64.getDecoder().decode(doc.get("statement").asText()), StandardCharsets.UTF_8);
        doc.put("statement", Base64.getEncoder().encodeToString(statement.replace("production", "productioN")
                .getBytes(StandardCharsets.UTF_8)));
        Files.writeString(file, doc.toString());
        assertTrue(prod.refused(true).contains("does not verify"));
        Files.writeString(file, "{\"format\": \"other/1\"}");
        assertTrue(prod.refused(true).contains("is not a " + KeyAttestation.FORMAT));
        Files.delete(file);
        assertTrue(prod.refused(true).contains("cannot be read"));
    }

    @Test
    void decision390AnAttestationNeedsAValidCustodianKey() throws Exception {
        Env prod = new Env("production");
        attested(prod, "production", keysOf(prod), CUSTODIAN.getPrivate());
        prod.env.setProperty(KeyEnvironmentSeparation.CUSTODIAN, "");
        assertTrue(prod.refused(true).contains("needs " + KeyEnvironmentSeparation.CUSTODIAN));
        prod.env.setProperty(KeyEnvironmentSeparation.CUSTODIAN, random(32));
        assertTrue(prod.refused(true).contains("is not a base64 X.509 Ed25519 public key"));
    }

    @Test
    void decision390TheOneTimeBootstrapStartsTheFirstProductionEnvironmentOnly() throws Exception {
        Env first = new Env("production");
        first.env.setProperty(KeyEnvironmentSeparation.BOOTSTRAP, "true");
        assertTrue(first.check(true).bootstrap(), "recorded once the database is ready");
        // refused once a list or an attestation exists
        Env listed = new Env("production").list(new Env("staging").export(dir.resolve("staging.txt")));
        listed.env.setProperty(KeyEnvironmentSeparation.BOOTSTRAP, "true");
        assertTrue(listed.refused(true).contains("is refused once " + KeyEnvironmentSeparation.FOREIGN));
        Env att = attested(new Env("production"), "production", List.of(), CUSTODIAN.getPrivate());
        att.env.setProperty(KeyEnvironmentSeparation.BOOTSTRAP, "true");
        assertTrue(att.refused(true).contains("is refused once " + KeyEnvironmentSeparation.ATTESTATION));
        // a value that is not a boolean, or no name
        first.env.setProperty(KeyEnvironmentSeparation.BOOTSTRAP, "yes");
        assertTrue(first.refused(true).contains("must be true or false"));
        first.env.setProperty(KeyEnvironmentSeparation.BOOTSTRAP, "true");
        first.env.setProperty(KeyEnvironmentSeparation.ENVIRONMENT, "");
        assertTrue(first.refused(true).startsWith(KeyEnvironmentSeparation.LIST_REQUIRED));
        // without the bootstrap, production still needs a list or an attestation
        assertTrue(new Env("production").refused(true).contains(KeyEnvironmentSeparation.ATTESTATION));
    }
}
