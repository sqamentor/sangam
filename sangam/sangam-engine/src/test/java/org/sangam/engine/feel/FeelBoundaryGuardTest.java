// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * SG-27 / OneIFMS SD-301 guard: the kept FEEL engine ({@code org.camunda.feel}) and its Scala runtime stay behind the
 * engine adapter. No production source in the reactor may import or name {@code org.camunda.*} or {@code scala.*}
 * types, except the engine adapter's FEEL package {@code org.sangam.engine.feel}. Code that needs FEEL uses
 * Operaton's API ({@code org.operaton.bpm.dmn.feel.*}) or {@link org.sangam.core.plugin.DmnFunctionProvider}.
 * Run from sangam-engine, so the reactor root is the parent folder.
 */
class FeelBoundaryGuardTest {

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?((?:org\\.camunda|scala)\\.[\\w.*]+)\\s*;");
    /** Fully-qualified use in code, e.g. {@code new org.camunda.feel.FeelEngine(...)} or {@code scala.math.BigDecimal}. */
    private static final Pattern QUALIFIED = Pattern.compile("(?<![\\w.\"/:])((?:org\\.camunda|scala)\\.[a-z]\\w*\\.[A-Za-z_]\\w*)");
    private static final String ALLOWED = "sangam-engine/src/main/java/org/sangam/engine/feel/";

    @Test
    void noFeelOrScalaTypesOutsideTheEngineAdapter() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(FeelBoundaryGuardTest::isMainSource)::iterator) {
                scanned++;
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(ALLOWED)) {
                    continue;
                }
                String code = stripComments(Files.readString(p));
                for (Pattern rx : new Pattern[] {IMPORT, QUALIFIED}) {
                    Matcher m = rx.matcher(code);
                    while (m.find()) {
                        violations.add(rel + ": " + m.group(1));
                    }
                }
            }
        }
        assertTrue(scanned > 100, "scanned the reactor's production sources (" + scanned + ")");
        assertTrue(violations.isEmpty(), "org.camunda / scala types outside " + ALLOWED + " (SG-27, SD-301): "
                + violations);
    }

    @Test
    void guardCatchesAViolation() {
        String sample = "package x;\nimport org.camunda.feel.FeelEngine;\nclass A { scala.math.BigDecimal b; "
                + "String s = \"META-INF/maven/org.camunda.feel/feel-engine\"; }";
        assertTrue(IMPORT.matcher(stripComments(sample)).find());
        assertTrue(QUALIFIED.matcher(stripComments(sample)).find());
        String harmless = "// org.camunda.feel.FeelEngine\n/* scala.math.X */ String s = \"org.camunda.feel.Api\";";
        assertTrue(!QUALIFIED.matcher(stripComments(harmless)).find(), "comments and strings are ignored");
    }

    private static boolean isMainSource(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.endsWith(".java") && s.contains("/src/main/java/") && !s.contains("/node_modules/")
                && !s.matches(".*/target[^/]*/.*");
    }

    /** Removes comments and string literals, so Javadoc and resource paths do not count. */
    static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ").replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }
}
