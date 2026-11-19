// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.core.env.Environment;

/**
 * Owner decision 37, environment part, as owner decision 385 (3) chose it (option b): one key per environment. Each
 * environment publishes one-way fingerprints of its own keys ({@link KeyPurposeSeparation.Entry#fingerprints}: SHA-256
 * of a public key, HMAC-SHA256 under a fixed label of a secret; never a value, never a plain digest of a secret), and
 * the start is refused with {@link #SHARED} when a key of this environment has a fingerprint on another environment's
 * list. Production refuses to start without a list ({@link #LIST_REQUIRED}).
 *
 * <ul>
 *   <li>{@value #ENVIRONMENT}: this environment's name, {@code [a-z0-9][a-z0-9._-]{0,47}}; required in production and
 *       with a list or an export;</li>
 *   <li>{@value #FOREIGN}: the path of the list of the other environments' fingerprints, one
 *       {@code <environment> <fingerprint> [<setting>]} per line ({@code #} comments, blank lines ignored; lines of this
 *       environment are skipped, so one merged list serves every environment). Required in production, where it must
 *       name at least one fingerprint of another environment; a configured list that cannot be read or has a line of
 *       another form refuses the start in every environment (fail closed);</li>
 *   <li>{@value #EXPORT}: optional path where the start writes this environment's own list in the same form, each line
 *       followed by the setting's purpose, for the other environments and for the custodian's attestation; it is
 *       written before the list and attestation checks, so a refused start still leaves it;</li>
 *   <li>{@value #ATTESTATION} and {@value #CUSTODIAN} (owner decisions 387 (4) and 390 (4), option c): the path of the
 *       key custodian's Ed25519-signed attestation ({@link KeyAttestation}) and the custodian's public key. In
 *       production it stands in for {@value #FOREIGN}; wherever it is set, every key of this environment must be attested
 *       for this environment and its purpose, else the start is refused with {@link KeyAttestation#INVALID};</li>
 *   <li>{@value #BOOTSTRAP} (decision 387 (4), option a, the stopgap): {@code true} lets the first environment of an
 *       installation start once in production without a list or attestation. The start is recorded as the system audit
 *       event {@link KeyEnvironmentBootstrap#EVENT} once the database is ready, and refused on any later start of this
 *       environment with the setting still on; it is refused at once when a list or an attestation is set.</li>
 * </ul>
 * Trusted keys (trust lists of public keys of others, {@link KeyPurposeSeparation.Role#TRUST}) have no fingerprint and
 * are neither published nor compared. A refusal names the setting, its purpose and the other environment; never a key,
 * a reference or a fingerprint.
 */
public final class KeyEnvironmentSeparation {

    public static final String ENVIRONMENT = "sangam.keys.environment";
    public static final String FOREIGN = "sangam.keys.foreign-fingerprints";
    public static final String EXPORT = "sangam.keys.fingerprint-export";
    /** Decisions 387 (4) and 390 (4): the path of the key custodian's signed attestation ({@link KeyAttestation}). */
    public static final String ATTESTATION = "sangam.keys.attestation";
    /** The custodian's Ed25519 public key, base64 X.509: a trusted key of someone else. */
    public static final String CUSTODIAN = "sangam.keys.custodian-public-key";
    /** Decision 387 (4) (a): the one-time start of the first environment, before any list or attestation exists. */
    public static final String BOOTSTRAP = "sangam.keys.bootstrap";

    /** A key of this environment is a key of another environment too. */
    public static final String SHARED = "KEY_SHARED_ACROSS_ENVIRONMENTS";
    /** Production without this environment's name or the other environments' list, or a list that cannot be used. */
    public static final String LIST_REQUIRED = "KEY_ENVIRONMENT_LIST_REQUIRED";

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,47}");
    static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    private KeyEnvironmentSeparation() {
    }

    /**
     * The outcome: the fingerprints of this environment, the number of foreign fingerprints compared, the number of key
     * settings the custodian's attestation covers, and whether this start is the one-time bootstrap (recorded once the
     * database is ready, {@link KeyEnvironmentBootstrap}).
     */
    public record Result(String environment, int own, int foreign, int attested, boolean bootstrap) {
    }

    /**
     * Refuses (IllegalStateException, {@link #SHARED}, {@link #LIST_REQUIRED} or {@link KeyAttestation#INVALID}) as
     * described above; writes the export when one is configured.
     */
    public static Result check(Environment env, List<KeyPurposeSeparation.Entry> entries, boolean production) {
        String name = trimmed(env.getProperty(ENVIRONMENT));
        String list = trimmed(env.getProperty(FOREIGN));
        String export = trimmed(env.getProperty(EXPORT));
        String attestation = trimmed(env.getProperty(ATTESTATION));
        boolean bootstrap = bootstrap(env);
        if (bootstrap && (list != null || attestation != null)) {
            throw refusal(LIST_REQUIRED, BOOTSTRAP + " is refused once " + (list != null ? FOREIGN : ATTESTATION)
                    + " is set: the bootstrap is only for the first environment, before any list or attestation exists;"
                    + " remove " + BOOTSTRAP);
        }
        if (production && (name == null || (list == null && attestation == null && !bootstrap))) {
            throw refusal(LIST_REQUIRED, "production refuses to start without " + ENVIRONMENT + " and either " + FOREIGN
                    + " (the fingerprints of the other environments' keys) or " + ATTESTATION + " (the key custodian's"
                    + " signed attestation); the first environment of an installation may start once with " + BOOTSTRAP);
        }
        if (name == null && (list != null || export != null || attestation != null || bootstrap)) {
            throw refusal(LIST_REQUIRED, (list != null ? FOREIGN : export != null ? EXPORT
                    : attestation != null ? ATTESTATION : BOOTSTRAP) + " needs " + ENVIRONMENT);
        }
        if (name != null && !NAME.matcher(name).matches()) {
            throw refusal(LIST_REQUIRED, ENVIRONMENT + " must match " + NAME.pattern());
        }
        Map<String, Set<String>> own = own(entries);
        if (export != null) {
            // before the list and attestation checks: a start refused for a new, not yet attested key still leaves
            // the custodian the fingerprints to sign (decision 390 (4), key rotation); the export holds no key
            write(Path.of(export), name, entries);
        }
        int foreign = 0;
        if (list != null) {
            Map<String, String> theirs = read(Path.of(list), name);
            if (production && theirs.isEmpty()) {
                throw refusal(LIST_REQUIRED, FOREIGN + " names no fingerprint of another environment");
            }
            foreign = theirs.size();
            for (KeyPurposeSeparation.Entry e : entries) {
                for (String f : e.fingerprints()) {
                    String other = theirs.get(f);
                    if (other != null) {
                        throw refusal(SHARED, e.setting() + " (" + e.purpose() + ") is a key of environment " + other
                                + " too; give each environment a key of its own");
                    }
                }
            }
        }
        int attested = attestation == null ? 0 : attest(env, entries, name, Path.of(attestation));
        return new Result(name, own.values().stream().mapToInt(Set::size).sum(), foreign, attested, bootstrap);
    }

    /**
     * Owner decisions 387 (4) and 390 (4): every key of this environment (each setting with a fingerprint) must be
     * attested by the custodian for this environment and the setting's purpose, by one of its fingerprints; returns the
     * number of settings attested.
     */
    static int attest(Environment env, List<KeyPurposeSeparation.Entry> entries, String name, Path file) {
        String custodian = trimmed(env.getProperty(CUSTODIAN));
        if (custodian == null) {
            throw KeyAttestation.refusal(ATTESTATION + " needs " + CUSTODIAN + " (the custodian's Ed25519 public key)");
        }
        KeyAttestation.Statement s = KeyAttestation.verify(file, KeyAttestation.custodian(custodian));
        if (!s.environment().equals(name)) {
            throw KeyAttestation.refusal("the attestation is for environment " + s.environment() + ", not " + name);
        }
        int n = 0;
        for (KeyPurposeSeparation.Entry e : entries) {
            if (e.fingerprints().isEmpty()) {
                continue; // a trusted key of someone else, or a reference that does not resolve here
            }
            boolean ok = e.fingerprints().stream()
                    .anyMatch(f -> s.keys().contains(new KeyAttestation.Key(f, e.purpose())));
            if (!ok) {
                throw KeyAttestation.refusal(e.setting() + " (" + e.purpose() + ") is not attested for environment "
                        + name + " and that purpose; the custodian attests each key with its purpose");
            }
            n++;
        }
        return n;
    }

    /** {@value #BOOTSTRAP}: true or false; any other value refuses (fail closed). */
    static boolean bootstrap(Environment env) {
        String v = trimmed(env.getProperty(BOOTSTRAP));
        if (v == null || "false".equalsIgnoreCase(v)) {
            return false;
        }
        if ("true".equalsIgnoreCase(v)) {
            return true;
        }
        throw refusal(LIST_REQUIRED, BOOTSTRAP + " must be true or false");
    }

    /** fingerprint -> settings of this environment that hold it, sorted. */
    static Map<String, Set<String>> own(List<KeyPurposeSeparation.Entry> entries) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (KeyPurposeSeparation.Entry e : entries) {
            for (String f : e.fingerprints()) {
                out.computeIfAbsent(f, k -> new TreeSet<>()).add(e.setting());
            }
        }
        return out;
    }

    /** fingerprint -> environment of every line of another environment. */
    static Map<String, String> read(Path list, String self) {
        List<String> lines;
        try {
            lines = Files.readAllLines(list, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw refusal(LIST_REQUIRED, FOREIGN + " cannot be read (" + e.getClass().getSimpleName() + ")");
        }
        Map<String, String> out = new TreeMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] t = line.split("\\s+");
            if (t.length < 2 || !NAME.matcher(t[0]).matches() || !FINGERPRINT.matcher(t[1]).matches()) {
                throw refusal(LIST_REQUIRED, FOREIGN + " line " + (i + 1)
                        + " is not '<environment> <fingerprint> [<setting> [<purpose>]]' (64 lower-case hex digits)");
            }
            if (!t[0].equals(self)) {
                out.putIfAbsent(t[1], t[0]);
            }
        }
        return out;
    }

    /**
     * Writes this environment's list (via a temporary file and a move, so a reader never sees half of it): one line
     * {@code <environment> <fingerprint> <setting> <purpose>} per fingerprint and setting; the purpose (the rest of the
     * line, {@link KeyPurposeSeparation#SETTINGS}) is what the custodian attests with the fingerprint.
     */
    static void write(Path export, String name, List<KeyPurposeSeparation.Entry> entries) {
        Set<String> rows = new TreeSet<>();
        for (KeyPurposeSeparation.Entry e : entries) {
            for (String f : e.fingerprints()) {
                rows.add(name + " " + f + " " + e.setting() + " " + e.purpose());
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("# Sangam key fingerprints of environment " + name + " (owner decisions 37 and 385 (3)); one-way, no"
                + " key values; <environment> <fingerprint> <setting> <purpose>");
        lines.addAll(rows);
        try {
            Path dir = export.toAbsolutePath().getParent();
            Path tmp = Files.createTempFile(dir, ".key-fingerprints", ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, export, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            throw refusal(LIST_REQUIRED, EXPORT + " cannot be written (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static IllegalStateException refusal(String code, String what) {
        return new IllegalStateException(code + " (owner decisions 37 and 385 (3): one key per environment): " + what);
    }

    private static String trimmed(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
