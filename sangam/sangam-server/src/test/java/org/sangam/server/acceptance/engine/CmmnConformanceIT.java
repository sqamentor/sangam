// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.engine;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.TaskView;

/**
 * CMMN 1.1 behaviour of the sangam-flowable-ports extensions on the real server and its database (R10-cmmn). Runs in
 * the B-engine IT chunk on postgres, oracle, sqlserver and mysql.
 */
class CmmnConformanceIT extends EngineScenarioSupport {

    private static final String MAX_COUNT_CASE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/cmmn"
                         xmlns:sangam="http://sangam.org/schema/1.0/cmmn" targetNamespace="t" id="d">
              <case id="r10-max-count">
                <casePlanModel id="plan" autoComplete="false">
                  <planItem id="PI_review" definitionRef="review">
                    <itemControl id="ic">
                      <repetitionRule id="rr" operaton:repeatOnStandardEvent="complete" sangam:maxInstanceCount="3"/>
                    </itemControl>
                  </planItem>
                  <humanTask id="review" name="Review"/>
                </casePlanModel>
              </case>
            </definitions>
            """;

    private void deployCmmn(String name, String xml) {
        adapter.deploy(SCENARIO_TENANT, name + "-" + RUN, Map.of(name + ".cmmn", xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** P-4 / FC-57: {@code sangam:maxInstanceCount} stops repetition after the third instance. */
    @Test
    void repetitionMaxInstanceCount() {
        scenario("CMMN-P4-MAX-COUNT", ctx -> {
            deployCmmn("r10-max-count", MAX_COUNT_CASE);
            String cid = adapter.startCase(SCENARIO_TENANT, "r10-max-count", key("MAX"), vars());
            for (int i = 1; i <= 3; i++) {
                List<TaskView> open = tasks(cid, null);
                ctx.require("instance " + i + " open (found " + open.size() + ")", open.size() == 1);
                adapter.completeTask(open.get(0).id(), vars());
            }
            ctx.check("no fourth instance after max count 3 (found " + tasks(cid, null).size() + ")",
                    tasks(cid, null).isEmpty());
        });
    }

    private static final String EXT_CASE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                         xmlns:sangam="http://sangam.org/schema/1.0/cmmn" targetNamespace="t" id="d">
              <case id="r10-ext-task">
                <casePlanModel id="plan" autoComplete="false">
                  <planItem id="PI_kyc" definitionRef="kyc"/>
                  <task id="kyc" name="KYC check" sangam:topic="r10-kyc"/>
                </casePlanModel>
              </case>
            </definitions>
            """;

    /** P-5 / FC-11 native: case external task row, fetch-and-lock, owner-only complete, on the real database. */
    @Test
    void caseExternalTask() {
        scenario("CMMN-P5-EXTERNAL-TASK", ctx -> {
            deployCmmn("r10-ext-task", EXT_CASE);
            String cid = adapter.startCase(SCENARIO_TENANT, "r10-ext-task", key("EXT"), vars());
            var svc = new org.sangam.ports.cmmn.CaseExternalTaskService(engine);
            var got = svc.fetchAndLock(SCENARIO_TENANT, "r10-w1", 5,
                    List.of(new org.sangam.ports.cmmn.CaseExternalTaskService.Topic("r10-kyc", 60_000)));
            ctx.require("one task fetched (got " + got.size() + ")", got.size() == 1);
            ctx.check("task belongs to the case", cid.equals(got.get(0).caseInstanceId()));
            ctx.check("second worker gets nothing", svc.fetchAndLock(SCENARIO_TENANT, "r10-w2", 5,
                    List.of(new org.sangam.ports.cmmn.CaseExternalTaskService.Topic("r10-kyc", 60_000))).isEmpty());
            boolean rejected = false;
            try {
                svc.complete(got.get(0).id(), "r10-w2", Map.of());
            } catch (org.sangam.ports.cmmn.CaseExternalTaskService.CaseExternalTaskException e) {
                rejected = true;
            }
            ctx.check("non-owner complete rejected", rejected);
            svc.complete(got.get(0).id(), "r10-w1", Map.of("kycOk", true));
            ctx.check("variable set on the case", Boolean.TRUE.equals(engine.getCaseService().getVariable(cid, "kycOk")));
            ctx.check("row removed", svc.list(SCENARIO_TENANT, cid).isEmpty());
        });
    }
}