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
 *   <li>{@value #EXPORT}: optional path where the start writes this environment's own list in the same form, for the
 *       other environments.</li>
 * </ul>
 * Trusted keys (trust lists of public keys of others, {@link KeyPurposeSeparation.Role#TRUST}) have no fingerprint and
 * are neither published nor compared. A refusal names the setting, its purpose and the other environment; never a key,
 * a reference or a fingerprint.
 */
public final class KeyEnvironmentSeparation {

    public static final String ENVIRONMENT = "sangam.keys.environment";
    public static final String FOREIGN = "sangam.keys.foreign-fingerprints";
    public static final String EXPORT = "sangam.keys.fingerprint-export";

    /** A key of this environment is a key of another environment too. */
    public static final String SHARED = "KEY_SHARED_ACROSS_ENVIRONMENTS";
    /** Production without this environment's name or the other environments' list, or a list that cannot be used. */
    public static final String LIST_REQUIRED = "KEY_ENVIRONMENT_LIST_REQUIRED";

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,47}");
    static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    private KeyEnvironmentSeparation() {
    }

    /** The outcome: the fingerprints of this environment and the number of foreign fingerprints compared. */
    public record Result(String environment, int own, int foreign) {
    }

    /**
     * Refuses (IllegalStateException, {@link #SHARED} or {@link #LIST_REQUIRED}) as described above; writes the export
     * when one is configured.
     */
    public static Result check(Environment env, List<KeyPurposeSeparation.Entry> entries, boolean production) {
        String name = trimmed(env.getProperty(ENVIRONMENT));
        String list = trimmed(env.getProperty(FOREIGN));
        String export = trimmed(env.getProperty(EXPORT));
        if (production && (name == null || list == null)) {
            throw refusal(LIST_REQUIRED, "production refuses to start without " + ENVIRONMENT + " and " + FOREIGN
                    + " (the fingerprints of the other environments' keys)");
        }
        if (name == null && (list != null || export != null)) {
            throw refusal(LIST_REQUIRED, (list != null ? FOREIGN : EXPORT) + " needs " + ENVIRONMENT);
        }
        if (name != null && !NAME.matcher(name).matches()) {
            throw refusal(LIST_REQUIRED, ENVIRONMENT + " must match " + NAME.pattern());
        }
        Map<String, Set<String>> own = own(entries);
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
        if (export != null) {
            write(Path.of(export), name, own);
        }
        return new Result(name, own.values().stream().mapToInt(Set::size).sum(), foreign);
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
            if (t.length < 2 || t.length > 3 || !NAME.matcher(t[0]).matches() || !FINGERPRINT.matcher(t[1]).matches()) {
                throw refusal(LIST_REQUIRED, FOREIGN + " line " + (i + 1)
                        + " is not '<environment> <fingerprint> [<setting>]' (64 lower-case hex digits)");
            }
            if (!t[0].equals(self)) {
                out.putIfAbsent(t[1], t[0]);
            }
        }
        return out;
    }

    /** Writes this environment's list (via a temporary file and a move, so a reader never sees half of it). */
    static void write(Path export, String name, Map<String, Set<String>> own) {
        List<String> lines = new ArrayList<>();
        lines.add("# Sangam key fingerprints of environment " + name + " (owner decisions 37 and 385 (3)); one-way, no"
                + " key values");
        own.forEach((f, settings) -> settings.forEach(s -> lines.add(name + " " + f + " " + s)));
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
