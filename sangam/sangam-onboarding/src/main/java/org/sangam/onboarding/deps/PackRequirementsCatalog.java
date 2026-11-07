// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.deps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * The requirements of every known pack. Sources, lowest precedence first:
 * <ol>
 *   <li>the pack descriptor: {@code dependsOn} (range {@code *}) and its connectors (required);</li>
 *   <li>data rules in {@value #RULES} (every jar, plugins included): {@code {"rules": [{"match": "glob", "requires":
 *       {...}, "conflicts": {...}, "upgrades": [...]}]}}, applied in file order, {@code *} matches any pack;</li>
 *   <li>the {@code requires}, {@code conflicts} and {@code upgrades} blocks of the pack's own manifest
 *       ({@code META-INF/sangam/packs.idx} manifests).</li>
 * </ol>
 */
public final class PackRequirementsCatalog {

    public static final String RULES = "META-INF/sangam/pack-requires.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Rule(String match, PackRequirements requirements) {
        boolean matches(String id) {
            if (match.equals("*")) {
                return true;
            }
            String re = ("\\Q" + match + "\\E").replace("*", "\\E.*\\Q");
            return id.matches(re);
        }
    }

    private final List<Rule> rules = new ArrayList<>();
    private final Map<String, PackRequirements> manifests = new ConcurrentHashMap<>();

    /** Reads the rules and manifests visible to the class loaders (the platform loader plus each plugin loader). */
    public static PackRequirementsCatalog scan(List<ClassLoader> loaders) {
        PackRequirementsCatalog c = new PackRequirementsCatalog();
        Set<String> seen = new LinkedHashSet<>();
        for (ClassLoader cl : loaders) {
            try {
                Enumeration<URL> rs = cl.getResources(RULES);
                while (rs.hasMoreElements()) {
                    URL u = rs.nextElement();
                    if (seen.add(u.toString())) {
                        try (InputStream in = u.openStream()) {
                            c.addRules(JSON.readTree(in));
                        }
                    }
                }
                Enumeration<URL> idx = cl.getResources(PackRegistry.INDEX);
                while (idx.hasMoreElements()) {
                    URL u = idx.nextElement();
                    if (!seen.add(u.toString())) {
                        continue;
                    }
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(u.openStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith("#")) {
                                continue;
                            }
                            try (InputStream in = cl.getResourceAsStream(line)) {
                                if (in != null) {
                                    JsonNode m = JSON.readTree(in);
                                    String id = m.path("id").asText(null);
                                    if (id != null) {
                                        c.manifests.put(id, read(id, m));
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return c;
    }

    public static PackRequirementsCatalog scan(ClassLoader loader) {
        return scan(List.of(loader));
    }

    /** Adds a rules document ({@code {"rules": [...]}}). */
    public void addRules(JsonNode doc) {
        for (JsonNode r : doc.path("rules")) {
            String match = r.path("match").asText("*");
            rules.add(new Rule(match, read(match, r)));
        }
    }

    /** Adds the requirement blocks of a manifest (for packs registered without a manifest index). */
    public void addManifest(String packId, JsonNode manifest) {
        manifests.put(packId, read(packId, manifest));
    }

    /** The merged requirements of {@code pack}. */
    public PackRequirements of(SangamPack pack) {
        PackDescriptor d = pack.descriptor();
        Map<String, VersionRange> deps = new LinkedHashMap<>();
        d.dependsOn().forEach(x -> deps.put(x, VersionRange.ANY));
        Set<String> conns = new LinkedHashSet<>();
        d.connectors().forEach(u -> conns.add(u.id()));
        PackRequirements r = new PackRequirements(d.id(), null, null, null, deps, conns, null, null, null, null);
        for (Rule rule : rules) {
            if (rule.matches(d.id())) {
                r = r.merge(rule.requirements());
            }
        }
        return r.merge(manifests.get(d.id()));
    }

    static PackRequirements read(String id, JsonNode m) {
        JsonNode q = m.path("requires");
        Map<String, VersionRange> modules = ranges(q.path("modules"));
        Map<String, VersionRange> packs = ranges(q.path("packs"));
        Set<String> conns = strings(q.path("connectors"));
        Set<String> optional = strings(q.path("optionalConnectors"));
        List<String> keys = new ArrayList<>(strings(q.path("profileKeys")));
        Map<String, VersionRange> conflicts = ranges(m.path("conflicts"));
        List<PackRequirements.UpgradePath> ups = new ArrayList<>();
        for (JsonNode u : m.path("upgrades")) {
            ups.add(new PackRequirements.UpgradePath(VersionRange.parse(u.path("from").asText("*")),
                    u.path("migration").asText(""), u.path("breaking").asBoolean(false)));
        }
        return new PackRequirements(id, range(q.path("sangam")), range(q.path("engine")), modules, packs, conns,
                optional, keys, conflicts, ups);
    }

    private static VersionRange range(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? VersionRange.ANY : VersionRange.parse(n.asText());
    }

    private static Map<String, VersionRange> ranges(JsonNode n) {
        Map<String, VersionRange> m = new LinkedHashMap<>();
        if (n.isArray()) {
            n.forEach(x -> m.put(x.asText(), VersionRange.ANY));
        } else {
            n.fields().forEachRemaining(e -> m.put(e.getKey(), VersionRange.parse(e.getValue().asText("*"))));
        }
        return m;
    }

    private static Set<String> strings(JsonNode n) {
        Set<String> s = new LinkedHashSet<>();
        n.forEach(x -> s.add(x.asText()));
        return s;
    }
}
