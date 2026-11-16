// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * S9: every operation the route deriver ({@code api/routes/_v2_spring.py}) takes from a sangam-rest controller declares
 * its success answer as {@code application/octet-stream} exactly when the controller method returns a byte stream
 * ({@code byte[]}, {@code StreamingResponseBody} or a Spring {@code org.springframework.core.io} resource), and JSON
 * otherwise. The deriver once matched any return type whose name contained "Resource", so sangam-resources' JSON
 * types (Resource, ResourceType, ResourcesPorts.Quote, ResourcesRestPort.EntryView) were declared octet-stream and a
 * generated client would not parse their answers.
 */
class DerivedRouteMediaTypeTest {

    private static final Path CURRENT = Path.of("api", "sangam-api.yaml");
    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Pattern DERIVED = Pattern.compile("^(\\w+)\\.(\\w+) \\(sangam-rest\\)\\.");
    private static final String OCTET = "application/octet-stream";

    @Test
    @SuppressWarnings("unchecked")
    void aDerivedOperationAnswersOctetStreamExactlyWhenItsControllerReturnsAByteStream() throws IOException {
        Map<String, Object> spec;
        try (InputStream in = Files.newInputStream(CURRENT)) {
            spec = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        }
        Map<String, List<Path>> byName = new HashMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(p -> p.toString().endsWith("Controller.java"))
                    .forEach(p -> byName.computeIfAbsent(p.getFileName().toString(), k -> new ArrayList<>()).add(p));
        }
        Map<Path, String> sources = new HashMap<>();
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (var path : ((Map<String, Object>) spec.get("paths")).entrySet()) {
            for (var opEntry : ((Map<String, Object>) path.getValue()).entrySet()) {
                if (!(opEntry.getValue() instanceof Map<?, ?> raw) || !raw.containsKey("responses")) {
                    continue;
                }
                Map<String, Object> op = (Map<String, Object>) raw;
                Matcher d = DERIVED.matcher(String.valueOf(op.getOrDefault("description", "")));
                if (!d.find()) {
                    continue;
                }
                String where = opEntry.getKey().toUpperCase() + " " + path.getKey();
                // two controllers may share a simple name (RegistryController): the one whose class-level mapping
                // is a prefix of this path is the source
                List<Path> candidates = byName.getOrDefault(d.group(1) + ".java", List.of()).stream()
                        .filter(c -> path.getKey().startsWith(base(sources.computeIfAbsent(c,
                                DerivedRouteMediaTypeTest::read))))
                        .toList();
                assertEquals(1, candidates.size(), where + ": controller " + d.group(1) + " found " + candidates);
                String src = sources.computeIfAbsent(candidates.get(0), DerivedRouteMediaTypeTest::read);
                Set<String> returns = new LinkedHashSet<>();
                Matcher m = Pattern.compile("public\\s+([\\w<>\\[\\], ?.]+?)\\s+" + d.group(2) + "\\s*\\(").matcher(src);
                while (m.find()) {
                    returns.add(m.group(1).trim());
                }
                assertEquals(1, returns.size(), where + ": return type of " + d.group(1) + "." + d.group(2));
                boolean stream = byteStream(returns.iterator().next(), src);
                boolean octet = false;
                for (var r : ((Map<String, Object>) op.get("responses")).entrySet()) {
                    if (r.getKey().startsWith("2") && r.getValue() instanceof Map<?, ?> resp
                            && resp.get("content") instanceof Map<?, ?> content && content.containsKey(OCTET)) {
                        octet = true;
                    }
                }
                if (stream != octet) {
                    wrong.add(where + " (" + d.group(1) + "." + d.group(2) + " returns " + returns.iterator().next()
                            + "): declared " + (octet ? OCTET : "JSON"));
                }
                checked++;
            }
        }
        assertTrue(checked > 200, "derived operations checked: " + checked);
        assertEquals(List.of(), wrong, "derived operations whose success media type does not match the controller");
    }

    /** byte[], StreamingResponseBody, or a type of org.springframework.core.io (fully named or imported). */
    static boolean byteStream(String ret, String src) {
        if (ret.contains("byte[]") || ret.contains("StreamingResponseBody")) {
            return true;
        }
        String inner = ret.substring(ret.lastIndexOf('<') + 1).replace(">", "").trim();
        if (inner.startsWith("org.springframework.core.io.")) {
            return true;
        }
        return inner.matches("\\w*Resource") && Pattern.compile(
                "import\\s+org\\.springframework\\.core\\.io\\.(?:\\*|" + inner + ")\\s*;").matcher(src).find();
    }

    /** The class-level request mapping without /api/v1; a template segment ({kind:...}) ends the literal prefix. */
    static String base(String src) {
        Matcher m = Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*|path\\s*=\\s*)?\"([^\"]*)\"")
                .matcher(src.split(" class ")[0]);
        String b = m.find() ? m.group(1).replaceFirst("^/api/v1", "") : "";
        int t = b.indexOf('{');
        return t < 0 ? b : b.substring(0, t);
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
