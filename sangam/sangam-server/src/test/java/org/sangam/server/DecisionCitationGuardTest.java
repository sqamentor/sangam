// SPDX-License-Identifier: Apache-2.0
package org.sangam.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Traceability guard: every decision/defect citation in production code resolves to the right register and topic.
 *
 * <ul>
 *   <li>{@code ADR-D-n} must be a row {@code | D-n |} of the SANGAM.md decision register;</li>
 *   <li>{@code FLOWABLE-INTO-OPERATON D-nn} / {@code FLOWABLE-INTO-OPERATON FC-nn} must be a row of
 *       {@code spike/results/FLOWABLE-INTO-OPERATON.md};</li>
 *   <li>for both, the lines around the citation must contain a topic keyword of that id
 *       ({@code citations/decision-topics.txt}), so an id that points at the wrong entry fails;</li>
 *   <li>a bare {@code D-nn} / {@code FC-nn} is allowed only as a quoted finding code that exists in the FIO register,
 *       as SANGAM.md's {@code Rule D-0}, or as {@code {@code D-1}} date arithmetic.</li>
 * </ul>
 * Scope: every {@code .java} file under a module's {@code src/main}.
 */
class DecisionCitationGuardTest {

    private static final Pattern CITE = Pattern.compile("(?<![A-Za-z0-9-])(ADR-|FLOWABLE-INTO-OPERATON )?(D|FC)-(\\d+)\\b");

    @Test
    void everyCitationResolvesToItsRegisterAndTopic() throws IOException {
        Path sangam = Path.of("..").toAbsolutePath().normalize();
        Path repo = sangam.getParent();
        Set<String> adr = ids(Files.readString(repo.resolve("SANGAM.md")), "^\\|\\s*(D-\\d+)\\s*\\|");
        Set<String> fio = ids(Files.readString(repo.resolve("spike/results/FLOWABLE-INTO-OPERATON.md")),
                "^\\|\\s*((?:D|FC)-\\d+)\\s*\\|");
        Map<String, List<String>> topics = topics();
        List<String> errors = new ArrayList<>();
        int checked = 0;
        List<Path> sources;
        try (Stream<Path> s = Files.walk(sangam)) {
            sources = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/"))
                    .filter(p -> !p.toString().contains("node_modules")).toList();
        }
        for (Path p : sources) {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = CITE.matcher(lines.get(i));
                while (m.find()) {
                    checked++;
                    String where = sangam.relativize(p) + ":" + (i + 1) + " " + m.group();
                    String id = m.group(2) + "-" + m.group(3);
                    String prefix = m.group(1);
                    String line = lines.get(i);
                    if (prefix == null) {
                        boolean code = m.start() > 0 && line.charAt(m.start() - 1) == '"'
                                && m.end() < line.length() && line.charAt(m.end()) == '"';
                        String before = line.substring(Math.max(0, m.start() - 7), m.start());
                        if (code && fio.contains(id)) {
                            continue;
                        }
                        if (id.equals("D-0") && before.toLowerCase(Locale.ROOT).endsWith("rule ")) {
                            continue;
                        }
                        if (id.equals("D-1") && before.endsWith("{@code ")) {
                            continue;
                        }
                        errors.add(where + ": bare id; write ADR-" + id + " (SANGAM.md) or FLOWABLE-INTO-OPERATON " + id);
                        continue;
                    }
                    boolean isAdr = prefix.startsWith("ADR");
                    if (isAdr ? !(id.startsWith("D-") && adr.contains(id)) : !fio.contains(id)) {
                        errors.add(where + ": not in " + (isAdr ? "the SANGAM.md decision register" : "FLOWABLE-INTO-OPERATON.md"));
                        continue;
                    }
                    String key = (isAdr ? "ADR:" : "FIO:") + id;
                    if (id.startsWith("FC-")) {
                        continue; // FC ids: existence only (topic map covers decisions/defects)
                    }
                    List<String> kws = topics.get(key);
                    String ctx = String.join(" ", lines.subList(Math.max(0, i - 3), Math.min(lines.size(), i + 4)))
                            .toLowerCase(Locale.ROOT);
                    if (kws == null) {
                        errors.add(where + ": no topic keywords for " + key + " in citations/decision-topics.txt");
                    } else if (kws.stream().noneMatch(ctx::contains)) {
                        errors.add(where + ": context matches none of the topics of " + key + " " + kws);
                    }
                }
            }
        }
        assertTrue(checked > 100, "the scan found the citations (" + checked + ")");
        assertTrue(errors.isEmpty(), errors.size() + " citation(s) do not resolve:\n" + String.join("\n", errors));
    }

    private static Set<String> ids(String doc, String regex) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(doc);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Map<String, List<String>> topics() throws IOException {
        Map<String, List<String>> out = new HashMap<>();
        try (InputStream in = DecisionCitationGuardTest.class.getResourceAsStream("/citations/decision-topics.txt")) {
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                l = l.strip().replace("﻿", "");
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                int eq = l.indexOf('=');
                out.put(l.substring(0, eq), List.of(l.substring(eq + 1).split("\\|")));
            }
        }
        return out;
    }
}
