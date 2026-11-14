// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * M-141b / M-144 DoD: the assembly layer is plain Java. Its main sources name no host framework on a code line, and its
 * pom declares no host-framework dependency (the pom-level half of the boundary, design note section 5.2 "Next"). The
 * banned roots are those of {@code HostFrameworkBoundaryTest.HOST_FRAMEWORK_PACKAGES} (decision 81). Runs from the
 * module folder. Written for the test phase (not run).
 */
class AssemblyIsPlainJavaTest {

    static final List<String> HOST_FRAMEWORK_PACKAGES = List.of(
            "org.springframework", "io.quarkus", "io.micronaut", "io.helidon",
            "jakarta.enterprise", "javax.enterprise", "jakarta.inject", "javax.inject",
            "com.google.inject", "io.dropwizard", "jakarta.ws.rs", "javax.ws.rs", "org.glassfish.jersey");

    static final Pattern IN_CODE = Pattern.compile("\\b(?:" + String.join("|",
            HOST_FRAMEWORK_PACKAGES.stream().map(Pattern::quote).toList()) + ")\\.");

    /** A dependency's groupId that is a host framework (also the framework's own BOM groups). */
    static final Pattern IN_POM = Pattern.compile("<groupId>\\s*(?:" + String.join("|",
            HOST_FRAMEWORK_PACKAGES.stream().map(Pattern::quote).toList()) + ")(?:\\.[a-z0-9.]*)?\\s*</groupId>");

    @Test
    void mainSourcesNameNoHostFramework() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main/java"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String t = lines.get(i).trim();
                    if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                        continue;
                    }
                    if (IN_CODE.matcher(t).find()) {
                        hits.add(p + ":" + (i + 1) + ": " + t);
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "sangam-assembly is not a host:\n" + String.join("\n", hits));
    }

    @Test
    void thePomDeclaresNoHostFramework() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher m = IN_POM.matcher(pom);
        List<String> hits = new ArrayList<>();
        while (m.find()) {
            hits.add(m.group());
        }
        assertTrue(hits.isEmpty(), "sangam-assembly declares a host framework: " + hits);
    }

    @Test
    void thePatternsMatchWhatTheyBan() {
        assertTrue(IN_CODE.matcher("import org.springframework.beans.factory.ObjectProvider;").find());
        assertTrue(IN_CODE.matcher("@jakarta.inject.Inject").find());
        assertTrue(!IN_CODE.matcher("import org.sangam.springish.X;").find());
        assertTrue(IN_POM.matcher("<groupId>org.springframework.boot</groupId>").find());
        assertTrue(IN_POM.matcher("<groupId>io.quarkus</groupId>").find());
        assertTrue(!IN_POM.matcher("<groupId>io.github.sqamentor</groupId>").find());
        assertTrue(!IN_POM.matcher("<groupId>io.quarkusx</groupId>").find());
    }
}
