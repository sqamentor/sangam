// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Config-source seam of the host SPI (M-144, design note section 10.1): the host's configuration system seen as plain
 * string keys. The Spring host wraps its {@code Environment}, a Quarkus host its SmallRye {@code Config}, a plain-Java
 * host a {@code Map}; {@code sangam-defaults.yaml} is the lowest source ({@link #withDefaults}). Every default and every
 * typed reading lives here and in the assembly, never in a host, so all hosts read one key the same way.
 *
 * <p>Typed getters treat a blank value as absent. Booleans accept exactly the values Spring's string-to-boolean
 * conversion accepts ({@code true/on/yes/1}, {@code false/off/no/0}, any case), so moving a reading from a Spring
 * {@code @Bean} method into the assembly never changes how an existing configuration is read; any other value fails
 * with the key named, never silently falls back to the default.
 */
@FunctionalInterface
public interface SangamProperties {

    /** The raw value of {@code key}, or {@code null} when no source defines it. */
    String get(String key);

    default String get(String key, String defaultValue) {
        String v = get(key);
        return v == null ? defaultValue : v;
    }

    default boolean getBoolean(String key, boolean defaultValue) {
        String v = trimmed(key);
        if (v == null) {
            return defaultValue;
        }
        switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1":
                return true;
            case "false", "off", "no", "0":
                return false;
            default:
                throw invalid(key, v, "a boolean (true/false, on/off, yes/no, 1/0)");
        }
    }

    default int getInt(String key, int defaultValue) {
        String v = trimmed(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw invalid(key, v, "an integer");
        }
    }

    default long getLong(String key, long defaultValue) {
        String v = trimmed(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw invalid(key, v, "a long integer");
        }
    }

    /**
     * A duration in ISO-8601 ({@code PT30S}) or Spring's simple form: a number with one of the units {@code ns},
     * {@code us}, {@code ms}, {@code s}, {@code m}, {@code h}, {@code d}; a bare number is milliseconds.
     */
    default Duration getDuration(String key, Duration defaultValue) {
        String v = trimmed(key);
        if (v == null) {
            return defaultValue;
        }
        Duration d = Durations.parse(v);
        if (d == null) {
            throw invalid(key, v, "a duration (PT30S, 30s, 500ms, 5m, 2h, 1d)");
        }
        return d;
    }

    /** A comma-separated list, each element trimmed, empty elements dropped; absent or blank gives an empty list. */
    default List<String> getList(String key) {
        String v = trimmed(key);
        if (v == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    /** This source first, then {@code lower} for every key this source does not define. */
    default SangamProperties withDefaults(SangamProperties lower) {
        Objects.requireNonNull(lower, "lower");
        SangamProperties upper = this;
        return key -> {
            String v = upper.get(key);
            return v != null ? v : lower.get(key);
        };
    }

    private String trimmed(String key) {
        String v = get(key);
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static IllegalArgumentException invalid(String key, String value, String expected) {
        return new IllegalArgumentException("configuration key " + key + "='" + value + "' is not " + expected);
    }

    /** A fixed source over a copy of {@code values} (null values are not allowed). */
    static SangamProperties of(Map<String, String> values) {
        Map<String, String> copy = Map.copyOf(values);
        return copy::get;
    }

    /** A source that defines no key. */
    static SangamProperties empty() {
        return key -> null;
    }
}
