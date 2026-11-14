// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M-150: the supported build needs no optional host component. No module of the default reactor declares an optional
 * host framework, and this module's own test class path carries none. Written for the test phase (not run).
 */
class DefaultReactorHasNoOptionalHostTest {

    @Test
    void noDefaultReactorModuleDeclaresAnOptionalHost() throws IOException {
        Path root = OptionalHosts.reactorRoot(Path.of(""));
        List<String> modules = OptionalHosts.defaultModules(root);
        assertTrue(modules.contains("sangam-server"), "the default reactor was read: " + modules);
        assertTrue(modules.contains("sangam-host-conformance"), "this module is in the default reactor: " + modules);
        assertEquals(List.of(), OptionalHosts.defaultReactorOffenders(root));
    }

    @Test
    void theKitsOwnClassPathCarriesNoOptionalHost() {
        assertEquals(List.of(), OptionalHosts.onClassPath(getClass().getClassLoader()));
    }

    @Test
    void profilesAndCommentsAreNotTheDefaultReactor(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>sangam-parent</artifactId>"
                + "<modules><module>a</module><!-- <module>c</module> --></modules>"
                + "<profiles><profile><modules><module>b</module></modules></profile></profiles></project>",
                StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("a"));
        Files.writeString(dir.resolve("a/pom.xml"), "<project><dependencies><dependency><groupId>"
                + OptionalHosts.OPTIONAL_HOST_GROUPS.get(0) + "</groupId></dependency></dependencies></project>",
                StandardCharsets.UTF_8);
        assertEquals(List.of("a"), OptionalHosts.defaultModules(dir));
        assertEquals(List.of("a: " + OptionalHosts.OPTIONAL_HOST_GROUPS.get(0)), OptionalHosts.defaultReactorOffenders(dir));
        Files.createDirectories(dir.resolve("a/sub"));
        assertEquals(dir.toAbsolutePath().normalize(), OptionalHosts.reactorRoot(dir.resolve("a/sub")));
    }
}
