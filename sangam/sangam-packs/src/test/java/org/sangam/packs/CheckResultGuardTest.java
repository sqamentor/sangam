// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * SL-115: no shipped model pre-fills the result of a check with a default (the property packgen check_results.py
 * enforces at generation). ConnectorCallDelegate always writes {@code <prefix>Status} but writes a result variable
 * ({@code <prefix><Key>}) only when the answer carries it, so a {@code defaults} value of such a variable stood in for a
 * check that failed, was never answered or answered without the key (SL-113: fundsAvailable=999999999999).
 *
 * <p>Asserted over every BPMN under {@code src/main/resources/packs}: (1) a non-null default of a connector result of
 * the same process or of a process it calls is listed, with a reason or an open question, in
 * {@code META-INF/sangam/check-results.json}, and every listed entry still exists; (2) every clear step
 * ({@code <check>_clear}, common.checked_query / availability_query) leads straight to the check it clears and clears
 * only that check's results; (3) every cleared result has an explicit "not confirmed" condition ({@code <var> == null})
 * in its process, except the two results no gateway reads.
 */
class CheckResultGuardTest {

    static final Path RESOURCES = Path.of("src", "main", "resources");
    static final Pattern PROC = Pattern.compile("<process\\b[^>]*\\bid=\"([^\"]+)\"[^>]*>(.*?)</process>", Pattern.DOTALL);
    static final Pattern TASK = Pattern.compile("<serviceTask\\b[^>]*\\bid=\"([^\"]+)\"[^>]*>(.*?)</serviceTask>",
            Pattern.DOTALL);
    static final Pattern FIELD = Pattern.compile(
            "<operaton:field name=\"(\\w+)\"><operaton:string>([^<]*)</operaton:string>");
    static final Pattern CALL = Pattern.compile("calledElement=\"([^\"]+)\"");
    static final Pattern FLOW = Pattern.compile("<sequenceFlow\\b[^>]*\\bsourceRef=\"([^\"]+)\"[^>]*\\btargetRef=\"([^\"]+)\"");
    static final Pattern CONDITION = Pattern.compile("<conditionExpression[^>]*>([^<]*)</conditionExpression>");

    /** Cleared results that no gateway reads (shown to a person only); (process, variable). */
    static final Set<String> NOT_GATED = Set.of("sangam-sec-tel-ucc-complaint consentFound",
            "sangam-ifms-receipt scrollAmount");

    record Proc(String path, String body, Map<String, String> prefixes, Map<String, Map<String, String>> tasks,
                Set<String> calls) {
    }

    static Map<String, Proc> parse(Map<String, String> xmlByPath) {
        Map<String, Proc> out = new LinkedHashMap<>();
        xmlByPath.forEach((path, xml) -> {
            Matcher p = PROC.matcher(xml);
            while (p.find()) {
                Map<String, String> prefixes = new LinkedHashMap<>();
                Map<String, Map<String, String>> tasks = new LinkedHashMap<>();
                Matcher t = TASK.matcher(p.group(2));
                while (t.find()) {
                    Map<String, String> fields = new HashMap<>();
                    Matcher f = FIELD.matcher(t.group(2));
                    while (f.find()) {
                        fields.put(f.group(1), f.group(2));
                    }
                    tasks.put(t.group(1), fields);
                    String c = fields.get("connector");
                    if (c != null && !c.isBlank()) {
                        String pre = fields.get("resultPrefix");
                        prefixes.putIfAbsent(pre == null || pre.isBlank() ? c.substring(c.lastIndexOf('.') + 1) : pre, c);
                    }
                }
                Set<String> calls = new LinkedHashSet<>();
                Matcher c = CALL.matcher(p.group(2));
                while (c.find()) {
                    calls.add(c.group(1));
                }
                out.put(p.group(1), new Proc(path, p.group(2), prefixes, tasks, calls));
            }
        });
        return out;
    }

    static boolean isResultOf(String variable, String prefix) {
        return variable.length() > prefix.length() && variable.startsWith(prefix)
                && Character.isUpperCase(variable.charAt(prefix.length()));
    }

    /** "process variable" -> where, for every non-null default of a connector result (own or called process). */
    static Map<String, String> prefilled(Map<String, Proc> procs) {
        Map<String, String> out = new LinkedHashMap<>();
        procs.forEach((pid, proc) -> {
            Map<String, String> prefixes = new LinkedHashMap<>();
            Set<String> seen = new LinkedHashSet<>(Set.of(pid));
            Deque<String> todo = new ArrayDeque<>(List.of(pid));
            while (!todo.isEmpty()) {
                Proc q = procs.get(todo.pop());
                if (q == null) {
                    continue;
                }
                q.prefixes().forEach(prefixes::putIfAbsent);
                q.calls().stream().filter(seen::add).forEach(todo::add);
            }
            proc.tasks().forEach((tid, fields) -> {
                String defaults = fields.get("defaults");
                if (defaults == null) {
                    return;
                }
                for (String pair : defaults.split(";")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0 || "null".equals(pair.substring(eq + 1).trim())) {
                        continue;
                    }
                    String name = pair.substring(0, eq).trim();
                    prefixes.entrySet().stream().filter(e -> isResultOf(name, e.getKey())).findFirst()
                            .ifPresent(e -> out.put(pid + " " + name, proc.path() + " " + tid + ": " + pair.trim()
                                    + " pre-fills the result of " + e.getValue()));
                }
            });
        });
        return out;
    }

    static Map<String, String> shippedModels() throws IOException {
        Map<String, String> xml = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(RESOURCES.resolve("packs"))) {
            for (Path f : files.filter(f -> f.toString().endsWith(".bpmn")).sorted().toList()) {
                xml.put(RESOURCES.relativize(f).toString().replace('\\', '/'), Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        return xml;
    }

    static JsonNode reviewed() throws IOException {
        return new ObjectMapper().readTree(RESOURCES.resolve("META-INF/sangam/check-results.json").toFile());
    }

    @Test
    void noShippedDefaultPrefillsACheckResultUnlessReviewed() throws IOException {
        Map<String, String> found = prefilled(parse(shippedModels()));
        assertTrue(found.size() > 10, "the scan sees the shipped defaults: " + found.size());
        Set<String> listed = new TreeSet<>();
        JsonNode r = reviewed();
        for (String list : List.of("allowed", "pending")) {
            for (JsonNode e : r.path(list)) {
                String key = e.path("process").asText() + " " + e.path("variable").asText();
                String why = e.path("allowed".equals(list) ? "reason" : "question").asText("");
                assertFalse(why.isBlank(), list + " entry without a reason: " + key);
                assertTrue(listed.add(key), "listed twice: " + key);
            }
        }
        List<String> unreviewed = new ArrayList<>();
        found.forEach((k, where) -> {
            if (!listed.contains(k)) {
                unreviewed.add(where);
            }
        });
        assertEquals(List.of(), unreviewed, "defaults that pre-fill a check result (use common.checked_query)");
        Set<String> stale = new TreeSet<>(listed);
        stale.removeAll(found.keySet());
        assertEquals(Set.of(), stale, "reviewed entries whose default no longer exists");
        System.out.println("[SL-115] open questions: " + r.path("pending"));
    }

    @Test
    void everyClearStepLeadsStraightToTheCheckItClears() throws IOException {
        List<String> bad = new ArrayList<>();
        int clears = 0;
        for (Map.Entry<String, Proc> e : parse(shippedModels()).entrySet()) {
            Proc p = e.getValue();
            for (Map.Entry<String, Map<String, String>> t : p.tasks().entrySet()) {
                if (!t.getKey().endsWith("_clear")) {
                    continue;
                }
                clears++;
                String check = t.getKey().substring(0, t.getKey().length() - "_clear".length());
                Map<String, String> target = p.tasks().get(check);
                List<String> next = new ArrayList<>();
                Matcher f = FLOW.matcher(p.body());
                while (f.find()) {
                    if (f.group(1).equals(t.getKey())) {
                        next.add(f.group(2));
                    }
                }
                if (target == null || target.get("connector") == null || !next.equals(List.of(check))) {
                    bad.add(e.getKey() + " " + t.getKey() + ": next " + next + ", not the check " + check);
                    continue;
                }
                String c = target.get("connector");
                String pre = target.get("resultPrefix") == null ? c.substring(c.lastIndexOf('.') + 1)
                        : target.get("resultPrefix");
                for (String pair : t.getValue().getOrDefault("values", "").split(";")) {
                    String name = pair.substring(0, Math.max(0, pair.indexOf('='))).trim();
                    if (!pair.trim().endsWith("=null") || !isResultOf(name, pre)) {
                        bad.add(e.getKey() + " " + t.getKey() + ": " + pair + " is not a cleared result of " + pre);
                    }
                }
            }
        }
        assertTrue(clears >= 40, "clear steps shipped: " + clears);
        assertEquals(List.of(), bad);
    }

    @Test
    void everyClearedResultHasANotConfirmedCondition() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Proc> e : parse(shippedModels()).entrySet()) {
            List<String> conditions = new ArrayList<>();
            Matcher c = CONDITION.matcher(e.getValue().body());
            while (c.find()) {
                conditions.add(c.group(1));
            }
            for (Map.Entry<String, Map<String, String>> t : e.getValue().tasks().entrySet()) {
                if (!t.getKey().endsWith("_clear")) {
                    continue;
                }
                for (String pair : t.getValue().getOrDefault("values", "").split(";")) {
                    String name = pair.substring(0, Math.max(0, pair.indexOf('='))).trim();
                    if (NOT_GATED.contains(e.getKey() + " " + name)) {
                        continue;
                    }
                    if (conditions.stream().noneMatch(x -> x.contains(name + " == null"))) {
                        missing.add(e.getKey() + " " + name);
                    }
                }
            }
        }
        assertEquals(List.of(), missing, "cleared results without an explicit not-confirmed branch");
    }

    // ------------------------------------------------------------------ the detection itself, on synthetic models

    static String model(String pid, String defaults, String connector, String prefix, String call) {
        return "<process id=\"" + pid + "\" isExecutable=\"true\">"
                + "<serviceTask id=\"d\" operaton:class=\"x\"><extensionElements><operaton:field name=\"defaults\">"
                + "<operaton:string>" + defaults + "</operaton:string></operaton:field></extensionElements></serviceTask>"
                + (connector == null ? "" : "<serviceTask id=\"q\" operaton:class=\"y\"><extensionElements>"
                + "<operaton:field name=\"connector\"><operaton:string>" + connector + "</operaton:string></operaton:field>"
                + (prefix == null ? "" : "<operaton:field name=\"resultPrefix\"><operaton:string>" + prefix
                + "</operaton:string></operaton:field>") + "</extensionElements></serviceTask>")
                + (call == null ? "" : "<callActivity id=\"c\" calledElement=\"" + call + "\"/>") + "</process>";
    }

    @Test
    void theScanFlagsAPrefilledResultAndNothingElse() {
        Map<String, String> found = prefilled(parse(Map.of("a.bpmn",
                model("p", "panValid=true;panRef=null;pancake=1;amount=0", "pan.verify", "pan", null))));
        assertEquals(Set.of("p panValid"), found.keySet(), "a null default fills nothing; 'pancake' is not pan + Key");
        // the default prefix is the last segment of the connector name
        assertEquals(Set.of("p verifyOk"), prefilled(parse(Map.of("a.bpmn",
                model("p", "verifyOk=true", "pan.verify", null, null)))).keySet());
    }

    @Test
    void theScanFollowsTheDefaultIntoACalledProcess() {
        Map<String, String> xml = new HashMap<>();
        xml.put("parent.bpmn", model("parent", "screenHit=false", null, null, "child"));
        xml.put("child.bpmn", model("child", "x=1", "sanctions.screen", "screen", null));
        assertEquals(Set.of("parent screenHit"), prefilled(parse(xml)).keySet());
    }
}
