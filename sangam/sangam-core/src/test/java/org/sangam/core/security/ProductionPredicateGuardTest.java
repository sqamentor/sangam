// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-109 guard: no production source decides "production" by itself. A {@code prod}-profile string check or a direct
 * read of {@code sangam.security.production} anywhere in {@code src/main} (outside {@link ProductionMode}) fails here;
 * guards call {@code ProductionMode} (or the server's {@code ProductionEnvironment}, which delegates to it).
 * Run from sangam-core, so the reactor root is the parent folder.
 */
class ProductionPredicateGuardTest {

    static final Pattern OWN_CHECK = Pattern.compile(
            "(?i)\\.contains\\(\\s*[\"']prod[\"']"                   // profile.contains("prod")
            + "|Profiles\\.of\\([^)]*[\"']prod[\"']"                  // env.acceptsProfiles(Profiles.of("prod"))
            + "|equals(IgnoreCase)?\\(\\s*\"prod\"\\s*\\)"            // p.equals("prod")
            + "|getProperty\\(\\s*\"sangam\\.security\\.production\"" // env.getProperty("sangam.security.production"...)
            + "|\\$\\{sangam\\.security\\.production");               // SpEL / @Value placeholders

    @Test
    void noProductionDecisionOutsideTheOnePredicate() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<String> hits = new ArrayList<>();
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
                if (rel.endsWith(".java") && rel.contains("/src/main/") && !rel.endsWith("/ProductionMode.java")) {
                    scanned[0]++;
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        if (OWN_CHECK.matcher(lines.get(i)).find()) {
                            hits.add(rel + ":" + (i + 1) + ": " + lines.get(i).strip());
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue(scanned[0] > 1000, "scanned the reactor's production sources (" + scanned[0] + ")");
        assertTrue(hits.isEmpty(), "production decided outside ProductionMode (SL-109): " + hits);
    }

    @Test
    void theGuardCatchesEveryKnownShape() {
        for (String planted : List.of(
                "Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.contains(\"prod\"))",
                "p.toLowerCase(Locale.ROOT).contains(\"prod\")",
                "@ConditionalOnExpression(\"${sangam.debug.enabled:false} and !'${spring.profiles.active:}'.contains('prod')\")",
                "if (env.acceptsProfiles(Profiles.of(\"prod\"))) {",
                "profiles.stream().anyMatch(p -> p.equalsIgnoreCase(\"prod\"))",
                "env.getProperty(\"sangam.security.production\", Boolean.class, false)",
                "@Value(\"${sangam.security.production:false}\") boolean production")) {
            assertTrue(OWN_CHECK.matcher(planted).find(), planted);
        }
    }

    @Test
    void messagesAndThePredicateCallAreAllowed() {
        for (String fine : List.of(
                "throw new IllegalStateException(\"sangam.security.production=true but ...\");",
                "boolean production = ProductionEnvironment.isProduction(env); // SL-109",
                "if (ProductionMode.isProductionProfile(this.profile)) {",
                "Map.of(\"gov.bill\", \"1.0.0\"), \"prod\");")) {
            assertFalse(OWN_CHECK.matcher(fine).find(), fine);
        }
    }
}
