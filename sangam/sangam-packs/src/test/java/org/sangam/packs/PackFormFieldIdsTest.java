// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * PACKFORM-FIELD-KEY: every field of every shipped pack form is named by a non-blank {@code id}, unique within its form,
 * and carries no {@code key} property. The UI (adapters.ts packForm, FormRenderer) names a field by {@code id} only, so a
 * field named {@code key} rendered without a name and its value was lost. Before the fix 13 of the 21 sangam-compliance
 * forms (DPDP breach, grievance, erasure, rectification, restriction, objection, nomination, automated-decision review)
 * named their fields {@code key}. Covers both pack roots: sangam-packs and sangam-compliance (releases.COMPLIANCE_PACKS).
 */
class PackFormFieldIdsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Surefire runs with the module directory as working directory. */
    static final List<Path> ROOTS = List.of(Path.of("src", "main", "resources", "packs"),
            Path.of("..", "sangam-compliance", "src", "main", "resources", "packs"));

    static List<Path> forms() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : ROOTS) {
            assertTrue(Files.isDirectory(root), "pack root missing: " + root.toAbsolutePath());
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(p -> p.getFileName().toString().endsWith(".form.json")).sorted().forEach(out::add);
            }
        }
        return out;
    }

    /** The problems of one form document, empty when every field has a unique non-blank id and no key. */
    static List<String> problems(JsonNode form) {
        List<String> out = new ArrayList<>();
        JsonNode fields = form.path("fields");
        if (!fields.isArray()) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode f : fields) {
            JsonNode id = f.get("id");
            if (id == null || !id.isTextual() || id.asText().isBlank()) {
                out.add("fields[" + i + "] has no id");
            } else if (!seen.add(id.asText())) {
                out.add("fields[" + i + "] duplicate id " + id.asText());
            }
            if (f.has("key")) {
                out.add("fields[" + i + "] has a key property (" + f.get("key") + "); the field name is id");
            }
            i++;
        }
        return out;
    }

    @Test
    void everyShippedFormFieldHasAUniqueIdAndNoKey() throws IOException {
        List<Path> forms = forms();
        assertTrue(forms.size() > 600, "expected every pack form of both roots, found " + forms.size());
        List<String> bad = new ArrayList<>();
        for (Path p : forms) {
            for (String problem : problems(JSON.readTree(p.toFile()))) {
                bad.add(p.normalize() + ": " + problem);
            }
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void theGuardCatchesKeyMissingIdAndDuplicates() throws IOException {
        assertEquals(List.of(), problems(JSON.readTree("{\"fields\":[{\"id\":\"a\"},{\"id\":\"b\"}]}")));
        assertEquals(2, problems(JSON.readTree("{\"fields\":[{\"key\":\"a\"}]}")).size());
        assertEquals(1, problems(JSON.readTree("{\"fields\":[{\"id\":\" \"}]}")).size());
        assertEquals(1, problems(JSON.readTree("{\"fields\":[{\"id\":\"a\"},{\"id\":\"a\"}]}")).size());
    }
}
