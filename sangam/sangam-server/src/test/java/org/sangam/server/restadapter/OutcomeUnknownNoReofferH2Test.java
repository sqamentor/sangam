// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.integration.bridge.operaton.OperatonHeldWorkReleaser;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.operaton.OperatonOperationIds;
import org.sangam.rest.adapter.operaton.OperatonWorkAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.RestProperties;

/**
 * CX-5 / VI.3 (integrator ruling SL-174b, Option A): work whose outcome is unknown awaits reconciliation. The worker that
 * reported it can never release it, fail it with a retry, extend it or finish it through its old lease (409 STALE_LEASE
 * STALE_VERSION: outcome-unknown ended the lease), so it is never re-offered and an external effect is never repeated;
 * fetch-and-lock does not return it. Checked on both server work adapters. On the Sangam engine adapter the hold is also
 * followed past its end: the work is parked (incident) instead of handed out, and only the reconciliation resolve, which
 * needs no lease, moves it on.
 */
class OutcomeUnknownNoReofferH2Test {

    static final RequestContext WA = WorkerEngineFixture.worker("tA", "wa");
    static final RequestContext WB = WorkerEngineFixture.worker("tA", "wb");

    @Test
    void operatonAdapterNeverReoffersWorkAwaitingReconciliation() {
        try (RestEngineFixture fx = new RestEngineFixture("cx5op")) {
            fx.engine.getRepositoryService().createDeployment().tenantId("tA")
                    .addString("cx5.bpmn", WorkerEngineFixture.BPMN).deploy();
            RestProperties props = new RestProperties();
            OperatonWorkAdapter work = new OperatonWorkAdapter(fx.engine.getExternalTaskService(),
                    fx.engine.getRuntimeService(), fx.tx, new JdbcAuditAdapter(fx.tx, fx.engine.getHistoryService(),
                    fx.engine.getRuntimeService(), props), props, new OperatonOperationIds(fx.engine.getRuntimeService(),
                    fx.engine.getHistoryService(), fx.engine.getExternalTaskService()));
            check(work, bk -> fx.engine.getRuntimeService().createProcessInstanceByKey(WorkerEngineFixture.KEY)
                    .processDefinitionTenantId("tA").businessKey(bk).execute().getId());
        }
    }

    @Test
    void sangamEngineAdapterNeverReoffersWorkAwaitingReconciliation() {
        try (WorkerEngineFixture fx = new WorkerEngineFixture("cx5eng")) {
            WorkPort.ExternalWork w = check(fx.work, bk -> fx.start("tA", bk));
            try {
                // past the hold: the engine offers the task again, the ledger refuses it and it is parked, not handed out
                fx.engine.setClock(fx.engine.clock().now().plus(Duration.ofHours(25)));
                assertTrue(fetch(fx.work, WB, "wb").isEmpty(), "work awaiting reconciliation is not handed out");
                ExternalTask parked = fx.engine.processEngine().getExternalTaskService().createExternalTaskQuery()
                        .externalTaskId(w.id()).singleResult();
                assertNotNull(parked, "the external task is kept");
                assertEquals(Integer.valueOf(0), parked.getRetries(),"parked as an incident for reconciliation");

                // the reconciliation resolve (queue + ledger, then release of the held work) needs no lease
                ReconciliationQueue queue = new ReconciliationQueue(Dialect.POSTGRESQL, null);
                List<ReconciliationQueue.Item> open = fx.tx.inTx(() -> queue.listOpen(fx.tx.connection(), "tA"));
                ReconciliationQueue.Item item = open.stream().filter(i -> w.id().equals(i.taskId())
                        && i.kind() == ReconciliationQueue.Kind.UNKNOWN_OUTCOME).findFirst().orElseThrow();
                fx.tx.inTx(() -> queue.resolve(fx.tx.connection(), item.id(),
                        ReconciliationQueue.Action.RETRY_SAME_OPERATION, "officer", "{\"reason\":\"no effect\"}"));
                assertEquals(1, new OperatonHeldWorkReleaser(fx.engine.processEngine())
                        .release("tA", w.processInstanceId(), item.operationId(), null));

                List<WorkPort.ExternalWork> again = fetch(fx.work, WB, "wb");
                assertEquals(1, again.size(), "after the resolve the same operation is offered again");
                assertEquals(w.id(), again.get(0).id());
                assertEquals(w.operationId(), again.get(0).operationId());
                assertTrue(again.get(0).lockVersion() > w.lockVersion(), "under a new lease");
            } finally {
                fx.engine.setClock(null);
            }
        }
    }

    /**
     * Leases one work item, reports its outcome unknown and checks that no call with the old lease releases, retries,
     * extends or finishes it and that it is offered to no worker. Returns the leased item.
     */
    static WorkPort.ExternalWork check(WorkPort work, Function<String, String> start) {
        start.apply("CX5-unknown");
        List<WorkPort.ExternalWork> got = fetch(work, WA, "wa");
        assertEquals(1, got.size());
        WorkPort.ExternalWork w = got.get(0);
        String id = w.id();
        long lv = w.lockVersion();
        String at = w.attemptId();

        WorkPort.OutcomeUnknownResult r = work.outcomeUnknown(WA, id,
                new WorkPort.OutcomeUnknownRequest("wa", lv, at, "timeout after the request was sent", Map.of()));
        assertEquals("UNKNOWN_OUTCOME", r.state());
        assertNotNull(r.reconciliationItemId());

        stale(() -> work.unlock(WA, id, new WorkPort.UnlockRequest("wa", lv, at)));
        assertTrue(fetch(work, WA, "wa").isEmpty(), "unlock did not re-offer it to the reporting worker");
        assertTrue(fetch(work, WB, "wb").isEmpty(), "nor to another worker");

        stale(() -> work.fail(WA, id, new WorkPort.FailRequest("wa", lv, at, "try again", null, 3, 0L)));
        stale(() -> work.fail(WA, id, new WorkPort.FailRequest("wa", lv, at, "give up", null, 0, 0L)));
        stale(() -> work.extendLock(WA, id, new WorkPort.ExtendLockRequest("wa", lv, at, 60_000L)));
        stale(() -> work.complete(WA, id, new WorkPort.CompleteRequest("wa", lv, at, Map.of(), null, null)));
        stale(() -> work.bpmnError(WA, id, new WorkPort.BpmnErrorRequest("wa", lv, at, "E1", null, Map.of())));
        stale(() -> work.outcomeUnknown(WA, id, new WorkPort.OutcomeUnknownRequest("wa", lv, at, "again", Map.of())));
        stale(() -> work.inFencedWork(WA, id, "wa", lv, at, (c, lease) -> null));
        assertTrue(fetch(work, WB, "wb").isEmpty(), "no call with the old lease released it");
        return w;
    }

    static List<WorkPort.ExternalWork> fetch(WorkPort work, RequestContext ctx, String worker) {
        return work.fetchAndLock(ctx, new WorkPort.FetchAndLockRequest(worker,
                List.of(new WorkPort.TopicRequest(WorkerEngineFixture.TOPIC, 60_000L, null, null, null, null)), 1, 0L, null));
    }

    static void stale(Executable call) {
        ApiProblem p = assertThrows(ApiProblem.class, call);
        assertEquals(409, p.status(), p.getMessage());
        assertEquals("STALE_LEASE", p.code(), p.getMessage());
        assertEquals("STALE_VERSION", String.valueOf(p.extensions().get("reason")), p.getMessage());
    }
}
