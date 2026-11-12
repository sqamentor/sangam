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
        CLASS_SIZE_ALLOWED.put("sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonOpsAdapter.java", 1373);
        CLASS_SIZE_ALLOWED.put("sangam-registry/src/main/java/org/sangam/registry/party/PartyService.java", 1341);
        CLASS_SIZE_ALLOWED.put("sangam-engine/src/main/java/org/sangam/engine/OperatonEngineAdapter.java", 1195);
        CLASS_SIZE_ALLOWED.put("sangam-resources/src/main/java/org/sangam/resources/roster/RosterService.java", 1146);
        CLASS_SIZE_ALLOWED.put("sangam-registry/src/main/java/org/sangam/registry/custody/CustodyService.java", 1118);
        CLASS_SIZE_ALLOWED.put("sangam-resources/src/main/java/org/sangam/resources/queue/QueueService.java", 1042);
        CLASS_SIZE_ALLOWED.put("sangam-server/src/main/java/org/sangam/server/bridge/CellTenantMover.java", 989);
        CLASS_SIZE_ALLOWED.put("sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonTaskAdapter.java", 981);
        CLASS_SIZE_ALLOWED.put("sangam-case/src/main/java/org/sangam/casefile/rest/CaseRestAdapter.java", 974);
        CLASS_SIZE_ALLOWED.put("sangam-resources/src/main/java/org/sangam/resources/booking/BookingService.java", 913);
        CLASS_SIZE_ALLOWED.put("sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonProcessAdapter.java", 832);
        CLASS_SIZE_ALLOWED.put("sangam-integration/src/main/java/org/sangam/integration/pipeline/PipelineRunner.java", 825);
        CLASS_SIZE_ALLOWED.put("sangam-rest/src/main/java/org/sangam/rest/adapter/jdbc/JdbcIdentityAdminAdapter.java", 821);
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
