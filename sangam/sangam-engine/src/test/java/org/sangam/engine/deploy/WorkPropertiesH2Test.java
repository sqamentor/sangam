// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * Decision 159 glue (W4-H REQUEST 20:15:17Z): fetched {@link EngineAdapter.ExternalWork} carries the activity's
 * extension properties and the definition and deployment ids; a model cannot spoof the adapter's own keys. The pinned
 * {@code sangamOperationRef} path needs an API contract store (OperationRefPinning) and is covered by the M-068 tests'
 * deployments, not here. Written, NOT_RUN.
 */
class WorkPropertiesH2Test {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="arz" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="wf.step1"/>
                <serviceTask id="wf.step1" operaton:type="external" operaton:topic="sangam.operation">
                  <extensionElements>
                    <operaton:properties>
                      <operaton:property name="arazzo.version" value="1.0.1"/>
                      <operaton:property name="arazzo.request" value="{&quot;method&quot;:&quot;GET&quot;}"/>
                      <operaton:property name="arazzo.outputs" value="{&quot;id&quot;:&quot;$response.body#/id&quot;}"/>
                      <operaton:property name="sangam.deploymentId" value="spoofed"/>
                    </operaton:properties>
                  </extensionElements>
                </serviceTask>
                <sequenceFlow id="f2" sourceRef="wf.step1" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    private OperatonEngineAdapter engine;

    @BeforeEach
    void start() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setApplyEnvironment(false);
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true"); // the engine creates its own tables in this test database
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:wp" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        engine = SangamEngineFactory.build(ds, s, "h2");
    }

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void fetchedWorkCarriesActivityPropertiesAndDefinitionIds() {
        String deploymentId = engine.deploy("t1", "arz", Map.of("arz.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
        engine.startProcess("t1", "arz", "bk-1", Map.of());
        List<EngineAdapter.ExternalWork> got = engine.fetchAndLock("w1", "sangam.operation", 1, Duration.ofMinutes(1));
        assertEquals(1, got.size());
        Map<String, String> p = got.get(0).properties();
        assertEquals("1.0.1", p.get("arazzo.version"));
        assertEquals("{\"method\":\"GET\"}", p.get("arazzo.request"));
        assertEquals("{\"id\":\"$response.body#/id\"}", p.get("arazzo.outputs"));
        assertEquals(deploymentId, p.get(WorkProperties.DEPLOYMENT_ID), "the adapter's key wins over a model property");
        String defId = engine.processEngine().getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionKey("arz").singleResult().getId();
        assertEquals(defId, p.get(WorkProperties.PROCESS_DEFINITION_ID));
        assertFalse(p.containsKey(SangamBpmnParseListener.OPERATION_REF_PROPERTY), "no operationRef on this activity");
        assertEquals("wf.step1", got.get(0).activityId(), "the step id is the part after the workflow id dot");
        assertThrows(UnsupportedOperationException.class, () -> p.put("x", "y"), "unmodifiable");
    }

    @Test
    void oldConstructorsGiveAnEmptyMap() {
        EngineAdapter.ExternalWork w = new EngineAdapter.ExternalWork("id", "topic", "pi", "act", "ai", 1,
                java.time.Instant.EPOCH, 1L, "att", Map.of());
        assertEquals(Map.of(), w.properties());
        EngineAdapter.ExternalWork n = new EngineAdapter.ExternalWork("id", "topic", "pi", "act", "ai", 1,
                java.time.Instant.EPOCH, 1L, "att", Map.of(), "op", null);
        assertEquals(Map.of(), n.properties(), "null becomes an empty map");
    }
}
