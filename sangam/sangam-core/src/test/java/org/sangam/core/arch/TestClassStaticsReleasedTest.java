// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * R9-statics, the reactor-wide form of R8-heap2's packs guard. JUnit keeps every test class loaded until the forked JVM
 * ends, so a static field of a test class lives as long as the whole module's test run. A class that keeps its engine,
 * an engine fixture, an adapter over one, a Spring context, a container or a parsed contract in a static field and only
 * closes it still pins all of it: a closed Operaton engine is ~50 MB (MyBatis configuration, BPMN/DMN DOMs, caches).
 * In sangam-packs ~50 such classes ran the fork out of heap (AWS dce3e6ba, fixed by c01bb60d).
 *
 * <p>Rule, for every {@code src/test/java} source of every module under the reactor root: a non-final static field of a
 * reference type must be set to {@code null} somewhere in its file (in practice in {@code @AfterAll}/{@code @AfterContainer}
 * after closing it). The rule is by default, not by type list, so a new engine-holding type cannot slip past. Exempt are
 * only small immutable values ({@link #VALUE_TYPES}) and the JVM-wide shared fixtures of {@link #SHARED_FIXTURES}, which
 * exist exactly once per fork by design.
 */
class TestClassStaticsReleasedTest {

    /** Small immutable values: holding one for the run costs bytes, and none can reach an engine. */
    static final Set<String> VALUE_TYPES = Set.of("String", "Path", "Pattern", "KeyPair", "PrivateKey",
            "X509Certificate", "RSAKey", "ECKey",
            // sangam-esign PadesLtvTest: record Party(KeyPair keys, X509Certificate cert)
            "Party");

    /** One instance per test JVM, shared by many test classes on purpose (lazy singletons, not test classes). */
    static final Map<String, String> SHARED_FIXTURES = Map.of(
            "sangam-server/src/test/java/org/sangam/server/acceptance/AcceptanceDatabase.java#container",
            "the one database container of the acceptance lane, shared by every acceptance IT",
            "sangam-server/src/test/java/org/sangam/server/acceptance/AcceptanceIdp.java#instance",
            "the one in-process IdP of the acceptance lane",
            "sangam-server/src/test/java/org/sangam/server/acceptance/contracts/IsolatedProductTables.java#instance",
            "the one isolated product-table namespace of the contract ITs");

    static final Pattern FIELD = Pattern.compile(
            "^\\s*(?:(?:private|protected|public|volatile|transient) )*static (?:(?:volatile|transient) )*"
                    + "(?!final )([A-Z][\\w.]*(?:<.*?>)?(?:\\[\\])*) (\\w+)\\s*;",
            Pattern.MULTILINE);

    static Path root() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    /** Every unreleased static of one test source as {@code rel: static Type name}. */
    static List<String> unreleased(String rel, String src) {
        List<String> out = new ArrayList<>();
        Matcher m = FIELD.matcher(src);
        while (m.find()) {
            String type = m.group(1).replaceAll("<.*", "");
            String name = m.group(2);
            if (VALUE_TYPES.contains(type.substring(type.lastIndexOf('.') + 1))
                    || SHARED_FIXTURES.containsKey(rel + "#" + name)) {
                continue;
            }
            if (!Pattern.compile("\\b" + name + "\\s*=\\s*null\\s*;").matcher(src).find()) {
                out.add(rel + ": static " + m.group(1) + " " + name);
            }
        }
        return out;
    }

    @Test
    void everyStaticFixtureOfATestClassIsReleased() throws IOException {
        Path root = root();
        List<String> missing = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return SKIPPED_DIRS.contains(n) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String s = file.toString().replace('\\', '/');
                if (s.endsWith(".java") && s.contains("/src/test/java/")) {
                    sources.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path p : sources) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (rel.endsWith("/core/arch/TestClassStaticsReleasedTest.java")) {
                continue; // its own rule fixture below is a text block of offending fields, not fields
            }
            missing.addAll(unreleased(rel, Files.readString(p, StandardCharsets.UTF_8)));
        }
        int scanned = sources.size();
        assertTrue(scanned > 1000,"the reactor scan found only " + scanned + " test sources under " + root);
        assertEquals(List.of(), missing,
                "set these to null when the class is done (a test class stays loaded for the whole run)");
    }

    @Test
    void theRuleCatchesAClosedEngineAndAnyNewTypeAndSkipsValuesAndFinals() {
        String src = """
                class T {
                    static ProcessEngine engine;
                    private static SomeNewFixture fx;
                    static Map<String, Object> doc;
                    static final ProcessEngine KEPT = null;
                    static String url;
                    static KeyPair keys;
                    static ProcessEngine released;
                    static void after() { released = null; }
                }
                """;
        assertEquals(List.of("m/T.java: static ProcessEngine engine", "m/T.java: static SomeNewFixture fx",
                "m/T.java: static Map<String, Object> doc"), unreleased("m/T.java", src));
    }

    @Test
    void sharedFixturesStillExist() throws IOException {
        for (String key : SHARED_FIXTURES.keySet()) {
            String[] f = key.split("#");
            String src = Files.readString(root().resolve(f[0]), StandardCharsets.UTF_8);
            assertTrue(Pattern.compile("static \\S+ " + f[1] + "\\s*;").matcher(src).find(), "stale exemption " + key);
        }
    }

    /** Build output, dependencies and VCS data: never test sources of this reactor. */
    static final Set<String> SKIPPED_DIRS = Set.of("target", "node_modules", ".git", "dist", "build");
}
