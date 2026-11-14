// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Owner decisions 167 and 153: {@code api/sangam-ocr-api.yaml} (generated from {@code api/apps/ocr.py}) is the wire
 * between the server's {@code RemoteOcrClient} and the OCR container's {@code OcrService}. Both sides take their path,
 * parameter, language pattern and result members from the constants of {@code RemoteOcrClient}, so this test reads those
 * sources and fails when the contract and the code disagree: path, query parameter and its pattern, the image media types
 * of {@code ImageCheck}, the error statuses the service sends with {@code {"error": ...}}, the result members and the
 * health path. Decision 153: recognition needs a bearer token, the health probe needs none, and no parameter carries a
 * credential.
 */
class OcrContractDriftTest {

    private static final Path CLIENT = Path.of("..", "sangam-integration", "src", "main", "java", "org", "sangam",
            "integration", "document", "ocr", "RemoteOcrClient.java");
    private static final Path SIDECAR = Path.of("..", "sangam-ocr-sidecar", "src", "main", "java", "org", "sangam",
            "ocrsidecar");
    private static final Path SERVICE = SIDECAR.resolve("OcrService.java");
    private static final Path IMAGE_CHECK = SIDECAR.resolve("ImageCheck.java");

    @Test
    @SuppressWarnings("unchecked")
    void contractMatchesTheClientConstantsAndTheSidecar() throws IOException {
        String client = Files.readString(CLIENT, StandardCharsets.UTF_8);
        String service = Files.readString(SERVICE, StandardCharsets.UTF_8);
        String imageCheck = Files.readString(IMAGE_CHECK, StandardCharsets.UTF_8);
        Map<String, Object> doc = read(Path.of("api", "sangam-ocr-api.yaml"));
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");

        String path = constant(client, "RECOGNISE_PATH");
        Map<String, Object> recognise = (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get("post");
        assertTrue(recognise != null, "POST " + path + " is in the contract");
        List<Map<String, Object>> params = (List<Map<String, Object>>) recognise.get("parameters");
        assertEquals(1, params.size(), "one query parameter");
        Map<String, Object> lang = params.get(0);
        assertEquals(constant(client, "LANGUAGES_PARAM"), lang.get("name"));
        assertEquals("query", lang.get("in"));
        assertEquals(Boolean.TRUE, lang.get("required"));
        String language = patternConstant(client, "LANGUAGE");
        assertEquals("^" + language + "(," + language + ")*$",
                ((Map<String, Object>) lang.get("schema")).get("pattern"), "lang = comma-separated LANGUAGE codes");

        Map<String, Object> body = (Map<String, Object>) ((Map<String, Object>) recognise.get("requestBody")).get("content");
        List<String> mediaTypes = List.copyOf(body.keySet());
        assertEquals(List.of("image/png", "image/jpeg", "image/tiff"), mediaTypes);
        for (String mediaType : mediaTypes) {
            assertTrue(imageCheck.contains("\"" + mediaType + "\""), "ImageCheck knows " + mediaType);
        }

        Map<String, Object> responses = (Map<String, Object>) recognise.get("responses");
        assertEquals(List.of("200", "400", "401", "413", "415", "422", "500", "503"), List.copyOf(responses.keySet()));
        for (String code : List.of("400", "413", "422", "500", "503")) {
            assertTrue(service.contains("send(ex, " + code + ", Map.of(\"error\""), "the service answers " + code
                    + " with {\"error\": ...}");
        }
        assertTrue(imageCheck.contains("new Refused(415,"), "ImageCheck answers 415");
        assertTrue(imageCheck.contains("new Refused(413,"), "ImageCheck answers 413 for the pixel limit");
        assertTrue(imageCheck.contains("new Refused(422,"), "ImageCheck answers 422");

        Map<String, Object> result = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc
                .get("components")).get("schemas")).get("OcrResult");
        List<String> members = List.copyOf(((Map<String, Object>) result.get("properties")).keySet());
        assertEquals(List.of(constant(client, "TEXT"), constant(client, "ENGINE"), constant(client, "LANGUAGES")),
                members);
        assertEquals(members, result.get("required"));

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
        for (Map<String, Object> p : params) {
            assertTrue(!"header".equals(p.get("in")) && !String.valueOf(p.get("name")).toLowerCase().contains("token"),
                    "no credential travels in a parameter: " + p.get("name"));
        }
    }

    private static String constant(String src, String name) {
        Matcher m = Pattern.compile("static final String " + name + "\\s*=\\s*\"([^\"]*)\"").matcher(src);
        assertTrue(m.find(), "constant " + name);
        return m.group(1);
    }

    private static String patternConstant(String src, String name) {
        Matcher m = Pattern.compile("static final Pattern " + name + "\\s*=\\s*Pattern\\.compile\\(\"([^\"]*)\"\\)")
                .matcher(src);
        assertTrue(m.find(), "pattern constant " + name);
        return m.group(1).replace("\\\\", "\\");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        }
    }
}
