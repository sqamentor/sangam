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
 * Results are cached per (scope, office, date) until {@link #invalidate()}, and per repository change marker
 * ({@link ProfileRepository#changeMarker()}, SL-120): a change committed by any node (activation, retirement, import)
 * moves the marker, so every node stops trusting its cached resolutions on its next call.
 *
 * <p>{@link #resolveBinding} resolves a case's stored binding (the exact document versions it started under, GP-19 /
 * R43, SL-119) instead of the versions in force today.
 */
public final class ProfileResolver {

    private static final String LOCAL = "local";

    private final ProfileRepository repo;
    private final LayerModel layers;
    private final Map<String, ResolvedProfile> cache = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicReference<String> marker = new java.util.concurrent.atomic.AtomicReference<>();

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
        String key = freshMarker() + "|" + scope + "|" + office + "|" + asOf;
        return cache.computeIfAbsent(key, k -> doResolve(ScopeRef.parse(scope), office, asOf));
    }

    /**
     * Resolves a stored case binding ({@code id@version[sections]>...#hash12}, {@link ResolvedProfile#binding()}): the
     * same document versions merged in the same order, whatever is active today, with row-level dates read on
     * {@code asOf}. Versions are frozen once submitted, so the recomposed binding equals the stored one; a binding that
     * cannot be parsed, names a version the repository no longer holds, or recomposes to a different hash carries an
     * ERROR finding ({@code BINDING_INVALID}, {@code BINDING_VERSION_MISSING}, {@code BINDING_MISMATCH}) and must not
     * be used in place of the case's configuration.
     */
    public ResolvedProfile resolveBinding(String scope, String binding, LocalDate asOf) {
        String key = freshMarker() + "|pin|" + scope + "|" + binding + "|" + asOf;
        return cache.computeIfAbsent(key, k -> doResolveBinding(ScopeRef.parse(scope), binding, asOf));
    }

    /** True when the finding is one of {@link #resolveBinding}'s own binding errors. */
    public static boolean isBindingError(Finding f) {
        return f.isError() && f.code().startsWith("BINDING_");
    }

    /**
     * The repository's change marker; when it moved since the last call the cache is dropped (entries are keyed by the
     * marker they were computed under, so a resolution racing a change is never served under the new marker).
     */
    private String freshMarker() {
        String m = repo.changeMarker();
        if (m == null) {
            return LOCAL;
        }
        String prev = marker.getAndSet(m);
        if (prev != null && !prev.equals(m)) {
            cache.clear();
        }
        return m;
    }

    private ResolvedProfile doResolveBinding(ScopeRef scope, String binding, LocalDate asOf) {
        List<Finding> findings = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        int hash = binding == null ? -1 : binding.lastIndexOf('#');
        if (hash <= 0) {
            findings.add(Finding.error("BINDING_INVALID", scope.id(), "binding '" + binding + "' has no chain#hash form", null));
        } else {
            for (String label : binding.substring(0, hash).split(">")) {
                String base = label;
                List<String> sections = null;
                int open = label.indexOf('[');
                if (open >= 0 && label.endsWith("]")) {
                    base = label.substring(0, open);
                    String inner = label.substring(open + 1, label.length() - 1);
                    sections = inner.isEmpty() ? List.of() : List.of(inner.split(","));
                }
                int at = base.indexOf('@');
                if (at <= 0 || at == base.length() - 1) {
                    findings.add(Finding.error("BINDING_INVALID", scope.id(), "binding step '" + label + "' is not id@version", null));
                    continue;
                }
                Optional<ProfileRepository.Stored> s = repo.get(base.substring(0, at), base.substring(at + 1));
                if (s.isEmpty()) {
                    findings.add(Finding.error("BINDING_VERSION_MISSING", scope.id(), "bound version " + base
                            + " is not in the repository", null));
                    continue;
                }
                steps.add(new Step(s.get().document(), sections));
            }
        }
        ResolvedProfile r = compose(scope, null, asOf, steps, findings);
        if (findings.stream().noneMatch(ProfileResolver::isBindingError) && !r.binding().equals(binding)) {
            List<Finding> f = new ArrayList<>(r.findings());
            f.add(Finding.error("BINDING_MISMATCH", scope.id(), "binding " + binding + " recomposes to " + r.binding(), null));
            r = new ResolvedProfile(r.scope(), r.office(), r.asOf(), r.data(), r.chain(), r.binding(), r.placeholders(),
                    List.copyOf(f));
        }
        return r;
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
        return compose(scope, office, asOf, steps, findings);
    }

    /** Merges the chain's steps in order (layer order, locked paths, placeholders, required paths) and binds it. */
    private ResolvedProfile compose(ScopeRef scope, String office, LocalDate asOf, List<Step> steps, List<Finding> findings) {
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
        if (!steps.isEmpty()) {
            fiscalFinding(data, findings);
        }
        for (Map.Entry<String, String> p : placeholders.entrySet()) {
            findings.add(new Finding(Finding.Severity.PLACEHOLDER, "PLACEHOLDER", p.getKey(), p.getValue(), null));
        }
        String joined = String.join(">", chain);
        String binding = joined + "#" + Hashes.hex(Hashes.sha256(joined + "|" + org.sangam.core.layer.CanonicalJson.write(data))).substring(0, 12);
        return new ResolvedProfile(scope.id(), office, asOf, data, List.copyOf(chain), binding, Map.copyOf(placeholders),
                List.copyOf(findings));
    }

    /** Finding code: the resolved profile has no {@code locale.fiscal} (WARNING; fiscal operations are refused). */
    public static final String FISCAL_NOT_CONFIGURED = "FISCAL_NOT_CONFIGURED";
    /** Finding code: {@code locale.fiscal} is present but incomplete or invalid (ERROR). */
    public static final String FISCAL_INVALID = "FISCAL_INVALID";

    /**
     * SL-125 (fail closed): a profile without a fiscal year is valid (a fiscal year is not every tenant's business), but
     * operators see a WARNING, and every fiscal operation is refused until the tenant sets it. A present but incomplete
     * {@code locale.fiscal} (start month, start day or label missing or invalid) is an ERROR: nothing is assumed.
     */
    private static void fiscalFinding(Map<String, Object> data, List<Finding> findings) {
        Map<String, Object> fiscal = Tree.map(Tree.get(data, "locale.fiscal"));
        if (fiscal.isEmpty()) {
            findings.add(Finding.warn(FISCAL_NOT_CONFIGURED, "locale.fiscal",
                    "locale.fiscal not configured; fiscal operations are refused", null));
            return;
        }
        try {
            org.sangam.config.locale.FiscalCalendar.from(fiscal, java.time.ZoneId.of("UTC"));
        } catch (RuntimeException e) {
            findings.add(Finding.error(FISCAL_INVALID, "locale.fiscal", e.getMessage(), null));
        }
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
