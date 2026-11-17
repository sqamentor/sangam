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

    private static String migCase(String aId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" targetNamespace="t" id="d">
                  <case id="r10-mig">
                    <casePlanModel id="plan" autoComplete="false">
                      <planItem id="%1$s" definitionRef="a"/>
                      <planItem id="PI_b" definitionRef="b">
                        <entryCriterion id="ec" sentryRef="s"/>
                      </planItem>
                      <sentry id="s">
                        <planItemOnPart id="op" sourceRef="%1$s"><standardEvent>complete</standardEvent></planItemOnPart>
                      </sentry>
                      <humanTask id="a" name="A"/>
                      <humanTask id="b" name="B"/>
                    </casePlanModel>
                  </case>
                </definitions>
                """.formatted(aId);
    }

    private String latestCaseDefinition(String key) {
        return engine.getRepositoryService().createCaseDefinitionQuery().caseDefinitionKey(key)
                .latestVersion().singleResult().getId();
    }

    /** P-5 migration A1: rebind with audit; a renamed plan item is rejected and nothing changes. */
    @Test
    void caseMigration() {
        scenario("CMMN-P5-MIGRATION", ctx -> {
            deployCmmn("r10-mig-v1", migCase("PI_a"));
            String v1 = latestCaseDefinition("r10-mig");
            String cid = adapter.startCase(SCENARIO_TENANT, "r10-mig", key("MIG"), vars());
            deployCmmn("r10-mig-v2", migCase("PI_a"));
            String v2 = latestCaseDefinition("r10-mig");
            var svc = new org.sangam.ports.cmmn.CaseMigrationService(engine);
            var rec = svc.migrate(cid, v2, "acc-officer", "R10 conformance");
            ctx.check("audit names actor and source", "acc-officer".equals(rec.migratedBy()) && v1.equals(rec.sourceDefinitionId()));
            ctx.check("every execution on v2", engine.getCaseService().createCaseExecutionQuery().caseInstanceId(cid).list()
                    .stream().allMatch(e -> v2.equals(e.getCaseDefinitionId())));
            List<TaskView> open = tasks(cid, null);
            ctx.require("task A open", open.size() == 1);
            adapter.completeTask(open.get(0).id(), vars());
            ctx.check("sentry fired on v2", tasks(cid, null).size() == 1);
            deployCmmn("r10-mig-v3", migCase("PI_renamed"));
            String v3 = latestCaseDefinition("r10-mig");
            boolean rejected = false;
            try {
                svc.migrate(cid, v3, "acc-officer", "rename");
            } catch (org.sangam.ports.cmmn.CaseMigrationService.CaseMigrationException e) {
                rejected = true;
            }
            ctx.check("rename rejected", rejected);
            ctx.check("one audit row", svc.history(cid).size() == 1);
        });
    }
}