// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.operaton.OperatonOperationIds;
import org.sangam.rest.adapter.operaton.OperatonWorkAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.RestProperties;

/**
 * SL-158 when the engine module is off: the worker port is {@link OperatonWorkAdapter}, and R26 holds there too. With
 * a guard bound, a fetch naming an unbound topic is refused before anything is leased, a bound worker fetches and
 * completes, and an unbound principal of the same tenant cannot settle the work (403). Another tenant's work id keeps
 * its 404 (the guard does not reveal it).
 */
class OperatonWorkTopicGuardH2Test {

    static final String BPMN = WorkerEngineFixture.BPMN;
    static RestEngineFixture fx;
    static OperatonWorkAdapter work;
    static final Map<String, Set<String>> BINDINGS = Map.of("wa", Set.of(WorkerEngineFixture.TOPIC));
    static final List<String> CHECKED = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        fx = new RestEngineFixture("sl158op");
        for (String tenant : new String[]{"tA", "tB"}) {
            fx.engine.getRepositoryService().createDeployment().tenantId(tenant).addString("sl157.bpmn", BPMN).deploy();
        }
        RestProperties props = new RestProperties();
        work = new OperatonWorkAdapter(fx.engine.getExternalTaskService(), fx.engine.getRuntimeService(), fx.tx,
                new JdbcAuditAdapter(fx.tx, fx.engine.getHistoryService(), fx.engine.getRuntimeService(), props), props,
                new OperatonOperationIds(fx.engine.getRuntimeService(), fx.engine.getHistoryService(),
                        fx.engine.getExternalTaskService()));
        work.setTopicGuard((ctx, topic) -> {
            CHECKED.add(ctx.principalId() + "@" + topic);
            if (!BINDINGS.getOrDefault(ctx.principalId(), Set.of()).contains(topic)) {
                throw ApiProblem.forbidden("ACTION_NOT_PERMITTED", "not bound to topic " + topic);
            }
        });
    }

    @AfterAll
    static void tearDown() {
        fx.close();
    }

    static String start(String tenant, String bk) {
        return fx.engine.getRuntimeService().createProcessInstanceByKey(WorkerEngineFixture.KEY)
                .processDefinitionTenantId(tenant).businessKey(bk).execute().getId();
    }

    static WorkPort.FetchAndLockRequest fetch(String worker, String... topics) {
        List<WorkPort.TopicRequest> ts = new ArrayList<>();
        for (String t : topics) {
            ts.add(new WorkPort.TopicRequest(t, 60_000L, null, null, null, null));
        }
        return new WorkPort.FetchAndLockRequest(worker, ts, 10, 0L, null);
    }

    static long leasedOn(String pi) {
        return fx.engine.getExternalTaskService().createExternalTaskQuery().processInstanceId(pi).locked().count();
    }

    @Test
    void anUnboundTopicIsRefusedBeforeAnythingIsLeased() {
        String pi = start("tA", "OP-1");
        RequestContext wa = WorkerEngineFixture.worker("tA", "wa");
        assertEquals(403, assertThrows(ApiProblem.class, () -> work.fetchAndLock(wa,
                fetch("wa", WorkerEngineFixture.TOPIC, WorkerEngineFixture.OTHER_TOPIC))).status());
        assertEquals(0, leasedOn(pi));
    }

    @Test
    void aBoundWorkerWorksAndAnUnboundOneCannotSettleItsWork() {
        String pi = start("tA", "OP-2");
        RequestContext wa = WorkerEngineFixture.worker("tA", "wa");
        WorkPort.ExternalWork w = work.fetchAndLock(wa, fetch("wa", WorkerEngineFixture.TOPIC)).stream()
                .filter(x -> pi.equals(x.processInstanceId())).findFirst().orElseThrow();
        RequestContext intruder = WorkerEngineFixture.worker("tA", "wx");
        assertEquals(403, assertThrows(ApiProblem.class, () -> work.complete(intruder, w.id(),
                new WorkPort.CompleteRequest("wa", w.lockVersion(), w.attemptId(), Map.of(), null, null))).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> work.extendLock(intruder, w.id(),
                new WorkPort.ExtendLockRequest("wa", w.lockVersion(), w.attemptId(), 60_000L))).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> work.unlock(intruder, w.id(),
                new WorkPort.UnlockRequest("wa", w.lockVersion(), w.attemptId()))).status());
        work.complete(wa, w.id(), new WorkPort.CompleteRequest("wa", w.lockVersion(), w.attemptId(), Map.of(), null, null));
        assertTrue(CHECKED.contains("wa@" + WorkerEngineFixture.TOPIC));
    }

    @Test
    void anotherTenantsWorkIdStaysNotFound() {
        String pi = start("tB", "OP-3");
        WorkPort.ExternalWork b = work.fetchAndLock(WorkerEngineFixture.worker("tB", "wa"),
                fetch("wa", WorkerEngineFixture.TOPIC)).stream().filter(x -> pi.equals(x.processInstanceId()))
                .findFirst().orElseThrow();
        RequestContext unboundA = WorkerEngineFixture.worker("tA", "wx");
        assertEquals(404, assertThrows(ApiProblem.class, () -> work.complete(unboundA, b.id(),
                new WorkPort.CompleteRequest("wa", b.lockVersion(), b.attemptId(), Map.of(), null, null))).status());
    }
}
