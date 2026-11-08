// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import org.sangam.integration.connector.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A pipeline as configuration (N11; R64, C09). JSON:
 * <pre>{@code
 * {"id":"nightly-feed","version":"3","dataset":"feed.daily","maxRejects":100,"steps":[
 *   {"id":"read","kind":"EXTRACT","type":"jdbc","config":{"datasource":"his","sql":"select * from t where d=?","params":["day"]}},
 *   {"id":"map","kind":"TRANSFORM","type":"mapping","config":{"fields":{"amount":"amt"}}},
 *   {"id":"check","kind":"TRANSFORM","type":"validate","config":{"rules":[{"field":"amount","required":true,"min":"0"}]}},
 *   {"id":"gate","kind":"GATE","type":"feel","config":{"expression":"rejected < 10"}},
 *   {"id":"load","kind":"LOAD","type":"jdbc","chunkSize":500,"config":{"datasource":"dw","table":"FACT",
 *      "columns":{"AMOUNT":"amount"},"runIdColumn":"RUN_ID","chunkColumn":"CHUNK_NO"}}]}
 * }</pre>
 * Types: EXTRACT jdbc, file, sftp, http; TRANSFORM mapping, feel, dmn, validate; GATE feel, dmn; LOAD jdbc, file, sftp,
 * http, outbox. The run stores a snapshot of the definition, so a restart uses the version it began with.
 */
public record PipelineDefinition(String id, String version, String dataset, long maxRejects, List<Step> steps) {

    public enum Kind { EXTRACT, TRANSFORM, GATE, LOAD }

    public record Step(String id, Kind kind, String type, int chunkSize, Map<String, Object> config) {
        public String str(String key) {
            Object v = config.get(key);
            return v == null ? null : v.toString();
        }

        public String require(String key) {
            String v = str(key);
            if (v == null || v.isBlank()) {
                throw new IllegalArgumentException("step " + id + ": config '" + key + "' is required");
            }
            return v;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> map(String key) {
            Object v = config.get(key);
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        @SuppressWarnings("unchecked")
        public List<Object> list(String key) {
            Object v = config.get(key);
            return v instanceof List<?> l ? (List<Object>) l : List.of();
        }
    }

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public PipelineDefinition {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid pipeline id " + id);
        }
        steps = List.copyOf(steps);
        Set<String> seen = new HashSet<>();
        boolean hasData = false;
        for (Step s : steps) {
            if (s.id() == null || !SAFE_ID.matcher(s.id()).matches() || s.id().contains("..") || !seen.add(s.id())) {
                throw new IllegalArgumentException("invalid or duplicate step id " + s.id());
            }
            if (s.kind() == Kind.EXTRACT) {
                hasData = true;
            } else if (!hasData) {
                throw new IllegalArgumentException("step " + s.id() + " comes before any EXTRACT step");
            }
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a pipeline needs steps");
        }
    }

    @SuppressWarnings("unchecked")
    public static PipelineDefinition parse(String json) {
        Map<String, Object> m = Json.readMap(json);
        List<Step> steps = new ArrayList<>();
        Object raw = m.get("steps");
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> s = (Map<String, Object>) o;
                Object chunk = s.get("chunkSize");
                Object cfg = s.get("config");
                steps.add(new Step((String) s.get("id"), Kind.valueOf(String.valueOf(s.get("kind"))), String.valueOf(s.get("type")),
                        chunk instanceof Number n ? n.intValue() : 500, cfg instanceof Map<?, ?> ? (Map<String, Object>) cfg : Map.of()));
            }
        }
        Object max = m.get("maxRejects");
        return new PipelineDefinition((String) m.get("id"), String.valueOf(m.getOrDefault("version", "1")), (String) m.get("dataset"),
                max instanceof Number n ? n.longValue() : 0, steps);
    }

    public String toJson() {
        return Json.write(this);
    }
}
