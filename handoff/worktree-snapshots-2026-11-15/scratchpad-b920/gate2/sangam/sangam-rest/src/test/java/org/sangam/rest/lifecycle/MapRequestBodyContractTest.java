// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.junit.jupiter.api.Test;

/**
 * M-051 (SL-145): request bodies that bind to {@code Map<String, Object>} (or to a JSON string the service parses) are
 * typed in {@code api/sangam-api.yaml} from the keys the service reads (gen_openapi.py, routes/_v2_bodykeys.py). Each such
 * schema lists the methods that read the body ({@code x-sangam-body-readers}: method, variable, declaration ordinal and
 * the nested object it holds) and the records it is converted to ({@code x-sangam-body-records}). This test re-reads those
 * methods from the source and fails when the keys read there and the schema's properties differ: a key added, renamed or
 * removed in the service without a regenerated contract, or a contract property no service reads. It also fails when a
 * JSON request body of the contract is an untyped open object.
 */
class MapRequestBodyContractTest {

    /** Reader functions of _v2_bodykeys.py (_BK_KINDS): f(map, "key", ...) reads "key". */
    static final Set<String> READERS = Set.of("str", "required", "req", "text", "string", "integer", "intOrNull", "intOr",
            "intVal", "lng", "decimal", "minor", "num", "dbl", "bool", "flag", "optBool", "boolVal", "date", "instant", "map",
            "maps", "strings", "asMap", "asList");
    /** map.get("key") and the other member reads of _v2_bodykeys.py (_BK_READS). */
    static final String MEMBER_READS = "get|getOrDefault|containsKey|remove|path|has|hasNonNull|putIfAbsent";
    static final String MAPLIKE = "(?:java\\.util\\.)?(?:Map|LinkedHashMap|HashMap|TreeMap|SortedMap)\\s*<[^;=(){}]*>|JsonNode|ObjectNode";
    static final String DECL_TYPE = "(?:final\\s+)?(" + MAPLIKE
            + "|(?:java\\.util\\.)?(?:List|Collection)\\s*<\\s*(?:Map\\s*<[^;=(){}]*>|JsonNode)\\s*>|var)";
    static final Pattern DECL = Pattern.compile("(for\\s*\\(\\s*)?" + DECL_TYPE + "\\s+(\\w+)\\s*([=:])");

    private final Map<String, String> masked = new HashMap<>();
    private final Map<String, String> sources = new HashMap<>();

    @Test
    @SuppressWarnings("unchecked")
    void everyMapBodyIsTypedExactlyFromTheKeysItsServiceReads() throws IOException, ClassNotFoundException {
        Map<String, Object> doc = AppContractDriftTest.read(Path.of("api", "sangam-api.yaml"));
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
        List<String> drift = new ArrayList<>();
        int bodies = 0;
        for (Map.Entry<String, Object> e : new TreeMap<>(schemas).entrySet()) {
            Map<String, Object> s = (Map<String, Object>) e.getValue();
            if (!(s.get("x-sangam-body-readers") instanceof List<?> readers)) {
                continue;
            }
            bodies++;
            Map<String, Set<String>> expected = new TreeMap<>();
            for (Object o : readers) {
                Map<String, Object> r = (Map<String, Object>) o;
                String node = String.valueOf(r.get("node"));
                int decl = r.get("declaration") instanceof Number n ? n.intValue() : 0;
                try {
                    keysRead(String.valueOf(r.get("method")), String.valueOf(r.get("var")), decl, node, expected);
                } catch (IllegalStateException ex) {
                    drift.add(e.getKey() + ": " + ex.getMessage());
                }
            }
            if (s.get("x-sangam-body-records") instanceof List<?> records) {
                for (Object o : records) {
                    Map<String, Object> r = (Map<String, Object>) o;
                    Set<String> names = expected.computeIfAbsent(String.valueOf(r.get("node")), k -> new TreeSet<>());
                    for (RecordComponent rc : Class.forName(String.valueOf(r.get("class"))).getRecordComponents()) {
                        names.add(jsonName(rc));
                    }
                }
            }
            for (Map.Entry<String, Set<String>> n : expected.entrySet()) {
                Map<String, Object> at = at(doc, s, n.getKey());
                Set<String> props = at != null && at.get("properties") instanceof Map<?, ?> p
                        ? new TreeSet<>((Set<String>) p.keySet()) : new TreeSet<>();
                if (!props.equals(n.getValue())) {
                    drift.add(e.getKey() + " node '" + n.getKey() + "': the service reads " + n.getValue()
                            + ", the contract has " + props);
                }
            }
        }
        assertTrue(bodies >= 121, "Map-backed request bodies found: " + bodies);
        assertEquals(List.of(), drift, "Map request bodies out of step with the keys their services read (" + drift.size() + ")");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noJsonRequestBodyIsAnUntypedOpenObject() throws IOException {
        Map<String, Object> doc = AppContractDriftTest.read(Path.of("api", "sangam-api.yaml"));
        List<String> open = new ArrayList<>();
        ((Map<String, Object>) doc.get("paths")).forEach((path, item) -> ((Map<String, Object>) item).forEach((method, op) -> {
            if (((Map<String, Object>) op).get("requestBody") instanceof Map<?, ?> rb && rb.get("content") instanceof Map<?, ?> c
                    && c.get("application/json") instanceof Map<?, ?> media && media.get("schema") instanceof Map<?, ?> schema) {
                Map<String, Object> s = AppContractDriftTest.resolve(doc, (Map<String, Object>) schema);
                if ("array".equals(s.get("type")) && s.get("items") instanceof Map<?, ?> items) {
                    s = AppContractDriftTest.resolve(doc, (Map<String, Object>) items);
                }
                if ("object".equals(s.get("type")) && Boolean.TRUE.equals(s.get("additionalProperties"))
                        && !(s.get("properties") instanceof Map<?, ?> p && !p.isEmpty())) {
                    open.add(method.toUpperCase() + " " + path);
                }
            }
        }));
        // The one exception: PartyController.saveIdentifierTypes binds a JSON array (List<Map>) while the 0.1.0 contract
        // says object; the type correction is a breaking change against the baseline and waits for an owner decision.
        assertEquals(List.of("PUT /registry/parties/config/identifier-types"), open,
                "JSON request bodies without a typed schema");
    }

    @Test
    void keysAreReadThroughAliasesScopesAndJsonPaths() {
        String src = """
                class T {
                    Object m(Map<String, Object> body) {
                        Maps.str(body, "a");
                        body.get("b");
                        unknown(body, "notAKey");
                        for (Map<String, Object> x : Maps.maps(body, "l1")) { Maps.str(x, "p"); }
                        for (Map<String, Object> x : Maps.maps(body, "l2")) { Maps.str(x, "q"); }
                        return req.path("params").path("name").asText();
                    }
                }
                """;
        String m = mask(src);
        Map<String, Set<String>> out = new TreeMap<>();
        readsIn(src, m, m.indexOf('{', m.indexOf("body)")) + 1, m.lastIndexOf('}', m.lastIndexOf('}') - 1), "body", 0, "", out);
        assertEquals(Map.of("", Set.of("a", "b", "l1", "l2")), out, "unknown(body, ...) is not a reader");
        out.clear();
        int b0 = m.indexOf('{', m.indexOf("body)")) + 1;
        int b1 = m.lastIndexOf('}', m.lastIndexOf('}') - 1);
        readsIn(src, m, b0, b1, "x", 2, "l2.[]", out);
        assertEquals(Map.of("l2.[]", Set.of("q")), out, "the second x is scoped to its own loop");
        out.clear();
        String js = "class J { void h(String s) { JsonNode req = parse(s); req.path(\"params\").path(\"name\").asText(); } }";
        String jm = mask(js);
        readsIn(js, jm, jm.indexOf('{', jm.indexOf("s)")) + 1, jm.lastIndexOf('}') - 1, "req", 1, "", out);
        assertEquals(Map.of("", Set.of("params"), "params", Set.of("name")), out);
    }

    // ------------------------------------------------------------------ source reading (mirrors _v2_bodykeys.py)

    private void keysRead(String reader, String var, int decl, String node, Map<String, Set<String>> out) throws IOException {
        String file = reader.substring(0, reader.indexOf('#'));
        String qual = reader.substring(reader.indexOf('#') + 1);
        String m = masked.computeIfAbsent(file, f -> mask(source(f)));
        String src = sources.get(file);
        String owner = qual.substring(0, qual.lastIndexOf('.'));
        String name = qual.substring(qual.lastIndexOf('.') + 1);
        int[] type = typeBlock(m, owner);
        if (type == null) {
            throw new IllegalStateException(reader + ": type " + owner + " not found");
        }
        boolean found = false;
        Matcher mm = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(m);
        mm.region(type[0], type[1]);
        while (mm.find()) {
            String before = m.substring(type[0], mm.start());
            if (count(before, '{') != count(before, '}') || before.stripTrailing().endsWith(".")) {
                continue;     // not a member of the type itself, or a call
            }
            int close = close(m, mm.end(), '(', ')');
            Matcher body = Pattern.compile("\\s*(?:throws\\s+[\\w.,\\s<>]+?)?\\s*\\{").matcher(m);
            body.region(close, m.length());
            if (!body.lookingAt()) {
                continue;
            }
            int b0 = body.end();
            int b1 = close(m, b0, '{', '}') - 1;
            String params = m.substring(mm.end(), close - 1);
            boolean declares;
            if (decl == 0) {     // the parameter, or (for a JSON string body) the local holding the parsed request
                Matcher local = Pattern.compile("(?:" + MAPLIKE + ")\\s+" + Pattern.quote(var) + "\\s*[=;]").matcher(m);
                local.region(b0, b1);
                declares = Pattern.compile("\\b" + Pattern.quote(var) + "\\s*$|\\b" + Pattern.quote(var) + "\\s*,")
                        .matcher(params).find() || local.find();
            } else {
                declares = declarationSites(m, b0, b1).stream().anyMatch(d -> d[0].equals(var));
            }
            if (!declares) {
                continue;
            }
            found = true;
            readsIn(src, m, b0, b1, var, decl, node, out);
        }
        if (!found) {
            throw new IllegalStateException(reader + "(" + var + "): method not found");
        }
    }

    /** Keys read on var (its decl-th declaration, 0 = the parameter) between b0 and b1, by node. */
    static void readsIn(String src, String m, int b0, int b1, String var, int decl, String node, Map<String, Set<String>> out) {
        int from = b0;
        int to = b1;
        if (decl > 0) {
            Object[] site = declarationSites(m, b0, b1).stream().filter(d -> d[0].equals(var) && (int) d[1] == decl)
                    .findFirst().orElseThrow(() -> new IllegalStateException(var + ": declaration " + decl + " not found"));
            from = (int) site[2];
            to = (int) site[3];
        }
        out.computeIfAbsent(node, k -> new TreeSet<>());
        Matcher f = Pattern.compile("\\b(\\w+)\\s*\\(\\s*" + Pattern.quote(var) + "\\s*,\\s*\"").matcher(m);
        f.region(from, to);
        while (f.find()) {
            if (READERS.contains(f.group(1)) && !m.substring(0, f.start(1)).stripTrailing().endsWith("new")) {
                out.get(node).add(literal(src, m, f.end() - 1));
            }
        }
        Matcher g = Pattern.compile("(?<![\\w.])" + Pattern.quote(var) + "\\s*\\.\\s*(" + MEMBER_READS + ")\\(\\s*\"").matcher(m);
        g.region(from, to);
        while (g.find()) {
            String key = literal(src, m, g.end() - 1);
            String how = g.group(1);
            String at = node;
            int end = close(m, m.indexOf('(', g.start() + var.length()) + 1, '(', ')');
            Matcher chain = Pattern.compile("\\s*\\.\\s*(path|get|has|hasNonNull)\\(\\s*\"").matcher(m);
            while (("path".equals(how) || "get".equals(how)) && chain.region(end, m.length()).lookingAt()) {
                out.computeIfAbsent(at, k -> new TreeSet<>()).add(key);
                at = at.isEmpty() ? key : at + "." + key;
                key = literal(src, m, chain.end() - 1);
                how = chain.group(1);
                end = close(m, m.indexOf('(', chain.start() + 1) + 1, '(', ')');
            }
            out.computeIfAbsent(at, k -> new TreeSet<>()).add(key);
        }
    }

    /** [name, ordinal, scope start, scope end] of the map, JSON-node and list-of-map locals (_bk_decl_sites). */
    static List<Object[]> declarationSites(String m, int a, int b) {
        List<Object[]> out = new ArrayList<>();
        Map<String, Integer> count = new HashMap<>();
        Matcher d = DECL.matcher(m);
        d.region(a, b);
        while (d.find()) {
            String name = d.group(3);
            int pos = d.start(3);
            int n = count.merge(name, 1, Integer::sum);
            int end;
            if (d.group(1) != null && ":".equals(d.group(4))) {
                int close = close(m, d.start() + d.group(0).indexOf('(') + 1, '(', ')');
                int nxt = close;
                while (Character.isWhitespace(m.charAt(nxt))) {
                    nxt++;
                }
                end = m.charAt(nxt) == '{' ? close(m, nxt + 1, '{', '}') : nxt + stmtEnd(m, nxt, b) + 1;
            } else {
                int depth = 0;
                int k = pos;
                while (k > a) {
                    k--;
                    char ch = m.charAt(k);
                    if (ch == '}') {
                        depth++;
                    } else if (ch == '{') {
                        if (depth == 0) {
                            break;
                        }
                        depth--;
                    }
                }
                end = k > a ? close(m, k + 1, '{', '}') - 1 : b;
            }
            out.add(new Object[] {name, n, pos, Math.min(end, b)});
        }
        return out;
    }

    private static int stmtEnd(String m, int from, int limit) {
        int depth = 0;
        for (int i = from; i < limit; i++) {
            char ch = m.charAt(i);
            if (ch == '(' || ch == '{' || ch == '[') {
                depth++;
            } else if (ch == ')' || ch == '}' || ch == ']') {
                if (depth == 0) {
                    return i - from;
                }
                depth--;
            } else if (ch == ';' && depth == 0) {
                return i - from;
            }
        }
        return limit - from;
    }

    /** [body start, body end] of the type Outer.Inner in the masked source. */
    private static int[] typeBlock(String m, String qualified) {
        int from = 0;
        int to = m.length();
        for (String simple : qualified.split("\\.")) {
            Matcher t = Pattern.compile("\\b(?:class|interface|record|enum)\\s+" + Pattern.quote(simple) + "\\b").matcher(m);
            t.region(from, to);
            if (!t.find()) {
                return null;
            }
            int i = t.end();
            int depth = 0;
            while (i < to && !(m.charAt(i) == '{' && depth == 0)) {
                char ch = m.charAt(i);
                if (ch == '(' || ch == '<') {
                    depth++;
                } else if (ch == ')' || ch == '>') {
                    depth--;
                }
                i++;
            }
            from = i + 1;
            to = close(m, i + 1, '{', '}') - 1;
        }
        return new int[] {from, to};
    }

    private String source(String rel) {
        try {
            String s = Files.readString(Path.of("..").resolve(rel), StandardCharsets.UTF_8);
            sources.put(rel, s);
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(rel + ": " + e.getMessage(), e);
        }
    }

    /** Comments and string/char contents blanked, same length (_bk_load). */
    static String mask(String s) {
        char[] out = s.toCharArray();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (s.startsWith("//", i)) {
                while (i < n && s.charAt(i) != '\n') {
                    out[i++] = ' ';
                }
            } else if (s.startsWith("/*", i)) {
                int j = s.indexOf("*/", i + 2) + 2;
                for (int k = i; k < j; k++) {
                    if (s.charAt(k) != '\n') {
                        out[k] = ' ';
                    }
                }
                i = j;
            } else if (s.startsWith("\"\"\"", i)) {
                int j = s.indexOf("\"\"\"", i + 3);
                while (s.charAt(j - 1) == '\\') {
                    j = s.indexOf("\"\"\"", j + 1);
                }
                for (int k = i + 3; k < j; k++) {
                    if (s.charAt(k) != '\n') {
                        out[k] = ' ';
                    }
                }
                i = j + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (s.charAt(j) != c) {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                for (int k = i + 1; k < j; k++) {
                    out[k] = ' ';
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static String literal(String src, String m, int quote) {
        return src.substring(quote + 1, m.indexOf('"', quote + 1));
    }

    private static int close(String m, int i, char open, char close) {
        int depth = 1;
        while (depth > 0) {
            char ch = m.charAt(i);
            if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
            }
            i++;
        }
        return i;
    }

    private static int count(String s, char c) {
        return (int) s.chars().filter(x -> x == c).count();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Map<String, Object> doc, Map<String, Object> schema, String node) {
        Map<String, Object> s = schema;
        if (node.isEmpty()) {
            return s;
        }
        for (String seg : node.split("\\.")) {
            s = AppContractDriftTest.resolve(doc, s);
            if ("[]".equals(seg)) {
                if (!(s.get("items") instanceof Map<?, ?> items)) {
                    return null;
                }
                s = (Map<String, Object>) items;
            } else {
                if (!(s.get("properties") instanceof Map<?, ?> p) || !(p.get(seg) instanceof Map<?, ?> next)) {
                    return null;
                }
                s = (Map<String, Object>) next;
            }
        }
        return AppContractDriftTest.resolve(doc, s);
    }

    private static String jsonName(RecordComponent rc) {
        JsonProperty jp = rc.getAnnotation(JsonProperty.class);
        if (jp == null) {
            jp = rc.getAccessor().getAnnotation(JsonProperty.class);
        }
        return jp != null && !jp.value().isEmpty() ? jp.value() : rc.getName();
    }
}
