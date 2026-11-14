// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Cross-host equivalence (M-150): the comparison of business results, against the golden result or between hosts. A
 * difference is reported per value with both sides, so a red run says exactly what a host computed differently.
 */
public final class CrossHostEquivalence {

    /** When set, each host's observed results are written to {@code <dir>/<host>.properties} for a cross-run diff. */
    public static final String REPORT_DIR_PROPERTY = "sangam.conformance.report-dir";

    private CrossHostEquivalence() {
    }

    /** Every value where {@code actual} differs from {@code expected}: missing, unexpected or different. */
    public static List<String> differences(String scenario, BusinessResult expected, BusinessResult actual) {
        List<String> out = new ArrayList<>();
        SortedMap<String, String> e = expected.values();
        SortedMap<String, String> a = actual.values();
        TreeSet<String> names = new TreeSet<>(e.keySet());
        names.addAll(a.keySet());
        for (String n : names) {
            if (!a.containsKey(n)) {
                out.add(scenario + "." + n + ": missing (expected " + e.get(n) + ")");
            } else if (!e.containsKey(n)) {
                out.add(scenario + "." + n + ": not expected (got " + a.get(n) + ")");
            } else if (!e.get(n).equals(a.get(n))) {
                out.add(scenario + "." + n + ": expected " + e.get(n) + " but got " + a.get(n));
            }
        }
        return out;
    }

    /**
     * Results of one scenario on several hosts, compared with the first host by name: every difference names the
     * other host. Empty when all hosts agree.
     */
    public static List<String> acrossHosts(String scenario, Map<String, BusinessResult> byHost) {
        List<String> out = new ArrayList<>();
        SortedMap<String, BusinessResult> sorted = new TreeMap<>(byHost);
        if (sorted.size() < 2) {
            return out;
        }
        String reference = sorted.firstKey();
        BusinessResult ref = sorted.get(reference);
        sorted.forEach((host, result) -> {
            if (!host.equals(reference)) {
                for (String d : differences(scenario, ref, result)) {
                    out.add(host + " vs " + reference + ": " + d);
                }
            }
        });
        return out;
    }

    /** The canonical text of one host's results ({@code <scenario>.<name>=<value>}, sorted), as in the golden file. */
    public static String render(Map<String, BusinessResult> byScenario) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(byScenario).forEach((scenario, result) -> result.values().forEach((n, v) ->
                sb.append(scenario).append('.').append(n).append('=').append(v).append('\n')));
        return sb.toString();
    }

    /** Writes {@link #render} to {@code <dir>/<host>.properties} when {@value #REPORT_DIR_PROPERTY} is set. */
    public static void report(String host, Map<String, BusinessResult> byScenario) throws IOException {
        String dir = System.getProperty(REPORT_DIR_PROPERTY);
        if (dir == null || dir.isBlank() || byScenario.isEmpty()) {
            return;
        }
        Path d = Path.of(dir);
        Files.createDirectories(d);
        Files.writeString(d.resolve(host + ".properties"), render(byScenario), StandardCharsets.UTF_8);
    }
}
