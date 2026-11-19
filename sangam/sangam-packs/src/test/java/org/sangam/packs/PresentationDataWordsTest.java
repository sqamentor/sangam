// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Owner rule 2026-11-19 (presentation data, extends IFMS SD-544): no user-visible data value says demo, test, sample,
 * dummy, fake, mock, example, lorem, placeholder, xyz, abc or foo, in any case or in Hindi. Scanned: the presentation
 * data sets (deploy/presentation/data), every pack seed (seats, offices, roles, holders), every form's default
 * values, and the e-mail domain the presentation seed gives each user. Keys and the data set's documentation blocks
 * (nonReal, h2Simulator, h2Directives, comment) are technical and not shown to users.
 */
class PresentationDataWordsTest {

    static final Pattern BARRED = Pattern.compile(
            "demo|test|sample|dummy|fake|mock|example|lorem|placeholder|xyz|abc|foo"
                    + "|डेमो|टेस्ट|परीक्षण|नमूना|डमी|नकली|उदाहरण|प्लेसहोल्डर",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final Set<String> TECHNICAL = Set.of("nonReal", "h2Simulator", "h2Directives", "comment", "types");

    static final Path SANGAM = Path.of("").toAbsolutePath().getParent();
    static final Path DATA = SANGAM.resolve("deploy/presentation/data");
    static final Path PACKS = SANGAM.resolve("sangam-packs/src/main/resources/packs");
    static final ObjectMapper JSON = new ObjectMapper();

    static void values(String where, JsonNode n, boolean formDefaultsOnly, boolean inDefault, List<String> hits) {
        if (n.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (TECHNICAL.contains(e.getKey())) {
                    continue;
                }
                boolean def = inDefault || e.getKey().toLowerCase().contains("default");
                values(where + "." + e.getKey(), e.getValue(), formDefaultsOnly, def, hits);
            }
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                values(where + "[" + i + "]", n.get(i), formDefaultsOnly, inDefault, hits);
            }
        } else if (n.isTextual() && (!formDefaultsOnly || inDefault) && BARRED.matcher(n.asText()).find()) {
            hits.add(where + " = " + n.asText());
        }
    }

    static List<Path> files(Path root, String suffix) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    @Test
    void presentationDataSetsSayNoBarredWord() throws IOException {
        List<Path> sets = files(DATA, ".json");
        assertTrue(!sets.isEmpty(), "no data set under " + DATA);
        List<String> hits = new ArrayList<>();
        for (Path p : sets) {
            values(p.getFileName().toString(), JSON.readTree(p.toFile()), false, false, hits);
        }
        assertEquals(List.of(), hits);
    }

    @Test
    void packSeedsSayNoBarredWord() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : files(PACKS, "seed.json")) {
            values(PACKS.relativize(p).toString(), JSON.readTree(p.toFile()), false, false, hits);
        }
        assertEquals(List.of(), hits);
    }

    @Test
    void formDefaultsSayNoBarredWord() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : files(PACKS, ".form.json")) {
            values(PACKS.relativize(p).toString(), JSON.readTree(p.toFile()), true, false, hits);
        }
        assertEquals(List.of(), hits);
    }

    /** The presentation users' e-mail addresses are shown in the UI (JIT person, Keycloak profile). */
    @Test
    void presentationEmailDomainSaysNoBarredWord() throws IOException {
        String seed = Files.readString(SANGAM.resolve("deploy/presentation/seed_presentation.py"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"email\":\\s*f\"\\{uname\\}@([^\"]+)\"").matcher(seed);
        assertTrue(m.find(), "the presentation e-mail domain was not found in seed_presentation.py");
        assertTrue(!BARRED.matcher(m.group(1)).find(), "barred word in the e-mail domain " + m.group(1));
    }
}
