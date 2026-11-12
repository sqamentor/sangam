// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;

/**
 * SL-158 (TM-6, R26): with a {@code WorkerTopicGuard} bound, the worker API checks the caller's topic binding on fetch
 * (every requested topic, before anything is leased) and on every call on one item (the topic of the item's lease):
 * complete, fail, BPMN error, extend, unlock, outcome unknown and cancel acknowledgement. A correctly bound worker keeps
 * working. The guard here holds the bindings as the server's {@code WorkerBindingService} does (principal → topics)
 * and refuses with 403, as {@code SecurityProblemAdvice} maps {@code AccessDenied}.
 */
class WorkerTopicBindingH2Test {

    WorkerEngineFixture fx;
    final Map<String, Set<String>> bindings = Map.of("wa", Set.of(WorkerEngineFixture.TOPIC));
    final List<String> checked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fx = new WorkerEngineFixture("sl158");
        fx.work.setTopicGuard((ctx, topic) -> {
            checked.add(ctx.principalId() + "@" + topic);
            if (!bindings.getOrDefault(ctx.principalId(), Set.of()).contains(topic)) {
                throw ApiProblem.forbidden("ACTION_NOT_PERMITTED", "not bound to topic " + topic);
            }
        });
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    static WorkPort.FetchAndLockRequest fetch(String worker, String... topics) {
        List<WorkPort.TopicRequest> ts = new ArrayList<>();
        for (String t : topics) {
            ts.add(new WorkPort.TopicRequest(t, 60_000L, null, null, null, null));
        }
        return new WorkPort.FetchAndLockRequest(worker, ts, 10, 0L, null);
    }

    @Test
    void aBoundWorkerFetchesAndCompletesOnItsTopic() {
        String pi = fx.start("tA", "A-1");
        RequestContext wa = WorkerEngineFixture.worker("tA", "wa");
        List<WorkPort.ExternalWork> got = fx.work.fetchAndLock(wa, fetch("wa", WorkerEngineFixture.TOPIC));
        assertEquals(1, got.size());
        assertEquals(pi, got.get(0).processInstanceId());
        WorkPort.ExternalWork w = got.get(0);
        fx.work.extendLock(wa, w.id(), new WorkPort.ExtendLockRequest("wa", w.lockVersion(), w.attemptId(), 60_000L));
        fx.work.complete(wa, w.id(), new WorkPort.CompleteRequest("wa", w.lockVersion(), w.attemptId(), Map.of(), null, null));
        assertTrue(checked.contains("wa@" + WorkerEngineFixture.TOPIC));
        // SG-15 lease-lookup fold: the operation id stored with the lease, read inside the completion, settled the ledger
        String state = fx.tx.withConnection(c -> org.sangam.core.layer.Jdbc.one(c,
                "SELECT state FROM OPERATION_LEDGER WHERE operation_id=?", rs -> rs.getString(1), w.operationId())
                .orElseThrow());
        assertEquals("SUCCEEDED", state);
        long acknowledged = fx.tx.withConnection(c -> org.sangam.core.layer.Jdbc.one(c,
                "SELECT COUNT(*) FROM AUDIT_EVENT WHERE chain_id=? AND event_type='EXT_OP_ACKNOWLEDGED'",
                rs -> rs.getLong(1), "work:" + w.id()).orElse(0L));
        assertEquals(1, acknowledged);
    }

    @Test
    void aFetchNamingAnUnboundTopicIsRefusedBeforeAnythingIsLeased() {
        fx.start("tA", "A-2");
        RequestContext wa = WorkerEngineFixture.worker("tA", "wa");
        ApiProblem p = assertThrows(ApiProblem.class, () -> fx.work.fetchAndLock(wa,
                fetch("wa", WorkerEngineFixture.TOPIC, WorkerEngineFixture.OTHER_TOPIC)));
        assertEquals(403, p.status());
        long leased = fx.tx.withConnection(c -> org.sangam.core.layer.Jdbc.one(c, "SELECT COUNT(*) FROM SANGAM_LEASE",
                rs -> rs.getLong(1)).orElse(0L));
        assertEquals(0, leased, "the bound topic was not fetched either: the request is refused as a whole");
    }

    @Test
    void anUnboundPrincipalCannotSettleWorkLeasedOnAnotherWorkersTopic() {
        fx.start("tA", "A-3");
        WorkPort.ExternalWork w = fx.work.fetchAndLock(WorkerEngineFixture.worker("tA", "wa"),
                fetch("wa", WorkerEngineFixture.TOPIC)).get(0);
        RequestContext intruder = WorkerEngineFixture.worker("tA", "wx");   // same tenant, no binding
        assertEquals(403, assertThrows(ApiProblem.class, () -> fx.work.complete(intruder, w.id(),
                new WorkPort.CompleteRequest("wa", w.lockVersion(), w.attemptId(), Map.of(), null, null))).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> fx.work.fail(intruder, w.id(),
                new WorkPort.FailRequest("wa", w.lockVersion(), w.attemptId(), "x", null, 0, 0L))).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> fx.work.extendLock(intruder, w.id(),
                new WorkPort.ExtendLockRequest("wa", w.lockVersion(), w.attemptId(), 60_000L))).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> fx.work.unlock(intruder, w.id(),
                new WorkPort.UnlockRequest("wa", w.lockVersion(), w.attemptId()))).status());
        // the bound worker is unaffected
        fx.work.complete(WorkerEngineFixture.worker("tA", "wa"), w.id(),
                new WorkPort.CompleteRequest("wa", w.lockVersion(), w.attemptId(), Map.of(), null, null));
    }
}
