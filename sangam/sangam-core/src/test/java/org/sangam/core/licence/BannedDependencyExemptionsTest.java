// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.licence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SL-106 guard, second half. {@link ReactorBannedDependenciesTest} proves that the root pom bans every Maven coordinate
 * of {@code deploy/licence/licence-policy.json}; this test proves that a module pom lifts such a ban (an
 * {@code <include>} in its {@code sangam-banned-dependencies} enforcer execution) only where the policy's
 * {@code allowed_in} data allows it:
 * <ul>
 *   <li>the include names one exact version ({@code [v]});</li>
 *   <li>the coordinate is a policy ban that has an {@code allowed_in} list (a ban without one is never lifted);</li>
 *   <li>the module is the module of an allowed distribution ({@code deploy/licence/distributions.json}), or it only
 *       compiles against the coordinate: every dependency it declares on a scoped ban is {@code optional}, so Maven
 *       never passes it on to a module that depends on this one, and one of them belongs to the same allowed
 *       distribution as the include.</li>
 * </ul>
 * The enforcer run over the real dependency graph does the rest: a banned coordinate that reaches any other module in
 * a shipped scope fails that module's build. The rule (enforcer 3.6.3; 3.7.0 held, it checks them) does not look at optional dependencies, so a
 * module that only compiles against a scoped coordinate needs no include today; the optional branch above is what
 * such a module must satisfy if it ever carries one. Run from sangam-core, so the reactor root is the parent folder.
 */
class BannedDependencyExemptionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXECUTION = "<id>sangam-banned-dependencies</id>";
    private static final Pattern INCLUDE = Pattern.compile("<include>([^<]+)</include>");
    private static final Pattern EXACT_VERSION = Pattern.compile("\\[[^\\[\\](),]+\\]");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern MANAGEMENT = Pattern.compile("(?s)<dependencyManagement>.*?</dependencyManagement>");
    private static final Pattern DEPENDENCY = Pattern.compile("(?s)<dependency>(.*?)</dependency>");
    private static final Pattern GROUP = Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern OPTIONAL = Pattern.compile("<optional>\\s*true\\s*</optional>");

    /** A Maven ban of the policy: purl prefix after {@code pkg:maven/} and its allowed distributions (may be empty). */
    record Ban(String prefix, Set<String> allowedIn) {
    }

    private static Path root() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    static List<Ban> bans(JsonNode policy) {
        List<Ban> out = new ArrayList<>();
        for (JsonNode fc : policy.path("forbidden_components")) {
            String purl = fc.path("purl").asText();
            if (!purl.startsWith("pkg:maven/")) {
                continue;
            }
            Set<String> allowed = new LinkedHashSet<>();
            fc.path("allowed_in").forEach(a -> allowed.add(a.asText()));
            out.add(new Ban(purl.substring("pkg:maven/".length()), allowed));
        }
        return out;
    }

    /** The first ban whose purl prefix covers {@code group:artifact}, as check_licences.py matches; null when none. */
    static Ban banOf(List<Ban> bans, String group, String artifact) {
        String coordinate = group + "/" + artifact;
        for (Ban b : bans) {
            if (coordinate.startsWith(b.prefix()) || (coordinate + "@").startsWith(b.prefix())) {
                return b;
            }
        }
        return null;
    }

    static Map<String, String> distributionModules(JsonNode distributions) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode d : distributions.path("distributions")) {
            if (d.hasNonNull("module")) {
                out.put(d.path("id").asText(), d.path("module").asText());
            }
        }
        return out;
    }

    /** The includes of the module's {@code sangam-banned-dependencies} execution; empty when it has none. */
    static List<String> includes(String pom) {
        List<String> out = new ArrayList<>();
        String text = COMMENT.matcher(pom).replaceAll("");
        int at = text.indexOf(EXECUTION);
        if (at < 0) {
            return out;
        }
        int end = text.indexOf("</execution>", at);
        Matcher m = INCLUDE.matcher(text.substring(at, end < 0 ? text.length() : end));
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    /** What is wrong with the enforcer exemptions of one module pom; empty when all are covered by the policy. */
    static List<String> violations(String module, String pom, List<Ban> bans, Map<String, String> distributionModules) {
        List<String> out = new ArrayList<>();
        List<String> includes = includes(pom);
        if (includes.isEmpty()) {
            return out;
        }
        // the scoped bans this module declares as dependencies, and whether each is optional
        String body = MANAGEMENT.matcher(COMMENT.matcher(pom).replaceAll("")).replaceAll("");
        Set<String> optionalScopes = new LinkedHashSet<>();
        List<String> declaredNotOptional = new ArrayList<>();
        Matcher d = DEPENDENCY.matcher(body);
        while (d.find()) {
            Matcher g = GROUP.matcher(d.group(1));
            Matcher a = ARTIFACT.matcher(d.group(1));
            if (!g.find() || !a.find()) {
                continue;
            }
            Ban ban = banOf(bans, g.group(1).trim(), a.group(1).trim());
            if (ban == null || ban.allowedIn().isEmpty()) {
                continue;
            }
            if (OPTIONAL.matcher(d.group(1)).find()) {
                optionalScopes.addAll(ban.allowedIn());
            } else {
                declaredNotOptional.add(g.group(1).trim() + ":" + a.group(1).trim());
            }
        }
        for (String include : includes) {
            String[] p = include.split(":");
            String label = module + " include " + include;
            if (p.length < 3 || !EXACT_VERSION.matcher(p[2]).matches()) {
                out.add(label + ": names no exact version [v]");
                continue;
            }
            Ban ban = banOf(bans, p[0], p[1]);
            if (ban == null || ban.allowedIn().isEmpty()) {
                out.add(label + ": the licence policy has no allowed_in for this coordinate, so no module may lift its ban");
                continue;
            }
            boolean isAllowedDistribution = ban.allowedIn().stream()
                    .anyMatch(id -> module.equals(distributionModules.get(id)));
            if (isAllowedDistribution) {
                continue;
            }
            if (!declaredNotOptional.isEmpty()) {
                out.add(label + ": the module is not the module of " + ban.allowedIn() + " and declares "
                        + declaredNotOptional + " without <optional>true</optional>, so it would pass it on");
            } else if (ban.allowedIn().stream().noneMatch(optionalScopes::contains)) {
                out.add(label + ": the module is not the module of " + ban.allowedIn()
                        + " and declares no optional dependency of that add-on");
            }
        }
        return out;
    }

    @Test
    void aModuleLiftsABanOnlyWhereThePolicyAllowsIt() throws IOException {
        Path root = root();
        List<Ban> bans = bans(JSON.readTree(root.resolve("deploy/licence/licence-policy.json").toFile()));
        Map<String, String> modules = distributionModules(
                JSON.readTree(root.resolve("deploy/licence/distributions.json").toFile()));
        assertTrue(bans.stream().anyMatch(b -> !b.allowedIn().isEmpty()), "the policy has scoped Maven bans");
        List<Path> poms;
        try (Stream<Path> s = Files.walk(root, 4)) {
            poms = s.filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !p.equals(root.resolve("pom.xml")))
                    .filter(p -> {
                        String n = root.relativize(p).toString().replace('\\', '/');
                        return !n.startsWith("target") && !n.contains("/target") && !n.contains("node_modules/");
                    })
                    .sorted().toList();
        }
        assertTrue(poms.size() > 20, "found the reactor module poms: " + poms.size());
        List<String> violations = new ArrayList<>();
        Set<String> exempting = new LinkedHashSet<>();
        for (Path pom : poms) {
            String module = root.relativize(pom.getParent()).toString().replace('\\', '/');
            String text = Files.readString(pom);
            if (!includes(text).isEmpty()) {
                exempting.add(module);
            }
            violations.addAll(violations(module, text, bans, modules));
        }
        assertEquals(List.of(), violations, "SL-106: enforcer exemptions outside the licence policy's allowed_in");
        assertTrue(exempting.contains("sangam-pdf-validator"), "the scan reads the module exemptions: " + exempting);
    }

    /** The guard itself refuses each kind of unwarranted exemption (so the empty result above means something). */
    @Test
    void theGuardRefusesExemptionsThePolicyDoesNotCover() {
        List<Ban> bans = List.of(new Ban("org.example/addon-only", Set.of("addon")), new Ban("org.example/", Set.of()),
                new Ban("org.other/lib", Set.of("other")));
        Map<String, String> modules = Map.of("addon", "sangam-addon", "other", "sangam-other");
        String exempt = "<execution>" + EXECUTION + "<includes><include>org.example:addon-only:[1.0]</include>"
                + "</includes></execution>";
        String optional = "<dependency><groupId>org.example</groupId><artifactId>addon-only</artifactId>"
                + "<optional>true</optional></dependency>";
        String plain = "<dependency><groupId>org.example</groupId><artifactId>addon-only</artifactId></dependency>";

        assertEquals(List.of(), violations("sangam-addon", plain + exempt, bans, modules), "the add-on module itself");
        assertEquals(List.of(), violations("sangam-lib", optional + exempt, bans, modules), "an optional compile-only use");
        assertEquals(List.of(), violations("sangam-lib", plain, bans, modules), "no exemption, nothing to check here");
        assertFalse(violations("sangam-lib", plain + exempt, bans, modules).isEmpty(), "a dependency that is passed on");
        assertFalse(violations("sangam-lib", exempt, bans, modules).isEmpty(), "an exemption with no optional dependency");
        assertFalse(violations("sangam-lib", "<!-- " + optional + " -->" + exempt, bans, modules).isEmpty(),
                "a dependency inside a comment does not count");
        assertFalse(violations("sangam-other", "<dependency><groupId>org.other</groupId><artifactId>lib</artifactId>"
                + "<optional>true</optional></dependency>" + exempt, bans, modules).isEmpty(), "another add-on's module");
        assertFalse(violations("sangam-addon", exempt.replace("[1.0]", "1.0"), bans, modules).isEmpty(), "a bare version");
        assertFalse(violations("sangam-addon", exempt.replace("[1.0]", "[1.0,)"), bans, modules).isEmpty(), "a range");
        assertFalse(violations("sangam-addon", exempt.replace("addon-only", "never"), bans, modules).isEmpty(),
                "a ban without allowed_in");
        assertFalse(violations("sangam-addon", exempt.replace("org.example", "org.unknown"), bans, modules).isEmpty(),
                "a coordinate the policy does not scope");
    }
}