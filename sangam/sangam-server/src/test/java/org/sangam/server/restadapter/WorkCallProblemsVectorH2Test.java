// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.operaton.OperatonOperationIds;
import org.sangam.rest.adapter.operaton.OperatonWorkAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.RestProperties;

/**
 * SL-174: the lease calls on work that is not open, from the vectors every SDK test engine is checked against too
 * ({@code sdks/testing/work-step-problems.json}, {@code workCalls}). Both server work adapters are checked: the
 * Operaton adapter and the Sangam engine adapter that sangam-server wires when sangam-engine is present.
 */
class WorkCallProblemsVectorH2Test {

    static final RequestContext WA = WorkerEngineFixture.worker("tA", "wa");

    static JsonNode vectors() throws Exception {
        return new ObjectMapper().readTree(Files.readString(Paths.get("..", "sdks", "testing", "work-step-problems.json")));
    }

    @Test
    void operatonAdapterAnswersTheSharedWorkCallVectors() throws Exception {
        try (RestEngineFixture fx = new RestEngineFixture("sl174op")) {
            fx.engine.getRepositoryService().createDeployment().tenantId("tA")
                    .addString("sl174.bpmn", WorkerEngineFixture.BPMN).deploy();
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
    void sangamEngineAdapterAnswersTheSharedWorkCallVectors() throws Exception {
        try (WorkerEngineFixture fx = new WorkerEngineFixture("sl174eng")) {
            check(fx.work, bk -> fx.start("tA", bk));
        }
    }

    /** Each vector on its own work item: lease, put it in the scenario, call with the original lease. */
    static void check(WorkPort work, Function<String, String> start) throws Exception {
        int n = 0;
        for (JsonNode c : vectors().get("workCalls")) {
            start.apply("SL174-" + c.get("id").asText());
            List<WorkPort.ExternalWork> got = work.fetchAndLock(WA, new WorkPort.FetchAndLockRequest("wa",
                    List.of(new WorkPort.TopicRequest(WorkerEngineFixture.TOPIC, 60_000L, null, null, null, null)), 1, 0L, null));
            assertEquals(1, got.size());
            WorkPort.ExternalWork w = got.get(0);
            String id = w.id();
            long lv = w.lockVersion();
            String at = w.attemptId();
            switch (c.get("scenario").asText()) {
                case "INCIDENT" -> work.fail(WA, id, new WorkPort.FailRequest("wa", lv, at, "no retries left", null, 0, 0L));
                case "UNKNOWN_OUTCOME" -> work.outcomeUnknown(WA, id, new WorkPort.OutcomeUnknownRequest("wa", lv, at,
                        "unsure", Map.of()));
                default -> throw new AssertionError("unknown scenario " + c.get("scenario"));
            }
            Executable call = switch (c.get("call").asText()) {
                case "complete" -> () -> work.complete(WA, id, new WorkPort.CompleteRequest("wa", lv, at, Map.of(), null, null));
                case "fail" -> () -> work.fail(WA, id, new WorkPort.FailRequest("wa", lv, at, "again", null, 1, 0L));
                case "bpmn-error" -> () -> work.bpmnError(WA, id, new WorkPort.BpmnErrorRequest("wa", lv, at, "E1", null, Map.of()));
                case "unlock" -> () -> work.unlock(WA, id, new WorkPort.UnlockRequest("wa", lv, at));
                case "extend-lock" -> () -> work.extendLock(WA, id, new WorkPort.ExtendLockRequest("wa", lv, at, 60_000L));
                case "outcome-unknown" -> () -> work.outcomeUnknown(WA, id, new WorkPort.OutcomeUnknownRequest("wa", lv, at,
                        "unsure", Map.of()));
                default -> throw new AssertionError("unknown call " + c.get("call"));
            };
            expect(c, call);
            n++;
        }
        assertTrue(n >= 12, "every work-call vector ran");
        stepFence(work, start);
    }

    /**
     * The step routes' fence ({@code WorkPort.inFencedWork}, behind PUT /work/{id}/step?stepName=) on both adapters, for
     * the step vectors whose scenario is a lease state (RELEASED, UNKNOWN_OUTCOME); WorkStepJournalH2Test checks every
     * step vector through the controller on the Operaton adapter.
     */
    static void stepFence(WorkPort work, Function<String, String> start) throws Exception {
        int n = 0;
        for (JsonNode c : vectors().get("cases")) {
            String scenario = c.get("scenario").asText();
            if (!"RELEASED".equals(scenario) && !"UNKNOWN_OUTCOME".equals(scenario)) {
                continue;
            }
            start.apply("SL174-step-" + c.get("id").asText());
            List<WorkPort.ExternalWork> got = work.fetchAndLock(WA, new WorkPort.FetchAndLockRequest("wa",
                    List.of(new WorkPort.TopicRequest(WorkerEngineFixture.TOPIC, 60_000L, null, null, null, null)), 1, 0L, null));
            assertEquals(1, got.size());
            WorkPort.ExternalWork w = got.get(0);
            if ("RELEASED".equals(scenario)) {
                work.unlock(WA, w.id(), new WorkPort.UnlockRequest("wa", w.lockVersion(), w.attemptId()));
            } else {
                work.outcomeUnknown(WA, w.id(), new WorkPort.OutcomeUnknownRequest("wa", w.lockVersion(), w.attemptId(),
                        "unsure", Map.of()));
            }
            expect(c, () -> work.inFencedWork(WA, w.id(), "wa", w.lockVersion(), w.attemptId(), (conn, lease) -> null));
            n++;
        }
        assertTrue(n >= 2, "every lease-state step vector ran");
    }

    static void expect(JsonNode c, Executable call) {
        String what = c.get("id").asText();
        ApiProblem p = assertThrows(ApiProblem.class, call, what);
        assertEquals(c.get("status").asInt(), p.status(), what);
        assertEquals(c.get("code").asText(), p.code(), what);
        for (Iterator<Map.Entry<String, JsonNode>> it = c.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            if (!List.of("id", "scenario", "call", "status", "code").contains(f.getKey())) {
                assertEquals(f.getValue().asText(), String.valueOf(p.extensions().get(f.getKey())), what + " " + f.getKey());
            }
        }
    }
}
