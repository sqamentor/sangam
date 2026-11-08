// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.party;

import org.sangam.registry.RegistryException;
import org.sangam.registry.support.Maps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Matching and survivorship configuration (data, per tenant):
 * <pre>{@code
 * { "comparators": [ {"field": "name", "method": "JARO_WINKLER", "m": 0.95, "u": 0.01, "threshold": 0.85},
 *                    {"field": "birthDate", "method": "DATE", "m": 0.97, "u": 0.02, "threshold": 0.4},
 *                    {"field": "gender", "method": "EXACT", "m": 0.98, "u": 0.5},
 *                    {"field": "attr:locality", "method": "TOKEN_SET", "m": 0.9, "u": 0.1, "threshold": 0.5} ],
 *   "rules": [ {"key": "name-dob-gender", "fields": ["name", "birthDate", "gender"], "decision": "REVIEW"} ],
 *   "autoMergeThreshold": 14, "reviewThreshold": 7, "autoMergeEnabled": false,
 *   "externalWeight": 0, "maxCandidates": 200,
 *   "survivorship": {"names": "SURVIVOR", "birthDate": "MOST_COMPLETE", "gender": "SURVIVOR",
 *                    "attributes": "MOST_RECENT", "displayName": "SURVIVOR"} }
 * }</pre>
 * Probabilistic score: the sum of Fellegi-Sunter weights. A comparator with similarity {@code s} at or above its
 * threshold contributes {@code d + (a - d) * s}, with agreement weight {@code a = log2(m/u)} and disagreement weight
 * {@code d = log2((1-m)/(1-u))}; below the threshold it contributes {@code d}; a missing value contributes 0. A shared
 * identifier adds its type's {@code matchWeight}; a shared value of a {@code unique} identifier type decides SAME
 * (AUTO_MERGE) at once. Deterministic rules match when every listed field is present and equal after normalisation.
 * Fields: {@code name}, {@code givenName}, {@code familyName}, {@code birthDate}, {@code gender}, {@code attr:<key>},
 * {@code identifier:<type>}.
 */
public record MatchConfig(List<Comparator> comparators, List<Rule> rules, double autoMergeThreshold,
                          double reviewThreshold, boolean autoMergeEnabled, double externalWeight, int maxCandidates,
                          Map<String, String> survivorship) {

    public record Comparator(String field, String method, double m, double u, double threshold) {
        public double agreement() {
            return Math.log(m / u) / Math.log(2);
        }

        public double disagreement() {
            return Math.log((1 - m) / (1 - u)) / Math.log(2);
        }
    }

    public record Rule(String key, List<String> fields, String decision) {
    }

    public static MatchConfig defaults() {
        return new MatchConfig(List.of(
                new Comparator("name", "JARO_WINKLER", 0.95, 0.01, 0.85),
                new Comparator("familyName", "JARO_WINKLER", 0.9, 0.05, 0.88),
                new Comparator("birthDate", "DATE", 0.97, 0.02, 0.4),
                new Comparator("gender", "EXACT", 0.98, 0.5, 1.0)),
                List.of(new Rule("name-birthdate-gender", List.of("name", "birthDate", "gender"), "REVIEW")),
                14, 7, false, 0, 200, Map.of());
    }

    public static MatchConfig from(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return defaults();
        }
        MatchConfig d = defaults();
        List<Comparator> comps = new ArrayList<>();
        for (Map<String, Object> c : Maps.maps(m, "comparators")) {
            double mm = num(c, "m", 0.9);
            double uu = num(c, "u", 0.1);
            if (mm <= 0 || mm >= 1 || uu <= 0 || uu >= 1) {
                throw RegistryException.invalid("comparator m and u must be strictly between 0 and 1");
            }
            String method = Maps.str(c, "method", "EXACT").toUpperCase();
            if (!List.of("EXACT", "JARO_WINKLER", "LEVENSHTEIN", "TOKEN_SET", "DATE").contains(method)) {
                throw RegistryException.invalid("unknown comparator method " + method);
            }
            comps.add(new Comparator(Maps.required(c, "field"), method, mm, uu, num(c, "threshold", 1.0)));
        }
        List<Rule> rules = new ArrayList<>();
        for (Map<String, Object> r : Maps.maps(m, "rules")) {
            String dec = Maps.str(r, "decision", "REVIEW").toUpperCase();
            if (!List.of("AUTO_MERGE", "REVIEW").contains(dec)) {
                throw RegistryException.invalid("rule decision must be AUTO_MERGE or REVIEW");
            }
            rules.add(new Rule(Maps.required(r, "key"), Maps.strings(r, "fields"), dec));
        }
        Map<String, String> surv = new LinkedHashMap<>();
        Maps.map(m, "survivorship").forEach((k, v) -> {
            String rule = String.valueOf(v).toUpperCase();
            if (!List.of("SURVIVOR", "MOST_RECENT", "MOST_COMPLETE", "SOURCE_PRIORITY").contains(rule)) {
                throw RegistryException.invalid("unknown survivorship rule " + rule);
            }
            surv.put(k, rule);
        });
        double auto = num(m, "autoMergeThreshold", d.autoMergeThreshold());
        double review = num(m, "reviewThreshold", d.reviewThreshold());
        if (review > auto) {
            throw RegistryException.invalid("reviewThreshold must not exceed autoMergeThreshold");
        }
        return new MatchConfig(comps.isEmpty() ? d.comparators() : List.copyOf(comps),
                m.containsKey("rules") ? List.copyOf(rules) : d.rules(), auto, review,
                Maps.bool(m, "autoMergeEnabled", false), num(m, "externalWeight", 0),
                Maps.integer(m, "maxCandidates", 200), Map.copyOf(surv));
    }

    private static double num(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        return v == null ? def : Double.parseDouble(v.toString());
    }

    public String survivorship(String field) {
        return survivorship.getOrDefault(field, switch (field) {
            case "birthDate" -> "MOST_COMPLETE";
            case "attributes" -> "MOST_RECENT";
            default -> "SURVIVOR";
        });
    }

    public Map<String, Object> toMap() {
        return Maps.of("comparators", comparators.stream().map(c -> Maps.of("field", c.field(), "method", c.method(),
                        "m", c.m(), "u", c.u(), "threshold", c.threshold())).toList(),
                "rules", rules.stream().map(r -> Maps.of("key", r.key(), "fields", r.fields(), "decision", r.decision())).toList(),
                "autoMergeThreshold", autoMergeThreshold, "reviewThreshold", reviewThreshold,
                "autoMergeEnabled", autoMergeEnabled, "externalWeight", externalWeight, "maxCandidates", maxCandidates,
                "survivorship", survivorship);
    }
}
