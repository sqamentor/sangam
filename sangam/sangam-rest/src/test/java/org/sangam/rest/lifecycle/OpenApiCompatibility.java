// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SL-38 / SL-46: breaking-change check between a released OpenAPI document and the generated one
 * (API-LIFECYCLE-POLICY.md section 3). Reported as breaking:
 * <ul>
 *   <li>a removed path or operation, unless the registry retired it (sunset date reached);</li>
 *   <li>a removed parameter, a new required parameter, an optional parameter made required, a request body made
 *       required;</li>
 *   <li>a removed success response;</li>
 *   <li>schema changes: type or format changed, request enum values removed, request min/max tightened, a new required
 *       request property, a removed property, a response property no longer required.</li>
 * </ul>
 * Path templates are compared with variable names erased, so renaming {@code {id}} is not a client-visible change.
 */
final class OpenApiCompatibility {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    private final Map<String, Object> base;
    private final Map<String, Object> cur;
    private final ApiDeprecationRegistry registry;
    private final LocalDate today;
    private final List<String> breaking = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();

    private OpenApiCompatibility(Map<String, Object> base, Map<String, Object> cur, ApiDeprecationRegistry registry,
                                 LocalDate today) {
        this.base = base;
        this.cur = cur;
        this.registry = registry;
        this.today = today;
    }

    static List<String> breakingChanges(Map<String, Object> baseline, Map<String, Object> current,
                                        ApiDeprecationRegistry registry, LocalDate today) {
        OpenApiCompatibility c = new OpenApiCompatibility(baseline, current, registry, today);
        c.run();
        return c.breaking;
    }

    private void run() {
        Map<String, Map<String, Object>> curPaths = byShape(map(cur.get("paths")));
        Map<String, Map<String, Object>> basePaths = byShape(map(base.get("paths")));
        for (var bp : basePaths.entrySet()) {
            Map<String, Object> cp = curPaths.get(bp.getKey());
            for (var op : bp.getValue().entrySet()) {
                String method = op.getKey();
                if (!METHODS.contains(method)) {
                    continue;
                }
                String where = method.toUpperCase() + " " + bp.getValue().get("$path");
                Object curOp = cp == null ? null : cp.get(method);
                if (curOp == null) {
                    if (!retired(method, (String) bp.getValue().get("$path"))) {
                        breaking.add(where + ": operation removed without a sunset in the deprecation registry");
                    }
                    continue;
                }
                operation(where, map(op.getValue()), map(curOp), map(bp.getValue().get("parameters")),
                        map(cp.get("parameters")));
            }
        }
    }

    private boolean retired(String method, String path) {
        return registry.entries().stream().anyMatch(e -> e.method().equalsIgnoreCase(method)
                && shape(e.path()).equals(shape(path)) && !today.isBefore(e.sunset()));
    }

    @SuppressWarnings("unchecked")
    private void operation(String where, Map<String, Object> b, Map<String, Object> c, Map<String, Object> bPathLevel,
                           Map<String, Object> cPathLevel) {
        Map<String, Map<String, Object>> bp = params(base, (List<Object>) b.get("parameters"));
        Map<String, Map<String, Object>> cp = params(cur, (List<Object>) c.get("parameters"));
        for (var e : bp.entrySet()) {
            Map<String, Object> cParam = cp.get(e.getKey());
            if (cParam == null) {
                breaking.add(where + ": parameter " + e.getKey() + " removed");
                continue;
            }
            if (truthy(cParam.get("required")) && !truthy(e.getValue().get("required"))) {
                breaking.add(where + ": parameter " + e.getKey() + " became required");
            }
            schema(where + " parameter " + e.getKey(), e.getValue().get("schema"), cParam.get("schema"), true);
        }
        for (var e : cp.entrySet()) {
            if (!bp.containsKey(e.getKey()) && truthy(e.getValue().get("required"))) {
                breaking.add(where + ": new required parameter " + e.getKey());
            }
        }
        Map<String, Object> bBody = deref(base, map(b.get("requestBody")));
        Map<String, Object> cBody = deref(cur, map(c.get("requestBody")));
        if (!cBody.isEmpty() && truthy(cBody.get("required")) && !truthy(bBody.get("required"))) {
            breaking.add(where + ": request body became required");
        }
        content(where + " request", map(bBody.get("content")), map(cBody.get("content")), true);
        Map<String, Object> bResp = map(b.get("responses"));
        Map<String, Object> cResp = map(c.get("responses"));
        for (var e : bResp.entrySet()) {
            if (!e.getKey().startsWith("2")) {
                continue;
            }
            Object cr = cResp.get(e.getKey());
            if (cr == null) {
                breaking.add(where + ": success response " + e.getKey() + " removed");
                continue;
            }
            content(where + " response " + e.getKey(), map(deref(base, map(e.getValue())).get("content")),
                    map(deref(cur, map(cr)).get("content")), false);
        }
    }

    private void content(String where, Map<String, Object> b, Map<String, Object> c, boolean request) {
        for (var e : b.entrySet()) {
            Object cm = c.get(e.getKey());
            if (cm == null) {
                if (!request) {
                    breaking.add(where + ": media type " + e.getKey() + " removed");
                } else {
                    breaking.add(where + ": request media type " + e.getKey() + " no longer accepted");
                }
                continue;
            }
            schema(where + " " + e.getKey(), map(e.getValue()).get("schema"), map(cm).get("schema"), request);
        }
    }

    @SuppressWarnings("unchecked")
    private void schema(String where, Object bRaw, Object cRaw, boolean request) {
        if (!(bRaw instanceof Map<?, ?>) || !(cRaw instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> b0 = (Map<String, Object>) bRaw;
        Map<String, Object> c0 = (Map<String, Object>) cRaw;
        String key = request + "|" + b0.get("$ref") + "|" + c0.get("$ref");
        if (b0.containsKey("$ref") && c0.containsKey("$ref") && !visited.add(key)) {
            return;
        }
        Map<String, Object> b = deref(base, b0);
        Map<String, Object> c = deref(cur, c0);
        Set<String> bt = types(b);
        Set<String> ct = types(c);
        if (!bt.isEmpty() && !ct.isEmpty() && !bt.equals(ct)) {
            boolean widenedRequest = request && ct.containsAll(bt)
                    || request && bt.equals(Set.of("integer")) && ct.contains("number");
            if (!widenedRequest) {
                breaking.add(where + ": type changed " + bt + " -> " + ct);
            }
        }
        if (b.get("format") != null && c.get("format") != null && !Objects.equals(b.get("format"), c.get("format"))) {
            breaking.add(where + ": format changed " + b.get("format") + " -> " + c.get("format"));
        }
        if (request) {
            if (b.get("enum") instanceof List<?> be) {
                Set<Object> removed = new LinkedHashSet<>(be);
                if (c.get("enum") instanceof List<?> ce) {
                    removed.removeAll(ce);
                } else {
                    removed.clear();
                }
                if (!removed.isEmpty()) {
                    breaking.add(where + ": enum values no longer accepted " + removed);
                }
            } else if (c.get("enum") != null && !bt.isEmpty()) {
                breaking.add(where + ": enum restriction added");
            }
            tightened(where, b, c, "maxLength", true);
            tightened(where, b, c, "maximum", true);
            tightened(where, b, c, "maxItems", true);
            tightened(where, b, c, "minLength", false);
            tightened(where, b, c, "minimum", false);
            tightened(where, b, c, "minItems", false);
        }
        Set<String> br = new LinkedHashSet<>(strings(b.get("required")));
        Set<String> cr = new LinkedHashSet<>(strings(c.get("required")));
        if (request) {
            cr.removeAll(br);
            if (!cr.isEmpty()) {
                breaking.add(where + ": new required request properties " + cr);
            }
        } else {
            br.removeAll(cr);
            if (!br.isEmpty()) {
                breaking.add(where + ": response properties no longer required " + br);
            }
        }
        Map<String, Object> bProps = map(b.get("properties"));
        Map<String, Object> cProps = map(c.get("properties"));
        for (var e : bProps.entrySet()) {
            if (!cProps.containsKey(e.getKey())) {
                if (!cProps.isEmpty() || !request) {
                    breaking.add(where + ": property " + e.getKey() + " removed");
                }
                continue;
            }
            schema(where + "." + e.getKey(), e.getValue(), cProps.get(e.getKey()), request);
        }
        schema(where + "[]", b.get("items"), c.get("items"), request);
        if (b.get("additionalProperties") instanceof Map<?, ?> ba && c.get("additionalProperties") instanceof Map<?, ?> ca) {
            schema(where + "{}", ba, ca, request);
        }
        for (String comb : List.of("allOf", "anyOf", "oneOf")) {
            if (b.get(comb) instanceof List<?> bl && c.get(comb) instanceof List<?> cl && bl.size() == cl.size()) {
                for (int i = 0; i < bl.size(); i++) {
                    schema(where + "/" + comb + i, bl.get(i), cl.get(i), request);
                }
            }
        }
    }

    private void tightened(String where, Map<String, Object> b, Map<String, Object> c, String kw, boolean upper) {
        Object cv = c.get(kw);
        if (cv == null) {
            return;
        }
        Object bv = b.get(kw);
        double cd = ((Number) cv).doubleValue();
        if (bv == null || (upper ? cd < ((Number) bv).doubleValue() : cd > ((Number) bv).doubleValue())) {
            breaking.add(where + ": " + kw + " tightened " + bv + " -> " + cv);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Map<String, Object>> byShape(Map<String, Object> paths) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        paths.forEach((p, v) -> {
            Map<String, Object> m = new LinkedHashMap<>(map(v));
            m.put("$path", p);
            out.put(shape(p), m);
        });
        return out;
    }

    static String shape(String path) {
        return ApiDeprecationRegistry.stripBase(path).replaceAll("\\{[^}]*}", "{}");
    }

    private Map<String, Map<String, Object>> params(Map<String, Object> doc, List<Object> list) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (list == null) {
            return out;
        }
        int pathIndex = 0;
        for (Object o : list) {
            Map<String, Object> p = deref(doc, map(o));
            String in = String.valueOf(p.get("in"));
            String key = in.equals("path") ? "path#" + pathIndex++
                    : in + ":" + (in.equals("header") ? String.valueOf(p.get("name")).toLowerCase() : p.get("name"));
            out.put(key, p);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deref(Map<String, Object> doc, Map<String, Object> node) {
        Map<String, Object> n = node;
        for (int guard = 0; guard < 16 && n.get("$ref") instanceof String ref && ref.startsWith("#/"); guard++) {
            Object at = doc;
            for (String part : ref.substring(2).split("/")) {
                at = at instanceof Map<?, ?> m ? m.get(part.replace("~1", "/").replace("~0", "~")) : null;
            }
            if (!(at instanceof Map<?, ?>)) {
                throw new IllegalStateException("unresolved $ref " + ref);
            }
            n = (Map<String, Object>) at;
        }
        return n;
    }

    private static Set<String> types(Map<String, Object> s) {
        Object t = s.get("type");
        Set<String> out = new LinkedHashSet<>();
        if (t instanceof String str) {
            out.add(str);
        } else if (t instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    private static boolean truthy(Object o) {
        return Boolean.TRUE.equals(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
