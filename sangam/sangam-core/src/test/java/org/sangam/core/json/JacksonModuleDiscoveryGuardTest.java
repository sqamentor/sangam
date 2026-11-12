// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.json;

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
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Build guard for SL-92: no Jackson mapper in the build discovers its modules from the class path. The server class
 * path carries jackson-module-scala (Operaton's FEEL engine), jackson-module-jakarta-xmlbind-annotations,
 * parameter-names and others; a discovering mapper silently changes behaviour with whatever happens to be deployed
 * (with the Scala module every untyped nested JSON object is read as a Scala {@code Map}). Every mapper registers the
 * modules it needs explicitly ({@code registerModule(new JavaTimeModule())} and so on).
 *
 * <p>Forbidden in any {@code src/main} or {@code src/test} Java file of the build (comments and string literals are
 * ignored): {@code findAndRegisterModules}, {@code findAndAddModules}, {@code findModules(},
 * {@code findModulesViaServiceLoader}, and a {@code ServiceLoader} of Jackson {@code Module}.
 */
class JacksonModuleDiscoveryGuardTest {

    static final Pattern DISCOVERY = Pattern.compile("\\bfindAndRegisterModules\\b|\\bfindAndAddModules\\b"
            + "|\\bfindModules\\s*\\(|\\bfindModulesViaServiceLoader\\b"
            + "|ServiceLoader\\s*\\.\\s*load\\s*\\(\\s*(?:com\\.fasterxml\\.jackson\\.databind\\.)?Module\\s*\\.\\s*class");

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("sangam-core"))) {
            p = p.getParent();
        }
        assertTrue(p != null, "repository root (with sangam-core) not found");
        return p;
    }

    static List<Path> java(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return !dir.equals(root) && (name.startsWith("target") || name.equals("node_modules")
                        || name.startsWith(".")) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String s = "/" + root.relativize(file).toString().replace('\\', '/');
                if (s.endsWith(".java") && (s.contains("/src/main/") || s.contains("/src/test/"))) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(null);
        return out;
    }

    static boolean comment(String code) {
        return code.startsWith("*") || code.startsWith("//") || code.startsWith("/*");
    }

    static String code(String line) {
        String noStrings = line.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
        int c = noStrings.indexOf("//");
        return c < 0 ? noStrings : noStrings.substring(0, c);
    }

    @Test
    void noMapperDiscoversModulesFromTheClassPath() throws IOException {
        Path root = root();
        List<Path> files = java(root);
        assertTrue(files.size() > 100, "source tree not found under " + root + " (" + files.size() + " files)");
        List<String> violations = new ArrayList<>();
        for (Path f : files) {
            List<String> src = Files.readAllLines(f);
            for (int i = 0; i < src.size(); i++) {
                String line = src.get(i).strip();
                if (!comment(line) && DISCOVERY.matcher(code(line)).find()) {
                    violations.add(root.relativize(f).toString().replace('\\', '/') + ":" + (i + 1) + ": " + line);
                }
            }
        }
        assertEquals(List.of(), violations, "Register Jackson modules explicitly (e.g. registerModule(new "
                + "JavaTimeModule()).registerModule(new Jdk8Module())); class-path discovery also registers "
                + "jackson-module-scala from the FEEL engine, which reads nested JSON objects as Scala maps (SL-92)");
    }

    @Test
    void patternRecognisesEveryDiscoveryForm() {
        for (String s : List.of("new ObjectMapper().findAndRegisterModules();", "JsonMapper.builder().findAndAddModules()",
                "m.registerModules(ObjectMapper.findModules());", "ObjectMapper.findModules (cl)",
                "builder.findModulesViaServiceLoader(true);", "ServiceLoader.load(Module.class)",
                "ServiceLoader.load( com.fasterxml.jackson.databind.Module.class, cl)")) {
            assertTrue(DISCOVERY.matcher(code(s)).find(), s);
        }
        for (String s : List.of("m.registerModule(new JavaTimeModule());", "String s = \"findAndRegisterModules\";",
                "x(); // findAndRegisterModules was removed", "ServiceLoader.load(Plugin.class)")) {
            assertEquals(false, DISCOVERY.matcher(code(s)).find(), s);
        }
    }
}
