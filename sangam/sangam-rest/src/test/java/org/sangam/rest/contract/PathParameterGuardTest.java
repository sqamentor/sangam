// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Decision 393 (no '/' or ':' value in a path parameter): every path parameter of every operation in
 * {@code api/sangam-api.yaml}, as the pair (path template, parameter name), is either proven safe by its schema
 * ({@code type: integer}, {@code format: uuid}, {@code enum}) or has a row in {@code api/path-parameters.tsv}:
 * SAFE (code proves the value never carries '/' or ':', the note cites file and line), EXEMPT (stays in the path by
 * design, the note names why) or PENDING (not yet moved; the note names the owning batch). A new path parameter without
 * proof fails: a value that can carry '/' or ':' goes in a required query parameter on a literal route
 * ({@code by-<name>}, d393-PATTERN.md). PENDING is a ratchet: {@link #PENDING_CEILING} equals the PENDING row count and
 * only goes down; the release gate is zero. A row whose pair left the contract fails until it is removed.
 */
class PathParameterGuardTest {

    /** The PENDING row count; lowered in the same commit that moves or classifies rows. Release gate: 0. */
    static final int PENDING_CEILING = 628;

    static final Path CONTRACT = Path.of("api", "sangam-api.yaml");
    static final Path LEDGER = Path.of("api", "path-parameters.tsv");
    static final Set<String> STATUSES = Set.of("SAFE", "EXEMPT", "PENDING");

    static Map<String, Map<String, Object>> contractPairs;
    static Map<String, String[]> ledger;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        Map<String, Object> spec;
        try (InputStream in = Files.newInputStream(CONTRACT)) {
            spec = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        }
        Map<String, Object> components = (Map<String, Object>) spec.getOrDefault("components", Map.of());
        Map<String, Object> shared = (Map<String, Object>) components.getOrDefault("parameters", Map.of());
        contractPairs = new LinkedHashMap<>();
        for (var path : ((Map<String, Object>) spec.get("paths")).entrySet()) {
            Map<String, Object> ops = (Map<String, Object>) path.getValue();
            List<Object> common = (List<Object>) ops.getOrDefault("parameters", List.of());
            for (var op : ops.entrySet()) {
                if ("parameters".equals(op.getKey()) || !(op.getValue() instanceof Map<?, ?> o)) {
                    continue;
                }
                List<Object> all = new ArrayList<>(common);
                all.addAll((List<Object>) ((Map<String, Object>) o).getOrDefault("parameters", List.of()));
                for (Object raw : all) {
                    Map<String, Object> p = (Map<String, Object>) raw;
                    if (p.get("$ref") instanceof String ref) {
                        p = (Map<String, Object>) shared.get(ref.substring(ref.lastIndexOf('/') + 1));
                    }
                    if (p != null && "path".equals(p.get("in"))) {
                        contractPairs.put(path.getKey() + "\t" + p.get("name"), p);
                    }
                }
            }
        }
        ledger = new LinkedHashMap<>();
        for (String line : Files.readAllLines(LEDGER, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            assertTrue(f.length == 4 && STATUSES.contains(f[0]) && !f[3].isBlank(), "malformed ledger row: " + line);
            assertTrue(ledger.put(f[1] + "\t" + f[2], f) == null, "duplicate ledger row: " + line);
        }
    }

    @SuppressWarnings("unchecked")
    static boolean schemaProven(Map<String, Object> p) {
        Object s = p.get("schema");
        if (!(s instanceof Map<?, ?> m)) {
            return false;
        }
        Map<String, Object> schema = (Map<String, Object>) m;
        return "integer".equals(schema.get("type")) || "uuid".equals(schema.get("format")) || schema.containsKey("enum");
    }

    @Test
    void everyPathParameterIsProvenSafeExemptOrPending() {
        Set<String> unproven = new TreeSet<>();
        contractPairs.forEach((pair, p) -> {
            if (!schemaProven(p) && !ledger.containsKey(pair)) {
                unproven.add(pair.replace('\t', ' '));
            }
        });
        assertEquals(Set.of(), unproven, "decision 393: a path parameter needs proof that it never carries '/' or ':'"
                + " (a SAFE row in api/path-parameters.tsv citing the code), else it goes in a required query parameter"
                + " on a literal by-<name> route (d393-PATTERN.md)");
    }

    @Test
    void noLedgerRowOutlivesItsParameter() {
        Set<String> stale = new TreeSet<>();
        ledger.forEach((pair, f) -> {
            Map<String, Object> p = contractPairs.get(pair);
            if (p == null || schemaProven(p)) {
                stale.add(pair.replace('\t', ' '));
            }
        });
        assertEquals(Set.of(), stale, "ledger rows for path parameters the contract no longer has (or proves by schema):"
                + " remove them, and lower PENDING_CEILING for removed PENDING rows");
    }

    @Test
    void pendingOnlyGoesDown() {
        long pending = ledger.values().stream().filter(f -> "PENDING".equals(f[0])).count();
        assertEquals(PENDING_CEILING, pending, "PENDING rows must equal PENDING_CEILING: a new PENDING row is refused, and"
                + " a moved or classified row lowers the ceiling in the same commit");
    }

    @Test
    void anExemptRowIsAWildcardOrASpecFixedWebhook() {
        ledger.forEach((pair, f) -> {
            if ("EXEMPT".equals(f[0])) {
                Map<String, Object> p = contractPairs.get(pair);
                boolean wildcard = p != null && Boolean.TRUE.equals(p.get("x-sangam-path-wildcard"));
                assertTrue(wildcard || pair.contains("/hooks/"), "EXEMPT is only for a multi-segment wildcard or a"
                        + " webhook path fixed by an outside specification: " + pair.replace('\t', ' '));
            }
        });
    }
}
