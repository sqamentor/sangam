// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * Decision 158 (M-126) through the process engine: a DMN 1.6 resource deploys, its decision evaluates as FEEL, the
 * repository keeps the author's bytes unchanged, a cache rebuild (as after a restart) parses it again, a business rule
 * task calls it, and ONNX content refuses the whole deployment. H2 only; Oracle and SQL Server NOT_RUN (no dialect
 * dependency: parsing happens before any SQL). Written, NOT_RUN.
 */
class Dmn16DeploymentH2Test {

    private static OperatonEngineAdapter engine;

    private static final String DMN16 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20240513/MODEL/" id="fees16" name="fees16"
                         namespace="https://example.org/fees16" expressionLanguage="https://www.omg.org/spec/DMN/20240513/FEEL/">
              <decision id="fee16" name="fee16">
                <decisionTable id="t" hitPolicy="FIRST">
                  <input id="i"><inputExpression id="ie" typeRef="number"><text>amount</text></inputExpression></input>
                  <output id="o" name="fee" typeRef="number"/>
                  <rule id="r1"><inputEntry id="r1i"><text>&lt; 1000</text></inputEntry><outputEntry id="r1o"><text>amount * 0.015</text></outputEntry></rule>
                  <rule id="r2"><inputEntry id="r2i"><text>&gt;= 1000</text></inputEntry><outputEntry id="r2o"><text>15.5</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>
            """.strip();

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="fee16Process" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="brt"/>
                <businessRuleTask id="brt" operaton:decisionRef="fee16" operaton:decisionRefBinding="deployment"
                                  operaton:mapDecisionResult="singleEntry" operaton:resultVariable="fee"/>
                <sequenceFlow id="f2" sourceRef="brt" targetRef="w"/>
                <userTask id="w"/>
                <sequenceFlow id="f3" sourceRef="w" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """.strip();

    private static String deploymentId;

    @BeforeAll
    static void start() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dmn16;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        settings.setAllowMultipleEnginesPerJvm(true);
        settings.setEngineSchemaUpdate("true"); // empty in-memory database owned by this test
        engine = SangamEngineFactory.build(ds, settings, "h2", List.of());
        deploymentId = engine.deploy("t1", "dmn16", Map.of("fees16.dmn", DMN16.getBytes(StandardCharsets.UTF_8),
                "fee16.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
    }

    @AfterAll
    static void stop() {
        if (engine != null) {
            engine.close();
        }
        // R9-statics: a test class stays loaded for the whole run; its statics must not pin what they held
        engine = null;
    }

    private static Object evaluate(Object amount) {
        return engine.processEngine().getDecisionService().evaluateDecisionByKey("fee16")
                .decisionDefinitionTenantId("t1").variables(Map.of("amount", amount)).evaluate().getSingleEntry();
    }

    @Test
    void dmn16DecisionDeploysAndEvaluates() {
        assertEquals(1, engine.processEngine().getRepositoryService().createDecisionDefinitionQuery()
                .decisionDefinitionKey("fee16").tenantIdIn("t1").count());
        assertEquals(0, new BigDecimal("1.5").compareTo(new BigDecimal(evaluate(100L).toString())));
        assertEquals(0, new BigDecimal("15.5").compareTo(new BigDecimal(evaluate(5000L).toString())));
    }

    @Test
    void repositoryKeepsTheAuthorsDmn16Bytes() throws Exception {
        try (InputStream in = engine.processEngine().getRepositoryService().getResourceAsStream(deploymentId,
                "fees16.dmn")) {
            assertArrayEquals(DMN16.getBytes(StandardCharsets.UTF_8), in.readAllBytes(),
                    "the conversion happens in memory only");
        }
    }

    @Test
    void cacheRebuildParsesTheDmn16ResourceAgain() {
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) engine.processEngine()
                .getProcessEngineConfiguration();
        cfg.getDeploymentCache().discardDecisionDefinitionCache();
        cfg.getDeploymentCache().discardDecisionRequirementsDefinitionCache();
        assertEquals(0, new BigDecimal("1.5").compareTo(new BigDecimal(evaluate(100L).toString())),
                "the decision is parsed again from the stored 1.6 bytes, as after a restart");
    }

    @Test
    void businessRuleTaskCallsTheDmn16Decision() {
        String pi = engine.processEngine().getRuntimeService().createProcessInstanceByKey("fee16Process")
                .processDefinitionTenantId("t1").setVariable("amount", 200L).execute().getId();
        Object fee = engine.processEngine().getRuntimeService().getVariable(pi, "fee");
        assertEquals(0, new BigDecimal("3").compareTo(new BigDecimal(fee.toString())), "200 * 0.015 = 3");
    }

    @Test
    void onnxContentRefusesTheDeployment() {
        String onnx = DMN16.replace("<decision id=\"fee16\" name=\"fee16\">",
                "<decision id=\"onnx16\" name=\"onnx16\"><functionDefinition id=\"fx\" kind=\"ONNX\"><context id=\"cx\"/>"
                        + "</functionDefinition></decision><decision id=\"fee16b\" name=\"fee16b\">")
                .replace("id=\"fees16\"", "id=\"fees16b\"");
        RuntimeException e = assertThrows(RuntimeException.class, () -> engine.deploy("t1", "dmn16-onnx",
                Map.of("onnx16.dmn", onnx.getBytes(StandardCharsets.UTF_8))));
        StringBuilder chain = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append(" | ");
        }
        assertTrue(chain.toString().contains(org.sangam.feel.Dmn16Support.CODE_ONNX), chain.toString());
        assertEquals(0, engine.processEngine().getRepositoryService().createDecisionDefinitionQuery()
                .decisionDefinitionKey("fee16b").count(), "nothing of the refused deployment is stored");
    }
}
