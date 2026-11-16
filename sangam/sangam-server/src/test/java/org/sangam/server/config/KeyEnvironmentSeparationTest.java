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
}
