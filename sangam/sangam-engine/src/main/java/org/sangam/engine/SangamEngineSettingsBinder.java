// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * R8-pnp PNP-1: binds {@code sangam.engine.*} keys from plain properties (system properties, a properties file) onto
 * {@link SangamEngineSettings}, for a host without Spring ({@link SangamEnginePlugin}). The same names as the Spring
 * binding ({@code @ConfigurationProperties("sangam.engine")}): kebab case of the JavaBean setter, for example
 * {@code sangam.engine.timer-zone=Asia/Kolkata}. Only the public bean setters with a text-representable type are
 * bindable: text, booleans, numbers, {@link Duration} (ISO-8601, {@code PT10S}), {@link ZoneId}, enums, a set of text
 * (comma list) and a map keyed by the next segment ({@code sangam.engine.deploy-allowed-beans.*=recorder.record}, a
 * map of sets; {@code sangam.engine.job-bulkheads.timer-transition=4}). Port objects are never bound from text.
 *
 * <p>Fail closed: an unknown {@code sangam.engine.*} key, or a value of the wrong type, is refused with
 * {@link IllegalArgumentException} (a typo must never leave a safety setting at a default silently). Keys outside
 * {@code sangam.engine.} are ignored.
 */
public final class SangamEngineSettingsBinder {

    public static final String PREFIX = "sangam.engine.";

    private SangamEngineSettingsBinder() {
    }

    /** Binds every {@code sangam.engine.*} key of {@code props} onto {@code settings}; returns {@code settings}. */
    public static SangamEngineSettings bind(SangamEngineSettings settings, Properties props) {
        Map<String, Method> setters = setters();
        Map<String, Map<String, String>> maps = new TreeMap<>();
        for (String key : new TreeMap<>(toMap(props)).keySet()) {
            if (!key.startsWith(PREFIX)) {
                continue;
            }
            String rest = key.substring(PREFIX.length());
            String value = props.getProperty(key);
            Method direct = setters.get(rest);
            if (direct != null && !isMap(direct)) {
                invoke(settings, direct, key, convert(direct.getGenericParameterTypes()[0], value, key));
                continue;
            }
            String name = mapSetting(setters, rest);
            if (name == null) {
                throw new IllegalArgumentException("unknown setting '" + key + "' (sangam.engine.* must name a "
                        + "bindable SangamEngineSettings property)");
            }
            maps.computeIfAbsent(name, k -> new LinkedHashMap<>()).put(rest.substring(name.length() + 1), value);
        }
        for (Map.Entry<String, Map<String, String>> m : maps.entrySet()) {
            Method setter = setters.get(m.getKey());
            Type valueType = ((ParameterizedType) setter.getGenericParameterTypes()[0]).getActualTypeArguments()[1];
            Map<String, Object> out = new LinkedHashMap<>();
            m.getValue().forEach((k, v) -> out.put(k, convert(valueType, v, PREFIX + m.getKey() + "." + k)));
            invoke(settings, setter, PREFIX + m.getKey(), out);
        }
        return settings;
    }

    private static Map<String, String> toMap(Properties p) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : p.stringPropertyNames()) {
            m.put(k, p.getProperty(k));
        }
        return m;
    }

    private static String mapSetting(Map<String, Method> setters, String rest) {
        String best = null;
        for (Map.Entry<String, Method> e : setters.entrySet()) {
            if (isMap(e.getValue()) && rest.startsWith(e.getKey() + ".") && rest.length() > e.getKey().length() + 1
                    && (best == null || e.getKey().length() > best.length())) {
                best = e.getKey();
            }
        }
        return best;
    }

    private static boolean isMap(Method m) {
        return Map.class.isAssignableFrom(m.getParameterTypes()[0]);
    }

    /** kebab name -> public one-argument setter of a text-representable type. */
    static Map<String, Method> setters() {
        Map<String, Method> out = new TreeMap<>();
        for (Method m : SangamEngineSettings.class.getMethods()) {
            if (!m.getName().startsWith("set") || m.getParameterCount() != 1 || m.getDeclaringClass() != SangamEngineSettings.class) {
                continue;
            }
            if (bindable(m.getGenericParameterTypes()[0])) {
                out.put(kebab(m.getName().substring(3)), m);
            }
        }
        return out;
    }

    private static boolean bindable(Type t) {
        if (t instanceof Class<?> c) {
            return c == String.class || c == boolean.class || c == Boolean.class || c == int.class || c == Integer.class
                    || c == long.class || c == Long.class || c == float.class || c == Float.class || c == Duration.class
                    || c == ZoneId.class || c.isEnum();
        }
        if (t instanceof ParameterizedType p) {
            Class<?> raw = (Class<?>) p.getRawType();
            Type[] a = p.getActualTypeArguments();
            if (raw == Set.class) {
                return a[0] == String.class;
            }
            if (raw == Map.class) {
                return a[0] == String.class && (bindable(a[1]) && !(a[1] instanceof ParameterizedType pp && pp.getRawType() == Map.class));
            }
        }
        return false;
    }

    static String kebab(String camel) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    b.append('-');
                }
                b.append(Character.toLowerCase(c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convert(Type t, String raw, String key) {
        String v = raw == null ? "" : raw.trim();
        try {
            if (t instanceof ParameterizedType p && p.getRawType() == Set.class) {
                return v.isEmpty() ? new LinkedHashSet<String>() : Arrays.stream(v.split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new));
            }
            Class<?> c = (Class<?>) t;
            if (c == String.class) {
                return v;
            }
            if (c == boolean.class || c == Boolean.class) {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("not true or false");
                }
                return Boolean.parseBoolean(v);
            }
            if (c == int.class || c == Integer.class) {
                return Integer.parseInt(v);
            }
            if (c == long.class || c == Long.class) {
                return Long.parseLong(v);
            }
            if (c == float.class || c == Float.class) {
                return Float.parseFloat(v);
            }
            if (c == Duration.class) {
                return Duration.parse(v);
            }
            if (c == ZoneId.class) {
                return ZoneId.of(v);
            }
            if (c.isEnum()) {
                return Enum.valueOf((Class<Enum>) c, v.toUpperCase(Locale.ROOT).replace('-', '_'));
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("setting '" + key + "' has an invalid value '" + raw + "': " + e.getMessage(), e);
        }
        throw new IllegalArgumentException("setting '" + key + "' cannot be bound from text");
    }

    private static void invoke(SangamEngineSettings s, Method m, String key, Object value) {
        try {
            m.invoke(s, value);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException r) {
                throw r;
            }
            throw new IllegalArgumentException("setting '" + key + "' refused: " + c, c);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The bindable keys, for documentation and tests. */
    public static Set<String> keys() {
        return setters().keySet().stream().map(k -> PREFIX + k).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
