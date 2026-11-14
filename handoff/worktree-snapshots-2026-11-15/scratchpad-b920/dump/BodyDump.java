import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Scratch tool: dumps every sangam-rest handler with a @RequestBody and the JSON shape of the body type. */
public class BodyDump {
    static StringBuilder out = new StringBuilder();
    static Set<String> seen = new HashSet<>();
    static Map<String, String> recs = new TreeMap<>();

    public static void main(String[] a) throws Exception {
        Path root = Path.of(a[0]);
        List<String> names;
        try (Stream<Path> s = Files.walk(root)) {
            names = s.filter(p -> p.toString().endsWith(".class") && !p.toString().contains("$"))
                    .map(p -> root.relativize(p).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", ""))
                    .collect(Collectors.toList());
        }
        Class<? extends Annotation> rc = (Class<? extends Annotation>) Class.forName("org.springframework.web.bind.annotation.RestController");
        Class<? extends Annotation> rm = (Class<? extends Annotation>) Class.forName("org.springframework.web.bind.annotation.RequestMapping");
        Class<? extends Annotation> rb = (Class<? extends Annotation>) Class.forName("org.springframework.web.bind.annotation.RequestBody");
        StringBuilder ops = new StringBuilder("[\n");
        for (String n : names) {
            Class<?> c;
            try { c = Class.forName(n, false, BodyDump.class.getClassLoader()); } catch (Throwable t) { System.err.println("skip " + n + " " + t); continue; }
            if (!c.isAnnotationPresent(rc)) continue;
            String base = "";
            Annotation cm = c.getAnnotation(rm);
            if (cm != null) { String[] v = (String[]) rm.getMethod("value").invoke(cm); if (v.length == 0) v = (String[]) rm.getMethod("path").invoke(cm); base = v.length > 0 ? v[0] : ""; }
            Method[] ms;
            try { ms = c.getDeclaredMethods(); } catch (Throwable t) { System.err.println("skip methods " + n + " " + t); continue; }
            for (Method m : ms) {
                String verb = null; String[] paths = null;
                for (Annotation an : m.getAnnotations()) {
                    String t = an.annotationType().getSimpleName();
                    if (t.endsWith("Mapping") && !t.equals("RequestMapping")) {
                        verb = t.replace("Mapping", "").toUpperCase();
                        paths = (String[]) an.annotationType().getMethod("value").invoke(an);
                        if (paths.length == 0) paths = (String[]) an.annotationType().getMethod("path").invoke(an);
                        String[] consumes = (String[]) an.annotationType().getMethod("consumes").invoke(an);
                    } else if (t.equals("RequestMapping")) {
                        Object[] meth = (Object[]) rm.getMethod("method").invoke(an);
                        verb = meth.length > 0 ? meth[0].toString() : "ANY";
                        paths = (String[]) rm.getMethod("value").invoke(an);
                    }
                }
                if (verb == null) continue;
                for (Parameter p : m.getParameters()) {
                    Annotation b = p.getAnnotation(rb);
                    if (b == null) continue;
                    boolean req = (Boolean) rb.getMethod("required").invoke(b);
                    for (String sub : paths.length == 0 ? new String[] {""} : paths) {
                        ops.append(String.format("{\"cls\":\"%s\",\"m\":\"%s\",\"verb\":\"%s\",\"path\":\"%s\",\"required\":%s,\"type\":%s},\n",
                                c.getName(), m.getName(), verb, base + sub, req, shape(p.getParameterizedType())));
                    }
                }
            }
        }
        if (ops.charAt(ops.length() - 2) == ',') ops.setLength(ops.length() - 2);
        ops.append("\n]");
        StringBuilder r = new StringBuilder("{\n");
        recs.forEach((k, v) -> r.append("\"").append(k).append("\":").append(v).append(",\n"));
        if (r.length() > 2) r.setLength(r.length() - 2);
        r.append("\n}");
        Files.writeString(Path.of(a[1]), "{\"ops\":" + ops + ",\n\"records\":" + r + "}\n");
    }

    static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    static String shape(Type t) {
        if (t instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            Type[] args = pt.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) return "{\"k\":\"list\",\"of\":" + shape(args[0]) + "}";
            if (Map.class.isAssignableFrom(raw)) return "{\"k\":\"map\",\"of\":" + shape(args[1]) + "}";
            if (raw == Optional.class) return shape(args[0]);
            return "{\"k\":\"other\",\"name\":" + q(t.getTypeName()) + "}";
        }
        if (t instanceof WildcardType w) return shape(w.getUpperBounds()[0]);
        if (t instanceof TypeVariable<?>) return "{\"k\":\"any\"}";
        if (t instanceof GenericArrayType ga) return "{\"k\":\"list\",\"of\":" + shape(ga.getGenericComponentType()) + "}";
        Class<?> c = (Class<?>) t;
        if (c.isArray()) return c == byte[].class ? "{\"k\":\"bytes\"}" : "{\"k\":\"list\",\"of\":" + shape(c.getComponentType()) + "}";
        if (c == String.class || c == Character.class || c == char.class) return "{\"k\":\"s\"}";
        if (c == int.class || c == Integer.class || c == short.class || c == Short.class || c == byte.class || c == Byte.class) return "{\"k\":\"int\",\"prim\":" + c.isPrimitive() + "}";
        if (c == long.class || c == Long.class) return "{\"k\":\"long\",\"prim\":" + c.isPrimitive() + "}";
        if (c == boolean.class || c == Boolean.class) return "{\"k\":\"bool\",\"prim\":" + c.isPrimitive() + "}";
        if (c == double.class || c == Double.class || c == float.class || c == Float.class) return "{\"k\":\"num\",\"prim\":" + c.isPrimitive() + "}";
        if (c == java.math.BigDecimal.class) return "{\"k\":\"decimal\"}";
        if (c == java.math.BigInteger.class) return "{\"k\":\"bigint\"}";
        if (c == java.time.Instant.class || c == java.time.OffsetDateTime.class || c == java.time.ZonedDateTime.class) return "{\"k\":\"dt\"}";
        if (c == java.time.LocalDateTime.class) return "{\"k\":\"ldt\"}";
        if (c == java.time.LocalDate.class) return "{\"k\":\"date\"}";
        if (c == java.time.LocalTime.class) return "{\"k\":\"time\"}";
        if (c == java.time.Duration.class) return "{\"k\":\"duration\"}";
        if (c == java.util.UUID.class) return "{\"k\":\"uuid\"}";
        if (c == Object.class) return "{\"k\":\"any\"}";
        if (Map.class.isAssignableFrom(c)) return "{\"k\":\"map\",\"of\":{\"k\":\"any\"}}";
        if (Collection.class.isAssignableFrom(c)) return "{\"k\":\"list\",\"of\":{\"k\":\"any\"}}";
        if (c.getName().startsWith("com.fasterxml.jackson") || c.getName().startsWith("tools.jackson")) return "{\"k\":\"json\",\"name\":" + q(c.getName()) + "}";
        if (c.isEnum()) {
            String vals = Arrays.stream(c.getEnumConstants()).map(e -> q(((Enum<?>) e).name())).collect(Collectors.joining(","));
            return "{\"k\":\"enum\",\"name\":" + q(c.getName()) + ",\"values\":[" + vals + "]}";
        }
        if (c.isRecord()) {
            if (seen.add(c.getName())) {
                StringBuilder s = new StringBuilder("{\"components\":[");
                boolean first = true;
                for (RecordComponent rc : c.getRecordComponents()) {
                    String json = null;
                    for (Annotation an : rc.getAccessor().getAnnotations()) json = jsonName(an, json);
                    for (Annotation an : rc.getAnnotations()) json = jsonName(an, json);
                    try { Field f = c.getDeclaredField(rc.getName()); for (Annotation an : f.getAnnotations()) json = jsonName(an, json); } catch (NoSuchFieldException e) { }
                    if (!first) s.append(",");
                    first = false;
                    s.append("{\"name\":").append(q(rc.getName())).append(",\"json\":").append(json == null ? "null" : q(json)).append(",\"type\":").append(shape(rc.getGenericType())).append("}");
                }
                boolean creator = Arrays.stream(c.getDeclaredMethods()).anyMatch(m -> Arrays.stream(m.getAnnotations()).anyMatch(an -> an.annotationType().getSimpleName().equals("JsonCreator")))
                        || Arrays.stream(c.getDeclaredConstructors()).anyMatch(k -> Arrays.stream(k.getAnnotations()).anyMatch(an -> an.annotationType().getSimpleName().equals("JsonCreator")));
                boolean classAnn = Arrays.stream(c.getAnnotations()).anyMatch(an -> an.annotationType().getName().contains("jackson"));
                s.append("],\"creator\":").append(creator).append(",\"jacksonClassAnnotations\":").append(classAnn).append("}");
                recs.put(c.getName(), s.toString());
            }
            return "{\"k\":\"record\",\"name\":" + q(c.getName()) + "}";
        }
        return "{\"k\":\"class\",\"name\":" + q(c.getName()) + "}";
    }

    static String jsonName(Annotation an, String cur) {
        String t = an.annotationType().getSimpleName();
        if (t.equals("JsonProperty")) {
            try { String v = (String) an.annotationType().getMethod("value").invoke(an); return v.isEmpty() ? cur : v; } catch (Exception e) { return cur; }
        }
        if (t.equals("JsonIgnore")) return "<ignored>";
        if (t.equals("JsonAlias") || t.equals("JsonUnwrapped") || t.equals("JsonAnySetter")) return cur == null ? "<" + t + ">" : cur + "<" + t + ">";
        return cur;
    }
}
