// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.interfaces.EdECPrivateKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.integration.connector.SecretResolver;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;

/**
 * Owner decision 37 (Q-04): one key per purpose. Every pair class is refused by name: the same secret reference for two
 * purposes, the same material behind two references, a literal and a reference, two literals, an Ed25519 key and the
 * same bytes as a raw key, one purpose's public key trusted by another, and the key-encryption key store; keys of one
 * purpose may repeat (rotation, the own key in the trust list). The refusal never carries a key or a reference.
 */
class KeyPurposeSeparationTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir
    Path dir;

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    /** Base64 PKCS#8 and X.509 of a fresh Ed25519 pair, and the raw 32-byte seed. */
    private static String[] ed25519() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] seed = ((EdECPrivateKey) kp.getPrivate()).getBytes().orElseThrow();
            return new String[] {Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()),
                    Base64.getEncoder().encodeToString(seed)};
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every purpose set, each with a key of its own, and the secret store behind the references. */
    private static final class Fixture {
        final MockEnvironment env = new MockEnvironment();
        final Map<String, String> secrets = new HashMap<>();
        final String kek = random(32);
        final String registryPepper = random(32);
        final String nationalIdPepper = random(32);
        final String principal = random(32);
        final String[] registry = ed25519();
        final String[] audit = ed25519();
        final String[] pkg = ed25519();
        final String[] bundle = ed25519();
        final String[] catalogue = ed25519();

        Fixture() {
            env.setConversionService(new ApplicationConversionService());
            env.setProperty("sangam.security.kek.keys.k1", kek);
            env.setProperty("sangam.security.kek.current-id", "k1");
            env.setProperty("sangam.registry.signer.private-key", registry[0]);
            env.setProperty("sangam.registry.signer.public-key", registry[1]);
            env.setProperty("sangam.registry.vault.hmac-pepper", registryPepper);
            env.setProperty("sangam.compliance.national-id-vault.hmac-pepper", nationalIdPepper);
            ref("sangam.compliance.principal-key-ref", "secret://compliance/principal-key", principal);
            ref("sangam.audit.checkpoint.private-key-ref", "secret://audit/checkpoint-test-private", audit[0]);
            ref("sangam.audit.checkpoint.public-key-ref", "secret://audit/checkpoint-test-public", audit[1]);
            ref("sangam.packages.signing.private-key-ref", "secret://packages/signing-test-private", pkg[0]);
            ref("sangam.packages.signing.public-key-ref", "secret://packages/signing-test-public", pkg[1]);
            env.setProperty("sangam.onboarding.bundle.private-key", bundle[0]);
            env.setProperty("sangam.onboarding.bundle.trusted-keys.b1", bundle[1]);
            env.setProperty("sangam.modeller.catalogue.public-key", catalogue[1]);
        }

        void ref(String property, String ref, String value) {
            env.setProperty(property, ref);
            secrets.put(ref, value);
        }

        int check() {
            return KeyPurposeSeparation.check(env, SecretResolver.of(secrets));
        }

        String refused() {
            return assertThrows(IllegalStateException.class, this::check).getMessage();
        }
    }

    private static void assertNames(String message, String a, String b) {
        assertTrue(message.startsWith(KeyPurposeSeparation.CODE), message);
        assertTrue(message.contains(a) && message.contains(b), message);
        assertTrue(message.contains("decision 37"), message);
    }

    @Test
    void everyPurposeWithAKeyOfItsOwnStarts() {
        Fixture f = new Fixture();
        assertEquals(13, f.check(), "every configured key entry is compared");
    }

    @Test
    void theSameSecretReferenceForTwoPurposesIsRefused() {
        Fixture f = new Fixture();
        f.env.setProperty("sangam.packages.signing.private-key-ref", "secret://audit/checkpoint-test-private");
        String m = f.refused();
        assertNames(m, "sangam.audit.checkpoint.private-key-ref", "sangam.packages.signing.private-key-ref");
        assertTrue(m.contains("same secret reference"), m);
    }

    @Test
    void theSameReferenceIsRefusedAlsoWhenItDoesNotResolve() {
        Fixture f = new Fixture();
        f.env.setProperty("sangam.compliance.principal-key-ref", "secret://shared/missing");
        f.env.setProperty("sangam.audit.checkpoint.trusted-public-key-refs", "secret://shared/missing");
        assertNames(f.refused(), "sangam.compliance.principal-key-ref",
                "sangam.audit.checkpoint.trusted-public-key-refs");
    }

    @Test
    void theSameMaterialBehindTwoReferencesIsRefused() {
        Fixture f = new Fixture();
        f.ref("sangam.packages.signing.private-key-ref", "secret://packages/copy", f.audit[0]);
        String m = f.refused();
        assertNames(m, "sangam.audit.checkpoint.private-key-ref", "sangam.packages.signing.private-key-ref");
        assertTrue(m.contains("same key material"), m);
    }

    @Test
    void aLiteralKeyAndTheSameKeyBehindAReferenceAreRefused() {
        Fixture f = new Fixture();
        f.env.setProperty("sangam.security.kek.keys.k1", f.principal);
        assertNames(f.refused(), "sangam.security.kek.keys.k1", "sangam.compliance.principal-key-ref");
    }

    @Test
    void twoLiteralPeppersOfTwoVaultsAreRefused() {
        Fixture f = new Fixture();
        f.env.setProperty("sangam.compliance.national-id-vault.hmac-pepper", f.registryPepper);
        assertNames(f.refused(), "sangam.registry.vault.hmac-pepper", "sangam.compliance.national-id-vault.hmac-pepper");
    }

    @Test
    void theKeyEncryptionKeyAsAPepperIsRefusedAlsoWithAnotherKeyIdAndSpacing() {
        Fixture f = new Fixture();
        f.env.setProperty("sangam.security.kek.keys.k0", f.kek);
        f.env.setProperty("sangam.registry.vault.hmac-pepper", " " + f.kek + " ");
        assertNames(f.refused(), "sangam.security.kek.keys.k", "sangam.registry.vault.hmac-pepper");
    }

    @Test
    void oneSigningPairForTwoPurposesIsRefused() {
        Fixture f = new Fixture();
        f.env.setProperty("sangam.registry.signer.private-key", f.bundle[0]);
        f.env.setProperty("sangam.registry.signer.public-key", f.bundle[1]);
        assertNames(f.refused(), "sangam.registry.signer.private-key", "sangam.onboarding.bundle.private-key");
    }

    @Test
    void anEd25519SeedUsedAsARawKeyOfAnotherPurposeIsRefused() {
        Fixture f = new Fixture();
        f.env.setProperty("sangam.registry.vault.hmac-pepper", f.audit[2]);
        assertNames(f.refused(), "sangam.registry.vault.hmac-pepper", "sangam.audit.checkpoint.private-key-ref");
    }

    @Test
    void onePurposesPublicKeyTrustedByAnotherIsRefused() {
        Fixture f = new Fixture();
        f.env.setProperty("sangam.onboarding.bundle.trusted-keys.pkg", f.pkg[1]);
        assertNames(f.refused(), "sangam.packages.signing.public-key-ref", "sangam.onboarding.bundle.trusted-keys.pkg");

        Fixture g = new Fixture();
        g.env.setProperty("sangam.modeller.catalogue.public-key", g.registry[1]);
        assertNames(g.refused(), "sangam.registry.signer.public-key", "sangam.modeller.catalogue.public-key");

        Fixture h = new Fixture();
        h.env.setProperty("sangam.packages.signing.trusted-public-key-refs",
                "secret://packages/other-env, secret://audit/checkpoint-test-public");
        h.secrets.put("secret://packages/other-env", ed25519()[1]);
        assertNames(h.refused(), "sangam.audit.checkpoint.public-key-ref",
                "sangam.packages.signing.trusted-public-key-refs");
    }

    @Test
    void aKeyEncryptionKeyStoreHoldingAnotherPurposesKeyIsRefused() throws Exception {
        Fixture g = new Fixture();
        // the key store instead of the literal keys (SecurityKeysWiring reads the store first)
        g.env.setProperty("sangam.security.kek.keys.k1", "");
        Path store = dir.resolve("kek.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        char[] pw = "store-pw-for-test".toCharArray();
        ks.setEntry("sangam-kek", new KeyStore.SecretKeyEntry(
                new SecretKeySpec(Base64.getDecoder().decode(g.registryPepper), "AES")), new KeyStore.PasswordProtection(pw));
        try (OutputStream out = Files.newOutputStream(store)) {
            ks.store(out, pw);
        }
        g.env.setProperty("sangam.security.kek.pkcs12.path", store.toString());
        g.env.setProperty("sangam.security.kek.pkcs12.store-password", new String(pw));
        assertNames(g.refused(), "sangam.security.kek.pkcs12.path#sangam-kek", "sangam.registry.vault.hmac-pepper");
    }

    @Test
    void aPluginTrustedKeyThatIsAnotherPurposesKeyIsRefused() throws Exception {
        Fixture f = new Fixture();
        Path keys = Files.createDirectories(dir.resolve("plugins").resolve("trusted-keys"));
        Files.writeString(keys.resolve("publisher.pub"), ed25519()[1]);
        f.env.setProperty("sangam.plugins.folder", dir.resolve("plugins").toString());
        assertEquals(14, f.check(), "the default directory <sangam.plugins.folder>/trusted-keys is read");
        // PEM armour and line breaks do not hide the package signing public key
        String b64 = f.pkg[1];
        Files.writeString(keys.resolve("packages.pub"), "-----BEGIN PUBLIC KEY-----\n" + b64.substring(0, 20) + "\n"
                + b64.substring(20) + "\n-----END PUBLIC KEY-----\n");
        assertNames(f.refused(), "sangam.packages.signing.public-key-ref", "sangam.plugins.trusted-keys/packages.pub");
    }

    @Test
    void keysOfOnePurposeMayRepeat() {
        Fixture f = new Fixture();
        // the own public key in the purpose's trust list, and a rotated key-encryption key under a second id
        f.env.setProperty("sangam.audit.checkpoint.trusted-public-key-refs", "secret://audit/checkpoint-test-public");
        f.env.setProperty("sangam.packages.signing.trusted-public-key-refs", "secret://packages/signing-test-public");
        f.env.setProperty("sangam.security.kek.keys.k2", f.kek);
        f.env.setProperty("sangam.onboarding.bundle.trusted-keys.b2", f.bundle[1]);
        assertEquals(17, f.check());
    }

    @Test
    void theRefusalCarriesNoKeyAndNoReference() {
        Fixture f = new Fixture();
        f.ref("sangam.packages.signing.private-key-ref", "secret://packages/copy", f.audit[0]);
        String m = f.refused();
        assertFalse(m.contains(f.audit[0]) || m.contains("secret://"), m);
        Fixture g = new Fixture();
        g.env.setProperty("sangam.compliance.national-id-vault.hmac-pepper", g.registryPepper);
        assertFalse(g.refused().contains(g.registryPepper));
    }

    @Test
    void nothingConfiguredStarts() {
        MockEnvironment env = new MockEnvironment();
        assertEquals(0, KeyPurposeSeparation.check(env, SecretResolver.of(Map.of())));
    }

    /**
     * Completeness: every key setting the server and sangam-rest read is in the purpose table, so a new key setting
     * cannot escape the check. Credentials of outside services (API keys, tokens, cloud access keys) are not keys of a
     * Sangam purpose and are not matched.
     */
    @Test
    void everyKeySettingTheServerReadsIsInThePurposeTable() throws Exception {
        Pattern literal = Pattern.compile(
                "\"(sangam\\.[a-z0-9.-]*(?:private-key|public-key|hmac-pepper|principal-key|trusted-keys|kek\\.(?:keys|pkcs12))[a-z0-9.-]*)\"");
        Pattern prefixed = Pattern.compile("PREFIX \\+ \"((?:private|public)-key[a-z-]*|trusted-public-key-refs)\"");
        Pattern prefixDef = Pattern.compile("static final String PREFIX = \"(sangam\\.[a-z0-9.-]+)\"");
        Set<String> found = new TreeSet<>();
        for (String root : new String[] {"src/main/java", "../sangam-rest/src/main/java"}) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (Path p : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                    String src = Files.readString(p);
                    Matcher m = literal.matcher(src);
                    while (m.find()) {
                        found.add(m.group(1));
                    }
                    Matcher d = prefixDef.matcher(src);
                    if (d.find()) {
                        Matcher q = prefixed.matcher(src);
                        while (q.find()) {
                            found.add(d.group(1) + q.group(1));
                        }
                    }
                }
            }
        }
        assertTrue(found.contains("sangam.packages.signing.private-key-ref"), () -> "the scan works: " + found);
        assertTrue(found.contains("sangam.registry.vault.hmac-pepper"), () -> "the scan works: " + found);
        for (String setting : found) {
            boolean covered = KeyPurposeSeparation.SETTINGS.stream()
                    .anyMatch(s -> setting.equals(s.property()) || setting.startsWith(s.property() + "."));
            assertTrue(covered, () -> setting + " is a key setting the server reads but not in"
                    + " KeyPurposeSeparation.SETTINGS (owner decision 37)");
        }
    }
}
