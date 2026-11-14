// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The golden business results ({@code golden-results.properties} next to this class): the host-independent oracle of
 * the equivalence scenarios (M-150). Two hosts are equivalent when each one's results equal these, value for value.
 */
public final class GoldenResults {

    static final String RESOURCE = "golden-results.properties";

    private final Map<String, String> values;

    private GoldenResults(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static GoldenResults load() {
        try (InputStream in = GoldenResults.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the conformance kit");
            }
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    static GoldenResults parse(Reader reader) throws IOException {
        Properties p = new Properties();
        p.load(reader);
        Map<String, String> m = new TreeMap<>();
        p.stringPropertyNames().forEach(k -> m.put(k, p.getProperty(k)));
        return new GoldenResults(m);
    }

    /** The scenario names that have golden results. */
    public Set<String> scenarios() {
        Set<String> out = new TreeSet<>();
        for (String k : values.keySet()) {
            int dot = k.indexOf('.');
            out.add(dot < 0 ? k : k.substring(0, dot));
        }
        return out;
    }

    /** The golden result of {@code scenario}; fails when the scenario has none (no scenario runs unchecked). */
    public BusinessResult forScenario(String scenario) {
        String prefix = scenario + ".";
        Map<String, String> m = new TreeMap<>();
        values.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                m.put(k.substring(prefix.length()), v);
            }
        });
        if (m.isEmpty()) {
            throw new IllegalStateException("no golden result for scenario " + scenario + " in " + RESOURCE);
        }
        return BusinessResult.ofCanonical(m);
    }
}
