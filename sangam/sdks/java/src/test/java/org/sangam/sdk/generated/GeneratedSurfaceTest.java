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
 *
 * <p>The one exception is explicit in the contract: an operation marked {@code x-sangam-sdk: false} is called by an
 * outside system, never by an SDK user (the CloudEvents webhook handshake that Azure Event Grid sends as OPTIONS), and
 * gets no SDK method. The generator (sdks/codegen/contract_ir.py) leaves out exactly the operations so marked, lists
 * them in the manifest under {@code notForSdk}, and stops on an operation with any other method it cannot generate;
 * this test expects the same split, so an operation can never be missing from the SDKs without the marker.
 */
class GeneratedSurfaceTest {

    private static final Path CONTRACT = Paths.get("..", "..", "sangam-rest", "api", "sangam-api.yaml");
    private static final Path MANIFEST = Paths.get("..", "codegen", "surface-manifest.json");
    private static final Pattern OPERATION_ID = Pattern.compile("^ {6}operationId: (\\S+)$");
    /** A key of a path item or above: the operation block of the last operationId has ended. */
    private static final Pattern OPERATION_END = Pattern.compile("^ {0,4}\\S.*$");
    private static final Pattern NOT_FOR_SDK = Pattern.compile("^ {6}x-sangam-sdk: false$");
    private static final Pattern SDK_MARKER = Pattern.compile("^ {6}x-sangam-sdk:.*$");
    private static final ObjectMapper JSON = HttpTransport.defaultMapper();

    private static JsonNode manifest() throws IOException {
        assertTrue(Files.isRegularFile(MANIFEST), "manifest not found at " + MANIFEST.toAbsolutePath());
        return JSON.readTree(Files.readString(MANIFEST, StandardCharsets.UTF_8));
    }

    /** The operationIds of the contract: [0] those an SDK method is generated for, [1] those marked x-sangam-sdk: false. */
    private static List<TreeSet<String>> contractOperations(List<String> lines) {
        TreeSet<String> forSdk = new TreeSet<>();
        TreeSet<String> notForSdk = new TreeSet<>();
        String current = null;
        for (String line : lines) {
            Matcher m = OPERATION_ID.matcher(line);
            if (m.find()) {
                current = m.group(1);
                forSdk.add(current);
            } else if (OPERATION_END.matcher(line).matches()) {
                current = null;
            } else if (SDK_MARKER.matcher(line).matches()) {
                assertNotNull(current, "x-sangam-sdk outside an operation (after its operationId): " + line);
                assertTrue(NOT_FOR_SDK.matcher(line).matches(), "only 'x-sangam-sdk: false' is written: " + line);
                forSdk.remove(current);
                notForSdk.add(current);
            }
        }
        return List.of(forSdk, notForSdk);
    }

    @Test
    void manifestCoversEveryContractOperation() throws IOException {
        List<TreeSet<String>> contract = contractOperations(Files.readAllLines(CONTRACT, StandardCharsets.UTF_8));
        TreeSet<String> generated = new TreeSet<>();
        manifest().get("surface").forEach(r -> generated.add(r.get("operationId").asText()));
        TreeSet<String> left = new TreeSet<>();
        manifest().get("notForSdk").forEach(r -> left.add(r.get("operationId").asText()));
        assertTrue(contract.get(0).size() > 1000,
                "contract layout changed? only " + contract.get(0).size() + " operationIds read");
        assertEquals(contract.get(0), generated, "the generated surface is stale: run sdks/codegen/generate_sdk_surface.py");
        assertEquals(contract.get(1), left, "the operations without an SDK method are exactly those the contract marks "
                + "x-sangam-sdk: false");
        assertEquals(new TreeSet<>(List.of("eventGridHookHandshake")), left, "an operation leaves the SDKs only by a "
                + "reviewed marker: add it here with the reason (who calls it instead of an SDK user)");
    }

    @Test
    void theMarkerIsReadPerOperation() {
        List<TreeSet<String>> ops = contractOperations(List.of(
                "paths:",
                "  /hooks/x:",
                "    options:",
                "      operationId: handshake",
                "      responses:",
                "        '200':",
                "          description: Success",
                "      x-sangam-sdk: false",
                "    post:",
                "      operationId: deliver",
                "  /cases:",
                "    get:",
                "      operationId: listCases"));
        assertEquals(new TreeSet<>(List.of("deliver", "listCases")), ops.get(0));
        assertEquals(new TreeSet<>(List.of("handshake")), ops.get(1));
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
