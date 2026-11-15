// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Tombstones;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.operaton.OperatonOperationIds;
import org.sangam.rest.adapter.operaton.OperatonWorkAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.web.WorkStepController;
import org.sangam.rest.web.WorkStepController.WorkStep;
import org.sangam.rest.web.WorkStepController.WorkStepRequest;

/**
 * M-025 (R37): the step journal behind {@code GET/PUT /work/{id}/steps/{stepName}} and {@code GET /work/{id}/steps} on
 * the Operaton worker port, H2 in PostgreSQL mode with the generated {@code WORK_STEP} DDL. The lease check and the
 * journal row share one transaction: a stale lease records nothing; steps are keyed by the work's stable operationId,
 * so a later attempt replays them; the INTENT / SUCCEEDED / FAILED transitions follow the contract.
 */
class WorkStepJournalH2Test {

    static RestEngineFixture fx;
    static OperatonWorkAdapter work;
    static WorkStepController steps;
    static final List<String> REQUIRED = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        fx = new RestEngineFixture("m025steps");   // the product schema: WORK_STEP is the generated baseline's
        for (String tenant : new String[]{"tA", "tB"}) {
            fx.engine.getRepositoryService().createDeployment().tenantId(tenant)
                    .addString("m025.bpmn", WorkerEngineFixture.BPMN).deploy();
        }
        RestProperties props = new RestProperties();
        work = new OperatonWorkAdapter(fx.engine.getExternalTaskService(), fx.engine.getRuntimeService(), fx.tx,
                new JdbcAuditAdapter(fx.tx, fx.engine.getHistoryService(), fx.engine.getRuntimeService(), props), props,
                new OperatonOperationIds(fx.engine.getRuntimeService(), fx.engine.getHistoryService(),
                        fx.engine.getExternalTaskService()));
        PermissionPort permissions = new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                REQUIRED.add(permission);
                return true;
            }

            @Override
            public java.util.Set<String> permissions(RequestContext ctx) {
                return java.util.Set.of("work.execute", "ops.read");
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return true;
            }
        };
        steps = new WorkStepController(work, permissions, fx.tx);
    }

    @AfterAll
    static void tearDown() {
        fx.close();
    }

    static WorkPort.ExternalWork lease(String tenant, String worker, String bk) {
        fx.engine.getRuntimeService().createProcessInstanceByKey(WorkerEngineFixture.KEY)
                .processDefinitionTenantId(tenant).businessKey(bk).execute();
        RequestContext ctx = WorkerEngineFixture.worker(tenant, worker);
        List<WorkPort.ExternalWork> l = work.fetchAndLock(ctx, new WorkPort.FetchAndLockRequest(worker,
                List.of(new WorkPort.TopicRequest(WorkerEngineFixture.TOPIC, 60_000L, null, null, null, null)), 1, 0L, null));
        assertEquals(1, l.size());
        return l.get(0);
    }

    static WorkStep put(String tenant, WorkPort.ExternalWork w, String worker, String name, String state, Object result) {
        return steps.record(WorkerEngineFixture.worker(tenant, worker), w.id(), name,
                new WorkStepRequest(worker, w.lockVersion(), w.attemptId(), state, result, null, null));
    }

    static void release(String tenant, WorkPort.ExternalWork w, String worker) {
        work.unlock(WorkerEngineFixture.worker(tenant, worker), w.id(),
                new WorkPort.UnlockRequest(worker, w.lockVersion(), w.attemptId()));
    }

    static WorkPort.ExternalWork refetch(String tenant, String worker) {
        List<WorkPort.ExternalWork> l = work.fetchAndLock(WorkerEngineFixture.worker(tenant, worker),
                new WorkPort.FetchAndLockRequest(worker, List.of(new WorkPort.TopicRequest(WorkerEngineFixture.TOPIC,
                        60_000L, null, null, null, null)), 1, 0L, null));
        assertEquals(1, l.size());
        return l.get(0);
    }

    @Test
    void intentThenSucceededIsReplayedToALaterAttemptOfTheSameOperation() {
        WorkPort.ExternalWork w = lease("tA", "wa", "M025-1");
        assertEquals(404, assertThrows(ApiProblem.class, () -> steps.get(WorkerEngineFixture.worker("tA", "wa"), w.id(),
                "charge", "wa", w.lockVersion(), w.attemptId())).status(), "never recorded: 404 (run it)");
        assertEquals("INTENT", put("tA", w, "wa", "charge", "INTENT", null).state());
        WorkStep done = put("tA", w, "wa", "charge", "SUCCEEDED", Map.of("paymentId", "PAY-1"));
        assertEquals("SUCCEEDED", done.state());
        assertEquals(w.operationId(), done.operationId());
        assertEquals(w.attemptId(), done.attemptId());

        release("tA", w, "wa");
        WorkPort.ExternalWork again = refetch("tA", "wa");
        assertNotEquals(w.attemptId(), again.attemptId());
        assertEquals(w.operationId(), again.operationId(), "R36: same operation on the next attempt");
        WorkStep replay = steps.get(WorkerEngineFixture.worker("tA", "wa"), again.id(), "charge", "wa",
                again.lockVersion(), again.attemptId());
        assertEquals(Map.of("paymentId", "PAY-1"), replay.result());
        assertEquals(w.attemptId(), replay.attemptId(), "the attempt that recorded it");
        assertTrue(REQUIRED.contains("work.execute"));
    }

    @Test
    void aStaleLeaseRecordsNothing() {
        WorkPort.ExternalWork w = lease("tA", "wa", "M025-2");
        release("tA", w, "wa");
        ApiProblem p = assertThrows(ApiProblem.class, () -> put("tA", w, "wa", "charge", "INTENT", null));
        assertEquals(409, p.status());
        assertEquals("STALE_LEASE", p.code());
        WorkPort.ExternalWork again = refetch("tA", "wa");
        assertEquals(404, assertThrows(ApiProblem.class, () -> steps.get(WorkerEngineFixture.worker("tA", "wa"),
                again.id(), "charge", "wa", again.lockVersion(), again.attemptId())).status());
    }

    @Test
    void transitionsFollowTheContract() {
        WorkPort.ExternalWork w = lease("tA", "wa", "M025-3");
        assertEquals("STEP_INTENT_REQUIRED",
                assertThrows(ApiProblem.class, () -> put("tA", w, "wa", "s", "SUCCEEDED", "v")).code());
        put("tA", w, "wa", "s", "INTENT", null);
        put("tA", w, "wa", "s", "INTENT", null);   // identical repeat: no-op
        put("tA", w, "wa", "s", "FAILED", null);
        assertEquals("STEP_INTENT_REQUIRED",
                assertThrows(ApiProblem.class, () -> put("tA", w, "wa", "s", "SUCCEEDED", "v")).code(),
                "FAILED needs a new INTENT before an outcome");
        put("tA", w, "wa", "s", "INTENT", null);   // a failure without effect may run again
        put("tA", w, "wa", "s", "SUCCEEDED", "v");
        assertEquals("SUCCEEDED", put("tA", w, "wa", "s", "SUCCEEDED", "v").state(), "identical success: no-op");
        assertEquals("STEP_ALREADY_RECORDED",
                assertThrows(ApiProblem.class, () -> put("tA", w, "wa", "s", "SUCCEEDED", "other")).code());
        assertEquals("STEP_ALREADY_RECORDED",
                assertThrows(ApiProblem.class, () -> put("tA", w, "wa", "s", "INTENT", null)).code(), "SUCCEEDED is final");
    }

    @Test
    void requestsAreValidated() {
        WorkPort.ExternalWork w = lease("tA", "wa", "M025-4");
        RequestContext ctx = WorkerEngineFixture.worker("tA", "wa");
        assertEquals(400, assertThrows(ApiProblem.class, () -> put("tA", w, "wa", "x".repeat(129), "INTENT", null)).status());
        assertEquals(400, assertThrows(ApiProblem.class, () -> put("tA", w, "wa", "tab\tname", "INTENT", null)).status());
        assertEquals(400, assertThrows(ApiProblem.class, () -> put("tA", w, "wa", "s", "DONE", null)).status());
        assertEquals(400, assertThrows(ApiProblem.class, () -> steps.record(ctx, w.id(), "s",
                new WorkStepRequest("wa", w.lockVersion(), null, "INTENT", null, null, null))).status());
        assertEquals(400, assertThrows(ApiProblem.class, () -> steps.record(ctx, w.id(), "s",
                new WorkStepRequest("wa", null, w.attemptId(), "INTENT", null, null, null))).status());
    }

    @Test
    void anotherTenantSeesNeitherTheWorkNorItsSteps() {
        WorkPort.ExternalWork w = lease("tA", "wa", "M025-5");
        put("tA", w, "wa", "s", "INTENT", null);
        RequestContext other = WorkerEngineFixture.worker("tB", "wa");
        assertEquals(404, assertThrows(ApiProblem.class, () -> steps.record(other, w.id(), "s",
                new WorkStepRequest("wa", w.lockVersion(), w.attemptId(), "SUCCEEDED", "v", null, null))).status());
        assertEquals(404, assertThrows(ApiProblem.class, () -> steps.list(other, w.id())).status());
    }

    @Test
    void theListIsInRecordingOrderAndNeedsOpsRead() {
        WorkPort.ExternalWork w = lease("tA", "wa", "M025-6");
        for (String s : new String[]{"reserve", "charge", "notify"}) {
            put("tA", w, "wa", s, "INTENT", null);
        }
        put("tA", w, "wa", "reserve", "SUCCEEDED", null);
        REQUIRED.clear();
        List<WorkStep> all = steps.list(WorkerEngineFixture.worker("tA", "ops"), w.id());
        assertEquals(List.of("reserve", "charge", "notify"), all.stream().map(WorkStep::name).toList());
        assertNull(all.get(1).result());
        assertTrue(REQUIRED.contains("ops.read"));
    }

    /**
     * SL-174: the problem for step calls on work that is not open, from the vectors every SDK test engine is checked
     * against too ({@code sdks/testing/work-step-problems.json}), so the engines and this adapter cannot drift apart.
     */
    // tenant tB: no other test fetches there, so a released item left AVAILABLE cannot be handed to another test
    @Test
    void stepCallsOnWorkThatIsNotOpenAnswerTheSharedVectors() throws Exception {
        JsonNode file = new ObjectMapper().readTree(Files.readString(
                Paths.get("..", "sdks", "testing", "work-step-problems.json")));
        String step = file.get("stepName").asText();
        int n = 0;
        for (JsonNode c : file.get("cases")) {
            WorkPort.ExternalWork w = lease("tB", "wa", "SL174-" + c.get("id").asText());
            RequestContext ctx = WorkerEngineFixture.worker("tB", "wa");
            String target = w.id();
            switch (c.get("scenario").asText()) {
                case "UNKNOWN" -> target = file.get("unknownWorkId").asText();
                case "COMPLETED" -> work.complete(ctx, w.id(),
                        new WorkPort.CompleteRequest("wa", w.lockVersion(), w.attemptId(), Map.of(), null, null));
                case "DELETED" -> deleteInstance(w);
                case "RELEASED" -> release("tB", w, "wa");
                case "UNKNOWN_OUTCOME" -> work.outcomeUnknown(ctx, w.id(),
                        new WorkPort.OutcomeUnknownRequest("wa", w.lockVersion(), w.attemptId(), "unsure", Map.of()));
                default -> throw new AssertionError("unknown scenario " + c.get("scenario"));
            }
            String id = target;
            expect(c, "GET", () -> steps.get(ctx, id, step, "wa", w.lockVersion(), w.attemptId()));
            expect(c, "PUT", () -> steps.record(ctx, id, step,
                    new WorkStepRequest("wa", w.lockVersion(), w.attemptId(), "INTENT", null, null, null)));
            n++;
        }
        assertTrue(n >= 5, "every vector ran");
    }

    /** The technical delete as the server does it: the leased task's tombstone, then the instance is gone. */
    static void deleteInstance(WorkPort.ExternalWork w) throws Exception {
        AuditChain chain = new AuditChain(Dialect.POSTGRESQL);
        Tombstones tombstones = new Tombstones(Dialect.POSTGRESQL, new ReconciliationQueue(Dialect.POSTGRESQL, chain), chain);
        try (Connection c = fx.raw.getConnection()) {
            tombstones.write(c, "tB", w.id(), UUID.fromString(w.operationId()), w.processInstanceId(), w.lockVersion(),
                    Duration.ofMinutes(5));
        }
        fx.engine.getRuntimeService().deleteProcessInstance(w.processInstanceId(), "SL-174 vector");
    }

    static void expect(JsonNode c, String route, Executable call) {
        String what = c.get("id").asText() + " " + route;
        ApiProblem p = assertThrows(ApiProblem.class, call, what);
        assertEquals(c.get("status").asInt(), p.status(), what);
        assertEquals(c.get("code").asText(), p.code(), what);
        for (Iterator<Map.Entry<String, JsonNode>> it = c.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            if (!List.of("id", "scenario", "status", "code").contains(f.getKey())) {
                assertEquals(f.getValue().asText(), String.valueOf(p.extensions().get(f.getKey())), what + " " + f.getKey());
            }
        }
    }
}
