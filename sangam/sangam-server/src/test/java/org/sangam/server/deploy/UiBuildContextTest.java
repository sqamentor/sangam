// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UI images build the UI inside Docker ({@code npm run build} type-checks every file under sangam-ui/src, tests
 * included). A UI source (other than a '?raw' import) that imports a file outside sangam-ui ({@code ../../../sangam-compliance/...}) needs that file
 * copied into the build stage at the same relative place, else tsc fails with TS2307 (ci-build run 44458833009, the
 * demo sangam-front build). Each such file must be copied by deploy/presentation/Dockerfile and deploy/docker/ui.Dockerfile
 * and let through ui.Dockerfile.dockerignore.
 */
class UiBuildContextTest {

    private static final Path SANGAM = Path.of("..");
    private static final Pattern OUTSIDE = Pattern.compile("from\\s+'((?:\\.\\./)+)(sangam-(?!ui/)[^'?]+)'");
    // '?raw' imports are typed by vite/client (declare module '*?raw') and are not resolved by tsc, so they need no copy

    private static Set<String> outsideImports() throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SANGAM.resolve("sangam-ui/src"))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().matches(".*\\.tsx?$"))::iterator) {
                Matcher m = OUTSIDE.matcher(Files.readString(p));
                while (m.find()) out.add(m.group(2));
            }
        }
        return out;
    }

    @Test
    void everyFileTheUiImportsFromOutsideIsInTheBuildStage() throws IOException {
        Set<String> paths = outsideImports();
        assertFalse(paths.isEmpty(), "expected at least the India compliance pack import");
        String presentation = Files.readString(SANGAM.resolve("deploy/presentation/Dockerfile"));
        String ui = Files.readString(SANGAM.resolve("deploy/docker/ui.Dockerfile"));
        String ignore = Files.readString(SANGAM.resolve("deploy/docker/ui.Dockerfile.dockerignore"));
        for (String p : paths) {
            String copy = "COPY " + p + " /" + p;
            assertTrue(presentation.contains(copy), "deploy/presentation/Dockerfile lacks: " + copy);
            assertTrue(ui.contains(copy), "deploy/docker/ui.Dockerfile lacks: " + copy);
            assertTrue(ignore.contains("!" + p), "ui.Dockerfile.dockerignore does not let through " + p);
        }
    }
}
