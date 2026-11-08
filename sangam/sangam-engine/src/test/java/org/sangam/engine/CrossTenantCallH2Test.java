// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;

/**
 * SL-13 tenant isolation of definition references (SANGAM.md section 10, AC-09, J-P2-E09): a call activity, a business
 * rule task or a CMMN process task may resolve only its own tenant's or a shared definition, never another tenant's,
 * even when the model names that tenant explicitly. Operaton resolves an explicit tenant id without any check, so every
 * "refused" test here fails without {@link org.sangam.engine.deploy.TenantIsolationGuard}.
 */
class CrossTenantCallH2Test {

    private static final String BPMN_NS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
            """;

    private static final String CHILD = BPMN_NS + """
              <process id="child" isExecutable="true">
                <startEvent id="cs"/><sequenceFlow id="c1" sourceRef="cs" targetRef="work"/>
                <userTask id="work"/><sequenceFlow id="c2" sourceRef="work" targetRef="ce"/><endEvent id="ce"/>
              </process>
            </definitions>
            """;

    private static final String DECISION = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="d" name="d" namespace="t">
              <decision id="rule" name="rule"><variable name="r"/><literalExpression><text>"ok"</text></literalExpression></decision>
            </definitions>
            """;

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Parent in its own tenant calling {@code child} with the given tenant attribute (empty for none). */
    private static byte[] parent(String tenantAttr) {
        return utf8(BPMN_NS + """
                  <process id="parent" isExecutable="true">
                    <startEvent id="ps"/><sequenceFlow id="p1" sourceRef="ps" targetRef="call"/>
                    <callActivity id="call" calledElement="child" operaton:calledElementBinding="latest" %s/>
                    <sequenceFlow id="p2" sourceRef="call" targetRef="pe"/><endEvent id="pe"/>
                  </process>
                </definitions>
                """.formatted(tenantAttr));
    }

    private static byte[] ruleTask(String tenantAttr) {
        return utf8(BPMN_NS + """
                  <process id="decide" isExecutable="true">
                    <startEvent id="ds"/><sequenceFlow id="d1" sourceRef="ds" targetRef="brt"/>
                    <businessRuleTask id="brt" operaton:decisionRef="rule" operaton:decisionRefBinding="latest" %s
                                      operaton:mapDecisionResult="singleEntry" operaton:resultVariable="out"/>
                    <sequenceFlow id="d2" sourceRef="brt" targetRef="hold"/><userTask id="hold"/>
                    <sequenceFlow id="d3" sourceRef="hold" targetRef="de"/><endEvent id="de"/>
                  </process>
                </definitions>
                """.formatted(tenantAttr));
    }

    private static byte[] caseWithProcessTask(String tenant) {
        return utf8("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/cmmn" targetNamespace="t">
                  <case id="caseParent">
                    <casePlanModel id="plan" autoComplete="false">
                      <planItem id="pi_call" definitionRef="callChild"/>
                      <processTask id="callChild" processRef="child" operaton:processBinding="latest"
                                   operaton:processTenantId="%s"/>
                    </casePlanModel>
                  </case>
                </definitions>
                """.formatted(tenant));
    }

    private static void withEngine(Consumer<OperatonEngineAdapter> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:x" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            test.accept(a);
        }
    }

    private static long children(ProcessEngine e, String tenant) {
        return e.getRuntimeService().createProcessInstanceQuery().processDefinitionKey("child").tenantIdIn(tenant).count();
    }

    @Test
    void callActivityNamingAnotherTenantIsRefused() {
        withEngine(a -> {
            a.deploy("tenant-b", "child", Map.of("child.bpmn", utf8(CHILD)));
            a.deploy("tenant-a", "parent", Map.of("parent.bpmn", parent("operaton:calledElementTenantId=\"tenant-b\"")));
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> a.startProcess("tenant-a", "parent", "X-1", Map.of()));
            assertTrue(String.valueOf(rootMessage(e)).contains("SL-13 tenant isolation"), rootMessage(e));
            assertEquals(0, children(a.processEngine(), "tenant-b"), "no instance was started in the other tenant");
        });
    }

    @Test
    void callActivityNamingAnotherTenantByExpressionIsRefused() {
        withEngine(a -> {
            a.deploy("tenant-b", "child", Map.of("child.bpmn", utf8(CHILD)));
            a.deploy("tenant-a", "parent", Map.of("parent.bpmn", parent("operaton:calledElementTenantId=\"${callee}\"")));
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> a.startProcess("tenant-a", "parent", "X-2", Map.of("callee", "tenant-b")));
            assertTrue(String.valueOf(rootMessage(e)).contains("SL-13 tenant isolation"), rootMessage(e));
            assertEquals(0, children(a.processEngine(), "tenant-b"));
        });
    }

    @Test
    void callActivityInOwnTenantRuns() {
        withEngine(a -> {
            a.deploy("tenant-b", "child", Map.of("child.bpmn", utf8(CHILD)));
            a.deploy("tenant-a", "child", Map.of("child.bpmn", utf8(CHILD)));
            a.deploy("tenant-a", "parent", Map.of("parent.bpmn", parent("operaton:calledElementTenantId=\"tenant-a\"")));
            a.startProcess("tenant-a", "parent", "X-3", Map.of());
            assertEquals(1, children(a.processEngine(), "tenant-a"));
            assertEquals(0, children(a.processEngine(), "tenant-b"));
        });
    }

    @Test
    void callActivityToSharedDefinitionRuns() {
        withEngine(a -> {
            a.deploy(null, "child", Map.of("child.bpmn", utf8(CHILD)));
            a.deploy("tenant-a", "parent", Map.of("parent.bpmn", parent("operaton:calledElementTenantId=\"${callee}\"")));
            Map<String, Object> vars = new HashMap<>();
            vars.put("callee", null);
            a.startProcess("tenant-a", "parent", "X-4", vars);
            String shared = a.processEngine().getRepositoryService().createProcessDefinitionQuery()
                    .processDefinitionKey("child").withoutTenantId().singleResult().getId();
            assertEquals(1, a.processEngine().getRuntimeService().createProcessInstanceQuery()
                    .processDefinitionId(shared).count(), "the shared definition was called");
        });
    }

    @Test
    void businessRuleTaskNamingAnotherTenantIsRefused() {
        withEngine(a -> {
            a.deploy("tenant-b", "rule", Map.of("rule.dmn", utf8(DECISION)));
            a.deploy("tenant-a", "rule", Map.of("rule.dmn", utf8(DECISION)));
            a.deploy("tenant-a", "decide", Map.of("decide.bpmn", ruleTask("operaton:decisionRefTenantId=\"tenant-b\"")));
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> a.startProcess("tenant-a", "decide", "X-5", Map.of()));
            assertTrue(String.valueOf(rootMessage(e)).contains("SL-13 tenant isolation"), rootMessage(e));
        });
    }

    @Test
    void businessRuleTaskInOwnTenantRuns() {
        withEngine(a -> {
            a.deploy("tenant-a", "rule", Map.of("rule.dmn", utf8(DECISION)));
            a.deploy("tenant-a", "decide", Map.of("decide.bpmn", ruleTask("operaton:decisionRefTenantId=\"tenant-a\"")));
            String pi = a.startProcess("tenant-a", "decide", "X-6", Map.of());
            assertEquals("ok", a.processEngine().getRuntimeService().getVariable(pi, "out"));
        });
    }

    @Test
    void cmmnProcessTaskNamingAnotherTenantIsRefused() {
        withEngine(a -> {
            a.deploy("tenant-b", "child", Map.of("child.bpmn", utf8(CHILD)));
            a.deploy("tenant-a", "case", Map.of("case.cmmn", caseWithProcessTask("tenant-b")));
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> a.processEngine().getCaseService().withCaseDefinitionByKey("caseParent")
                            .caseDefinitionTenantId("tenant-a").create());
            assertTrue(String.valueOf(rootMessage(e)).contains("SL-13 tenant isolation"), rootMessage(e));
            assertEquals(0, children(a.processEngine(), "tenant-b"));
        });
    }

    @Test
    void cmmnProcessTaskInOwnTenantRuns() {
        withEngine(a -> {
            a.deploy("tenant-a", "child", Map.of("child.bpmn", utf8(CHILD)));
            a.deploy("tenant-a", "case", Map.of("case.cmmn", caseWithProcessTask("tenant-a")));
            a.processEngine().getCaseService().withCaseDefinitionByKey("caseParent").caseDefinitionTenantId("tenant-a").create();
            assertEquals(1, children(a.processEngine(), "tenant-a"));
        });
    }

    private static final String CMMN_NS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/cmmn" targetNamespace="t">
            """;

    /** A callable case with one manual human task, so a created instance stays active. */
    private static final String CHILD_CASE = CMMN_NS + """
              <case id="childCase">
                <casePlanModel id="cplan" autoComplete="false">
                  <planItem id="pi_h" definitionRef="h"/><humanTask id="h" isBlocking="true"/>
                </casePlanModel>
              </case>
            </definitions>
            """;

    private static byte[] caseWithCaseTask(String tenant) {
        return utf8(CMMN_NS + """
                  <case id="caseParent">
                    <casePlanModel id="plan" autoComplete="false">
                      <planItem id="pi_call" definitionRef="callCase"/>
                      <caseTask id="callCase" caseRef="childCase" operaton:caseBinding="latest" operaton:caseTenantId="%s"/>
                    </casePlanModel>
                  </case>
                </definitions>
                """.formatted(tenant));
    }

    private static byte[] caseWithDecisionTask(String tenant) {
        return utf8(CMMN_NS + """
                  <case id="caseParent">
                    <casePlanModel id="plan" autoComplete="false">
                      <planItem id="pi_dec" definitionRef="dec"/>
                      <decisionTask id="dec" decisionRef="rule" operaton:decisionBinding="latest"
                                    operaton:decisionTenantId="%s" operaton:mapDecisionResult="singleEntry"
                                    operaton:resultVariable="out"/>
                    </casePlanModel>
                  </case>
                </definitions>
                """.formatted(tenant));
    }

    private static long childCases(ProcessEngine e, String tenant) {
        return e.getCaseService().createCaseInstanceQuery().caseDefinitionKey("childCase").tenantIdIn(tenant).count();
    }

    private static void createParentCase(OperatonEngineAdapter a, String tenant) {
        a.processEngine().getCaseService().withCaseDefinitionByKey("caseParent").caseDefinitionTenantId(tenant).create();
    }

    @Test
    void cmmnCaseTaskNamingAnotherTenantIsRefused() {
        withEngine(a -> {
            a.deploy("tenant-b", "childCase", Map.of("child.cmmn", utf8(CHILD_CASE)));
            a.deploy("tenant-a", "case", Map.of("case.cmmn", caseWithCaseTask("tenant-b")));
            RuntimeException e = assertThrows(RuntimeException.class, () -> createParentCase(a, "tenant-a"));
            assertTrue(String.valueOf(rootMessage(e)).contains("SL-13 tenant isolation"), rootMessage(e));
            assertEquals(0, childCases(a.processEngine(), "tenant-b"), "no case was created in the other tenant");
        });
    }

    @Test
    void cmmnCaseTaskInOwnTenantRuns() {
        withEngine(a -> {
            a.deploy("tenant-b", "childCase", Map.of("child.cmmn", utf8(CHILD_CASE)));
            a.deploy("tenant-a", "childCase", Map.of("child.cmmn", utf8(CHILD_CASE)));
            a.deploy("tenant-a", "case", Map.of("case.cmmn", caseWithCaseTask("tenant-a")));
            createParentCase(a, "tenant-a");
            assertEquals(1, childCases(a.processEngine(), "tenant-a"));
            assertEquals(0, childCases(a.processEngine(), "tenant-b"));
        });
    }

    @Test
    void cmmnDecisionTaskNamingAnotherTenantIsRefused() {
        withEngine(a -> {
            a.deploy("tenant-b", "rule", Map.of("rule.dmn", utf8(DECISION)));
            a.deploy("tenant-a", "case", Map.of("case.cmmn", caseWithDecisionTask("tenant-b")));
            RuntimeException e = assertThrows(RuntimeException.class, () -> createParentCase(a, "tenant-a"));
            assertTrue(String.valueOf(rootMessage(e)).contains("SL-13 tenant isolation"), rootMessage(e));
            assertEquals(0, a.processEngine().getHistoryService().createHistoricDecisionInstanceQuery()
                    .tenantIdIn("tenant-b").count(), "the other tenant's decision was not evaluated");
        });
    }

    @Test
    void cmmnDecisionTaskInOwnTenantRuns() {
        withEngine(a -> {
            a.deploy("tenant-a", "rule", Map.of("rule.dmn", utf8(DECISION)));
            a.deploy("tenant-a", "case", Map.of("case.cmmn", caseWithDecisionTask("tenant-a")));
            createParentCase(a, "tenant-a");
            assertEquals(1, a.processEngine().getHistoryService().createHistoricDecisionInstanceQuery()
                    .tenantIdIn("tenant-a").count(), "the own tenant's decision was evaluated");
        });
    }

    /**
     * Static resolution (no execution, no engine-authenticated user, as on /engine-rest
     * {@code GET /process-definition/{id}/static-called-process-definitions}): Operaton's tenant check applies only to
     * an authenticated user, so without the guard's owner-tenant check tenant A's model disclosed tenant B's definition.
     */
    @Test
    void staticResolutionNamingAnotherTenantIsRefused() {
        withEngine(a -> {
            a.deploy("tenant-b", "child", Map.of("child.bpmn", utf8(CHILD)));
            a.deploy("tenant-a", "parent", Map.of("parent.bpmn", parent("operaton:calledElementTenantId=\"tenant-b\"")));
            var repo = a.processEngine().getRepositoryService();
            String parentId = repo.createProcessDefinitionQuery().processDefinitionKey("parent").tenantIdIn("tenant-a")
                    .singleResult().getId();
            // fail closed: the static analysis of a model naming another tenant is refused, nothing is disclosed
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> repo.getStaticCalledProcessDefinitions(parentId));
            assertTrue(String.valueOf(rootMessage(e)).contains("SL-13 tenant isolation"), rootMessage(e));
        });
    }

    @Test
    void staticResolutionInOwnTenantAndSharedStillWorks() {
        withEngine(a -> {
            a.deploy("tenant-a", "child", Map.of("child.bpmn", utf8(CHILD)));
            a.deploy("tenant-a", "parent", Map.of("parent.bpmn", parent("operaton:calledElementTenantId=\"tenant-a\"")));
            var repo = a.processEngine().getRepositoryService();
            String parentId = repo.createProcessDefinitionQuery().processDefinitionKey("parent").tenantIdIn("tenant-a")
                    .singleResult().getId();
            var called = repo.getStaticCalledProcessDefinitions(parentId);
            assertEquals(1, called.size());
            assertEquals("tenant-a", called.iterator().next().getTenantId());
        });
    }

    private static String rootMessage(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable x = t; x != null; x = x.getCause()) {
            b.append(x.getMessage()).append(" | ");
        }
        return b.toString();
    }
}
