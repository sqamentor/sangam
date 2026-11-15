// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Source guard for RM-27 and RM-28 (owner decisions 143 and 152: a missing production setting is treated as
 * production, never as development): <b>nothing is permitted by default</b>. A port that decides who may do what, or
 * that records what was done, has no value that permits everything or records nothing unless a host stated it. A
 * permissive default is reached in production by omission (a bean that is absent, a null passed on), with at most a
 * warning in a log, so this test fails the day one appears in main code.
 *
 * <p>A plain-Java scan in the style of sangam-core's {@code BearerOnlyAuthenticationGuardTest} (comment lines skipped)
 * of the main Java sources of {@link #SCANNED}: sangam-billing, sangam-security, sangam-resources, sangam-gateway and
 * the idempotency package of sangam-integration. It fails on a line that
 * <ol>
 *   <li>names a permit-everything value ({@code ALLOW_ALL}, {@code PERMIT_ALL}, {@code TRUST_ANY}, {@code permitAll(},
 *       ...), as a definition or as a use;</li>
 *   <li>falls back from null to a function that answers {@code true} or does nothing
 *       ({@code x == null ? (a, b) -> true : x});</li>
 *   <li>assigns a function that answers {@code true} or does nothing to a field or variable
 *       ({@code private Guard guard = (c, a) -> { };}).</li>
 * </ol>
 * A function that does nothing is not always a permission (a metrics sink, a listener nobody registered yet): each such
 * line is on {@link #ALLOWED} with its exact count per file and the reason it permits nothing. The list is a ratchet:
 * a file with more matching lines fails, and an entry whose file has fewer fails until the entry is lowered or
 * removed, so it cannot go stale. Never add an entry for an authorisation, audit, accounting, payment or consent port.
 */
class PermissiveDefaultGuardTest {

    /** Reactor-relative source roots this guard covers (forward slashes). */
    static final List<String> SCANNED = List.of(
            "sangam-billing/src/main/java",
            "sangam-security/src/main/java",
            "sangam-resources/src/main/java",
            "sangam-gateway/src/main/java",
            "sangam-integration/src/main/java/org/sangam/integration/idempotency");

    /** A function literal that answers true or does nothing: {@code (a, b) -> true}, {@code x -> { }}. */
    private static final String PERMISSIVE_FUNCTION = "(?:\\([^()]*\\)|\\w+)\\s*->\\s*(?:true\\b|\\{\\s*\\})";

    /** Item 1: a named permit-everything value, defined or used. */
    static final Pattern NAMED = Pattern.compile(
            "\\b(?:ALLOW|PERMIT|ACCEPT|TRUST|GRANT)_(?:ALL|ANY|EVERY\\w*)\\b"
                    + "|\\b(?:permit|allow|accept|trust|grant)(?:All|Any|Every)\\w*\\s*\\(");

    /** Item 2: null falls back to a permissive function. */
    static final Pattern NULL_FALLBACK = Pattern.compile("==\\s*null\\s*\\?\\s*" + PERMISSIVE_FUNCTION);

    /** Item 3: a permissive function assigned to a field or variable. */
    static final Pattern ASSIGNED = Pattern.compile("=\\s*" + PERMISSIVE_FUNCTION + "\\s*;");

    /**
     * Main source (reactor-relative, forward slashes) -> exact number of matching lines, each with the reason it is not
     * a permission. State of 2026-11-15.
     */
    static final Map<String, Integer> ALLOWED = new TreeMap<>();

    static {
        // a metrics sink (tenant, source, outcome, rows): counts nothing when no meter registry is bound
        ALLOWED.put("sangam-security/src/main/java/org/sangam/security/crypto/FieldReencryptionJob.java", 1);
        // an observer of the external policy check's timing; the check itself denies on its own
        ALLOWED.put("sangam-security/src/main/java/org/sangam/security/policy/external/ExternalPolicyGate.java", 1);
        // Builder.withoutAuthorization: refused unless EnvironmentPolicy allows development-only defaults (decision 143)
        ALLOWED.put("sangam-resources/src/main/java/org/sangam/resources/ResourcesContext.java", 1);
        // listeners of freed capacity, empty until the waitlist registers itself
        ALLOWED.put("sangam-resources/src/main/java/org/sangam/resources/booking/AllotmentService.java", 1);
        ALLOWED.put("sangam-resources/src/main/java/org/sangam/resources/booking/BookingService.java", 1);
        // stand-alone ports: silent notifications, no display feed, no metrics, and NO_AUDIT. NO_AUDIT is debt, not a
        // precedent: the module's own history (RES_EVENT) is written in every case, the server binds the audit port.
        ALLOWED.put("sangam-resources/src/main/java/org/sangam/resources/port/ResourcesPorts.java", 4);
        // the definition of ReplayAuthorizer.ALLOW_ALL (development only). No main source refers to it: since RM-27
        // IdempotencyService refuses a missing authorizer instead of falling back to it.
        ALLOWED.put("sangam-integration/src/main/java/org/sangam/integration/idempotency/ReplayAuthorizer.java", 1);
    }

    static Path root() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    /** Every violation of one source as {@code path:line: text}; comment lines are not code. */
    static List<String> violations(String rel, String text) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String code = lines[i].strip();
            if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                continue;
            }
            if (NAMED.matcher(code).find() || NULL_FALLBACK.matcher(code).find() || ASSIGNED.matcher(code).find()) {
                out.add(rel + ":" + (i + 1) + ": " + code);
            }
        }
        return out;
    }

    static List<Path> javaSources(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".java")).sorted().toList();
        }
    }

    static String read(Path p) throws IOException {
        try {
            return Files.readString(p);
        } catch (MalformedInputException e) {
            return null;
        }
    }

    @Test
    void noMainSourcePermitsByDefault() throws IOException {
        Path root = root();
        Map<String, Integer> counts = new TreeMap<>();
        List<String> found = new ArrayList<>();
        int scanned = 0;
        for (String dir : SCANNED) {
            List<Path> sources = javaSources(root.resolve(dir));
            assertTrue(!sources.isEmpty(), "the guard found the sources of " + dir + " under " + root);
            for (Path p : sources) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                String text = read(p);
                if (text == null) {
                    continue;
                }
                scanned++;
                List<String> v = violations(rel, text);
                if (!v.isEmpty()) {
                    counts.put(rel, v.size());
                    if (!Integer.valueOf(v.size()).equals(ALLOWED.get(rel))) {
                        found.addAll(v);
                    }
                }
            }
        }
        assertTrue(scanned > 150, "scanned the main Java sources (" + scanned + ")");
        assertTrue(found.isEmpty(), "nothing is permitted by default (RM-27, RM-28): require the value from the host"
                + " and refuse without it, or, for a function that permits nothing, add an ALLOWED entry with the reason: "
                + found);
        for (Map.Entry<String, Integer> e : ALLOWED.entrySet()) {
            assertEquals(e.getValue(), counts.getOrDefault(e.getKey(), 0),
                    "allow-list entry is stale or changed (lower, remove or correct it): " + e.getKey());
        }
    }

    @Test
    void patternsMatchWhatTheyBan() {
        for (String s : List.of(
                "Guard ALLOW_ALL = (c, a, t, p, id) -> { };",
                "private Guard guard = Guard.ALLOW_ALL;",
                "this.authorizer = authorizer == null ? ReplayAuthorizer.ALLOW_ALL : authorizer;",
                "static final Policy PERMIT_ALL = new Policy();",
                "TrustManager[] tm = TRUST_ANY;",
                "ConsentPort ACCEPT_EVERYTHING = c -> Consent.GIVEN;",
                "http.authorizeHttpRequests(a -> a.anyRequest().permitAll());",
                "policy.allowAll();",
                "verifier.trustAnyCertificate(chain);",
                "this.guard = guard == null ? (c, a, t, p, id) -> { } : guard;",
                "this.check = check == null ? request -> true : check;",
                "this.check = check == null ? (a, b) -> true : check;",
                "private Guard guard = (c, a, t, p, id) -> { };",
                "private Predicate<String> mayRead = tenant -> true;",
                "public static final AuditPort NO_AUDIT = (c, a, e, s, p) -> { };",
                "this.guard = (c, actor, resourceType, permission, id) -> { };",
                "Observer NONE = (commandType, result, elapsed) -> {};")) {
            assertEquals(1, violations("A.java", s + "\n").size(), s);
        }
        for (String s : List.of(
                "this.authorizer = java.util.Objects.requireNonNull(authorizer, \"authorizer\");",
                "UnverifiedPaymentAuthority NONE = (caller, action, tenant, office, channel) -> false;",
                "this.authority = authority == null ? UnverifiedPaymentAuthority.NONE : authority;",
                "this(billing, ledger, profiles, accounts, periodResolver, clock, request -> null);",
                "private Guard guard;",
                "this.guard = Guard.of(authz);",
                "return rows.stream().filter(r -> true).toList();",
                "ApprovalPort NO_APPROVAL = (c, a, k, st, sid, bk, v) -> {",
                "boolean callAllowed = policy.permits(caller);",
                "String note = \"all rows are allowed\";",
                "// Guard ALLOW_ALL = (c, a, t, p, id) -> { };",
                " * falls back to {@link ReplayAuthorizer#ALLOW_ALL}",
                "case 400, 404, 410, 422 -> true;",
                ".onStatus(s -> true, r -> Mono.empty())",
                "allowed = e.allowedFlag() && within;")) {
            assertEquals(List.of(), violations("A.java", s + "\n"), s);
        }
    }

    @Test
    void theAllowListNamesMainSourcesOfTheScannedModules() {
        for (Map.Entry<String, Integer> e : ALLOWED.entrySet()) {
            assertTrue(e.getValue() > 0 && e.getKey().endsWith(".java")
                            && SCANNED.stream().anyMatch(dir -> e.getKey().startsWith(dir + "/")),
                    "entries are main Java sources of a scanned root, with a positive count: " + e.getKey());
            assertTrue(Files.isRegularFile(root().resolve(e.getKey())), "the file exists: " + e.getKey());
        }
    }
}
