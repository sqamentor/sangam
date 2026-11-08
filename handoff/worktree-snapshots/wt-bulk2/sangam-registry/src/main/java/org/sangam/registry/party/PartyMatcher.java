// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.party;

import org.sangam.registry.support.Maps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The built-in matcher: blocking keys (to find candidates cheaply) and deterministic plus probabilistic scoring.
 * Identifiers are compared only by their keyed hashes, never by clear value.
 */
public final class PartyMatcher {

    /** What is compared: names, birth date, gender, attributes and identifier hashes by type. */
    public record Probe(String partyId, String kind, String fullName, String givenName, String familyName,
                        String birthDate, String gender, Map<String, Object> attrs, Map<String, Set<String>> idHashes) {

        public Map<String, Object> toMap() {
            return Maps.of("partyId", partyId, "kind", kind, "name", fullName, "givenName", givenName,
                    "familyName", familyName, "birthDate", birthDate, "gender", gender, "attributes", attrs,
                    "identifierHashes", idHashes);
        }
    }

    private PartyMatcher() {
    }

    public static Probe probe(PartyRecords.Party p, List<PartyRecords.Identifier> ids) {
        Map<String, Set<String>> h = new LinkedHashMap<>();
        for (PartyRecords.Identifier i : ids) {
            if ("ACTIVE".equals(i.status())) {
                h.computeIfAbsent(i.idType(), k -> new TreeSet<>()).add(i.valueHash());
            }
        }
        return new Probe(p.id(), p.kind(), fullName(p.names(), p.displayName()), Maps.str(p.names(), "given"),
                Maps.str(p.names(), "family"), p.birthDate(), p.gender(), p.attrs(), h);
    }

    /** The full name from a names map: {@code full}, else given + family, else the fallback. */
    public static String fullName(Map<String, Object> names, String fallback) {
        String full = Maps.str(names, "full");
        if (full != null && !full.isBlank()) {
            return full;
        }
        String g = Maps.str(names, "given");
        String f = Maps.str(names, "family");
        String joined = ((g == null ? "" : g) + " " + (f == null ? "" : f)).trim();
        return joined.isEmpty() ? fallback : joined;
    }

    /**
     * Blocking keys: each identifier hash; sorted 4-character name-token prefixes with the birth year; the family
     * token with the given initial; the full birth date with the name initial.
     */
    public static Set<String> blockingKeys(Probe p) {
        Set<String> keys = new LinkedHashSet<>();
        p.idHashes().forEach((type, hashes) -> hashes.forEach(h -> keys.add(cap("id:" + type + ":" + h))));
        List<String> tokens = new ArrayList<>(Similarity.tokens(p.fullName()));
        String year = p.birthDate() != null && p.birthDate().length() >= 4 ? p.birthDate().substring(0, 4) : "?";
        if (!tokens.isEmpty()) {
            TreeSet<String> pre = new TreeSet<>();
            for (String t : tokens) {
                pre.add(t.length() > 4 ? t.substring(0, 4) : t);
            }
            List<String> sorted = new ArrayList<>(pre);
            keys.add(cap("nm:" + String.join(".", sorted.subList(0, Math.min(3, sorted.size()))) + ":" + year));
            for (String t : pre) {
                if (t.length() >= 3) {
                    keys.add(cap("tk:" + t + ":" + year));
                }
            }
            if (p.birthDate() != null && p.birthDate().length() == 10) {
                keys.add(cap("dob:" + p.birthDate() + ":" + tokens.get(0).charAt(0)));
            }
        }
        String fam = Similarity.normalise(p.familyName());
        String giv = Similarity.normalise(p.givenName());
        if (!fam.isEmpty() && !giv.isEmpty()) {
            keys.add(cap("fg:" + fam + ":" + giv.charAt(0)));
        }
        return keys;
    }

    private static String cap(String k) {
        return k.length() > 255 ? k.substring(0, 255) : k;
    }

    static String value(Probe p, String field) {
        if (field.startsWith("attr:")) {
            return Maps.str(p.attrs(), field.substring(5));
        }
        return switch (field) {
            case "name" -> p.fullName();
            case "givenName" -> p.givenName();
            case "familyName" -> p.familyName();
            case "birthDate" -> p.birthDate();
            case "gender" -> p.gender();
            default -> null;
        };
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    /** Scores {@code b} against {@code a}. */
    public static PartyRecords.MatchResult score(Probe a, Probe b, MatchConfig cfg, IdentifierTypeCatalogue cat,
                                                 String tenant) {
        Map<String, Object> details = new LinkedHashMap<>();
        // unique identifier: decisive
        for (Map.Entry<String, Set<String>> e : a.idHashes().entrySet()) {
            Set<String> other = b.idHashes().get(e.getKey());
            if (other == null) {
                continue;
            }
            Set<String> shared = new TreeSet<>(e.getValue());
            shared.retainAll(other);
            if (shared.isEmpty()) {
                continue;
            }
            IdentifierType t = cat.find(tenant, e.getKey());
            if (t != null && t.unique()) {
                details.put("identifier:" + e.getKey(), "SHARED_UNIQUE");
                return new PartyRecords.MatchResult(b.partyId(), 99.0, "AUTO_MERGE", "DET:UNIQUE_IDENTIFIER", details);
            }
        }
        // deterministic rules
        for (MatchConfig.Rule r : cfg.rules()) {
            boolean all = !r.fields().isEmpty();
            for (String f : r.fields()) {
                if (f.startsWith("identifier:")) {
                    Set<String> x = a.idHashes().get(f.substring(11));
                    Set<String> y = b.idHashes().get(f.substring(11));
                    if (x == null || y == null || x.stream().noneMatch(y::contains)) {
                        all = false;
                    }
                } else {
                    String va = value(a, f);
                    String vb = value(b, f);
                    if (!present(va) || !present(vb) || !Similarity.normalise(va).equals(Similarity.normalise(vb))) {
                        all = false;
                    }
                }
                if (!all) {
                    break;
                }
            }
            if (all) {
                details.put("rule", r.key());
                double s = "AUTO_MERGE".equals(r.decision()) ? Math.max(cfg.autoMergeThreshold(), 50) : cfg.reviewThreshold();
                return new PartyRecords.MatchResult(b.partyId(), s, r.decision(), "DET:" + r.key(), details);
            }
        }
        // probabilistic
        double total = 0;
        for (MatchConfig.Comparator c : cfg.comparators()) {
            String va = value(a, c.field());
            String vb = value(b, c.field());
            if (!present(va) || !present(vb)) {
                continue;
            }
            double s = Similarity.compare(c.method(), va, vb);
            double w = s >= c.threshold() ? c.disagreement() + (c.agreement() - c.disagreement()) * s : c.disagreement();
            details.put(c.field(), Map.of("similarity", round(s), "weight", round(w)));
            total += w;
        }
        for (Map.Entry<String, Set<String>> e : a.idHashes().entrySet()) {
            Set<String> other = b.idHashes().get(e.getKey());
            if (other != null && e.getValue().stream().anyMatch(other::contains)) {
                IdentifierType t = cat.find(tenant, e.getKey());
                double w = t == null ? 4.0 : t.matchWeight();
                details.put("identifier:" + e.getKey(), Map.of("weight", round(w)));
                total += w;
            }
        }
        total = round(total);
        String decision = total >= cfg.autoMergeThreshold() ? "AUTO_MERGE"
                : total >= cfg.reviewThreshold() ? "REVIEW" : "NON_MATCH";
        return new PartyRecords.MatchResult(b.partyId(), total, decision, "PROBABILISTIC", details);
    }

    /** Applies an external score (weight from config) and re-decides. */
    public static PartyRecords.MatchResult withExternal(PartyRecords.MatchResult r, double external, MatchConfig cfg) {
        if (r.ruleKey().startsWith("DET:") || cfg.externalWeight() == 0) {
            return r;
        }
        double total = round(r.score() + cfg.externalWeight() * external);
        Map<String, Object> d = new LinkedHashMap<>(r.details());
        d.put("external", Map.of("score", round(external), "weight", cfg.externalWeight()));
        String decision = total >= cfg.autoMergeThreshold() ? "AUTO_MERGE"
                : total >= cfg.reviewThreshold() ? "REVIEW" : "NON_MATCH";
        return new PartyRecords.MatchResult(r.partyId(), total, decision, "PROBABILISTIC+EXTERNAL", d);
    }

    static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    static boolean same(String a, String b) {
        return Objects.equals(a, b);
    }
}
