// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * R8-heap2 guard. JUnit keeps every test class loaded until the run ends, so a static field of a test class lives as long
 * as the forked JVM. A class that keeps its engine (or a {@link PackDriver}, which holds one) in a static field and only
 * closes it still pins the whole closed engine: MyBatis configuration, parsed BPMN/DMN DOMs, deployment caches, ~50 MB.
 * With ~50 such classes the sangam-packs fork ran out of heap at the AWS default (build dce3e6ba), a heap dump showing
 * 17 closed ProcessEngineImpl held only by test-class statics. Every such field must be set to {@code null} when the
 * class is done (in its {@code @AfterAll} / {@code @AfterContainer}).
 */
class TestEngineStaticsReleasedTest {

    private static final Pattern FIELD = Pattern.compile(
            "^\\s*(?:private |protected |public )?static (ProcessEngine|PackDriver|ProfileGateway|ProfileTableDeployer) (\\w+);",
            Pattern.MULTILINE);

    @Test
    void everyStaticEngineFieldOfATestClassIsReleased() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = FIELD.matcher(src);
                while (m.find()) {
                    if (!Pattern.compile("\\b" + m.group(2) + "\\s*=\\s*null\\s*;").matcher(src).find()) {
                        missing.add(p.getFileName() + ": static " + m.group(1) + " " + m.group(2));
                    }
                }
            }
        }
        assertEquals(List.of(), missing, "set these to null when the class is done (they pin a closed engine)");
    }
}
