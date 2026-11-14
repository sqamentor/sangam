// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UpgradeCheckTest {

    @TempDir
    Path dir;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final Clock clock = Clock.fixed(Instant.parse("2027-06-01T10:00:00Z"), ZoneOffset.UTC);
    final UpgradeCheck check = new UpgradeCheck(new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8), clock);

    static final String RULES = """
            {"release":"9.9.0",
             "models":{"constructs":[
               {"namespace":"http://www.omg.org/spec/BPMN/20100524/MODEL","element":"complexBehaviorDefinition","status":"removed","note":"not executable"},
               {"namespace":"*","element":"*","attribute":"asyncBefore","attributeNamespace":"http://operaton.org/schema/1.0/bpmn","status":"deprecated","note":"use sangam:async"}]},
             "config":{"removed":{"sangam.old-feature":"gone in 9.9"},"renamed":{"sangam.engine.lockOwner":"sangam.engine.job-lock-owner"},
                       "removedEnv":{"SANGAM_OLD_FEATURE":"gone in 9.9"},"renamedEnv":{}}}
            """;

    static final String DEPRECATIONS = """
            policy:
              minimumSupportDays: 365
            deprecations:
              - method: GET
                path: "/cases/search"
                deprecated: 2026-01-01
                sunset: 2027-01-01
                link: "https://x/changelog"
                successor: "/cases/query"
              - method: POST
                path: "/tasks/{id}/claim"
                deprecated: 2027-01-01
                sunset: 2028-01-01
                link: "https://x/changelog"
              - method: GET
                path: "/later"
                deprecated: 2028-01-01
                sunset: 2029-01-01
                link: "https://x/changelog"
            """;

    Path write(String name, String text) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    JsonNode run(List<String> models, List<String> configs, int expectedExit) throws Exception {
        Path rules = write("rules.json", RULES);
        Path deps = write("deps.yaml", DEPRECATIONS);
        int exit = check.run(models, configs, rules.toString(), deps.toString(), null);
        assertEquals(expectedExit, exit, err.toString(StandardCharsets.UTF_8));
        return new ObjectMapper().readTree(out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void reportsRemovedAndDeprecatedModelConstructs() throws Exception {
        write("models/a.bpmn", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn">
                  <process id="p"><task id="t1" operaton:asyncBefore="true"/>
                    <subProcess id="s"><multiInstanceLoopCharacteristics><complexBehaviorDefinition id="c"/></multiInstanceLoopCharacteristics></subProcess>
                  </process>
                </definitions>
                """);
        write("models/readme.txt", "<complexBehaviorDefinition/>");
        JsonNode r = run(List.of(dir.resolve("models").toString()), List.of(), Main.REFUSED);
        assertEquals("9.9.0", r.path("targetRelease").asText());
        assertTrue(text(r).contains("BLOCKING model") && text(r).contains("#c"), text(r));
        assertTrue(text(r).contains("WARNING model") && text(r).contains("#t1"), text(r));
        assertFalse(text(r).contains("readme.txt"), "only model files are scanned");
    }

    @Test
    void modelWithADoctypeIsRefusedNotResolved() throws Exception {
        write("models/x.bpmn", "<!DOCTYPE d [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><definitions>&e;</definitions>");
        JsonNode r = run(List.of(dir.resolve("models").toString()), List.of(), Main.REFUSED);
        assertTrue(text(r).contains("not a well-formed model"));
    }

    @Test
    void configKeysAreMatchedInRelaxedFormAndByPrefix() throws Exception {
        Path yaml = write("application.yaml", "sangam:\n  old_feature:\n    enabled: true\n  engine:\n    lock-owner: n1\n---\nserver:\n  port: 8080\n");
        Path props = write("extra.properties", "sangam.OldFeature=1\nsangam.other=2\n");
        Path env = write("prod.env", "# comment\nexport SANGAM_OLD_FEATURE=1\nSANGAM_FINE=2\n");
        JsonNode r = run(List.of(), List.of(yaml.toString(), props.toString(), env.toString()), Main.REFUSED);
        String t = text(r);
        assertTrue(t.contains("sangam.old_feature.enabled"), t);
        assertTrue(t.contains("renamed to sangam.engine.job-lock-owner"), t);
        assertTrue(t.contains("sangam.OldFeature"), t);
        assertTrue(t.contains("SANGAM_OLD_FEATURE"), t);
        assertFalse(t.contains("sangam.other") || t.contains("SANGAM_FINE") || t.contains("server.port"), t);
    }

    @Test
    void apiSunsetBlocksAndDeprecationWarns() throws Exception {
        JsonNode r = run(List.of(), List.of(), Main.REFUSED);
        String t = text(r);
        assertTrue(t.contains("BLOCKING api GET /cases/search") && t.contains("use /cases/query"), t);
        assertTrue(t.contains("WARNING api POST /tasks/{id}/claim"), t);
        assertFalse(t.contains("/later"), "not yet deprecated on the as-of date");
        assertEquals("2027-06-01", r.path("asOf").asText());
        assertTrue(r.path("notChecked").get(0).asText().startsWith("schema version"), "never claims an unchecked area");
    }

    @Test
    void cleanInputExitsZero() throws Exception {
        Path rules = write("empty-rules.json", "{\"release\":\"0.1.0\",\"models\":{\"constructs\":[]},\"config\":{}}");
        Path deps = write("empty.yaml", "policy: { minimumSupportDays: 365 }\ndeprecations: []\n");
        write("m/ok.dmn", "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\"/>");
        assertEquals(Main.OK, check.run(List.of(dir.resolve("m").toString()), List.of(), rules.toString(), deps.toString(), "2027-01-01"));
    }

    @Test
    void unreadableInputIsAUsageError() throws Exception {
        Path rules = write("r.json", "{}");
        assertEquals(Main.USAGE, check.run(List.of(), List.of(dir.resolve("missing.yaml").toString()), rules.toString(),
                write("d.yaml", "deprecations: []").toString(), null));
        assertEquals(Main.USAGE, check.run(List.of(), List.of(), rules.toString(), write("d2.yaml", "deprecations: []").toString(), "June"));
    }

    @Test
    void bundledRulesAndRegistryLoad() {
        // The packaged upgrade-rules.json and sangam-api-deprecations.yaml (pom resources) parse and report cleanly.
        assertEquals(Main.OK, check.run(List.of(), List.of(), null, null, "2026-11-12"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"targetRelease\" : \"0.1.0\""));
    }

    static String text(JsonNode report) {
        StringBuilder b = new StringBuilder();
        report.path("findings").forEach(f -> b.append(f.path("severity").asText()).append(' ').append(f.path("area").asText())
                .append(' ').append(f.path("where").asText()).append(" | ").append(f.path("message").asText()).append('\n'));
        return b.toString();
    }
}
