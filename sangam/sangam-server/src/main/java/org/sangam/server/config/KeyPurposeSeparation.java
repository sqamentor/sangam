// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.sangam.integration.connector.SecretResolver;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Owner decision 37 (Q-04): one key per purpose. The start is refused by name when one key serves two purposes: two
 * key settings of different purposes that name the same secret reference, or whose values (literal, behind a secret
 * reference, or in the key-encryption key store) are the same key material.
 *
 * <p>The purposes and their settings are the table {@link #SETTINGS}; {@code KeyPurposeSeparationTest} fails when a
 * key setting read by the server is missing from it. Key material is compared by SHA-256 digest only and never logged
 * or printed; a refusal names the two settings and their purposes, not the reference or the value. Ed25519 keys are
 * compared by their raw key bytes as well as their encoding (the 32-byte seed of a PKCS#8 private key, the 32-byte
 * key of an X.509 public key), so the same bytes as a raw key of another purpose are caught too.
 *
 * <p>A reference that does not resolve is compared by reference only; the setting's own wiring refuses it when it
 * uses the key. The environment part of decision 37 (a key of its own per environment, owner decision 385 (3)) is
 * {@link KeyEnvironmentSeparation}: it compares the one-way {@link Entry#fingerprints} of the keys this environment
 * holds with the lists other environments publish.
 */
public final class KeyPurposeSeparation {

    /** The refusal code, also the first word of the message. */
    public static final String CODE = "KEY_SHARED_ACROSS_PURPOSES";

    /** How a setting holds its key or keys. */
    enum Source {
        /** One literal base64 value. */
        LITERAL,
        /** A map {@code <setting>.<id>=<base64>} of literal values. */
        LITERAL_MAP,
        /** One {@code secret://} reference. */
        REFERENCE,
        /** A comma list of {@code secret://} references. */
        REFERENCE_LIST,
        /** The PKCS#12 key store of the key-encryption key ({@code <setting>.path}, {@code .store-password}, ...). */
        PKCS12,
        /** A directory of public keys ({@code *.pub}, base64 or PEM X.509), as PluginManager reads it. */
        KEY_DIRECTORY
    }

    /**
     * Whose key a setting holds (owner decision 385 (3)): a secret of this environment, a public key of this environment,
     * or a trust list of public keys of others (publishers, signers). Only the environment's own keys have a
     * fingerprint: a trusted key is someone else's and may be trusted by several environments.
     */
    enum Role {
        /** This environment's secret (a private key, a symmetric key, a pepper): HMAC-SHA256 fingerprint. */
        SECRET,
        /** This environment's public key: SHA-256 fingerprint. */
        PUBLIC,
        /** Public keys this environment trusts: no fingerprint. */
        TRUST
    }

    /**
     * One key setting of a purpose; {@code fallback} is the value the server uses when the setting is unset (a
     * placeholder expression resolved against the environment), or null.
     */
    record Setting(String purpose, String property, Source source, Role role, String fallback) {
        Setting(String purpose, String property, Source source, Role role) {
            this(purpose, property, source, role, null);
        }
    }

    /** The key settings the server reads, by purpose (owner decision 37) and role (owner decision 385 (3)). */
    static final List<Setting> SETTINGS = List.of(
            new Setting("key-encryption key", "sangam.security.kek.keys", Source.LITERAL_MAP, Role.SECRET),
            new Setting("key-encryption key", "sangam.security.kek.pkcs12", Source.PKCS12, Role.SECRET),
            new Setting("registry signer", "sangam.registry.signer.private-key", Source.LITERAL, Role.SECRET),
            new Setting("registry signer", "sangam.registry.signer.public-key", Source.LITERAL, Role.PUBLIC),
            new Setting("registry identifier vault pepper", "sangam.registry.vault.hmac-pepper", Source.LITERAL, Role.SECRET),
            new Setting("national-id vault pepper", "sangam.compliance.national-id-vault.hmac-pepper", Source.LITERAL, Role.SECRET),
            new Setting("compliance principal key", "sangam.compliance.principal-key-ref", Source.REFERENCE, Role.SECRET),
            new Setting("audit checkpoint signing", "sangam.audit.checkpoint.private-key-ref", Source.REFERENCE, Role.SECRET),
            new Setting("audit checkpoint signing", "sangam.audit.checkpoint.public-key-ref", Source.REFERENCE, Role.PUBLIC),
            new Setting("audit checkpoint signing", "sangam.audit.checkpoint.trusted-public-key-refs",
                    Source.REFERENCE_LIST, Role.TRUST),
            new Setting("package signing", "sangam.packages.signing.private-key-ref", Source.REFERENCE, Role.SECRET),
            new Setting("package signing", "sangam.packages.signing.public-key-ref", Source.REFERENCE, Role.PUBLIC),
            new Setting("package signing", "sangam.packages.signing.trusted-public-key-refs", Source.REFERENCE_LIST, Role.TRUST),
            new Setting("onboarding bundle signing", "sangam.onboarding.bundle.private-key", Source.LITERAL, Role.SECRET),
            new Setting("onboarding bundle signing", "sangam.onboarding.bundle.trusted-keys", Source.LITERAL_MAP, Role.TRUST),
            new Setting("extension catalogue trust", "sangam.modeller.catalogue.public-key", Source.LITERAL, Role.TRUST),
            // decision 390 (4): the key custodian's public key, which signs the key-environment attestation
            new Setting("key custodian trust", "sangam.keys.custodian-public-key", Source.LITERAL, Role.TRUST),
            // PluginWiring: default <sangam.plugins.folder>/trusted-keys
            new Setting("plugin signing trust", "sangam.plugins.trusted-keys", Source.KEY_DIRECTORY, Role.TRUST,
                    "${sangam.plugins.folder:lib-ext/plugins}/trusted-keys"));

    /** The DER prefix of an X.509 SubjectPublicKeyInfo of an Ed25519 key; the raw 32-byte key follows it. */
    private static final byte[] ED25519_X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    /** The fixed HMAC key of a secret's fingerprint (owner decision 385 (3)); a label, not a secret. */
    static final byte[] FINGERPRINT_LABEL = "sangam:key-fingerprint:v1".getBytes(StandardCharsets.UTF_8);

    /**
     * One configured key: its purpose, the setting that holds it, its reference (or null), material digests (compared
     * within this environment, never published) and fingerprints (published to other environments, owner decision 385
     * (3): SHA-256 of an own public key, HMAC-SHA256 under {@link #FINGERPRINT_LABEL} of an own secret, none for a
     * trusted key; each over the same forms as the material).
     */
    record Entry(String purpose, String setting, String reference, Set<String> material, Set<String> fingerprints) {
        static Entry of(Setting s, String setting, String reference, byte[] bytes) {
            Set<String> material = new LinkedHashSet<>();
            Set<String> fingerprints = new LinkedHashSet<>();
            for (byte[] f : forms(bytes)) {
                material.add(digest(f));
                switch (s.role()) {
                    case SECRET -> fingerprints.add(hmac(f));
                    case PUBLIC -> fingerprints.add(digest(f));
                    case TRUST -> {
                        // someone else's key: no fingerprint of this environment
                    }
                }
            }
            return new Entry(s.purpose(), setting, reference, Set.copyOf(material), Set.copyOf(fingerprints));
        }

        /** A reference that does not resolve here: compared by reference only, no material, no fingerprint. */
        static Entry unresolved(Setting s, String reference) {
            return new Entry(s.purpose(), s.property(), reference, Set.of(), Set.of());
        }
    }

    private KeyPurposeSeparation() {
    }

    /**
     * Refuses (IllegalStateException, {@link #CODE}) when one key serves two purposes; returns the number of key
     * entries checked.
     */
    public static int check(Environment env, SecretResolver secrets) {
        return check(entries(env, secrets));
    }

    /** {@link #check(Environment, SecretResolver)} over entries already read. */
    static int check(List<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                Entry a = entries.get(i);
                Entry b = entries.get(j);
                if (a.purpose().equals(b.purpose())) {
                    continue;
                }
                if (a.reference() != null && a.reference().equals(b.reference())) {
                    throw refusal(a, b, "name the same secret reference");
                }
                if (!Collections.disjoint(a.material(), b.material())) {
                    throw refusal(a, b, "hold the same key material");
                }
            }
        }
        return entries.size();
    }

    private static IllegalStateException refusal(Entry a, Entry b, String what) {
        return new IllegalStateException(CODE + " (owner decision 37: one key per purpose and environment): "
                + a.setting() + " (" + a.purpose() + ") and " + b.setting() + " (" + b.purpose() + ") " + what
                + "; give each purpose a key of its own");
    }

    static List<Entry> entries(Environment env, SecretResolver secrets) {
        List<Entry> out = new ArrayList<>();
        for (Setting s : SETTINGS) {
            switch (s.source()) {
                case LITERAL -> {
                    String v = env.getProperty(s.property());
                    if (notBlank(v)) {
                        out.add(Entry.of(s, s.property(), null, bytes(v)));
                    }
                }
                case LITERAL_MAP -> {
                    Map<String, String> m = Binder.get(env).bind(s.property(),
                            Bindable.mapOf(String.class, String.class)).orElse(Map.of());
                    for (Map.Entry<String, String> e : new TreeMap<>(m).entrySet()) {
                        if (notBlank(e.getValue())) {
                            out.add(Entry.of(s, s.property() + "." + e.getKey(), null, bytes(e.getValue())));
                        }
                    }
                }
                case REFERENCE -> {
                    String v = env.getProperty(s.property());
                    if (notBlank(v)) {
                        out.add(referenced(s, v.strip(), secrets));
                    }
                }
                case REFERENCE_LIST -> {
                    String v = env.getProperty(s.property(), "");
                    for (String ref : v.split(",")) {
                        if (notBlank(ref)) {
                            out.add(referenced(s, ref.strip(), secrets));
                        }
                    }
                }
                case PKCS12 -> out.addAll(keyStore(env, s));
                case KEY_DIRECTORY -> out.addAll(keyDirectory(env, s));
            }
        }
        return out;
    }

    /** A setting that should hold a {@code secret://} reference; a literal is compared as material. */
    private static Entry referenced(Setting s, String ref, SecretResolver secrets) {
        if (!ref.startsWith("secret://")) {
            // the setting's own wiring refuses a literal; compared as material meanwhile
            return Entry.of(s, s.property(), null, bytes(ref));
        }
        Optional<String> value;
        try {
            value = secrets == null ? Optional.empty() : secrets.resolve(ref);
        } catch (RuntimeException e) {
            // not resolvable here: compared by reference only, the setting's wiring refuses it when it uses the key
            value = Optional.empty();
        }
        return value.filter(KeyPurposeSeparation::notBlank)
                .map(v -> Entry.of(s, s.property(), ref, bytes(v)))
                .orElseGet(() -> Entry.unresolved(s, ref));
    }

    /** Every secret key in the PKCS#12 key store of the key-encryption key, as SecurityKeysWiring reads it. */
    private static List<Entry> keyStore(Environment env, Setting s) {
        String path = env.getProperty(s.property() + ".path");
        if (!notBlank(path)) {
            return List.of();
        }
        String storePw = env.getProperty(s.property() + ".store-password", "");
        String keyPw = env.getProperty(s.property() + ".key-password", storePw);
        List<Entry> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, storePw.toCharArray());
            for (String alias : Collections.list(ks.aliases())) {
                if (!ks.isKeyEntry(alias)) {
                    continue;
                }
                Key k = ks.getKey(alias, keyPw.toCharArray());
                byte[] encoded = k == null ? null : k.getEncoded();
                if (encoded != null) {
                    out.add(Entry.of(s, s.property() + ".path#" + alias, null, encoded));
                }
            }
        } catch (java.io.IOException | GeneralSecurityException | RuntimeException e) {
            // not readable here: SecurityKeysWiring refuses the store by name when it loads it
            return List.of();
        }
        return out;
    }

    /** Every {@code *.pub} file of a public-key directory, PEM armour and white space removed. */
    private static List<Entry> keyDirectory(Environment env, Setting s) {
        String dir = env.getProperty(s.property());
        if (!notBlank(dir) && s.fallback() != null) {
            dir = env.resolvePlaceholders(s.fallback());
        }
        if (!notBlank(dir) || !Files.isDirectory(Path.of(dir))) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(Path.of(dir))) {
            for (Path p : files.filter(x -> x.getFileName().toString().endsWith(".pub")).sorted().toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8)
                        .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
                if (notBlank(text)) {
                    out.add(Entry.of(s, s.property() + "/" + p.getFileName(), null, bytes(text)));
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            // not readable here: PluginManager reads the directory itself and refuses what it cannot read
            return List.of();
        }
        return out;
    }

    /** The bytes of a configured value: base64-decoded when it is base64, else its text. */
    static byte[] bytes(String value) {
        String v = value.strip();
        try {
            return Base64.getDecoder().decode(v);
        } catch (IllegalArgumentException e) {
            return v.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** The bytes, plus the raw key when the bytes are an Ed25519 PKCS#8 or X.509 key. */
    static List<byte[]> forms(byte[] bytes) {
        List<byte[]> out = new ArrayList<>();
        out.add(bytes);
        if (bytes.length == ED25519_X509_PREFIX.length + 32
                && Arrays.equals(bytes, 0, ED25519_X509_PREFIX.length, ED25519_X509_PREFIX, 0,
                ED25519_X509_PREFIX.length)) {
            out.add(Arrays.copyOfRange(bytes, ED25519_X509_PREFIX.length, bytes.length));
        } else {
            ed25519Seed(bytes).ifPresent(out::add);
        }
        return out;
    }

    private static Optional<byte[]> ed25519Seed(byte[] pkcs8) {
        try {
            PrivateKey k = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            return k instanceof EdECPrivateKey ed ? ed.getBytes() : Optional.empty();
        } catch (GeneralSecurityException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HMAC-SHA256 of the bytes under {@link #FINGERPRINT_LABEL}: one-way, and not the plain digest of the secret. */
    static String hmac(byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(FINGERPRINT_LABEL, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
