// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * SL-55: the rejection reason-code catalogue ({@code META-INF/sangam/reason-codes.json}, written by packgen from
 * {@code reason_codes.py}) covers every code a generated pack uses, and its codes are unique and labelled.
 *
 * <p>Uses checked: every {@code rejectionReasonCodes} value set in a BPMN or CMMN model (SetVariables steps: literal
 * codes, comma lists, {@code @variable} copies), in a pack manifest (acceptance-run answers) or a seed, and the options
 * a form's {@code rejectionReasonCodes} field offers. A copy {@code @variable} must be a catalogued dynamic source; when
 * the source is a DMN decision, the string outputs of that column (other than the declared non-reasons) must be
 * catalogued too. {@code DEEMED_REFUSAL}, set by CaseRecordDelegate for a deemed refusal, must be catalogued.
 */
class ReasonCodeCatalogueTest {

    static final Path RES = Path.of("src", "main", "resources");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]*");
    /** A model assignment ({@code rejectionReasonCodes=X,Y}) or a JSON member ({@code "rejectionReasonCodes": "X"}). */
    static final Pattern USE = Pattern.compile("rejectionReasonCodes(?:=|\"\\s*:\\s*\")([^;<\"]*)");

    record Catalogue(Set<String> codes, Map<String, JsonNode> dynamic, List<String> problems) {
    }

    static Catalogue load(JsonNode doc) {
        Set<String> codes = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (JsonNode c : doc.path("codes")) {
            String code = c.path("code").asText("");
            if (!CODE.matcher(code).matches()) {
                problems.add("malformed code '" + code + "'");
            }
            if (!codes.add(code)) {
                problems.add("duplicate code " + code);
            }
            if (c.path("label").path("en").asText("").isBlank()) {
                problems.add("no English label for " + code);
            }
        }
        Map<String, JsonNode> dynamic = new HashMap<>();
        for (JsonNode d : doc.path("dynamic")) {
            if (dynamic.put(d.path("variable").asText(), d) != null) {
                problems.add("duplicate dynamic variable " + d.path("variable").asText());
            }
        }
        return new Catalogue(codes, dynamic, problems);
    }

    /** The uncatalogued codes of one file's text ({@code where} names it in the result). */
    static List<String> unknownUses(String where, String text, Catalogue cat) {
        List<String> out = new ArrayList<>();
        Matcher m = USE.matcher(text);
        while (m.find()) {
            String spec = m.group(1).trim();
            if (spec.startsWith("@")) {
                if (!cat.dynamic().containsKey(spec.substring(1))) {
                    out.add(where + ": copy " + spec + " is not a catalogued dynamic source");
                }
                continue;
            }
            for (String code : spec.split(",")) {
                if (!code.isBlank() && !cat.codes().contains(code.trim())) {
                    out.add(where + ": " + code.trim());
                }
            }
        }
        return out;
    }

    /** The string outputs of column {@code output} of DMN decision {@code decision} in any pack. */
    static Set<String> decisionOutputs(String decision, String output) throws IOException {
        Set<String> values = new TreeSet<>();
        Pattern dec = Pattern.compile("<decision\\b[^>]*\\bid=\"" + Pattern.quote(decision) + "\"[\\s\\S]*?</decision>");
        Pattern out = Pattern.compile("<output\\b[^>]*\\bname=\"([^\"]+)\"");
        Pattern rule = Pattern.compile("<rule\\b[\\s\\S]*?</rule>");
        Pattern entry = Pattern.compile("<outputEntry\\b[^>]*>\\s*<text>([\\s\\S]*?)</text>");
        boolean found = false;
        for (Path f : files(".dmn")) {
            Matcher d = dec.matcher(Files.readString(f));
            if (!d.find()) {
                continue;
            }
            found = true;
            String xml = d.group();
            List<String> outs = new ArrayList<>();
            Matcher o = out.matcher(xml);
            while (o.find()) {
                outs.add(o.group(1));
            }
            int col = outs.indexOf(output);
            assertTrue(col >= 0, decision + " has no output " + output + " (" + f + ")");
            Matcher r = rule.matcher(xml);
            while (r.find()) {
                Matcher e = entry.matcher(r.group());
                for (int i = 0; e.find(); i++) {
                    String v = e.group(1).trim();
                    if (i == col && v.startsWith("\"") && v.endsWith("\"") && v.length() > 1) {
                        values.add(v.substring(1, v.length() - 1));
                    }
                }
            }
        }
        assertTrue(found, "decision " + decision + " of a dynamic reason source is not in any pack");
        return values;
    }

    static List<Path> files(String suffix) throws IOException {
        try (Stream<Path> s = Files.walk(RES.resolve("packs"))) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    // ---- the generated catalogue and packs -------------------------------------------------------------------------

    @Test
    void theCatalogueHasUniqueLabelledCodesIncludingThePlatformOnes() throws IOException {
        Catalogue cat = load(JSON.readTree(RES.resolve("META-INF/sangam/reason-codes.json").toFile()));
        assertEquals(List.of(), cat.problems());
        assertTrue(cat.codes().containsAll(List.of("DEEMED_REFUSAL", "EXECUTION_FAILED", "CLARIFICATION_LAPSED")),
                "codes the platform sets itself (CaseRecordDelegate, packgen common.AUTO_REJECTION_REASONS)");
    }

    @Test
    void everyReasonCodeAGeneratedPackUsesIsCatalogued() throws IOException {
        Catalogue cat = load(JSON.readTree(RES.resolve("META-INF/sangam/reason-codes.json").toFile()));
        List<String> unknown = new ArrayList<>();
        int uses = 0;
        List<Path> scanned = new ArrayList<>();
        for (String suffix : List.of(".bpmn", ".cmmn", "pack.json", "seed.json")) {
            scanned.addAll(files(suffix));
        }
        for (Path f : scanned) {
            String text = Files.readString(f);
            Matcher m = USE.matcher(text);
            while (m.find()) {
                uses++;
            }
            unknown.addAll(unknownUses(RES.relativize(f).toString(), text, cat));
        }
        // the options a form's reason field offers
        for (Path f : files(".form.json")) {
            for (JsonNode field : JSON.readTree(f.toFile()).path("fields")) {
                if ("rejectionReasonCodes".equals(field.path("id").asText())) {
                    for (JsonNode opt : field.path("options")) {
                        String v = opt.isTextual() ? opt.asText() : opt.path("value").asText();
                        if (!cat.codes().contains(v)) {
                            unknown.add(RES.relativize(f) + ": option " + v);
                        }
                    }
                }
            }
        }
        // dynamic sources from a decision: every rejecting output value is catalogued
        for (Map.Entry<String, JsonNode> d : cat.dynamic().entrySet()) {
            JsonNode src = d.getValue();
            if (src.hasNonNull("decision")) {
                Set<String> not = new HashSet<>();
                src.path("notReasons").forEach(n -> not.add(n.asText()));
                for (String v : decisionOutputs(src.path("decision").asText(), d.getKey())) {
                    if (!not.contains(v) && !cat.codes().contains(v)) {
                        unknown.add("decision " + src.path("decision").asText() + ": " + v);
                    }
                }
            } else {
                assertTrue(src.path("open").asBoolean(false) && !src.path("vocabulary").asText("").isBlank(),
                        "dynamic source " + d.getKey() + " needs a decision or an open vocabulary");
            }
        }
        assertTrue(uses > 50, "the packs set reason codes: " + uses);
        assertEquals(List.of(), unknown, "reason codes used by the packs but not in META-INF/sangam/reason-codes.json");
    }

    // ---- the check itself ----------------------------------------------------------------------------------------------

    @Test
    void unknownCodesCopiesAndDuplicatesAreReported() throws IOException {
        Catalogue cat = load(JSON.readTree("""
                {"codes": [{"code": "A_CODE", "label": {"en": "A"}}, {"code": "A_CODE", "label": {"en": "again"}},
                           {"code": "no-label", "label": {}}],
                 "dynamic": [{"variable": "known", "open": true, "vocabulary": "v"}]}"""));
        assertEquals(List.of("duplicate code A_CODE", "malformed code 'no-label'", "no English label for no-label"),
                cat.problems());
        String model = "<operaton:string>rejectionReasonCodes=A_CODE,B_CODE;x=1</operaton:string>"
                + "<operaton:string>rejectionReasonCodes=@known</operaton:string>"
                + "<operaton:string>rejectionReasonCodes=@other</operaton:string>"
                + "{\"rejectionReasonCodes\": \"C_CODE\"}";
        List<String> unknown = unknownUses("m", model, cat);
        assertEquals(List.of("m: B_CODE", "m: copy @other is not a catalogued dynamic source", "m: C_CODE"), unknown);
        assertFalse(unknown.contains("m: A_CODE"));
    }
}
