// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-105 (ADR-D-16): every way Sangam reaches into Operaton's internals is recorded in {@code PATCHES.md}. The ledger's
 * allow-lists are the source of truth; this test compares them with the main sources of every reactor module (run from
 * sangam-engine, so the reactor root is the parent folder; build output, {@code node_modules} and {@code .git} skipped):
 * <ul>
 *   <li>every main source that refers to {@code org.operaton.bpm.engine.impl.} (import or qualified name) is listed in
 *       allow-list A (sangam-engine) or B (other modules), and every listed file still does;</li>
 *   <li>every main source that uses Operaton's {@code customSessionFactories} extension point is listed in allow-list C;</li>
 *   <li>every main source that implements or installs a {@code FailedJobCommandFactory} is listed in allow-list D;</li>
 *   <li>exactly one main source replaces Operaton's {@code TaskManager} (B-26: one Sangam TaskManager).</li>
 * </ul>
 * A new use fails until it is recorded (with its B-n / I-n entry); a recorded file that no longer matches fails, so the
 * ledger cannot go stale.
 */
class EnginePatchLedgerGuardTest {

    static final Pattern IMPL = Pattern.compile("\\borg\\.operaton\\.bpm\\.engine\\.impl\\.");
    static final Pattern SESSION_FACTORIES = Pattern.compile("(?i)\\bcustomSessionFactories\\b|CustomSessionFactories\\(");
    static final Pattern FAILED_JOB_FACTORY = Pattern.compile(
            "implements\\s+(?:[\\w.]+\\.)?FailedJobCommandFactory\\b|setFailedJobCommandFactory\\s*\\(");
    static final Pattern TASK_MANAGER = Pattern.compile(
            "extends\\s+(?:org\\.operaton\\.bpm\\.engine\\.impl\\.persistence\\.entity\\.)?TaskManager\\b");
    static final Pattern ROW = Pattern.compile("^\\|\\s*`([^`]+)`\\s*\\|");

    static final String A = "### Allow-list A:";
    static final String B = "### Allow-list B:";
    static final String C = "### Allow-list C:";
    static final String D = "### Allow-list D:";

    static Path root() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    /** Reactor-relative paths (forward slashes) of the main Java sources. */
    static List<Path> mainSources(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return dir.equals(root) || !(name.startsWith("target") || name.equals("node_modules") || name.equals(".git"))
                        ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String r = rel(root, file);
                if (attrs.isRegularFile() && r.endsWith(".java") && r.contains("/src/main/")) {
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

    /** The backticked first-column paths of the table rows under {@code heading}, up to the next heading. */
    static Set<String> allowList(String ledger, String heading) {
        Set<String> out = new TreeSet<>();
        boolean in = false;
        for (String line : ledger.split("\r?\n")) {
            if (line.startsWith("#")) {
                in = line.startsWith(heading);
                continue;
            }
            if (in) {
                Matcher m = ROW.matcher(line);
                if (m.find() && !m.group(1).equals("impl")) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }

    @Test
    void everyEngineInternalUseIsOnThePatchLedger() throws IOException {
        Path root = root();
        String ledger = Files.readString(root.resolve("sangam-engine/PATCHES.md"));
        Set<String> impl = new TreeSet<>();
        Set<String> sessions = new TreeSet<>();
        Set<String> failedJob = new TreeSet<>();
        Set<String> taskManagers = new TreeSet<>();
        int scanned = 0;
        for (Path p : mainSources(root)) {
            scanned++;
            String r = rel(root, p);
            String text = Files.readString(p);
            if (IMPL.matcher(text).find()) {
                impl.add(r);
            }
            if (SESSION_FACTORIES.matcher(text).find()) {
                sessions.add(r);
            }
            if (FAILED_JOB_FACTORY.matcher(text).find()) {
                failedJob.add(r);
            }
            Matcher tm = TASK_MANAGER.matcher(text);
            while (tm.find()) {
                taskManagers.add(r + "@" + tm.start());
            }
        }
        assertTrue(scanned > 500, "scanned the main Java sources (" + scanned + ")");

        Set<String> listedImpl = new TreeSet<>(allowList(ledger, A));
        listedImpl.addAll(allowList(ledger, B));
        assertFalse(listedImpl.isEmpty(), "PATCHES.md allow-lists A/B found");
        assertSame("org.operaton.bpm.engine.impl uses (PATCHES.md allow-lists A/B)", listedImpl, impl);
        assertSame("customSessionFactories uses (PATCHES.md allow-list C)", allowList(ledger, C), sessions);
        assertSame("FailedJobCommandFactory wrappers (PATCHES.md allow-list D)", allowList(ledger, D), failedJob);
        assertEquals(1, taskManagers.size(), "B-26: exactly one Sangam TaskManager replaces Operaton's: " + taskManagers);
    }

    private static void assertSame(String what, Set<String> listed, Set<String> found) {
        Set<String> unlisted = new TreeSet<>(found);
        unlisted.removeAll(listed);
        Set<String> stale = new TreeSet<>(listed);
        stale.removeAll(found);
        assertTrue(unlisted.isEmpty(), what + ": not recorded in PATCHES.md (add a B-n/I-n entry and the file): " + unlisted);
        assertTrue(stale.isEmpty(), what + ": listed in PATCHES.md but no longer matching (remove it): " + stale);
    }

    @Test
    void patternsAndLedgerParsing() {
        assertTrue(IMPL.matcher("import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;").find());
        assertTrue(IMPL.matcher("List<org.operaton.bpm.engine.impl.interceptor.SessionFactory> s;").find());
        assertFalse(IMPL.matcher("import org.operaton.bpm.engine.ProcessEngine;").find());
        assertTrue(SESSION_FACTORIES.matcher("cfg.setCustomSessionFactories(sessions);").find());
        assertTrue(SESSION_FACTORIES.matcher("cfg.getCustomSessionFactories()").find());
        assertTrue(FAILED_JOB_FACTORY.matcher("public final class X implements FailedJobCommandFactory {").find());
        assertTrue(FAILED_JOB_FACTORY.matcher("cfg.setFailedJobCommandFactory(new X(cfg.getFailedJobCommandFactory()));").find());
        assertFalse(FAILED_JOB_FACTORY.matcher("FailedJobCommandFactory delegate;").find());
        assertTrue(TASK_MANAGER.matcher("static final class Tasks extends TaskManager {").find());
        assertTrue(TASK_MANAGER.matcher("class T extends org.operaton.bpm.engine.impl.persistence.entity.TaskManager {").find());
        assertFalse(TASK_MANAGER.matcher("class T extends TaskManagerSupport {").find());
        String ledger = """
                ## Allow-lists
                ### Allow-list A: x
                | File | `impl` types used |
                |---|---|
                | `a/src/main/java/A.java` | `cfg.X` |
                ### Allow-list C: y
                | `c/src/main/java/C.java` | B-26 |
                ## Next
                | `z/src/main/java/Z.java` | not in a list |
                """;
        assertEquals(Set.of("a/src/main/java/A.java"), allowList(ledger, A));
        assertEquals(Set.of("c/src/main/java/C.java"), allowList(ledger, C));
        assertEquals(Set.of(), allowList(ledger, D));
    }
}
