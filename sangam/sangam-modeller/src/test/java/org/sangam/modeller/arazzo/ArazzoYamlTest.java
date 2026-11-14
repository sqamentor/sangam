// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.arazzo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.sangam.modeller.arazzo.ArazzoImport.Severity;
import org.sangam.modeller.arazzo.ArazzoImport.Status;

/**
 * Decision 204: the YAML form of an Arazzo document is read with SnakeYAML's safe composition and gives the same draft
 * as its JSON form; YAML features that could change meaning or expand a document are refused, never guessed.
 */
class ArazzoYamlTest {

    static final String YAML = """
            arazzo: 1.0.1
            info:
              title: Pets
              version: "1"
            sourceDescriptions:
              - name: petStore
                url: https://example.test/pets.json
                type: openapi
            workflows:
              - workflowId: adopt
                summary: Adopt a pet
                steps:
                  - stepId: find
                    description: Find pets
                    operationId: findPets
                    parameters:
                      - name: status
                        in: query
                        value: $inputs.status
                    successCriteria:
                      - condition: $statusCode == 200
                    outputs:
                      petId: $response.body#/0/id
                  - stepId: adopt
                    operationId: adoptPet
                    parameters:
                      - name: petId
                        in: path
                        value: $steps.find.outputs.petId
                    requestBody:
                      contentType: application/json
                      payload:
                        note: ${not-juel}
            """;

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static JsonNode read(String yaml) throws ArazzoYaml.YamlProblem {
        return ArazzoYaml.read(utf8(yaml), ArazzoImporter.MAX_BYTES);
    }

    @Test
    void theYamlFormGivesTheSameDraftAsTheJsonForm() {
        ArazzoImporter.Options o = new ArazzoImporter.Options(Map.of(), Map.of(), "P30D");
        ArazzoImport fromYaml = ArazzoImporter.importDocument("pets.arazzo.yaml", utf8(YAML), o);
        ArazzoImport fromJson = ArazzoImporter.importDocument("pets.arazzo.json",
                ArazzoImporterTest.wf(ArazzoImporterTest.FIND + "," + ArazzoImporterTest.ADOPT), o);
        assertEquals(Status.REVIEW_REQUIRED, fromYaml.status(), fromYaml.diagnostics().toString());
        assertTrue(ArazzoImporterTest.codes(fromYaml, Severity.INFO).contains("ARAZZO-YAML"));
        assertArrayEquals(fromJson.bpmn(), fromYaml.bpmn(), "same BPMN draft from both forms");
        assertEquals(fromJson.processes(), fromYaml.processes());
    }

    @Test
    void scalarsFollowTheJsonDataModelExactly() throws Exception {
        JsonNode n = read("""
                a: 1.10
                b: 42
                c: 12345678901234567890
                d: true
                e: ~
                f: '0755'
                g: 2026-11-13
                h: !!str 7
                i: "yes"
                j: 1.0.1
                k: -0.5e3
                """);
        assertEquals(new BigDecimal("1.10"), n.get("a").decimalValue(), "decimals stay exact, scale kept");
        assertTrue(n.get("b").isInt());
        assertEquals("12345678901234567890", n.get("c").bigIntegerValue().toString());
        assertTrue(n.get("d").booleanValue());
        assertTrue(n.get("e").isNull());
        assertEquals("0755", n.get("f").textValue(), "quoted: text");
        assertEquals("2026-11-13", n.get("g").textValue(), "no timestamp conversion");
        assertEquals("7", n.get("h").textValue(), "explicit !!str honoured");
        assertEquals("yes", n.get("i").textValue());
        assertEquals("1.0.1", n.get("j").textValue());
        assertEquals(new BigDecimal("-0.5e3"), n.get("k").decimalValue());
    }

    @Test
    void featuresThatChangeMeaningOrExpandTheDocumentAreRefused() {
        for (String bad : List.of(
                "a: &x 1\nb: *x\n",                                  // anchor and alias
                "base: &b {x: 1}\nother:\n  <<: *b\n",              // merge key
                "a: !!python/object:os.system x\n",                 // global custom tag
                "a: !local x\n",                                    // local tag
                "a: yes\n",                                         // YAML 1.1 boolean
                "a: 0755\n",                                        // YAML 1.1 octal
                "a: 0x1F\n",                                        // hex
                "a: .inf\n",                                        // special float
                "a: 10:30\n",                                       // sexagesimal
                "a: 1_000\n",                                       // underscore number
                "a: 1\na: 2\n",                                     // duplicate key
                "? [x]\n: 1\n",                                     // non-scalar key
                "a: 1\n---\nb: 2\n",                                // two documents
                "")) {                                              // empty
            assertThrows(ArazzoYaml.YamlProblem.class, () -> read(bad), bad);
        }
        assertThrows(ArazzoYaml.YamlProblem.class, () -> ArazzoYaml.read(new byte[]{'a', ':', ' ', (byte) 0xC3},
                ArazzoImporter.MAX_BYTES), "not UTF-8");
        assertThrows(ArazzoYaml.YamlProblem.class, () -> ArazzoYaml.read(utf8("a: " + "x".repeat(200)), 100),
                "code point limit");
        assertThrows(ArazzoYaml.YamlProblem.class, () -> read("a:\n" + "  - ".repeat(1) + "[".repeat(250)
                + "]".repeat(250) + "\n"), "nesting limit");
    }

    @Test
    void aRefusedYamlDocumentIsADiagnosticWithItsLine() {
        ArazzoImport r = ArazzoImporter.importDocument("x.yaml", utf8("arazzo: 1.0.1\ninfo:\n  enabled: on\n"));
        assertEquals(Status.MANUAL, r.status());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("ARAZZO-YAML-UNREADABLE")
                && d.location().equals("line 3")), r.diagnostics().toString());
    }

    @Test
    void aSuppliedOpenApiDocumentMayBeYamlToo() {
        String openApi = """
                openapi: 3.1.0
                info: {title: Pets, version: "1"}
                paths:
                  /pets:
                    get:
                      operationId: findPets
                  /pets/{id}/adopt:
                    post:
                      operationId: adoptPet
                """;
        ArazzoImport r = ArazzoImporter.importDocument("pets.arazzo.yaml", utf8(YAML),
                new ArazzoImporter.Options(Map.of(), Map.of("petStore", utf8(openApi)), "P30D"));
        assertEquals(Status.REVIEW_REQUIRED, r.status(), r.diagnostics().toString());
        assertTrue(!ArazzoImporterTest.codes(r, Severity.WARNING).contains("ARAZZO-OPERATION-UNCHECKED"),
                "operation ids checked against the YAML OpenAPI document");
    }
}
