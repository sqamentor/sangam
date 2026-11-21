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

    private static final String CASE_FILE_CASE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" targetNamespace="t" id="d">
              <caseFileItemDefinition id="cfidApp" name="Application" definitionType="http://www.omg.org/spec/CMMN/DefinitionType/Unspecified"/>
              <case id="r10-case-file">
                <caseFileModel id="cfm">
                  <caseFileItem id="cfiApp" name="application" definitionRef="cfidApp"/>
                </caseFileModel>
                <casePlanModel id="plan" autoComplete="false">
                  <planItem id="PI_review" definitionRef="review">
                    <entryCriterion id="ec" sentryRef="s"/>
                  </planItem>
                  <sentry id="s">
                    <caseFileItemOnPart id="op" sourceRef="cfiApp"><standardEvent>create</standardEvent></caseFileItemOnPart>
                  </sentry>
                  <humanTask id="review" name="Review application"/>
                </casePlanModel>
              </case>
            </definitions>
            """;

    /** Owner ruling C1: case file item create fires the sentry; lifecycle state recorded by the engine. */
    @Test
    void caseFileItem() {
        scenario("CMMN-C1-CASE-FILE-ITEM", ctx -> {
            deployCmmn("r10-case-file", CASE_FILE_CASE);
            String cid = adapter.startCase(SCENARIO_TENANT, "r10-case-file", key("CFI"), vars());
            ctx.check("no review before the item exists", tasks(cid, null).isEmpty());
            engine.getCaseService().setVariable(cid, "application", "A-1");
            ctx.check("create fired the sentry", tasks(cid, null).size() == 1);
            var states = new org.sangam.ports.cmmn.CaseFileItemService(engine).states(cid);
            ctx.check("state AVAILABLE recorded (" + states + ")", states.size() == 1 && "AVAILABLE".equals(states.get(0).state()));
            boolean refused = false;
            try {
                deployCmmn("r10-case-file-bad", CASE_FILE_CASE.replace("r10-case-file", "r10-case-file-bad")
                        .replace("<standardEvent>create</standardEvent>", "<standardEvent>addChild</standardEvent>"));
            } catch (RuntimeException e) {
                refused = true;
            }
            ctx.check("addChild refused at deploy", refused);
        });
    }

    private static final String PLANNING_CASE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL" targetNamespace="t" id="d">
              <case id="r10-planning">
                <casePlanModel id="plan" autoComplete="false">
                  <planItem id="PI_main" definitionRef="main"/>
                  <planningTable id="pt">
                    <discretionaryItem id="DI_high" definitionRef="high" applicabilityRuleRefs="arBig" authorizedRoleRefs="rApprover"/>
                    <discretionaryItem id="DI_open" definitionRef="open"/>
                    <applicabilityRule id="arBig"><condition>${amount > 1000}</condition></applicabilityRule>
                  </planningTable>
                  <humanTask id="main" name="Main"/>
                  <humanTask id="high" name="High value review"/>
                  <humanTask id="open" name="Open item"/>
                </casePlanModel>
                <caseRoles id="roles"><role id="rApprover" name="approver"/></caseRoles>
              </case>
            </definitions>
            """;

    /** Owner rulings C2 / C3 = B: applicable discretionary items are listed (rule + role); planning itself is not offered. */
    @Test
    void planningTableApplicableItems() {
        scenario("CMMN-C2-PLANNING-APPLICABLE", ctx -> {
            deployCmmn("r10-planning", PLANNING_CASE);
            var svc = new org.sangam.ports.cmmn.PlanningService(engine);
            var approver = new org.sangam.ports.cmmn.PlanningService.Caller("u1", java.util.Set.of("approver"));
            String small = adapter.startCase(SCENARIO_TENANT, "r10-planning", key("PLS"), vars("amount", 500));
            String big = adapter.startCase(SCENARIO_TENANT, "r10-planning", key("PLB"), vars("amount", 5000));
            List<String> s = svc.applicable(small, approver).stream().map(i -> i.discretionaryItemId()).sorted().toList();
            List<String> b = svc.applicable(big, approver).stream().map(i -> i.discretionaryItemId()).sorted().toList();
            List<String> none = svc.applicable(big, new org.sangam.ports.cmmn.PlanningService.Caller("u2", java.util.Set.of()))
                    .stream().map(i -> i.discretionaryItemId()).toList();
            ctx.check("rule false: only the open item (" + s + ")", List.of("DI_open").equals(s));
            ctx.check("rule true and role held (" + b + ")", List.of("DI_high", "DI_open").equals(b));
            ctx.check("role missing hides the item (" + none + ")", List.of("DI_open").equals(none));
        });
    }
}