// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.config.common.EffectiveDated;
import org.sangam.config.common.Tree;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads profile documents and other configuration data from YAML or JSON. YAML is read with SnakeYAML's
 * {@link SafeConstructor} (plain maps, lists and scalars only; no type tags), with an alias limit, so untrusted
 * profile files cannot instantiate classes or expand billion-laughs payloads. Dates become ISO strings.
 */
public final class ProfileLoader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProfileLoader() {
    }

    /** Parses YAML or JSON text into a data tree. */
    public static Map<String, Object> parseTree(String text) {
        String t = text.stripLeading();
        Object v;
        if (t.startsWith("{")) {
            try {
                v = JSON.readValue(t, Map.class);
            } catch (IOException e) {
                throw new IllegalArgumentException("invalid JSON: " + e.getMessage(), e);
            }
        } else {
            LoaderOptions o = new LoaderOptions();
            o.setMaxAliasesForCollections(50);
            o.setAllowDuplicateKeys(false);
            o.setCodePointLimit(16 * 1024 * 1024);
            v = new Yaml(new SafeConstructor(o)).load(text);
        }
        return Tree.map(normalise(v));
    }

    /** Parses a list-valued YAML / JSON document (seed files). */
    public static List<Object> parseList(String text) {
        LoaderOptions o = new LoaderOptions();
        o.setMaxAliasesForCollections(50);
        Object v = new Yaml(new SafeConstructor(o)).load(text);
        Object n = normalise(v);
        return n instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }

    @SuppressWarnings("unchecked")
    static Object normalise(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), normalise(val)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(normalise(o));
            }
            return out;
        }
        if (v instanceof Date d) {
            return d.toInstant().atOffset(ZoneOffset.UTC).toLocalDate().toString();
        }
        if (v instanceof Double || v instanceof Float || v instanceof java.math.BigDecimal) {
            // R33: decimals are carried as strings (exact, and canonical-JSON safe); Tree.decimal parses them
            return new java.math.BigDecimal(v.toString()).toPlainString();
        }
        return v;
    }

    /** Builds a document from a parsed tree in file shape. */
    public static ProfileDocument fromTree(Map<String, Object> tree) {
        Map<String, Object> h = Tree.map(tree.get("profile"));
        Map<String, String> name = new LinkedHashMap<>();
        Tree.map(h.get("name")).forEach((k, v) -> name.put(k, String.valueOf(v)));
        Map<String, String> ph = new LinkedHashMap<>();
        for (Map<String, Object> p : Tree.maps(tree.get("placeholders"))) {
            ph.put(Tree.str(p.get("path"), ""), Tree.str(p.get("reason"), "unverified"));
        }
        Map<String, Object> data = new LinkedHashMap<>(tree);
        data.remove("profile");
        data.remove("placeholders");
        data.remove("locked");
        return new ProfileDocument(Tree.str(h.get("id"), null), Tree.str(h.get("layer"), null),
                Tree.str(h.get("version"), "0"), EffectiveDated.date(h.get("effectiveFrom")),
                EffectiveDated.date(h.get("effectiveTo")), Tree.strings(h.get("extends")), Tree.maps(h.get("adopt")),
                name, Tree.str(h.get("source"), null), ph, Tree.strings(tree.get("locked")), data);
    }

    public static ProfileDocument parse(String text) {
        return fromTree(parseTree(text));
    }

    public static ProfileDocument load(InputStream in) {
        try (in) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ProfileDocument load(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every {@code *.yaml}, {@code *.yml} and {@code *.json} profile under a directory (recursively, sorted). */
    public static List<ProfileDocument> loadDirectory(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            List<Path> files = s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return (n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".json")) && !n.startsWith("_");
                    })
                    .sorted().toList();
            List<ProfileDocument> out = new ArrayList<>();
            for (Path f : files) {
                Map<String, Object> tree = parseTree(Files.readString(f, StandardCharsets.UTF_8));
                if (tree.containsKey("profile")) {
                    out.add(fromTree(tree));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Loads the documents named in a classpath index ({@code one resource path per line, # comments}), e.g.
     * {@code profiles/profiles.idx}. Works from a directory or a jar.
     */
    public static List<ProfileDocument> loadIndex(ClassLoader cl, String indexResource) {
        String base = indexResource.contains("/") ? indexResource.substring(0, indexResource.lastIndexOf('/') + 1) : "";
        List<ProfileDocument> out = new ArrayList<>();
        for (String line : readResource(cl, indexResource).split("\\R")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            Map<String, Object> tree = parseTree(readResource(cl, base + l));
            if (tree.containsKey("profile")) {
                out.add(fromTree(tree));
            }
        }
        return out;
    }

    public static String readResource(ClassLoader cl, String path) {
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
