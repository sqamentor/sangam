// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
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
 * Jackson 3 (Spring Boot 4) refuses a JSON request member that is missing or null when it binds to a Java primitive
 * ({@code FAIL_ON_NULL_FOR_PRIMITIVES}, on by default): the caller gets 400 VALIDATION_FAILED "x is missing, null or
 * not a valid boolean" (ProblemHandler). So the contract must say so. For every {@code /api/v1} operation with a JSON
 * request body, on the whole server class path (sangam-rest, sangam-server and every module they host), every primitive
 * component of the bound record, recursively through nested records, lists, arrays and map values, is a property of the
 * contract schema and is listed in its {@code required}. A field that must stay optional is a boxed component with its
 * default applied in one place (CI ui e2e run 43717963671: the officer "Define purpose" form omitted two flags). A
 * record bound through a {@code @JsonCreator} (the SL-81 pattern: boxed parameters, the documented default applied in
 * the factory) is checked by the creator's parameters, because those are what Jackson binds.
 */
class PrimitiveRequestFieldContractTest {

    private static final Path CONTRACT = Path.of("..", "sangam-rest", "api", "sangam-api.yaml");
    private static final String BASE = "/api/v1";

    @Test
    @SuppressWarnings("unchecked")
    void everyPrimitiveRequestComponentIsRequiredInTheContract() throws Exception {
        Map<String, Object> doc = read(CONTRACT);
        Map<String, Map<String, Object>> contract = new HashMap<>();
        ((Map<String, Object>) doc.get("paths")).forEach((path, item) -> ((Map<String, Object>) item).forEach(
                (method, op) -> contract.put(method.toUpperCase() + " " + shape(path), (Map<String, Object>) op)));
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> findings = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        int bodies = 0;
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
                    if (p.getAnnotation(RequestBody.class) == null) {
                        continue;
                    }
                    for (String base : bases) {
                        for (String sub : mm.path().length == 0 ? new String[] {""} : mm.path()) {
                            String full = base + sub;
                            if (!full.startsWith(BASE)) {
                                continue;
                            }
                            String key = mm.method()[0].name() + " " + shape(full.substring(BASE.length()));
                            Map<String, Object> op = contract.get(key);
                            Map<String, Object> schema = op == null ? null : jsonBody(op);
                            if (schema == null) {
                                continue;   // ServerRequestBodyContractTest and RequestBodyContractTest own presence
                            }
                            bodies++;
                            check(doc, key + " (" + c.getSimpleName() + "." + m.getName() + ")", schema,
                                    p.getParameterizedType(), findings, reported, 0);
                        }
                    }
                }
            }
        }
        assertTrue(bodies >= 300, "JSON request bodies found: " + bodies + " (the scan must see the controllers)");
        assertEquals(List.of(), findings, "primitive request components that the contract does not require ("
                + findings.size() + "): make each required (an explicit choice), or box it (a server-assigned value or "
                + "an optional flag with its documented default applied once)");
    }

    @Test
    void theCheckFindsAnOptionalPrimitiveAtAnyDepth() {
        Map<String, Object> doc = new Yaml(new SafeConstructor(new LoaderOptions())).load("""
                components:
                  schemas:
                    Outer: {type: object, required: [count], properties: {count: {type: integer}, flag: {type: boolean},
                            name: {type: string}, inner: {$ref: '#/components/schemas/Inner'},
                            items: {type: array, items: {$ref: '#/components/schemas/Inner'}}}}
                    Inner: {type: object, properties: {enabled: {type: boolean}, label: {type: string}}}
                """);
        List<String> findings = new ArrayList<>();
        check(doc, "POST /x", Map.of("$ref", "#/components/schemas/Outer"), Outer.class, findings, new HashSet<>(), 0);
        assertEquals(List.of("POST /x: Outer.flag (boolean) is optional in the contract",
                "POST /x.inner: Inner.enabled (boolean) is optional in the contract"), findings);

        List<String> missing = new ArrayList<>();
        check(doc, "POST /y", Map.of("$ref", "#/components/schemas/Inner"), Missing.class, missing, new HashSet<>(), 0);
        assertEquals(List.of("POST /y: Missing.count (int) is not a property of the contract schema"), missing);

        // bound through a @JsonCreator: the boxed creator parameter is optional, a primitive one is still checked
        List<String> created = new ArrayList<>();
        check(doc, "POST /z", Map.of("$ref", "#/components/schemas/Inner"), Defaulted.class, created, new HashSet<>(), 0);
        assertEquals(List.of(), created);
        check(doc, "POST /w", Map.of("$ref", "#/components/schemas/Inner"), StrictCreator.class, created, new HashSet<>(), 0);
        assertEquals(List.of("POST /w: StrictCreator.enabled (boolean) is optional in the contract"), created);
    }

    record Defaulted(boolean enabled, String label) {
        @JsonCreator
        static Defaulted fromRequest(@JsonProperty("enabled") Boolean enabled, @JsonProperty("label") String label) {
            return new Defaulted(enabled != null && enabled, label);
        }
    }

    record StrictCreator(boolean enabled, String label) {
        @JsonCreator
        static StrictCreator fromRequest(@JsonProperty("enabled") boolean enabled, @JsonProperty("label") String label) {
            return new StrictCreator(enabled, label);
        }
    }

    record Inner(boolean enabled, String label) {
    }

    record Outer(int count, boolean flag, String name, Inner inner, List<Inner> items) {
    }

    record Missing(int count) {
    }

    /** Adds one finding per (record, component) whose primitive component the schema does not require. */
    @SuppressWarnings("unchecked")
    static void check(Map<String, Object> doc, String where, Map<String, Object> schema, Type type, List<String> findings,
                      Set<String> reported, int depth) {
        if (depth > 8) {
            return;
        }
        Map<String, Object> s = resolve(doc, schema);
        Class<?> raw = raw(type);
        if (raw.isRecord()) {
            Map<String, Object> props = s.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            Set<String> required = s.get("required") instanceof List<?> r ? new HashSet<>((List<String>) r) : Set.of();
            for (Bound b : bound(raw)) {
                if (b.type().isPrimitive()) {
                    String id = raw.getName() + "." + b.name() + "@" + System.identityHashCode(s);
                    String label = raw.getSimpleName() + "." + b.name() + " (" + b.type().getName() + ")";
                    if (!props.containsKey(b.name()) && reported.add(id)) {
                        findings.add(where + ": " + label + " is not a property of the contract schema");
                    } else if (props.containsKey(b.name()) && !required.contains(b.name()) && reported.add(id)) {
                        findings.add(where + ": " + label + " is optional in the contract");
                    }
                } else {
                    check(doc, where + "." + b.name(), (Map<String, Object>) props.get(b.name()), b.generic(), findings,
                            reported, depth + 1);
                }
            }
        } else if (element(type) != null) {
            Map<String, Object> items = s.get("items") instanceof Map<?, ?> i ? (Map<String, Object>) i : null;
            check(doc, where + "[]", items, element(type), findings, reported, depth + 1);
        } else if (Map.class.isAssignableFrom(raw) && type instanceof ParameterizedType pt) {
            Map<String, Object> values = s.get("additionalProperties") instanceof Map<?, ?> a ? (Map<String, Object>) a : null;
            check(doc, where + "{}", values, pt.getActualTypeArguments()[1], findings, reported, depth + 1);
        }
    }

    /** One JSON member Jackson binds: its name, its Java type and its generic type. */
    record Bound(String name, Class<?> type, Type generic) {
    }

    /**
     * The members Jackson binds for a record: the parameters of its delegating-free {@code @JsonCreator} (constructor
     * or static factory) when it declares one, else its components (the canonical constructor).
     */
    static List<Bound> bound(Class<?> record) {
        List<Executable> creators = new ArrayList<>();
        for (Method m : record.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && creator(m)) {
                creators.add(m);
            }
        }
        for (var c : record.getDeclaredConstructors()) {
            if (creator(c)) {
                creators.add(c);
            }
        }
        assertTrue(creators.size() <= 1, record.getName() + " declares more than one @JsonCreator");
        List<Bound> out = new ArrayList<>();
        if (creators.isEmpty()) {
            for (RecordComponent rc : record.getRecordComponents()) {
                out.add(new Bound(jsonName(rc), rc.getType(), rc.getGenericType()));
            }
            return out;
        }
        Executable e = creators.get(0);
        Parameter[] ps = e.getParameters();
        Type[] gs = e.getGenericParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            JsonProperty jp = ps[i].getAnnotation(JsonProperty.class);
            assertTrue(jp != null && !jp.value().isEmpty(),
                    record.getName() + ": every @JsonCreator parameter names its JSON member (@JsonProperty)");
            out.add(new Bound(jp.value(), ps[i].getType(), gs[i]));
        }
        return out;
    }

    private static boolean creator(Executable e) {
        JsonCreator jc = e.getAnnotation(JsonCreator.class);
        return jc != null && jc.mode() != JsonCreator.Mode.DISABLED && jc.mode() != JsonCreator.Mode.DELEGATING;
    }

    private static Class<?> raw(Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        if (t instanceof WildcardType w) {
            return raw(w.getUpperBounds()[0]);
        }
        if (t instanceof GenericArrayType) {
            return Object[].class;
        }
        return Object.class;
    }

    /** The element type of a collection or array type, else null. */
    private static Type element(Type t) {
        if (t instanceof Class<?> c && c.isArray()) {
            return c.getComponentType().isPrimitive() ? null : c.getComponentType();
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
                s = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas"))
                        .get(ref.substring(ref.lastIndexOf('/') + 1));
            } else if (s.get("allOf") instanceof List<?> all && all.size() == 1 && !s.containsKey("type")) {
                s = (Map<String, Object>) all.get(0);
            } else {
                break;
            }
        }
        return s == null ? Map.of() : s;
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
