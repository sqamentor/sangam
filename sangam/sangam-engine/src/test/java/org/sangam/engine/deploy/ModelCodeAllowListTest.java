// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ResourceEntity;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * SL-160 / TM-10 part A: only reviewed Java classes and delegate beans are deployable. Unknown classes (task and listener,
 * any extension namespace) and non-reviewed or computed delegate expressions are refused with a stable code; Sangam's own
 * and tenant-approved entries deploy; a stored deployment re-parsed into the cache is never refused.
 */
class ModelCodeAllowListTest {

    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                         xmlns:camunda="http://camunda.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="sl160" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
            """;
    private static final String TAIL = """
                <sequenceFlow id="f2" sourceRef="work" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    private static byte[] model(String work) {
        return (HEAD + work + TAIL).getBytes(StandardCharsets.UTF_8);
    }

    private static List<ModelCodeAllowList.Finding> check(String work) {
        return ModelCodeAllowList.check("m.bpmn", model(work), ModelCodeAllowList.SANGAM_CLASSES,
                ModelCodeAllowList.SANGAM_BEANS);
    }

    private static void assertRefused(String code, String work) {
        List<ModelCodeAllowList.Finding> f = check(work);
        assertEquals(1, f.size(), work + " -> " + f);
        assertEquals(code, f.get(0).code());
    }

    @Test
    void sangamClassesAndBeansAreAllowed() {
        assertEquals(List.of(), check("<serviceTask id=\"work\" operaton:class=\"org.sangam.packs.core.SetVariablesDelegate\"/>"));
        assertEquals(List.of(), check("<serviceTask id=\"work\" operaton:delegateExpression=\"${sangamOnboardingStep}\"/>"));
        assertEquals(List.of(), check("<userTask id=\"work\"><extensionElements><operaton:taskListener event=\"create\" "
                + "class=\"org.sangam.packs.core.ActorRecorderListener\"/></extensionElements></userTask>"));
        assertEquals(List.of(), check("<serviceTask id=\"work\" operaton:type=\"external\" operaton:topic=\"t\"/>"),
                "external workers are the route for other code");
    }

    @Test
    void anUnknownClassIsRefusedWhereverItIsNamed() {
        assertRefused(ModelCodeAllowList.CLASS_NOT_ALLOWED, "<serviceTask id=\"work\" operaton:class=\"com.evil.Payload\"/>");
        assertRefused(ModelCodeAllowList.CLASS_NOT_ALLOWED, "<serviceTask id=\"work\" camunda:class=\"com.evil.Payload\"/>");
        assertRefused(ModelCodeAllowList.CLASS_NOT_ALLOWED, "<task id=\"work\"><extensionElements><operaton:executionListener "
                + "event=\"start\" class=\"java.lang.Runtime\"/></extensionElements></task>");
        assertRefused(ModelCodeAllowList.CLASS_NOT_ALLOWED, "<userTask id=\"work\"><extensionElements><operaton:taskListener "
                + "event=\"create\" class=\"com.evil.Listener\"/></extensionElements></userTask>");
        assertTrue(check("<serviceTask id=\"work\" operaton:class=\"com.evil.Payload\"/>").get(0).detail()
                .contains("external worker"), "the error names the route for external code");
    }

    @Test
    void delegateExpressionsMustBeAReviewedBean() {
        assertRefused(ModelCodeAllowList.DELEGATE_NOT_ALLOWED,
                "<serviceTask id=\"work\" operaton:delegateExpression=\"${someSpringBean}\"/>");
        assertRefused(ModelCodeAllowList.DELEGATE_NOT_ALLOWED,
                "<serviceTask id=\"work\" operaton:delegateExpression=\"${execution.getProcessEngineServices()}\"/>");
        assertRefused(ModelCodeAllowList.DELEGATE_NOT_ALLOWED,
                "<serviceTask id=\"work\" operaton:delegateExpression=\"${execution.getVariable('delegate')}\"/>");
        assertRefused(ModelCodeAllowList.DELEGATE_NOT_ALLOWED, "<task id=\"work\"><extensionElements><operaton:executionListener "
                + "event=\"start\" delegateExpression=\"${anyBean}\"/></extensionElements></task>");
    }

    @Test
    void tenantApprovedEntriesAreAllowedForThatTenantOnly() {
        Map<String, Set<String>> classes = new LinkedHashMap<>();
        classes.put("t1", Set.of("com.dept.ReviewedDelegate"));
        classes.put("*", Set.of("com.dept.SharedDelegate"));
        assertTrue(ModelCodeAllowList.allowed(ModelCodeAllowList.SANGAM_CLASSES, classes, "t1").contains("com.dept.ReviewedDelegate"));
        assertTrue(ModelCodeAllowList.allowed(ModelCodeAllowList.SANGAM_CLASSES, classes, "t2").contains("com.dept.SharedDelegate"));
        assertTrue(!ModelCodeAllowList.allowed(ModelCodeAllowList.SANGAM_CLASSES, classes, "t2").contains("com.dept.ReviewedDelegate"));
        assertTrue(!ModelCodeAllowList.allowed(ModelCodeAllowList.SANGAM_CLASSES, classes, null).contains("com.dept.ReviewedDelegate"));
    }

    @Test
    void anUnreadableOrDoctypeModelIsRefused() {
        List<ModelCodeAllowList.Finding> f = ModelCodeAllowList.check("x.bpmn",
                ("<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><definitions/>")
                        .getBytes(StandardCharsets.UTF_8), Set.of(), Set.of());
        assertEquals(ModelCodeAllowList.UNREADABLE, f.get(0).code());
    }

    @Test
    void onlyNewDeploymentsAreChecked() {
        ModelCodeAllowList.DeployRule rule = new ModelCodeAllowList.DeployRule(Map.of(), Map.of());
        DeploymentEntity d = new DeploymentEntity();
        d.setName("d");
        ResourceEntity r = new ResourceEntity();
        r.setName("m.bpmn");
        r.setBytes(model("<serviceTask id=\"work\" operaton:class=\"com.evil.Payload\"/>"));
        d.addResource(r);
        d.setNew(false);
        assertDoesNotThrow(() -> rule.deploy(d), "a stored deployment re-parsed into the cache keeps running");
        d.setNew(true);
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> rule.deploy(d));
        assertTrue(e.getMessage().startsWith("SL-160") && e.getMessage().contains(ModelCodeAllowList.CLASS_NOT_ALLOWED),
                e.getMessage());
    }

    @Test
    void theEngineRefusesAtDeployAndAcceptsReviewedCode() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sl160" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.getDeployAllowedClasses().put("t1", Set.of("com.dept.ReviewedDelegate"));
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> a.deploy("t1", "bad",
                    Map.of("bad.bpmn", model("<serviceTask id=\"work\" operaton:class=\"com.evil.Payload\"/>"))));
            Throwable t = e;
            while (t != null && (t.getMessage() == null || !t.getMessage().contains("SL160-CLASS"))) {
                t = t.getCause();
            }
            assertTrue(t != null, "refused with SL160-CLASS: " + e);
            assertEquals(0, a.processEngine().getRepositoryService().createDeploymentQuery().deploymentName("bad").count(),
                    "nothing of the refused deployment is stored");
            assertDoesNotThrow(() -> a.deploy("t1", "ok", Map.of("ok.bpmn",
                    model("<serviceTask id=\"work\" operaton:class=\"com.dept.ReviewedDelegate\"/>"))), "tenant-approved");
            assertDoesNotThrow(() -> a.deploy("t1", "own", Map.of("own.bpmn",
                    model("<serviceTask id=\"work\" operaton:class=\"org.sangam.packs.core.SetVariablesDelegate\"/>"))));
        }
    }
}
