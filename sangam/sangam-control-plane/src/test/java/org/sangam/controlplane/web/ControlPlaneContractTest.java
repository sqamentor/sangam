// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * M-017: {@code sangam-rest/api/sangam-control-plane-api.yaml} (generated from {@code api/apps/controlplane.py}) and
 * {@link ControlPlaneController} cannot drift. Every mapping is an operation and every operation a mapping; path and
 * query parameters (names and required flags) match; request bodies and record responses have exactly the record's
 * components, with matching JSON types, recursively.
 */
class ControlPlaneContractTest {

    private static final Path CONTRACT = Path.of("..", "sangam-rest", "api", "sangam-control-plane-api.yaml");

    @Test
    @SuppressWarnings("unchecked")
    void contractAndControllerDescribeTheSameApi() throws IOException {
        Map<String, Object> doc = read(CONTRACT);
        String base = ControlPlaneController.class.getAnnotation(RequestMapping.class).value()[0];
        assertEquals(base, ((Map<String, Object>) ((List<Object>) doc.get("servers")).get(0)).get("url"), "base path");

        Map<String, Map<String, Object>> contract = new TreeMap<>();
        ((Map<String, Object>) doc.get("paths")).forEach((path, item) -> ((Map<String, Object>) item)
                .forEach((method, op) -> contract.put(method.toUpperCase() + " " + path, (Map<String, Object>) op)));
        Map<String, Method> code = new TreeMap<>();
        for (Method m : ControlPlaneController.class.getDeclaredMethods()) {
            String key = mapping(m);
            if (key != null) {
                code.put(key, m);
            }
        }
        assertEquals(new TreeSet<>(code.keySet()), new TreeSet<>(contract.keySet()), "control-plane operations");

        List<String> drift = new ArrayList<>();
        code.forEach((key, m) -> check(doc, key, m, contract.get(key), drift));
        assertEquals(List.of(), drift, "contract drift (" + drift.size() + ")");
    }

    @SuppressWarnings("unchecked")
    private static void check(Map<String, Object> doc, String key, Method m, Map<String, Object> op, List<String> drift) {
        Map<String, Boolean> pathVars = new TreeMap<>();
        Map<String, Boolean> query = new TreeMap<>();
        for (Parameter p : m.getParameters()) {
            PathVariable pv = p.getAnnotation(PathVariable.class);
            if (pv != null) {
                pathVars.put(name(pv.value(), p), true);
            }
            RequestParam rp = p.getAnnotation(RequestParam.class);
            if (rp != null) {
                query.put(name(rp.value(), p), rp.required() && ValueConstants.DEFAULT_NONE.equals(rp.defaultValue()));
            }
            if (p.getAnnotation(RequestBody.class) != null) {
                Map<String, Object> body = (Map<String, Object>) op.get("requestBody");
                if (body == null) {
                    drift.add(key + ": the handler takes a body, the contract has none");
                    continue;
                }
                Map<String, Object> media = (Map<String, Object>) ((Map<String, Object>) body.get("content")).get("application/json");
                compare(doc, key + " request", (Map<String, Object>) media.get("schema"), p.getParameterizedType(), drift);
            }
        }
        Map<String, Boolean> cPath = new TreeMap<>();
        Map<String, Boolean> cQuery = new TreeMap<>();
        for (Object o : (List<Object>) op.getOrDefault("parameters", List.of())) {
            Map<String, Object> p = (Map<String, Object>) o;
            ("path".equals(p.get("in")) ? cPath : cQuery).put((String) p.get("name"), Boolean.TRUE.equals(p.get("required")));
        }
        if (!pathVars.equals(cPath)) {
            drift.add(key + ": path parameters " + pathVars + " vs contract " + cPath);
        }
        if (!query.equals(cQuery)) {
            drift.add(key + ": query parameters (name=required) " + query + " vs contract " + cQuery);
        }

        Type ret = m.getGenericReturnType();
        if (ret instanceof ParameterizedType pt && pt.getRawType() == ResponseEntity.class) {
            ret = pt.getActualTypeArguments()[0];
        }
        if (ret == Void.class || ret == void.class) {
            return;
        }
        Map<String, Object> success = null;
        for (var r : ((Map<String, Object>) op.get("responses")).entrySet()) {
            if (r.getKey().startsWith("2") && r.getValue() instanceof Map<?, ?> resp && resp.get("content") instanceof Map<?, ?> c) {
                success = (Map<String, Object>) ((Map<String, Object>) c.get("application/json")).get("schema");
            }
        }
        if (success == null) {
            drift.add(key + ": the handler returns " + ret.getTypeName() + ", the contract has no response body");
            return;
        }
        compare(doc, key + " response", success, ret, drift);
    }

    /** JSON type of a Java type, recursively through records and lists; maps are open objects. */
    @SuppressWarnings("unchecked")
    private static void compare(Map<String, Object> doc, String where, Map<String, Object> schema, Type type, List<String> drift) {
        Map<String, Object> s = resolve(doc, schema);
        Class<?> raw = type instanceof ParameterizedType pt ? (Class<?>) pt.getRawType() : (Class<?>) type;
        if (List.class.isAssignableFrom(raw)) {
            if (!"array".equals(s.get("type"))) {
                drift.add(where + ": " + type.getTypeName() + " but contract type " + s.get("type"));
                return;
            }
            compare(doc, where + "[]", (Map<String, Object>) s.get("items"),
                    ((ParameterizedType) type).getActualTypeArguments()[0], drift);
            return;
        }
        if (Map.class.isAssignableFrom(raw)) {
            if (!"object".equals(s.get("type"))) {
                drift.add(where + ": a map but contract type " + s.get("type"));
            }
            return;
        }
        if (raw.isRecord()) {
            Map<String, RecordComponent> components = new LinkedHashMap<>();
            for (RecordComponent rc : raw.getRecordComponents()) {
                components.put(rc.getName(), rc);
            }
            Map<String, Object> props = (Map<String, Object>) s.getOrDefault("properties", Map.of());
            if (!new TreeSet<>(components.keySet()).equals(new TreeSet<>(props.keySet()))) {
                drift.add(where + ": " + raw.getSimpleName() + " components " + new TreeSet<>(components.keySet())
                        + " vs contract properties " + new TreeSet<>(props.keySet()));
                return;
            }
            components.forEach((n, rc) -> compare(doc, where + "." + n, (Map<String, Object>) props.get(n),
                    rc.getGenericType(), drift));
            return;
        }
        String[] expected = scalar(raw);
        if (expected == null) {
            fail(where + ": no JSON mapping for " + raw);
        }
        if (!expected[0].equals(s.get("type")) || expected[1] != null && !expected[1].equals(s.get("format"))) {
            drift.add(where + ": " + raw.getSimpleName() + " is " + String.join("/", expected[0], String.valueOf(expected[1]))
                    + " but contract has " + s.get("type") + "/" + s.get("format"));
        }
    }

    private static String[] scalar(Class<?> c) {
        if (c == String.class) {
            return new String[] {"string", null};
        }
        if (c == int.class || c == Integer.class) {
            return new String[] {"integer", "int32"};
        }
        if (c == long.class || c == Long.class) {
            return new String[] {"integer", "int64"};
        }
        if (c == double.class || c == Double.class) {
            return new String[] {"number", "double"};
        }
        if (c == boolean.class || c == Boolean.class) {
            return new String[] {"boolean", null};
        }
        return null;
    }

    private static String mapping(Method m) {
        for (Annotation a : m.getAnnotations()) {
            String verb = switch (a) {
                case GetMapping g -> "GET " + first(g.value(), g.path());
                case PostMapping p -> "POST " + first(p.value(), p.path());
                case PutMapping p -> "PUT " + first(p.value(), p.path());
                case DeleteMapping d -> "DELETE " + first(d.value(), d.path());
                case PatchMapping p -> "PATCH " + first(p.value(), p.path());
                default -> null;
            };
            if (verb != null) {
                return verb;
            }
        }
        return null;
    }

    private static String first(String[] value, String[] path) {
        String[] v = value.length > 0 ? value : path;
        return v.length > 0 ? v[0] : "";
    }

    private static String name(String declared, Parameter p) {
        assertNotNull(p.getName());
        return declared == null || declared.isEmpty() ? p.getName() : declared;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolve(Map<String, Object> doc, Map<String, Object> s) {
        if (s != null && s.get("$ref") instanceof String ref) {
            Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
            return (Map<String, Object>) schemas.get(ref.substring(ref.lastIndexOf('/') + 1));
        }
        return s == null ? Map.of() : s;
    }

    private static Map<String, Object> read(Path p) throws IOException {
        LoaderOptions o = new LoaderOptions();
        o.setCodePointLimit(64 * 1024 * 1024);
        try (InputStream in = Files.newInputStream(p)) {
            return new Yaml(new SafeConstructor(o)).load(in);
        }
    }
}
