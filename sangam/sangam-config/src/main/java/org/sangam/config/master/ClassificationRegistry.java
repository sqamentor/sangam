// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.master;

import org.sangam.config.common.ConfigException;
import org.sangam.config.common.Finding;
import org.sangam.config.common.Tree;
import org.sangam.config.profile.ScopeRef;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Master-data / classification registry: parses and validates composite codes against a {@link ClassificationScheme},
 * resolves segment codes through the scope prefixes, derives attributes, applies correction slips and bulk-loads seeds.
 */
public final class ClassificationRegistry {

    /** A parsed composite code. {@code segments} keeps scheme order; absent optional segments are null. */
    public record Code(String scheme, Map<String, String> segments, Map<String, Object> attributes) {
        public String segment(String id) {
            return segments.get(id);
        }

        /** Digits joined without separators (e.g. the 15-digit form). */
        public String compact() {
            StringBuilder b = new StringBuilder();
            segments.values().forEach(v -> b.append(v == null ? "" : v));
            return b.toString();
        }
    }

    /** One change of a correction slip. ops: ADD, MODIFY (rename / flags), RETIRE, REMAP (old → new). */
    public record SlipChange(String op, String segment, String parentCode, String code, Map<String, String> name,
                             Map<String, Object> flags, String newCode) {
    }

    /** A correction slip / amendment of a master list, applied on its effective date. */
    public record CorrectionSlip(String id, String scope, String scheme, LocalDate effectiveFrom, List<SlipChange> changes) {
    }

    private final MasterStore store;
    private java.util.function.Function<String, List<String>> referenceScopes = s -> List.of();

    public ClassificationRegistry(MasterStore store) {
        this.store = store;
    }

    /**
     * Scopes searched after the scope's own prefixes, e.g. the jurisdiction and international reference documents a
     * scope's profile extends ({@code KA -> [IN, INTL]}). Usually derived from the resolved profile chain.
     */
    public ClassificationRegistry withReferenceScopes(java.util.function.Function<String, List<String>> f) {
        this.referenceScopes = f;
        return this;
    }

    public MasterStore store() {
        return store;
    }

    /**
     * Parses {@code value} by the scheme: with separators ({@code 2054-00-095-01-00-13}) or compact digits
     * ({@code 205400095010013}).
     */
    public Code parse(ClassificationScheme s, String value) {
        String v = value.trim();
        Map<String, String> segs = new LinkedHashMap<>();
        if (v.contains(s.separator()) || v.contains("(")) {
            String clean = v.replace("(", "").replace(")", "").replace("[", "").replace("]", "");
            String[] parts = clean.split(java.util.regex.Pattern.quote(s.separator()), -1);
            int i = 0;
            for (ClassificationScheme.Segment seg : s.segments()) {
                segs.put(seg.id(), i < parts.length && !parts[i].isBlank() ? parts[i].trim() : null);
                i++;
            }
        } else {
            int pos = 0;
            for (ClassificationScheme.Segment seg : s.segments()) {
                if (pos + seg.width() <= v.length()) {
                    segs.put(seg.id(), v.substring(pos, pos + seg.width()));
                    pos += seg.width();
                } else {
                    segs.put(seg.id(), null);
                }
            }
        }
        return new Code(s.id(), segs, derive(s, segs));
    }

    /** Renders a code with the scheme's template, or joined by the separator. */
    public String render(ClassificationScheme s, Code c) {
        if (s.render() == null) {
            List<String> p = new ArrayList<>();
            c.segments().values().forEach(x -> p.add(x == null ? "" : x));
            return String.join(s.separator(), p);
        }
        String out = s.render();
        for (Map.Entry<String, String> e : c.segments().entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    /** Validates every segment: presence, width / pattern, and master codes valid on {@code asOf} in the scope chain. */
    public List<Finding> validate(String scope, ClassificationScheme s, Code c, LocalDate asOf) {
        return validate(scope, s, c, asOf, id -> id.equals(s.id()) ? s : null);
    }

    /**
     * As above, with a scheme lookup: a segment that references another scheme ({@code scheme: HOA}) follows the
     * hierarchy declared on that scheme's segment of the same id, so a government's own layout inherits it.
     */
    public List<Finding> validate(String scope, ClassificationScheme s, Code c, LocalDate asOf,
                                  java.util.function.Function<String, ClassificationScheme> schemes) {
        List<Finding> out = new ArrayList<>();
        for (ClassificationScheme.Segment seg : s.segments()) {
            String v = c.segment(seg.id());
            if (v == null) {
                if (!seg.optional()) {
                    out.add(Finding.error("SEGMENT_MISSING", seg.id(), "segment " + seg.id() + " is required", null));
                }
                continue;
            }
            String pat = seg.pattern() != null ? seg.pattern() : "\\d{" + seg.width() + "}";
            if (!v.matches(pat)) {
                out.add(Finding.error("SEGMENT_FORMAT", seg.id(), "'" + v + "' does not match " + pat, null));
                continue;
            }
            if (seg.master() || seg.scheme() != null) {
                String parent = seg.parent() == null ? null : c.segment(seg.parent());
                String scheme = seg.scheme() != null ? seg.scheme() : s.id();
                String segId = seg.id();
                ClassificationScheme ref = seg.scheme() == null ? s : schemes.apply(seg.scheme());
                ClassificationScheme.Segment def = ref == null ? null : ref.segment(segId);
                if (def != null && !def.hierarchy().isEmpty()) {
                    checkHierarchy(scope, scheme, def, c, v, asOf, out);
                    continue;
                }
                Optional<MasterCode> m = lookup(scope, scheme, segId, seg.scheme() != null ? null : parent, v, asOf);
                if (m.isEmpty()) {
                    out.add(Finding.error("CODE_UNKNOWN", seg.id(), "code " + v + (parent == null ? "" : " under " + parent)
                            + " is not valid on " + asOf, null));
                }
            }
        }
        return out;
    }

    /**
     * Parent-child check of one segment, configured by {@code hierarchy}:
     * <ul>
     *   <li>{@code path}: ancestor segments; their values joined by {@code separator} form the parent key;</li>
     *   <li>{@code anyParentFlag}: a code with this flag and no parent is valid under any parent;</li>
     *   <li>{@code openRuleFlag}: an ancestor, or a sibling row, with this flag lets codes be opened by that rule;</li>
     *   <li>{@code templateWildcard}: rows whose parent is {@code <ancestor>-<wildcard>n} apply when the immediate parent
     *       segment was itself opened by rule;</li>
     *   <li>{@code noneCode}: valid when the parent has no coded children; {@code templateChild}: this segment's codes
     *       are opened by rule when that child segment has template rows under the parent.</li>
     * </ul>
     * A parent with no coded children and no rule cannot refute a code: it passes with a WARNING. A rule-opened code
     * passes with an INFO finding quoting the rule.
     */
    private void checkHierarchy(String scope, String scheme, ClassificationScheme.Segment def, Code c, String v, LocalDate asOf,
                                List<Finding> out) {
        Map<String, Object> h = def.hierarchy();
        List<String> path = Tree.strings(h.get("path"));
        String sep = Tree.str(h.get("separator"), "-");
        List<String> values = new ArrayList<>();
        for (String p : path) {
            if (c.segment(p) == null) {
                return; // reported as a missing or optional ancestor
            }
            values.add(c.segment(p));
        }
        String parent = String.join(sep, values);
        if (lookup(scope, scheme, def.id(), path.isEmpty() ? null : parent, v, asOf).isPresent()) {
            return;
        }
        String anyFlag = Tree.str(h.get("anyParentFlag"), null);
        if (anyFlag != null && lookup(scope, scheme, def.id(), null, v, asOf)
                .filter(m -> Tree.bool(m.flags().get(anyFlag), false)).isPresent()) {
            return;
        }
        if (v.equals(Tree.str(h.get("noneCode"), null)) && !path.isEmpty()
                && children(scope, scheme, def.id(), parent, asOf).isEmpty()) {
            return;
        }
        String wildcard = Tree.str(h.get("templateWildcard"), null);
        String template = wildcard == null || path.size() < 2 ? null
                : String.join(sep, values.subList(0, values.size() - 1)) + sep + wildcard;
        if (template != null && children(scope, scheme, def.id(), template, asOf).stream().anyMatch(m -> m.code().equals(v))) {
            return;
        }
        String childSeg = Tree.str(h.get("templateChild"), null);
        if (childSeg != null && wildcard != null && !children(scope, scheme, childSeg, parent + sep + wildcard, asOf).isEmpty()) {
            out.add(Finding.info("CODE_OPENED_BY_RULE", def.id(), "code " + v + " opened by rule under " + parent, null));
            return;
        }
        String ruleFlag = Tree.str(h.get("openRuleFlag"), null);
        if (ruleFlag != null) {
            String rule = null;
            for (int i = 0; i < path.size() && rule == null; i++) {
                String ancestorParent = i == 0 ? null : String.join(sep, values.subList(0, i));
                rule = lookup(scope, scheme, path.get(i), ancestorParent, values.get(i), asOf)
                        .map(m -> Tree.str(m.flags().get(ruleFlag), null)).orElse(null);
            }
            if (rule == null) {
                rule = children(scope, scheme, def.id(), parent, asOf).stream()
                        .filter(m -> parent.equals(m.parentCode()) && m.flags().get(ruleFlag) != null)
                        .map(m -> Tree.str(m.flags().get(ruleFlag), null)).findFirst().orElse(null);
            }
            if (rule != null) {
                out.add(Finding.info("CODE_OPENED_BY_RULE", def.id(), "code " + v + " under " + parent + ": " + rule, null));
                return;
            }
        }
        if (!path.isEmpty() && children(scope, scheme, def.id(), parent, asOf).isEmpty()
                && (template == null || children(scope, scheme, def.id(), template, asOf).isEmpty())) {
            out.add(Finding.warn("PARENT_HAS_NO_CODES", def.id(), "no codes listed under " + parent + "; " + v + " not verified", null));
            return;
        }
        out.add(Finding.error("CODE_NOT_UNDER_PARENT", def.id(), "code " + v + " is not valid under " + parent + " on " + asOf, null));
    }

    /** Codes valid on the date whose parent starts with the prefix, through the scope chain. */
    private List<MasterCode> children(String scope, String scheme, String segment, String prefix, LocalDate asOf) {
        List<String> scopes = new ArrayList<>(ScopeRef.parse(scope).prefixes());
        scopes.addAll(referenceScopes.apply(scope));
        scopes.add("*");
        List<MasterCode> out = new ArrayList<>();
        for (String sc : scopes) {
            store.children(sc, scheme, segment, prefix).stream().filter(m -> m.validOn(asOf)).forEach(out::add);
        }
        return out;
    }

    /** Looks a code up in the scope and its ancestors (most specific first). */
    public Optional<MasterCode> lookup(String scope, String scheme, String segment, String parentCode, String code,
                                       LocalDate asOf) {
        List<String> prefixes = new ArrayList<>(ScopeRef.parse(scope).prefixes());
        java.util.Collections.reverse(prefixes);
        prefixes.addAll(referenceScopes.apply(scope));
        prefixes.add("*");
        for (String p : prefixes) {
            Optional<MasterCode> m = store.find(p, scheme, segment, parentCode, code).stream().filter(x -> x.validOn(asOf))
                    .max(Comparator.comparing(x -> x.validFrom() == null ? LocalDate.MIN : x.validFrom()));
            if (m.isPresent()) {
                return m;
            }
        }
        return Optional.empty();
    }

    /** Applies the scheme's derivation rules; the first matching rule sets each attribute. */
    public Map<String, Object> derive(ClassificationScheme s, Map<String, String> segs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> r : s.derive()) {
            String v = segs.get(Tree.str(r.get("segment"), ""));
            if (v == null) {
                continue;
            }
            String prefix = Tree.str(r.get("prefix"), null);
            String equals = Tree.str(r.get("equals"), null);
            String regex = Tree.str(r.get("matches"), null);
            boolean hit = (prefix != null && v.startsWith(prefix)) || (equals != null && v.equals(equals))
                    || (regex != null && v.matches(regex));
            if (hit) {
                Tree.map(r.get("set")).forEach(out::putIfAbsent);
            }
        }
        return out;
    }

    /** Applies a correction slip: adds, modifies, retires and remaps codes from its effective date. */
    public List<Finding> apply(CorrectionSlip slip) {
        List<Finding> out = new ArrayList<>();
        for (SlipChange ch : slip.changes()) {
            List<MasterCode> existing = store.find(slip.scope(), slip.scheme(), ch.segment(), ch.parentCode(), ch.code());
            MasterCode current = existing.stream().filter(x -> x.validOn(slip.effectiveFrom())).findFirst().orElse(null);
            switch (ch.op().toUpperCase()) {
                case "ADD" -> {
                    if (current != null) {
                        out.add(Finding.warn("SLIP_ADD_EXISTS", ch.code(), "already valid; treated as MODIFY", slip.id()));
                        close(current, slip.effectiveFrom());
                    }
                    store.put(new MasterCode(slip.scope(), slip.scheme(), ch.segment(), ch.code(), ch.parentCode(),
                            ch.name(), ch.flags(), slip.effectiveFrom(), null, slip.id()));
                }
                case "MODIFY" -> {
                    if (current == null) {
                        out.add(Finding.error("SLIP_MODIFY_MISSING", ch.code(), "no valid code to modify", slip.id()));
                        continue;
                    }
                    close(current, slip.effectiveFrom());
                    Map<String, Object> flags = new LinkedHashMap<>(current.flags());
                    if (ch.flags() != null) {
                        flags.putAll(ch.flags());
                    }
                    store.put(new MasterCode(slip.scope(), slip.scheme(), ch.segment(), ch.code(), ch.parentCode(),
                            ch.name() == null || ch.name().isEmpty() ? current.name() : ch.name(), flags,
                            slip.effectiveFrom(), null, slip.id()));
                }
                case "RETIRE", "REMAP" -> {
                    if (current == null) {
                        out.add(Finding.error("SLIP_RETIRE_MISSING", ch.code(), "no valid code to retire", slip.id()));
                        continue;
                    }
                    close(current, slip.effectiveFrom());
                    if ("REMAP".equalsIgnoreCase(ch.op())) {
                        out.add(Finding.info("SLIP_REMAP", ch.code(), "remap budget lines " + ch.code() + " -> " + ch.newCode(),
                                slip.id()));
                    }
                }
                default -> out.add(Finding.error("SLIP_OP", ch.code(), "unknown op " + ch.op(), slip.id()));
            }
        }
        return out;
    }

    private void close(MasterCode c, LocalDate to) {
        store.put(new MasterCode(c.scope(), c.scheme(), c.segment(), c.code(), c.parentCode(), c.name(), c.flags(),
                c.validFrom(), to, c.sourceRef()));
    }

    public void require(String scope, ClassificationScheme s, Code c, LocalDate asOf) {
        List<Finding> f = validate(scope, s, c, asOf);
        if (Finding.anyError(f)) {
            throw new ConfigException("CLASSIFICATION_INVALID", "invalid code", Finding.errors(f));
        }
    }
}
