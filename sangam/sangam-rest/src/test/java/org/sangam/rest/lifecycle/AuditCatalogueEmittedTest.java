// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Every audit event name that SANGAM.md X.4.0.3 catalogues (and the AsyncAPI publishes, see
 * {@link AsyncApiConsistencyTest#x403()}) is written by the code: it appears in the main sources of at least one reactor
 * module as an exact string literal or as an enum constant. This is the check that does not share the table parser's
 * blind spot; the backtick slip of 2026-11-13 (payload values {@code PENDING} and {@code REST} read as event names)
 * passed AsyncApiConsistencyTest because both of its sides use the same rule.
 *
 * <p>Scanned: every {@code src/main/java} under the reactor root, except generated code ({@code sdks}, any
 * {@code generated} directory, {@code target*}), the example plugins ({@code examples}) and {@code node_modules}.
 * Comments are ignored, so a name only mentioned in Javadoc does not count. A name that is catalogued but not emitted
 * yet is listed in {@link #NOT_EMITTED_YET} with its reference, owner and expected emitter, so each audit gap is visible;
 * an entry whose name now appears in the code, or that is no longer catalogued, fails, so the list stays exact.
 *
 * <p>Limitation: a status value or other word that is also a literal or enum constant somewhere (for example
 * {@code PENDING}) passes this check; the table rule (names outside parentheses, guarded in AsyncApiConsistencyTest)
 * is what keeps such values out of the catalogue. Written for the test phase (not run here).
 */
class AuditCatalogueEmittedTest {

    private static final Path REACTOR = Path.of("..");

    /** A catalogued name without an emitter yet: the catalogue reference, the owner and the expected emitter. */
    record Gap(String reference, String owner, String expectedEmitter) {
    }

    /**
     * Catalogued in X.4.0.3 but written by no code yet, each with its catalogue reference and owner decision, its owner
     * lane and the expected emitter. Empty since 2026-11-13: the 17 gaps of the first scan were emitted under owner
     * decisions 289, 299, 300 to 305 and 308. A name added to the catalogue before its emitter lands goes here, for
     * example {@code Map.entry("NAME", new Gap("X.4.0.3 <row>, owner decision <n>", "<lane>", "<emitter class>"))},
     * and leaves when its owner's DONE lands the emitter.
     */
    static final Map<String, Gap> NOT_EMITTED_YET = Map.of();

    @Test
    void everyCataloguedNameIsWrittenByTheCodeOrListedAsAGap() throws IOException {
        Set<String> written = writtenNames();
        List<String> missing = new ArrayList<>();
        for (String name : AsyncApiConsistencyTest.x403()) {
            if (!written.contains(name) && !NOT_EMITTED_YET.containsKey(name)) {
                missing.add(name);
            }
        }
        assertEquals(List.of(), missing, "X.4.0.3 names that no main source writes as a string literal or an enum "
                + "constant: emit them, fix the table, or list them in NOT_EMITTED_YET with reference and owner");
    }

    @Test
    void theGapListIsExact() throws IOException {
        Set<String> written = writtenNames();
        Set<String> catalogued = new HashSet<>(AsyncApiConsistencyTest.x403());
        Set<String> nowWritten = new TreeSet<>();
        Set<String> notCatalogued = new TreeSet<>();
        for (Map.Entry<String, Gap> e : NOT_EMITTED_YET.entrySet()) {
            if (written.contains(e.getKey())) {
                nowWritten.add(e.getKey());
            }
            if (!catalogued.contains(e.getKey())) {
                notCatalogued.add(e.getKey());
            }
            assertTrue(!e.getValue().reference().isBlank() && !e.getValue().owner().isBlank()
                    && !e.getValue().expectedEmitter().isBlank(), e.getKey() + " needs reference, owner and emitter");
        }
        assertEquals(Set.of(), nowWritten, "NOT_EMITTED_YET names that the code now writes: remove them from the list");
        assertEquals(Set.of(), notCatalogued, "NOT_EMITTED_YET names that X.4.0.3 no longer catalogues");
    }

    @Test
    void theScannerSeesLiteralsAndEnumConstantsButNotComments() {
        Set<String> out = new HashSet<>();
        collect("""
                // "COMMENT_LINE" ENUM_IN_LINE_COMMENT
                /* "COMMENT_BLOCK" */
                /** {@code JAVADOC_NAME} "JAVADOC_LITERAL" */
                class A {
                    static final String E = "LITERAL_NAME";
                    String s = "escaped \\" QUOTE_INSIDE";
                    char c = '"';
                    String after = "AFTER_CHAR";
                    enum Kind implements Runnable {
                        @Deprecated FIRST_CONSTANT("x", 1) { public void run() { } },
                        SECOND_CONSTANT,
                        THIRD_CONSTANT;
                        static final int NOT_A_CONSTANT = 1;
                        public void run() { }
                    }
                    int FIELD_NAME = 2;
                }
                """, out);
        assertTrue(out.containsAll(Set.of("LITERAL_NAME", "AFTER_CHAR", "FIRST_CONSTANT", "SECOND_CONSTANT",
                "THIRD_CONSTANT")), out.toString());
        for (String no : List.of("COMMENT_LINE", "ENUM_IN_LINE_COMMENT", "COMMENT_BLOCK", "JAVADOC_NAME",
                "JAVADOC_LITERAL", "NOT_A_CONSTANT", "FIELD_NAME", "Deprecated")) {
            assertTrue(!out.contains(no), no + " must not count: " + out);
        }
    }

    // ---------------------------------------------------------------- scan

    private static Set<String> cache;

    /** String literals and enum constants of every scanned main source file. */
    static synchronized Set<String> writtenNames() throws IOException {
        if (cache != null) {
            return cache;
        }
        Set<String> out = new HashSet<>();
        int[] files = {0};
        Files.walkFileTree(REACTOR, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                Path rel = REACTOR.relativize(dir);
                boolean top = rel.getNameCount() == 1;
                if (name.startsWith("target") || name.equals("node_modules") || name.equals("generated")
                        || name.startsWith(".") && !dir.equals(REACTOR)
                        || top && (name.equals("sdks") || name.equals("examples"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String p = file.toString().replace('\\', '/');
                if (p.endsWith(".java") && p.contains("/src/main/java/")) {
                    collect(Files.readString(file, StandardCharsets.UTF_8), out);
                    files[0]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue(files[0] > 500, "scanned only " + files[0] + " main source files under " + REACTOR.toAbsolutePath());
        cache = Set.copyOf(out);
        return cache;
    }

    private static final Pattern ENUM = Pattern.compile("\\benum\\s+\\w+[^{;]*\\{");

    /** Adds the string literals and the enum constants of one Java source; comments are skipped. */
    static void collect(String src, Set<String> out) {
        StringBuilder code = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (src.startsWith("//", i)) {
                int j = src.indexOf('\n', i);
                i = j < 0 ? n : j;
            } else if (src.startsWith("/*", i)) {
                int j = src.indexOf("*/", i + 2);
                i = j < 0 ? n : j + 2;
                code.append(' ');
            } else if (src.startsWith("\"\"\"", i)) {
                int j = src.indexOf("\"\"\"", i + 3);
                j = j < 0 ? n : j;
                out.add(src.substring(i + 3, j));
                code.append("\"\"");
                i = Math.min(n, j + 3);
            } else if (c == '"') {
                StringBuilder lit = new StringBuilder();
                int j = i + 1;
                while (j < n && src.charAt(j) != '"' && src.charAt(j) != '\n') {
                    if (src.charAt(j) == '\\' && j + 1 < n) {
                        lit.append(src, j, j + 2);
                        j += 2;
                    } else {
                        lit.append(src.charAt(j++));
                    }
                }
                out.add(lit.toString());
                code.append("\"\"");
                i = j + 1;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != '\'' && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                code.append("' '");
                i = j + 1;
            } else {
                code.append(c);
                i++;
            }
        }
        enumConstants(code.toString(), out);
    }

    /** The constants of every enum body: identifiers at depth 0 before the first ';' (annotations skipped). */
    private static void enumConstants(String code, Set<String> out) {
        Matcher m = ENUM.matcher(code);
        while (m.find()) {
            int i = m.end();
            int depth = 0;
            boolean expect = true;
            while (i < code.length()) {
                char c = code.charAt(i);
                if (c == '(' || c == '{' || c == '[') {
                    depth++;
                } else if (c == ')' || c == '}' || c == ']') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (depth == 0 && c == ';') {
                    break;
                } else if (depth == 0 && c == ',') {
                    expect = true;
                } else if (depth == 0 && c == '@') {
                    int j = i + 1;
                    while (j < code.length() && (Character.isJavaIdentifierPart(code.charAt(j)) || code.charAt(j) == '.')) {
                        j++;
                    }
                    i = j;
                    continue;
                } else if (depth == 0 && expect && Character.isJavaIdentifierStart(c)) {
                    int j = i;
                    while (j < code.length() && Character.isJavaIdentifierPart(code.charAt(j))) {
                        j++;
                    }
                    out.add(code.substring(i, j));
                    expect = false;
                    i = j;
                    continue;
                }
                i++;
            }
        }
    }

    @AfterAll
    static void releaseStatics() {
        // R9-statics: a test class stays loaded for the whole run; its statics must not pin what they held
        cache = null;
    }
}
