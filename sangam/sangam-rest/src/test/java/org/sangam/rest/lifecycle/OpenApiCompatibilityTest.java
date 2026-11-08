// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * SL-38 / SL-46 gate: the generated contract ({@code api/sangam-api.yaml}) must stay backward compatible with the last
 * released contract ({@code api/baseline/sangam-api-0.1.0.yaml}, the file as of commit 15e4135). When a release is
 * cut, the release process copies the released spec into {@code api/baseline/} (API-LIFECYCLE-POLICY.md section 3).
 */
class OpenApiCompatibilityTest {

    private static final Path CURRENT = Path.of("api", "sangam-api.yaml");
    private static final Path BASELINE = Path.of("api", "baseline", "sangam-api-0.1.0.yaml");
    private static final Path REGISTRY = Path.of("api", ApiDeprecationRegistry.RESOURCE);

    @Test
    void generatedSpecHasNoBreakingChangeAgainstLastRelease() throws IOException {
        ApiDeprecationRegistry registry;
        try (InputStream in = Files.newInputStream(REGISTRY)) {
            registry = ApiDeprecationRegistry.load(in);
        }
        List<String> breaking = OpenApiCompatibility.breakingChanges(read(BASELINE), read(CURRENT), registry,
                LocalDate.now(java.time.ZoneOffset.UTC));
        assertEquals(List.of(), breaking, "breaking API changes against the last released spec ("
                + breaking.size() + "); deprecate first (sangam-api-deprecations.yaml) and remove only after sunset");
    }

    @Test
    void identicalSpecsAreCompatible() {
        assertEquals(List.of(), check(spec(PATH_OK), spec(PATH_OK), ApiDeprecationRegistry.empty()));
    }

    @Test
    void removedOperationIsBreakingUntilItsSunsetPassed() {
        String noOp = "openapi: 3.1.0\npaths: {}\ncomponents: {schemas: {Case: {type: object}}}\n";
        List<String> r = check(spec(PATH_OK), spec(noOp), ApiDeprecationRegistry.empty());
        assertTrue(r.size() == 2 && r.get(0).contains("GET /cases/{caseId}: operation removed"), r.toString());

        ApiDeprecationRegistry reg = registry("""
                deprecations:
                  - {method: GET, path: '/api/v1/cases/{id}', deprecated: 2025-01-01, sunset: 2026-01-01, link: https://x/cl}
                  - {method: POST, path: /cases, deprecated: 2025-01-01, sunset: 2027-06-01, link: https://x/cl}
                """);
        List<String> after = check(spec(PATH_OK), spec(noOp), reg);
        assertEquals(1, after.size(), after.toString());
        assertTrue(after.get(0).startsWith("POST /cases: operation removed"), "POST is not past sunset yet: " + after);
    }

    @Test
    void parameterChangesAreDetected() {
        String cur = PATH_OK.replace("{name: q, in: query, required: false, schema: {type: string}}",
                        "{name: q, in: query, required: true, schema: {type: string}}")
                .replace("parameters: [", "parameters: [{name: extra, in: query, required: true, schema: {type: string}}, ");
        List<String> r = check(spec(PATH_OK), spec(cur), ApiDeprecationRegistry.empty());
        assertTrue(r.stream().anyMatch(s -> s.contains("parameter query:q became required")), r.toString());
        assertTrue(r.stream().anyMatch(s -> s.contains("new required parameter query:extra")), r.toString());

        String removed = PATH_OK.replace(", {name: q, in: query, required: false, schema: {type: string}}", "");
        assertTrue(check(spec(PATH_OK), spec(removed), ApiDeprecationRegistry.empty()).stream()
                .anyMatch(s -> s.contains("parameter query:q removed")));

        String renamedPathVar = PATH_OK.replace("{caseId}", "{id}").replace("name: caseId", "name: id");
        assertEquals(List.of(), check(spec(PATH_OK), spec(renamedPathVar), ApiDeprecationRegistry.empty()),
                "a path variable rename is not visible to clients");
    }

    @Test
    void narrowedTypesAreDetected() {
        String cur = PATH_OK.replace("status: {type: string, enum: [OPEN, CLOSED]}", "status: {type: string, enum: [OPEN]}")
                .replace("amount: {type: string}", "amount: {type: number}")
                .replace("note: {type: string, maxLength: 200}", "note: {type: string, maxLength: 50}")
                .replace("required: [status]", "required: [status, note]");
        List<String> r = check(spec(PATH_OK), spec(cur), ApiDeprecationRegistry.empty());
        assertTrue(r.stream().anyMatch(s -> s.contains("enum values no longer accepted [CLOSED]")), r.toString());
        assertTrue(r.stream().anyMatch(s -> s.contains("amount: type changed")), r.toString());
        assertTrue(r.stream().anyMatch(s -> s.contains("maxLength tightened 200 -> 50")), r.toString());
        assertTrue(r.stream().anyMatch(s -> s.contains("new required request properties [note]")), r.toString());
    }

    @Test
    void responsePropertyRemovalIsBreakingButAdditionIsNot() {
        String removed = PATH_OK.replace("Case: {type: object, required: [id], properties: {id: {type: string}, title: {type: string}}}",
                "Case: {type: object, properties: {id: {type: string}}}");
        List<String> r = check(spec(PATH_OK), spec(removed), ApiDeprecationRegistry.empty());
        assertTrue(r.stream().anyMatch(s -> s.contains("property title removed")), r.toString());
        assertTrue(r.stream().anyMatch(s -> s.contains("no longer required [id]")), r.toString());

        String added = PATH_OK.replace("title: {type: string}}}", "title: {type: string}, extra: {type: string}}}");
        assertEquals(List.of(), check(spec(PATH_OK), spec(added), ApiDeprecationRegistry.empty()));
    }

    // ---------------------------------------------------------------- fixtures

    private static final String PATH_OK = """
            openapi: 3.1.0
            paths:
              /cases/{caseId}:
                get:
                  parameters: [{name: caseId, in: path, required: true, schema: {type: string}}, {name: q, in: query, required: false, schema: {type: string}}]
                  responses:
                    '200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/Case'}}}}
              /cases:
                post:
                  requestBody:
                    required: true
                    content: {application/json: {schema: {$ref: '#/components/schemas/NewCase'}}}
                  responses:
                    '201': {description: created}
            components:
              schemas:
                Case: {type: object, required: [id], properties: {id: {type: string}, title: {type: string}}}
                NewCase: {type: object, required: [status], properties: {status: {type: string, enum: [OPEN, CLOSED]}, amount: {type: string}, note: {type: string, maxLength: 200}}}
            """;

    private static List<String> check(Map<String, Object> b, Map<String, Object> c, ApiDeprecationRegistry reg) {
        return OpenApiCompatibility.breakingChanges(b, c, reg, LocalDate.of(2026, 11, 8));
    }

    private static ApiDeprecationRegistry registry(String yaml) {
        return ApiDeprecationRegistry.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> spec(String yaml) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    }

    private static Map<String, Object> read(Path p) throws IOException {
        LoaderOptions o = new LoaderOptions();
        o.setCodePointLimit(64 * 1024 * 1024);
        try (InputStream in = Files.newInputStream(p)) {
            return new Yaml(new SafeConstructor(o)).load(in);
        }
    }
}
