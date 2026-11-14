// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.diff;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import org.sangam.modeller.diff.ModelDiff.Change;
import org.sangam.modeller.diff.ModelDiff.Kind;
import org.sangam.modeller.diff.ModelDiff.Level;

/**
 * The JSON side of {@link ModelDiff}: Sangam form definitions ({@code *.form.json}: {@code fields[]} with {@code id}) and
 * component-tree forms ({@code components[]} with {@code id} or {@code key}). An array whose items are all objects with
 * a unique {@code id} (else a unique {@code key}) is matched by that identity, so a moved field is an order change, not
 * a removal and an addition; any other array is compared by position. A matched item is an element of the report; a
 * value that is an expression ({@code =...} FEEL, or a property named {@code condition}, {@code conditional},
 * {@code hide}, {@code visibleIf}, {@code expression}, {@code calculate}) is an {@link Level#EXPRESSION} change.
 */
final class FormJsonDiff {

    static final String FORM_KEY = "(form)";
    static final Set<String> EXPRESSION_KEYS = Set.of("condition", "conditional", "hide", "visibleIf", "expression",
            "calculate");

    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(200).build());
    }

    private FormJsonDiff() {
    }

    /** The element a change belongs to: its id, type and display name. */
    private record Owner(String id, String type, String name) {
    }

    static List<Change> compare(byte[] before, byte[] after) {
        JsonNode a = read(before);
        JsonNode b = read(after);
        List<Change> out = new ArrayList<>();
        Owner root = new Owner(FORM_KEY, "form", text(b.isMissingNode() ? a : b, "key"));
        if (a.isMissingNode() || b.isMissingNode()) {
            out.add(new Change(Level.ELEMENT, a.isMissingNode() ? Kind.ADDED : Kind.REMOVED, FORM_KEY, "form",
                    root.name(), "", null, null));
            return out;
        }
        walk(root, "", a, b, out);
        return out;
    }

    private static void walk(Owner owner, String path, JsonNode a, JsonNode b, List<Change> out) {
        if (a.isObject() && b.isObject()) {
            Set<String> keys = new TreeSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                String p = path.isEmpty() ? k : path + "." + k;
                JsonNode x = a.path(k);
                JsonNode y = b.path(k);
                if (x.isMissingNode() || y.isMissingNode()) {
                    out.add(new Change(level(k, x.isMissingNode() ? y : x), x.isMissingNode() ? Kind.ADDED : Kind.REMOVED,
                            owner.id(), owner.type(), owner.name(), p, value(x), value(y)));
                } else {
                    walk(owner, p, x, y, out, k);
                }
            }
            return;
        }
        if (a.isArray() && b.isArray()) {
            String identity = identity(a, b);
            if (identity != null) {
                byIdentity(owner, path, identity, a, b, out);
            } else {
                byPosition(owner, path, a, b, out);
            }
            return;
        }
        if (!a.equals(b)) {
            out.add(new Change(level(lastKey(path), b), Kind.CHANGED, owner.id(), owner.type(), owner.name(), path,
                    value(a), value(b)));
        }
    }

    private static void walk(Owner owner, String path, JsonNode a, JsonNode b, List<Change> out, String key) {
        if (EXPRESSION_KEYS.contains(key) && !a.equals(b) && !(a.isArray() && b.isArray())) {
            // a structured expression (form-js "conditional": {"hide": ...}) is reported as one expression change
            if (!a.isObject() || !b.isObject()) {
                out.add(new Change(Level.EXPRESSION, Kind.CHANGED, owner.id(), owner.type(), owner.name(), path,
                        value(a), value(b)));
                return;
            }
        }
        walk(owner, path, a, b, out);
    }

    private static void byIdentity(Owner owner, String path, String identity, JsonNode a, JsonNode b, List<Change> out) {
        Map<String, JsonNode> x = index(a, identity);
        Map<String, JsonNode> y = index(b, identity);
        for (Map.Entry<String, JsonNode> e : x.entrySet()) {
            if (!y.containsKey(e.getKey())) {
                out.add(new Change(Level.ELEMENT, Kind.REMOVED, e.getKey(), text(e.getValue(), "type"),
                        name(e.getValue()), "", null, null));
            }
        }
        for (Map.Entry<String, JsonNode> e : y.entrySet()) {
            if (!x.containsKey(e.getKey())) {
                out.add(new Change(Level.ELEMENT, Kind.ADDED, e.getKey(), text(e.getValue(), "type"),
                        name(e.getValue()), "", null, null));
            }
        }
        for (Map.Entry<String, JsonNode> e : x.entrySet()) {
            JsonNode now = y.get(e.getKey());
            if (now == null) {
                continue;
            }
            Owner item = new Owner(e.getKey(), text(now, "type").isEmpty() ? text(e.getValue(), "type") : text(now, "type"),
                    name(now).isEmpty() ? name(e.getValue()) : name(now));
            String oldType = text(e.getValue(), "type");
            String newType = text(now, "type");
            if (!oldType.equals(newType)) {
                out.add(new Change(Level.ELEMENT, Kind.CHANGED, item.id(), newType, item.name(), "#type", oldType, newType));
            }
            walkItem(item, e.getValue(), now, out);
        }
        Set<String> common = new LinkedHashSet<>(x.keySet());
        common.retainAll(y.keySet());
        List<String> before = x.keySet().stream().filter(common::contains).toList();
        List<String> after = y.keySet().stream().filter(common::contains).toList();
        if (!before.equals(after)) {
            out.add(new Change(Level.PROPERTY, Kind.CHANGED, owner.id(), owner.type(), owner.name(),
                    (path.isEmpty() ? "" : path + ".") + "#order", String.join(",", before), String.join(",", after)));
        }
    }

    /** Compares the properties of one matched item except its identity and type (reported separately). */
    private static void walkItem(Owner item, JsonNode a, JsonNode b, List<Change> out) {
        Set<String> keys = new TreeSet<>();
        a.fieldNames().forEachRemaining(keys::add);
        b.fieldNames().forEachRemaining(keys::add);
        keys.remove("type");
        for (String k : keys) {
            JsonNode x = a.path(k);
            JsonNode y = b.path(k);
            if (x.isMissingNode() || y.isMissingNode()) {
                out.add(new Change(level(k, x.isMissingNode() ? y : x), x.isMissingNode() ? Kind.ADDED : Kind.REMOVED,
                        item.id(), item.type(), item.name(), k, value(x), value(y)));
            } else {
                walk(item, k, x, y, out, k);
            }
        }
    }

    private static void byPosition(Owner owner, String path, JsonNode a, JsonNode b, List<Change> out) {
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            String p = path + "[" + i + "]";
            JsonNode x = a.path(i);
            JsonNode y = b.path(i);
            if (x.isMissingNode() || y.isMissingNode()) {
                out.add(new Change(level(lastKey(path), x.isMissingNode() ? y : x),
                        x.isMissingNode() ? Kind.ADDED : Kind.REMOVED, owner.id(), owner.type(), owner.name(), p,
                        value(x), value(y)));
            } else {
                walk(owner, p, x, y, out);
            }
        }
    }

    /** {@code id} or {@code key} when every item of both arrays is an object with a unique textual value of it. */
    static String identity(JsonNode a, JsonNode b) {
        for (String field : new String[] {"id", "key"}) {
            if (unique(a, field) && unique(b, field) && (!a.isEmpty() || !b.isEmpty())) {
                return field;
            }
        }
        return null;
    }

    private static boolean unique(JsonNode array, String field) {
        Set<String> seen = new HashSet<>();
        for (JsonNode n : array) {
            if (!n.isObject() || !n.path(field).isTextual() || !seen.add(n.get(field).asText())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, JsonNode> index(JsonNode array, String field) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (Iterator<JsonNode> it = array.elements(); it.hasNext();) {
            JsonNode n = it.next();
            m.put(n.get(field).asText(), n);
        }
        return m;
    }

    static Level level(String key, JsonNode value) {
        if (key != null && EXPRESSION_KEYS.contains(key)) {
            return Level.EXPRESSION;
        }
        if (value != null && value.isTextual() && value.asText().startsWith("=")) {
            return Level.EXPRESSION;
        }
        return Level.PROPERTY;
    }

    private static String lastKey(String path) {
        String p = path.replaceAll("\\[\\d+]$", "");
        int i = p.lastIndexOf('.');
        return i < 0 ? p : p.substring(i + 1);
    }

    /** A form component's display name: its label (the English one of a translated label) or title. */
    private static String name(JsonNode n) {
        for (String f : new String[] {"label", "title", "text"}) {
            JsonNode v = n.path(f);
            if (v.isTextual()) {
                return v.asText();
            }
            if (v.isObject() && v.path("en").isTextual()) {
                return v.get("en").asText();
            }
        }
        return "";
    }

    private static String text(JsonNode n, String field) {
        return n != null && n.path(field).isTextual() ? n.get(field).asText() : "";
    }

    private static String value(JsonNode n) {
        if (n == null || n.isMissingNode()) {
            return null;
        }
        return n.isTextual() ? n.asText() : n.toString();
    }

    private static JsonNode read(byte[] bytes) {
        if (!ModelDiff.nonEmpty(bytes)) {
            return MissingNode.getInstance();
        }
        try {
            JsonNode n = JSON.readTree(bytes);
            if (n == null || !n.isObject()) {
                throw new IllegalArgumentException("a form definition must be a JSON object");
            }
            return n;
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read the form as JSON: " + e.getMessage(), e);
        }
    }
}
