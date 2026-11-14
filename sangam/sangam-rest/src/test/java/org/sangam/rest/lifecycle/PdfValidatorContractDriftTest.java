// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Owner decisions 114 and 119: {@code api/sangam-pdf-validator-api.yaml} (generated from {@code api/apps/pdf_validator.py})
 * is the wire between the server's {@code RemotePdfValidator} and the validator container's {@code PdfValidatorService}.
 * Both sides take their path, parameter and media types from the constants of {@code RemotePdfValidator}, so this test
 * reads those sources and fails when the contract and the code disagree: path, query parameter, request and report media
 * types, the conformance levels ({@code PdfRenderer.Conformance}), the report members and the health path. Owner decision 153:
 * validation needs a bearer token (401 before the PDF is read, 503 while no token is configured), the health probe needs none,
 * and no parameter carries a credential.
 */
class PdfValidatorContractDriftTest {

    private static final Path PDF = Path.of("..", "sangam-integration", "src", "main", "java", "org", "sangam",
            "integration", "document", "pdf");
    private static final Path CLIENT = PDF.resolve(Path.of("remote", "RemotePdfValidator.java"));
    private static final Path RENDERER = PDF.resolve("PdfRenderer.java");
    private static final Path SERVICE = Path.of("..", "sangam-pdf-validator", "src", "main", "java", "org", "sangam",
            "pdfvalidator", "PdfValidatorService.java");

    @Test
    @SuppressWarnings("unchecked")
    void contractMatchesTheClientConstantsAndTheService() throws IOException {
        String client = Files.readString(CLIENT, StandardCharsets.UTF_8);
        String renderer = Files.readString(RENDERER, StandardCharsets.UTF_8);
        String service = Files.readString(SERVICE, StandardCharsets.UTF_8);
        Map<String, Object> doc = read(Path.of("api", "sangam-pdf-validator-api.yaml"));
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");

        String validatePath = constant(client, "VALIDATE_PATH");
        Map<String, Object> validate = (Map<String, Object>) ((Map<String, Object>) paths.get(validatePath)).get("post");
        assertTrue(validate != null, "POST " + validatePath + " is in the contract");
        Map<String, Object> param = ((List<Map<String, Object>>) validate.get("parameters")).get(0);
        assertEquals(constant(client, "CONFORMANCE_PARAM"), param.get("name"));
        assertEquals(Boolean.TRUE, param.get("required"));
        List<String> levels = (List<String>) ((Map<String, Object>) ((Map<String, Object>) param.get("schema"))
                .get("items")).get("enum");
        assertEquals(conformance(renderer), levels, "conformance levels = PdfRenderer.Conformance");

        Map<String, Object> body = (Map<String, Object>) ((Map<String, Object>) validate.get("requestBody")).get("content");
        assertEquals(List.of(constant(client, "PDF_CONTENT_TYPE")), List.copyOf(body.keySet()));
        Map<String, Object> responses = (Map<String, Object>) validate.get("responses");
        assertEquals(List.of("200", "400", "401", "413", "500", "503"), List.copyOf(responses.keySet()));
        Map<String, Object> ok = (Map<String, Object>) ((Map<String, Object>) responses.get("200")).get("content");
        assertEquals(List.of(constant(client, "REPORT_CONTENT_TYPE")), List.copyOf(ok.keySet()));
        for (String code : List.of("400", "413", "500")) {
            assertTrue(service.contains("send(ex, " + code + ", Map.of(\"error\""), "the service answers " + code
                    + " with {\"error\": ...}");
        }

        Map<String, Object> report = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc
                .get("components")).get("schemas")).get("ValidationReport");
        List<String> members = List.copyOf(((Map<String, Object>) report.get("properties")).keySet());
        assertEquals(List.of("ok", "validator", "checked", "findings"), members);
        for (String key : List.of("OK", "VALIDATOR", "CHECKED", "FINDINGS")) {
            assertTrue(members.contains(constant(client, key)), "report member constant " + key);
        }

        assertEquals("/health", constant(service, "HEALTH_PATH"));
        assertTrue(paths.containsKey("/health"), "GET /health is in the contract");
        assertEquals(List.of(Map.of("bearerAuth", List.of())), doc.get("security"), "decision 153: bearer token by default");
        Map<String, Object> scheme = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc
                .get("components")).get("securitySchemes")).get("bearerAuth");
        assertEquals("http", scheme.get("type"));
        assertEquals("bearer", scheme.get("scheme"));
        Map<String, Object> health = (Map<String, Object>) ((Map<String, Object>) paths.get("/health")).get("get");
        assertEquals(List.of(), health.get("security"), "the probe needs no token");
        assertEquals(List.of("200", "503"), List.copyOf(((Map<String, Object>) health.get("responses")).keySet()));
        for (Map<String, Object> p : (List<Map<String, Object>>) validate.get("parameters")) {
            assertTrue(!"header".equals(p.get("in")) && !String.valueOf(p.get("name")).toLowerCase().contains("token"),
                    "no credential travels in a parameter: " + p.get("name"));
        }
    }

    private static String constant(String src, String name) {
        Matcher m = Pattern.compile("static final String " + name + "\\s*=\\s*\"([^\"]*)\"").matcher(src);
        assertTrue(m.find(), "constant " + name);
        return m.group(1);
    }

    private static List<String> conformance(String renderer) {
        Matcher m = Pattern.compile("enum Conformance \\{([^}]*)}").matcher(renderer);
        assertTrue(m.find(), "PdfRenderer.Conformance");
        List<String> out = new ArrayList<>();
        for (String v : m.group(1).split(",")) {
            if (!v.isBlank()) {
                out.add(v.strip());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        }
    }
}
