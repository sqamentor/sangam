// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * SL-38: the API deprecation registry ({@code sangam-api-deprecations.yaml}, the same file {@code gen_openapi.py}
 * reads to mark operations {@code deprecated: true}). The registry is validated when it is loaded, so a bad entry
 * stops the server instead of sending wrong headers:
 * <ul>
 *   <li>method, path, deprecated, sunset and link are required; the path must start with {@code /};</li>
 *   <li>the sunset date is at least {@code policy.minimumSupportDays} (never less than {@link #FLOOR_SUPPORT_DAYS})
 *       after the deprecation date (API-LIFECYCLE-POLICY.md section 2);</li>
 *   <li>no two entries name the same operation.</li>
 * </ul>
 */
public final class ApiDeprecationRegistry {

    /** Classpath resource that sangam-rest packages from {@code api/}. */
    public static final String RESOURCE = "sangam-api-deprecations.yaml";
    /** The written policy's minimum support window; a registry may only raise it. */
    public static final int FLOOR_SUPPORT_DAYS = 365;
    static final String BASE = "/api/v1";
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /** One deprecated operation. {@code path} is stored without the {@code /api/v1} prefix. */
    public record Entry(String method, String path, LocalDate deprecated, LocalDate sunset, String link,
                        String successor) {

        boolean matches(String m, String requestPath) {
            return method.equals(m) && templateMatches(path, requestPath);
        }
    }

    private final int minimumSupportDays;
    private final List<Entry> entries;

    private ApiDeprecationRegistry(int minimumSupportDays, List<Entry> entries) {
        this.minimumSupportDays = minimumSupportDays;
        this.entries = List.copyOf(entries);
    }

    public static ApiDeprecationRegistry empty() {
        return new ApiDeprecationRegistry(FLOOR_SUPPORT_DAYS, List.of());
    }

    /** Loads the packaged registry; an absent resource means "nothing deprecated". */
    public static ApiDeprecationRegistry fromClasspath() {
        ClassLoader cl = ApiDeprecationRegistry.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(RESOURCE)) {
            return in == null ? empty() : load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static ApiDeprecationRegistry load(InputStream in) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        Map<String, Object> doc = root instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        int minDays = FLOOR_SUPPORT_DAYS;
        if (doc.get("policy") instanceof Map<?, ?> policy && policy.get("minimumSupportDays") != null) {
            minDays = Integer.parseInt(String.valueOf(policy.get("minimumSupportDays")));
            if (minDays < FLOOR_SUPPORT_DAYS) {
                throw invalid("policy.minimumSupportDays " + minDays + " is below the policy floor of "
                        + FLOOR_SUPPORT_DAYS + " days");
            }
        }
        List<Entry> out = new ArrayList<>();
        Object list = doc.get("deprecations");
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> raw)) {
                    throw invalid("each deprecation must be a mapping");
                }
                Entry e = entry((Map<String, Object>) raw, minDays);
                for (Entry x : out) {
                    if (x.method().equals(e.method()) && x.path().equals(e.path())) {
                        throw invalid("duplicate entry for " + e.method() + " " + e.path());
                    }
                }
                out.add(e);
            }
        } else if (list != null) {
            throw invalid("deprecations must be a list");
        }
        return new ApiDeprecationRegistry(minDays, out);
    }

    private static Entry entry(Map<String, Object> m, int minDays) {
        String method = required(m, "method").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw invalid("unknown method " + method);
        }
        String path = required(m, "path");
        if (!path.startsWith("/")) {
            throw invalid("path must start with /: " + path);
        }
        path = stripBase(path);
        LocalDate deprecated = date(m, "deprecated");
        LocalDate sunset = date(m, "sunset");
        long days = ChronoUnit.DAYS.between(deprecated, sunset);
        if (days < minDays) {
            throw invalid(method + " " + path + ": sunset " + sunset + " is " + days
                    + " days after deprecation " + deprecated + "; the minimum support window is " + minDays + " days");
        }
        String link = required(m, "link");
        Object successor = m.get("successor");
        return new Entry(method, path, deprecated, sunset, link, successor == null ? null : stripBase(successor.toString()));
    }

    private static String required(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw invalid("missing required field '" + key + "'");
        }
        return v.toString().trim();
    }

    private static LocalDate date(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof java.util.Date d) {
            // SnakeYAML resolves unquoted ISO dates as timestamps at UTC midnight.
            return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        try {
            return LocalDate.parse(required(m, key));
        } catch (java.time.format.DateTimeParseException e) {
            throw invalid("'" + key + "' must be an ISO date (yyyy-MM-dd): " + v);
        }
    }

    private static IllegalStateException invalid(String why) {
        return new IllegalStateException("Invalid " + RESOURCE + ": " + why);
    }

    static String stripBase(String path) {
        return path.startsWith(BASE + "/") ? path.substring(BASE.length()) : path;
    }

    /** {@code /cases/{caseId}} matches {@code /cases/42}; segments must match one for one. */
    static boolean templateMatches(String template, String path) {
        String[] t = template.split("/", -1);
        String[] p = path.split("/", -1);
        if (t.length != p.length) {
            return false;
        }
        for (int i = 0; i < t.length; i++) {
            boolean variable = t[i].startsWith("{") && t[i].endsWith("}");
            if (variable ? p[i].isEmpty() : !t[i].equals(p[i])) {
                return false;
            }
        }
        return true;
    }

    /** The entry for a request, given its path relative to the context root (with the {@code /api/v1} prefix). */
    public Optional<Entry> find(String method, String requestPath) {
        if (!requestPath.startsWith(BASE + "/")) {
            return Optional.empty();
        }
        String rel = requestPath.substring(BASE.length());
        String m = method.toUpperCase(Locale.ROOT);
        // A literal template wins over a variable one (/cases/search before /cases/{caseId}).
        return entries.stream().filter(e -> e.matches(m, rel))
                .min((a, b) -> Long.compare(a.path().chars().filter(c -> c == '{').count(),
                        b.path().chars().filter(c -> c == '{').count()));
    }

    public List<Entry> entries() {
        return entries;
    }

    public int minimumSupportDays() {
        return minimumSupportDays;
    }
}
