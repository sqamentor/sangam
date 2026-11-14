// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RFC 7644 section 3.5.2 PATCH ({@code add}, {@code replace}, {@code remove}) applied to a copy of a resource's JSON.
 * The service applies the operations to the current representation and then stores the result through the same path
 * as a PUT, so every rule (decision 154 postings, uniqueness, member checks) holds for PATCH too.
 *
 * <p>Paths: {@code attr}, {@code attr.sub}, {@code attr[sub eq "v"]} and {@code attr[sub eq "v"].sub}, optionally
 * prefixed by a schema URN ({@code urn:...:2.0:User:name.givenName}, or the Sangam extension URN). Attribute names are
 * case-insensitive. A value filter supports one {@code eq} comparison of a string (other forms: 400 invalidFilter).
 */
public final class ScimPatch {

    public static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    /** Most operations one request may carry. */
    public static final int MAX_OPERATIONS = 1000;

    private static final List<String> CORE = List.of("urn:ietf:params:scim:schemas:core:2.0:user",
            "urn:ietf:params:scim:schemas:core:2.0:group");

    private ScimPatch() {
    }

    /** Parsed path: {@code container} is the extension URN or null for the core attributes. */
    record Path(String container, String attr, String filterAttr, String filterValue, String sub) {
    }

    /** Applies a PatchOp request to a deep copy of {@code resource} and returns the copy. */
    public static ObjectNode apply(ObjectNode resource, JsonNode request) {
        if (request == null || !request.isObject()) {
            throw ScimException.invalidSyntax("the PATCH body must be a PatchOp object");
        }
        if (!hasSchema(request, PATCH_OP)) {
            throw ScimException.invalidSyntax("schemas must contain " + PATCH_OP);
        }
        JsonNode ops = field(request, "Operations");
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            throw ScimException.invalidSyntax("Operations must be a non-empty array");
        }
        if (ops.size() > MAX_OPERATIONS) {
            throw new ScimException(413, "tooMany", "more than " + MAX_OPERATIONS + " operations");
        }
        ObjectNode target = resource.deepCopy();
        for (JsonNode op : ops) {
            JsonNode name = field(op, "op");
            if (name == null || !name.isTextual()) {
                throw ScimException.invalidSyntax("every operation needs op");
            }
            JsonNode pathNode = field(op, "path");
            String path = pathNode == null || pathNode.isNull() ? null : pathNode.asText();
            JsonNode value = field(op, "value");
            switch (name.asText().toLowerCase(Locale.ROOT)) {
                case "add" -> add(target, path, value);
                case "replace" -> replace(target, path, value);
                case "remove" -> remove(target, path);
                default -> throw ScimException.invalidSyntax("op '" + name.asText() + "' is not add, replace or remove");
            }
        }
        return target;
    }

    // ------------------------------------------------------------------ operations

    private static void add(ObjectNode target, String path, JsonNode value) {
        if (value == null || value.isNull()) {
            throw ScimException.invalidValue("add needs a value");
        }
        if (path == null) {
            mergeObject(target, value, true);
            return;
        }
        Path p = parse(path);
        if (p.filterAttr() != null) {
            throw ScimException.invalidPath("add does not take a value filter: " + path);
        }
        ObjectNode holder = holder(target, p, true);
        if (p.sub() != null) {
            ObjectNode complex = complex(holder, p.attr(), path);
            complex.set(key(complex, p.sub()), value);
            return;
        }
        String k = key(holder, p.attr());
        JsonNode current = holder.get(k);
        if (current != null && current.isArray()) {
            appendAll((ArrayNode) current, value);
        } else if (current != null && current.isObject() && value.isObject()) {
            mergeObject((ObjectNode) current, value, true);
        } else {
            holder.set(k, value);
        }
    }

    private static void replace(ObjectNode target, String path, JsonNode value) {
        if (value == null) {
            throw ScimException.invalidValue("replace needs a value");
        }
        if (path == null) {
            mergeObject(target, value, false);
            return;
        }
        Path p = parse(path);
        ObjectNode holder = holder(target, p, true);
        String k = key(holder, p.attr());
        if (p.filterAttr() == null) {
            if (p.sub() == null && holder.get(k) instanceof ObjectNode complex && value.isObject()) {
                mergeObject(complex, value, false);
            } else if (p.sub() == null) {
                holder.set(k, value);
            } else {
                ObjectNode complex = complex(holder, p.attr(), path);
                complex.set(key(complex, p.sub()), value);
            }
            return;
        }
        int hits = 0;
        for (ObjectNode element : matches(holder.get(k), p)) {
            hits++;
            if (p.sub() == null) {
                if (!value.isObject()) {
                    throw ScimException.invalidValue("replacing a filtered element needs an object value");
                }
                mergeObject(element, value, false);
            } else {
                element.set(key(element, p.sub()), value);
            }
        }
        if (hits == 0) {
            throw ScimException.noTarget("no element matches " + path);
        }
    }

    private static void remove(ObjectNode target, String path) {
        if (path == null) {
            throw ScimException.noTarget("remove needs a path");
        }
        Path p = parse(path);
        ObjectNode holder = holder(target, p, false);
        if (holder == null) {
            return;
        }
        String k = key(holder, p.attr());
        if (p.filterAttr() == null) {
            if (p.sub() == null) {
                holder.remove(k);
            } else if (holder.get(k) instanceof ObjectNode complex) {
                complex.remove(key(complex, p.sub()));
            }
            return;
        }
        JsonNode array = holder.get(k);
        List<ObjectNode> hit = matches(array, p);
        if (hit.isEmpty()) {
            throw ScimException.noTarget("no element matches " + path);
        }
        if (p.sub() == null) {
            Iterator<JsonNode> it = array.iterator();
            while (it.hasNext()) {
                if (hit.contains(it.next())) {
                    it.remove();
                }
            }
        } else {
            for (ObjectNode element : hit) {
                element.remove(key(element, p.sub()));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A no-path add or replace: each attribute of the value object, the extension objects included. */
    private static void mergeObject(ObjectNode target, JsonNode value, boolean addToArrays) {
        if (!value.isObject()) {
            throw ScimException.invalidValue("an operation without path needs an object value");
        }
        for (Map.Entry<String, JsonNode> e : value.properties()) {
            String k = key(target, e.getKey());
            JsonNode current = target.get(k);
            JsonNode v = e.getValue();
            if (current instanceof ArrayNode a && addToArrays) {
                appendAll(a, v);
            } else if (current instanceof ObjectNode o && v.isObject()) {
                // a complex attribute: the given sub-attributes are set, the others stay (RFC 7644 section 3.5.2.3)
                mergeObject(o, v, addToArrays);
            } else {
                target.set(k, v);
            }
        }
    }

    private static void appendAll(ArrayNode array, JsonNode value) {
        if (value.isArray()) {
            for (JsonNode v : value) {
                if (!contains(array, v)) {
                    array.add(v);
                }
            }
        } else if (!contains(array, value)) {
            array.add(value);
        }
    }

    private static boolean contains(ArrayNode array, JsonNode v) {
        for (JsonNode x : array) {
            if (x.equals(v)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode holder(ObjectNode target, Path p, boolean create) {
        if (p.container() == null) {
            return target;
        }
        String k = key(target, p.container());
        JsonNode ext = target.get(k);
        if (ext instanceof ObjectNode o) {
            return o;
        }
        return create ? target.putObject(k) : null;
    }

    private static ObjectNode complex(ObjectNode holder, String attr, String path) {
        String k = key(holder, attr);
        JsonNode n = holder.get(k);
        if (n == null || n.isNull()) {
            return holder.putObject(k);
        }
        if (!(n instanceof ObjectNode o)) {
            throw ScimException.invalidPath(path + ": " + attr + " is not a complex attribute");
        }
        return o;
    }

    private static List<ObjectNode> matches(JsonNode array, Path p) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        java.util.ArrayList<ObjectNode> out = new java.util.ArrayList<>();
        for (JsonNode element : array) {
            if (element instanceof ObjectNode o) {
                JsonNode v = o.get(key(o, p.filterAttr()));
                if (v != null && !v.isContainerNode() && p.filterValue().equals(v.asText())) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    /** The existing key that equals {@code name} ignoring case, else {@code name}. */
    static String key(ObjectNode node, String name) {
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            if (k.equalsIgnoreCase(name)) {
                return k;
            }
        }
        return name;
    }

    static JsonNode field(JsonNode node, String name) {
        return node instanceof ObjectNode o ? o.get(key(o, name)) : null;
    }

    static boolean hasSchema(JsonNode body, String urn) {
        JsonNode s = field(body, "schemas");
        if (s != null && s.isArray()) {
            for (JsonNode x : s) {
                if (urn.equalsIgnoreCase(x.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    static Path parse(String path) {
        String rest = path.trim();
        if (rest.isEmpty()) {
            throw ScimException.invalidPath("empty path");
        }
        String container = null;
        int bracket = rest.indexOf('[');
        int colon = (bracket < 0 ? rest : rest.substring(0, bracket)).lastIndexOf(':');
        if (colon >= 0) {
            String urn = rest.substring(0, colon);
            rest = rest.substring(colon + 1);
            if (!CORE.contains(urn.toLowerCase(Locale.ROOT))) {
                container = urn;
            }
            bracket = rest.indexOf('[');
        }
        String attr;
        String filterAttr = null;
        String filterValue = null;
        String after;
        if (bracket >= 0) {
            int close = rest.lastIndexOf(']');
            if (close < bracket) {
                throw ScimException.invalidPath("unbalanced brackets in " + path);
            }
            attr = rest.substring(0, bracket);
            List<ScimFilter.Clause> f = valueFilter(rest.substring(bracket + 1, close), path);
            filterAttr = f.isEmpty() ? null : rawAttr(rest.substring(bracket + 1, close));
            filterValue = f.isEmpty() ? null : f.get(0).value();
            after = rest.substring(close + 1);
        } else {
            int dot = rest.indexOf('.');
            attr = dot < 0 ? rest : rest.substring(0, dot);
            after = dot < 0 ? "" : rest.substring(dot);
        }
        String sub = null;
        if (!after.isEmpty()) {
            if (!after.startsWith(".") || after.length() < 2 || after.indexOf('.', 1) >= 0) {
                throw ScimException.invalidPath("invalid sub-attribute in " + path);
            }
            sub = after.substring(1);
        }
        if (attr.isEmpty() || !attr.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '$')) {
            throw ScimException.invalidPath("invalid attribute in " + path);
        }
        return new Path(container, attr, filterAttr, filterValue, sub);
    }

    /** {@code sub eq "value"}: one comparison, eq only, any sub-attribute name. */
    private static List<ScimFilter.Clause> valueFilter(String text, String path) {
        String t = text.trim();
        int sp = t.indexOf(' ');
        if (sp <= 0) {
            throw ScimException.invalidFilter("value filter must be: attribute eq \"value\" (" + path + ")");
        }
        String rest = t.substring(sp).trim();
        if (!rest.regionMatches(true, 0, "eq ", 0, 3)) {
            throw ScimException.invalidFilter("value filters support eq only (" + path + ")");
        }
        // reuse the string-literal rules of the list filter on a known attribute, then keep the value only
        List<ScimFilter.Clause> parsed = ScimFilter.parse("userName " + rest);
        if (parsed.size() != 1) {
            throw ScimException.invalidFilter("value filters support one comparison only (" + path + ")");
        }
        return parsed;
    }

    private static String rawAttr(String filterText) {
        String t = filterText.trim();
        return t.substring(0, t.indexOf(' '));
    }
}
