// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

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
import org.junit.jupiter.api.Test;

import org.sangam.packs.core.RowEvidence;

/**
 * Decision 139 build guard over the shipped packs (packgen rounding.py):
 * <ol>
 *   <li>no BPMN computes with {@code *} or {@code /} in a JUEL {@code setVariable} expression (JUEL divides through a
 *       double and has no rounding policy); such amounts go through {@code MoneyCalculationDelegate};</li>
 *   <li>every money calculation names a rounding policy DMN that the same pack ships, with the policy columns;</li>
 *   <li>every policy row states its evidence ({@code VERIFIED} or {@code UNVERIFIED}), its permitted use
 *       ({@code PRODUCTION} only with {@code VERIFIED} evidence, a source, a provision and an explicit effective date,
 *       otherwise {@code SYNTHETIC_DEMO_ONLY}) and why (decision 288), and a pack that ships a
 *       {@code SYNTHETIC_DEMO_ONLY} row declares that decision a placeholder, so the readiness check blocks go-live
 *       until the tenant deploys a confirmed policy.</li>
 * </ol>
 */
class RoundingPolicyPacksTest {

    static final Path PACKS = Path.of("src", "main", "resources", "packs");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern JUEL_ARITHMETIC = Pattern.compile(
            "operaton:expression=\"\\$\\{execution\\.setVariable\\([^\"]*[*/][^\"]*\\)\\}\"");
    static final Pattern MONEY_TASK = Pattern.compile(
            "operaton:class=\"org\\.sangam\\.packs\\.core\\.MoneyCalculationDelegate\"><extensionElements>(.*?)"
                    + "</extensionElements>", Pattern.DOTALL);
    static final Pattern POLICY_FIELD = Pattern.compile(
            "<operaton:field name=\"policy\"><operaton:string>([^<]+)</operaton:string>");
    static final Pattern RULE = Pattern.compile("<rule id=\"[^\"]*\">(.*?)</rule>", Pattern.DOTALL);
    static final Pattern OUT = Pattern.compile("<outputEntry id=\"[^\"]*\"><text>([^<]*)</text></outputEntry>");
    static final List<String> COLUMNS = List.of("policyId", "policyVersion", "evidenceStatus", "permittedUse",
            "evidenceNote", "mode", "quantumMinor", "negatives", "residual", "application", "stage", "jurisdiction",
            "scheme", "purpose", "source", "provision", "effectiveFrom", "effectiveTo");

    static String text(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void moneyIsComputedOnlyByPolicyAndEveryPolicyIsVerifiedOrABlockedDemo() throws IOException {
        List<String> problems = new ArrayList<>();
        int calculations = 0;
        List<Path> manifests;
        try (Stream<Path> s = Files.walk(PACKS)) {
            manifests = s.filter(p -> p.getFileName().toString().equals("pack.json")).sorted().toList();
        }
        for (Path manifest : manifests) {
            Path dir = manifest.getParent();
            JsonNode pack = JSON.readTree(manifest.toFile());
            Set<String> placeholders = new HashSet<>();
            pack.path("placeholders").forEach(p -> placeholders.add(p.path("decision").asText()));
            List<Path> files;
            try (Stream<Path> s = Files.list(dir)) {
                files = s.sorted().toList();
            }
            for (Path bpmn : files.stream().filter(p -> p.toString().endsWith(".bpmn")).toList()) {
                String xml = text(bpmn);
                Matcher j = JUEL_ARITHMETIC.matcher(xml);
                while (j.find()) {
                    problems.add(PACKS.relativize(bpmn) + ": JUEL arithmetic " + j.group());
                }
                Matcher m = MONEY_TASK.matcher(xml);
                while (m.find()) {
                    calculations++;
                    Matcher pf = POLICY_FIELD.matcher(m.group(1));
                    if (!pf.find()) {
                        continue; // SUM of rounded amounts or an exact ratio: no rounding, no policy
                    }
                    String key = pf.group(1);
                    Path dmn = dir.resolve(key + ".dmn");
                    if (!Files.exists(dmn)) {
                        problems.add(PACKS.relativize(bpmn) + ": policy " + key + " is not shipped by the pack");
                        continue;
                    }
                    problems.addAll(policyProblems(PACKS.relativize(dmn).toString(), text(dmn), placeholders, key));
                }
            }
        }
        assertTrue(calculations >= 10, "money calculations found: " + calculations);
        assertTrue(problems.isEmpty(), problems.size() + " problem(s):\n" + String.join("\n", problems));
    }

    static List<String> policyProblems(String where, String xml, Set<String> placeholders, String key) {
        List<String> out = new ArrayList<>();
        for (String c : COLUMNS) {
            if (!xml.contains("name=\"" + c + "\"")) {
                out.add(where + ": no policy column " + c);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        Matcher r = RULE.matcher(xml);
        int rows = 0;
        while (r.find()) {
            rows++;
            List<String> v = new ArrayList<>();
            Matcher o = OUT.matcher(r.group(1));
            while (o.find()) {
                v.add(o.group(1).replace("&quot;", "\"").replace("\"", "").trim());
            }
            String evidence = v.get(COLUMNS.indexOf("evidenceStatus"));
            String use = v.get(COLUMNS.indexOf("permittedUse"));
            if (!evidence.equals("VERIFIED") && !evidence.equals("UNVERIFIED")) {
                out.add(where + " row " + rows + ": evidenceStatus " + evidence + " (VERIFIED or UNVERIFIED)");
            }
            if (v.get(COLUMNS.indexOf("evidenceNote")).isEmpty()) {
                out.add(where + " row " + rows + ": no evidenceNote (decision 288)");
            }
            if (use.equals("PRODUCTION")) {
                if (!evidence.equals("VERIFIED")) {
                    out.add(where + " row " + rows + ": PRODUCTION with evidence " + evidence + " (decision 288)");
                }
                if (v.get(COLUMNS.indexOf("source")).isEmpty() || v.get(COLUMNS.indexOf("provision")).isEmpty()
                        || v.get(COLUMNS.indexOf("source")).startsWith("none found")) {
                    out.add(where + " row " + rows + ": PRODUCTION without a source and provision");
                }
                String from = v.get(COLUMNS.indexOf("effectiveFrom"));
                if (from.isEmpty() || from.equals(RowEvidence.NO_EFFECTIVE_DATE)) {
                    out.add(where + " row " + rows + ": PRODUCTION without an explicit effective date (decision 280)");
                }
            } else if (use.equals("SYNTHETIC_DEMO_ONLY")) {
                if (!placeholders.contains(key)) {
                    out.add(where + " row " + rows + ": SYNTHETIC_DEMO_ONLY policy " + key + " is not a pack"
                            + " placeholder");
                }
            } else {
                out.add(where + " row " + rows + ": permittedUse " + use + " cannot ship (PRODUCTION or"
                        + " SYNTHETIC_DEMO_ONLY)");
            }
        }
        if (rows == 0) {
            out.add(where + ": no policy row");
        }
        return out;
    }
}
