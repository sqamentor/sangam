// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * M-059 end to end on a real engine: a model declaring handlers deploys, a broken declaration is refused at deploy
 * (L6 codes in the message), and the registry reads back what the parse listener checked.
 */
class InstanceHandlersH2Test {

    private static String model(String key, String extension) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:sangam="http://sangam.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <extensionElements>%s</extensionElements>
                    <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="scrutiny"/>
                    <userTask id="scrutiny"/>
                    <sequenceFlow id="f2" sourceRef="scrutiny" targetRef="e"/><endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(key, extension);
    }

    private static final String GOOD = """
            <sangam:query name="feeDue" expression="vars.fee - vars.paid"/>
            <sangam:update name="correctAddress" activities="scrutiny">
              <sangam:argument name="address" type="string" required="true"/>
              <sangam:set variable="address" expression="args.address"/>
            </sangam:update>
            """;

    private static OperatonEngineAdapter engine() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m059" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return SangamEngineFactory.build(ds, s, "h2");
    }

    private static Map<String, byte[]> resource(String key, String ext) {
        return Map.of(key + ".bpmn", model(key, ext).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void declaredHandlersDeployAndAreReadBackAndEvaluated() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "m059-" + UUID.randomUUID(), resource("licence", GOOD));
            String pid = a.startProcess("t1", "licence", "L-" + UUID.randomUUID(),
                    Map.of("fee", new BigDecimal("250.00"), "paid", new BigDecimal("100.00")));
            RepositoryService repo = a.processEngine().getRepositoryService();
            String defId = a.processEngine().getRuntimeService().createProcessInstanceQuery().processInstanceId(pid)
                    .singleResult().getProcessDefinitionId();

            InstanceHandlerRegistry registry = new InstanceHandlerRegistry(repo, new InstanceHandlerEvaluator());
            InstanceHandlers.Definitions d = registry.of(defId);
            assertEquals(java.util.Set.of("feeDue"), d.queries().keySet());
            assertEquals(java.util.Set.of("scrutiny"), d.updates().get("correctAddress").activities());
            assertSame(d, registry.of(defId), "a definition never changes, so it is cached");

            Map<String, Object> vars = a.processEngine().getRuntimeService().getVariables(pid);
            Object due = registry.evaluator().query(d.queries().get("feeDue"), vars, Map.of(), Map.of());
            // exact FEEL numbers: a whole result is answered as a Long, any other as a BigDecimal
            assertEquals(0, new BigDecimal("150.00").compareTo(new BigDecimal(String.valueOf(due))), String.valueOf(due));
        }
    }

    @Test
    void aModelWithoutHandlersIsUnaffected() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "m059-" + UUID.randomUUID(), resource("plain", ""));
            String defId = a.processEngine().getRepositoryService().createProcessDefinitionQuery()
                    .processDefinitionKey("plain").singleResult().getId();
            assertSame(InstanceHandlers.Definitions.NONE, new InstanceHandlerRegistry(
                    a.processEngine().getRepositoryService(), new InstanceHandlerEvaluator()).of(defId));
        }
    }

    @Test
    void brokenDeclarationsAreRefusedAtDeploy() {
        try (OperatonEngineAdapter a = engine()) {
            assertRefused(a, "<sangam:query name=\"q\" expression=\"execution.getId()\"/>", "L6-FEEL");
            assertRefused(a, "<sangam:query name=\"q\" expression=\"for x in vars.l return x\"/>", "L6-FEEL-UNBOUNDED");
            assertRefused(a, "<sangam:update name=\"u\" activities=\"nowhere\">"
                    + "<sangam:set variable=\"x\" expression=\"1\"/></sangam:update>", "L6-ACTIVITY");
            assertRefused(a, "<sangam:update name=\"u\"><sangam:set variable=\"sangamCaseVersion\" expression=\"1\"/>"
                    + "</sangam:update>", "L6-SET-RESERVED");
        }
    }

    private static void assertRefused(OperatonEngineAdapter a, String ext, String code) {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> a.deploy("t1", "m059-" + UUID.randomUUID(), resource("bad", ext)));
        String all = String.valueOf(e.getMessage()) + (e.getCause() == null ? "" : " " + e.getCause().getMessage());
        assertTrue(all.contains("M-059/L6") && all.contains(code), all);
    }
}
