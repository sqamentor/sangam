// SPDX-License-Identifier: Apache-2.0
package org.sangam.pdfvalidator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Owner decisions 114 and 119 (RM-26): this module is the only reactor module that reaches veraPDF and Rhino, so no
 * library inside the server fat jar does. Run from {@code sangam-pdf-validator}; reads the reactor poms and the
 * sangam-integration service registration.
 */
class PdfValidatorReactorScopeTest {

    private static final Path ROOT = Path.of("..");
    private static final Pattern RHINO_INCLUDE = Pattern.compile("<include>org\\.mozilla:rhino:([^<]*)</include>");

    private static List<Path> modulePoms() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(ROOT, 4)) {
            s.filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !p.normalize().equals(ROOT.resolve("pom.xml").normalize()))
                    .filter(p -> {
                        String n = p.toString().replace('\\', '/');
                        return !n.contains("/target") && !n.contains("/node_modules/") && !n.contains("/sangam-pdf-validator/");
                    })
                    .forEach(out::add);
        }
        return out;
    }

    @Test
    void noOtherModuleDeclaresVeraPdfOrRhino() throws Exception {
        List<Path> poms = modulePoms();
        assertTrue(poms.size() > 20, "found the reactor module poms: " + poms.size());
        for (Path pom : poms) {
            String text = Files.readString(pom);
            assertFalse(text.contains("<groupId>org.verapdf</groupId>"), pom + " declares veraPDF (decision 114)");
            assertFalse(text.contains("<groupId>org.mozilla</groupId>"), pom + " declares Rhino (LIC-10, decision 119)");
            assertFalse(text.contains("<artifactId>sangam-pdf-validator</artifactId>"),
                    pom + " depends on the validator module, which would bring Rhino along");
            assertFalse(RHINO_INCLUDE.matcher(text).find(), pom + " exempts Rhino from the enforcer");
        }
    }

    @Test
    void theRootPomStillBansRhinoAndOnlyThisModuleIsExemptAtOneExactVersion() throws Exception {
        String root = Files.readString(ROOT.resolve("pom.xml"));
        assertTrue(root.contains("<exclude>org.mozilla:rhino</exclude>"), "the reactor-wide Rhino ban stays (SL-106)");
        assertFalse(RHINO_INCLUDE.matcher(root).find(), "the root pom exempts Rhino for every module");

        String own = Files.readString(Path.of("pom.xml"));
        Matcher m = RHINO_INCLUDE.matcher(own);
        assertTrue(m.find(), "this module carries the scoped Rhino exemption");
        assertEquals("[${version.rhino.verapdf}]", m.group(1), "an exact version range, never a bare version");
        assertFalse(m.find(), "exactly one Rhino exemption");
        assertTrue(own.contains("<version.rhino.verapdf>1.7.15.1</version.rhino.verapdf>"));
        assertTrue(own.contains("combine.children=\"append\""), "the root includes are kept, not replaced");
    }

    @Test
    void theServerSideRegistrationIsTheRemoteClientNotVeraPdf() throws Exception {
        Path services = ROOT.resolve(Path.of("sangam-integration", "src", "main", "resources", "META-INF", "services",
                "org.sangam.integration.document.pdf.PdfRenderer$Validator"));
        List<String> providers = Files.readAllLines(services).stream().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        assertEquals(List.of("org.sangam.integration.document.pdf.remote.RemotePdfValidator"), providers);
        assertFalse(Files.exists(Path.of("src", "main", "resources", "META-INF", "services",
                "org.sangam.integration.document.pdf.PdfRenderer$Validator")),
                "VeraPdfValidator is not a ServiceLoader provider: two providers on one class path are a packaging error");
    }
}
