// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Owner decisions 140 and 217: Bouncy Castle is used only by the optional module {@code sangam-esign} (and by the
 * optional health connectors module, decisions 173 and 187). No other module imports it or declares it in its POM, so
 * the server class path has no Bouncy Castle unless sangam-esign is added; without it unrelated features work and
 * verified eSign and DSC stay blocked. The verification interfaces here never name the implementation.
 */
class BouncyCastleConfinementTest {

    /** Modules allowed to use Bouncy Castle. */
    private static final Set<String> ALLOWED = Set.of("sangam-esign", "sangam-connectors-health");

    private static List<Path> modules() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        try (Stream<Path> s = Files.list(root)) {
            List<Path> modules = s.filter(p -> p.getFileName().toString().startsWith("sangam-") && Files.isDirectory(p))
                    .toList();
            assertTrue(modules.size() > 3, "run from a module directory of the sangam build: " + root);
            return modules;
        }
    }

    @Test
    void bouncyCastleIsImportedOnlyByTheOptionalSigningModule() throws IOException {
        List<String> offenders = modules().stream()
                .filter(m -> !ALLOWED.contains(m.getFileName().toString()))
                .map(m -> m.resolve("src/main/java"))
                .filter(Files::isDirectory)
                .flatMap(BouncyCastleConfinementTest::javaFiles)
                .filter(p -> read(p).contains("org.bouncycastle"))
                .map(p -> p.toString().replace('\\', '/'))
                .toList();
        assertEquals(List.of(), offenders);
    }

    @Test
    void noOtherModuleDeclaresBouncyCastle() throws IOException {
        List<String> offenders = modules().stream()
                .filter(m -> !ALLOWED.contains(m.getFileName().toString()))
                .map(m -> m.resolve("pom.xml"))
                .filter(Files::isRegularFile)
                .filter(p -> read(p).contains("<groupId>org.bouncycastle</groupId>"))
                .map(p -> p.toString().replace('\\', '/'))
                .toList();
        assertEquals(List.of(), offenders);
    }

    @Test
    void theSigningModuleExistsAndTheOldPackageIsGone() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(root.resolve("sangam-esign/pom.xml")), "sangam-esign is the signing module");
        assertFalse(Files.exists(Path.of("src/main/java/org/sangam/integration/document/sign")),
                "the Bouncy Castle code moved to sangam-esign");
    }

    @Test
    void theVerificationInterfacesLoadTheImplementationOnlyThroughTheServiceLoader() throws IOException {
        Path pkg = Path.of("src/main/java/org/sangam/integration/signature");
        try (Stream<Path> s = Files.list(pkg)) {
            for (Path p : s.toList()) {
                String src = read(p);
                assertFalse(src.contains("org.sangam.esign"), p + " must not name the Bouncy Castle implementation");
                assertFalse(src.contains("org.bouncycastle"), p + " must stay free of Bouncy Castle");
            }
        }
    }

    private static Stream<Path> javaFiles(Path dir) {
        try {
            return Files.walk(dir).filter(p -> p.toString().endsWith(".java")).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
