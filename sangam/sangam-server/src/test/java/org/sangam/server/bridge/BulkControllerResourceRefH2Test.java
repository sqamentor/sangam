// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.ops.OpsSchema;
import org.sangam.ops.bulk.BulkIngest;
import org.sangam.rest.modules.ops.BulkController;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.http.ResponseEntity;

/**
 * Owner decision 273 (option A): chunk upload, close and abort answer the resource reference {@code bulk-job:<id>}
 * exactly as create does, so an Idempotency-Key replay of any bulk command can re-check the stored job's sink
 * permission. The bodies are unchanged, and a caller without the sink permission still gets 403 before any change.
 */
class BulkControllerResourceRefH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TYPE = "test.item";
    static final String SINK_PERMISSION = "test.load";
    static final RequestContext CTX = new RequestContext("t1", "alice", Set.of(), null, null, null, null, null, "TEST");

    BulkIngest ingest;

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            OpsSchema.create(c, D);
        }
        ingest = new BulkIngest(D, Transactions.of(ds), new AuditChain(D), null, "node-1").register(sink());
    }

    static BulkIngest.Sink sink() {
        return new BulkIngest.Sink() {
            @Override
            public String objectType() {
                return TYPE;
            }

            @Override
            public String permission() {
                return SINK_PERMISSION;
            }

            @Override
            public String apply(Connection c, String tenant, String actor, String externalId, Map<String, Object> data,
                                String existingRef) {
                return "item:" + externalId;
            }
        };
    }

    static PermissionPort holding(String... granted) {
        Set<String> g = Set.of(granted);
        return new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return g.contains(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return g;
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return false;
            }
        };
    }

    static List<BulkIngest.RowInput> rows() {
        return List.of(new BulkIngest.RowInput("ext-1", Map.of("v", "a")));
    }

    String created(BulkController controller) {
        ResponseEntity<BulkIngest.Job> r = controller.create(CTX, new BulkController.CreateJobRequest(TYPE, 1));
        assertEquals(201, r.getStatusCode().value());
        assertEquals("bulk-job:" + r.getBody().id(), r.getHeaders().getFirst(Lists.RESOURCE_REF), "create, as before");
        return r.getBody().id();
    }

    @Test
    void chunkUploadAnswersTheJobResourceRefAndTheSameReceipt() {
        BulkController controller = new BulkController(ingest, holding(SINK_PERMISSION));
        String id = created(controller);
        ResponseEntity<BulkIngest.ChunkReceipt> r = controller.upload(CTX, id, 0, new BulkController.ChunkRequest(rows()));
        assertEquals(200, r.getStatusCode().value());
        assertEquals("bulk-job:" + id, r.getHeaders().getFirst(Lists.RESOURCE_REF));
        assertEquals(0, r.getBody().chunkSeq());
        assertEquals(1, r.getBody().rows());
        assertFalse(r.getBody().replayed());
        ResponseEntity<BulkIngest.ChunkReceipt> again = controller.upload(CTX, id, 0, new BulkController.ChunkRequest(rows()));
        assertEquals("bulk-job:" + id, again.getHeaders().getFirst(Lists.RESOURCE_REF), "a same-content replay too");
        assertEquals(true, again.getBody().replayed());
    }

    @Test
    void closeAnswersTheJobResourceRefAndTheJob() {
        BulkController controller = new BulkController(ingest, holding(SINK_PERMISSION));
        String id = created(controller);
        controller.upload(CTX, id, 0, new BulkController.ChunkRequest(rows()));
        ResponseEntity<BulkIngest.Job> r = controller.close(CTX, id);
        assertEquals(200, r.getStatusCode().value());
        assertEquals("bulk-job:" + id, r.getHeaders().getFirst(Lists.RESOURCE_REF));
        assertEquals(id, r.getBody().id());
        assertEquals(BulkIngest.JobState.PROCESSING, r.getBody().state());
    }

    @Test
    void abortAnswersTheJobResourceRefAndTheJob() {
        BulkController controller = new BulkController(ingest, holding(SINK_PERMISSION));
        String id = created(controller);
        ResponseEntity<BulkIngest.Job> r = controller.abort(CTX, id);
        assertEquals(200, r.getStatusCode().value());
        assertEquals("bulk-job:" + id, r.getHeaders().getFirst(Lists.RESOURCE_REF));
        assertEquals(id, r.getBody().id());
        assertEquals(BulkIngest.JobState.ABORTED, r.getBody().state());
    }

    @Test
    void theReferenceIsThePrefixAndTheIdAndTheSinkPermissionIsStillCheckedFirst() {
        assertEquals("bulk-job:j-1", BulkController.resourceRef("j-1"));
        assertEquals("bulk-job:", BulkController.RESOURCE_PREFIX);
        String id = created(new BulkController(ingest, holding(SINK_PERMISSION)));
        BulkController denied = new BulkController(ingest, holding());
        assertEquals(403, assertThrows(ApiProblem.class,
                () -> denied.upload(CTX, id, 0, new BulkController.ChunkRequest(rows()))).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> denied.close(CTX, id)).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> denied.abort(CTX, id)).status());
        assertEquals(BulkIngest.JobState.OPEN, ingest.job("t1", id).state(), "no refused call changed the job");
    }
}
