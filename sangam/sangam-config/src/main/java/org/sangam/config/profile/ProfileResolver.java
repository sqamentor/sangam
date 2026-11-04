// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import org.sangam.config.common.ConfigException;
import org.sangam.config.common.Finding;
import org.sangam.config.common.Tree;
import org.sangam.core.layer.Hashes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the effective configuration of a scope by inheritance.
 *
 * <p>Chain for scope {@code A.B} with office {@code O} on date d: for each prefix ({@code A}, {@code A.B}) and then
 * the office overlay ({@code A.B/O}), the ACTIVE document effective on d, each preceded by its {@code extends}
 * documents (recursively, depth first) and by the sections it {@code adopt}s from donors. Documents are merged in that
 * order (most specific wins, see {@link Tree#merge}). Rules enforced:
 * <ul>
 *   <li>layer order follows the {@link LayerModel} (error if a lower layer follows a higher one);</li>
 *   <li>a path in any earlier document's {@code locked} list, or set by a non-overridable layer, may not be changed by
 *       a later document: the change is ignored and an ERROR finding records it;</li>
 *   <li>placeholders accumulate; a later document that sets the path clears it unless it re-declares it;</li>
 *   <li>a missing {@code extends} / {@code adopt} target is an ERROR; cycles are an ERROR.</li>
 * </ul>
 * Results are cached per (scope, office, date) until {@link #invalidate()}.
 */
public final class ProfileResolver {

    private final ProfileRepository repo;
    private final LayerModel layers;
    private final Map<String, ResolvedProfile> cache = new ConcurrentHashMap<>();

    public ProfileResolver(ProfileRepository repo, LayerModel layers) {
        this.repo = repo;
        this.layers = layers == null ? LayerModel.open() : layers;
    }

    public LayerModel layers() {
        return layers;
    }

    public void invalidate() {
        cache.clear();
    }

    public ResolvedProfile resolve(String scope, LocalDate asOf) {
        return resolve(scope, null, asOf);
    }

    /** Resolves and throws {@link ConfigException} if the chain has errors (strict use at case start). */
    public ResolvedProfile resolveStrict(String scope, String office, LocalDate asOf) {
        ResolvedProfile r = resolve(scope, office, asOf);
        if (r.hasErrors()) {
            throw new ConfigException("PROFILE_INVALID", "profile chain of " + scope + " has errors", Finding.errors(r.findings()));
        }
        return r;
    }

    public ResolvedProfile resolve(String scope, String office, LocalDate asOf) {
        String key = scope + "|" + office + "|" + asOf;
        return cache.computeIfAbsent(key, k -> doResolve(ScopeRef.parse(scope), office, asOf));
    }

    private ResolvedProfile doResolve(ScopeRef scope, String office, LocalDate asOf) {
        List<Finding> findings = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> ids = new ArrayList<>(scope.prefixes());
        if (office != null && !office.isBlank()) {
            ids.add(scope.officeId(office));
        }
        boolean any = false;
        for (String id : ids) {
            Optional<ProfileDocument> d = repo.find(id, asOf);
            if (d.isPresent()) {
                any = true;
                expand(d.get(), asOf, steps, seen, new LinkedHashSet<>(), findings);
            }
        }
        if (!any) {
            findings.add(Finding.error("PROFILE_NOT_FOUND", scope.id(), "no active profile for scope " + scope
                    + " on " + asOf, null));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, String> placeholders = new LinkedHashMap<>();
        Map<String, String> lockedBy = new LinkedHashMap<>();
        List<String> chain = new ArrayList<>();
        int lastRank = -1;
        String lastLayer = null;
        for (Step s : steps) {
            ProfileDocument doc = s.doc();
            chain.add(s.label());
            int rank = layers.rank(doc.layer());
            if (!layers.isOpen()) {
                if (rank < 0) {
                    findings.add(Finding.error("LAYER_UNKNOWN", doc.id(), "layer '" + doc.layer() + "' is not in the layer model", null));
                } else if (rank < lastRank) {
                    findings.add(Finding.error("LAYER_ORDER", doc.id(), "layer '" + doc.layer() + "' follows '" + lastLayer
                            + "'", null));
                } else {
                    lastRank = rank;
                    lastLayer = doc.layer();
                }
            }
            Map<String, Object> overlay = s.sections() == null ? Tree.copyMap(doc.data()) : pick(doc.data(), s.sections());
            // locked paths from earlier documents
            List<String> violations = new ArrayList<>();
            Tree.merge(Tree.copyMap(data), Tree.copyMap(overlay), "", p -> {
                String owner = lockOwner(lockedBy, p);
                if (owner != null) {
                    violations.add(p);
                }
            });
            for (String v : violations) {
                findings.add(Finding.error("PATH_LOCKED", v, doc.id() + " may not override '" + v + "' (locked by "
                        + lockOwner(lockedBy, v) + ")", null));
                remove(overlay, v);
            }
            Set<String> setPaths = new LinkedHashSet<>();
            Tree.merge(data, overlay, "", setPaths::add);
            for (String p : setPaths) {
                placeholders.keySet().removeIf(ph -> ph.equals(p) || ph.startsWith(p + ".") || p.startsWith(ph + "."));
            }
            if (s.sections() == null) {
                placeholders.putAll(doc.placeholders());
                for (String l : doc.locked()) {
                    lockedBy.putIfAbsent(l, doc.id());
                }
                LayerModel.Layer lm = layers.layer(doc.layer());
                if (lm != null && !lm.overridable()) {
                    for (String p : doc.data().keySet()) {
                        lockedBy.putIfAbsent(p, doc.id() + " (layer " + doc.layer() + ")");
                    }
                }
            } else {
                doc.placeholders().forEach((p, r) -> {
                    for (String sec : s.sections()) {
                        if (p.equals(sec) || p.startsWith(sec + ".")) {
                            placeholders.put(p, r);
                        }
                    }
                });
            }
        }
        if (!layers.isOpen() && !steps.isEmpty()) {
            for (LayerModel.Layer l : layers.layers()) {
                boolean present = steps.stream().anyMatch(s -> s.doc().layer().equals(l.id()));
                if (present) {
                    for (String req : l.required()) {
                        if (Tree.get(data, req) == null) {
                            findings.add(Finding.error("REQUIRED_MISSING", req, "layer " + l.id() + " requires '" + req + "'", null));
                        }
                    }
                }
            }
        }
        for (Map.Entry<String, String> p : placeholders.entrySet()) {
            findings.add(new Finding(Finding.Severity.PLACEHOLDER, "PLACEHOLDER", p.getKey(), p.getValue(), null));
        }
        String joined = String.join(">", chain);
        String binding = joined + "#" + Hashes.hex(Hashes.sha256(joined + "|" + org.sangam.core.layer.CanonicalJson.write(data))).substring(0, 12);
        return new ResolvedProfile(scope.id(), office, asOf, data, List.copyOf(chain), binding, Map.copyOf(placeholders),
                List.copyOf(findings));
    }

    private record Step(ProfileDocument doc, List<String> sections) {
        String label() {
            return doc.ref() + (sections == null ? "" : "[" + String.join(",", sections) + "]");
        }
    }

    private void expand(ProfileDocument doc, LocalDate asOf, List<Step> steps, Set<String> seen, Set<String> stack,
                        List<Finding> findings) {
        if (!stack.add(doc.id())) {
            findings.add(Finding.error("EXTENDS_CYCLE", doc.id(), "cycle through " + stack, null));
            return;
        }
        for (String e : doc.extendsIds()) {
            if (seen.contains(e)) {
                continue;
            }
            Optional<ProfileDocument> p = repo.find(e, asOf);
            if (p.isEmpty()) {
                findings.add(Finding.error("EXTENDS_MISSING", doc.id(), "extends '" + e + "' has no active version on " + asOf, null));
            } else {
                expand(p.get(), asOf, steps, seen, stack, findings);
            }
        }
        for (Map<String, Object> a : doc.adopt()) {
            String donor = Tree.str(a.get("profile"), null);
            Optional<ProfileDocument> p = donor == null ? Optional.empty() : repo.find(donor, asOf);
            if (p.isEmpty()) {
                findings.add(Finding.error("ADOPT_MISSING", doc.id(), "adopts from '" + donor + "' with no active version", null));
            } else {
                steps.add(new Step(p.get(), Tree.strings(a.get("sections"))));
            }
        }
        if (seen.add(doc.id())) {
            steps.add(new Step(doc, null));
        }
        stack.remove(doc.id());
    }

    private static Map<String, Object> pick(Map<String, Object> data, List<String> sections) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String s : sections) {
            Object v = Tree.get(data, s);
            if (v != null) {
                put(out, s, Tree.copy(v));
            }
        }
        return out;
    }

    private static void put(Map<String, Object> root, String path, Object v) {
        String[] parts = path.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object n = cur.get(parts[i]);
            if (!(n instanceof Map<?, ?>)) {
                n = new LinkedHashMap<String, Object>();
                cur.put(parts[i], n);
            }
            cur = Tree.map(n);
        }
        cur.put(parts[parts.length - 1], v);
    }

    private static void remove(Map<String, Object> root, String path) {
        String[] parts = path.split("\\.");
        Object cur = root;
        for (int i = 0; i < parts.length - 1 && cur != null; i++) {
            cur = cur instanceof Map<?, ?> m ? m.get(parts[i]) : null;
            if (cur instanceof List<?> l) {
                cur = Tree.get(Map.of("x", l), "x." + parts[i + 1]);
                i++;
                if (i == parts.length - 1) {
                    if (cur instanceof Map<?, ?> mm) {
                        Tree.map(mm).clear();
                    }
                    return;
                }
            }
        }
        if (cur instanceof Map<?, ?> m) {
            Tree.map(m).remove(parts[parts.length - 1]);
        }
    }

    private static String lockOwner(Map<String, String> locked, String path) {
        for (Map.Entry<String, String> e : locked.entrySet()) {
            String l = e.getKey();
            if (path.equals(l) || path.startsWith(l + ".")) {
                return e.getValue();
            }
        }
        return null;
    }
}
