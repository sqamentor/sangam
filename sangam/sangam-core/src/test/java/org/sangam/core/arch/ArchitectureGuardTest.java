// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.arch;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-106 source guards over the whole reactor (a plain-Java scan: ArchUnit is not a dependency of the build). Run from
 * sangam-core, so the reactor root is the parent folder; build output ({@code target*}), {@code node_modules} and
 * {@code .git} are skipped.
 * <ol>
 *   <li><b>Engine-neutral core</b> (ARCHITECTURE: sangam-core holds engine-neutral contracts): no {@code sangam-core}
 *       main source refers to {@code org.operaton} (import or qualified name), and the sangam-core pom declares no
 *       Operaton dependency.</li>
 *   <li><b>No gRPC</b> (SANGAM.md ADR-D-5: REST/OpenAPI first, gRPC only after a measured need and a decision): no main
 *       source refers to {@code io.grpc} or {@code com.google.protobuf}, and no main source tree holds a {@code .proto}
 *       file. The dependency side is the root pom's banned-dependency rule ({@code ReactorBannedDependenciesTest}).</li>
 *   <li><b>No disabled tests</b>: no test source uses JUnit's {@code @Disabled} (Jupiter) or {@code @Ignore} (JUnit 4).
 *       Conditional variants ({@code @DisabledIf*}, {@code @EnabledIf*}, {@code @DisabledOn*}) are not covered. A
 *       justified exception is on {@link #DISABLED_ALLOWED} with its register row and exact count; an entry that no
 *       longer matches fails, so the list cannot go stale.</li>
 *   <li><b>SPDX header</b> (ARCHITECTURE section 5): every Java source starts with
 *       {@code // SPDX-License-Identifier: Apache-2.0}; ported Operaton/Flowable code may instead keep its original
 *       Apache header and say "Modified by Sangam".</li>
 *   <li><b>Class size</b> (M-098, SANGAM.md D4: no class over about 800 lines): no main Java source has more than
 *       {@link #MAX_CLASS_LINES} lines ({@link String#lines()}). The files that were already larger are on
 *       {@link #CLASS_SIZE_ALLOWED} with their exact line count, as a ratchet: a listed file that grows fails, one that
 *       shrinks fails until its ceiling is lowered to the new count, and one back at or under the limit fails until
 *       its entry is removed. The list only ever gets shorter.</li>
 *   <li><b>Determinism in the core</b> (M-097, SANGAM.md D11: no hidden clocks, randomness or thread-locals in the
 *       core; time and ids come from injectable providers): no {@code sangam-core} main source line outside a comment
 *       matches {@link #NONDETERMINISTIC} (system clock reads such as {@code System.currentTimeMillis}, {@code new Date()},
 *       {@code Instant.now}, {@code Clock.systemUTC}; system entropy such as {@code UUID.randomUUID},
 *       {@code new SecureRandom}, {@code ThreadLocalRandom}; and {@code ThreadLocal}). Core code takes
 *       {@code SangamClock}, {@code BusinessClock} or {@code IdSource} instead. {@link #DETERMINISM_PROVIDERS} holds the
 *       production defaults of those providers, {@link #DETERMINISM_ALLOWED} the remaining debt (target: empty), both
 *       with exact line counts as a ratchet: a new file or a higher count fails, and a lower count fails until the
 *       entry is lowered (or removed, at 0). Neither list may gain an entry or a higher count.</li>
 * </ol>
 */
class ArchitectureGuardTest {

    static final String SPDX = "// SPDX-License-Identifier: Apache-2.0";
    static final Pattern OPERATON = Pattern.compile("\\borg\\.operaton\\b");
    static final Pattern GRPC = Pattern.compile("\\bio\\.grpc\\b|\\bcom\\.google\\.protobuf\\b");
    static final Pattern DISABLED = Pattern.compile(
            "@(?:org\\.junit\\.jupiter\\.api\\.)?Disabled(?![A-Za-z0-9_])|@(?:org\\.junit\\.)?Ignore(?![A-Za-z0-9_])");
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", ".git");

    /** Test file (reactor-relative, forward slashes) -> exact number of allowed hits, with the register row. */
    static final Map<String, Integer> DISABLED_ALLOWED = new TreeMap<>();

    static {
        // no entries: SL-54's rollback test is enabled again (roll-forward rollback); add one here only with its row
    }

    /** M-098 / D4: the line limit for one main Java source. */
    static final int MAX_CLASS_LINES = 800;

    /**
     * M-098 ratchet: main source (reactor-relative, forward slashes) -> its exact line count when the gate was added
     * (2026-11-12). Split a class to take it off the list; never add a new entry or raise a ceiling.
     */
    static final Map<String, Integer> CLASS_SIZE_ALLOWED = new TreeMap<>();

    static {
        CLASS_SIZE_ALLOWED.put("sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonOpsAdapter.java", 1358);
        CLASS_SIZE_ALLOWED.put("sangam-registry/src/main/java/org/sangam/registry/party/PartyService.java", 1339);
        CLASS_SIZE_ALLOWED.put("sangam-engine/src/main/java/org/sangam/engine/OperatonEngineAdapter.java", 1107);
        CLASS_SIZE_ALLOWED.put("sangam-resources/src/main/java/org/sangam/resources/roster/RosterService.java", 1146);
        CLASS_SIZE_ALLOWED.put("sangam-registry/src/main/java/org/sangam/registry/custody/CustodyService.java", 1118);
        CLASS_SIZE_ALLOWED.put("sangam-resources/src/main/java/org/sangam/resources/queue/QueueService.java", 1042);
        CLASS_SIZE_ALLOWED.put("sangam-server/src/main/java/org/sangam/server/bridge/CellTenantMover.java", 983);
        CLASS_SIZE_ALLOWED.put("sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonTaskAdapter.java", 972);
        CLASS_SIZE_ALLOWED.put("sangam-case/src/main/java/org/sangam/casefile/rest/CaseRestAdapter.java", 974);
        CLASS_SIZE_ALLOWED.put("sangam-resources/src/main/java/org/sangam/resources/booking/BookingService.java", 913);
        CLASS_SIZE_ALLOWED.put("sangam-integration/src/main/java/org/sangam/integration/pipeline/PipelineRunner.java", 825);
        CLASS_SIZE_ALLOWED.put("sangam-rest-core/src/main/java/org/sangam/rest/adapter/jdbc/JdbcIdentityAdminAdapter.java", 820);
    }

    /**
     * M-097 / D11: hidden time, entropy and thread-local state, as written in Java source (calls and method references).
     * Time: {@code System.currentTimeMillis/nanoTime}, {@code <java.time type>.now}, {@code Clock.system*},
     * {@code new Date()}, {@code Date::new}, {@code Calendar.getInstance}. Entropy: {@code UUID.randomUUID},
     * {@code new Random/SplittableRandom/SecureRandom(...)}, {@code SecureRandom.getInstance*},
     * {@code ThreadLocalRandom.current}, {@code Math.random}, {@code RandomGenerator.getDefault/of}. Thread-locals:
     * {@code ThreadLocal}, {@code InheritableThreadLocal}.
     */
    static final Pattern NONDETERMINISTIC = Pattern.compile(
            "\\bSystem\\s*(?:\\.|::)\\s*(?:currentTimeMillis|nanoTime)\\b"
                    + "|\\b(?:Instant|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|OffsetTime|Year|YearMonth|MonthDay)"
                    + "\\s*(?:\\.|::)\\s*now\\b"
                    + "|\\bClock\\s*(?:\\.|::)\\s*system(?:UTC|DefaultZone)?\\b"
                    + "|\\bnew\\s+(?:java\\.util\\.)?Date\\s*\\(\\s*\\)|\\bDate\\s*::\\s*new\\b"
                    + "|\\bCalendar\\s*(?:\\.|::)\\s*getInstance\\b"
                    + "|\\b(?:Inheritable)?ThreadLocal\\b"
                    + "|\\bUUID\\s*(?:\\.|::)\\s*randomUUID\\b"
                    + "|\\bnew\\s+(?:java\\.util\\.)?(?:Random|SplittableRandom)\\s*\\(|\\bnew\\s+(?:java\\.security\\.)?SecureRandom\\s*\\("
                    + "|\\bSecureRandom\\s*(?:\\.|::)\\s*getInstance\\w*\\b"
                    + "|\\bThreadLocalRandom\\s*(?:\\.|::)\\s*current\\b"
                    + "|\\bMath\\s*(?:\\.|::)\\s*random\\b"
                    + "|\\bRandomGenerator\\s*(?:\\.|::)\\s*(?:getDefault|of)\\b");

    /**
     * M-097: the production defaults of the D11 providers, the only sangam-core main sources that may read the system
     * clock or system entropy (reactor-relative path -> exact number of matching lines, 2026-11-12). Never add one: a
     * new source of time or ids is an implementation of {@code SangamClock} or {@code IdSource} outside sangam-core.
     */
    static final Map<String, Integer> DETERMINISM_PROVIDERS = new TreeMap<>();

    static {
        // SangamClock.SYSTEM = Instant::now (the engine registers OperatonClock through SangamClockHolder)
        DETERMINISM_PROVIDERS.put("sangam-core/src/main/java/org/sangam/core/engine/SangamClock.java", 1);
        // BusinessClock.system() before the server installs the tenant-profile clock: utc(Clock.systemUTC())
        DETERMINISM_PROVIDERS.put("sangam-core/src/main/java/org/sangam/core/time/BusinessClock.java", 1);
        // IdSource.SYSTEM: UUID.randomUUID, new SecureRandom (UUIDv7 tail), System.currentTimeMillis (UUIDv7 time),
        // ThreadLocalRandom.current (relay jitter)
        DETERMINISM_PROVIDERS.put("sangam-core/src/main/java/org/sangam/core/layer/IdSource.java", 4);
    }

    /**
     * M-097 ratchet: remaining hidden state in sangam-core main code (reactor-relative path -> exact number of matching
     * lines, 2026-11-12). The target is an empty list; each entry names the lane that owns the fix. Remove or lower an
     * entry when its line goes; never add one or raise a count.
     */
    static final Map<String, Integer> DETERMINISM_ALLOWED = new TreeMap<>();

    static {
        // JobIdentityBinder.CURRENT removed 2026-11-12 (W3-F C3-05): the job identity is passed explicitly and kept on
        // the job's engine command (sangam-engine JobIdentitySupport / CommandState).
        // LockScope.DEPTH removed 2026-11-17 (SL-172, owner decision 396(3)): the L2 lock-held marker moved unchanged to
        // sangam-engine (org.sangam.engine.tx.LockScope), next to CommandState, its only writer. The list is empty.
    }

    static Path root() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    /** Every regular file under {@code root} outside build output, {@code node_modules} and {@code .git}. */
    static List<Path> files(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return dir.equals(root) || !(name.startsWith("target") || SKIP_DIRS.contains(name))
                        ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    static boolean isMain(String rel) {
        return rel.contains("/src/main/");
    }

    static boolean isTest(String rel) {
        return rel.contains("/src/test/");
    }

    static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (MalformedInputException e) {
            return null;
        } catch (IOException e) {
            throw new IllegalStateException(p.toString(), e);
        }
    }

    /** {@code path:line: text} for every line of {@code text} matching {@code pattern}. */
    static List<String> hits(String rel, String text, Pattern pattern) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) {
                out.add(rel + ":" + (i + 1) + ": " + lines[i].strip());
            }
        }
        return out;
    }

    /** Lines of a source file as {@link String#lines()} counts them (a final line terminator adds no line). */
    static int lineCount(String text) {
        return (int) text.lines().count();
    }

    /**
     * M-098 ratchet check over {@code main source -> line count}: every violation, worded with the fix it needs. A
     * missing allow-listed file counts as 0 lines, so its entry is reported as stale.
     */
    static List<String> classSizeViolations(Map<String, Integer> lineCounts, Map<String, Integer> allowed, int max) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : new TreeMap<>(lineCounts).entrySet()) {
            if (e.getValue() > max && !allowed.containsKey(e.getKey())) {
                out.add(e.getKey() + ": " + e.getValue() + " lines > " + max + " (split the class)");
            }
        }
        for (Map.Entry<String, Integer> e : new TreeMap<>(allowed).entrySet()) {
            int now = lineCounts.getOrDefault(e.getKey(), 0);
            if (now <= max) {
                out.add(e.getKey() + ": " + now + " lines, at or under " + max + " (remove its CLASS_SIZE_ALLOWED entry)");
            } else if (now > e.getValue()) {
                out.add(e.getKey() + ": grew from " + e.getValue() + " to " + now + " lines (the ceiling never rises: split the class)");
            } else if (now < e.getValue()) {
                out.add(e.getKey() + ": shrank from " + e.getValue() + " to " + now + " lines (lower its CLASS_SIZE_ALLOWED ceiling to " + now + ")");
            }
        }
        return out;
    }

    /**
     * {@code path:line: text} for every line of {@code text} that matches {@code pattern} and is not a comment line (its
     * stripped text starts with {@code *}, {@code //} or {@code /*}), as in {@code BusinessDateGuardTest}.
     */
    static List<String> codeHits(String rel, String text, Pattern pattern) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String code = lines[i].strip();
            if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                continue;
            }
            if (pattern.matcher(lines[i]).find()) {
                out.add(rel + ":" + (i + 1) + ": " + code);
            }
        }
        return out;
    }

    /**
     * M-097 ratchet check over {@code main source -> matching lines}: every violation against the providers plus the
     * allow-list, worded with the fix it needs. An entry whose file no longer matches counts as 0 and is reported.
     */
    static List<String> determinismViolations(Map<String, Integer> found, Map<String, Integer> providers,
                                              Map<String, Integer> allowed) {
        Map<String, Integer> ceilings = new TreeMap<>(allowed);
        ceilings.putAll(providers);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : new TreeMap<>(found).entrySet()) {
            Integer ceiling = ceilings.get(e.getKey());
            if (ceiling == null) {
                out.add(e.getKey() + ": " + e.getValue() + " line(s) of hidden time, entropy or thread-local state"
                        + " (inject SangamClock, BusinessClock or IdSource)");
            } else if (e.getValue() > ceiling) {
                out.add(e.getKey() + ": grew from " + ceiling + " to " + e.getValue() + " line(s) (the ceiling never rises)");
            }
        }
        for (Map.Entry<String, Integer> e : ceilings.entrySet()) {
            int now = found.getOrDefault(e.getKey(), 0);
            if (now < e.getValue()) {
                out.add(e.getKey() + ": shrank from " + e.getValue() + " to " + now + " line(s) ("
                        + (now == 0 ? "remove its entry" : "lower its entry to " + now) + ")");
            }
        }
        return out;
    }

    /** First line is the SPDX header, or a ported file keeps its Apache header and says "Modified by Sangam". */
    static boolean hasLicenceHeader(String text) {
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        int nl = body.indexOf('\n');
        String first = (nl < 0 ? body : body.substring(0, nl)).stripTrailing();
        if (first.equals(SPDX)) {
            return true;
        }
        String head = body.lines().limit(40).reduce("", (a, b) -> a + "\n" + b);
        return head.contains("Licensed under the Apache License") && head.contains("Modified by Sangam");
    }

    @Test
    void coreNeverDependsOnOperaton() throws IOException {
        Path root = root();
        List<String> found = new ArrayList<>();
        int scanned = 0;
        for (Path p : files(root)) {
            String r = rel(root, p);
            if (r.startsWith("sangam-core/src/main/") && r.endsWith(".java")) {
                scanned++;
                found.addAll(hits(r, read(p), OPERATON));
            }
        }
        assertTrue(scanned > 50, "scanned sangam-core main sources (" + scanned + ")");
        assertTrue(found.isEmpty(), "sangam-core is engine-neutral, no org.operaton: " + found);
        String pom = Files.readString(root.resolve("sangam-core/pom.xml"));
        assertFalse(pom.contains("org.operaton"), "sangam-core/pom.xml declares no Operaton dependency");
    }

    @Test
    void noGrpcInProductSources() throws IOException {
        Path root = root();
        List<String> found = new ArrayList<>();
        int scanned = 0;
        for (Path p : files(root)) {
            String r = rel(root, p);
            if (!isMain(r)) {
                continue;
            }
            if (r.endsWith(".proto")) {
                found.add(r + ": .proto file");
            } else if (r.endsWith(".java")) {
                scanned++;
                found.addAll(hits(r, read(p), GRPC));
            }
        }
        assertTrue(scanned > 500, "scanned the main Java sources (" + scanned + ")");
        assertTrue(found.isEmpty(), "ADR-D-5: no gRPC until a measured need and a decision: " + found);
    }

    @Test
    void noDisabledTests() throws IOException {
        Path root = root();
        Map<String, Integer> counts = new TreeMap<>();
        List<String> found = new ArrayList<>();
        int scanned = 0;
        for (Path p : files(root)) {
            String r = rel(root, p);
            if (!isTest(r) || !r.endsWith(".java") || r.endsWith("/ArchitectureGuardTest.java")) {
                continue;
            }
            scanned++;
            List<String> h = hits(r, read(p), DISABLED);
            if (!h.isEmpty()) {
                counts.put(r, h.size());
                if (!DISABLED_ALLOWED.containsKey(r)) {
                    found.addAll(h);
                }
            }
        }
        assertTrue(scanned > 200, "scanned the test sources (" + scanned + ")");
        assertTrue(found.isEmpty(), "disabled tests (fix the cause or record it on DISABLED_ALLOWED): " + found);
        for (Map.Entry<String, Integer> e : DISABLED_ALLOWED.entrySet()) {
            assertEquals(e.getValue(), counts.getOrDefault(e.getKey(), 0),
                    "allow-list entry is stale or changed (remove it when the test is re-enabled): " + e.getKey());
        }
    }

    @Test
    void everyJavaSourceCarriesTheSpdxHeader() throws IOException {
        Path root = root();
        List<String> missing = new ArrayList<>();
        int scanned = 0;
        for (Path p : files(root)) {
            String r = rel(root, p);
            if (!r.endsWith(".java")) {
                continue;
            }
            String text = read(p);
            if (text == null) {
                continue;
            }
            scanned++;
            if (!hasLicenceHeader(text)) {
                missing.add(r);
            }
        }
        assertTrue(scanned > 1000, "scanned the Java sources (" + scanned + ")");
        assertTrue(missing.isEmpty(), "Java sources without the SPDX header (ARCHITECTURE section 5): " + missing);
    }

    @Test
    void mainClassesStayUnderTheSizeLimit() throws IOException {
        Path root = root();
        Map<String, Integer> counts = new TreeMap<>();
        for (Path p : files(root)) {
            String r = rel(root, p);
            if (!isMain(r) || !r.endsWith(".java")) {
                continue;
            }
            String text = read(p);
            if (text != null) {
                counts.put(r, lineCount(text));
            }
        }
        assertTrue(counts.size() > 500, "scanned the main Java sources (" + counts.size() + ")");
        List<String> violations = classSizeViolations(counts, CLASS_SIZE_ALLOWED, MAX_CLASS_LINES);
        assertTrue(violations.isEmpty(), "M-098 class-size gate (D4, " + MAX_CLASS_LINES + " lines): " + violations);
    }

    @Test
    void classSizeRatchetReportsEveryCase() {
        assertEquals(2, lineCount("a\nb\n"));
        assertEquals(2, lineCount("a\r\nb"));
        assertEquals(0, lineCount(""));
        Map<String, Integer> allowed = new TreeMap<>(Map.of("Big.java", 12, "Shrunk.java", 12, "Grown.java", 12,
                "Fixed.java", 12, "Gone.java", 12));
        Map<String, Integer> counts = new TreeMap<>(Map.of("Small.java", 10, "AtLimit.java", 10, "New.java", 11,
                "Big.java", 12, "Shrunk.java", 11, "Grown.java", 13, "Fixed.java", 10));
        List<String> v = classSizeViolations(counts, allowed, 10);
        assertEquals(5, v.size(), v.toString());
        assertTrue(v.contains("New.java: 11 lines > 10 (split the class)"), v.toString());
        assertTrue(v.contains("Fixed.java: 10 lines, at or under 10 (remove its CLASS_SIZE_ALLOWED entry)"), v.toString());
        assertTrue(v.contains("Gone.java: 0 lines, at or under 10 (remove its CLASS_SIZE_ALLOWED entry)"), v.toString());
        assertTrue(v.contains("Grown.java: grew from 12 to 13 lines (the ceiling never rises: split the class)"), v.toString());
        assertTrue(v.contains("Shrunk.java: shrank from 12 to 11 lines (lower its CLASS_SIZE_ALLOWED ceiling to 11)"), v.toString());
        assertTrue(classSizeViolations(Map.of("Big.java", 12), Map.of("Big.java", 12), 10).isEmpty());
        for (Map.Entry<String, Integer> e : CLASS_SIZE_ALLOWED.entrySet()) {
            assertTrue(e.getValue() > MAX_CLASS_LINES, "a ceiling at or under the limit is not an exception: " + e.getKey());
            assertTrue(isMain(e.getKey()) && e.getKey().endsWith(".java"), "entries are main Java sources: " + e.getKey());
        }
    }

    @Test
    void coreIsDeterministic() throws IOException {
        Path root = root();
        Map<String, Integer> counts = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        int scanned = 0;
        for (Path p : files(root)) {
            String r = rel(root, p);
            if (!r.startsWith("sangam-core/src/main/") || !r.endsWith(".java")) {
                continue;
            }
            String text = read(p);
            if (text == null) {
                continue;
            }
            scanned++;
            List<String> h = codeHits(r, text, NONDETERMINISTIC);
            if (!h.isEmpty()) {
                counts.put(r, h.size());
                lines.addAll(h);
            }
        }
        assertTrue(scanned > 50, "scanned sangam-core main sources (" + scanned + ")");
        List<String> violations = determinismViolations(counts, DETERMINISM_PROVIDERS, DETERMINISM_ALLOWED);
        assertTrue(violations.isEmpty(), "M-097 / D11 determinism in sangam-core: " + violations + "; matching lines: " + lines);
    }

    @Test
    void determinismRatchetReportsEveryCase() {
        Map<String, Integer> providers = new TreeMap<>(Map.of("Clock.java", 1));
        Map<String, Integer> allowed = new TreeMap<>(Map.of("Debt.java", 2, "Shrunk.java", 2, "Grown.java", 1, "Gone.java", 1));
        Map<String, Integer> found = new TreeMap<>(Map.of("Clock.java", 1, "Debt.java", 2, "Shrunk.java", 1,
                "Grown.java", 2, "New.java", 1));
        List<String> v = determinismViolations(found, providers, allowed);
        assertEquals(4, v.size(), v.toString());
        assertTrue(v.contains("New.java: 1 line(s) of hidden time, entropy or thread-local state"
                + " (inject SangamClock, BusinessClock or IdSource)"), v.toString());
        assertTrue(v.contains("Grown.java: grew from 1 to 2 line(s) (the ceiling never rises)"), v.toString());
        assertTrue(v.contains("Shrunk.java: shrank from 2 to 1 line(s) (lower its entry to 1)"), v.toString());
        assertTrue(v.contains("Gone.java: shrank from 1 to 0 line(s) (remove its entry)"), v.toString());
        assertTrue(determinismViolations(Map.of("Clock.java", 2), providers, Map.of()).contains(
                "Clock.java: grew from 1 to 2 line(s) (the ceiling never rises)"), "a provider is ratcheted too");
        assertTrue(determinismViolations(Map.of(), Map.of(), Map.of()).isEmpty());
        for (String path : DETERMINISM_PROVIDERS.keySet()) {
            assertFalse(DETERMINISM_ALLOWED.containsKey(path), "a provider is not also debt: " + path);
        }
        for (Map<String, Integer> m : List.of(DETERMINISM_PROVIDERS, DETERMINISM_ALLOWED)) {
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                assertTrue(e.getValue() > 0, "an entry at 0 is removed, not kept: " + e.getKey());
                assertTrue(e.getKey().startsWith("sangam-core/src/main/java/") && e.getKey().endsWith(".java"),
                        "entries are sangam-core main Java sources: " + e.getKey());
            }
        }
        assertEquals(3, DETERMINISM_PROVIDERS.size(), "the D11 providers are SangamClock, BusinessClock and IdSource");
        assertTrue(DETERMINISM_ALLOWED.isEmpty(),
                "M-097 debt only shrinks (3 entries on 2026-11-12, 1 after W3-F C3-05, 0 after SL-172 moved LockScope)");
        assertFalse(java.nio.file.Files.exists(root().resolve("sangam-core/src/main/java/org/sangam/core/engine/LockScope.java")),
                "SL-172: the lock-held marker lives in sangam-engine, never again in sangam-core");
        assertFalse(DETERMINISM_ALLOWED.containsKey("sangam-core/src/main/java/org/sangam/core/engine/JobIdentityBinder.java"),
                "C3-05: the job identity is passed explicitly, JobIdentityBinder holds no thread-local state");
    }

    @Test
    void determinismPatternMatchesWhatItBans() {
        for (String s : List.of("long t = System.currentTimeMillis();", "System.nanoTime()", "LongSupplier s = System::currentTimeMillis;",
                "Instant.now()", "java.time.Instant.now()", "SangamClock SYSTEM = Instant::now;", "LocalDateTime.now(clock)",
                "OffsetDateTime.now()", "Clock.systemUTC()", "Clock.systemDefaultZone()", "Clock.system(zone)",
                "new Date()", "new java.util.Date( )", "Supplier<Date> d = Date::new;", "Calendar.getInstance()",
                "new ThreadLocal<>()", "ThreadLocal.withInitial(() -> 1)", "InheritableThreadLocal<String> x;",
                "UUID.randomUUID()", "new Random(42)", "new java.util.Random()", "new SplittableRandom()",
                "new SecureRandom()", "new java.security.SecureRandom()", "SecureRandom.getInstanceStrong()",
                "ThreadLocalRandom.current().nextDouble()", "Math.random()", "RandomGenerator.getDefault()")) {
            assertTrue(NONDETERMINISTIC.matcher(s).find(), s);
        }
        for (String s : List.of("dialect.now()", "clock.now()", "SangamClockHolder.now()", "businessClock.now(tenant)",
                "Instant.ofEpochMilli(ms)", "Clock.fixed(i, ZoneOffset.UTC)", "new Date(ms)", "Date.from(instant)",
                "UUID.fromString(s)", "UUID.nameUUIDFromBytes(b)", "ids.randomUuid()", "ids.timeOrderedUuid()",
                "random.unitRandom()", "import java.util.concurrent.ThreadLocalRandom;", "import java.security.SecureRandom;",
                "SecureRandom tail;", "Hashes.uuidV7(ms, random)", "Math.max(0, x)", "long nowMillis = clock.now().toEpochMilli();")) {
            assertFalse(NONDETERMINISTIC.matcher(s).find(), s);
        }
        String text = "// Instant.now() in a comment\n * UUID.randomUUID() in javadoc\n/* new Date() */\n"
                + "    long t = System.currentTimeMillis(); // code\n";
        assertEquals(List.of("A.java:4: long t = System.currentTimeMillis(); // code"), codeHits("A.java", text, NONDETERMINISTIC));
    }

    @Test
    void patternsMatchWhatTheyBan() {
        assertTrue(OPERATON.matcher("import org.operaton.bpm.engine.ProcessEngine;").find());
        assertFalse(OPERATON.matcher("import org.sangam.core.engine.EngineAdapter;").find());
        assertTrue(GRPC.matcher("import io.grpc.ManagedChannel;").find());
        assertTrue(GRPC.matcher("com.google.protobuf.Message m;").find());
        assertFalse(GRPC.matcher("String grpcMethod = \"x\";").find());
        assertTrue(DISABLED.matcher("@Disabled(\"why\")").find());
        assertTrue(DISABLED.matcher("@Disabled").find());
        assertTrue(DISABLED.matcher("@org.junit.jupiter.api.Disabled").find());
        assertTrue(DISABLED.matcher("@Ignore").find());
        assertFalse(DISABLED.matcher("@DisabledIfSystemProperty(named = \"x\", matches = \"y\")").find());
        assertFalse(DISABLED.matcher("@DisabledOnOs(OS.WINDOWS)").find());
        assertFalse(DISABLED.matcher("@EnabledIfSystemProperty(named = \"x\", matches = \"y\")").find());
        assertFalse(DISABLED.matcher("@IgnoreForBinding").find());
        assertTrue(hasLicenceHeader(SPDX + "\npackage x;\n"));
        assertTrue(hasLicenceHeader(SPDX + "\r\npackage x;\r\n"));
        assertTrue(hasLicenceHeader("/*\n * Licensed under the Apache License, Version 2.0\n * Modified by Sangam\n */\npackage x;\n"));
        assertFalse(hasLicenceHeader("package x;\n"));
        assertFalse(hasLicenceHeader("/*\n * Licensed under the Apache License, Version 2.0\n */\npackage x;\n"));
        assertTrue(isMain("sangam-x/src/main/java/A.java"));
        assertTrue(isTest("sangam-x/src/test/java/ATest.java"));
    }
}
