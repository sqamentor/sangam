// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Guard: "am I inside an engine command?" is never asked by testing {@code Context.getCommandContext()} for null.
 *
 * <p>Operaton 2.2's {@code Context.getCommandContext()} throws {@code ENGINE-09025} when no command is active; it never
 * answers null (operaton-engine 2.2.0, {@code Context.getCommandContext} calls
 * {@code EnsureUtil.ensureActiveCommandContext}). A null test on its result therefore throws exactly when the answer
 * is "no": code meant to fall back to its own transaction, or to refuse with its own message, failed with the engine's
 * exception instead. The engine has the proper question, {@code Context.hasActiveCommandContext()}; every site asks it
 * first ({@code Context.hasActiveCommandContext() ? Context.getCommandContext() : null}).
 *
 * <p>There is no Sangam helper for this on purpose: the sites are in modules whose only common Sangam module is
 * sangam-core, which has no engine dependency, so the shared helper is the engine's own method. Run from sangam-core,
 * so the reactor root is the parent folder.
 */
class CommandContextNullCheckGuardTest {

    /** The result compared with null directly. */
    static final Pattern DIRECT = Pattern.compile(
            "getCommandContext\\(\\)\\s*(?:!=|==)\\s*null|null\\s*(?:!=|==)\\s*(?:[\\w.]+\\.)?getCommandContext\\(\\)");
    /** The result kept in a variable (group 1) without asking first. */
    static final Pattern ASSIGNED = Pattern.compile("\\b(\\w+)\\s*=\\s*(?:[\\w.]+\\.)?Context\\.getCommandContext\\(\\)\\s*;");
    /** How far below the assignment a null test of the variable is looked for. */
    static final int WINDOW = 8;

    /** Lines (1-based) of {@code lines} that test the command context for null. */
    static List<Integer> hits(List<String> lines) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (DIRECT.matcher(line).find()) {
                out.add(i + 1);
            }
            Matcher m = ASSIGNED.matcher(line);
            if (m.find() && !line.contains("hasActiveCommandContext")) {
                String v = Pattern.quote(m.group(1));
                Pattern test = Pattern.compile("\\b" + v + "\\s*(?:==|!=)\\s*null\\b|\\bnull\\s*(?:==|!=)\\s*" + v + "\\b");
                for (int j = i + 1; j < Math.min(lines.size(), i + 1 + WINDOW); j++) {
                    if (test.matcher(lines.get(j)).find()) {
                        out.add(j + 1);
                        break;
                    }
                }
            }
        }
        return out;
    }

    @Test
    void noMainSourceTestsTheCommandContextForNull() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<String> found = new ArrayList<>();
        int[] scanned = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return dir.equals(root) || !(n.startsWith("target") || n.equals("node_modules") || n.equals(".git"))
                        ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (rel.endsWith(".java") && rel.contains("/src/main/")) {
                    scanned[0]++;
                    List<String> lines = Files.readAllLines(file);
                    for (int line : hits(lines)) {
                        found.add(rel + ":" + line + ": " + lines.get(line - 1).strip());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue(scanned[0] > 1000, "scanned the reactor's production sources (" + scanned[0] + ")");
        assertTrue(found.isEmpty(), "Context.getCommandContext() throws outside a command and never answers null: ask "
                + "Context.hasActiveCommandContext() first: " + found);
    }

    @Test
    void theGuardCatchesTheKnownShapes() {
        assertEquals(List.of(1), hits(List.of("return Context.getCommandContext() != null;")));
        assertEquals(List.of(1), hits(List.of("if (null == org.operaton.bpm.engine.impl.context.Context.getCommandContext()) {")));
        assertEquals(List.of(2), hits(List.of("CommandContext cc = Context.getCommandContext();", "if (cc == null) {")));
        assertEquals(List.of(4), hits(List.of("CommandContext cc = Context.getCommandContext();", "JobExecutor je = x.get();",
                "ProcessEngineImpl pe = engine.get();", "if (cc == null || je == null || pe == null) {")));
        assertEquals(List.of(2), hits(List.of("cc = org.operaton.bpm.engine.impl.context.Context.getCommandContext();",
                "if (incident != null && cc != null && createdWork(cc)) {")));
    }

    @Test
    void askingFirstAndUsingTheContextInsideACommandAreAllowed() {
        assertEquals(List.of(), hits(List.of(
                "CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;",
                "if (cc == null) {")));
        assertEquals(List.of(), hits(List.of("if (Context.hasActiveCommandContext()) {",
                "    CommandContext cc = Context.getCommandContext();", "    cc.getDbEntityManager();")));
        assertEquals(List.of(), hits(List.of("CommandContext cc = Context.getCommandContext();",
                "Object other = null;", "if (other == null) {", "    use(cc);")));
    }
}
