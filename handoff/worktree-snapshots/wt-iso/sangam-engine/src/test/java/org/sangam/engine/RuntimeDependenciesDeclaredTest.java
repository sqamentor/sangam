// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Regression for SG-28: Operaton's StrongUuidGenerator (used by SangamProcessEnginePlugin) needs
 * com.fasterxml.uuid:java-uuid-generator at run time, but operaton-engine 2.2.0 declares it "provided"; Sangam got it
 * only by chance through org.camunda.feel:feel-engine, so a consumer without that path failed with
 * NoClassDefFoundError: com/fasterxml/uuid/EthernetAddress. sangam-engine must declare it itself in a scope that
 * reaches consumers, and sangam-bom must pin the same version as the build.
 */
class RuntimeDependenciesDeclaredTest {

    private static final Pattern DEP = Pattern.compile(
            "<dependency>\\s*<groupId>com\\.fasterxml\\.uuid</groupId>\\s*<artifactId>java-uuid-generator</artifactId>"
                    + "(.*?)</dependency>", Pattern.DOTALL);

    @Test
    void sangamEngineDeclaresUuidGeneratorForConsumers() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher m = DEP.matcher(pom);
        assertTrue(m.find(), "sangam-engine declares java-uuid-generator");
        String body = m.group(1);
        assertTrue(!body.contains("<scope>provided</scope>") && !body.contains("<scope>test</scope>")
                && !body.contains("<optional>true</optional>"), "scope must reach consumers: " + body);
        assertTrue(body.contains("${version.java-uuid-generator}"), "version from the root pin");
    }

    @Test
    void bomPinsTheBuildVersion() throws Exception {
        Matcher root = Pattern.compile("<version\\.java-uuid-generator>([^<]+)<")
                .matcher(Files.readString(Path.of("..", "pom.xml")));
        Matcher bom = Pattern.compile("<artifactId>java-uuid-generator</artifactId><version>([^<]+)<")
                .matcher(Files.readString(Path.of("..", "sangam-bom", "pom.xml")));
        assertTrue(root.find() && bom.find(), "both pins present");
        assertEquals(root.group(1), bom.group(1));
    }

    @Test
    void classIsOnTheRuntimeClassPath() throws Exception {
        Class.forName("com.fasterxml.uuid.EthernetAddress");
    }
}
