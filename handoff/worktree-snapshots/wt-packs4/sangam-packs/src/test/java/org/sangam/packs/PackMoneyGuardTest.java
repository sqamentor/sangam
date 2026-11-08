// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Build guard for money in packs (SD-398 / SG-20, ADR-D-26): every amount a pack ships is an integer number of minor
 * units (paise for INR). The naming convention is packgen's {@code money.py}, shipped as
 * {@code META-INF/sangam/money.json}. The guard fails when, anywhere under {@code packs/} or {@code profiles/}:
 * <ol>
 *   <li>a DMN column has typeRef {@code double} or {@code float} (binary floating point);</li>
 *   <li>a money DMN column (by the naming convention) is not {@code number}, or one of its literal entries is not a
 *       whole number, or a money output expression does not round once to a whole minor unit;</li>
 *   <li>a money form field is not type {@code money} (or {@code readonly}) with the pack currency and its exponent;</li>
 *   <li>a money value in an acceptance scenario ({@code startVariables}, run answers) or in a {@code SetVariables}
 *       default / value of a BPMN is not a whole number;</li>
 *   <li>a pack uses money but does not declare {@code money} (unit MINOR, currency, exponent, variables) in
 *       {@code pack.json}, or the declaration misses a money variable its DMN or forms use.</li>
 * </ol>
 */
class PackMoneyGuardTest {

    static final Path RES = Path.of("src", "main", "resources");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern TYPEREF = Pattern.compile("typeRef=\"([^\"]+)\"");
    static final Pattern INPUT = Pattern.compile(
            "<input id=\"[^\"]*\"[^>]*><inputExpression[^>]*typeRef=\"([^\"]+)\"><text>([^<]*)</text>");
    static final Pattern OUTPUT = Pattern.compile("<output id=\"[^\"]*\"[^>]*name=\"([^\"]+)\" typeRef=\"([^\"]+)\"");
    static final Pattern RULE = Pattern.compile("<rule id=\"[^\"]*\">(.*?)</rule>", Pattern.DOTALL);
    static final Pattern ENTRY = Pattern.compile("<(inputEntry|outputEntry) id=\"[^\"]*\"><text>([^<]*)</text>");
    static final Pattern WORD = Pattern.compile("[A-Za-z_]\\w*");
    static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d+(\\.\\d+)?(?![\\w.])");
    static final Pattern SETV = Pattern.compile(
            "SetVariablesDelegate\"><extensionElements>(.*?)</extensionElements>", Pattern.DOTALL);
    static final Pattern SPEC = Pattern.compile("<operaton:string>([^<]*)</operaton:string>");
    static final Set<String> TEST_WORDS = Set.of("null", "not", "true", "false");
    static final List<String> ROUNDING = List.of("floor(", "ceiling(", "decimal(", "round half up(",
            "round half down(", "round up(", "round down(");

    static JsonNode convention;
    static Pattern suffix;
    static Set<String> suffixesLower = new HashSet<>();

    @BeforeAll
    static void load() throws IOException {
        convention = JSON.readTree(RES.resolve("META-INF/sangam/money.json").toFile());
        assertEquals("MINOR", convention.path("unit").asText());
        List<String> s = new ArrayList<>();
        convention.path("suffixes").forEach(n -> {
            s.add(Pattern.quote(n.asText()));
            suffixesLower.add(n.asText().toLowerCase());
        });
        assertTrue(s.size() > 10, "money suffixes");
        suffix = Pattern.compile("(?:^|[a-z0-9])(" + String.join("|", s) + ")$");
    }

    /** packgen money.is_money, from the shipped convention. */
    static boolean isMoney(String name) {
        if (convention.path("notMoney").has(name)) {
            return false;
        }
        for (JsonNode e : convention.path("extra")) {
            if (e.asText().equals(name)) {
                return true;
            }
        }
        if (name.isEmpty()) {
            return false;
        }
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return suffix.matcher(cap).find() || suffixesLower.contains(name.toLowerCase());
    }

    static String text(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Path> files(String dir, String ext) throws IOException {
        try (Stream<Path> s = Files.walk(RES.resolve(dir))) {
            return s.filter(p -> p.toString().endsWith(ext)).sorted().toList();
        }
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    static boolean literal(String entry) {
        Matcher m = WORD.matcher(entry);
        while (m.find()) {
            if (!TEST_WORDS.contains(m.group())) {
                return false;
            }
        }
        return true;
    }

    /** The problems of one DMN file; {@code moneyColumns} collects the money column names it has. */
    static List<String> dmnProblems(String where, String xml, Set<String> moneyColumns) {
        List<String> out = new ArrayList<>();
        Matcher t = TYPEREF.matcher(xml);
        while (t.find()) {
            if (t.group(1).equalsIgnoreCase("double") || t.group(1).equalsIgnoreCase("float")) {
                out.add(where + ": typeRef " + t.group(1) + " (binary floating point)");
            }
        }
        List<Boolean> inMoney = new ArrayList<>();
        Matcher in = INPUT.matcher(xml);
        while (in.find()) {
            String expr = unescape(in.group(2)).trim();
            boolean m = expr.matches("[A-Za-z_]\\w*") && isMoney(expr);
            inMoney.add(m);
            if (m) {
                moneyColumns.add(expr);
                if (!"number".equals(in.group(1))) {
                    out.add(where + ": money input " + expr + " has typeRef " + in.group(1) + " (must be number)");
                }
            }
        }
        List<Boolean> outMoney = new ArrayList<>();
        Matcher o = OUTPUT.matcher(xml);
        while (o.find()) {
            boolean m = isMoney(o.group(1));
            outMoney.add(m);
            if (m) {
                moneyColumns.add(o.group(1));
                if (!"number".equals(o.group(2))) {
                    out.add(where + ": money output " + o.group(1) + " has typeRef " + o.group(2) + " (must be number)");
                }
            }
        }
        Matcher r = RULE.matcher(xml);
        int rule = 0;
        while (r.find()) {
            int i = 0;
            int j = 0;
            Matcher e = ENTRY.matcher(r.group(1));
            while (e.find()) {
                boolean isInput = e.group(1).equals("inputEntry");
                int col = isInput ? i++ : j++;
                boolean money = isInput ? col < inMoney.size() && inMoney.get(col) : col < outMoney.size() && outMoney.get(col);
                if (!money) {
                    continue;
                }
                String v = unescape(e.group(2)).trim();
                if (literal(v)) {
                    Matcher n = NUMBER.matcher(v);
                    while (n.find()) {
                        if (n.group(1) != null) {
                            out.add(where + " rule " + rule + ": money " + (isInput ? "input" : "output") + " entry '" + v
                                    + "' is not a whole number of minor units");
                        }
                    }
                } else if (!isInput && !v.matches("[A-Za-z_]\\w*") && ROUNDING.stream().noneMatch(v::startsWith)) {
                    out.add(where + " rule " + rule + ": money output expression '" + v
                            + "' does not round once to a whole minor unit");
                }
            }
            rule++;
        }
        return out;
    }

    static boolean wholeNumber(JsonNode v) {
        return v.isIntegralNumber() || (v.isTextual() && v.asText().matches("-?\\d+"));
    }

    @Test
    void everyShippedAmountIsAnIntegerNumberOfMinorUnits() throws IOException {
        List<String> problems = new ArrayList<>();
        int packsWithMoney = 0;
        for (Path manifest : files("packs", "pack.json")) {
            JsonNode pack = JSON.readTree(manifest.toFile());
            String id = pack.path("id").asText();
            Set<String> used = new HashSet<>();
            JsonNode money = pack.path("money");
            String ccy = money.path("currency").asText(convention.path("defaultCurrency").asText());
            int exponent = convention.path("exponent").path(ccy).asInt(-1);
            for (JsonNode res : pack.path("resources")) {
                Path p = RES.resolve(res.path("path").asText());
                String kind = res.path("kind").asText();
                if (kind.equals("DMN")) {
                    problems.addAll(dmnProblems(res.path("path").asText(), text(p), used));
                } else if (kind.equals("FORM")) {
                    for (JsonNode f : JSON.readTree(p.toFile()).path("fields")) {
                        String fid = f.path("id").asText();
                        if (!isMoney(fid)) {
                            continue;
                        }
                        used.add(fid);
                        String type = f.path("type").asText();
                        if (!(type.equals("money") || type.equals("readonly")) || !ccy.equals(f.path("currency").asText())
                                || f.path("exponent").asInt(-2) != exponent) {
                            problems.add(res.path("path").asText() + ": money field " + fid + " is " + f
                                    + " (expected type money, currency " + ccy + ", exponent " + exponent + ")");
                        }
                    }
                } else if (kind.equals("BPMN")) {
                    Matcher s = SETV.matcher(text(p));
                    while (s.find()) {
                        Matcher spec = SPEC.matcher(s.group(1));
                        while (spec.find()) {
                            for (String pair : unescape(spec.group(1)).split(";")) {
                                int eq = pair.indexOf('=');
                                if (eq > 0 && isMoney(pair.substring(0, eq).trim())
                                        && pair.substring(eq + 1).trim().matches("-?\\d+\\.\\d+")) {
                                    problems.add(res.path("path").asText() + ": money default " + pair
                                            + " is not a whole number of minor units");
                                }
                            }
                        }
                    }
                }
            }
            for (JsonNode t : pack.path("tenantOverrides")) {
                problems.addAll(dmnProblems(t.path("path").asText(), text(RES.resolve(t.path("path").asText())), used));
            }
            for (JsonNode a : pack.path("acceptance")) {
                List<JsonNode> bags = new ArrayList<>();
                bags.add(a.path("startVariables"));
                a.path("run").path("answers").forEach(ans -> bags.add(ans.path("vars")));
                for (JsonNode bag : bags) {
                    bag.fieldNames().forEachRemaining(k -> {
                        if (isMoney(k) && bag.get(k).isNumber()) {
                            used.add(k);
                            if (!wholeNumber(bag.get(k))) {
                                problems.add(id + " " + a.path("id").asText() + ": money " + k + " = " + bag.get(k)
                                        + " is not a whole number of minor units");
                            }
                        }
                    });
                }
            }
            if (used.isEmpty()) {
                continue;
            }
            packsWithMoney++;
            if (!"MINOR".equals(money.path("unit").asText()) || exponent < 0) {
                problems.add(id + ": uses money " + used + " but pack.json declares no money {unit MINOR, currency, exponent}");
                continue;
            }
            Set<String> declared = new HashSet<>();
            money.path("variables").forEach(v -> declared.add(v.asText()));
            for (String u : used) {
                if (!declared.contains(u)) {
                    problems.add(id + ": money variable " + u + " is not in pack.json money.variables");
                }
            }
        }
        for (Path dmn : files("profiles", ".dmn")) {
            problems.addAll(dmnProblems(RES.relativize(dmn).toString(), text(dmn), new HashSet<>()).stream()
                    .filter(s -> s.contains("binary floating point")).toList());
        }
        assertTrue(packsWithMoney > 50, "packs with money: " + packsWithMoney);
        assertTrue(problems.isEmpty(), problems.size() + " money problem(s):\n" + String.join("\n", problems));
    }

    /** The guard itself: a double money column, a fractional paise boundary and an unrounded output are caught. */
    @Test
    void theGuardCatchesFloatingAndFractionalMoney() {
        String bad = "<definitions><decision id=\"d\"><decisionTable id=\"t\" hitPolicy=\"FIRST\">"
                + "<input id=\"i0\" label=\"A\"><inputExpression id=\"e0\" typeRef=\"double\"><text>amount</text></inputExpression></input>"
                + "<output id=\"o0\" label=\"L\" name=\"powerLimit\" typeRef=\"number\"/>"
                + "<output id=\"o1\" label=\"F\" name=\"ldAmount\" typeRef=\"number\"/>"
                + "<rule id=\"r0\"><inputEntry id=\"x\"><text>&gt; 2500000.5</text></inputEntry>"
                + "<outputEntry id=\"y\"><text>250000000</text></outputEntry>"
                + "<outputEntry id=\"z\"><text>contractValue * 0.1</text></outputEntry></rule>"
                + "</decisionTable></decision></definitions>";
        List<String> p = dmnProblems("bad.dmn", bad, new HashSet<>());
        assertTrue(p.stream().anyMatch(s -> s.contains("typeRef double")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("money input amount has typeRef double")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("2500000.5")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("does not round once")), p.toString());
        assertEquals(4, p.size(), p.toString());
        assertTrue(isMoney("amount") && isMoney("lastPay") && isMoney("authBandMax") && isMoney("powerLimit"));
        assertTrue(!isMoney("leaveBalance") && !isMoney("qualifyingService") && !isMoney("foirPct"));
    }
}
