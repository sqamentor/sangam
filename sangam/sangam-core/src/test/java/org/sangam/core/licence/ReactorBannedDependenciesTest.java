// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.licence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-106 guard: the reactor's maven-enforcer {@code bannedDependencies} (root pom) covers every Maven coordinate ban
 * of {@code deploy/licence/licence-policy.json} (forbidden_components and namespace_bans), plus LIC-7 (Liquibase 5)
 * and ADR-D-5 (gRPC). A ban added to the policy without the enforcer rule fails here. Run from sangam-core.
 */
class ReactorBannedDependenciesTest {

    private static final Pattern EXCLUDE = Pattern.compile("<exclude>([^<]+)</exclude>");
    private static final Pattern POLICY_PURL = Pattern.compile("\"purl\": \"pkg:maven/([^\"@]+)\"");

    private static String enforcerBlock() throws Exception {
        String pom = Files.readString(Path.of("..", "pom.xml"));
        int at = pom.indexOf("<id>sangam-banned-dependencies</id>");
        assertTrue(at > 0, "root pom has the sangam-banned-dependencies enforcer execution");
        int end = pom.indexOf("</execution>", at);
        assertTrue(pom.lastIndexOf("</pluginManagement>", at) > 0, "the execution is a build plugin, not only managed");
        return pom.substring(at, end);
    }

    private static List<String[]> excludes(String block) {
        List<String[]> out = new ArrayList<>();
        Matcher m = EXCLUDE.matcher(block);
        while (m.find()) {
            out.add(m.group(1).trim().split(":"));
        }
        return out;
    }

    /** Enforcer field match: {@code *} is a wildcard, anything else literal; a missing field matches anything. */
    static boolean fieldMatches(String[] pattern, int i, String value) {
        if (i >= pattern.length) {
            return true;
        }
        String regex = Pattern.quote(pattern[i]).replace("*", "\\E.*\\Q");
        return value.matches(regex);
    }

    /** True when an exclude bans {@code group:artifact} in a shipped (compile) scope. */
    static boolean banned(List<String[]> excludes, String group, String artifact) {
        for (String[] p : excludes) {
            boolean scopeOk = p.length < 5 || p[4].equals("*") || p[4].equals("compile");
            boolean anyVersion = p.length < 3 || p[2].equals("*");
            if (scopeOk && anyVersion && fieldMatches(p, 0, group) && fieldMatches(p, 1, artifact)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everyMavenCoordinateBanOfTheLicencePolicyIsEnforced() throws Exception {
        String policy = Files.readString(Path.of("..", "deploy", "licence", "licence-policy.json"));
        int pinned = policy.indexOf("\"pinned_exceptions\":");
        List<String[]> ex = excludes(enforcerBlock());
        Matcher m = POLICY_PURL.matcher(policy.substring(0, pinned));
        int checked = 0;
        while (m.find()) {
            String spec = m.group(1);
            String group;
            String artifact;
            int slash = spec.indexOf('/');
            if (slash < 0) {
                group = spec + "x";                 // namespace prefix, e.g. org.camunda -> org.camundax
                artifact = "any";
            } else {
                group = spec.substring(0, slash);
                artifact = slash == spec.length() - 1 ? "any" : spec.substring(slash + 1);
                if (group.endsWith(".")) {
                    group = group + "x";
                }
            }
            if (group.endsWith(".")) {
                group = group + "x";
            }
            assertTrue(banned(ex, group, artifact), "licence-policy ban pkg:maven/" + spec + " has no enforcer exclude");
            checked++;
        }
        assertTrue(checked >= 13, "checked the policy's Maven bans (" + checked + ")");
    }

    @Test
    void liquibaseFiveAndGrpcAreBannedAndTheFeelExceptionIsExact() throws Exception {
        String block = enforcerBlock();
        assertTrue(block.contains("<exclude>org.liquibase:liquibase-core:[5,)</exclude>"), "LIC-7");
        assertTrue(banned(excludes(block), "io.grpc", "grpc-netty-shaded"), "ADR-D-5");
        assertTrue(block.contains("<include>org.camunda.feel:feel-engine:[${version.feel-engine}]</include>"),
                "SG-27 exception pinned to the exact version (a bare version would match every version)");
    }

    @Test
    void testScopedDriversStayAllowed() throws Exception {
        List<String[]> ex = excludes(enforcerBlock());
        for (String[] p : ex) {
            if (p[0].startsWith("com.oracle") || p[0].startsWith("com.ibm.db2") || p[0].startsWith("com.mysql")
                    || p[0].equals("mysql")) {
                assertEquals(5, p.length, String.join(":", p) + " names a scope");
                assertFalse(p[4].equals("test") || p[4].equals("*"), String.join(":", p) + " must not ban test scope");
            }
        }
    }

    @Test
    void wildcardMatching() {
        assertTrue(fieldMatches(new String[] {"com.oracle.database.*"}, 0, "com.oracle.database.jdbc"));
        assertTrue(fieldMatches(new String[] {"org.camunda*"}, 0, "org.camunda.feel"));
        assertFalse(fieldMatches(new String[] {"org.camunda*"}, 0, "org.operaton.bpm"));
        assertTrue(fieldMatches(new String[] {"g"}, 1, "anything"));
    }
}
