// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.generated.model.ExternalWork;
import org.sangam.sdk.generated.model.Problem;
import org.sangam.sdk.http.HttpTransport;

/**
 * M-020 coverage guard: every operation of sangam-api.yaml has a generated method (the manifest written by
 * sdks/codegen/generate_sdk_surface.py names it), every schema has a record, and SangamClient.api() exposes the
 * surface. CI also runs the generator with --check, so a stale tree fails before this test.
 */
class GeneratedSurfaceTest {

    private static final Path CONTRACT = Paths.get("..", "..", "sangam-rest", "api", "sangam-api.yaml");
    private static final Path MANIFEST = Paths.get("..", "codegen", "surface-manifest.json");
    private static final Pattern OPERATION_ID = Pattern.compile("^ {6}operationId: (\\S+)$");
    private static final ObjectMapper JSON = HttpTransport.defaultMapper();

    private static JsonNode manifest() throws IOException {
        assertTrue(Files.isRegularFile(MANIFEST), "manifest not found at " + MANIFEST.toAbsolutePath());
        return JSON.readTree(Files.readString(MANIFEST, StandardCharsets.UTF_8));
    }

    @Test
    void manifestCoversEveryContractOperation() throws IOException {
        TreeSet<String> contract = new TreeSet<>();
        for (String line : Files.readAllLines(CONTRACT, StandardCharsets.UTF_8)) {
            Matcher m = OPERATION_ID.matcher(line);
            if (m.find()) contract.add(m.group(1));
        }
        TreeSet<String> generated = new TreeSet<>();
        manifest().get("surface").forEach(r -> generated.add(r.get("operationId").asText()));
        assertTrue(contract.size() > 1000, "contract layout changed? only " + contract.size() + " operationIds read");
        assertEquals(contract, generated, "the generated surface is stale: run sdks/codegen/generate_sdk_surface.py");
    }

    @Test
    void everyOperationHasAGeneratedMethod() throws Exception {
        List<String> missing = new ArrayList<>();
        for (JsonNode r : manifest().get("surface")) {
            String[] target = r.get("java").asText().split("#");
            Class<?> api = Class.forName("org.sangam.sdk.generated.api." + target[0]);
            boolean found = false;
            for (Method m : api.getMethods()) {
                if (m.getName().equals(target[1]) && Modifier.isPublic(m.getModifiers())) found = true;
            }
            if (!found) missing.add(r.get("operationId").asText() + " -> " + r.get("java").asText());
        }
        assertTrue(missing.isEmpty(), "operations without a generated Java method: " + missing);
    }

    @Test
    void everySchemaHasARecordAndEveryTagAnAccessor() throws Exception {
        for (JsonNode n : manifest().get("models")) {
            Class<?> c = Class.forName("org.sangam.sdk.generated.model." + n.asText());
            assertTrue(c.isRecord(), n.asText() + " is not a record");
        }
        SangamClient client = SangamClient.builder().baseUrl("http://127.0.0.1:1").build();
        SangamApis apis = client.api();
        assertNotNull(apis);
        for (JsonNode r : manifest().get("surface")) {
            String cls = r.get("java").asText().split("#")[0];
            boolean accessor = false;
            for (Method m : SangamApis.class.getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().equals(cls)) {
                    assertNotNull(m.invoke(apis), cls);
                    accessor = true;
                }
            }
            assertTrue(accessor, "SangamApis has no accessor for " + cls);
        }
    }

    @Test
    void recordsReadTheWireNamesAndKeepUndeclaredMembers() throws IOException {
        ExternalWork w = JSON.readValue("{\"id\":\"wk-1\",\"lockVersion\":3,\"attemptId\":\"a-1\","
                + "\"lockExpiresAt\":\"2026-03-31T18:30:00Z\",\"operation\":{\"state\":\"INTENT\",\"mayDispatch\":true},"
                + "\"unknownMember\":1}", ExternalWork.class);
        assertEquals("wk-1", w.id());
        assertEquals(3L, w.lockVersion());
        assertEquals("INTENT", w.operation().state());
        assertEquals("2026-03-31T18:30:00Z", w.lockExpiresAt().toString());

        // Problem declares additionalProperties: true; members it does not name (workId, reason) survive a round trip
        Problem p = JSON.readValue("{\"type\":\"https://sangam.dev/problems/stale-lease\",\"title\":\"Stale lease\","
                + "\"status\":409,\"code\":\"STALE_LEASE\",\"workId\":\"wk-1\",\"reason\":\"STALE_VERSION\"}", Problem.class);
        assertEquals("STALE_LEASE", p.code());
        assertEquals(Map.of("workId", "wk-1", "reason", "STALE_VERSION"), p.additionalProperties());
        JsonNode back = JSON.readTree(JSON.writeValueAsString(p));
        assertEquals("wk-1", back.get("workId").asText());
        assertEquals(409, back.get("status").asInt());
    }
}
