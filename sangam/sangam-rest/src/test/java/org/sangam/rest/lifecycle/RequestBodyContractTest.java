// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-051: every {@code @RequestBody} bound to a record, in every {@code /api/v1} controller on the class path, has a typed
 * request schema in {@code api/sangam-api.yaml}: exactly the record's components (JSON names), with compatible JSON
 * types, recursively through nested records and lists. The schemas come from the records (gen_openapi.py,
 * routes/_v2_spring.py {@code _v2_body_schema} and {@code record_body}); a record change without a regenerated contract,
 * or a contract property the record does not have, fails here. Map-backed bodies (and JSON strings the service parses)
 * are typed from the keys the service reads and checked by {@code MapRequestBodyContractTest}.
 */
class RequestBodyContractTest {

    private static final String BASE = "/api/v1";

    @Test
    @SuppressWarnings("unchecked")
    void everyRecordRequestBodyIsTypedExactlyAsItsRecord() throws Exception {
        Map<String, Object> doc = AppContractDriftTest.read(Path.of("api", "sangam-api.yaml"));
        Map<String, Map<String, Object>> contract = new HashMap<>();
        ((Map<String, Object>) doc.get("paths")).forEach((path, item) -> ((Map<String, Object>) item).forEach(
                (method, op) -> contract.put(method.toUpperCase() + " " + shape(path), (Map<String, Object>) op)));

        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> drift = new ArrayList<>();
        int checked = 0;
        for (BeanDefinition bd : scan.findCandidateComponents("org.sangam")) {
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
                            if (op == null) {
                                drift.add(where + ": no contract operation");
                                continue;
                            }
                            Map<String, Object> schema = jsonBody(op);
                            if (schema == null) {
                                drift.add(where + ": the contract has no application/json request body");
                                continue;
                            }
                            compare(doc, where, schema, p.getParameterizedType(), drift);
                            checked++;
                        }
                    }
                }
            }
        }
        assertTrue(checked >= 300, "record request bodies found: " + checked + " (the scan must see the controllers)");
        assertEquals(List.of(), drift, "request bodies out of step with their records (" + drift.size() + ")");
    }

    /** Java to JSON type rules of the generator ({@code _V2_SCALAR}, {@code _V2_STRINGS}) and of Jackson. */
    @SuppressWarnings("unchecked")
    static void compare(Map<String, Object> doc, String where, Map<String, Object> schema, Type type, List<String> drift) {
        Map<String, Object> s = resolve(doc, schema);
        Object t = s.get("type");
        if (t == null) {
            return; // an untyped schema (any JSON) accepts every Java type
        }
        Set<String> types = t instanceof List<?> l ? new TreeSet<>((List<String>) l) : Set.of((String) t);
        Class<?> raw = raw(type);
        Set<String> allowed = allowed(raw);
        if (allowed.isEmpty()) {
            return; // Object, JsonNode: any JSON
        }
        if (types.stream().noneMatch(allowed::contains)) {
            drift.add(where + ": " + type.getTypeName() + " is " + allowed + " in JSON, the contract says " + types);
            return;
        }
        if (raw.isRecord() && s.get("properties") instanceof Map<?, ?> props) {
            Map<String, RecordComponent> components = new LinkedHashMap<>();
            for (RecordComponent rc : raw.getRecordComponents()) {
                components.put(jsonName(rc), rc);
            }
            if (!components.keySet().equals(props.keySet())) {
                drift.add(where + ": " + raw.getName() + " components " + new TreeSet<>(components.keySet())
                        + " vs contract properties " + new TreeSet<>((Set<String>) props.keySet()));
                return;
            }
            components.forEach((n, rc) -> compare(doc, where + "." + n, (Map<String, Object>) props.get(n),
                    rc.getGenericType(), drift));
        } else if (raw.isEnum() && s.get("enum") instanceof List<?> values) {
            Set<String> names = new TreeSet<>();
            for (Object e : raw.getEnumConstants()) {
                names.add(((Enum<?>) e).name());
            }
            if (!names.equals(new TreeSet<>((List<String>) values))) {
                drift.add(where + ": enum " + names + " vs contract " + values);
            }
        } else if (element(type) != null && s.get("items") instanceof Map<?, ?> items) {
            compare(doc, where + "[]", (Map<String, Object>) items, element(type), drift);
        }
    }

    private static Set<String> allowed(Class<?> c) {
        if (c == String.class || c == char.class || c == Character.class || c.isEnum() || c == UUID.class
                || c == Instant.class || c == OffsetDateTime.class || c == ZonedDateTime.class || c == LocalDate.class
                || c == LocalDateTime.class || c == LocalTime.class || c == Duration.class) {
            return Set.of("string");
        }
        if (c == int.class || c == Integer.class || c == long.class || c == Long.class || c == short.class
                || c == Short.class) {
            return Set.of("integer");
        }
        if (c == double.class || c == Double.class || c == float.class || c == Float.class) {
            return Set.of("number");
        }
        if (c == BigDecimal.class) {
            return Set.of("string", "number");
        }
        if (c == BigInteger.class) {
            return Set.of("string", "integer");
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
        return Set.of();
    }

    private static Class<?> raw(Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType pt) {
            return (Class<?>) pt.getRawType();
        }
        if (t instanceof GenericArrayType) {
            return Object[].class;
        }
        if (t instanceof WildcardType w) {
            return raw(w.getUpperBounds()[0]);
        }
        return Object.class;
    }

    /** The element type of a collection or array type, else null. */
    private static Type element(Type t) {
        if (t instanceof Class<?> c && c.isArray()) {
            return c.getComponentType();
        }
        if (t instanceof GenericArrayType g) {
            return g.getGenericComponentType();
        }
        if (t instanceof ParameterizedType pt && Collection.class.isAssignableFrom((Class<?>) pt.getRawType())) {
            return pt.getActualTypeArguments()[0];
        }
        return null;
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
                s = AppContractDriftTest.resolve(doc, s);
            } else if (s.get("allOf") instanceof List<?> all && all.size() == 1 && !s.containsKey("type")) {
                s = (Map<String, Object>) all.get(0);
            } else {
                break;
            }
        }
        return s;
    }

    /** Path template with variable names (and Spring regex constraints) erased. */
    static String shape(String path) {
        return path.replaceAll("\\{[^}]*}", "{}");
    }

    @Test
    void shapeErasesNamesAndConstraints() throws IOException {
        assertEquals("/admin/{}/{}", shape("/admin/{kind:services|agents}/{id}"));
        assertEquals("/a/{}", shape("/a/{x}"));
    }
}
