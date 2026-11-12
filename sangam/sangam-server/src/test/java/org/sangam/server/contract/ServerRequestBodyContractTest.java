// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * M-051 (SL-145): the record request bodies of the sangam-server controllers (onboarding, promotion, pack lifecycle, cell
 * tenant admin, security defaults) are typed in {@code sangam-rest/api/sangam-api.yaml} exactly as their records: the
 * same JSON member names, recursively through nested records and lists, and compatible JSON types. These controllers
 * are not on sangam-rest's test class path, so RequestBodyContractTest (sangam-rest) cannot see them; a record change
 * without a matching contract change fails here.
 */
class ServerRequestBodyContractTest {

    private static final Path CONTRACT = Path.of("..", "sangam-rest", "api", "sangam-api.yaml");
    private static final String BASE = "/api/v1";

    @Test
    @SuppressWarnings("unchecked")
    void everyServerRecordRequestBodyIsTypedExactlyAsItsRecord() throws Exception {
        Map<String, Object> doc = read(CONTRACT);
        Map<String, Map<String, Object>> contract = new HashMap<>();
        ((Map<String, Object>) doc.get("paths")).forEach((path, item) -> ((Map<String, Object>) item).forEach(
                (method, op) -> contract.put(method.toUpperCase() + " " + shape(path), (Map<String, Object>) op)));
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> drift = new ArrayList<>();
        int checked = 0;
        for (BeanDefinition bd : scan.findCandidateComponents("org.sangam.server")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (String.valueOf(c.getProtectionDomain().getCodeSource().getLocation()).contains("test-classes")) {
                continue;
            }
            RequestMapping cm = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            String[] bases = cm == null || cm.path().length == 0 ? new String[] {""} : cm.path();
            for (Method m : c.getDeclaredMethods()) {
                RequestMapping mm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mm == null || mm.method().length == 0) {
                    continue;
                }
                for (Parameter p : m.getParameters()) {
                    if (p.getAnnotation(RequestBody.class) == null || !p.getType().isRecord()) {
                        continue;
                    }
                    for (String base : bases) {
                        for (String sub : mm.path().length == 0 ? new String[] {""} : mm.path()) {
                            String full = base + sub;
                            if (!full.startsWith(BASE)) {
                                continue;
                            }
                            String key = mm.method()[0].name() + " " + shape(full.substring(BASE.length()));
                            String where = key + " (" + c.getSimpleName() + "." + m.getName() + ")";
                            Map<String, Object> op = contract.get(key);
                            Map<String, Object> schema = op == null ? null : jsonBody(op);
                            if (schema == null) {
                                drift.add(where + ": no contract operation with an application/json request body");
                                continue;
                            }
                            compare(doc, where, schema, p.getParameterizedType(), drift, 0);
                            checked++;
                        }
                    }
                }
            }
        }
        assertTrue(checked >= 20, "server record request bodies found: " + checked + " (the scan must see the controllers)");
        assertEquals(List.of(), drift, "server request bodies out of step with their records (" + drift.size() + ")");
    }

    @Test
    void shapeErasesVariableNamesAndConstraints() {
        assertEquals("/admin/cell/tenants/{}/freeze", shape("/admin/cell/tenants/{tenant}/freeze"));
        assertEquals("/packs/{}/uninstall", shape("/packs/{id:.+}/uninstall"));
    }

    @SuppressWarnings("unchecked")
    private static void compare(Map<String, Object> doc, String where, Map<String, Object> schema, Type type,
                                List<String> drift, int depth) {
        Map<String, Object> s = resolve(doc, schema);
        Class<?> raw = raw(type);
        Object t = s.get("type");
        if (t == null || depth > 8) {
            return;     // any JSON
        }
        Set<String> types = t instanceof List<?> l ? new TreeSet<>((List<String>) l) : Set.of((String) t);
        Set<String> allowed = allowed(raw);
        if (!allowed.isEmpty() && types.stream().noneMatch(allowed::contains)) {
            drift.add(where + ": " + type.getTypeName() + " is " + allowed + " in JSON, the contract says " + types);
            return;
        }
        if (raw.isRecord()) {
            Map<String, RecordComponent> components = new LinkedHashMap<>();
            for (RecordComponent rc : raw.getRecordComponents()) {
                components.put(jsonName(rc), rc);
            }
            Map<String, Object> props = s.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            if (!components.keySet().equals(props.keySet())) {
                drift.add(where + ": " + raw.getName() + " components " + new TreeSet<>(components.keySet())
                        + " vs contract properties " + new TreeSet<>(props.keySet()));
                return;
            }
            components.forEach((n, rc) -> compare(doc, where + "." + n, (Map<String, Object>) props.get(n),
                    rc.getGenericType(), drift, depth + 1));
        } else if (Collection.class.isAssignableFrom(raw) && s.get("items") instanceof Map<?, ?> items
                && type instanceof ParameterizedType pt) {
            compare(doc, where + "[]", (Map<String, Object>) items, pt.getActualTypeArguments()[0], drift, depth + 1);
        }
    }

    private static Set<String> allowed(Class<?> c) {
        if (c == String.class || c == char.class || c == Character.class || c == BigDecimal.class || c == BigInteger.class
                || c == Instant.class || c == LocalDate.class || c == OffsetDateTime.class || c == java.util.UUID.class
                || c.isEnum() || c.getName().startsWith("java.time.")) {
            return Set.of("string");
        }
        if (c == int.class || c == Integer.class || c == long.class || c == Long.class || c == short.class || c == Short.class) {
            return Set.of("integer", "number");
        }
        if (c == double.class || c == Double.class || c == float.class || c == Float.class) {
            return Set.of("number");
        }
        if (c == boolean.class || c == Boolean.class) {
            return Set.of("boolean");
        }
        if (Collection.class.isAssignableFrom(c) || c.isArray()) {
            return Set.of("array");
        }
        if (Map.class.isAssignableFrom(c) || c.isRecord()) {
            return Set.of("object");
        }
        return Set.of();     // Object, JsonNode: any JSON
    }

    private static Class<?> raw(Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        return Object.class;
    }

    private static String jsonName(RecordComponent rc) {
        JsonProperty jp = rc.getAnnotation(JsonProperty.class);
        if (jp == null) {
            jp = rc.getAccessor().getAnnotation(JsonProperty.class);
        }
        return jp != null && !jp.value().isEmpty() ? jp.value() : rc.getName();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonBody(Map<String, Object> op) {
        if (op.get("requestBody") instanceof Map<?, ?> body && body.get("content") instanceof Map<?, ?> content
                && content.get("application/json") instanceof Map<?, ?> media) {
            return (Map<String, Object>) media.get("schema");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolve(Map<String, Object> doc, Map<String, Object> schema) {
        Map<String, Object> s = schema == null ? Map.of() : schema;
        for (int guard = 0; guard < 8; guard++) {
            if (s.get("$ref") instanceof String ref) {
                s = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas"))
                        .get(ref.substring(ref.lastIndexOf('/') + 1));
            } else if (s.get("allOf") instanceof List<?> all && all.size() == 1 && !s.containsKey("type")) {
                s = (Map<String, Object>) all.get(0);
            } else {
                break;
            }
        }
        return s;
    }

    static String shape(String path) {
        return path.replaceAll("\\{[^}]*}", "{}");
    }

    private static Map<String, Object> read(Path p) throws IOException {
        LoaderOptions o = new LoaderOptions();
        o.setCodePointLimit(64 * 1024 * 1024);
        try (InputStream in = Files.newInputStream(p)) {
            return new Yaml(new SafeConstructor(o)).load(in);
        }
    }
}
