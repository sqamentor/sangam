// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.licence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * SG-27 guard (OneIFMS SD-301 owner exception): the Operaton DMN FEEL engine closure is pinned to the same exact
 * versions in the build (root pom), in the consumer BOM (sangam-bom) and in the licence allowlist
 * (deploy/licence/licence-policy.json pinned_exceptions). Changing one without the others fails here; a new version
 * must be re-verified (licence, NOTICE, CVEs) before all three move. Run from sangam-core.
 */
class FeelBomPinsTest {

    /** group:artifact to the root pom property that pins it. */
    private static final Map<String, String> PINS = new LinkedHashMap<>();

    static {
        PINS.put("org.camunda.feel:feel-engine", "version.feel-engine");
        PINS.put("org.scala-lang:scala-library", "version.scala-library");
        PINS.put("com.lihaoyi:fastparse_2.13", "version.fastparse");
        PINS.put("com.lihaoyi:sourcecode_2.13", "version.sourcecode");
        PINS.put("com.lihaoyi:geny_2.13", "version.geny");
        PINS.put("com.fasterxml.jackson.module:jackson-module-scala_2.13", "version.jackson");
    }

    @Test
    void feelClosureIsPinnedIdenticallyInBuildBomAndAllowlist() throws Exception {
        String root = Files.readString(Path.of("..", "pom.xml"));
        String bom = Files.readString(Path.of("..", "sangam-bom", "pom.xml"));
        String policy = Files.readString(Path.of("..", "deploy", "licence", "licence-policy.json"));
        for (Map.Entry<String, String> e : PINS.entrySet()) {
            String[] ga = e.getKey().split(":");
            String build = find(root, "<" + Pattern.quote(e.getValue()) + ">([^<]+)</", "root pom " + e.getValue());
            String b = find(bom, "<groupId>" + Pattern.quote(ga[0]) + "</groupId><artifactId>" + Pattern.quote(ga[1])
                    + "</artifactId><version>([^<]+)</version>", "sangam-bom " + e.getKey());
            String allowed = find(policy, "\"pkg:maven/" + Pattern.quote(ga[0] + "/" + ga[1]) + "@([^\"]+)\"",
                    "licence-policy pinned_exceptions " + e.getKey());
            assertEquals(build, b, "sangam-bom and the build pin the same " + e.getKey());
            assertEquals(build, allowed, "licence allowlist names exactly the pinned " + e.getKey());
        }
        assertTrue(find(root, "<version\\.operaton>([^<]+)</", "version.operaton").equals("2.2.0"),
                "the FEEL pins were verified against operaton-engine-dmn 2.2.0; an Operaton upgrade re-verifies them");
    }

    @Test
    void camundaNamespaceIsBannedWithoutWildcardExceptions() throws Exception {
        String policy = Files.readString(Path.of("..", "deploy", "licence", "licence-policy.json"));
        assertTrue(policy.contains("\"purl\": \"pkg:maven/org.camunda\""), "SD-301 namespace ban present");
        Matcher m = Pattern.compile("\"purl\": \"(pkg:maven/org\\.camunda[^\"]*)\", \"license\"").matcher(policy);
        int n = 0;
        while (m.find()) {
            n++;
            assertTrue(m.group(1).matches("pkg:maven/org\\.camunda\\.feel/feel-engine@\\d+\\.\\d+\\.\\d+"),
                    "only the exact feel-engine coordinate is excepted: " + m.group(1));
        }
        assertEquals(1, n, "exactly one org.camunda exception");
    }

    private static String find(String text, String regex, String what) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertTrue(m.find(), what + " is pinned");
        return m.group(1).trim();
    }
}
