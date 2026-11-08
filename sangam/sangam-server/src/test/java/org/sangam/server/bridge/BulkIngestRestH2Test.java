// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.sangam.ops.bulk.BulkIngest;
import org.sangam.ops.bulk.BulkSinks;
import org.sangam.rest.modules.ops.BulkController;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * SL-44 through the real server on H2: the bulk REST surface (create, idempotent chunk upload, 413 limits, SL-43 batch-size quota (413), close, asynchronous completion by the worker, per-row status, error report), the tenant scope of every
 * job route and the object-type permission.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sangam.modules.ops.enabled=true",
        "sangam.security.dev-token.token=bulk-rest-test-token",
        "sangam.security.dev-token.tenant=bulk-t1",
        // the dev-token person is provisioned in its tenant, as in the acceptance runs (else 401 NOT_LINKED)
        "sangam.security.jit-provisioning-tenants=bulk-t1",
        "sangam.security.bootstrap.enabled=true",
        "sangam.security.bootstrap.admin-groups=sangam-admin",
        "sangam.ops.bulk.max-rows-per-chunk=5",
        // SL-43, the real JdbcTenantQuotas: a batch of more than 3 items is refused for every tenant
        "sangam.quotas.defaults.maxBatchSize=3",
        "sangam.ops.bulk.worker.poll-millis=200"})
@ActiveProfiles("h2")
class BulkIngestRestH2Test {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String TOKEN = "bulk-rest-test-token";

    @Value("${local.server.port}")
    int port;
    @Autowired
    BulkIngest ingest;
    @Autowired
    PermissionPort permissions;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    HttpResponse<String> call(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + TOKEN).header("Accept", "application/json").timeout(Duration.ofSeconds(30));
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }

    static Map<String, Object> chunk(String... names) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String n : names) {
            rows.add(Map.of("externalId", n, "data", Map.of("value", "v-" + n)));
        }
        return Map.of("rows", rows);
    }

    @Test
    void bulkJobRoundTripThroughTheRestApi() throws Exception {
        HttpResponse<String> created = call("POST", "/bulk/jobs", Map.of("objectType", BulkSinks.VARIABLE, "expectedChunks", 2));
        assertEquals(201, created.statusCode(), created.body());
        String id = json(created).get("id").asText();
        assertTrue(created.headers().firstValue("Location").orElse("").endsWith("/api/v1/bulk/jobs/" + id));
        assertEquals("OPEN", json(created).get("state").asText());
        assertEquals("bulk-t1", json(created).get("tenantId").asText(), "the tenant comes from the token");

        HttpResponse<String> c0 = call("PUT", "/bulk/jobs/" + id + "/chunks/0", chunk("bulkA", "bulkB", "bulkC"));
        assertEquals(200, c0.statusCode(), c0.body());
        assertFalse(json(c0).get("replayed").asBoolean());
        HttpResponse<String> again = call("PUT", "/bulk/jobs/" + id + "/chunks/0", chunk("bulkA", "bulkB", "bulkC"));
        assertTrue(json(again).get("replayed").asBoolean());

        HttpResponse<String> tooBig = call("PUT", "/bulk/jobs/" + id + "/chunks/1", chunk("a1", "a2", "a3", "a4", "a5", "a6"));
        assertEquals(413, tooBig.statusCode(), tooBig.body());
        assertTrue(tooBig.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        assertEquals("BULK_LIMIT", json(tooBig).get("code").asText());

        HttpResponse<String> quota = call("PUT", "/bulk/jobs/" + id + "/chunks/1", chunk("q1", "q2", "q3", "q4"));
        assertEquals(413, quota.statusCode(), quota.body());
        assertEquals("TENANT_QUOTA_EXCEEDED", json(quota).get("code").asText());
        assertEquals(3, json(call("GET", "/bulk/jobs/" + id, null)).get("totalRows").asInt(), "the refused chunk stored nothing");

        HttpResponse<String> c1 = call("PUT", "/bulk/jobs/" + id + "/chunks/1", chunk("bulkD", "bad name!"));
        assertEquals(200, c1.statusCode(), c1.body());
        HttpResponse<String> closed = call("POST", "/bulk/jobs/" + id + "/close", null);
        assertEquals(200, closed.statusCode(), closed.body());
        assertEquals("PROCESSING", json(closed).get("state").asText());

        JsonNode job = null;
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < until) {
            job = json(call("GET", "/bulk/jobs/" + id, null));
            if (!"PROCESSING".equals(job.get("state").asText())) {
                break;
            }
            Thread.sleep(200);
        }
        assertEquals("COMPLETED_WITH_ERRORS", job.get("state").asText(), job.toString());
        assertEquals(4, job.get("rows").get("DONE").asInt());
        assertEquals(1, job.get("rows").get("FAILED").asInt());

        HttpResponse<String> errors = call("GET", "/bulk/jobs/" + id + "/errors", null);
        assertEquals("1", errors.headers().firstValue("X-Total-Count").orElse(null));
        assertEquals("INVALID_VARIABLE", json(errors).get(0).get("errorCode").asText());
        assertEquals("bad name!", json(errors).get(0).get("externalId").asText());
        HttpResponse<String> done = call("GET", "/bulk/jobs/" + id + "/rows?state=DONE&size=2", null);
        assertEquals("4", done.headers().firstValue("X-Total-Count").orElse(null));
        assertEquals(2, json(done).size());
        assertEquals("variable:bulkA", json(done).get(0).get("resultRef").asText());

        assertEquals("v-bulkD", json(call("GET", "/ops-admin/variables", null)).get("bulkD").asText(), "rows were applied by the sink");
        assertTrue(json(call("GET", "/bulk/jobs?state=COMPLETED_WITH_ERRORS", null)).toString().contains(id));
        assertEquals(404, call("GET", "/bulk/jobs/no-such-job", null).statusCode());
        assertEquals(400, call("POST", "/bulk/jobs", Map.of("objectType", "no.such.type")).statusCode());
        assertEquals(400, call("GET", "/bulk/jobs/" + id + "/rows?state=NOPE", null).statusCode());
    }

    @Test
    void jobRoutesAreTenantScopedAndNeedTheObjectTypePermission() {
        BulkController controller = new BulkController(ingest, permissions);
        RequestContext a = new RequestContext("bulk-ta", "alice", Set.of("sangam-admin"), null, null, null, null, null, "TEST");
        RequestContext b = new RequestContext("bulk-tb", "bob", Set.of("sangam-admin"), null, null, null, null, null, "TEST");
        String id = controller.create(a, new BulkController.CreateJobRequest(BulkSinks.VARIABLE, null)).getBody().id();
        controller.upload(a, id, 0, new BulkController.ChunkRequest(List.of(new BulkIngest.RowInput("isoVar", Map.of("value", 1)))));

        for (Runnable r : List.<Runnable>of(() -> controller.job(b, id), () -> controller.close(b, id), () -> controller.abort(b, id),
                () -> controller.rows(b, id, null, null, null), () -> controller.errors(b, id, null, null),
                () -> controller.upload(b, id, 1, new BulkController.ChunkRequest(List.of(new BulkIngest.RowInput("x", Map.of("value", 2))))))) {
            ApiProblem p = assertThrows(ApiProblem.class, r::run);
            assertEquals(404, p.status(), p.getMessage());
        }
        assertEquals(List.of(), controller.list(b, null, null, null).getBody());
        assertEquals(1, controller.list(a, null, null, null).getBody().size());

        PermissionPort none = new PermissionPort() {
            public boolean has(RequestContext ctx, String permission) {
                return false;
            }

            public Set<String> permissions(RequestContext ctx) {
                return Set.of();
            }

            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return false;
            }
        };
        BulkController denied = new BulkController(ingest, none);
        assertEquals(403, assertThrows(ApiProblem.class, () -> denied.job(a, id)).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> denied.create(a, new BulkController.CreateJobRequest(BulkSinks.VARIABLE, null))).status());
        assertEquals(List.of(), denied.list(a, null, null, null).getBody(), "no visible object type, no jobs");
    }
}
